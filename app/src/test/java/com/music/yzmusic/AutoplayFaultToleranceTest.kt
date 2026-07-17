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
        val seed = youtubeSeedFor(ytSong)
        assertEquals("J7p4bzqLvCw", seed)
    }

    @Test
    fun `youtubeSeedFor with local content uri and blank title returns null gracefully`() = runBlocking {
        val localSong = song("content://media/external/audio/media/123", "", "")
        val seed = youtubeSeedFor(localSong)
        assertNull("Local file with blank metadata must return null rather than raw content URI", seed)
    }

    @Test
    fun `QueueBuilder extend prioritises audio tracks over video tracks`() {
        val candidates = listOf(
            song("vid1", "Song One").copy(isVideo = true),
            song("aud1", "Song Two").copy(isVideo = false),
            song("aud2", "Song Three").copy(isVideo = false),
        )
        val extra = QueueBuilder.extend(existing = emptyList(), candidates = candidates, limit = 10)
        assertEquals(listOf("aud1", "aud2"), extra.map { it.videoId })
    }

    @Test
    fun `production loadAutoplayTracks tolerates candidate failures and preserves ordering`() = runBlocking {
