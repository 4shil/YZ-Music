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
        val seed = song("seed1", "Seed Track")
        val candidates = listOf(
            song("candA", "Candidate A", "Artist A").copy(isVideo = true),
            song("candB", "Candidate B", "Artist B").copy(isVideo = true),
            song("candC", "Candidate C", "Artist C").copy(isVideo = true),
            song("candD", "Candidate D", "Artist D").copy(isVideo = true),
        )

        val result = loadAutoplayTracks(
            existing = emptyList(),
            seedSong = seed,
            limit = 10,
            fetchRadio = { Result.success(candidates) },
            resolveAudio = { candidate ->
                when (candidate.videoId) {
                    "candA" -> candidate.copy(title = "Candidate A (Audio)")
                    "candB" -> throw IOException("Simulated network timeout for candidate B")
                    "candC" -> candidate.copy(title = "Candidate C (Audio)")
                    "candD" -> throw IOException("Simulated 404 for candidate D")
                    else -> candidate
                }
            },
        )

        assertTrue("loadAutoplayTracks must return success even if individual candidates throw", result.isSuccess)
        val resolved = result.getOrThrow()
        assertEquals(4, resolved.size)

        // Verifies successful candidates survived with converted audio
        assertEquals("Candidate A (Audio)", resolved[0].title)
        assertEquals("Candidate C (Audio)", resolved[2].title)

        // Verifies failed candidates survived as original candidate fallbacks
        assertEquals("Candidate B", resolved[1].title)
        assertEquals("Candidate D", resolved[3].title)

        // Verifies deterministic candidate ordering
        assertEquals(listOf("candA", "candB", "candC", "candD"), resolved.map { it.videoId })

        // Verifies fromAutoplay is set
        assertTrue(resolved.all { it.fromAutoplay })
    }

    @Test
    fun `production loadAutoplayTracks propagates CancellationException`() = runBlocking {
        val seed = song("seed1", "Seed Track")
        val candidates = listOf(
            song("candA", "Candidate A").copy(isVideo = true),
        )

        var cancellationPropagated = false
        try {
            loadAutoplayTracks(
                existing = emptyList(),
                seedSong = seed,
                limit = 10,
                fetchRadio = { Result.success(candidates) },
                resolveAudio = { throw CancellationException("User skipped to next track") },
            )
        } catch (e: CancellationException) {
            cancellationPropagated = true
        }

        assertTrue("CancellationException must be rethrown rather than swallowed as a candidate failure", cancellationPropagated)
    }
}
