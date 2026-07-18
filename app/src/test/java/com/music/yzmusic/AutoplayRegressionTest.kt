package com.music.yzmusic

import com.music.yzmusic.data.model.Song
import com.music.yzmusic.playback.AUTOPLAY_LOW_WATER_MARK
import com.music.yzmusic.playback.MAX_QUEUED_AUTOPLAY
import com.music.yzmusic.playback.QueueBuilder
import com.music.yzmusic.playback.loadAutoplayTracks
import com.music.yzmusic.playback.youtubeSeedFor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoplayRegressionTest {

    private fun song(
        id: String,
        title: String,
        artist: String = "Test Artist",
        isVideo: Boolean = false,
    ) = Song(
        videoId = id,
        title = title,
        artist = artist,
        thumbnailUrl = null,
        durationText = "3:30",
        isVideo = isVideo,
    )

    @Test
    fun `youtubeSeedFor returns standard YouTube ID directly without remote lookup`() = runBlocking {
        val standardSong = song("v_abc123xyz", "Blinding Lights", "The Weeknd")
        val seed = youtubeSeedFor(standardSong)
        assertEquals("v_abc123xyz", seed)
    }

    @Test
    fun `youtubeSeedFor recognizes local content URIs and avoids raw ID usage`() = runBlocking {
        val localSong = song("content://media/external/audio/media/42", "Local File", "Local Artist")
        // When offline or unsearchable, it should safely return null rather than emitting the raw URI
        val seed = youtubeSeedFor(localSong)
        assertTrue(seed == null || !seed.startsWith("content://"))
    }

    @Test
    fun `QueueBuilder extend filters duplicates of existing queue items`() {
        val existing = listOf(
            song("id1", "Starboy", "The Weeknd"),
            song("id2", "Save Your Tears", "The Weeknd"),
        )
        val candidates = listOf(
            song("id1", "Starboy", "The Weeknd"), // exact duplicate ID
            song("id3", "Starboy (Official Video)", "The Weeknd"), // duplicate recording title
            song("id4", "After Hours", "The Weeknd"),
            song("id5", "Levitating", "Dua Lipa"),
        )

        val extended = QueueBuilder.extend(existing = existing, candidates = candidates, limit = 5)
        val ids = extended.map { it.videoId }

        assertFalse("Exact duplicate id1 should not be in extended", "id1" in ids)
        assertFalse("Same recording id3 should not be in extended", "id3" in ids)
        assertTrue("New song id5 should be in extended", "id5" in ids)
    }

    @Test
