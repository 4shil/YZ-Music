package com.music.yzmusic

import com.music.yzmusic.data.model.Song
import com.music.yzmusic.playback.QueueBuilder
import com.music.yzmusic.playback.loadAutoplayTracks
import com.music.yzmusic.playback.youtubeSeedFor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class AutoplayFaultToleranceTest {

    private fun song(id: String, title: String, artist: String = "The Weeknd") =
        Song(videoId = id, title = title, artist = artist, thumbnailUrl = null)

    @Test
    fun `youtubeSeedFor preserves normal YouTube videoId`() = runBlocking {
        val ytSong = song("J7p4bzqLvCw", "Blinding Lights")
