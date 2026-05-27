package com.music.yzmusic.playback

import android.os.SystemClock
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.music.yzmusic.data.settings.AppSettings
import com.music.yzmusic.data.settings.SmartAnalysis
import com.music.yzmusic.data.settings.TrackAnalysisState
import com.music.yzmusic.data.settings.TransitionWindow
import com.music.yzmusic.playback.smart.CrossfadeMode
import com.music.yzmusic.playback.smart.TrackAnalysis
import com.music.yzmusic.playback.smart.TransitionStyle
import com.music.yzmusic.playback.smart.TransitionTrackInfo
import com.music.yzmusic.playback.smart.planTransition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.math.sin
import java.util.Locale

/**
 * A real crossfade: two tracks audible at once, the outgoing one falling as the
 * incoming one rises, the way Spotify and Apple Music do it.
 *
 * ## Why there are two players
 *
 * One ExoPlayer renders one queue item at a time, so at a track boundary there
 * is exactly one source and the gain it can be given is either 1 (no fade) or 0
 * (silence). The previous version of this class was a single-player volume
 * ramp, and that is precisely why it never sounded like a crossfade: it dipped
 * to silence at the join and climbed back out, leaving a hole where the blend
 * should be. Overlap needs a second decoder. There is no way around it.
 *
 * ## Which player plays what
 *
 * Two peers, not a player and a helper. Both are full ExoPlayers built the same
 * way and both can own the queue; at any instant one of them *is* the session
 * (it backs the MediaSession, holds audio focus and carries the notification)
 * and the other is idle. They swap roles at every transition.
 *
 *  - **[active]** — whichever player the session currently points at. The rest
 *    of the app only ever sees this one.
 *  - **[standby]** — the idle player. Between transitions it holds nothing. To
 *    arm a transition it is loaded with *the queue, positioned on the incoming
 *    track* at the plan's cue point, and started silently.
 *
 * The crucial word is **incoming**. An earlier version of this class put the
 * *outgoing* track on the second player: the session player jumped ahead to the
 * next song and the second player carried the old song's tail. That works, but
 * it forces a moment where both players render *the same audio*, and two
 * ExoPlayers cannot be started sample-accurately against each other. Whatever
 * they were misaligned by — measured on real transitions at 9 to 41ms — was
 * heard as the last instant of the outgoing track playing twice, at the head of
 * every single crossfade. No amount of tuning removes that; the duplication is
 * structural.
 *
 * Loading the *incoming* track on the standby removes it outright. The two
 * players never hold the same audio, so there is nothing to align, nothing to
 * hand over, and no seam to hide. The incoming track is simply already playing,
 * from exactly the right position, when its fader starts to move.
 *
 * ## The handoff
 *
 * Because both players own the queue, finishing a transition is a **role swap**
 * rather than a seek: nothing is re-buffered, nothing is re-sought, and no audio
 * is rendered twice. [onHandoff] is what performs it — the service moves the
 * MediaSession, audio focus, its listeners and its bookkeeping onto the incoming
 * player.
 *
 * It fires as the incoming track's first note sounds, not at the end of the
 * blend, which keeps the behaviour the old design was built around: the queue
 * index, the metadata, the notification and the UI all flip to the incoming song
 * the moment it becomes audible, rather than trailing the song on its way out.
 * From that instant [outgoing] is the idle player, still audible, being faded
 * out — which is exactly what the previous design used its tail player for, at
 * none of the cost.
 *
 * ## Curve
 *
 * `sin`/`cos` rather than the old `sqrt`: `sin²+cos²=1` exactly, so two tracks
 * fading past each other hold constant *power* the whole way through and the
 * transition has no dip in the middle. That is the standard crossfade law, and
 * it is what makes a long crossfade sound like a blend instead of a dip.
 */
