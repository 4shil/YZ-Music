package com.music.yzmusic

import com.music.yzmusic.data.StreamQualityLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The rule these pin is the negative one: a stream detail line says what was
 * known and nothing else. Every case below is one way the real data arrives
 * short of a measurement, and the line has to come back shorter rather than
 * filled in.
 */
class StreamQualityLabelTest {

    @Test
    fun `a fully measured lossless stream reads as the whole line`() {
        assertEquals(
            "FLAC · 24-bit · 96kHz",
            StreamQualityLabel.detail("audio/flac", 24, 96_000, null),
        )
    }

    @Test
    fun `the codec parameter names the codec, not the container`() {
        // Media3 reports AAC inside MP4 as audio/mp4; codecs="mp4a.40.2".
        // Echoing the subtype would tell a listener they were playing MP4.
        assertEquals(
            "AAC · 16-bit · 44.1kHz",
            StreamQualityLabel.detail("""audio/mp4; codecs="mp4a.40.2"""", 16, 44_100, null),
        )
    }

    @Test
    fun `the three AAC profiles are not all called AAC`() {
        assertEquals("HE-AAC", StreamQualityLabel.detail("""audio/mp4; codecs="mp4a.40.5"""", null, null, null))
        assertEquals("AAC", StreamQualityLabel.detail("""audio/mp4; codecs="mp4a.40.2"""", null, null, null))
    }

    @Test
    fun `an unrecognised codec is dropped rather than echoed`() {
        assertEquals("16-bit · 44.1kHz", StreamQualityLabel.detail("audio/3gpp", 16, 44_100, null))
    }

    @Test
    fun `an absent depth or rate removes that word and leaves the rest`() {
        assertEquals("FLAC · 96kHz", StreamQualityLabel.detail("audio/flac", null, 96_000, null))
        assertEquals("FLAC · 24-bit", StreamQualityLabel.detail("audio/flac", 24, null, null))
    }

    @Test
    fun `a placeholder zero is treated as unknown, not as a measurement`() {
        // The decoders hand back 0 rather than null for a depth they never
        // resolved. Printing "0-bit" would be a falsehood; printing nothing
        // for that word is the honest reading.
        assertEquals("FLAC", StreamQualityLabel.detail("audio/flac", 0, 0, null))
    }

    @Test
    fun `a bitrate that was never measured is not shown`() {
        assertEquals("Opus · 48kHz", StreamQualityLabel.detail("audio/opus", null, 48_000, null))
    }

    @Test
    fun `bitrate is kept for the lossy streams it is the only measure of`() {
        // A bare container name carries no codec, so the bitrate stands alone.
        assertEquals("320 kbps", StreamQualityLabel.detail("audio/mp4", null, null, 320))
    }

    @Test
    fun `nothing known means no line at all`() {
        assertNull(StreamQualityLabel.detail(null, null, null, null))
        assertNull(StreamQualityLabel.detail("  ", null, null, null))
    }

    @Test
    fun `the round sample rates read without a pointless decimal`() {
        assertEquals("48kHz", StreamQualityLabel.detail(null, null, 48_000, null))
        assertEquals("44.1kHz", StreamQualityLabel.detail(null, null, 44_100, null))
        assertEquals("192kHz", StreamQualityLabel.detail(null, null, 192_000, null))
    }
}
