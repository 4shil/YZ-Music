package com.music.yzmusic.playback

import android.os.SystemClock
import com.music.yzmusic.data.sources.SourceStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Which copy of a track is being served, remembered for as long as the track
 * is being served from it.
 *
 * ExoPlayer opens a source many times over one play: the initial read, a seek
 * past the buffer, a resume, and — the one that matters here — the
 * continuation fetch when playback reaches the end of what the cache holds.
 * Every one of those goes back through the resolving data source, and until
 * this existed, every one of them was free to come back with a *different*
 * answer.
 *
 * That is not a hypothetical. Measured on this device: a track started on
 * YouTube with 57 seconds pre-buffered, played to the end of those 57 seconds,
 * and its continuation fetch resolved — correctly, by its own lights — to a
 * module's 320kbps AAC instead. The player was handed the middle of an MP4
 * where it expected the rest of a WebM, and `MatroskaExtractor` threw
 * `EOFException`:
 *
 * ```
 *   12:09:04  first audio          (YouTube Opus, buffered position 57761)
 *   12:10:12  Source error
 *             Caused by: java.io.EOFException
 *               at MatroskaExtractor.read
 * ```
 *
 * Two sources cannot share one cache entry, and [AudioCache]'s key factory
 * cannot tell them apart: it runs *before* the resolve, off the
 * `yzmusic://watch?v=…` URI, and at that point which server will answer is
 * not yet known. So the fix goes the other way round — the entry does not
 * learn who is filling it, the resolver is held to whoever filled it first.
 *
 * [QualityUpgrade] already worked this way for the streams it swaps in, for
 * exactly the same reason, and the reasoning simply hadn't been carried back
 * to the first resolve.
 *
 * ### Why a time limit
 *
 * A module's stream URL is signed and expires — around five hours, on the
 * catalogues in use here. Holding one indefinitely would eventually hand the
 * player a dead URL rather than a wrong one, which is no better. [TTL_MS] is
 * far inside every expiry seen, so a choice is either reused while it is
 * certainly still good or made again from scratch.
 */
object StreamChoice {

    private class Choice(val stream: SourceStream, val at: Long, val substituted: Boolean)

    private val chosen = ConcurrentHashMap<String, Choice>()

    /**
     * The stream already serving [videoId], or null if this is the first read
     * for it — or the last one was long enough ago that its URL is no longer
     * worth trusting.
     */
    fun of(videoId: String): SourceStream? {
        val choice = chosen[videoId] ?: return null
        if (SystemClock.elapsedRealtime() - choice.at > TTL_MS) {
            chosen.remove(videoId)
            return null
        }
        return choice.stream
    }

    /**
     * Records [stream] as the one copy of [videoId] this play is reading.
     *
     * @param substituted whether this came from a source standing in for
     *   YouTube rather than from YouTube itself. Only the resolver knows, and
     *   only [refuseSubstitutes] needs it — a substitution that turns out to be
     *   unplayable has somewhere else to fall back to, and a YouTube stream
     *   that fails has not.
     */
    fun remember(videoId: String, stream: SourceStream, substituted: Boolean) {
        if (chosen.size >= MAX_REMEMBERED) {
            // Drop what can no longer be honoured, and only then the oldest of
            // what can. This used to `clear()`, which is the one eviction
            // capable of causing the corruption this class exists to prevent:
            // every live entry is a promise that a half-filled cache entry will
            // be finished by the stream that started filling it, and emptying
            // the map frees *all* of those entries to be finished by a
            // different server instead.
            //
            // Harmless while only playback wrote here — an entry was made and
            // consumed within one track. Read-ahead now pins the next track
            // before it is reached and caches its bytes on the strength of that
            // pin, so a promise can outlive several other tracks' worth of
            // entries, and the blunt version became reachable.
            val now = SystemClock.elapsedRealtime()
            chosen.entries.removeIf { now - it.value.at > TTL_MS }
            if (chosen.size >= MAX_REMEMBERED) {
                chosen.minByOrNull { it.value.at }?.let { chosen.remove(it.key) }
            }
        }
        chosen[videoId] = Choice(stream, SystemClock.elapsedRealtime(), substituted)
    }

    /**
     * Whether the copy currently serving [videoId] came from a source standing
     * in for YouTube.
     *
     * Read on the recovery path, before [forget], to tell a track that died on
     * a substitution from one that died on YouTube's own stream. The two have
     * opposite right answers: the first should stop substituting, the second
     * has nothing better to try.
     */
