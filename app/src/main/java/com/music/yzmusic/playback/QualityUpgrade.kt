package com.music.yzmusic.playback

import android.net.Uri
import android.util.Log
import com.music.yzmusic.data.TrackLog
import com.music.yzmusic.data.NerdStats
import com.music.yzmusic.data.sources.SourceResolver
import com.music.yzmusic.data.sources.SourceStream
import com.music.yzmusic.data.sources.StreamFormat
import com.music.yzmusic.data.sources.TrackMatcher
import kotlinx.coroutines.Deferred
import java.util.concurrent.ConcurrentHashMap

/**
 * The second look: a track that started on less than was asked for gets the
 * question asked again, properly, while it plays.
 *
 * The live path has to answer in the time a listener will wait for a track to
 * start, and it buys that by giving up on the slow catalogue — which is
 * regularly the one holding the lossless copy. Measured on this device: the
 * fastest module answered a search in 1.3s and the slowest in 5.4s, and the
 * slow one then took a further 8.2s to walk its own fallback chain down to a
 * 128kbps MP3. Waiting for all of that costs 14 seconds of silence; not
 * waiting costs the FLAC. Neither is a good trade, and neither has to be made
 * once the search can happen with sound already coming out of the speaker.
 *
 * So: play whatever can be had now, then look again with no time limit, and
 * swap only if the answer is genuinely the same recording *and* genuinely
 * better than what is playing — which is usually the lossless copy that was
 * asked for, but is also a 320kbps module stream against YouTube's 160kbps
 * Opus. See [SourceResolver.worthSwapping] for where that line is drawn.
 * The swap is not free — ExoPlayer cannot change sources
 * gaplessly mid-track, so there is a short break in the audio — which is why
 * every guard here errs towards not doing it. A missed upgrade is a quieter
 * failure than an interrupted song.
 *
 * ### How the swap reaches the player
 *
 * The queue holds `yzmusic://watch?v=…` URIs that
 * [PlaybackService][PlaybackService]'s resolving data source turns into real
 * URLs at open time. An upgrade re-points that indirection rather than
 * touching the queue: the stream is parked in [forced], the item is replaced
 * with the same URI plus a `q=` marker, and the marker does two jobs — it
 * makes the item unequal to its old self so Media3 actually rebuilds the media
 * source, and it keys the disk cache separately so the FLAC is not written
 * into the middle of the half-cached MP3 it is replacing.
 */
object QualityUpgrade {

    private const val TAG = "YZ Music"

    /** The marker that distinguishes an upgraded item from the one it replaced. */
    const val MARKER = "q"
    private const val UPGRADED = "hifi"

    /**
     * A track playing on less than was asked for.
     *
     * [inFlight] is the live lookup that lost the race rather than ran out of
     * answers — still running, and worth waiting on rather than repeating,
     * because whatever it returns is precisely the stream that would have
     * played had it been quicker. Null once the live path has finished and
     * come back with nothing better; the second look then has to go find its
     * own candidates.
     */
    private data class Pending(
        val target: TrackMatcher.Target,
        val inFlight: Deferred<SourceStream?>? = null,
        /**
         * What the listener is actually hearing — the yardstick a lossy
         * candidate is measured against in [SourceResolver.worthSwapping].
         * Known by the time a track is marked pending: whichever stream won
         * the race has already named its format, and a track adopted from the
         * cache without a race has one measured for it — see
         * [adoptUnresolved]. Null only when neither could, and an unknown
         * floor is one nothing lossy clears.
         */
        val playing: StreamFormat? = null,
    )

    private val pending = ConcurrentHashMap<String, Pending>()
    private val forced = ConcurrentHashMap<String, SourceStream>()

    /**
     * Tracks whose upgraded stream is being *proved* rather than played — see
     * [PlaybackService][com.music.yzmusic.playback.PlaybackService]'s
     * audition.
     *
     * An audition reaches its bytes through the same resolving data source the
     * real player does, which is where [forcedStream] hands over the URL and
     * where the format it promises is recorded for "stats for nerds". That
     * recording is right for a stream being played and wrong for one being
     * tried out: for the length of an audition the listener is still hearing
     * the old stream, and a badge that reads "Lossless" over it is describing
     * a swap that has not happened and might never.
     */
    private val auditioning = java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    fun beginAudition(mediaId: String) {
        auditioning += mediaId
    }

    fun endAudition(mediaId: String) {
        auditioning -= mediaId
    }

    /** Whether the fetch about to happen for [videoId] is an audition, not playback. */
    fun isAuditioning(videoId: String?): Boolean = videoId != null && videoId in auditioning

    /**
     * Upgrades that were found, proved and cached, and then never got to
     * happen because the queue moved on in the last moments before the swap.
     *
     * Everything expensive about an upgrade is already spent by that point —
     * the catalogue search, the audition, the megabytes on disk — and all of it
     * was being thrown away over a quarter of a second of timing. Measured: a
     * FLAC found in 10.1s, proved in 2.0s and cached in full, discarded because
     * the listener skipped 254ms before the swap; skipping straight back to the
     * track could not use any of it.
     *
     * Held against exactly that — the listener coming back. The stream stays in
     * [forced] and its bytes stay under the rendition key, so the swap that
     * follows is the cheap kind: no search, no download, and an audition that
     * reads from disk.
     */
    private val shelved = ConcurrentHashMap<String, SourceStream>()

    /** Keeps a proved-but-unused upgrade for [mediaId] against a return visit. */
