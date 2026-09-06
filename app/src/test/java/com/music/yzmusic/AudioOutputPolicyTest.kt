package com.music.yzmusic

import com.music.yzmusic.data.settings.OutputPcmMode
import com.music.yzmusic.playback.AudioOutputPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioOutputPolicyTest {

    @Test
    fun `pcm float only allowed when mode is 32-bit and usb route advertises float`() {
        assertFalse(
            AudioOutputPolicy.shouldUseFloatOutput(
                requestedMode = OutputPcmMode.PCM_16,
                isPreferredUsbRoute = true,
                advertisesPcmFloat = true,
            )
        )
        assertFalse(
            AudioOutputPolicy.shouldUseFloatOutput(
                requestedMode = OutputPcmMode.FLOAT_32,
                isPreferredUsbRoute = false,
                advertisesPcmFloat = true,
            )
        )
        assertFalse(
            AudioOutputPolicy.shouldUseFloatOutput(
                requestedMode = OutputPcmMode.FLOAT_32,
                isPreferredUsbRoute = true,
                advertisesPcmFloat = false,
            )
        )
        assertTrue(
            AudioOutputPolicy.shouldUseFloatOutput(
                requestedMode = OutputPcmMode.FLOAT_32,
                isPreferredUsbRoute = true,
                advertisesPcmFloat = true,
            )
        )
    }

    @Test
    fun `detects unsafe Samsung flac decoders`() {
        assertTrue(AudioOutputPolicy.isUnsafeFloatFlacDecoder("c2.sec.flac.decoder"))
        assertTrue(AudioOutputPolicy.isUnsafeFloatFlacDecoder("OMX.SEC.flac.dec"))
        assertFalse(AudioOutputPolicy.isUnsafeFloatFlacDecoder("c2.android.flac.decoder"))
        assertFalse(AudioOutputPolicy.isUnsafeFloatFlacDecoder("OMX.google.flac.decoder"))
    }

    @Test
    fun `isAtmosFormat detects Atmos flag and codec combinations`() {
        val standardFormat = com.music.yzmusic.data.sources.StreamFormat(
            codec = "flac",
            kbps = 320,
            sampleRateHz = 44100,
            bitDepth = 16,
            isAtmos = false,
        )
        assertFalse(AudioOutputPolicy.isAtmosFormat(standardFormat))
        assertFalse(AudioOutputPolicy.isAtmosFormat(null))

        val atmosExplicit = standardFormat.copy(isAtmos = true)
        assertTrue(AudioOutputPolicy.isAtmosFormat(atmosExplicit))

        val atmosCodec = standardFormat.copy(codec = "eac3-joc")
        assertTrue(AudioOutputPolicy.isAtmosFormat(atmosCodec))
    }

    @Test
    fun `isAtmosStream detects eac3-joc and atmos urls or mimetypes`() {
        assertTrue(AudioOutputPolicy.isAtmosStream(null, "audio/eac3-joc"))
        assertTrue(AudioOutputPolicy.isAtmosStream(null, "audio/eac3"))
        assertTrue(AudioOutputPolicy.isAtmosStream("https://stream.provider.com/track_atmos.m4a", null))
        assertTrue(AudioOutputPolicy.isAtmosStream("https://stream.provider.com/audio_eac3-joc_256k.mp4", "audio/mp4"))
        assertFalse(AudioOutputPolicy.isAtmosStream("https://stream.provider.com/track.flac", "audio/flac"))
        assertFalse(AudioOutputPolicy.isAtmosStream(null, null))
    }

    @Test
    fun `shouldEnableSpatialAudio arbitrates user preference and atmos bypass`() {
        // User spatial audio off -> always off
        assertFalse(AudioOutputPolicy.shouldEnableSpatialAudio(userSpatialAudioEnabled = false, isAtmosActive = false))
        assertFalse(AudioOutputPolicy.shouldEnableSpatialAudio(userSpatialAudioEnabled = false, isAtmosActive = true))

        // User spatial audio on + standard stream -> virtualizer enabled
        assertTrue(AudioOutputPolicy.shouldEnableSpatialAudio(userSpatialAudioEnabled = true, isAtmosActive = false))

        // User spatial audio on + Atmos stream -> virtualizer bypassed for native hardware spatialization
        assertFalse(AudioOutputPolicy.shouldEnableSpatialAudio(userSpatialAudioEnabled = true, isAtmosActive = true))
    }
}
