package com.music.yzmusic.data.innertube

import com.music.yzmusic.data.TrackLog
import com.music.yzmusic.data.settings.AppSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Registers plays against the signed-in account's YouTube Music history, so
 * recommendations on the home tab reflect what's actually been listened to.
 *
 * Three pings make one play, which is the sequence a real client performs:
 *
 *  - `videostatsPlaybackUrl` the moment a track becomes audible. This creates
 *    the history entry.
 *  - `atrUrl` a few seconds in, which is what separates a play that started
 *    from a play that happened.
 *  - `videostatsWatchtimeUrl` as it plays on, and once more when it ends. An
 *    entry with no watchtime behind it looks like a track that was skipped,
 *    which is close to worthless as a recommendation signal.
 *
 * All three carry the same client-playback-nonce, which is what ties them to
 * one play; a nonce reused across tracks gets the second play discarded.
 *
 * Best-effort only: any failure here must never affect playback itself. That is
 * a constraint on how failures are handled, not permission to ignore them —
 * everything below is retried, because the alternative is what this file did
 * before, which was to lose a play to a single momentary refusal and never
 * mention it again.
 */
object PlaybackTracker {

    private const val TAG = "YZ Music"

    /** Report watched time once this much new audio has gone by. */
    private const val REPORT_INTERVAL_SECONDS = 30L

    /**
     * Attempts at opening a session before the play is written off.
     *
     * A track starting is the worst possible moment to require a network round
     * trip to succeed first time: it is exactly when a stream is being resolved,
     * a connection may be handing over between wifi and cellular, and the player
     * is saturating whatever is left. One attempt, which is what this had, meant
     * a play lost for good to a blip that had nothing to do with it.
     */
    private const val OPEN_ATTEMPTS = 3

    private const val OPEN_RETRY_DELAY_MS = 2_000L

    /**
     * A videoId, as opposed to anything else that can be a media id.
     *
     * Local files carry their `content://` URI as an id, and a module source
     * carries whatever that module uses. Asking Google to register a play of one
     * of those is a request that cannot succeed, made once per track, and it was
     * being made — the guard is here rather than at the call site because this
     * object is the thing that knows what an id has to look like to be useful.
     */
    private val VIDEO_ID = Regex("""[A-Za-z0-9_-]{11}""")

    private class Session(
        val videoId: String,
        val cpn: String,
        val tracking: Innertube.PlaybackTracking,
    ) {
        var reportedSeconds = 0L

        /** Set before the network call, so a slow flush can't stack up behind itself. */
