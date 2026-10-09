package com.music.yzmusic

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import com.music.yzmusic.data.settings.LoudnessBoostMode
import com.music.yzmusic.playback.LoudnessBoostProcessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.pow

/**
 * Two things worth pinning: that off is exactly unity (the setting exists to
 * be off by default, and "approximately unchanged" is not unchanged), and that
 * the decibels the settings UI advertises are the decibels the processor
 * actually applies.
 */
class LoudnessBoostTest {

    @Test
    fun `off is exactly unity gain, not a rounded approximation of it`() {
        // The whole "no behaviour change when the setting is off" promise rests
        // on this being precisely 1f. A 0.999f here would quietly attenuate
        // every track by a hair, on every device, forever.
        assertEquals(1f, LoudnessBoostProcessor.gainFor(LoudnessBoostMode.OFF.millibels), 0f)
    }

    @Test
    fun `the advertised decibels are the decibels applied`() {
        // 10^(dB/20): +6 dB is about 1.995, +12 dB about 3.981. If the UI says
        // "+6 dB" and the processor applied anything else, the label is a lie.
        for (mode in listOf(LoudnessBoostMode.SUBTLE, LoudnessBoostMode.STRONG)) {
            val dB = mode.millibels / 100.0
            assertEquals(
                10f.pow(dB.toFloat() / 20f),
                LoudnessBoostProcessor.gainFor(mode.millibels),
                1e-5f,
            )
        }
    }

    @Test
    fun `the modes are ordered and off is the weakest of them`() {
        assertTrue(
            LoudnessBoostMode.OFF.millibels <
                LoudnessBoostMode.SUBTLE.millibels &&
                LoudnessBoostMode.SUBTLE.millibels < LoudnessBoostMode.STRONG.millibels,
        )
        assertTrue(
            LoudnessBoostProcessor.gainFor(LoudnessBoostMode.SUBTLE.millibels) <
                LoudnessBoostProcessor.gainFor(LoudnessBoostMode.STRONG.millibels),
        )
    }

    @Test
    fun `a cut is the same conversion in the other direction`() {
        // Not reachable from the settings today, but the conversion is the one
        // in the processor, and a millibel sign error would turn a -6 dB into a
        // +6 dB rather than anything more obviously wrong.
        assertEquals(0.501f, LoudnessBoostProcessor.gainFor(-600), 1e-3f)
    }
}

/**
 * The regression that actually shipped: [LoudnessBoostProcessor] accepted
 * 16-bit only and answered NOT_SET for anything else, which takes the processor
 * out of Media3's pipeline rather than passing audio through. On a decoder that
 * hands over float or 24-bit the control then did nothing at all, silently.
 */
class LoudnessBoostEncodingTest {

    private fun configure(
        p: LoudnessBoostProcessor,
        encoding: Int,
        channels: Int = 1,
    ): Boolean = p.configure(AudioProcessor.AudioFormat(44_100, channels, encoding)) !=
        AudioProcessor.AudioFormat.NOT_SET

    @Test
    fun `every PCM encoding Media3 can deliver stays in the pipeline`() {
        for (encoding in listOf(
            C.ENCODING_PCM_16BIT,
            C.ENCODING_PCM_24BIT,
            C.ENCODING_PCM_32BIT,
            C.ENCODING_PCM_FLOAT,
        )) {
            val p = LoudnessBoostProcessor()
            assertTrue(
                "encoding $encoding was dropped from the pipeline",
                configure(p, encoding),
            )
        }
    }

    @Test
    fun `a real unsupported encoding is still refused rather than guessed at`() {
        // Refusing is right here; silently treating, say, 8-bit as 16-bit would
        // reinterpret the stream as noise.
        assertTrue(!configure(LoudnessBoostProcessor(), C.ENCODING_PCM_8BIT))
    }

    @Test
    fun `the processor stays active so a later boost is not ignored`() {
        // The sink decides this once, when it configures. A processor that
        // reported inactive at unity would never be reconsidered when the
        // listener turns the boost up afterwards.
        assertTrue(LoudnessBoostProcessor().isActive)
    }
}

/** Drives the real DSP over real buffers, since the encoding tests only cover configure. */
class LoudnessBoostSignalTest {

