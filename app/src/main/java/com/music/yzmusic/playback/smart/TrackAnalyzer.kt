/*
 * Modeled on Orchard's own TrackAnalyzer (https://github.com/SFG5453/Orchard).
 * Phase 1 was the DSP-only pass (native/analyzer/audio_analysis.cpp); Phase 2
 * adds the Beat This! ONNX model (see [BeatTracker]) and Phase 3 the
 * open-unmix vocal mask (see [VocalTracker]), both over the head and tail of
 * the track, which is the only part a transition ever reads.
 *
 * Copyright (C) 2026 Kushagra Singh
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or (at your
 * option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General
 * Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package com.music.yzmusic.playback.smart

import android.content.Context
import android.media.MediaDataSource
import android.net.Uri
import android.util.Log
import androidx.media3.common.util.UnstableApi
import com.music.yzmusic.playback.AudioCache
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import java.util.Locale

/**
 * Produces [TrackAnalysis] for tracks that are about to be mixed, and hands it
 * to [TransitionPlanner].
 *
 * [analysisFor] is called from the crossfade watcher every tick, so it never
 * blocks or computes: it returns what is already known, and an unanalysed
 * track simply reads as no evidence, which the policy ladder answers with a
 * plain fade.
 */
@UnstableApi
class TrackAnalyzer(private val context: Context, private val cache: AudioCache) {

    private val tracker = BeatTracker(context)
    private val vocals = VocalTracker(context)

    /**
     * Resolved on first use, not at construction, for the reason [AnalysisStore]
     * documents about its own directory: this class is a field initializer on
     * the playback service, which runs before the service has a base context
     * attached, and asking a context for anything there returns null.
     */
    private val resolver by lazy { context.contentResolver }

    private val results = ConcurrentHashMap<String, TrackAnalysis>()
    private val running = ConcurrentHashMap.newKeySet<String>()

    /** Tracks whose result came from [analyzeHead] and is waiting to be superseded. */
    private val provisional = ConcurrentHashMap.newKeySet<String>()

    /**
     * Cached prefix size, in bytes, at the last head attempt on each
     * *rendition*. See [headWorthTrying].
     *
     * Keyed by rendition rather than by track because the growth guard is
     * asking "have I already decoded roughly this much of this copy", and a
     * track can move between copies: a first attempt on a half-cached lossless
     * rendition recorded six megabytes, and a much lighter Opus head arriving
     * afterwards — the one that would actually have produced a result — was then
     * refused for being smaller than a number belonging to a different file.
     */
    private val headAttempts = ConcurrentHashMap<String, Long>()

    /**
     * Track-and-rendition pairs that have already reported waiting for a head,
     * so the tick doesn't spam. Not keyed by track alone: a track that gains a
     * second copy of itself is in a genuinely new situation, and the first
     * version of this hid exactly that.
     */
    private val headSkipLogged = ConcurrentHashMap.newKeySet<String>()

    /**
     * How many times each track's whole-track pass has been refused for
     * decoding short. Counted rather than flagged so a rendition still filling
     * in its holes gets a few more chances, while one that is genuinely
     * truncated stops being re-decoded on every tick.
     */
    private val shortDecodes = ConcurrentHashMap<String, Int>()

    /** Tracks already looked for on disk this session; see [restoreOnce]. */
    private val restoreAttempted = ConcurrentHashMap.newKeySet<String>()

    /** Results that survive the process, so a track is measured once and stays measured. */
    private val store = AnalysisStore(context)

    /**
     * Cache keys of renditions that decoded short despite the cache calling them
     * complete. Keyed by rendition rather than by track, because the point is to
     * send the next attempt at the *same* track to a different copy of it.
     */
    private val badRenditions = ConcurrentHashMap.newKeySet<String>()

    /**
     * Cache keys already put through the whole-track pass, whatever came of it.
     *
     * What reopens a track that was written off: a copy of it nobody has read
     * yet. Without this the write-off is final for the session, and a rendition
     * that arrives seconds later — a quality upgrade, or the head fetch the
     * analyzer itself asked for — is never looked at. Measured, a track was
     * given up on at 22:09:24 and its `#hifi` copy finished downloading at
     * 22:09:41.
     */
    private val triedRenditions = ConcurrentHashMap.newKeySet<String>()

