package com.music.yzmusic.playback

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheEvictor
import androidx.media3.datasource.cache.CacheSpan
import java.util.TreeSet

/**
 * [androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor], except the
 * ceiling is a `var` rather than baked into the constructor.
 *
 * [SimpleCache] can only be opened once per process, so a settings change
 * can't just build a new one with a bigger evictor — this is what lets the
 * limit move without tearing the cache down and reopening it, which would
 * orphan the [CacheDataSource][androidx.media3.datasource.cache.CacheDataSource]
 * the player already holds a reference to.
 *
 * ## Why the opening of a track is treated differently
 *
 * Plain LRU has a pathology here that took a while to see. A track's first
 * bytes are read once, when playback starts, and never touched again; its tail
 * is read continuously as the listener keeps listening. So the head is always
 * the oldest span a track owns, and plain LRU always evicts it first. Measured
 * on the reference device with a full cache: 48 of 170 spans still began at
 * byte 0 — most tracks were sitting on disk almost complete, with their
 * openings gone.
 *
 * That is invisible for playback, which re-fetches whatever it needs. It is
 * fatal for Automix, whose analyzer can only decode from the start of a
 * container: a track holding 95% of itself but nothing at byte 0 reads as
 * entirely uncached, and is silently never analysed. Worse, with the cache at
 * its ceiling a track downloading its own tail could evict its own head while
 * still playing.
 *
 * So head spans are held back from eviction, up to [headBudgetBytes] in total.
 * The budget matters: heads are never re-read, so nothing would ever age them
 * out, and an unbounded exemption would fill the cache with openings. Past the
 * budget the oldest protected head is released back to ordinary LRU, which is
 * both a bound and roughly the right policy — the tracks you played longest ago
 * are the ones least likely to come round again.
 */
@UnstableApi
class DynamicLruCacheEvictor(
    @Volatile var maxBytes: Long,
    /**
     * How far into a track counts as its "head" — the region the analyzer needs
     * contiguously from zero. Sized for lossless, where twelve seconds of audio
     * is a few megabytes; a compressed rendition's head fits many times over.
     */
    private val headBytes: Long = DEFAULT_HEAD_BYTES,
    /** Ceiling on the total held back. See the class doc. */
    private val headBudgetBytes: Long = DEFAULT_HEAD_BUDGET_BYTES,
) : CacheEvictor {

    private val leastRecentlyUsed = TreeSet<CacheSpan>(::compare)

    /**
     * Head spans exempt from eviction, oldest first. Ordered by the same clock
     * as [leastRecentlyUsed] so that releasing one back to ordinary LRU picks
     * the least useful.
     */
    private val protectedHeads = TreeSet<CacheSpan>(::compare)
    private var protectedSize = 0L
    private var currentSize = 0L

    /** A span is a head if it starts at the very beginning, or within [headBytes] of it. */
