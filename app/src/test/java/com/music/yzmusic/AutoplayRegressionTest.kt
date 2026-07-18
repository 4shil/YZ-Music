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
