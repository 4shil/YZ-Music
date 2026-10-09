package com.music.yzmusic.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import com.music.yzmusic.data.DebugLog as Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.pow

/**
 * A user-chosen output gain, so a quiet mix can be brought up toward the rest
 * of a session by hand.
 *
 * ## This is a boost, not a normalization
 *
 * The name is the honest one. Equalising tracks to a common loudness needs to
 * know each track's integrated loudness, and nothing in this app measures it —
 * `TrackFeatures` carries an energy *curve* over time, not an integrated
 * figure, and there is no ReplayGain anywhere in the source tree. So what this
 * does is the one thing available without inventing a measurement: it scales
 * the signal by a fixed amount the listener chose, which makes a quiet mix
 * easier to hear but levels nothing against anything else. It is deliberately
 * not called normalization, because a control that overstates what the audio
 * is doing is worse than no control.
 *
 * ## Every encoding, because the one that arrives is not the one you expect
 *
 * [onConfigure] used to accept 16-bit only and answer [AudioProcessor.AudioFormat.NOT_SET]
 * for anything else. NOT_SET is not "pass this through" — it takes the processor
 * out of the pipeline, so on any device or decoder that hands over float or
 * 24-bit the control silently did nothing at all, with no error the user could
 * see. So all four of Media3's PCM encodings are handled here: 16-bit, packed
 * 24-bit, 32-bit int and float.
 *
 * ## Always in the pipeline
 *
 * [isActive] reports true unconditionally, and that is load-bearing rather than
 * tidiness. [BaseAudioProcessor.configure] is what ends a configure call with
 * `NOT_SET` when [isActive] is false — it is not a pass-through hint, it is the
 * processor being struck from the chain. So a processor that reported inactive
 * at unity, which is exactly what this one used to do, was removed the moment
 * the sink was built and never came back: turning the boost up minutes later
 * reached a processor that was no longer in the audio path, which is why the
 * control did nothing and reported no error. The unity case still costs almost
 * nothing, because [queueInput] recognises it and hands the buffer across
 * without touching a sample.
 *
 * ## Where it sits
 *
 * Last in the custom-processor array, so the soft clip sees the fully filtered
 * signal — clipping before the equaliser would hand the filter a distorted
 * input to work with.
 *
 * ## Not being a click
 *
 * A gain change is a step, and a step on the audio thread is a click. The ramp
 * takes [GLIDE_FRAMES] frames to arrive, about 20 ms, advanced once per frame
 * so stereo stays in step. A flush snaps rather than glides, because a flush
 * means a seek or a fresh source and there is no continuous signal there for a
 * ramp to be continuous with.
 */
class LoudnessBoostProcessor : BaseAudioProcessor() {

    companion object {
        private const val TAG = "LoudnessBoostProcessor"

        /** About 20 ms at 48 kHz: long enough to be inaudible, short to feel instant. */
        private const val GLIDE_FRAMES = 960

        /**
         * Full-scale-normalised magnitude below which the signal is untouched.
         * Above it the curve bends asymptotically toward 1 rather than clipping
         * flat, and its slope matches the straight line at the knee, so there is
         * no kink to hear either.
         */
        private const val KNEE = 0.5f
        private val ROOM = 1f - KNEE

        /** 2^23 — full scale for packed 24-bit, whose samples span -1 to just under 1. */
        private const val PEAK_24 = 8_388_608f
        private const val PEAK_32 = 2_147_483_648f

        /**
         * Millibels to a linear multiplier, the conversion Media3's own
         * loudness APIs use: dB is millibels/100, and a gain in dB is 10^(dB/20).
         */
        internal fun gainFor(mb: Int): Float =
            if (mb == 0) 1f else 10f.pow(mb / 2000f)
    }

    /**
     * The requested gain in millibels. Read from the audio thread every buffer
     * and written from the main thread when the setting changes, so it is a
     * volatile rather than a flow this would have to collect inside a processor.
     */
    @Volatile
    private var targetMb: Int = 0

    fun setTargetGainMb(mb: Int) {
        targetMb = mb
    }

