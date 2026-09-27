package com.music.yzmusic.playback

import android.os.SystemClock
import androidx.media3.common.Player
import com.music.yzmusic.data.DebugLog as Log
import com.music.yzmusic.data.listentogether.ListenTogether
import com.music.yzmusic.data.listentogether.PartyTrack
import com.music.yzmusic.data.model.Song
import com.music.yzmusic.data.sources.TrackMatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * Makes the player obey the party, and the party obey this player.
 *
 * Lives in [PlaybackService] rather than in the UI on purpose: a party has to
 * survive the app being backgrounded and the screen going off, which is most of
 * what listening together actually looks like. Bound to a composable it would
 * desync the moment somebody put their phone down.
 *
 * ## Telling the two directions apart
 *
 * The hard part of this is not moving the playhead, it is knowing *who moved
 * it*. A naive binder that reacts to player events and republishes them ends up
 * echoing its own corrections around the party forever. This one never has to
 * guess, because the two directions arrive through physically different doors:
 *
 *  - **Outbound** is [onLocalIntent], called only from [PlaybackService]'s
 *    `SessionPlayer` — the [androidx.media3.common.ForwardingPlayer] every
 *    *user* action passes through, wherever it came from: the app, the
 *    notification, a headset button, Android Auto. Plus the one thing that is
 *    the user's intent without being their action, the queue moving on by
 *    itself at the end of a track.
 *  - **Inbound** is [reconcile], which writes straight to the ExoPlayer,
 *    underneath that wrapper. So nothing this class does to the player can ever
 *    come back to it as an intent.
 */
