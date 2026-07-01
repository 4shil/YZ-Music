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
        var flushingTo = 0L

        var atrSent = false
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _registeredPlays = MutableStateFlow(0)

    /**
     * Bumped each time a play lands in the account's history. The home feed's
     * lead shelf is built from that history, so it has gone stale whenever this
     * moves — the counter is the signal to re-fetch, and carries no meaning
     * beyond having changed.
     */
    val registeredPlays: StateFlow<Int> = _registeredPlays.asStateFlow()

    /** Guards session hand-over: starting a track must not race its own pings. */
    private val lock = Mutex()

    @Volatile
    private var session: Session? = null

    /** The track a session is being opened for, so repeat calls don't stack up. */
    @Volatile
    private var opening: String? = null

    /**
     * Call when [videoId] becomes audible — both on play/resume and when the
     * queue moves on. A no-op while the same track is already being tracked, so
     * a pause/resume does not register a second play.
     */
    fun onPlaying(videoId: String) {
        if (!VIDEO_ID.matches(videoId)) return
        if (Innertube.cookie == null) return
        // A downloaded track plays perfectly well with the radio off — the
        // whole point of downloading it — so this is the one place that has
        // to ask before trying rather than let a request find out the hard
        // way. [meteredConnection] is null exactly when there is no active
        // network, which is the one case worth skipping outright rather than
        // spending three retries on: nothing here is urgent enough to wait
        // for connectivity to return, and the play was still counted by
        // whichever surface reads local listening history.
        if (AppSettings.meteredConnection.value == null) return
        if (session?.videoId == videoId || opening == videoId) return
        opening = videoId
        scope.launch(TrackLog.about(videoId)) {
            try {
                openWithRetries(videoId)
            } finally {
                if (opening == videoId) opening = null
            }
        }
    }

    /**
     * Call when the queue moves to a different track. The previous session's
     * watched time is flushed before it is dropped, so a track skipped at the
     * two-minute mark is reported as two minutes rather than lost.
     */
