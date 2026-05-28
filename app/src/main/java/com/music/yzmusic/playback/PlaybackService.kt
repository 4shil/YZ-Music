package com.music.yzmusic.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.music.yzmusic.MainActivity
import com.music.yzmusic.R
import com.music.yzmusic.data.Http
import com.music.yzmusic.data.LikeState
import com.music.yzmusic.data.NerdStats
import com.music.yzmusic.data.TrackLog
import com.music.yzmusic.data.YtMusicRepository
import com.music.yzmusic.data.discord.DiscordRPC
import com.music.yzmusic.data.innertube.PlaybackTracker
import com.music.yzmusic.data.stats.ListeningRecorder
import com.music.yzmusic.data.innertube.PlayerClient
import com.music.yzmusic.data.innertube.StreamResolver
import com.music.yzmusic.data.model.LikeStatus
import com.music.yzmusic.data.model.Song
import com.music.yzmusic.data.scrobbling.LastFM
import com.music.yzmusic.data.scrobbling.ListenBrainzManager
import com.music.yzmusic.data.scrobbling.ScrobbleManager
import com.music.yzmusic.data.settings.AppSettings
import com.music.yzmusic.data.sources.SourceResolver
import com.music.yzmusic.data.sources.SourceStream
import com.music.yzmusic.data.sources.StreamFormat
import com.music.yzmusic.data.sources.TrackMatcher
import com.music.yzmusic.download.Downloads
import com.music.yzmusic.widget.MediaWidget
import com.music.yzmusic.widget.MediaWidgetSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlinx.coroutines.TimeoutCancellationException
import java.util.Locale

/** Past this point in a track, back restarts it instead of skipping to the previous one. */
const val BACK_RESTARTS_AFTER_MS = 10_000L

/** Session command used by both the player UI and the media notification. */
const val ACTION_TOGGLE_AUTOPLAY = "com.music.yzmusic.action.TOGGLE_AUTOPLAY"

/** Session command used by the media notification's Shuffle button. */
const val ACTION_TOGGLE_SHUFFLE = "com.music.yzmusic.action.TOGGLE_SHUFFLE"

/**
 * Background playback via Media3. A [MediaSessionService] gives us the media
 * notification, lockscreen/Bluetooth controls, and Android Auto surface for
 * free; UI processes attach with a MediaController.
 *
 * Queue items carry a `yzmusic://watch?v=<videoId>` URI. The actual stream
 * URL is resolved lazily by [ResolvingDataSource] the moment ExoPlayer opens
 * the item — stream URLs expire after a few hours, so resolving at play time
 * (on Media3's loader thread, hence runBlocking is safe) keeps queues valid.
 *
 * A single ExoPlayer owns the queue and backs the session for the service's
 * whole life; [CrossfadeController] rides on top of it as volume automation.
 */