class PartySync(
    private val scope: CoroutineScope,
    /** Read fresh every time: the service swaps players at a crossfade. */
    private val player: () -> Player?,
) {

    private val jobs = mutableListOf<Job>()
    private var publishJob: Job? = null
    private var startJob: Job? = null

    /**
     * Until when [reconcile] should keep its hands off.
     */
    private var reconcileQuietUntilMs = 0L

    /**
     * The party seq at which this device's own controls will have all landed.
     */
    private var awaitPlaybackSeq = Long.MAX_VALUE
    private var awaitQueueSeq = Long.MAX_VALUE

    /** Tracks last seen playback anchor properties to differentiate playback controls from queue updates. */
    private var lastAnchorMs = 0L
    private var lastPositionMs = 0L
    private var lastTrackId: String? = null
    private var lastIsPlaying = false

    /** Guards against re-issuing a load for a track already being loaded. */
    private var loadingVideoId: String? = null

    /**
     * The last party control this device has put itself exactly on.
     */
    private var alignedSeq = -1L

    /**
     * A resume this device has accepted but not yet performed.
     */
    private var deferredPlayPending: Boolean
        get() = ListenTogether.awaitingStart.value
        set(value) = ListenTogether.setAwaitingStart(value)

    /**
     * This device has been silenced by something its user did not ask for.
     */
    @Volatile
    private var focusLost = false

    /**
     * The user has asked to come back after [focusLost], and this device is
     * behind by however long it was away.
     */
    @Volatile
    private var rejoining = false

    /** Consecutive over-limit readings. See the drift branch of [reconcile]. */
    private var driftStrikes = 0

    /** When the player may next be seeked for drift, having just been. */
    private var driftCooldownUntilMs = 0L

    private var lastPartyCode: String? = null

    /**
     * Set when a listener in a host-only party presses pause locally.
     */
    private var locallyPaused = false

    fun start() {
        jobs += scope.launch {
            ListenTogether.state
                .map { ReconcileKey(it.playback.seq, it.queue.seq, it.code, it.clockSynced) }
                .distinctUntilChanged()
                .collect { key ->
                    if (key.code != lastPartyCode) {
                        val wasInParty = lastPartyCode != null
                        val nowInParty = key.code != null
                        lastPartyCode = key.code
                        if (!wasInParty && nowInParty) {
                            onEnteredParty()
                        } else if (wasInParty && !nowInParty) {
                            onLeftParty()
                        }
                    }
                    if (key.seq >= awaitPlaybackSeq && key.queueSeq >= awaitQueueSeq) {
                        reconcileQuietUntilMs = 0L
                    }
                    reconcile()
                }
        }
        jobs += scope.launch {
            while (true) {
                delay(TICK_MS)
                if (ListenTogether.state.value.inParty) ListenTogether.ensureConnected()
                reconcile()
            }
        }
    }

    fun stop() {
        jobs.forEach(Job::cancel)
        jobs.clear()
        publishJob?.cancel()
        startJob?.cancel()
    }

    /**
     * The user did something to playback on this device — or the queue moved on
     * by itself, which is the same thing as far as the party is concerned.
     */
    fun onLocalIntent() {
        val party = ListenTogether.state.value
        if (!party.inParty) return
        if (focusLost) {
            Log.i(TAG, "rejoining the party after losing the audio")
            focusLost = false
            rejoining = true
        }
        reconcileQuietUntilMs = SystemClock.elapsedRealtime() + INTENT_QUIET_MS
        awaitPlaybackSeq = Long.MAX_VALUE
        awaitQueueSeq = Long.MAX_VALUE
        publishJob?.cancel()
        publishJob = scope.launch {
            delay(PUBLISH_DEBOUNCE_MS)
            publish()
        }
    }

    /**
     * The player's `playWhenReady` moved, and why.
     */
    fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        if (playWhenReady) {
            focusLost = false
            return
        }
        if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS &&
            ListenTogether.state.value.inParty
        ) {
            Log.i(TAG, "another app took the audio; dropping out of the party until asked back")
            focusLost = true
            deferredPlayPending = false
            startJob?.cancel()
        }
    }

    /**
     * Play or pause for a listener whose party is locked to its host.
     * Returns true when it has handled the event locally.
     */
    fun onLockedTransport(playing: Boolean): Boolean {
        val party = ListenTogether.state.value
        if (!party.controlsLocked) return false
        val exo = player() ?: return false
        if (playing) {
            locallyPaused = false
            if (!party.playback.isPlaying) return true
            ListenTogether.partyPositionMs()
                ?.takeIf { party.clockSynced }
                ?.let(exo::seekTo)
            exo.play()
        } else {
            locallyPaused = true
            exo.pause()
        }
        return true
    }

    private fun clearLocalPauseIfFreed(party: ListenTogether.State) {
        if (!locallyPaused) return
        if (!party.controlsLocked) locallyPaused = false
    }

    /**
     * Whether a `play()` from this device should be held back for the party.
     */
    fun shouldDeferPlay(): Boolean {
        val party = ListenTogether.state.value
        if (!party.inParty || party.connection != ListenTogether.Connection.LIVE) return false
        if (!party.clockSynced) return false
        if (player()?.playWhenReady == true) return false
        deferredPlayPending = true
        deferredPlayFallback()
        return true
    }

    private fun deferredPlayFallback() {
        startJob?.cancel()
        startJob = scope.launch {
            delay(DEFERRED_PLAY_TIMEOUT_MS)
            if (!deferredPlayPending) return@launch
            deferredPlayPending = false
            val exo = player() ?: return@launch
            if (!exo.playWhenReady) {
                Log.w(TAG, "party never acknowledged the resume; starting locally")
                exo.play()
            }
        }
    }

    // ------------------------------------------------------------ inbound --

    private fun reconcile() {
        val party = ListenTogether.state.value
        if (!party.inParty) {
            loadingVideoId = null
            focusLost = false
            rejoining = false
            deferredPlayPending = false
            locallyPaused = false
            lastAnchorMs = 0L
            lastPositionMs = 0L
            lastTrackId = null
            lastIsPlaying = false
            return
        }

        clearLocalPauseIfFreed(party)
        if (SystemClock.elapsedRealtime() < reconcileQuietUntilMs) return
        if (focusLost) return

        val target = party.playback
        val track = target.track ?: run {
            seedEmptyParty(party)
            return
        }
        val exo = player() ?: return

        if (exo.playbackSuppressionReason != Player.PLAYBACK_SUPPRESSION_REASON_NONE) return
        if (exo.currentMediaItem?.toSong()?.isDeviceFile() == true) return
        if (target.isPlaying && !party.clockSynced) return

        if (exo.currentMediaItem?.mediaId != track.videoId) {
            load(party)
            return
        }
        loadingVideoId = null

        // Non-destructive queue reconciliation for upcoming tracks
        reconcileQueue(party, exo)

        if (!target.isPlaying) {
            deferredPlayPending = false
            if (exo.playWhenReady) exo.pause()
            if (abs(exo.currentPosition - target.positionMs) > PAUSED_TOLERANCE_MS) {
                exo.seekTo(target.positionMs)
            }
            return
        }

        val want = ListenTogether.partyPositionMs()
        if (!exo.playWhenReady) {
            if (locallyPaused) return
            val wait = ListenTogether.msUntilStart()
            if (wait > 0) {
                startJob?.cancel()
                startJob = scope.launch {
                    delay(wait)
                    reconcile()
                }
                return
            }
            if (want != null) exo.seekTo(want)
            deferredPlayPending = false
            startJob?.cancel()
            exo.play()
            alignedSeq = target.seq
            lastAnchorMs = target.anchorMs
            lastPositionMs = target.positionMs
            lastTrackId = track.videoId
            lastIsPlaying = target.isPlaying
            return
        }

        if (want == null) return

        if (exo.playbackState != Player.STATE_READY) {
            driftStrikes = 0
            return
        }

        val drift = exo.currentPosition - want

        if (target.seq != alignedSeq) {
            val isPlaybackAnchorChanged = lastAnchorMs != target.anchorMs ||
                lastPositionMs != target.positionMs ||
                lastTrackId != track.videoId ||
                lastIsPlaying != target.isPlaying

            alignedSeq = target.seq
            lastAnchorMs = target.anchorMs
            lastPositionMs = target.positionMs
            lastTrackId = track.videoId
            lastIsPlaying = target.isPlaying

            if (isPlaybackAnchorChanged && abs(drift) > ALIGN_TOLERANCE_MS) {
                Log.i(TAG, "aligning ${drift}ms onto party control ${target.seq}")
                exo.seekTo(want)
                return
            }
        }
        if (abs(drift) <= DRIFT_LIMIT_MS) {
            driftStrikes = 0
            return
        }

        val now = SystemClock.elapsedRealtime()
        if (now < driftCooldownUntilMs) return
        if (++driftStrikes < DRIFT_STRIKES) return
        Log.i(TAG, "correcting ${drift}ms of drift against the party")
        driftStrikes = 0
        driftCooldownUntilMs = now + DRIFT_COOLDOWN_MS
        exo.seekTo(want)
    }

    private fun load(party: ListenTogether.State) {
        val track = party.playback.track ?: return
        if (loadingVideoId == track.videoId) return
        loadingVideoId = track.videoId

        scope.launch {
            val partyQueue = party.queue.items
            val index = partyQueue.indexOfFirst { it.videoId == track.videoId }
            val queue = if (index >= 0) partyQueue else listOf(track)
            val startIndex = if (index >= 0) index else 0
            val items = withContext(Dispatchers.Default) { queue.map { it.toSong().toMediaItem() } }
            val exo = player()
            if (exo == null) {
                loadingVideoId = null
                return@launch
            }
            val startAt = ListenTogether.partyPositionMs() ?: party.playback.positionMs
            exo.setMediaItems(items, startIndex, startAt)
            exo.prepare()
            if (party.playback.isPlaying &&
                party.clockSynced &&
                ListenTogether.msUntilStart() <= 0L
            ) {
                exo.play()
            }
            reconcile()
        }
    }

    // ----------------------------------------------------------- outbound --

    private fun publish() {
        val party = ListenTogether.state.value
        if (!party.inParty) return
        if (rejoining) {
            rejoining = false
            reconcileQuietUntilMs = 0L
            return
        }
        val exo = player() ?: return
        val song = exo.currentMediaItem?.toSong() ?: return
        if (song.isDeviceFile()) return
        val track = song.toPartyTrack(exo.duration)
        val position = exo.currentPosition.coerceAtLeast(0L)
        val wantsPlaying = deferredPlayPending || exo.playWhenReady
        val basePlayback = party.playback.seq
        val baseQueue = party.queue.seq
        var playbackControls = 0
        var queueControls = 0

        val rawLocalItems = (0 until exo.mediaItemCount)
            .map { exo.getMediaItemAt(it) }
            .filterNot { it.mediaId.startsWith("content://") || it.mediaId.startsWith("file://") }

        val rawTrackIndex = rawLocalItems.indexOfFirst { it.mediaId == track.videoId }
        val localItems = if (rawTrackIndex >= 0) {
            val pastAndCurrent = rawLocalItems.subList(0, rawTrackIndex + 1)
            val upcoming = rawLocalItems.subList(rawTrackIndex + 1, rawLocalItems.size)
            pastAndCurrent + upcoming
        } else {
            rawLocalItems
        }

        val localIds = localItems.map { it.mediaId }
        val trackIndex = localIds.indexOf(track.videoId)
        val clampedIds = if (trackIndex >= 0) {
            val upcomingEnd = (trackIndex + 1 + MAX_PARTY_UPCOMING_QUEUE).coerceAtMost(localIds.size)
            localIds.subList(0, upcomingEnd)
        } else {
            localIds.take(1 + MAX_PARTY_UPCOMING_QUEUE)
        }

        val partyIndex = party.queue.items.indexOfFirst { it.videoId == party.playback.track?.videoId }
        val upcomingPartyTracks = if (partyIndex >= 0) {
            party.queue.items.drop(partyIndex + 1)
        } else {
            emptyList()
        }

        val partyIds = party.queue.items.map(PartyTrack::videoId)
        if (party.playback.track?.videoId != track.videoId && clampedIds.size == 1 && upcomingPartyTracks.isNotEmpty()) {
            val toPreserve = upcomingPartyTracks
                .filterNot { it.fromAutoplay }
                .take(MAX_PARTY_UPCOMING_QUEUE)
            if (exo.mediaItemCount == 1 && toPreserve.isNotEmpty()) {
                exo.addMediaItems(toPreserve.map { it.toSong().toMediaItem() })
            }
            val queue = listOf(track) + toPreserve
            ListenTogether.setQueue(queue, 0)
            queueControls++
        } else if (clampedIds != partyIds) {
            val singleMove = if (partyIds.size == clampedIds.size && trackIndex >= 0 && trackIndex < partyIds.size && partyIds[trackIndex] == clampedIds[trackIndex]) {
                detectSingleMove(partyIds, clampedIds)
            } else {
                null
            }

            if (singleMove != null && singleMove.fromIndex > trackIndex && singleMove.toIndex > trackIndex) {
                ListenTogether.queueMove(singleMove.fromIndex, singleMove.toIndex, singleMove.videoId)
                queueControls++
            } else {
                val countToTake = clampedIds.size
                val queue = (0 until countToTake)
                    .map { localItems[it].toSong() }
                    .filterNot(Song::isDeviceFile)
                    .map { it.toPartyTrack(0L) }
                ListenTogether.setQueue(queue, trackIndex)
                queueControls++
            }
        }

        when {
            party.playback.track?.videoId != track.videoId -> {
                ListenTogether.setTrack(track, position, wantsPlaying)
                playbackControls++
            }
            party.playback.isPlaying != wantsPlaying -> {
                if (wantsPlaying) ListenTogether.play(position) else ListenTogether.pause(position)
                playbackControls++
            }
            else -> {
                val partyPosition = ListenTogether.partyPositionMs()
                if (partyPosition == null || abs(position - partyPosition) > SEEK_REPORT_FLOOR_MS) {
                    ListenTogether.seek(position)
                    playbackControls++
                }
            }
        }

        awaitPlaybackSeq = basePlayback + playbackControls
        awaitQueueSeq = baseQueue + queueControls
        if (playbackControls == 0 && queueControls == 0) reconcileQuietUntilMs = 0L
    }

    private fun seedEmptyParty(party: ListenTogether.State) {
        if (party.connection != ListenTogether.Connection.LIVE) return
        if (party.you?.isHost != true) return
        val exo = player() ?: return
        val song = exo.currentMediaItem?.toSong() ?: return
        if (song.isDeviceFile()) return
        Log.i(TAG, "seeding the new party with what this device is already playing")
        publish()
    }

    private fun onEnteredParty() {
        val exo = player() ?: return
        Log.i(TAG, "entered party, stashing personal queue")
        PartyPersonalQueueStash.stashFromPlayer(exo)
    }

    private fun onLeftParty() {
        Log.i(TAG, "left party, restoring personal queue if stashed")
        loadingVideoId = null
        focusLost = false
        rejoining = false
        deferredPlayPending = false
        locallyPaused = false
        val stashed = PartyPersonalQueueStash.load()
        if (stashed != null) {
            val exo = player() ?: return
            val items = stashed.songs.map { it.toMediaItem() }
            exo.setMediaItems(items, stashed.index, stashed.positionMs)
            exo.prepare()
            if (stashed.wasPlaying) {
                exo.play()
            } else {
                exo.pause()
            }
            PartyPersonalQueueStash.clear()
        }
    }

    private fun reconcileQueue(party: ListenTogether.State, exo: Player) {
        val partyQueue = party.queue.items
        if (partyQueue.isEmpty()) return

        val currentIndex = exo.currentMediaItemIndex
        val currentMediaId = exo.currentMediaItem?.mediaId ?: return
        val partyIndex = partyQueue.indexOfFirst { it.videoId == currentMediaId }
        if (partyIndex < 0) return

        val desiredUpcoming = partyQueue.subList(partyIndex + 1, partyQueue.size).take(MAX_PARTY_UPCOMING_QUEUE)
        val desiredUpcomingIds = desiredUpcoming.map { it.videoId }

        val localUpcomingIds = (currentIndex + 1 until exo.mediaItemCount).map {
            exo.getMediaItemAt(it).mediaId
        }

        if (localUpcomingIds != desiredUpcomingIds) {
            if (desiredUpcomingIds.startsWith(localUpcomingIds)) {
                exo.addMediaItems(desiredUpcoming.drop(localUpcomingIds.size).map { it.toSong().toMediaItem() })
                return
            }

            if (localUpcomingIds.startsWith(desiredUpcomingIds)) {
                exo.removeMediaItems(currentIndex + 1 + desiredUpcomingIds.size, exo.mediaItemCount)
                return
            }

            val singleMove = if (localUpcomingIds.size == desiredUpcomingIds.size) {
                detectSingleMove(localUpcomingIds, desiredUpcomingIds)
            } else {
                null
            }

            if (singleMove != null) {
                exo.moveMediaItem(
                    currentIndex + 1 + singleMove.fromIndex,
                    currentIndex + 1 + singleMove.toIndex,
                )
            } else {
                exo.replaceMediaItems(
                    currentIndex + 1,
                    exo.mediaItemCount,
                    desiredUpcoming.map { it.toSong().toMediaItem() },
                )
            }
        }
    }

    private fun <T> List<T>.startsWith(prefix: List<T>): Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

    private data class ReconcileKey(
        val seq: Long,
        val queueSeq: Long,
        val code: String?,
        val clockSynced: Boolean,
    )

    private companion object {
        const val TAG = "PartySync"
        const val TICK_MS = 700L
        const val DRIFT_LIMIT_MS = 1_200L
        const val DRIFT_STRIKES = 2
        const val DRIFT_COOLDOWN_MS = 6_000L
        const val PAUSED_TOLERANCE_MS = 400L
        const val ALIGN_TOLERANCE_MS = 120L
        const val SEEK_REPORT_FLOOR_MS = 1_000L
        const val DEFERRED_PLAY_TIMEOUT_MS = 1_800L
        const val PUBLISH_DEBOUNCE_MS = 120L
        const val INTENT_QUIET_MS = 4_500L
        const val MAX_PARTY_UPCOMING_QUEUE = 25
        const val MAX_PUBLISHED_QUEUE = 1 + MAX_PARTY_UPCOMING_QUEUE
    }
}

