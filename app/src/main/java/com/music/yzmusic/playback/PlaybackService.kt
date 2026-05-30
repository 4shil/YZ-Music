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
        val prevSong = listenBrainzSong
        val prevStart = listenBrainzStartMs
        if (prevSong != null && ended && prevStart > 0L) {
            submitListenBrainzFinished(prevSong, prevStart, listenBrainzDurationMs)
        }
        listenBrainzSong = newSong
        listenBrainzStartMs = if (exoPlayer.isPlaying) System.currentTimeMillis() else 0L
        listenBrainzDurationMs = durationMs
        if (newSong != null && exoPlayer.isPlaying) {
            submitListenBrainzPlayingNow(newSong, 0L, durationMs)
        }

        // Discord: the whole of "live updating" for a card whose bar Discord
        // draws itself. Only a track change needs a new presence; the countdown
        // in between is Discord's own arithmetic.
        if (exoPlayer.isPlaying) pushDiscordPresence(exoPlayer)

        // "Sleep after this song": the queue moving on by itself is the
        // moment the track the user meant has finished. REPEAT counts
        // too, or the timer would never fire with repeat-one on.
        if (ended && SleepTimer.afterTrack.value) {
            exoPlayer.pause()
            SleepTimer.cancel()
        }
        if (exoPlayer.isPlaying) registerCurrentPlay()
        prefetchAround(exoPlayer)
        // The second look belongs to the track it was started for; the
        // queue moving on ends it, whatever it had found — and starts
        // the new track's own, which nothing else here would. The
        // track arriving has usually been resolved already, by
        // ExoPlayer preparing the next item while this one played, so
        // it is pending by now; the ones that aren't are picked up by
        // the sampler in [reportProgress].
        upgradeJob?.cancel()
        lookForBetterCopy(exoPlayer)
        saveQueue()
        // Covers crossfades too: a blended advance never reaches
        // onMediaItemTransition, and [adoptPlayer] calls this handler by hand.
        publishWidgetState()
        // Cleared rather than re-published. The renderer is still
        // configured for the track that just ended at this point, so
        // reading the format here reports the *previous* song — which
        // is how a lossy track spent its whole resolve showing the
        // "Hi-Res Lossless" badge the track before it had earned.
        // Nothing measured is better than something wrong, and the
        // gap is exactly when "Loading lossless" should be showing
        // instead. The periodic sampler below and
        // onAudioInputFormatChanged both re-publish once the decoder
        // has actually settled on this track, so the same-format case
        // the old call was here to cover is still covered.
        NerdStats.current.value = null
    }

    /**
     * Loads the queue from the last session so the app opens on the track it
     * was left on, rather than with nothing in the mini player.
     *
     * Deliberately no `prepare()`. Preparing would resolve the stream — a
     * NewPipe extraction over the network — on every cold start, for a track
     * that may never be played, and would post a media notification for a
     * session nobody has touched yet (Media3 shows one as soon as the player
     * leaves IDLE with a non-empty queue). Left idle, restoring costs nothing:
     * [MediaSession] routes every play request through
     * `Util.handlePlayButtonAction`, which prepares an idle player first, so
     * the mini player, the notification and Bluetooth all resume from here
     * without knowing the queue was cold.
     */
    private fun restoreLastQueue(player: ExoPlayer) {
        val last = LastPlayed.load() ?: return
        player.setMediaItems(
            last.songs.map { it.toMediaItem() },
            last.index,
            last.positionMs,
        )
    }

    /** The background hunt for a better copy of whatever is playing. */
    private var upgradeJob: Job? = null

    /** Which track [upgradeJob] is hunting for — see [lookForBetterCopy]. */
    private var upgradeFor: String? = null

    /**
     * How many times each track has been picked up off the floor, so a stream
     * that fails the same way every time stops rather than loops. Reset when
     * the queue genuinely moves on, not when a track merely re-prepares.
     */
    private val recoveries = mutableMapOf<String, Int>()

    /**
     * The track [recoverFrom] is about to seek-and-prepare, so that the
     * transition its own retry may fire can be told from the queue moving on or
     * the listener asking again — see [onTrackBecameCurrent]. Cleared by the
     * transition it describes, the same way [swappingMediaId] is.
     */
    private var retryingMediaId: String? = null

    /**
     * Media3's retry budget, with one thing added: a load error that cannot
     * succeed on a second attempt does not get one.
     *
     * There was no policy here at all, which meant
     * [DefaultLoadErrorHandlingPolicy] — three tries at a 1s/2s/3s backoff,
     * against *any* IOException. Stacked on top of this service's own
     * [MAX_RECOVERIES] counter and read-ahead's independent resolves, that is
     * where the "infinite loading" came from: the app logged
     * "leaving it alone" after its third attempt, and then Media3 quietly
     * started a fourth on its own schedule — visible in the report as a full
     * resolve walk beginning with no track selection before it, forty seconds
     * after the app had given up. Nothing in the log named it, because nothing
     * in the app had asked for it.
     *
     * Only [StreamResolver.PermanentlyUnplayableException] is refused, and it is
     * refused rather than delayed because the resolver has already established
     * the answer cannot change — it is the type it uses to say exactly that.
     * Everything else keeps the default behaviour, which is right: a shaped
     * response or a dropped connection is worth another go.
     */
    private class PermanentAwareLoadErrorPolicy : DefaultLoadErrorHandlingPolicy() {
        override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
            if (isPermanent(loadErrorInfo.exception)) return C.TIME_UNSET
            return super.getRetryDelayMsFor(loadErrorInfo)
        }

        private fun isPermanent(error: Throwable?): Boolean {
            var cause = error
            var depth = 0
            while (cause != null && depth++ < CAUSE_DEPTH) {
                if (cause is StreamResolver.PermanentlyUnplayableException) return true
                cause = cause.cause?.takeIf { it !== cause }
            }
            return false
        }

        private companion object {
            /** Media3 and the coroutine machinery both wrap; nothing nests deeper than this. */
            const val CAUSE_DEPTH = 8
        }
    }

    /**
     * Puts a track that died mid-read back on its feet.
     *
     * Two things get thrown away before trying again, because both have been
     * seen to be the actual fault and neither is visible from the exception:
     *
     *  - The cached bytes. An entry filled from two different files reads
     *    fine until playback reaches the seam and then throws forever, and no
     *    number of retries against the same entry will do anything else.
     *  - The choice of who serves the track. If the source that was picked is
     *    the one handing over something unreadable, resolving again from
     *    scratch is the only way to land anywhere else.
     *
     * The position is kept: this should look like a hiccup, not like the song
     * starting over.
     */
    private fun recoverFrom(error: PlaybackException, player: ExoPlayer) {
        val item = player.currentMediaItem ?: return
        val mediaId = item.mediaId
        val uri = item.localConfiguration?.uri
        val position = player.currentPosition.coerceAtLeast(0L)
        val attempts = recoveries.getOrDefault(mediaId, 0) + 1
        recoveries[mediaId] = attempts
        TrackLog.w(
            "YZ Music",
            "playback failed for $mediaId at ${position}ms (${error.errorCodeName}), attempt $attempts",
            error,
            about = mediaId,
        )
        // A local file that is not there is the one failure retrying cannot
        // touch, and the only one where the fix is to stop asking for the file.
        //
        // Everything below this point recovers a *stream*: it discards cached
        // bytes, releases the choice of who serves the track, and prepares the
        // same item again. Against `file:///…/Drake - Janice STFU.m4a` that is
        // all wasted — the uri is baked into the item already in the timeline,
        // so `prepare()` reopens the identical dead path and fails identically.
        // Observed as eight `ERROR_CODE_IO_FILE_NOT_FOUND`s in three seconds
        // against a download whose file had been deleted from a file manager,
        // ending in a track that simply refused to play.
        //
        // Rebuilding the item without its local uri is what turns that into a
        // stream, and dropping the record is what stops the next play walking
        // into the same hole. Deliberately ahead of the verdict and the attempt
        // budget below: this is not an attempt spent, it is a different source.
        if (error.errorCode == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND &&
            restreamMissingLocalFile(player, item, uri, position)
        ) {
            return
        }
        // Giving up on the *retry*, not on everything below it.
        //
        // A track that has exhausted its attempts is not finished with.
        // [recoveries] is cleared the moment any track becomes current, so the
        // listener who presses play again gets a fresh count — and used to get,
        // along with it, the exact URL that had just failed three times.
        // [StreamChoice] outlives this method by [StreamChoice.TTL_MS], and the
        // resolving factory reads it *before* it resolves anything, so the
        // replay failed instantly and in silence: no resolve logged, no lookup
        // attempted, and none of the refusals recorded below ever consulted,
        // because reaching them means getting as far as resolving. Fifteen
        // minutes of a track that cannot be played and does not even try, which
        // to the listener is a track that is permanently broken. Reported as
        // "sometimes songs don't play even if I've played it before", and the
        // 1.4 log of one shows it exactly — a selection, five seconds, a 404,
        // and not one resolver line in between.
        //
        // So everything from here to the discard runs either way, and only the
        // seek-and-prepare at the end is skipped.
        //
        // A verdict counts as exhausting the attempts immediately. The resolver
        // only throws [StreamResolver.PermanentlyUnplayableException] once it has
        // established that no client, no session and no extraction can serve the
        // track, so the two further attempts this would otherwise spend are two
        // more full walks for an answer already in hand — and in the report they
        // were exactly that, at roughly seventeen youtubei requests each.
        val verdict = permanentReason(error)
        val givingUp = verdict != null || attempts > MAX_RECOVERIES
        if (verdict != null) {
            TrackLog.w("YZ Music", "$mediaId cannot be played: $verdict", about = mediaId)
        } else if (givingUp) {
            TrackLog.w("YZ Music", "$mediaId has failed $attempts times; leaving it alone", about = mediaId)
        }
        // The upgraded rendition goes with the cache entry it lived in, so the
        // marker on the URI would otherwise point at nothing.
        QualityUpgrade.forget(mediaId)
        // A track that died on an upgraded URI died on the *upgrade*, and it
        // must not be offered that same swap again the moment it recovers.
        // Left unrecorded, the second look starts over on the retry, finds the
        // same FLAC at the same dead URL, cuts the audio for it again, and
        // fails again — twice more before [MAX_RECOVERIES] stops it. Observed
        // on a Tidal URL answering ERROR_CODE_IO_BAD_HTTP_STATUS.
        if (uri?.let(QualityUpgrade::cacheTag) != null) {
            QualityUpgrade.refuseUpgrades(mediaId)
        }
        // Whatever failed took its claimed format with it. The stream that
        // recovers is a different one and has not promised anything yet, so
        // leaving the old claim behind is how a badge earned by a FLAC ends up
        // sitting over the Opus that replaced it.
        NerdStats.clearDeclared(mediaId)
        // A track that died on a substituted stream died on the *substitution*,
        // and the retry must not be free to make the same one again. The lookup
        // behind it is deterministic and, by the second attempt, cached — so it
        // wins the race against YouTube by the same margin it won it the first
        // time and hands back the identical dead URL, until [MAX_RECOVERIES]
        // stops trying. That is a track that never plays at all while a working
        // YouTube URL sits in [StreamResolver]'s cache, resolved and unused.
        // The same reasoning as [QualityUpgrade.refuseUpgrades] above, for the
        // substitution that happens *before* the first note rather than after.
        // Read before the forget below, which is what clears the evidence.
        uri?.getQueryParameter("v")?.takeIf(StreamChoice::isSubstitute)?.let { videoId ->
            StreamChoice.refuseSubstitutes(videoId)
            TrackLog.w(
                "YZ Music",
                "$videoId broke on a substituted stream; YouTube serves it for now",
                about = mediaId,
            )
            // And no swapping back to it mid-song either: the second look asks
            // the same catalogues the same question and would cut the audio that
            // just recovered to land on the same refusal.
            QualityUpgrade.refuseUpgrades(videoId)
        }
        uri?.getQueryParameter("v")?.let(StreamChoice::forget)
        scope.launch(TrackLog.about(mediaId)) {
            // Long enough for the released source to let go of the cache keys
            // about to be removed, short enough to read as a stutter.
            delay(RECOVERY_DELAY_MS)
            uri?.let { withContext(Dispatchers.IO) { AudioCache.discard(it) } }
            // The bytes go even when nothing is going to be prepared after
            // them. A half-filled entry whose owner has just been forgotten is
            // the seam this file's [StreamChoice] note is about: the next play
            // resolves freely, lands on a different source, and streams it into
            // the middle of the last one. Releasing the choice without dropping
            // the bytes would trade one stuck track for a corrupt one.
            if (givingUp) {
                // A track nobody can play must not leave the player parked in
                // IDLE on it. Nothing else in this service calls prepare() again,
                // so before this the queue simply stopped: the notification kept
                // showing the song, the play button kept doing nothing, and from
                // the outside that is indistinguishable from a hung app — which
                // is what the report describes and what "it was stuck on my
                // phone too" means. Moving on is the only honest answer, and it
                // is only safe to do for a verdict: a track that merely ran out
                // of attempts may still be playable when the listener presses
                // play, and skipping past it would silently eat it.
                if (verdict != null) withContext(Dispatchers.Main) { skipPastUnplayable(mediaId, verdict) }
                return@launch
            }
            withContext(Dispatchers.Main) {
                val player = this@PlaybackService.player ?: return@withContext
                if (player.currentMediaItem?.mediaId != mediaId) return@withContext
                TrackLog.d("YZ Music", "retrying $mediaId from ${position}ms")
                // Claimed before the seek, so the transition it may fire is
                // recognised as this retry rather than read as a fresh start and
                // handed a fresh attempt budget.
                retryingMediaId = mediaId
                player.seekTo(player.currentMediaItemIndex, position)
                player.prepare()
            }
        }
    }

    /**
     * Swaps a track whose downloaded file has gone missing back onto a stream,
     * in place and at the same position.
     *
     * The file was downloaded and then deleted from under the app — a file
     * manager, a cleaner, a wiped SD card — leaving [Downloads]' record pointing
     * at nothing. [Song.toMediaItem] checks that record before it builds an
     * item, but only at build time: an item already sitting in the timeline was
     * built when the file was still there, and a queue restored by [LastPlayed]
     * carries the same stale uri back across a restart. This is the other end of
     * that, and the only one that can see the file is gone rather than guess.
     *
     * The record goes first, then the item is rebuilt from its own metadata with
     * the local uri stripped, which sends [Song.toMediaItem] down its streaming
     * branch. Position is kept, so this reads as the hiccup [recoverFrom] is
     * written around rather than the song starting over.
     *
     * @return false when this is not that situation and the caller should carry
     *   on with its ordinary stream recovery — including the case where stripping
     *   the local uri changes nothing, which is a device-library track whose
     *   mediaId *is* the missing file and for which there is no stream to fall
     *   back to. Returning true there would be a swap that fixes nothing, on a
     *   loop.
     */
    private fun restreamMissingLocalFile(
        player: ExoPlayer,
        item: MediaItem,
        uri: Uri?,
        position: Long,
    ): Boolean {
        val scheme = uri?.scheme
        if (scheme != "file" && scheme != "content") return false
        val mediaId = item.mediaId

        Downloads.forgetMissing(mediaId)
        val restreamed = item.toSong().copy(localUri = null, localPath = null).toMediaItem()
        if (restreamed.localConfiguration?.uri == uri) {
            TrackLog.w(
                "YZ Music",
                "$mediaId is a local file that is gone and has no stream behind it",
                about = mediaId,
            )
            return false
        }

        TrackLog.w(
            "YZ Music",
            "$mediaId was downloaded but $uri is gone; streaming it instead",
            about = mediaId,
        )
        // Not claimed as [retryingMediaId], unlike the seek-and-prepare retry
        // below: that flag exists to stop a retry against the *same* stream
        // refilling its own attempt budget, and this is a different source
        // entirely. Letting the transition clear the count is the right answer
        // here — a stream that has never been tried deserves the full budget.
        recoveries.remove(mediaId)
        player.replaceMediaItem(player.currentMediaItemIndex, restreamed)
        player.seekTo(player.currentMediaItemIndex, position)
        player.prepare()
        return true
    }

    /**
     * Leave a track the resolver has ruled out and carry on down the queue.
     *
     * The item is left in place rather than removed: the listener queued it, and
     * the reason it cannot be played is usually temporary in a way this service
     * cannot see the end of — signing in clears an age gate, and travelling
     * clears a region block. Removing it would quietly rewrite a queue on the
     * strength of a ten-minute verdict.
     *
     * With nothing after it there is nowhere to go, and stopping is then the
     * correct end state rather than a failure to recover: the error stays on the
     * player, which is what puts a message in front of the listener — see
     * `rememberPlayerState` in
     * [PlayerConnection][com.music.yzmusic.playback.PlayerConnection].
     */
    private fun skipPastUnplayable(mediaId: String, reason: String) {
        val exoPlayer = player ?: return
        if (exoPlayer.currentMediaItem?.mediaId != mediaId) return
        if (!exoPlayer.hasNextMediaItem()) {
            TrackLog.w("YZ Music", "$reason — and nothing after it in the queue", about = mediaId)
            return
        }
        TrackLog.w("YZ Music", "$reason — skipping to the next track", about = mediaId)
        exoPlayer.seekToNextMediaItem()
        // No play() here: an error does not clear playWhenReady, so prepare()
        // resumes exactly as far as the listener had asked for. Calling play()
        // would un-pause a queue they had paused.
        exoPlayer.prepare()
    }

    /**
     * Why [error] means "never", or null if it only means "not just now".
     *
     * Unwrapped by hand because the classification is the resolver's and the
     * exception has been through Media3's loader by the time it arrives:
     * `ExoPlaybackException` wrapping `Loader.UnexpectedLoaderException` wrapping
     * what the resolver actually threw. A shallow `is` check sees only the
     * outermost of those three.
     */
    private fun permanentReason(error: Throwable?): String? {
        var cause = error
        var depth = 0
        while (cause != null && depth++ < PERMANENT_CAUSE_DEPTH) {
            if (cause is StreamResolver.PermanentlyUnplayableException) {
                return cause.message ?: "This track cannot be played"
            }
            cause = cause.cause?.takeIf { it !== cause }
        }
        return null
    }

    /**
     * The track whose item this service is about to replace under it, so that
     * [Player.Listener.onMediaItemTransition] can tell a quality swap from the
     * queue actually moving on. Cleared by the transition it describes.
     */
    private var swappingMediaId: String? = null

    /**
     * When the audio was last cut for a quality swap, so the analytics listener
     * can say how long it stayed cut. Null except across a swap.
     */
    private var swapCutAt: Long? = null

    /**
     * The track [ExoPlayer.getAudioFormat] is currently describing.
     *
     * `audioFormat` is a property of the *renderer*, not of the queue item, and
     * it keeps naming the outgoing track's codec until the renderer has read a
     * sample of the incoming one. Anything that asks "what is playing right
     * now" in the moments after a transition is therefore told about the track
     * before it, and [adoptCachedTrack] is asked exactly there — a queue
     * advance is one of the places [lookForBetterCopy] runs from.
     *
     * Observed: 'Harleys In Hawaii' came up fifteen milliseconds after the
     * queue moved onto it, twenty seconds after the previous track had been
     * upgraded to FLAC. The renderer still said `audio/flac`, so a WebM Opus
     * stream — verified by the `1A 45 DF A3` on its cache entry — was written
     * off as "already lossless from cache" and, because that verdict is
     * recorded once and for good, never offered an upgrade again for the rest
     * of the session.
     */
    private var audioFormatFor: String? = null

    /**
     * Starts the second look for the playing track, if it settled for less
     * than was asked for — see [QualityUpgrade].
     *
     * Runs at most once per track: [QualityUpgrade.lookAgain] drops the track
     * from its pending set whatever the answer, so the repeated calls this
     * gets cost nothing after the first. It needs to be called from several
     * places for that reason — a track becomes eligible at a different moment
     * depending on how it was reached. Called only from
     * `onIsPlayingChanged`, it fired for the first track of a session and for
     * nothing after it: the queue advancing while already playing is not a
     * change in `isPlaying`, so every track but the first kept a lookup that
     * had already found its FLAC and was never asked for it.
     *
     * Eligibility has two sources, because being resolved and being played are
     * not the same event. A track the resolver saw is already marked; a track
     * served from the disk cache was never resolved at all and is judged here
     * instead — see [adoptCachedTrack] and [QualityUpgrade.adoptUnresolved].
     */
    private fun lookForBetterCopy(player: ExoPlayer) {
        val item = player.currentMediaItem ?: return
        val mediaId = item.mediaId
        val uri = item.localConfiguration?.uri
        val alreadyPending = QualityUpgrade.isPending(mediaId)
        val shelved = QualityUpgrade.shelvedFor(mediaId)
        if (shelved == null && !alreadyPending && !QualityUpgrade.couldStillUpgrade(mediaId, uri)) return
        if (upgradeJob?.isActive == true) {
            // Already hunting for this track. One left over from a track the
            // queue has moved past is a different matter: it can only come
            // back with an answer about a song nobody is listening to, and
            // until it does it holds the slot the current track needs.
            if (upgradeFor == mediaId) return
            upgradeJob?.cancel()
        }
        upgradeFor = mediaId
        if (alreadyPending) {
            TrackLog.d("YZ Music", "looking again for a better copy of $mediaId", about = mediaId)
        }
        upgradeJob = scope.launch(TrackLog.about(mediaId)) {
            // A previous visit to this track already did all the expensive
            // parts and lost the swap to a skip. Nothing about the answer has
            // gone stale — the stream is still parked and its bytes are still
            // on disk — so this goes straight to the swap and skips the ten
            // seconds of catalogue searching it would otherwise repeat.
            if (shelved != null) {
                TrackLog.d("YZ Music", "re-offering the upgrade already proved for $mediaId")
                NerdStats.onLosslessRaceStart(mediaId)
                try {
                    swapIn(mediaId, shelved)
                } finally {
                    NerdStats.onLosslessRaceEnd(mediaId)
                }
                return@launch
            }
            // The runtime the decoder reports is the only measured evidence
            // about what is playing, and everything downstream weighs
            // candidates against it — so it is worth a short wait rather than
            // a null. It is genuinely not known yet at some of the moments
            // this is called from: a queue advance runs its transition before
            // the item it moved onto has finished preparing.
            val playingSeconds = withTimeoutOrNull(DURATION_SETTLE_MS) {
                while (true) {
                    val ms = withContext(Dispatchers.Main) {
                        this@PlaybackService.player
                            ?.takeIf { it.currentMediaItem?.mediaId == mediaId }
                            ?.duration
                            ?: 0L
                    }
                    if (ms > 0) return@withTimeoutOrNull (ms / 1000).toInt()
                    delay(UPGRADE_PROVE_STEP_MS)
                }
                @Suppress("UNREACHABLE_CODE") null
            }
            // Re-asked rather than carried down from above, because the wait
            // is long enough for the answer to have changed: the resolver runs
            // on the loader thread and marks a track pending as it opens the
            // source, which for a track being fetched is precisely what has to
            // happen before the decoder can report the runtime waited for just
            // above. Reading the flag from before the wait meant a freshly
            // resolved track arrived here looking un-resolved, was handed to
            // the cached-track path, was refused by it for being pending, and
            // lost its upgrade until the next progress sample came round.
            if (!QualityUpgrade.isPending(mediaId) &&
                (uri == null || !adoptCachedTrack(mediaId, uri, playingSeconds))
            ) {
                return@launch
            }
            val better = withContext(Dispatchers.IO) {
                QualityUpgrade.lookAgain(mediaId, playingSeconds)
            } ?: return@launch
            try {
                swapIn(mediaId, better)
            } finally {
                // The badge comes down when the upgrade is done, not when the
                // search that found it was — including the deliberate wait in
                // [swapIn] before the audio is allowed to be cut. See
                // [QualityUpgrade.lookAgain]. In `finally` because a queue
                // that moves on cancels this job, and a cancelled swap has to
                // put the badge out as surely as a completed one.
                NerdStats.onLosslessRaceEnd(mediaId)
            }
        }
    }

    /**
     * Decides whether a track nothing resolved is worth a second look, now that
     * the decoder has settled enough to say what it is playing.
     *
     * Two questions that need the player rather than the queue entry:
     *
     *  - **What codec is actually coming out.** A cache entry can already hold
     *    the FLAC a previous session upgraded to, and hunting a lossless copy
     *    of a track that is already lossless buys a break in the audio for
     *    nothing. An unknown codec is not read as "lossy": it means the
     *    renderer has not been configured yet, so the track is left un-adopted
     *    and the progress sampler asks again a few seconds later. A codec the
     *    renderer is reporting for *some other track* gets the same treatment,
     *    and has to, because it is indistinguishable from an answer — see
     *    [audioFormatFor] for what it cost to read one on trust.
     *  - **Whether the listener owns the file.** A downloaded track resolves to
     *    its own copy on disk — see the resolving data source above, which
     *    answers it before the module race is ever reached, so a download has
     *    never been a candidate for substitution. Reproduced here because this
     *    path skips that resolver entirely; without it the second look would
     *    spend data replacing a file the user deliberately saved.
     *
     * @param durationSec the runtime the decoder reports, waited for by the
     *   caller — needed here to turn the size of the cache entry into a
     *   bitrate. See [cachedFloor].
     */
    private suspend fun adoptCachedTrack(mediaId: String, uri: Uri, durationSec: Int?): Boolean {
        val format = withContext(Dispatchers.Main) {
            player
                ?.takeIf { it.currentMediaItem?.mediaId == mediaId && audioFormatFor == mediaId }
                ?.audioFormat
        } ?: return false
        val mime = format.sampleMimeType ?: return false
        val videoId = uri.getQueryParameter("v") ?: return false
        val downloaded = com.music.yzmusic.download.Downloads.savedUri(this, videoId) != null
        if (downloaded) return false
        return QualityUpgrade.adoptUnresolved(
            mediaId = mediaId,
            uri = uri,
            target = SourceResolver.targetIn(uri),
            playingMime = mime,
            playing = withContext(Dispatchers.IO) { cachedFloor(uri, format, durationSec) },
        )
    }

    /**
     * How good the bytes already on disk are, in the only terms a track nothing
     * resolved can be measured in.
     *
     * Two measurements, in order of directness:
     *
     *  - **What the decoder says.** `Format.bitrate` is populated for the
     *    containers that carry the field, which for what YZ Music plays means
     *    MP4/AAC — the 320kbps copy a module served last session reports itself
     *    exactly.
     *  - **What the cache entry weighs.** Opus in WebM, which is what YouTube
     *    serves and so what most base entries hold, states no bitrate at all;
     *    but the rendition's full length is recorded in the cache index, and
     *    bytes over seconds *is* a bitrate. Slightly high, because container
     *    overhead counts toward the byte total and not toward the audio — which
     *    errs toward leaving the track alone, the right direction for a figure
     *    that decides whether to cut into playing audio.
     *
     * Null only when neither is available: an entry whose content length was
     * never recorded, or a runtime the decoder never reported. That is the old
     * behaviour of this path, and it is now the exception rather than the rule.
     *
     * The codec is deliberately not filled in. [StreamFormat.isLossless] reads
     * it, and a name carried over from the decoder's mime type would have to be
     * translated to be recognised — where being wrong means claiming a cached
     * stream is already lossless and abandoning the upgrade. Only the bitrate
     * is wanted here; [QualityUpgrade.adoptUnresolved] settles the lossless
     * question separately, from the mime type itself.
     */
    private fun cachedFloor(uri: Uri, format: Format, durationSec: Int?): StreamFormat? {
        format.bitrate.takeIf { it != Format.NO_VALUE && it > 0 }?.let {
            return StreamFormat(kbps = it / 1000)
        }
        val seconds = durationSec?.takeIf { it > 0 } ?: return null
        val bytes = AudioCache.contentLengthOf(uri).takeIf { it > 0 } ?: return null
        return StreamFormat(kbps = (bytes * 8 / seconds / 1000).toInt())
    }

    /** Where the playing track stands, read off the player in one hop. */
    private class SwapPoint(
        val item: MediaItem,
        val uri: String,
        val position: Long,
        val duration: Long,
    )

    /**
     * Replaces the playing track's audio with [stream], keeping the position.
     *
     * The break this causes is the whole cost of the feature, so the guards
     * are worth more than the swap is:
     *
     *  - The track must still be the one the search was started for. A skip
     *    during the lookup makes the answer worthless, not merely late.
     *  - There has to be enough of it left to be worth interrupting. Cutting
     *    the last few seconds of a song to improve the last few seconds of a
     *    song is a straight loss.
     *  - **The replacement has to be ready before anything is taken away.**
     *    See [auditionUpgrade]; this is what the break costs, so it is what
     *    the cut is bought against.
     *
     * The mechanism is [MediaItem.buildUpon] with a marked URI rather than a
     * new item: Media3 only rebuilds a media source when the replacement's
     * playback URI differs, so an item rebuilt identically would be accepted
     * and quietly keep playing the old stream.
     */
    private suspend fun swapIn(mediaId: String, stream: SourceStream) {
        val at = withContext(Dispatchers.Main) { swapPointFor(mediaId) } ?: return
        if (at.duration > 0 && at.duration - at.position < UPGRADE_MIN_REMAINING_MS) {
            TrackLog.d("YZ Music", "upgrade abandoned: only ${at.duration - at.position}ms of the track left")
            return
        }

        val upgradedUri = QualityUpgrade.upgradedUri(at.uri)
        // Whether the rendition entry already holds *this* stream's bytes,
        // asked before [force] overwrites the record of what filled it. True
        // only for a shelved upgrade being re-offered, where throwing the entry
        // away would mean paying for the same megabytes twice — and where
        // keeping it is safe for the one reason the discard exists: the file
        // under that key came from this very URL.
        val alreadyFilled = QualityUpgrade.forcedStream(Uri.parse(upgradedUri))?.url == stream.url
        // Parked before the audition rather than at the swap: the silent player
        // reaches its bytes through the same resolving data source the real one
        // does, and that is where a marked URI is turned back into a stream.
        QualityUpgrade.force(mediaId, stream)
        val warmedThrough = auditionUpgrade(mediaId, at, upgradedUri, stream, alreadyFilled)
        if (warmedThrough == null) {
            // Nothing was cut, so there is nothing to put back: the listener
            // keeps the stream they already had and never learns this
            // happened. Which is the point — this is the failure that used to
            // arrive as a break in the audio followed by the same lossy stream
            // returning a few seconds later. Dropping the parked stream stops
            // [QualityUpgrade.forcedStream] serving a URL that has just failed
            // to prove itself.
            QualityUpgrade.forget(mediaId)
            withContext(Dispatchers.IO) { AudioCache.discardRendition(Uri.parse(upgradedUri)) }
            return
        }

        // There used to be an unconditional five-second hold here, keyed off the
        // track's own position, so that an upgrade arriving with the first note
        // could not cut the song a millisecond in. Its own reasoning said it was
        // "almost always already past by now", and that turned out to be the
        // whole story: by the time this line is reached the search, the stream
        // lookup and the audition have all run, and the audition alone spends
        // seconds on the network. So the guard was not usually deciding to wait
        // — it was adding its five seconds to whatever the swap had already
        // cost, on exactly the tracks that had been quickest to find a better
        // copy. Removed rather than shortened: it is the crossfade grace below
        // that protects the case an upgrade can genuinely spoil, and it does so
        // by asking whether a transition actually happened rather than assuming
        // one might have.
        //
        // Never cut into a crossfade in flight. `replaceMediaItem` tears the
        // session player's source down and rebuilds it — CrossfadeController
        // is either syncing its tail player's position against that same
        // source (arming), riding a ~90ms handoff between the two (lapping),
        // or ramping volume off the incoming track's own position (fading),
        // and all three read a session-player discontinuity as either an
        // unrecognised seek (bail, with an audible ramp-out) or a progress
        // calculation reset to whatever position the new source opens at.
        // Either way the blend breaks rather than merely waits.
        //
        // Bounded so a stuck flag can never leave the upgrade waiting forever;
        // past the timeout this falls through to the same check made again,
        // authoritatively, below — so an unusually long-running crossfade
        // still gets one more look rather than being forced through.
        //
        // This loop is only the coarse wait. [crossfade] is read again inside
        // the `withContext` below with no suspension between that read and
        // `replaceMediaItem` — both run on the same single-threaded Main
        // dispatcher [scope] does — so that second check is the one this
        // logic actually depends on for correctness, not this one.
        var waitedForCrossfade = 0L
        while (withContext(Dispatchers.Main) { crossfade?.isTransitioning() } == true &&
            waitedForCrossfade < UPGRADE_CROSSFADE_WAIT_TIMEOUT_MS
        ) {
            delay(UPGRADE_CROSSFADE_POLL_MS)
            waitedForCrossfade += UPGRADE_CROSSFADE_POLL_MS
        }

        // Nor the instant one ends. The loop above releases on the tick the
        // blend completes, and a swap made there lands its cut a few hundred
        // milliseconds after the incoming track finally stood alone: the
        // listener hears the mix land and the music stop, in that order, which
        // reads as the transition having broken rather than as a track quietly
        // getting better. This is the one delay on this path, and it is why the
        // blanket one above it could go: a hold measured from the track's own
        // start never covered this case anyway, since an Automix hands over at a
        // cue point that can be well past it.
        //
        // Keyed off when a transition last ended rather than off whether the
        // loop above actually waited, so the same grace covers an upgrade
        // shelved by the check below and re-offered moments later — the same
        // swap, the same few seconds after the same blend, arriving by a
        // different route. And nothing is held back on a track nowhere near a
        // transition: the reading is then already long past the grace.
        withContext(Dispatchers.Main) { crossfade?.msSinceTransition() }?.let { since ->
            if (since < UPGRADE_AFTER_CROSSFADE_MS) {
                val settle = UPGRADE_AFTER_CROSSFADE_MS - since
                TrackLog.d("YZ Music", "upgrade for $mediaId holding ${settle}ms; a transition just ended")
                delay(settle)
            }
        }

        withContext(Dispatchers.Main) {
            val now = swapPointFor(mediaId)
            if (crossfade?.isTransitioning() == true) {
                // Caught right before the swap that would have broken it —
                // everything spent proving this stream is still worth keeping
                // for next time rather than throwing away, exactly like the
                // "queue moved on" case just below.
                QualityUpgrade.shelve(mediaId, stream)
                TrackLog.d("YZ Music", "upgrade for $mediaId shelved: a crossfade was still running")
                return@withContext
            }
            if (now == null) {
                // The queue moved on between the upgrade being proved and the
                // swap being made — a skip, or a track that ran out. Everything
                // this cost is still in hand, so it goes on the shelf rather
                // than in the bin; see [QualityUpgrade.shelve]. Logged because
                // this used to be the one exit here that left no trace at all,
                // and from the logs "found a FLAC, cached it, swapped nothing"
                // was indistinguishable from never having looked.
                QualityUpgrade.shelve(mediaId, stream)
                TrackLog.d("YZ Music", "upgrade for $mediaId proved but the queue moved on; shelved")
                return@withContext
            }
            val player = player ?: return@withContext
            if (now.duration > 0 && now.duration - now.position < UPGRADE_MIN_REMAINING_MS) {
                TrackLog.d("YZ Music", "upgrade abandoned: only ${now.duration - now.position}ms of the track left")
                QualityUpgrade.forget(mediaId)
                return@withContext
            }
            // The parked stream can be taken away underneath a swap in flight:
            // a playback failure on the *old* stream runs [recoverFrom], which
            // forgets the pending upgrade along with everything else it clears.
            // Swapping onto a marked URI with nothing parked behind it would
            // send the resolver off to find a stream of its own and write it
            // into the rendition entry the audition just filled — two files,
            // one key, which is the corruption the audition exists to avoid.
            if (QualityUpgrade.forcedStream(Uri.parse(upgradedUri)) == null) {
                TrackLog.d("YZ Music", "upgrade abandoned: its stream was dropped while it was being proved")
                return@withContext
            }
            // Not fatal, just slower than intended, and worth being able to see
            // in a log: the audition buffers ahead of a moving target and can
            // only lose that race on a connection that is barely keeping up.
            if (now.position > warmedThrough) {
                TrackLog.d(
                    "YZ Music",
                    "upgrade landing at ${now.position}ms, past the ${warmedThrough}ms warmed for it",
                )
            }

            // Read before the swap overwrites it — see [watchUpgrade]'s
            // NerdStats cleanup for why the pre-upgrade claim has to be
            // captured here rather than looked up again on revert.
            val previousFormat = NerdStats.declaredFormat(mediaId)
            swappingMediaId = mediaId
            swapCutAt = SystemClock.elapsedRealtime()
            player.replaceMediaItem(
                player.currentMediaItemIndex,
                now.item.buildUpon().setUri(upgradedUri).build(),
            )
            player.seekTo(player.currentMediaItemIndex, now.position)
            player.prepare()
            QualityUpgrade.unshelve(mediaId)
            TrackLog.d("YZ Music", "upgraded to ${stream.format.summary} at ${now.position}ms")
            watchUpgrade(mediaId, now.uri, now.position, now.duration, previousFormat)
            // The opening again, this time sized for Automix rather than for
            // a container header.
            //
            // An upgraded rendition is only ever fetched from the swap point
            // onward, so its first seconds are the one region nothing downloads
            // on its own — [UPGRADE_HEADER_BYTES] covers the header and stops
            // well short of enough *audio* to measure. A megabyte of lossless is
            // four seconds, against the twelve the analyzer needs, so a track
            // that upgrades early could never be analysed from any rendition:
            // the lossless copy had no audio at its head and the copy it
            // replaced was discarded.
            //
            // After the swap and off the main thread, because nothing waits on
            // it — the upgrade is already audible and this only decides whether
            // the *next* transition can be a real mix.
            launch(Dispatchers.IO) {
                AudioCache.warmRange(Uri.parse(upgradedUri), 0, ANALYSIS_HEAD_BYTES)
            }
        }
    }

    /** Main thread. Null unless [mediaId] is still current and still un-upgraded. */
    private fun swapPointFor(mediaId: String): SwapPoint? {