    /**
     * How many times each rendition has been thrown off disk for being
     * undecodable, so a clean slate stays a one-off.
     *
     * The refetch that follows a discard is not guaranteed to be any better —
     * a source can serve the same broken file twice — and without a bound the
     * two halves feed each other: refuse, delete, refetch, refuse, delete, on a
     * 250ms tick, spending the listener's data in a loop. One clean-slate retry
     * is enough for the case this exists for, which is an entry spliced from two
     * different encodings and unrecoverable only because nothing would ever
     * overwrite it.
     */
    private val discarded = ConcurrentHashMap<String, Int>()

    private fun discardsOf(key: String): Int = discarded[key] ?: 0

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "bitchord-smart-analysis").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY
        }
    }

    /**
     * What is known about [trackId] right now: never a computation, never a
     * block. Returns an empty analysis for anything not yet finished, which
     * [assessTransitionTier] reads as no evidence rather than as a failure.
     */
    fun analysisFor(trackId: String): TrackAnalysis = results[trackId] ?: TrackAnalysis(trackId = trackId)

    /**
     * Looks [trackId] up on disk, once, off the playback thread.
     *
     * Deliberately not folded into [analysisFor], which is called several times
     * per tick from the playback thread and must never touch the filesystem.
     * The result lands in [results] a tick or two later, which is immaterial
     * against the seconds a real analysis takes — and against the alternative,
     * which is not having it at all.
     */
    private fun restoreOnce(trackId: String) {
        if (results.containsKey(trackId)) return
        // The set doubles as the once-guard: add() is true only for the first
        // caller, so a track with nothing stored is looked for once per session
        // rather than on every tick.
        if (!restoreAttempted.add(trackId)) return
        executor.execute {
            val stored = store.load(trackId) ?: return@execute
            Log.d(TAG, "Restored analysis for $trackId: bpm=${stored.bpm} conf=${stored.beatConfidence}")
            results.putIfAbsent(trackId, stored)
        }
    }

    /** True once [trackId] has a result, including a failure. Nothing more will arrive. */
    fun isAnalysed(trackId: String): Boolean = results.containsKey(trackId)

    /**
     * True while a decode and inference for [trackId] is actually in flight.
     *
     * Distinct from "not analysed": a track waiting on bytes and a track being
     * worked on right now are the same absence of a result, and the difference
     * is the difference between something being wrong and something simply
     * taking the several seconds it takes.
     */
    fun isAnalysing(trackId: String): Boolean = trackId in running

    /**
     * Queues [trackId] (playing at [uri]) for analysis if it is not already
     * done or in flight. Cheap to call repeatedly; callers re-request as
     * caching progresses.
     *
     * Runs in up to two passes, because waiting for a full cache is what kept
     * the *incoming* track of every transition unanalysed. A track only
     * finishes downloading once it is already playing, so the whole-track pass
     * lands in time to describe a track's own mix-out and never in time to
     * describe its entry — which is the half the listener hears at the moment
     * of the blend.
     *
     * So a track with enough of a head on disk gets [analyzeHead] first: beat
     * grid only, over the opening window, which is all the incoming side is
     * read for. That result is provisional and is replaced by the whole-track
     * [analyze] as soon as the remaining bytes arrive.
     *
     * None of that applies to a track playing off the device, which goes
     * straight to [analyze] on the first tick that reaches it — there is nothing
     * to wait for and nothing to escalate through. See [LocalAudioSource] for
     * why that needed saying at all.
     */
    fun request(trackId: String, uri: Uri, durationSeconds: Double) {
        if (trackId.isBlank()) return
        if (trackId in running) return

        // Any complete rendition of this recording will do, not just the one the
        // player happens to be on: see [chooseRendition]. Waiting on the live
        // URI is what made analysis arrive after the transition that needed it.
        // Queued before the cache is even consulted, so a track measured in an
        // earlier session short-circuits the whole path rather than being
        // re-earned from audio the cache may since have evicted.
        restoreOnce(trackId)

        // A track playing off the device is not a download in progress. Every
        // byte of it is already there, and none of those bytes are in the cache:
        // local URIs are routed past it rather than written into it a second
        // time. So everything below answers "nothing on disk" for a track that
        // is entirely on disk, and the head fetch that answer falls back to is a
        // no-op for anything without a YouTube id — which is why a local file
        // was never queued for analysis at all. See [LocalAudioSource].
        val local = LocalAudioSource.isLocal(uri)

        // Complete *and* not already written off. A copy that decoded to
        // nothing is not a copy worth routing to: counting it as "fully cached"
        // sent this down the whole-track path on every tick with nothing left
        // for that path to read, and each of those empty passes was then
        // counted as a failed decode.
        //
        // Not asked of a local file, whose bytes the cache has never seen and
        // whose completeness is not a question: it is whole from the first tick.
        val complete = if (local) {
            emptyList()
        } else {
            cache.renditionsOf(uri).filter { it.isComplete && it.key !in badRenditions }
        }
        val usableComplete = local || complete.isNotEmpty()

        val recorded = results[trackId]
        // Two things are worth superseding, and nothing else is. A provisional
        // head result, because replacing it with the whole-track pass is the
        // entire point of it — and a recorded failure, but only once a copy of
        // the track nobody has read yet turns up. Re-deciding a failure against
        // the same renditions that produced it would just spend the decode
        // again for the same answer.
        //
        // A local file is its own single copy, keyed by URI — see [copyToRead] —
        // so a failure there is read once and stays read, which is correct: no
        // second copy of it is ever going to arrive.
        val untried = if (local) {
            uri.toString() !in triedRenditions
        } else {
            complete.any { it.key !in triedRenditions }
        }
        val supersedable = when {
            recorded == null -> false
            trackId in provisional -> usableComplete
            else -> !recorded.isUsable && untried
        }
        if (recorded != null && !supersedable) {
            // One exception to returning empty-handed. A provisional result is a
            // placeholder, not an answer — it says the opening decoded, not that
            // the track is measured — and the thing that supersedes it is bytes.
            // Without this nudge the byte escalation stops at whatever the first
            // successful head happened to cost, nothing else ever asks for the
            // rest, and a queued track reaches its own transition carrying an
            // entry-only estimate: no content end, no mix-out anchor, no vocal
            // mask, which is most of what the outgoing half of a blend reads.
            if (trackId in provisional && !usableComplete) cache.requestAnalysisHead(uri)
            return
        }
        // The strike count belongs to the attempt that was given up on, not to
        // the track for the rest of the session; a reopened track starts level.
        if (recorded != null && !recorded.isUsable) shortDecodes.remove(trackId)
        // One head attempt per track: a partial container that will not parse
        // now is unlikely to parse ten ticks later, and retrying a decode every
        // 250ms would cost more than the analysis it is trying to bring
        // forward. Skipped entirely for a local file, which is `usableComplete`
        // from the first tick: the head pass exists to get ahead of a download,
        // and there is no download to get ahead of.
        val headRendition = if (usableComplete) null else headWorthTrying(trackId, uri, durationSeconds)
        if (!usableComplete && headRendition == null) {
            // Nothing on disk worth decoding, so ask for something. Every other
            // writer either fetches this track's opening too late to matter or
            // never fetches it at all — see [AudioCache.requestAnalysisHead],
            // which is a no-op after the first call and for anything that isn't
            // a YouTube-backed track.
            cache.requestAnalysisHead(uri)
            return
        }
        if (!running.add(trackId)) return

        executor.execute {
            try {
                // [restoreOnce] queues onto this same single-threaded executor,
                // so a stored result for this track has landed by now if there
                // was one — but the decision to get here was taken a tick
                // earlier, when it had not. Without this check a track measured
                // in an earlier session is restored and then immediately spends
                // seven seconds recomputing the identical numbers. A provisional
                // result is exempt: superseding one is the whole point of it.