@UnstableApi
class CrossfadeController(
    private val scope: CoroutineScope,
    /** The player backing the session right now. Moves at every [onHandoff]. */
    private val active: () -> ExoPlayer,
    /** The idle player, which the next transition will load the incoming track onto. */
    private val standby: () -> ExoPlayer,
    /**
     * Moves the session onto the player that has just started the incoming
     * track: the MediaSession's player, audio focus, the service's listeners and
     * everything it books against a track change.
     *
     * Called once per transition, at the instant the incoming track becomes
     * audible. After it returns, [active] must answer `incoming` and [standby]
     * must answer `outgoing` — this class re-reads neither during a transition,
     * but everything else in the service does.
     */
    private val onHandoff: (outgoing: ExoPlayer, incoming: ExoPlayer) -> Unit,
    /**
     * Stored Automix analysis for a media item, or an empty [TrackAnalysis]
     * when there is none yet. This is the seam Phase 1's DSP analyzer plugs
     * into: until analysis finishes, a track reads as "no evidence", which
     * [planTransition] answers with the same fixed-length crossfade this
     * class always ran before Automix existed.
     */
    private val analysisFor: (MediaItem) -> TrackAnalysis = { TrackAnalysis() },
    /**
     * Queues background analysis for a media item that will soon need it.
     * Cheap to call on every tick: a track already analysed, already in
     * flight, or not yet fully cached is a no-op.
     *
     * Takes the item's duration in milliseconds, or 0 when Media3 hasn't loaded
     * that far ahead yet. The analyzer needs it to tell one rendition of a
     * recording from a differently-cut one before reusing an analysis across
     * them, and this class is the only place that already knows it.
     */
    private val requestAnalysis: (MediaItem, Long) -> Unit = { _, _ -> },
    /**
     * The low-pass and high-pass riding each side of a transition. This is what
     * makes a plan's
     * [com.music.yzmusic.playback.smart.TransitionPlan.transitionStyle] audible
     * rather than advisory: see [rideFilters]. Defaults to
     * [TransitionFilters.None], which renders every style as the plain
     * equal-power blend this class ran before.
     */
    private val filters: TransitionFilters = TransitionFilters.None,
    /**
     * Whether a decode and inference for a media item is running right now.
     * Only feeds the stats line — nothing about a transition waits on it.
     */
    private val analysisRunningFor: (MediaItem) -> Boolean = { false },
) {

    private enum class Phase {
        /** Nothing in flight; watching for the next transition. */
        IDLE,

        /**
         * The standby player is loading the incoming track and buffering to its
         * cue point. Silent, and nothing has been committed: abandoning here
         * costs only the standby's decoder.
         */
        ARMING,

        /** Incoming track rising on one player, outgoing falling on the other. */
        FADING,

        /** Something interrupted the fade; the outgoing track is being ramped away. */
        BAILING,
    }

    private var phase = Phase.IDLE

    /**
     * The player the session was on when this transition began — the one whose
     * track is being left. Held explicitly rather than re-read through
     * [standby], because [onHandoff] moves it out from under that name halfway
     * through the fade and the ramp has to keep driving the same two players it
     * started with.
     */
    private var outgoing: ExoPlayer? = null

    /** The player carrying the track arriving. Becomes the session at [onHandoff]. */
    private var incoming: ExoPlayer? = null

    /**
     * Whether [onHandoff] has run for the transition in flight, which is what
     * decides who owns what if it has to be unwound: before it, [outgoing] is
     * the session and [incoming] is a silent scratch player; after it, they have
     * traded places.
     */
    private var handedOff = false

    /**
     * How many items the queue held when the standby was loaded with a copy of
     * it. AutoPlay appending mid-transition is explicitly allowed, so the
     * difference is reconciled onto the standby before the swap rather than
     * being allowed to lose the appended tracks — see [reconcileQueue].
     */
    private var queuedItemCount = 0

    /** Which player this class's own listener is currently attached to. */
    private var listeningTo: ExoPlayer? = null

    /** Length of the transition in flight, in ms. Fixed when it begins. */
    private var fadeMs = 0L

    /**
     * Where the fade window ends, in the session player's position ms.
     * Standard mode sets this to the track's own duration, which is what
     * [driveArming] always compared against before Automix existed; a
     * Automix plan can set it earlier, at an analyzed mix-out anchor, so
     * [driveArming] watches this field rather than re-deriving the fade point
     * from [ExoPlayer.getDuration] on every tick.
     */
    private var fadeEndMs = 0L

    /**
     * Which setting armed the fade in flight, so [driveFade] knows which one
     * being switched off mid-blend means "stop now" rather than misreading the
     * other mode's control as the fade having been turned off. Automix
     * doesn't need [AppSettings.crossfadeSeconds] to be above zero at all —
     * see [considerSmartTransition] — so treating that as still-zero as a
     * reason to cut a Automix short would end every one of them on its
     * first tick.
     */
    private var smartFadeActive = false

    /**
     * Where the incoming track is cued when the lap hands the queue over, in
     * its own timeline ms. Standard fades always leave this at 0 — a plain
     * track change starts from the top — and only a Automix plan sets it
     * to an analyzed mix-in point instead.
     */
    private var incomingCueTimeMs: Long = 0L

    /**
     * The tempo-stretch ratio applied to the incoming track for the
     * transition, stacked on top of whatever [AppSettings.playbackSpeed] the
     * listener already has set — 1.0 is a no-op. This is what actually
     * beatmatches a BEATMATCHED-tier plan: without it, the two tracks blend
     * at their own unrelated tempi and the result is a crossfade with
     * smarter timing, not a beatmatch.
     */
    private var incomingPlaybackRate: Double = 1.0

    /**
     * The style-specific half of the plan in flight — everything [rideFilters]
     * needs and nothing else. Fixed when the transition begins, because a plan
     * is recomputed every tick and a bass swap that moved to a different beat
     * halfway through the blend would be heard as the low end flapping.
     */
    private var render = Render()

    /**
     * The style fields of a [com.music.yzmusic.playback.smart.TransitionPlan],
     * separated out so the standard (non-Smart) path can pass defaults without
     * constructing a plan it never made.
     */
    private data class Render(
        val style: TransitionStyle = TransitionStyle.EQUAL_POWER,
        val bassSwap: Boolean = false,
        val bassSwapFraction: Double = 0.7,
        val filterSweep: Double = 0.0,
        val vocalOverlap: Double = 0.0,
    )

    private var fadeStartedAt = 0L
    private var bailStartedAt = 0L
    private var armDeadline = 0L

    /**
     * When the last transition finished, from [SystemClock.elapsedRealtime], or
     * zero while none has this session.
     *
     * Read through [msSinceTransition] by callers that have to stay off the
     * session player for a moment *after* a blend as well as during one.
     */
    private var settledAt = 0L

    /**
     * Gain the outgoing track was at when the fade was interrupted, so the ramp
     * out starts from where it actually is rather than from full volume.
     */
    private var bailFromGain = 0f

    /** Dedupes the per-tick plan log down to one line per distinct verdict. */
    private var lastPlanVerdict = ""

    /**
     * True while a transition is armed or running.
     *
     * For callers about to do something that would otherwise fight this class
     * for the session player mid-blend — [PlaybackService]'s quality upgrade is
     * the one that does, since `replaceMediaItem` tears the current source down
     * and rebuilds it. Doing that to either player mid-transition breaks the
     * blend rather than merely delaying it, so such a caller should wait for
     * this to clear rather than proceed anyway.
     */
    fun isTransitioning(): Boolean = phase != Phase.IDLE

    /**
     * How long since the last transition finished, or null while none has.
     *
     * For the same caller as [isTransitioning], which needs a little more than
     * that flag can give it. The flag clears on the tick the blend completes,
     * so a source torn down and rebuilt the moment it clears puts its break in
     * the audio a few hundred milliseconds after the incoming track finally
     * stood alone — not a broken blend, but heard as one. A caller that wants
     * the transition to have been *over* for a while, rather than merely to
     * have ended, waits this out too.
     *
     * Says nothing about a transition still in flight — it reports whatever the
     * one before it left behind — so [isTransitioning] stays the first question
     * to ask.
     */
    fun msSinceTransition(): Long? =
        settledAt.takeIf { it != 0L }?.let { SystemClock.elapsedRealtime() - it }

    private val listener = object : Player.Listener {
        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            // The listener moving the playhead is something no half-finished
            // crossfade should survive. Nothing this class does registers here
            // any more: the handoff is a role swap, not a seek.
            if (reason == Player.DISCONTINUITY_REASON_SEEK) bail()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            when (reason) {
                // Something replaced the queue out from under the fade — a new
                // album, a new search result — so the tail still playing is a
                // leftover of a session that no longer exists. Note that this
                // does *not* fire when AutoPlay appends to the end, since the
                // playing item doesn't change: extending the queue mid-fade is
                // harmless and shouldn't cost the listener the blend.
                Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED -> bail()
                Player.MEDIA_ITEM_TRANSITION_REASON_SEEK -> bail()
            }
        }

        override fun onPlayerError(error: PlaybackException) = bail()
    }

    /**
     * Keeps [listener] on whichever player is the session.
     *
     * It has to move rather than sit on both: arming loads a whole queue onto
     * the standby, which Media3 reports as the playlist changing, and a listener
     * attached there would read that as the queue being replaced out from under
     * the very transition it is setting up.
     */
    private fun listenTo(target: ExoPlayer) {
        if (listeningTo === target) return
        listeningTo?.removeListener(listener)
        target.addListener(listener)
        listeningTo = target
    }

    fun start() {
        listenTo(active())
        scope.launch {
            while (isActive) {
                tick()
                delay(
                    when (phase) {
                        Phase.IDLE -> IDLE_STEP_MS
                        Phase.ARMING -> ARM_STEP_MS
                        Phase.FADING -> FADE_STEP_MS
                        Phase.BAILING -> BAIL_STEP_MS
                    },
                )
            }
        }
    }

    fun release() {
        listeningTo?.removeListener(listener)
        listeningTo = null
        active().volume = 1f
        AppSettings.smartMixInProgress.value = false
        filters.open()
    }

    // ---- Entry points -------------------------------------------------------

    /**
     * A skip the listener asked for: drop any blend in flight and get out of
     * the way.
     *
     * Crossfade is deliberately a property of tracks *running out*, not of
     * being changed. Blending a manual skip means the song just left behind
     * stays audible over the one that was asked for, which reads as the app
     * ignoring the button rather than as a transition — the point of pressing
     * next is usually to stop hearing the current track.
     *
     * Called before the skip is carried out, so the outgoing track is already on
     * its way down as the new one starts, and the listener's own seek lands on a
     * player this class has finished with.
     */
    fun onSkipRequested() {
        if (phase != Phase.IDLE) bail()
    }

    // ---- Ticker -------------------------------------------------------------

    private fun tick() {
        // A pause has to take the other player with it, or one half of the blend
        // carries on alone over a stopped one. Mirrored every tick rather than
        // handled as an event, so audio focus loss, the sleep timer and the
        // pause button all get the same treatment for free. Which player follows
        // which flips at the handoff: before it the standby shadows the session,
        // after it the outgoing tail does.
        if (phase == Phase.FADING || phase == Phase.BAILING) {
            outgoing?.playWhenReady = incoming?.playWhenReady ?: true
        }

        // Every tick, not only when a transition can be planned. This used to
        // live inside [considerSmartTransition], which needs an idle phase, a
        // playing player and a known duration — none of which hold during a
        // transition or during the re-buffer after a quality upgrade. The line
        // simply froze on the previous pair, so a track that had not been
        // analysed kept showing the *departing* track's "analysed" until
        // ticking resumed.
        publishAnalysisState()

        when (phase) {
            Phase.IDLE -> considerAutoTransition()
            Phase.ARMING -> driveArming()
            Phase.FADING -> driveFade()
            Phase.BAILING -> driveBail()
        }
    }

    /** Arms a crossfade as the playing track runs out. */
    private fun considerAutoTransition() {
        val player = active()
        if (!player.isPlaying) return
        // Nothing to transition *into*, so any analysis state left over from the
        // previous pair is stale — the last track of a queue should not still be
        // claiming both songs are measured.
        if (!player.hasNextMediaItem()) {
            AppSettings.smartTransitionWindow.value = null
            return
        }

        val duration = player.duration
        if (duration == C.TIME_UNSET || duration <= 0L) return

        // Repeating one track would crossfade it into itself, so nothing is
        // armed and no window is marked — but the queue behind the loop has not
        // moved, and what sits after it is still the track that plays next the
        // moment repeat-one comes off.
        //
        // Returning here outright is what made turning repeat off look like it
        // lost an analysis. Analysis is only ever asked for on the way to
        // planning a transition, so for as long as the loop ran nothing asked
        // for the following track at all, and the request that finally arrived
        // when repeat came off was the *first* one — a whole-track decode
        // starting from nothing on a song that was by then seconds away, where
        // an unlooped queue would have had it measured minutes earlier. The
        // measurement is the same either way, so it may as well be made during
        // the loop rather than after it.
        if (player.repeatMode == Player.REPEAT_MODE_ONE) {
            if (AppSettings.smartFadeEnabled.value) requestAnalysisAround(player, duration)
            // Stale otherwise: the marker would keep describing the transition
            // planned for this pair before the loop went on, at a point the
            // playhead now runs past on every lap without anything happening.
            AppSettings.smartTransitionWindow.value = null
            return
        }

        // Automix is its own on/off, independent of the manual crossfade
        // length: it decides its own duration from each pair of tracks (beats,
        // tempo, structure), so requiring a nonzero [AppSettings.crossfadeSeconds]
        // first would tie an automatic feature to a manual one it doesn't use.
        if (AppSettings.smartFadeEnabled.value) {
            considerSmartTransition(duration)
            return
        }

        if (configuredFadeMs() <= 0L) return
        val fade = fadeFor(duration)
        if (fade <= 0L) return

        val remaining = duration - player.currentPosition
        // Arm early: the standby has to open the incoming track and buffer to
        // its cue point, and that work has to be finished by the time the fade
        // is due rather than started then.
        if (remaining > fade + ARM_LEAD_MS) return

        begin(fade, endMs = duration, smart = false)
    }

    /**
     * Arms a Automix transition once its plan says the playhead is close
     * enough to start arming for it.
     *
     * Reads the plan's timing (where the fade starts and how long it runs),
     * where the incoming track should be cued
     * ([com.music.yzmusic.playback.smart.TransitionPlan.incomingCueTime]),
     * and the tempo-stretch to align it with the outgoing track
     * ([com.music.yzmusic.playback.smart.TransitionPlan.incomingPlaybackRate])
     * — see [driveLap], which applies both at the handoff — and the style the
     * blend is rendered in
     * ([com.music.yzmusic.playback.smart.TransitionPlan.transitionStyle]),
     * which [rideFilters] turns into a filter ride or a bass swap over the same
     * equal-power gain curve.
     */
    private fun considerSmartTransition(duration: Long) {
        val player = active()
        val currentItem = player.currentMediaItem ?: return
        val nextIndex = player.nextMediaItemIndex
        if (nextIndex == C.INDEX_UNSET) return
        val nextItem = player.getMediaItemAt(nextIndex)