    private var encoding = C.ENCODING_PCM_16BIT
    private var bytesPerSample = 2
    private var channelCount = 1
    private var gain = 1f
    private var rampLeft = 0

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        bytesPerSample = when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_16BIT -> 2
            C.ENCODING_PCM_24BIT -> 3
            C.ENCODING_PCM_32BIT -> 4
            C.ENCODING_PCM_FLOAT -> 4
            else -> {
                Log.w(TAG, "Boost unsupported: encoding=${inputAudioFormat.encoding}")
                return AudioProcessor.AudioFormat.NOT_SET
            }
        }
        if (inputAudioFormat.channelCount < 1) {
            Log.w(TAG, "Boost unsupported: channelCount=${inputAudioFormat.channelCount}")
            return AudioProcessor.AudioFormat.NOT_SET
        }
        encoding = inputAudioFormat.encoding
        channelCount = inputAudioFormat.channelCount
        gain = gainFor(targetMb)
        rampLeft = 0
        return inputAudioFormat
    }

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        // Snapped, not glided — see the class note.
        gain = gainFor(targetMb)
        rampLeft = 0
    }

    // See the class note: always true, so a boost turned on after the sink was
    // built still reaches the audio thread.
    override fun isActive(): Boolean = true

    override fun queueInput(inputBuffer: ByteBuffer) {
        val bytesPerFrame = bytesPerSample * channelCount
        if (bytesPerFrame == 0) return
        val frameCount = inputBuffer.remaining() / bytesPerFrame
        if (frameCount == 0) return
        val outputBuffer = replaceOutputBuffer(frameCount * bytesPerFrame)

        val wanted = gainFor(targetMb)
        if (gain == 1f && wanted == 1f) {
            // Settled at unity: the buffer goes across untouched, which is what
            // makes the off position free rather than merely quiet.
            outputBuffer.put(inputBuffer)
            outputBuffer.flip()
            return
        }

        inputBuffer.order(ByteOrder.nativeOrder())
        outputBuffer.order(ByteOrder.nativeOrder())

        // A ramp runs for the shorter of the glide or the buffer, then holds: a
        // buffer longer than the glide must not carry the step past its target
        // and overshoot on the way there.
        if (rampLeft <= 0 && gain != wanted) {
            rampLeft = min(frameCount, GLIDE_FRAMES)
        }
        val step = if (rampLeft > 0) (wanted - gain) / rampLeft else 0f

        var level = gain
        var remaining = rampLeft
        var i = 0
        while (i < frameCount) {
            // Once per frame, not once per sample: a per-sample step would glide
            // through the buffer in a quarter of the intended time and would
            // finish the ramp on one channel before the other had started.
            if (remaining > 0) {
                level += step
                if (--remaining == 0) level = wanted
            } else if (level != wanted) {
                level = wanted
            }
            var ch = 0
            while (ch < channelCount) {
                val at = i * bytesPerFrame + ch * bytesPerSample
                val x = readNormalised(inputBuffer, at) * level
                writeScaled(outputBuffer, at, bend(x))
                ch++
            }
            i++
        }
        gain = level
        rampLeft = remaining
        // The samples were written at absolute offsets, which leaves the position
        // where it started; flip() on that would publish an empty buffer, i.e.
        // silence. Advancing to the end first is what makes the write visible.
        outputBuffer.position(frameCount * bytesPerFrame).flip()
    }

    /** One sample, as a fraction of full scale in -1..1, whatever the encoding is. */
    private fun readNormalised(b: ByteBuffer, at: Int): Float = when (bytesPerSample) {
        2 -> b.getShort(at) / 32_767f
        3 -> {
            val lo: Int = b.get(at).toInt() and 0xFF
            val mid: Int = b.get(at + 1).toInt() and 0xFF
            val hi: Int = b.get(at + 2).toInt()
            // `hi` holds bits 16..23, so its sign is the sample's sign. ORing in
            // the top bit sign-extends; without this the upper half of the range
            // reads back inverted and a quiet passage would come out loud.
            val raw = lo or (mid shl 8) or (hi shl 16)
            (if (hi < 0) raw or Int.MIN_VALUE else raw) / PEAK_24
        }
        4 -> if (encoding == C.ENCODING_PCM_FLOAT) b.getFloat(at) else b.getInt(at) / PEAK_32
        else -> 0f
    }

    private fun writeScaled(b: ByteBuffer, at: Int, v: Float) {
        when (bytesPerSample) {
            2 -> b.putShort(at, (v * 32_767f).toInt().coerceIn(-32_768, 32_767).toShort())
            3 -> {
                val q = (v * PEAK_24).toInt().coerceIn(-8_388_608, 8_388_607)
                b.put(at, (q and 0xFF).toByte())
                b.put(at + 1, ((q shr 8) and 0xFF).toByte())
                b.put(at + 2, ((q shr 16) and 0xFF).toByte())
            }
            4 -> if (encoding == C.ENCODING_PCM_FLOAT) {
                b.putFloat(at, v)
            } else {
                b.putInt(at, (v * PEAK_32).toInt().coerceIn(Int.MIN_VALUE, Int.MAX_VALUE))
            }
        }
    }

    /**
     * Full-scale-normalised input, bent back under 1. Asymptotic: at no
     * overdrive this is exactly the input, and as the overdrive grows it
     * approaches 1 without ever reaching it, so the output cannot exceed full
     * scale however hard the signal is pushed.
     */
    private fun bend(x: Float): Float {
        val magnitude = abs(x)
        if (magnitude <= KNEE) return x
        val over = magnitude - KNEE
        val side = if (x < 0) -1f else 1f
        return side * (KNEE + ROOM * (over / (over + ROOM)))
    }
}
