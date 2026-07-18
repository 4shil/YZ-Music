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
