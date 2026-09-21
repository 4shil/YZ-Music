package com.music.yzmusic

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import com.music.yzmusic.playback.EqCurve
import com.music.yzmusic.playback.EqLayout
import com.music.yzmusic.playback.EqualizerPreset
import com.music.yzmusic.playback.EqualizerProcessor
import com.music.yzmusic.playback.manualCurve
import com.music.yzmusic.playback.toneCurve
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class EqualizerProcessorTest {

    @Test
    fun `presets define 7 bands and flat preset has all zero gains`() {
        val presets = EqualizerPreset.entries.filter { it != EqualizerPreset.CUSTOM }
        assertEquals(16, presets.size)

        for (preset in presets) {
            assertEquals("Preset ${preset.name} must have 7 bands", 7, preset.bands.size)
            for (bandGain in preset.bands) {
                assertTrue(
                    "Band gain $bandGain in ${preset.name} must be within [-12, 12] dB",
                    bandGain in -12f..12f
                )
            }
        }

        val flat = EqualizerPreset.FLAT
        for (gain in flat.bands) {
            assertEquals(0f, gain, 0.001f)
        }
    }

    @Test
    fun `manual curve sets 7 bands and clamps to valid range`() {
        val inputGains = listOf(15f, -20f, 3f, -4f, 5f, -6f, 7f)
        val curve = manualCurve(inputGains)

        assertEquals(10, curve.gainsDb.size)
        // Check clamped bounds
        assertEquals(12f, curve.gainsDb[0], 0.001f)
        assertEquals(-12f, curve.gainsDb[1], 0.001f)
        assertEquals(3f, curve.gainsDb[2], 0.001f)
        assertEquals(-4f, curve.gainsDb[3], 0.001f)
        assertEquals(5f, curve.gainsDb[4], 0.001f)
        assertEquals(-6f, curve.gainsDb[5], 0.001f)
        assertEquals(7f, curve.gainsDb[6], 0.001f)

        // Tone pad slots (7, 8, 9) must remain 0
        assertEquals(0f, curve.gainsDb[7], 0.001f)
        assertEquals(0f, curve.gainsDb[8], 0.001f)
        assertEquals(0f, curve.gainsDb[9], 0.001f)

        // Preamp attenuation must be <= 0 dB to prevent 16-bit PCM clipping
        assertTrue(curve.preampDb <= 0f)
    }

    @Test
    fun `tone pad curve generates warmth clarity tilt and contour`() {
        // Centered tone pad
        val centerCurve = toneCurve(x = 0, y = 0, focused = false)
        assertEquals(0f, centerCurve.gainsDb[EqLayout.TONE_LOW], 0.001f)
        assertEquals(0f, centerCurve.gainsDb[EqLayout.TONE_MID], 0.001f)
        assertEquals(0f, centerCurve.gainsDb[EqLayout.TONE_HIGH], 0.001f)

        // Warm tilt (x < 0) boosts low shelf and cuts high shelf
        val warmCurve = toneCurve(x = -3, y = 0, focused = false)
        assertTrue(warmCurve.gainsDb[EqLayout.TONE_LOW] > 0f)
        assertTrue(warmCurve.gainsDb[EqLayout.TONE_HIGH] < 0f)

        // Bright tilt (x > 0) boosts high shelf and cuts low shelf
        val brightCurve = toneCurve(x = 3, y = 0, focused = false)
        assertTrue(brightCurve.gainsDb[EqLayout.TONE_LOW] < 0f)
        assertTrue(brightCurve.gainsDb[EqLayout.TONE_HIGH] > 0f)

        // Focused mode narrows Q
        val broadCurve = toneCurve(x = 2, y = 2, focused = false)
        val focusedCurve = toneCurve(x = 2, y = 2, focused = true)
        assertTrue(focusedCurve.qs[EqLayout.TONE_LOW] > broadCurve.qs[EqLayout.TONE_LOW])
        assertTrue(focusedCurve.qs[EqLayout.TONE_MID] > broadCurve.qs[EqLayout.TONE_MID])
    }

    @Test
    fun `equalizer processor accepts 16-bit PCM and rejects float PCM`() {
        val processor = EqualizerProcessor()

        // Supported 16-bit stereo PCM
        val validFormat = AudioProcessor.AudioFormat(44100, 2, C.ENCODING_PCM_16BIT)
        val configured = processor.configure(validFormat)
        assertEquals(44100, configured.sampleRate)
        assertEquals(2, configured.channelCount)
        assertEquals(C.ENCODING_PCM_16BIT, configured.encoding)
        assertTrue(processor.isActive)

        // Unsupported Float PCM (e.g. 32-bit USB DAC bit-perfect output)
        val floatFormat = AudioProcessor.AudioFormat(96000, 2, C.ENCODING_PCM_FLOAT)
        val unconfigured = processor.configure(floatFormat)
        assertEquals(AudioProcessor.AudioFormat.NOT_SET, unconfigured)
        assertFalse(processor.isActive)
    }

    @Test
    fun `equalizer processor processes 16-bit audio without drop or crash`() {
        val processor = EqualizerProcessor()
        val format = AudioProcessor.AudioFormat(44100, 2, C.ENCODING_PCM_16BIT)
        processor.configure(format)
        processor.flush()

        val testCurve = manualCurve(listOf(4f, 2f, 0f, -2f, -4f, 1f, 3f))
        processor.setTuning(enabled = true, curve = testCurve, balance = 0f)

        // Create 100 samples of 16-bit stereo PCM audio (400 bytes)
        val sampleCount = 100
        val buffer = ByteBuffer.allocateDirect(sampleCount * 2 * 2).order(ByteOrder.nativeOrder())
        for (i in 0 until sampleCount * 2) {
            buffer.putShort((i * 100).toShort())
        }
        buffer.flip()

        processor.queueInput(buffer)
        val output = processor.output
        assertTrue(output.hasRemaining())
        assertEquals(sampleCount * 4, output.remaining())

        // Change tuning dynamically while audio is running (should not crash or click)
        processor.setTuning(enabled = true, curve = EqCurve.FLAT, balance = -0.5f)
        buffer.rewind()
        processor.queueInput(buffer)
        val output2 = processor.output
        assertTrue(output2.hasRemaining())
    }
}