    private fun scaled(encoding: Int, gainMb: Int, frames: Int, peakIn: Float): Float {
        val p = LoudnessBoostProcessor()
        p.configure(AudioProcessor.AudioFormat(44_100, 1, encoding))
        p.setTargetGainMb(gainMb)
        val bytes = when (encoding) {
            C.ENCODING_PCM_16BIT -> 2
            C.ENCODING_PCM_24BIT -> 3
            C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT -> 4
            else -> 2
        }
        val inBuf = ByteBuffer.allocateDirect(frames * bytes).order(ByteOrder.LITTLE_ENDIAN)
        repeat(frames) {
            when (bytes) {
                2 -> inBuf.putShort((peakIn * 32_767f).toInt().toShort())
                3 -> inBuf.put((peakIn * 8_388_608f).toInt().let {
                    byteArrayOf(
                        (it and 0xFF).toByte(),
                        ((it shr 8) and 0xFF).toByte(),
                        ((it shr 16) and 0xFF).toByte(),
                    )
                })
                4 -> if (encoding == C.ENCODING_PCM_FLOAT) inBuf.putFloat(peakIn)
                else inBuf.putInt((peakIn * 2_147_483_648f).toInt())
            }
        }
        inBuf.flip()
        p.queueInput(inBuf)
        val out = p.getOutput().order(ByteOrder.LITTLE_ENDIAN)
        var peak = 0f
        for (f in 0 until frames) {
            val at = f * bytes
            peak = maxOf(
                peak,
                when (bytes) {
                    2 -> out.getShort(at).toInt() / 32_767f
                    3 -> {
                        val hi: Int = out.get(at + 2).toInt()
                        val raw: Int = (out.get(at).toInt() and 0xFF) or
                            ((out.get(at + 1).toInt() and 0xFF) shl 8) or (hi shl 16)
                        (if (hi < 0) raw or Int.MIN_VALUE else raw) / 8_388_608f
                    }
                    4 -> if (encoding == C.ENCODING_PCM_FLOAT) out.getFloat(at)
                    else out.getInt(at) / 2_147_483_648f
                    else -> 0f
                },
            )
        }
        return peak
    }

    @Test
    fun `off is a bit-exact passthrough on every encoding`() {
        for (encoding in listOf(C.ENCODING_PCM_16BIT, C.ENCODING_PCM_24BIT, C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT)) {
            val p = LoudnessBoostProcessor()
            p.configure(AudioProcessor.AudioFormat(44_100, 1, encoding))
            val bytes = when (encoding) {
                C.ENCODING_PCM_24BIT -> 3
                C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT -> 4
                else -> 2
            }
            val inBuf = ByteBuffer.allocateDirect(1024 * bytes).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until 1024) when (bytes) {
                2 -> inBuf.putShort((i * 13 % 2000 - 1000).toShort())
                3 -> inBuf.put(byteArrayOf((i % 200).toByte(), 0, 0))
                4 -> if (encoding == C.ENCODING_PCM_FLOAT) inBuf.putFloat(i * 0.001f) else inBuf.putInt(i * 1000)
            }
            val original = inBuf.duplicate().order(ByteOrder.LITTLE_ENDIAN)
            inBuf.flip()
            p.queueInput(inBuf)
            val got = p.getOutput()
            assertNotEquals("nothing came out for encoding $encoding", 0, got.remaining())
            for (i in 0 until got.remaining()) {
                assertEquals("byte $i differs at unity, encoding $encoding", original.get(i).toInt(), got.get(i).toInt())
            }
        }
    }

    @Test
    fun `a boost lifts a quiet signal on every encoding, by the advertised amount`() {
        val want = 10f.pow(LoudnessBoostMode.STRONG.millibels / 2000f)
        for (encoding in listOf(C.ENCODING_PCM_16BIT, C.ENCODING_PCM_24BIT, C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT)) {
            // 0.1 in, boosted 4x, is still under the clipper's knee, so what
            // comes out is the gain and not the limiter.
            val got = scaled(encoding, LoudnessBoostMode.STRONG.millibels, 4096, 0.1f)
            assertEquals("encoding $encoding", 0.1f * want, got, 2e-3f)
        }
    }

    @Test
    fun `overdriving full scale never leaves the range, on any encoding`() {
        for (encoding in listOf(C.ENCODING_PCM_16BIT, C.ENCODING_PCM_24BIT, C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT)) {
            val got = scaled(encoding, LoudnessBoostMode.STRONG.millibels, 1024, 0.999f)
            assertTrue("encoding $encoding overshot to $got", abs(got) <= 1.0f)
        }
    }
}
