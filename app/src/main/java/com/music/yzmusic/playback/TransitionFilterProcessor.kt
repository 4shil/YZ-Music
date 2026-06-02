package com.music.yzmusic.playback

import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteOrder
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.tan

/**
 * The filter a track rides through a Automix transition: a low-pass that can
 * close over the outgoing track, and a high-pass that can lift the low end out
 * of one side of a blend.
 *
 * ## Why this exists
 *
 * [CrossfadeController] renders every transition as an equal-power gain blend,
 * and a gain blend is the one move that cannot fix the two things that actually
 * make a mix sound amateur:
 *
 *  - **Two basslines at once.** Below roughly 200 Hz a mix has very little room;
 *    two kick drums and two bass parts occupying it simultaneously read as mud
 *    and eat headroom, however carefully the gains are matched. Every DJ mixer
 *    ever built has a bass kill for exactly this, and the fix is the same here:
 *    the low end belongs to exactly one track at a time, and it changes hands
 *    once, on a beat the planner picked
 *    ([com.music.yzmusic.playback.smart.TransitionPlan.bassSwapFraction]).
 *  - **Two unrelated tempi at once.** When the tracks are too far apart to
 *    beat-match, their transients simply collide. Closing a low-pass over the
 *    outgoing track pulls it behind the incoming one instead of leaving them to
 *    fight, which is why a filtered handoff is the standard move for a tempo
 *    change.
 *
 * ## The filter
 *
 * A topology-preserving (trapezoidal-integrator) state-variable filter, two
 * second-order sections cascaded to a 24 dB/octave Butterworth response. Chosen
 * over the more familiar Chamberlin SVF because the trapezoidal form is stable
 * at every cutoff up to Nyquist, while Chamberlin's is only well behaved below
 * about a sixth of the sample rate — a low-pass parked wide open at 20 kHz sits
 * far outside that, so the naive form would have to be special-cased at exactly
 * the setting it spends most of its time at.
 *
 * `tan` is evaluated once per sub-block rather than per sample, and the whole
 * thing degenerates to a buffer copy when both cutoffs are parked, so a
 * transition that asks for no filtering costs nothing.
 *
 * ## Gliding
 *
 * Cutoffs are targets, not values. [CrossfadeController] re-aims them once per
 * fade tick (every 30 ms), and stepping a filter in 30 ms jumps is audible as
 * zipper noise, so the real cutoff chases its target geometrically across
 * [GLIDE_FRAMES]-sample sub-blocks. Geometric because cutoff is perceived
 * logarithmically: a linear glide down from 20 kHz would spend nearly all of
 * itself inaudible and then lurch through the last octave.
 */
@UnstableApi
class TransitionFilterProcessor : BaseAudioProcessor() {

    @Volatile
    private var targetLowPassHz: Float = OPEN_HZ

    @Volatile
    private var targetHighPassHz: Float = OFF_HZ

    private var channelCount = 0
    private var sampleRate = 0

    private var currentLowPassHz = OPEN_HZ
    private var currentHighPassHz = OFF_HZ

    /** Two integrator states per second-order section, per channel. */
    private var lowState = FloatArray(0)
    private var highState = FloatArray(0)

    private val lowA1 = FloatArray(STAGES)
    private val lowA2 = FloatArray(STAGES)
    private val lowA3 = FloatArray(STAGES)
    private val highA1 = FloatArray(STAGES)
    private val highA2 = FloatArray(STAGES)
    private val highA3 = FloatArray(STAGES)
    private val highK = FloatArray(STAGES)

    /**
     * Aims the filter. [lowPassHz] at or above [OPEN_HZ] and [highPassHz] at or
     * below [OFF_HZ] mean "not filtering", which is the state this returns to
     * between transitions.
     */
    fun setCutoffs(lowPassHz: Float, highPassHz: Float) {
        targetLowPassHz = lowPassHz.coerceIn(MIN_HZ, OPEN_HZ)
        targetHighPassHz = highPassHz.coerceIn(OFF_HZ, MAX_HIGH_PASS_HZ)
    }

    /** Parks both filters. Glided, not snapped — see the class doc. */
    fun open() = setCutoffs(OPEN_HZ, OFF_HZ)

    /**
     * 16-bit PCM only, matching [SpatialAudioProcessor] — and bowing out with
     * [AudioProcessor.AudioFormat.NOT_SET] rather than throwing for the same
     * reason it does: `DefaultAudioSink` configures every processor in its chain
     * whether or not the effect is switched on, and a throw from any of them
     * kills the renderer outright. NOT_SET means "inactive for this format" and
     * the chain routes around this processor.
     *
     * Logged rather than silent, because the failure mode of a filter that
     * quietly declines to run is a Phase 4 transition that sounds exactly like a
     * Phase 3 one, with nothing anywhere saying why.
     */
    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT || inputAudioFormat.channelCount < 1) {
            Log.w(
                TAG,
                "Transition filtering inactive: encoding=${inputAudioFormat.encoding} " +
                    "channels=${inputAudioFormat.channelCount} is not 16-bit PCM",
            )
            return AudioProcessor.AudioFormat.NOT_SET
        }
        channelCount = inputAudioFormat.channelCount
        sampleRate = inputAudioFormat.sampleRate
        lowState = FloatArray(channelCount * STAGES * 2)
        highState = FloatArray(channelCount * STAGES * 2)
        currentLowPassHz = targetLowPassHz
        currentHighPassHz = targetHighPassHz
        return inputAudioFormat
    }

    override fun onFlush() {
        lowState.fill(0f)
        highState.fill(0f)
        // Snapped, not glided: a flush means a seek or a fresh source, so there
        // is no continuous signal for a glide to be continuous with.
        currentLowPassHz = targetLowPassHz
        currentHighPassHz = targetHighPassHz
    }

    override fun onReset() {
        targetLowPassHz = OPEN_HZ
        targetHighPassHz = OFF_HZ
        lowState = FloatArray(0)
        highState = FloatArray(0)
    }

    override fun queueInput(inputBuffer: java.nio.ByteBuffer) {
        val bytesPerFrame = BYTES_PER_SAMPLE * channelCount
        if (bytesPerFrame == 0) return
        val frameCount = inputBuffer.remaining() / bytesPerFrame
        if (frameCount == 0) return
        val outputBuffer = replaceOutputBuffer(frameCount * bytesPerFrame)

        val targetLow = targetLowPassHz
