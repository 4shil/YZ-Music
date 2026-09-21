package com.music.yzmusic

import com.music.yzmusic.data.model.Song
import com.music.yzmusic.data.settings.SongSort
import com.music.yzmusic.ui.screens.sortedForDetail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.system.measureTimeMillis

class LibrarySortingTest {

    private val sampleSongs = listOf(
        Song(videoId = "s1", title = "Zebra", artist = "Artist A", thumbnailUrl = null, durationText = "3:00", localDateAddedSeconds = 1000L),
        Song(videoId = "s2", title = "apple", artist = "Artist B", thumbnailUrl = null, durationText = "3:30", localDateAddedSeconds = 3000L),
        Song(videoId = "s3", title = "Banana", artist = "Artist C", thumbnailUrl = null, durationText = "2:45", localDateAddedSeconds = 2000L),
        Song(videoId = "s4", title = "cherry", artist = "Artist D", thumbnailUrl = null, durationText = "4:15", localDateAddedSeconds = null)
    )

    @Test
    fun `default sort preserves original order`() {
        val sorted = sampleSongs.sortedForDetail(SongSort.DEFAULT)
        assertEquals(listOf("s1", "s2", "s3", "s4"), sorted.map { it.videoId })
    }

    @Test
    fun `title asc sorts alphabetically case-insensitive`() {
        val sorted = sampleSongs.sortedForDetail(SongSort.TITLE_ASC)
        assertEquals(listOf("s2", "s3", "s4", "s1"), sorted.map { it.videoId }) // apple, Banana, cherry, Zebra
    }

    @Test
    fun `title desc sorts reverse alphabetically case-insensitive`() {
        val sorted = sampleSongs.sortedForDetail(SongSort.TITLE_DESC)
        assertEquals(listOf("s1", "s4", "s3", "s2"), sorted.map { it.videoId }) // Zebra, cherry, Banana, apple
    }

    @Test
    fun `date added asc sorts oldest first with nulls at the end`() {
        val sorted = sampleSongs.sortedForDetail(SongSort.DATE_ADDED_ASC)
        // Timestamps: s1=1000L, s3=2000L, s2=3000L, s4=null (placed at end)
        assertEquals(listOf("s1", "s3", "s2", "s4"), sorted.map { it.videoId })
    }

    @Test
    fun `date added desc sorts newest first with nulls at the end`() {
        val sorted = sampleSongs.sortedForDetail(SongSort.DATE_ADDED_DESC)
        // Timestamps: s2=3000L, s3=2000L, s1=1000L, s4=null (placed at end)
        assertEquals(listOf("s2", "s3", "s1", "s4"), sorted.map { it.videoId })
    }

    @Test
    fun `large collection sorting performs efficiently`() {
        val largeList = (0 until 10_000).map { i ->
            Song(
                videoId = "song_$i",
                title = "Song ${(10000 - i)}",
                artist = "Artist",
                thumbnailUrl = null,
                durationText = "3:00",
                localDateAddedSeconds = (i % 500).toLong()
            )
        }

        val elapsedMs = measureTimeMillis {
            val asc = largeList.sortedForDetail(SongSort.TITLE_ASC)
            assertEquals(10_000, asc.size)
            val dateAsc = largeList.sortedForDetail(SongSort.DATE_ADDED_ASC)
            assertEquals(10_000, dateAsc.size)
        }

        assertTrue("Sorting 10,000 items twice should complete in under 500ms, took $elapsedMs ms", elapsedMs < 500)
    }
}
