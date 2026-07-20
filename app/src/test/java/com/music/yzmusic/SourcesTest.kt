package com.music.yzmusic

import com.music.yzmusic.data.NerdStats
import com.music.yzmusic.data.model.Song
import com.music.yzmusic.data.sources.ModuleSource
import com.music.yzmusic.data.sources.MusicSource
import com.music.yzmusic.data.sources.SourceHealth
import com.music.yzmusic.data.sources.SourceKind
import com.music.yzmusic.data.sources.SourceRegistry
import com.music.yzmusic.data.sources.SourceResolver
import com.music.yzmusic.data.sources.SourceStream
import com.music.yzmusic.data.sources.StreamFormat
import com.music.yzmusic.data.sources.StreamRequest
import com.music.yzmusic.data.sources.TrackMatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.system.measureTimeMillis

/**
 * The parts of the source layer that can be wrong quietly.
 *
 * The cross-source matcher gets most of the attention because it is the one
 * piece here whose failure isn't visible: a bad match doesn't crash or show an
 * error, it plays a different recording under the right title.
 */
class SourcesTest {

    private companion object {
        /** What the raced fakes below all claim to hold, so the matcher accepts them. */
        const val RACE_TITLE = "Paniyon Sa"
        const val RACE_ARTIST = "Atif Aslam"
    }

    // ---- Track identity -----------------------------------------------------

    @Test
    fun `track key round-trips`() {
        val key = SourceRegistry.trackKey("cfg-1", "track-42")
        assertEquals("cfg-1" to "track-42", SourceRegistry.parseTrackKey(key))
    }

    /** A module's own track ids are opaque and some issue ones containing colons. */
    @Test
    fun `track key survives separators inside the track id`() {
        val key = SourceRegistry.trackKey("cfg-1", "al::bum::7")
        assertEquals("cfg-1" to "al::bum::7", SourceRegistry.parseTrackKey(key))
    }

    /** A bare YouTube video id must not be mistaken for a source-backed one. */
    @Test
    fun `plain video ids are not source keys`() {
        assertNull(SourceRegistry.parseTrackKey("dQw4w9WgXcQ"))
        assertNull(SourceRegistry.parseTrackKey(""))
    }

    // ---- Format reporting ---------------------------------------------------

    @Test
    fun `lossless is decided by codec, not bitrate`() {
        assertEquals(true, StreamFormat(codec = "flac", kbps = 900).isLossless)
        assertEquals(true, StreamFormat(codec = "alac").isLossless)
        // A high sample rate does not rescue a lossy codec.
        assertEquals(false, StreamFormat(codec = "opus", sampleRateHz = 192_000).isLossless)
        // Unknown stays unknown rather than defaulting to "no".
        assertNull(StreamFormat().isLossless)
    }

    @Test
    fun `summary states depth and rate and drops bitrate when lossless`() {
        val hiRes = StreamFormat(codec = "flac", kbps = 4608, sampleRateHz = 192_000, bitDepth = 24)
        assertEquals("FLAC · 24-bit · 192 kHz", hiRes.summary)

        val lossy = StreamFormat(codec = "mp3", kbps = 320, sampleRateHz = 44_100)
        assertEquals("MP3 · 44.1 kHz · 320 kbps", lossy.summary)

        assertEquals("Unknown format", StreamFormat().summary)
    }

    /**
     * The badge tiers. "Hi-Quality" exists to separate a module's 320kbps
     * stream from YouTube's 160kbps Opus, which the screen otherwise renders
     * identically — as nothing at all.
     */
    @Test
    fun `names a high-bitrate lossy stream without calling it lossless`() {
        val aac320 = NerdStats.Snapshot(mimeType = "audio/mp4a-latm", bitrateKbps = 320, sampleRateHz = 44_100, channels = 2)
        assertFalse(aac320.isLossless)
        assertTrue(aac320.isHiQuality)

        val opus160 = NerdStats.Snapshot(mimeType = "audio/opus", bitrateKbps = 160, sampleRateHz = 48_000, channels = 2)
        assertFalse(opus160.isHiQuality)

        // Lossless is its own badge and never doubles as this one, however
        // large the bitrate a FLAC reports.
        val flac = NerdStats.Snapshot(mimeType = "audio/flac", bitrateKbps = 1411, sampleRateHz = 44_100, channels = 2)
        assertTrue(flac.isLossless)
        assertFalse(flac.isHiQuality)
    }

    /** With no measured bitrate, what the source said it was sending will do. */
    @Test
