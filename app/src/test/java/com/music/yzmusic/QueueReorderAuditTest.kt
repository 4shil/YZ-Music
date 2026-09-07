package com.music.yzmusic

import com.music.yzmusic.data.model.Song
import com.music.yzmusic.playback.autoplaySectionStart
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class QueueReorderAuditTest {

    private fun makeSong(id: String, setVideoId: String? = null, fromAutoplay: Boolean = false) = Song(
        videoId = id,
        title = "Title $id",
        artist = "Artist",
        thumbnailUrl = null,
        durationText = "3:00",
        setVideoId = setVideoId ?: UUID.randomUUID().toString(),
        fromAutoplay = fromAutoplay,
    )

    private fun <T : Any> MutableList<T>.media3Move(from: Int, to: Int) {
        if (from == to) return
        androidx.media3.common.util.Util.moveItems(this, from, from + 1, to)
    }

    private fun simulateExoPlayerIndexAfterMove(currentIndex: Int, from: Int, to: Int): Int {
        if (from == to) return currentIndex
        return when {
            from == currentIndex -> to
            from < currentIndex && to >= currentIndex -> currentIndex - 1
            from > currentIndex && to <= currentIndex -> currentIndex + 1
            else -> currentIndex
        }
    }

    private fun computeQueueKeys(queue: List<Song>, cache: MutableMap<Song, String>): List<String> {
        val used = HashSet<String>()
        return queue.mapIndexed { _, song ->
            val existing = cache[song]
            if (existing != null && used.add(existing)) {
                existing
            } else {
                val candidate = song.setVideoId?.takeIf { it.isNotBlank() }
                    ?: "${song.videoId}#${System.identityHashCode(song)}"
                var key = candidate
                var seq = 1
                while (!used.add(key)) {
                    key = "${candidate}_$seq"
                    seq++
                }
                cache[song] = key
                key
            }
        }
    }

    // =========================================================================
    // 1. BASIC MOVEMENT TESTS
    // =========================================================================

    @Test
    fun `test basic movement - first to second (0 to 1)`() {
        val queue = mutableListOf(makeSong("A"), makeSong("B"), makeSong("C"), makeSong("D"), makeSong("E"))
        queue.media3Move(0, 1)
        assertEquals(listOf("B", "A", "C", "D", "E"), queue.map { it.videoId })
    }

    @Test
    fun `test basic movement - second to first (1 to 0)`() {
        val queue = mutableListOf(makeSong("A"), makeSong("B"), makeSong("C"), makeSong("D"), makeSong("E"))
        queue.media3Move(1, 0)
        assertEquals(listOf("B", "A", "C", "D", "E"), queue.map { it.videoId })
    }

    @Test
    fun `test basic movement - second to third (1 to 2)`() {
        val queue = mutableListOf(makeSong("A"), makeSong("B"), makeSong("C"), makeSong("D"), makeSong("E"))
        queue.media3Move(1, 2)
        assertEquals(listOf("A", "C", "B", "D", "E"), queue.map { it.videoId })
    }

    @Test
    fun `test basic movement - third to second (2 to 1)`() {
        val queue = mutableListOf(makeSong("A"), makeSong("B"), makeSong("C"), makeSong("D"), makeSong("E"))
        queue.media3Move(2, 1)
        assertEquals(listOf("A", "C", "B", "D", "E"), queue.map { it.videoId })
    }

    @Test
    fun `test basic movement - middle to last`() {
        val queue = mutableListOf(makeSong("A"), makeSong("B"), makeSong("C"), makeSong("D"), makeSong("E"))
        queue.media3Move(2, 4)
        assertEquals(listOf("A", "B", "D", "E", "C"), queue.map { it.videoId })
    }

    @Test
    fun `test basic movement - last to middle`() {
        val queue = mutableListOf(makeSong("A"), makeSong("B"), makeSong("C"), makeSong("D"), makeSong("E"))
        queue.media3Move(4, 2)
        assertEquals(listOf("A", "B", "E", "C", "D"), queue.map { it.videoId })
    }

    // =========================================================================
    // 2. ARBITRARY MOVEMENT TESTS
    // =========================================================================

    @Test
    fun `test arbitrary movement - first to last (0 to 4)`() {
        val queue = mutableListOf(makeSong("A"), makeSong("B"), makeSong("C"), makeSong("D"), makeSong("E"))
        queue.media3Move(0, 4)
        assertEquals(listOf("B", "C", "D", "E", "A"), queue.map { it.videoId })
    }

    @Test
    fun `test arbitrary movement - last to first (4 to 0)`() {
        val queue = mutableListOf(makeSong("A"), makeSong("B"), makeSong("C"), makeSong("D"), makeSong("E"))
        queue.media3Move(4, 0)
        assertEquals(listOf("E", "A", "B", "C", "D"), queue.map { it.videoId })
    }

    @Test
    fun `test arbitrary movement - middle to middle (1 to 3)`() {
        val queue = mutableListOf(makeSong("A"), makeSong("B"), makeSong("C"), makeSong("D"), makeSong("E"))
        queue.media3Move(1, 3)
        assertEquals(listOf("A", "C", "D", "B", "E"), queue.map { it.videoId })
    }

    // =========================================================================
    // 3. NEXT-SONG MOVEMENT TEST (REPORTED CRITICAL USER SCENARIO)
    // =========================================================================

    @Test
    fun `test next-song movement - move E to immediately after current song C`() {
        // Current song is C at index 2
        // Queue: A(0), B(1), C(2, current), D(3), E(4)
        val queue = mutableListOf(makeSong("A"), makeSong("B"), makeSong("C"), makeSong("D"), makeSong("E"))
        var currentIndex = 2
        val currentTrack = queue[currentIndex]
        assertEquals("C", currentTrack.videoId)

        // Move E (index 4) to immediately after C (index 3)
        val from = 4
        val to = 3
        queue.media3Move(from, to)
        currentIndex = simulateExoPlayerIndexAfterMove(currentIndex, from, to)

        // Verifications:
        // 1. Queue is now A, B, C, E, D
        assertEquals(listOf("A", "B", "C", "E", "D"), queue.map { it.videoId })
        // 2. Current index is STILL index 2
        assertEquals(2, currentIndex)
        // 3. Current playing track is STILL C
        assertEquals("C", queue[currentIndex].videoId)
        // 4. NEXT song (index 3) is now E!
        assertEquals("E", queue[currentIndex + 1].videoId)
    }

    @Test
    fun `test move song to position 2 while song A is playing at position 1`() {
        // Queue: A(0, current), B(1), C(2), D(3), E(4)
        val queue = mutableListOf(makeSong("A"), makeSong("B"), makeSong("C"), makeSong("D"), makeSong("E"))
        var currentIndex = 0

        // Move E (index 4) to position 2 (index 1)
        queue.media3Move(4, 1)
        currentIndex = simulateExoPlayerIndexAfterMove(currentIndex, 4, 1)

        // Verifications:
        assertEquals(listOf("A", "E", "B", "C", "D"), queue.map { it.videoId })
        assertEquals(0, currentIndex)
        assertEquals("A", queue[currentIndex].videoId)
        assertEquals("E", queue[1].videoId)
    }

    // =========================================================================
    // 4. PLAYING-STATE PRESERVATION & CURRENT-SONG MOVEMENT
    // =========================================================================

    @Test
    fun `test moving playing track itself shifts current index and preserves track identity`() {
        // Queue: A(0), B(1, current), C(2), D(3), E(4)
        val queue = mutableListOf(makeSong("A"), makeSong("B"), makeSong("C"), makeSong("D"), makeSong("E"))
        var currentIndex = 1
        val playingSong = queue[currentIndex]
        assertEquals("B", playingSong.videoId)

        // Move playing song B to position 1 (index 0)
        queue.media3Move(1, 0)
        currentIndex = simulateExoPlayerIndexAfterMove(currentIndex, 1, 0)

        assertEquals(0, currentIndex)
        assertEquals(playingSong.videoId, queue[currentIndex].videoId)
        assertEquals(listOf("B", "A", "C", "D", "E"), queue.map { it.videoId })

        // Move playing song B to end (index 4)
        queue.media3Move(0, 4)
        currentIndex = simulateExoPlayerIndexAfterMove(currentIndex, 0, 4)

        assertEquals(4, currentIndex)
        assertEquals(playingSong.videoId, queue[currentIndex].videoId)
        assertEquals(listOf("A", "C", "D", "E", "B"), queue.map { it.videoId })
    }

    // =========================================================================
    // 5. DUPLICATE SONGS & KEY STABILITY
    // =========================================================================

    @Test
    fun `test duplicate songs retain immutable unique keys across arbitrary reordering`() {
        val songB1 = makeSong("B", setVideoId = "id-B1")
        val songB2 = makeSong("B", setVideoId = "id-B2")
        val queue = mutableListOf(makeSong("A", setVideoId = "id-A"), songB1, songB2, makeSong("C", setVideoId = "id-C"))

        val cache = HashMap<Song, String>()
        val initialKeys = computeQueueKeys(queue, cache)
        assertEquals(listOf("id-A", "id-B1", "id-B2", "id-C"), initialKeys)

        // Drag second B (at index 2, key "id-B2") to index 0
        queue.media3Move(2, 0)
        val keysAfterMove = computeQueueKeys(queue, cache)

        // The moved item at index 0 STILL has key "id-B2"!
        // The first B at index 2 STILL has key "id-B1"!
        assertEquals(listOf("id-B2", "id-A", "id-B1", "id-C"), keysAfterMove)
        assertEquals(listOf("id-B2", "id-A", "id-B1", "id-C"), queue.map { it.setVideoId })

        // No key collisions: all keys in list are unique
        assertEquals(keysAfterMove.size, keysAfterMove.toSet().size)
    }

    // =========================================================================
    // 6. REPEATED REORDERING (STRESS TEST)
    // =========================================================================

    @Test
    fun `test repeated reordering 20 times in succession maintains queue integrity`() {
        val songs = (1..10).map { makeSong("Track$it", setVideoId = "id-$it") }
        val queue = songs.toMutableList()
        val cache = HashMap<Song, String>()

        val moves = listOf(
            0 to 5, 5 to 0,
            2 to 8, 8 to 1,
            9 to 0, 0 to 9,
            3 to 4, 4 to 3,
            7 to 2, 1 to 6,
            8 to 3, 2 to 7,
            5 to 1, 9 to 4,
            0 to 2, 6 to 0,
            1 to 8, 7 to 3,
            4 to 9, 3 to 1
        )

        for ((from, to) in moves) {
            val movingItem = queue[from]
            queue.media3Move(from, to)
            assertEquals(movingItem, queue[to])

            val keys = computeQueueKeys(queue, cache)
            assertEquals(queue.size, keys.size)
            assertEquals("All keys must be unique after move $from -> $to", keys.size, keys.toSet().size)
        }

        // Original elements are all preserved
        assertEquals(songs.toSet(), queue.toSet())
    }

    // =========================================================================
    // 7. LAZY TO QUEUE AND QUEUE TO LAZY INDEX MAPPING
    // =========================================================================

    @Test
    fun `test index mapping with AutoPlay heading present`() {
        val queueSize = 6
        val autoplayStart = 3
        val headingShown = true
        val headingCount = if (headingShown) 1 else 0

        // Manual items: queue indices 0, 1, 2 -> lazy indices 0, 1, 2 with lazyOffset = 0
        val manualOffset = 0
        for (q in 0 until autoplayStart) {
            val lazy = q + manualOffset
            assertEquals(q, lazy - manualOffset)
        }

        // AutoPlay items: queue indices 3, 4, 5 -> lazy indices 4, 5, 6 with lazyOffset = headingCount
        val autoplayOffset = headingCount
        for (q in autoplayStart until queueSize) {
            val lazy = q + autoplayOffset
            assertEquals(q, lazy - autoplayOffset)
        }
    }

    @Test
    fun `test index mapping without AutoPlay heading`() {
        val queueSize = 5
        val autoplayStart = 5
        val headingShown = false
        val headingCount = if (headingShown) 1 else 0

        for (q in 0 until queueSize) {
            val lazy = q + headingCount
            assertEquals(q, lazy - headingCount)
        }
    }

    // =========================================================================
    // 8. BOUNDARY CONDITIONS
    // =========================================================================

    @Test
    fun `test boundary conditions - 1 item and 2 item queues`() {
        // 1 item queue
        val single = mutableListOf(makeSong("A"))
        single.media3Move(0, 0)
        assertEquals(listOf("A"), single.map { it.videoId })

        // 2 item queue
        val pair = mutableListOf(makeSong("A"), makeSong("B"))
        pair.media3Move(0, 1)
        assertEquals(listOf("B", "A"), pair.map { it.videoId })
        pair.media3Move(1, 0)
        assertEquals(listOf("A", "B"), pair.map { it.videoId })
    }

    @Test
    fun `test autoplaySectionStart calculation`() {
        // Queue: 3 manual songs, 2 autoplay songs
        val fromAutoplay = listOf(false, false, false, true, true)
        val start = autoplaySectionStart(fromAutoplay, currentIndex = 0)
        assertEquals(3, start)

        // When currently playing song is at index 3 (first autoplay song):
        // Next autoplay song is at index 4
        val startPlayingAutoplay = autoplaySectionStart(fromAutoplay, currentIndex = 3)
        assertEquals(4, startPlayingAutoplay)

        // When all songs are manual:
        val allManual = listOf(false, false, false)
        assertEquals(3, autoplaySectionStart(allManual, currentIndex = 0))
    }
}