@UnstableApi
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    /**
     * The player the session is on. Swaps with [spare] at every crossfade — see
     * [adoptPlayer] — so anything reading it must read it *now* rather than
     * capturing it.
     */
    private var player: ExoPlayer? = null

    /**
     * The idle player. Between transitions it holds nothing; to arm one,
     * [CrossfadeController] loads it with the queue positioned on the incoming
     * track.
     */
    private var spare: ExoPlayer? = null

    private var crossfade: CrossfadeController? = null

    /**
     * One audio-processor set per player, because both carry per-sink state — a
     * delay line, filter memory — that two sinks cannot share.
     *
     * The `A`/`B` pair is fixed to the players that own them; [activeFilter] and
     * [spareFilter] are the *roles*, and they trade places at every handoff
     * along with the players. Everything downstream talks in roles.
     */
    private val spatialAudioProcessorA = SpatialAudioProcessor()
    private val spatialAudioProcessorB = SpatialAudioProcessor()
    private val transitionFilterA = TransitionFilterProcessor()
    private val transitionFilterB = TransitionFilterProcessor()

    private var activeFilter: TransitionFilterProcessor = transitionFilterA
    private var spareFilter: TransitionFilterProcessor = transitionFilterB

    /** Automix's DSP analyzer — see [com.music.yzmusic.playback.smart.TrackAnalyzer]. */
    private val trackAnalyzer = com.music.yzmusic.playback.smart.TrackAnalyzer(this, AudioCache)

    /** Shared with the crossfade's tail player, so both read the same disk cache. */
    private var mediaSourceFactory: DefaultMediaSourceFactory? = null

    /** Last sampled position of the playing track, in seconds. */
    private var lastPositionSeconds = 0L

    /** When the current track was chosen, for the time-to-first-audio log. */
    private var trackSelectedAt: Long? = null

    private var scrobbleManager: ScrobbleManager? = null
    private var listenBrainzSong: Song? = null

    private var listenBrainzStartMs: Long = 0L

    private var listenBrainzDurationMs: Long? = null

    /**
     * The gateway connection publishing what's playing to Discord, or null when
     * the feature is off or no account is connected. See [DiscordRPC].
     */
    private var discordRpc: DiscordRPC? = null

    /**
     * The in-flight presence push. Held so the next one can cancel it: the
     * pushes hit the network — the artwork has to be mirrored onto Discord's CDN
     * before the activity can name it — and a skipped-through queue would
     * otherwise land its presences in whatever order the requests happened to
     * finish in, leaving the profile on a track the listener passed seconds ago.
     */
    private var discordUpdateJob: Job? = null

    /**
     * Whether a presence has been published and not yet taken down.
     *
     * Tracked because [KizzyRPC.close] — which is what clears the card — opens a
     * gateway connection first if one isn't already up. Clearing unconditionally
     * would therefore dial Discord for the sole purpose of sending it nothing,
     * every time playback paused without a presence ever having been set.
     */
    private var discordPresenceUp = false

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    /** Commands exposed as the secondary buttons on the media notification. */
    private val favoriteCommand = SessionCommand(ACTION_TOGGLE_FAVORITE, Bundle.EMPTY)
    private val autoplayCommand = SessionCommand(ACTION_TOGGLE_AUTOPLAY, Bundle.EMPTY)
    private val shuffleCommand = SessionCommand(ACTION_TOGGLE_SHUFFLE, Bundle.EMPTY)

    private var favoriteActionJob: Job? = null
    private var autoplayLoadJob: Job? = null
    private var autoplaySeed: String? = null

    /**
     * What AutoPlay had queued when repeat-all was switched on, held so
     * switching it off again puts back the same tracks rather than a fresh
     * mix.
     *
     * Repeat-all loops the queue as it stands, so AutoPlay's endless supply of
     * new tracks comes out of it first — see [onRepeatModeChanged]. Fetching a
     * replacement mix afterwards is the obvious thing to do and the wrong one:
     * the track that was queued next has usually been analysed for the
     * transition into it by then (see
     * [com.music.yzmusic.playback.smart.TrackAnalyzer]), and a different track
     * in its place means that several-second decode is spent again, on a song
     * that is now much closer than it was. Turning repeat on and back off
     * should leave the queue where it found it.
     */
    private var repeatAllStash: List<MediaItem> = emptyList()

    /**
     * The track that was playing when [repeatAllStash] was taken. The stash
     * describes what came after *that* track, so it is only put back if the
     * queue has not moved on in the meantime.
     */
    private var repeatAllStashSeed: String? = null

    /**
     * The last repeat mode seen, so [onRepeatModeChanged] can tell which
     * direction the change went in: the callback reports where the player has
     * arrived, and leaving repeat-all is the half that has to restore.
     */
    private var lastRepeatMode = Player.REPEAT_MODE_OFF

    /**
     * Every song AutoPlay has offered or played this service instance, kept
     * only so "don't repeat suggestions" has something to check against once
     * a song scrolls out of the live queue or the queue itself is replaced.
     * Never persisted — a fresh process means a fresh session.
     */
    private val sessionSongHistory = mutableListOf<Song>()

    private val sessionCallback = object : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            // The media notification controller is a normal Media3 controller. Its custom
            // buttons are omitted unless their commands are explicitly available.
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS
                .buildUpon()
                .add(favoriteCommand)
                .add(autoplayCommand)
                .add(shuffleCommand)
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(commands)
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                ACTION_TOGGLE_AUTOPLAY -> toggleAutoplayFromNotification()
                ACTION_TOGGLE_SHUFFLE -> toggleShuffleFromNotification()
                ACTION_TOGGLE_FAVORITE -> session.player.currentMediaItem?.mediaId?.let {
                    toggleFavoriteFromNotification(it)
                }
                else -> return Futures.immediateFuture(
                    SessionResult(SessionError.ERROR_NOT_SUPPORTED),
                )
            }
            // The actual YouTube rating is asynchronous. The command itself has been accepted;
            // the notification is refreshed when the network write completes.
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
    }

    /**
     * Everything the service books against the player it is currently on.
     *
     * A field rather than an anonymous object registered once, because the
     * session moves between two players at every crossfade — see [adoptPlayer]
     * — and this has to move with it. It is attached to exactly one player at a
     * time: the one [player] names.
     */
    private val playbackListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            // The player this fired on, which is by definition the one the
            // session is currently pointed at.
            val exoPlayer = player ?: return
            // The only number that describes what a listener actually
            // waits through. Every other timing in this app measures one
            // leg of getting a track started — a resolve, a client walk, an
            // extraction — and a leg being fast has repeatedly turned out
            // to say nothing about whether sound arrived quickly, because
            // the legs that were measured were the ones running in the
            // background for tracks nobody was waiting on.
            if (isPlaying) {
                trackSelectedAt?.let {
                    TrackLog.d(
                        "YZ Music",
                        "TIMING first audio: ${SystemClock.elapsedRealtime() - it}ms since track selected",
                        about = exoPlayer.currentMediaItem?.mediaId,
                    )
                    trackSelectedAt = null
                }
            }
            if (isPlaying) registerCurrentPlay()
            // Nothing to read ahead for while paused, and a pause is often
            // the last thing that happens before the process goes idle.
            if (isPlaying) prefetchAround(exoPlayer) else AudioCache.cancel()
            if (isPlaying) lookForBetterCopy(exoPlayer)
            saveQueue()
            // Not strictly needed for the glyph — onPlayWhenReadyChanged has
            // already flipped that — but this is where hasNext/hasPrevious and
            // the artwork are known to be settled.
            publishWidgetState()

            val song = exoPlayer.currentMediaItem?.toSong()
            val durationMs = exoPlayer.duration.takeIf { it > 0 }
            scrobbleManager?.onPlayerStateChanged(isPlaying, song, durationMs)

            // The listening record has to be told a pause happened, not merely
            // stop being told about play: its sampler measures the gap between
            // ticks, and an unclosed one across a pause is an afternoon on the
            // lock screen arriving as an afternoon of listening.
            if (!isPlaying) ListeningRecorder.onStopped()

            // ListenBrainz: "now playing" on play/resume too, not just on
            // transition — a track started from idle or resumed from pause
            // otherwise stays silent on the site.
            if (isPlaying && song != null) {
                if (listenBrainzSong?.videoId != song.videoId || listenBrainzStartMs == 0L) {
                    listenBrainzSong = song
                    listenBrainzStartMs = System.currentTimeMillis()
                    listenBrainzDurationMs = durationMs
                } else if (listenBrainzDurationMs == null) {
                    listenBrainzDurationMs = durationMs
                }
                submitListenBrainzPlayingNow(song, exoPlayer.currentPosition, durationMs)
            }

            // Discord: a pause has to clear the presence, not just stop
            // refreshing it. Discord's countdown runs on its own clock from the
            // timestamps it was given, so a presence left up while paused goes
            // on advancing through a song that has stopped — and finishes it.
            if (isPlaying) {
                pushDiscordPresence(exoPlayer)
            } else {
                clearDiscordPresence()
            }
        }

        /**
         * The one callback the home-screen widget can be driven from.
         *
         * `onIsPlayingChanged` is too late by seconds: this app resolves a
         * stream before it can buffer one, and for a YouTube track that means a
         * NewPipe extraction, all of which happens with `isPlaying` still false.
         * A widget keyed on that answers a tap on play by leaving the play glyph
         * exactly where it was — the control reads as broken, and the obvious
         * response is to tap it again. `playWhenReady` flips on the command, not
         * on the audio, which is what the media notification shows too.
         */
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            publishWidgetState(playing = playWhenReady)
        }

        /**
         * A seek is the one change to a playing track that no other callback
         * reports, and the only one Discord cannot work out for itself: its bar
         * is drawn from two absolute instants, so moving the playhead without
         * sending new ones leaves the profile counting down from where the
         * listener no longer is.
         */
        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            val exoPlayer = player ?: return
            if (reason == Player.DISCONTINUITY_REASON_SEEK && exoPlayer.isPlaying) {
                pushDiscordPresence(exoPlayer)
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            // The player this fired on, which is by definition the one the
            // session is currently pointed at.
            val exoPlayer = player ?: return
            // A quality swap replaces the playing item, which Media3
            // reports here as a playlist change — indistinguishable, from
            // this callback's point of view, from the queue moving on. It
            // is not the queue moving on: it is the same song, at the same
            // position, from a better source. Letting the bookkeeping below
            // run for it scrobbled the track twice, wrote a second history
            // entry, resubmitted it to ListenBrainz and closed out its
            // play count mid-play — all of which happened, and all of which
            // are invisible until someone reads their listening history.
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED &&
                mediaItem?.mediaId != null &&
                mediaItem.mediaId == swappingMediaId
            ) {
                swappingMediaId = null
                return
            }

            // No crossfade case to allow for here any more. A blended advance
            // never reaches this callback — the incoming track starts as the
            // *first* item of the other player — so it is booked by
            // [adoptPlayer] instead, and what is left arriving here is only ever
            // ExoPlayer moving the queue on by itself, a repeat, or a skip.
            onTrackBecameCurrent(
                mediaItem,
                previousEnded = reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO ||
                    reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT,
                reason = reason,
            )
            autoplayLoadJob?.cancel()
            autoplayLoadJob = null
            autoplaySeed = null
            loadAutoplayForCurrentTrack()
            mediaSession?.setCustomLayout(notificationButtons())
        }

        /**
         * A failed stream is not a failed track: nothing else in this
         * service ever calls [Player.prepare] again, so before this
         * existed a single read error left the player in `STATE_IDLE` for
         * good. The notification kept the song on it, the play button kept
         * being pressed, and nothing happened — which is exactly what a
         * broken app looks like from the outside.
         */
        override fun onPlayerError(error: PlaybackException) {
            // The player this fired on, which is by definition the one the
            // session is currently pointed at.
            val exoPlayer = player ?: return
            recoverFrom(error, exoPlayer)
        }

        // Nothing follows the last track, so there is no transition to
        // pause on — the queue simply runs out and the timer is spent.
        override fun onPlaybackStateChanged(state: Int) {
            // The player this fired on, which is by definition the one the
            // session is currently pointed at.
            val exoPlayer = player ?: return
            if (state == Player.STATE_ENDED) {
                SleepTimer.cancel()
                // The queue ran dry, so no transition will ever close the last
                // track out. Without this its history entry keeps whatever
                // watchtime the 30-second sampler happened to have reported and
                // is never marked finished — so the one play most likely to be
                // a full, deliberate listen is the one recorded as abandoned.
                PlaybackTracker.onPlaybackFinished(lastPositionSeconds)
                lastPositionSeconds = 0
                // The last track finished with nothing after it, so no
                // transition will ever close it out. Scrobble it now.
                val lastSong = listenBrainzSong
                if (lastSong != null && listenBrainzStartMs > 0L) {
                    val lastStart = listenBrainzStartMs
                    val lastDuration = listenBrainzDurationMs
                        ?: exoPlayer.duration.takeIf { it > 0 }
                    submitListenBrainzFinished(lastSong, lastStart, lastDuration)
                }
                listenBrainzSong = null
                listenBrainzStartMs = 0L
                listenBrainzDurationMs = null
            }
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            val previous = lastRepeatMode
            lastRepeatMode = repeatMode
            // Repeat-all loops the queue as it stands; AutoPlay's tracks are the
            // opposite of that — an endless supply of new ones — so they come
            // back out first, and native REPEAT_MODE_ALL then wraps a plain
            // queue exactly as it should. [loadAutoplayForCurrentTrack] leaves
            // it alone for as long as repeat-all stays on.
            //
            // Done here rather than in the UI that used to do it because this is
            // the only place that sees the *previous* mode, and taking the
            // tracks back is only half the job: they have to go in again when
            // the loop ends, or a listener who cycles repeat on and straight
            // back off is left with a queue that simply stops after the playing
            // track.
            when {
                repeatMode == Player.REPEAT_MODE_ALL -> stashAutoplayTracks()
                previous == Player.REPEAT_MODE_ALL -> restoreAutoplayTracks()
            }
            // Turning repeat-all back off can leave the current item at the end
            // of the queue, which is the same trigger as a normal transition.
            loadAutoplayForCurrentTrack()
        }

        /**
         * AutoPlay appends to the queue after the transition that ran it
         * dry, so the track to read ahead for often only exists once the
         * timeline has changed.
         */
        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            // The player this fired on, which is by definition the one the
            // session is currently pointed at.
            val exoPlayer = player ?: return
            if (exoPlayer.isPlaying) prefetchAround(exoPlayer)
            if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) {
                mediaSession?.setCustomLayout(notificationButtons())
            }
        }
    }

    /** Registered alongside [playbackListener], and moved with it. */
    private val formatListener = object : AnalyticsListener {
        override fun onAudioInputFormatChanged(
            eventTime: AnalyticsListener.EventTime,
            format: Format,
            decoderReuseEvaluation: DecoderReuseEvaluation?,
        ) {
            // Taken off the event's own window rather than off the player,
            // so it names the track this format arrived for even if the
            // queue has moved on again since. See [audioFormatFor].
            audioFormatFor = eventTime.mediaId()
            // Ground truth for a real-device listening test: this is the
            // renderer's own Format, straight off the decoder with none of
            // the app's caching/upgrade logic in between, so it's the one
            // line that can prove a "hi-res" session never quietly slid
            // onto a lower-rate stream mid-track. `adb logcat -s DECODE:I`.
            val khz = format.sampleRate.takeIf { it != Format.NO_VALUE }
                ?.let { "%.1fkHz".format(Locale.ROOT, it / 1000.0) } ?: "?kHz"
            val kbps = format.bitrate.takeIf { it != Format.NO_VALUE }
                ?.let { "${it / 1000}kbps" } ?: "bitrate n/a"
            val depth = bitDepthOf(format.pcmEncoding)?.let { "${it}-bit" } ?: "?-bit"
            TrackLog.i(
                "DECODE",
                "$audioFormatFor <- ${format.sampleMimeType} $khz $kbps $depth ${format.channelCount}ch",
                about = audioFormatFor,
            )
            publishNerdStats()
        }

        /**
         * The seam, measured rather than described. This fires when the
         * audio track starts putting samples out again after the sink was
         * flushed, which for a quality swap is the exact instant the music
         * comes back — and the gap between it and the swap is the only
         * number that says whether any of the work above paid off. Every
         * other timing here brackets a fetch, and a fetch being fast has
         * repeatedly said nothing about whether the listener heard a hole.
         */
        override fun onAudioPositionAdvancing(
            eventTime: AnalyticsListener.EventTime,
            playoutStartSystemTimeMs: Long,
        ) {
            val cutAt = swapCutAt ?: return
            swapCutAt = null
            TrackLog.d(
                "YZ Music",
                "swap seam: ${SystemClock.elapsedRealtime() - cutAt}ms of silence",
                about = eventTime.mediaId(),
            )
        }

        /**
         * The three legs the seam breaks into, logged separately because
         * they have entirely different fixes: getting the new source
         * loaded and past the load control's gate, standing a decoder up,
         * and opening an audio track. Only the first is ours to shorten.
         */
        override fun onPlaybackStateChanged(eventTime: AnalyticsListener.EventTime, state: Int) {
            val cutAt = swapCutAt ?: return
            if (state == Player.STATE_READY) {
                TrackLog.d(
                    "YZ Music",
                    "swap leg: ready ${SystemClock.elapsedRealtime() - cutAt}ms after the cut",
                    about = eventTime.mediaId(),
                )
            }
        }

        override fun onAudioDecoderInitialized(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String,
            initializedTimestampMs: Long,
            initializationDurationMs: Long,
        ) {
            val cutAt = swapCutAt ?: return
            TrackLog.d(
                "YZ Music",
                "swap leg: $decoderName stood up in ${initializationDurationMs}ms, " +
                    "${SystemClock.elapsedRealtime() - cutAt}ms after the cut",
                about = eventTime.mediaId(),
            )
        }
    }

    /**
     * Which track an analytics event is about, taken off the event's own window.
     *
     * The player has moved on by the time some of these arrive — a format
     * change for the outgoing track lands after the transition — so its
     * `currentMediaItem` names the wrong one. The event carries the timeline it
     * was raised against, which does not.
     */
    private fun AnalyticsListener.EventTime.mediaId(): String? = timeline
        .takeIf { windowIndex < it.windowCount }
        ?.getWindow(windowIndex, Timeline.Window())
        ?.mediaItem
        ?.mediaId

    override fun onCreate() {
        super.onCreate()

        // First, because everything below assumes it is standing up fresh and
        // one of the two ways this service starts does not give it that.
        //
        // A cold start runs in a new process, where the session-scoped state
        // these two hold is empty anyway. A *warm* one doesn't: closing the app
        // destroys the service while Android keeps the process to reuse, so
        // without this a second service inherits the first one's idea of what
        // was playing and what has already been asked about. That cost the
        // reported bug all three of its symptoms — a badge reading "Lossless"
        // over a player holding no bytes, and a track that had been upgraded to
        // FLAC playing its cached Opus with no second look, permanently, because
        // its id was still recorded as answered. Both are documented where the
        // state lives.
        NerdStats.forgetLastSession()
        QualityUpgrade.forgetLastSession()

        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this)
                .setChannelId(CHANNEL_ID)
                .setChannelName(R.string.playback_channel_name)
                .build()
                .apply { setSmallIcon(R.drawable.ic_notification_logo) },
        )

        // The player screen toggles QueueShuffle directly on its MediaController.
        // Observe the shared state here so the notification's Shuffle icon and
        // label follow that toggle immediately as well.
        scope.launch {
            QueueShuffle.enabled
                .collectLatest {
                    mediaSession?.setCustomLayout(notificationButtons())
                }
        }
        scope.launch {
            LikeState.overrides.collectLatest {
                mediaSession?.setCustomLayout(notificationButtons())
            }
        }

        // No user agent on the factory: the right one depends on which client
        // minted the URL, so it is set per request below. Setting it here as
        // well would not override that — OkHttpDataSource *appends* the
        // factory's agent after the request's, and the fetch would go out
        // carrying two contradictory User-Agent headers.
        val resolvingFactory = ResolvingDataSource.Factory(
            // Innermost, so it chunks the real googlevideo URL the resolver
            // below has already substituted in — see [ChunkedDataSource] for
            // why an open-ended read of one is worth avoiding.
            ChunkedDataSource.Factory(OkHttpDataSource.Factory(Http.client), STREAM_CHUNK_BYTES),
        ) { dataSpec ->
            // Which track everything below is for, said once, because none of
            // it would otherwise know: this runs on ExoPlayer's loader thread
            // with a DataSpec and nothing else, and the work it starts — the
            // source ladder, the module sandbox, a client walk — logs from
            // places several layers deep that have no idea whose bytes they
            // are fetching. Read-ahead means the track being resolved here is
            // usually *not* the one playing, which is exactly why the lines
            // have to say. See [TrackLog.about].
            val about = TrackLog.about(mediaIdIn(dataSpec.uri))
            // A source-backed track is resolved by whichever source can serve
            // it, which is not necessarily the one it was queued from — see
            // [SourceResolver.resolve]. Handled ahead of the YouTube path
            // because these carry no `v` parameter and would otherwise fall
            // straight through unresolved.
            if (dataSpec.uri.authority == "source") {
                val stream = runBlocking(about) {
                    withTimeout(RESOLVE_TIMEOUT_MS) { SourceResolver.resolve(dataSpec.uri) }
                } ?: throw java.io.IOException("No enabled source could serve ${dataSpec.uri.getQueryParameter("n")}")
                NerdStats.onSourceStream(dataSpec.uri.getQueryParameter("t"), stream.format)
                return@Factory dataSpec.buildUpon()
                    .setUri(Uri.parse(stream.url))
                    .setHttpRequestHeaders(stream.headers)
                    .build()
            }
            val videoId = dataSpec.uri.getQueryParameter("v")
                ?: return@Factory dataSpec
            // An upgraded item carries a marker and its stream has already
            // been found — see [QualityUpgrade]. Answered before anything
            // else, and without re-resolving: this exact URL is what the
            // player was told it was getting when it agreed to the swap.
            QualityUpgrade.forcedStream(dataSpec.uri)?.let { upgraded ->
                // An audition opens this same stream before a note of the one
                // playing has been touched — see [auditionUpgrade] — so what it
                // is about to be handed describes a swap that has not happened
                // and may never. Recording it here would light "Lossless" over
                // the lossy stream still coming out of the speaker. The real
                // open, moments later, records it.
                val proving = QualityUpgrade.isAuditioning(videoId)
                if (!proving) NerdStats.onSourceStream(videoId, upgraded.format)
                // Logged because the alternative — a swap that silently never
                // reached its stream — is indistinguishable in the logs from
                // one that reached it and got nothing back, and the two have
                // opposite fixes.
                TrackLog.d(
                    "YZ Music",
                    "${if (proving) "auditioning" else "serving"} upgraded $videoId " +
                        "from ${Uri.parse(upgraded.url).host} " +
                        "at ${dataSpec.position} (${upgraded.format.summary})",
                    about = videoId,
                )
                return@Factory dataSpec.buildUpon()
                    .setUri(Uri.parse(upgraded.url))
                    .setHttpRequestHeaders(upgraded.headers)
                    .build()
            }
            // A downloaded copy is *not* substituted here, deliberately. This
            // point is inside the HTTP-only half of the chain — below
            // DefaultDataSource, which has already given up on dispatching by
            // scheme, and below the cache bypass that keeps local files from
            // being written to disk a second time. A content:// URI returned
            // from here reaches OkHttp, which refuses it as a malformed URL.
            // Which copy of a track to play is settled where the item is built
            // instead: see [Song.toMediaItem].
            // Whoever is already filling this track's cache entry keeps it.
            // Everything below decides between servers holding *different
            // files*, and this method is called again for every re-open of a
            // track — including the continuation fetch when playback runs off
            // the end of the cached bytes. Deciding afresh each time is how
            // the middle of an MP4 ended up appended to a WebM. See
            // [StreamChoice].
            StreamChoice.of(videoId)?.let { serving ->
                // What the stream claims to be, restated on every open rather
                // than only on the one that chose it.
                //
                // The race below is what used to report this, and it was enough
                // while the race was the only way a substitution could be made.
                // Read-ahead now pins one before the track is reached, so a
                // warmed track arrives *here* on its very first open and never
                // reaches the race at all — leaving the player with a 320kbps
                // stream and nothing on record saying so, and the quality badge
                // reading blank until the decoder got far enough to measure it
                // for itself.
                //
                // Only when the format states something. A plain YouTube choice
                // is remembered with an empty one, and writing that over a
                // claim some other path made would be worse than saying nothing.
                if (serving.format != StreamFormat()) {
                    NerdStats.onSourceStream(videoId, serving.format)
                }
                return@Factory dataSpec.buildUpon()
                    .setUri(Uri.parse(serving.url))
                    .setHttpRequestHeaders(serving.headers)
                    .build()
            }
            // A track queued from YouTube may be held by a source the user
            // ranked above it — see [SourceResolver.substituteForYouTube] and
            // [raceYouTubeOrModule]. Only worth the extra lookup when
            // something actually outranks YouTube; otherwise this is the
            // plain resolve every build before this one made.
            if (!SourceResolver.canSubstituteForYouTube()) {
                val streamUrl = try {
                    runBlocking(about) {
                        withTimeout(RESOLVE_TIMEOUT_MS) { StreamResolver.resolve(videoId) }
                    }
                } catch (e: TimeoutCancellationException) {
                    throw java.io.IOException("Stream resolution timed out for $videoId", e)
                }
                // googlevideo names the client that minted the URL inside the
                // URL itself, and compares it against the request that comes
                // back for the bytes. A mismatch is answered with a throttled
                // trickle or a 403 rather than an error worth the name, so the
                // fetch is dressed as whatever the URL says it should be.
                val headers = PlayerClient.forStreamUrl(streamUrl).mediaHeaders()
                // Recorded even though only one server can answer here: a
                // source enabled from Settings mid-track flips the branch
                // above under a half-filled cache entry, and the entry would
                // then be finished by a different file.
                StreamChoice.remember(videoId, SourceStream(streamUrl, headers = headers), substituted = false)
                return@Factory dataSpec.buildUpon()
                    .setUri(Uri.parse(streamUrl))
                    .setHttpRequestHeaders(headers)
                    .build()
            }
            val won = runBlocking(about) {
                resolveWithModulePriority(
                    videoId = videoId,
                    target = SourceResolver.targetIn(dataSpec.uri),
                )
            }
            when (won) {
                is Resolved.Module -> {
                    NerdStats.onSourceStream(videoId, won.stream.format)
                    StreamChoice.remember(videoId, won.stream, substituted = true)
                    dataSpec.buildUpon()
                        .setUri(Uri.parse(won.stream.url))
                        .setHttpRequestHeaders(won.stream.headers)
                        .build()
                }
                // A module could have served this and didn't — it missed, its
                // server was slow, or the lookup ran out of budget. The last
                // of those is worth chasing rather than accepting: measured
                // here, a module's stream URL arrived 66ms after the live path
                // gave up on it, and the difference between a FLAC and a
                // YouTube Opus stream came down to that. The second look has
                // no such deadline, so what was nearly in hand is asked for
                // again while the fallback plays.
                is Resolved.YouTube -> {
                    val headers = PlayerClient.forStreamUrl(won.url).mediaHeaders()
                    StreamChoice.remember(videoId, SourceStream(won.url, headers = headers), substituted = false)
                    dataSpec.buildUpon()
                        .setUri(Uri.parse(won.url))
                        .setHttpRequestHeaders(headers)
                        .build()
                }
            }
        }
        // Read-ahead resolves streams through the same chain the player does.
        val defaultDataSourceFactory = DefaultDataSource.Factory(this, resolvingFactory)
        AudioCache.setUpstream(defaultDataSourceFactory)
        mediaSourceFactory = DefaultMediaSourceFactory(AudioCache.playbackFactory(defaultDataSourceFactory))
            .setLoadErrorHandlingPolicy(PermanentAwareLoadErrorPolicy())

        val exoPlayer = buildPlayer(spatialAudioProcessorA, transitionFilterA, ownsSession = true)
        val sparePlayer = buildPlayer(spatialAudioProcessorB, transitionFilterB, ownsSession = false)
        player = exoPlayer
        spare = sparePlayer
        // Both sinks feed the same session id, so the system equalizer and any
        // other effect attached to the app applies to whichever player happens
        // to be audible. Without it a crossfade would audibly change EQ halfway
        // through, and again at every handoff.
        sparePlayer.audioSessionId = exoPlayer.audioSessionId

        AppSettings.audioSessionId.value = exoPlayer.audioSessionId
        applySettings(exoPlayer)
        applySettings(sparePlayer)
        observeSettings()
        observeScrobbling()
        observeDiscord()
        watchSleepTimer()
        // Before the listener below is attached, so loading the queue doesn't
        // read as a track change and set the read-ahead going.
        restoreLastQueue(exoPlayer)
        // …but the widgets do want to know: a service woken by a widget's own
        // play button has just recovered the track they should be showing, and
        // nothing else in this class will mention it until playback starts.
        publishWidgetState()

        // History pings fire once a track is actually audible — both when
        // playback starts and when the queue moves on while already playing.
        lastRepeatMode = exoPlayer.repeatMode
        exoPlayer.addListener(playbackListener)
        loadAutoplayForCurrentTrack()

        // Only the analytics listener reports the format the audio renderer was
        // configured with. Treated as a trigger rather than a source: the
        // publisher reads the format off the player, so it can't go stale
        // against the track the bitrate is looked up for.
        exoPlayer.addAnalyticsListener(formatListener)

        reportProgress()

        val controller = CrossfadeController(
            scope,
            active = { requireNotNull(player) },
            standby = { requireNotNull(spare) },
            onHandoff = ::adoptPlayer,
            analysisFor = { item -> trackAnalyzer.analysisFor(item.mediaId) },
            requestAnalysis = { item, durationMs ->
                item.localConfiguration?.uri?.let { uri ->
                    trackAnalyzer.request(item.mediaId, uri, durationMs / 1000.0)
                }
            },
            // "Incoming" and "outgoing" are roles, not players. The controller
            // only ever filters after the handoff, by which point the incoming
            // track is on the session player and the outgoing one is on the
            // spare — so these read the role fields fresh on every call rather
            // than closing over an instance that will have changed hands.
            filters = object : TransitionFilters {
                override fun incoming(lowPassHz: Float, highPassHz: Float) =
                    activeFilter.setCutoffs(lowPassHz, highPassHz)

                override fun outgoing(lowPassHz: Float, highPassHz: Float) =
                    spareFilter.setCutoffs(lowPassHz, highPassHz)
            },
            analysisRunningFor = { item -> trackAnalyzer.isAnalysing(item.mediaId) },
        )
        crossfade = controller
        controller.start()

        mediaSession = MediaSession.Builder(this, SessionPlayer(exoPlayer, controller))
            .setId(SESSION_ID)
            .setSessionActivity(sessionActivity())
            .setCallback(sessionCallback)
            .build()
        mediaSession?.setCustomLayout(notificationButtons())
    }

    /**
     * The one custom layout advertised to all Media3 control surfaces.
     *
     * AutoPlay is deliberately not here. It stays a player-screen control: the
     * session command remains available so [toggleAutoplay] still routes through
     * this service, it just isn't offered as a notification button.
     */
    private fun notificationButtons(): List<CommandButton> {
        val favorite = CommandButton.Builder(
            if (LikeState.overrides.value[player?.currentMediaItem?.mediaId] == LikeStatus.LIKE) {
                CommandButton.ICON_HEART_FILLED
            } else {
                CommandButton.ICON_HEART_UNFILLED
            },
        )
            .setSessionCommand(favoriteCommand)
            .setDisplayName("Favorite")
            .build()
        val shuffleEnabled = QueueShuffle.enabled.value
        val shuffle = CommandButton.Builder(
            if (shuffleEnabled) {
                CommandButton.ICON_SHUFFLE_ON
            } else {
                CommandButton.ICON_SHUFFLE_OFF
            },
        )
            .setSessionCommand(shuffleCommand)
            .setDisplayName(if (shuffleEnabled) "Shuffle off" else "Shuffle on")
            .build()
        return listOf(favorite, shuffle)
    }

    private fun toggleShuffleFromNotification() {
        player?.let(QueueShuffle::toggle)
        mediaSession?.setCustomLayout(notificationButtons())
    }

    private fun toggleAutoplayFromNotification() {
        val enabled = !AppSettings.autoplay.value
        AppSettings.setAutoplay(enabled)
        if (enabled) {
            autoplayLoadJob?.cancel()
            autoplayLoadJob = null
            autoplaySeed = null
            loadAutoplayForCurrentTrack()
        } else {
            autoplayLoadJob?.cancel()
            autoplayLoadJob = null
            autoplaySeed = null
            dropAutoplayTracksFromQueue()
            // Switching AutoPlay off is the listener saying they don't want
            // those tracks; leaving a stash behind would put them back the next
            // time repeat-all ended.
            repeatAllStash = emptyList()
            repeatAllStashSeed = null
        }
        mediaSession?.setCustomLayout(notificationButtons())
    }

    /**
     * Tops the queue back up to [MAX_QUEUED_AUTOPLAY] AutoPlay-suggested tracks
     * ahead of whatever is currently playing. Run on every track change rather
     * than only once the queue runs dry, so a freshly played suggestion is
     * replaced by a new one appended after the ones still waiting instead of
     * everything arriving in one burst at the end of the queue.
     */
    private fun loadAutoplayForCurrentTrack() {
        val exoPlayer = player ?: return
        if (!AppSettings.autoplay.value || exoPlayer.repeatMode == Player.REPEAT_MODE_ALL) {
            return
        }
        val current = exoPlayer.currentMediaItem?.toSong() ?: return
        if (AppSettings.dontRepeatSuggestions.value) sessionSongHistory += current
        val queuedAutoplay = (exoPlayer.currentMediaItemIndex + 1 until exoPlayer.mediaItemCount)
            .count { exoPlayer.getMediaItemAt(it).fromAutoplay }
        if (queuedAutoplay >= AUTOPLAY_LOW_WATER_MARK) return
        val needed = MAX_QUEUED_AUTOPLAY - queuedAutoplay
        if (needed <= 0) return
        if (autoplaySeed == current.videoId && autoplayLoadJob?.isActive == true) {
            TrackLog.d("YZ Music", "AUTOPLAY_DUPLICATE_SUPPRESSED: already active for ${current.videoId}", about = current.videoId)
            return
        }
        autoplaySeed = current.videoId
        TrackLog.d("YZ Music", "AUTOPLAY_START: seed=${current.videoId}, queuedAutoplay=$queuedAutoplay, needed=$needed", about = current.videoId)
        autoplayLoadJob = scope.launch {
            val queueSongs = (0 until exoPlayer.mediaItemCount)
                .map { exoPlayer.getMediaItemAt(it).toSong() }
            val existing = if (AppSettings.dontRepeatSuggestions.value) {
                queueSongs + sessionSongHistory
            } else {
                queueSongs
            }
            loadAutoplayTracks(existing, current, needed)
                .onSuccess { resolved ->
                    val activePlayer = player ?: return@onSuccess
                    if (!AppSettings.autoplay.value ||
                        activePlayer.currentMediaItem?.mediaId != current.videoId
                    ) {
                        TrackLog.d("YZ Music", "AUTOPLAY_SKIPPED: player state changed before insertion", about = current.videoId)
                        return@onSuccess
                    }
                    val currentQueueIds = (0 until activePlayer.mediaItemCount)
                        .mapNotNull { activePlayer.getMediaItemAt(it).mediaId }
                        .toSet()
                    val toAppend = resolved.filter { it.videoId !in currentQueueIds }
                    if (toAppend.isNotEmpty()) {
                        activePlayer.addMediaItems(toAppend.map { it.toMediaItem() })
                        TrackLog.d("YZ Music", "AUTOPLAY_APPENDED: ${toAppend.size} items (queue size now ${activePlayer.mediaItemCount})", about = current.videoId)
                    }
                    if (AppSettings.dontRepeatSuggestions.value) sessionSongHistory += resolved
                }
                .onFailure {
                    TrackLog.w("YZ Music", "AUTOPLAY_FETCH_FAILURE: notification autoplay failed: ${it.message}", about = current.videoId)
                }
        }
    }

    /**
     * Takes back what AutoPlay queued and hasn't played yet — what switching
     * AutoPlay off means for a queue it has already been extending. Removed
     * from the bottom up so the indexes ahead of each removal still hold, and
     * handed back in queue order for the one caller that intends to put them
     * in again.
     */
    private fun dropAutoplayTracksFromQueue(): List<MediaItem> {
        val exoPlayer = player ?: return emptyList()
        val dropped = mutableListOf<MediaItem>()
        for (index in exoPlayer.mediaItemCount - 1 downTo exoPlayer.currentMediaItemIndex + 1) {
            val item = exoPlayer.getMediaItemAt(index)
            if (!item.fromAutoplay) continue
            dropped += item
            exoPlayer.removeMediaItem(index)
        }
        return dropped.reversed()
    }

    /** Clears the queue's AutoPlay tail for the duration of repeat-all, keeping it to put back. */
    private fun stashAutoplayTracks() {
        val exoPlayer = player ?: return
        // Only ever taken once per stretch of repeat-all: cycling
        // OFF -> ALL -> ONE -> OFF sets the mode three times, and the second
        // and third of those must not overwrite a full stash with the empty
        // queue tail the first one left behind.
        if (repeatAllStash.isNotEmpty()) return
        val dropped = dropAutoplayTracksFromQueue()
        if (dropped.isEmpty()) return
        repeatAllStash = dropped
        repeatAllStashSeed = exoPlayer.currentMediaItem?.mediaId
    }

    /**
     * Puts the stashed AutoPlay tracks back when repeat-all ends.
     *
     * Refused, rather than forced, in the cases where the stash no longer
     * describes the queue: AutoPlay switched off while the loop ran, or the
     * loop played on past the track the stash was taken behind. Both leave
     * [loadAutoplayForCurrentTrack] to fill the queue the ordinary way.
     */
    private fun restoreAutoplayTracks() {
        val exoPlayer = player ?: return
        val stashed = repeatAllStash
        val seed = repeatAllStashSeed
        repeatAllStash = emptyList()
        repeatAllStashSeed = null
        // The seed is stale now either way, so the queue can be topped up again
        // for this track — without this the guard in [loadAutoplayForCurrentTrack]
        // reads a track it has already loaded for and returns, which is how a
        // queue whose stash was refused ended up with nothing after it at all.
        autoplayLoadJob?.cancel()
        autoplayLoadJob = null
        autoplaySeed = null
        if (stashed.isEmpty() || !AppSettings.autoplay.value) return
        if (exoPlayer.currentMediaItem?.mediaId != seed) return
        // A track the listener queued by hand during the loop is not queued
        // twice for having been in the mix before it.
        val present = (0 until exoPlayer.mediaItemCount)
            .mapTo(mutableSetOf()) { exoPlayer.getMediaItemAt(it).mediaId }
        val restored = stashed.filter { it.mediaId !in present }
        if (restored.isEmpty()) return
        exoPlayer.addMediaItems(restored)
    }

    private fun toggleFavoriteFromNotification(videoId: String) {
        favoriteActionJob?.cancel()
        val previous = LikeState.overrides.value[videoId] ?: LikeStatus.INDIFFERENT
        val target = if (previous == LikeStatus.LIKE) {
            LikeStatus.INDIFFERENT
        } else {
            LikeStatus.LIKE
        }

        // Match the player UI: update both surfaces immediately, then reconcile
        // the optimistic state with YouTube in the background.
        LikeState.set(videoId, target)
        mediaSession?.setCustomLayout(notificationButtons())
        favoriteActionJob = scope.launch {
            YtMusicRepository.rate(videoId, target)
                .onFailure {
                    LikeState.set(videoId, previous)
                    mediaSession?.setCustomLayout(notificationButtons())
                    TrackLog.w("YZ Music", "notification favorite failed: ${it.message}", about = videoId)
                }
        }
    }

    /**
     * Both players, built identically. Only [ownsSession] differs, and only at
     * construction — it moves at every handoff, see [setSessionOwner].
     *
     * They share the media source factory, so whichever one is arming reads from
     * the same on-disk cache the other is playing out of rather than
     * re-resolving a stream URL for audio that is already local.
     */
    private fun buildPlayer(
        spatial: SpatialAudioProcessor,
        filter: TransitionFilterProcessor,
        ownsSession: Boolean,
    ): ExoPlayer = ExoPlayer.Builder(this)
        .setRenderersFactory(silenceSkippingRenderers(spatial, filter))
        .setMediaSourceFactory(requireNotNull(mediaSourceFactory))
        .setLoadControl(farBufferingLoadControl())
        .setAudioAttributes(AUDIO_ATTRIBUTES, /* handleAudioFocus = */ ownsSession)
        .setHandleAudioBecomingNoisy(ownsSession)
        // Back restarts the track once you're this far into it; only a
        // press before that steps to the previous one.
        .setMaxSeekToPreviousPositionMs(BACK_RESTARTS_AFTER_MS)
        .build()

    /**
     * Moves the session onto the player the crossfade has just started the
     * incoming track on. This is the whole of the handoff: no seek, no
     * re-buffer, and no audio rendered twice.
     *
     * Order matters in one place — focus is released on the outgoing player
     * *before* the incoming one asks for it, so the app never holds two focus
     * requests at once and never briefly holds none.
     */
    private fun adoptPlayer(outgoing: ExoPlayer, incoming: ExoPlayer) {
        setSessionOwner(outgoing, owns = false)
        setSessionOwner(incoming, owns = true)

        outgoing.removeListener(playbackListener)
        outgoing.removeAnalyticsListener(formatListener)
        // The fields move before the listeners are attached, so anything the
        // first callback reads already describes the new arrangement.
        player = incoming
        spare = outgoing
        val heldFilter = activeFilter
        activeFilter = spareFilter
        spareFilter = heldFilter
        incoming.addListener(playbackListener)
        incoming.addAnalyticsListener(formatListener)

        mediaSession?.player = SessionPlayer(incoming, requireNotNull(crossfade))

        // The queue moving on used to arrive here as an item transition on the
        // one player that owned the queue. It cannot any more — the incoming
        // track started as its own player's *first* item, which fires on a
        // player nothing was listening to yet — so the bookkeeping that hung off
        // that callback is driven explicitly instead. Without this the crossfade
        // would silently stop scrobbling, stop writing history, stop honouring
        // "sleep after this song" and stop reading ahead.
        onTrackBecameCurrent(
            incoming.currentMediaItem,
            previousEnded = true,
            reason = Player.MEDIA_ITEM_TRANSITION_REASON_AUTO,
            alreadyAudible = true,
        )
        // Autoplay replenishment: ensure stale state from outgoing player cannot
        // suppress or interfere with the newly adopted player.
        autoplayLoadJob?.cancel()
        autoplayLoadJob = null
        autoplaySeed = null
        loadAutoplayForCurrentTrack()
        mediaSession?.setCustomLayout(notificationButtons())
    }

    /**
     * Only one player may handle audio focus at a time.
     *
     * Two focus-handling players in one process fight each other: the standby
     * taking focus as it starts would have Media3 pause the player that lost it,
     * cutting the outgoing track dead instead of fading it. Focus follows the
     * session, and so does "becoming noisy" — unplugging headphones should pause
     * the song you are listening to, which is whichever one the session is on.
     */
    private fun setSessionOwner(target: ExoPlayer, owns: Boolean) {
        target.setAudioAttributes(AUDIO_ATTRIBUTES, /* handleAudioFocus = */ owns)
        target.setHandleAudioBecomingNoisy(owns)
    }

    /**
     * Where a tap on the session lands. Media3 uses this both as the media
     * notification's contentIntent and as the session activity handed to the
     * platform MediaSession.
     *
     * This is not cosmetic on One UI: Samsung's Now Bar / Live Notification
     * chip is a launcher for the session, so a session that advertises nowhere
     * to go is skipped and only the plain shade notification survives. Same
     * reason the notification itself was previously un-tappable.
     */
    private fun sessionActivity(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java)
            .setAction(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            // MainActivity is singleTask, so this resumes the existing task
            // rather than stacking a second copy of the UI.
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun registerCurrentPlay() {
        player?.currentMediaItem?.mediaId?.let(PlaybackTracker::onPlaying)
    }

    /**
     * Everything that has to happen when a different song becomes the one
     * playing: history, scrobbles, ListenBrainz, the sleep timer, read-ahead
     * and the second look for a better copy.
     *
     * Called from two places, and it has to be, because there are now two ways
     * for the current song to change. ExoPlayer's own item transition covers
     * the ordinary ones — the queue advancing, a skip, a repeat. A crossfade
     * covers none of them: the incoming track starts life as the *first* item
     * of the other player, which fires a transition on a player nothing is
     * listening to yet, so [adoptPlayer] calls this by hand at the handoff.
     * Before that split existed this logic lived inside the callback, and
     * moving to two players would have silently stopped every crossfaded track
     * from being scrobbled, recorded, or read ahead for.
     *
     * @param previousEnded whether the song being replaced ran to its end, as
     *   opposed to being skipped past. Only an ended song is a listen.
     * @param alreadyAudible whether the track was already sounding when it
     *   became current, which is only true of a crossfade handoff.
     */
    private fun onTrackBecameCurrent(
        mediaItem: MediaItem?,
        previousEnded: Boolean,
        reason: Int,
        alreadyAudible: Boolean = false,
    ) {
        val exoPlayer = player ?: return
        // A *different* track is a clean slate for [recoverFrom], and so is the
        // same track becoming current for any reason other than that method's
        // own retry. The distinction is the whole of the reported loading loop.
        //
        // This was an unconditional clear, on the reasoning that the count exists
        // to stop one broken stream looping rather than to hold a grudge for the
        // session — and that reasoning is right about the listener pressing play
        // again, which is why it is kept below. What it missed is that "a track
        // became current" is not the same event as "something other than the
        // retry happened": ExoPlayer fires a transition for PLAYLIST_CHANGED and
        // for SEEK, and [recoverFrom]'s recovery *is* a seek — so the counter was
        // reset by the very retries it was counting. The report shows four resets
        // in 2m41s, each followed by a fresh "attempt 1", eight failures against
        // a budget of two, and roughly twenty-seven full resolve walks for one
        // unplayable track. [retryingMediaId] is the one case that must not
        // reset; everything else still does.
        val becameCurrent = mediaItem?.mediaId
        if (becameCurrent == null || becameCurrent != retryingMediaId) {
            recoveries.clear()
        }
        retryingMediaId = null

        // Where the wait starts, for the log in onIsPlayingChanged — unless
        // there was no wait. A crossfaded track has been audible for as long as
        // it has been current, so `onIsPlayingChanged` will never fire for it
        // and an armed timer would sit there until some unrelated buffering
        // blip tripped it, reporting a wait of seconds for a track that started
        // instantly. Measured one at 16871ms.
        trackSelectedAt = if (alreadyAudible) null else SystemClock.elapsedRealtime()
        if (alreadyAudible) {
            TrackLog.d(
                "YZ Music",
                "TIMING first audio: 0ms, the crossfade covered it",
                about = mediaItem?.mediaId,
            )
        }
        // And the same instant on the wall clock, which is the one
        // logcat stamps its lines with — see [TrackLog].
        mediaItem?.mediaId?.let(TrackLog::onTrackStarted)
        TrackLog.d(
            "YZ Music",
            "TIMING track selected: ${mediaItem?.mediaId} (reason=$reason)",
            about = mediaItem?.mediaId,
        )

        // currentPosition already belongs to the new item by now, so
        // the outgoing track is closed out on the last sampled value.
        PlaybackTracker.onTrackChanged(lastPositionSeconds)
        lastPositionSeconds = 0

        // Scrobbling: stop old song, start new song
        scrobbleManager?.onSongStop()
        // And the local record, which needs the transition even when the track
        // id doesn't change: repeat-one plays the same song again, and without
        // this the second play through is a continuation of the first and is
        // never counted.
        ListeningRecorder.onStopped()
        val newSong = mediaItem?.toSong()
        val durationMs = exoPlayer.duration.takeIf { it > 0 }
        if (exoPlayer.isPlaying) {
            scrobbleManager?.onSongStart(newSong, durationMs)
        }

        // ListenBrainz: submit finished for old song, playing_now for new song.
        // The finished listen only counts when the track actually ended —
        // an auto-advance, a repeat, or a crossfade at the very end. A
        // manual skip (SEEK) means the song wasn't listened to, so it must
        // not be scrobbled.
        val ended = previousEnded