private fun Song.isDeviceFile(): Boolean =
    videoId.startsWith("content://") || videoId.startsWith("file://")

internal fun Song.toPartyTrack(playerDurationMs: Long): PartyTrack = PartyTrack(
    videoId = videoId,
    title = title,
    artist = artist,
    thumbnailUrl = thumbnailUrl,
    durationMs = playerDurationMs.takeIf { it > 0L }
        ?: TrackMatcher.secondsOf(durationText)?.let { it * 1000L },
    fromAutoplay = fromAutoplay,
)

internal fun PartyTrack.toSong(): Song = Song(
    videoId = videoId,
    title = title,
    artist = artist,
    thumbnailUrl = thumbnailUrl,
    durationText = durationMs?.let { ms ->
        val totalSec = ms / 1000
        val min = totalSec / 60
        val sec = totalSec % 60
        "%d:%02d".format(min, sec)
    } ?: "",
    fromAutoplay = fromAutoplay,
)

data class QueueMoveDelta(
    val fromIndex: Int,
    val toIndex: Int,
    val videoId: String,
)

/**
 * Detects if [newList] is the result of moving exactly one item in [oldList].
 */
fun detectSingleMove(
    oldList: List<String>,
    newList: List<String>,
    baseOffset: Int = 0,
): QueueMoveDelta? {
    if (oldList.size != newList.size || oldList == newList || oldList.isEmpty()) return null
    if (oldList.groupingBy { it }.eachCount() != newList.groupingBy { it }.eachCount()) return null

    for (from in oldList.indices) {
        val item = oldList[from]
        val withoutItem = oldList.toMutableList().apply { removeAt(from) }
        for (to in oldList.indices) {
            if (from == to) continue
            val simulated = withoutItem.toMutableList().apply { add(to, item) }
            if (simulated == newList) {
                return QueueMoveDelta(
                    fromIndex = baseOffset + from,
                    toIndex = baseOffset + to,
                    videoId = item,
                )
            }
        }
    }
    return null
}
