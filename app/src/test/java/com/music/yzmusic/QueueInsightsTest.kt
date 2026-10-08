package com.music.yzmusic

import com.music.yzmusic.data.model.Song
import com.music.yzmusic.ui.player.queue.ALL_TAGS_LABEL
import com.music.yzmusic.ui.player.queue.QueueTagAxis
import com.music.yzmusic.ui.player.queue.computeQueueStats
import com.music.yzmusic.ui.player.queue.computeQueueTags
import com.music.yzmusic.ui.player.queue.formatQueueStats
import com.music.yzmusic.ui.player.queue.parseDurationText
import com.music.yzmusic.ui.player.queue.songMatchesQuery
import com.music.yzmusic.ui.player.queue.songMatchesAnyTag
import com.music.yzmusic.ui.player.queue.songMatchesTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun song(
    videoId: String = "v",
    title: String = "Title",
    artist: String = "Artist",
    album: String? = null,
    duration: String? = null,
    radio: String? = null,
    quality: String? = null,
    explicit: Boolean? = null,
) = Song(
    videoId = videoId,
    title = title,
    artist = artist,
    thumbnailUrl = null,
    durationText = duration,
    albumName = album,
    radioName = radio,
    sourceQuality = quality,
    isExplicit = explicit,
)

class QueueInsightsTest {

    // ---- tags ----

    @Test
    fun `mood tags count the songs each station queued`() {
        val tags = computeQueueTags(
            listOf(
                song(videoId = "1", radio = "Romance"),
                song(videoId = "2", radio = "Romance"),
                song(videoId = "3", radio = "Workout"),
            ),
        )
        val mood = tags.first { it.axis == QueueTagAxis.MOOD }
        assertEquals(listOf("Romance", "Workout"), mood.tags.map { it.label })
        assertEquals(listOf(2, 1), mood.tags.map { it.count })
    }

    @Test
    fun `tag order is stable across recomputes and breaks count ties by label`() {
        val queue = listOf(
            song(videoId = "1", radio = "Party"),
            song(videoId = "2", radio = "Chill"),
            song(videoId = "3", radio = "Party"),
        )
        val first = computeQueueTags(queue).first { it.axis == QueueTagAxis.MOOD }.tags
        val second = computeQueueTags(queue.reversed()).first { it.axis == QueueTagAxis.MOOD }.tags
        assertEquals(listOf("Party", "Chill"), first.map { it.label })
        assertEquals(first, second)
    }

    @Test
    fun `axis with nothing to say is left out rather than shown empty`() {
        val tags = computeQueueTags(listOf(song(), song(videoId = "2")))
        // Nothing carries a station, a source quality or an explicit flag.
        assertTrue(tags.none { it.axis == QueueTagAxis.MOOD })
        assertTrue(tags.none { it.axis == QueueTagAxis.QUALITY })
        assertTrue(tags.none { it.axis == QueueTagAxis.EXPLICIT })
    }

    @Test
    fun `artist axis is withheld once the queue is mostly one-song artists`() {
        val many = (1..9).map { song(videoId = "$it", artist = "Artist $it") }
        assertTrue(computeQueueTags(many).none { it.axis == QueueTagAxis.ARTIST })

        val few = (1..3).map { song(videoId = "$it", artist = "Artist $it") }
        val artist = computeQueueTags(few).first { it.axis == QueueTagAxis.ARTIST }
        assertEquals(3, artist.tags.size)
    }

    @Test
    fun `tag covers exactly the songs carrying that label`() {
        val queue = listOf(
            song(videoId = "1", radio = "Romance"),
            song(videoId = "2", radio = "Workout"),
        )
        val romance = computeQueueTags(queue).first { it.axis == QueueTagAxis.MOOD }
            .tags.first { it.label == "Romance" }
        assertTrue(songMatchesTag(queue[0], romance))
        assertFalse(songMatchesTag(queue[1], romance))
    }

    @Test
    fun `no tags selected passes every song, so clearing restores the queue`() {
        val queue = listOf(song(), song(videoId = "2", radio = "Workout"))
        assertTrue(queue.all { songMatchesAnyTag(it, emptySet()) })
    }

    @Test
    fun `two tags together keep the songs of both, not only the ones carrying both`() {
        // The rule the multi-select is built on: a song need only carry one of
        // the chosen tags. Demanding both would empty the list the moment a
        // second chip was tapped.
        val queue = listOf(
            song(videoId = "1", radio = "Romance"),
            song(videoId = "2", radio = "Workout"),
            song(videoId = "3", radio = "Focus"),
        )
        val groups = computeQueueTags(queue)
        val romance = groups.flatMap { it.tags }.first { it.label == "Romance" }
        val workout = groups.flatMap { it.tags }.first { it.label == "Workout" }

        val kept = queue.filter { songMatchesAnyTag(it, setOf(romance, workout)) }
        assertEquals(listOf("1", "2"), kept.map { it.videoId })
    }

    @Test
    fun `tags from different axes combine, so a song matches on whichever it carries`() {
        val queue = listOf(
            song(videoId = "1", radio = "Workout", quality = "LOSSLESS"),
            song(videoId = "2", radio = "Focus", quality = "LOW"),
        )
        val tags = computeQueueTags(queue).flatMap { it.tags }
        val workout = tags.first { it.label == "Workout" }
        val lossless = tags.first { it.label == "Lossless" }

        // The first song carries both, the second neither: only it can be
        // dropped, and it must be dropped on both grounds at once.
        val kept = queue.filter { songMatchesAnyTag(it, setOf(workout, lossless)) }
        assertEquals(listOf("1"), kept.map { it.videoId })
    }

    @Test
    fun `selecting one tag behaves exactly as selecting it alone did`() {
        val queue = listOf(
            song(videoId = "1", radio = "Romance"),
            song(videoId = "2", radio = "Workout"),
        )
        val tags = computeQueueTags(queue).flatMap { it.tags }
        val romance = tags.first { it.label == "Romance" }
        assertEquals(
            queue.filter { songMatchesTag(it, romance) },
            queue.filter { songMatchesAnyTag(it, setOf(romance)) },
        )
    }

    @Test
    fun `quality labels are normalised and unknown quality is not a tag`() {
        val tags = computeQueueTags(
            listOf(
                song(videoId = "1", quality = "LOSSLESS"),
                song(videoId = "2", quality = "high"),
                song(videoId = "3", quality = "something else"),
            ),
        )
        val quality = tags.first { it.axis == QueueTagAxis.QUALITY }
        assertEquals(setOf("Lossless", "High"), quality.tags.map { it.label }.toSet())
    }

    // ---- search ----

    @Test
    fun `blank query matches everything so clearing restores the queue`() {
        val queue = listOf(song(title = "A"), song(videoId = "2", title = "B"))
        assertTrue(queue.all { songMatchesQuery(it, "") })
        assertTrue(queue.all { songMatchesQuery(it, "   ") })
    }

    @Test
    fun `every term has to be found so a second word narrows rather than widens`() {
        val track = song(title = "Midnight City", artist = "M83", album = "Hurry Up")
        assertTrue(songMatchesQuery(track, "midnight"))
        assertTrue(songMatchesQuery(track, "M83"))
        assertTrue(songMatchesQuery(track, "Hurry Up"))
        assertFalse(songMatchesQuery(track, "midnight daft"))
    }

    // ---- duration parsing ----

    @Test
    fun `duration is read from both shapes a row carries`() {
        assertEquals(225, parseDurationText("3:45"))
        assertEquals(3753, parseDurationText("1:02:33"))
    }

    @Test
    fun `unreadable duration contributes nothing rather than a wrong length`() {
        assertNull(parseDurationText(null))
        assertNull(parseDurationText(""))
        assertNull(parseDurationText("live"))
        assertNull(parseDurationText("3"))
        assertNull(parseDurationText("3:xx"))
    }

    // ---- statistics ----

    @Test
    fun `stats add up the queue and count distinct artists and albums`() {
        val stats = computeQueueStats(
            listOf(
                song(videoId = "1", duration = "3:00", artist = "A", album = "One"),
                song(videoId = "2", duration = "4:00", artist = "A", album = "Two"),
                song(videoId = "3", duration = "5:00", artist = "B", album = null),
            ),
        )
        assertEquals(3, stats.songCount)
        assertEquals(720, stats.totalDurationSeconds)
        assertEquals(2, stats.artistCount)
        assertEquals(2, stats.albumCount)
        assertEquals("3 songs · 12m · 2 artists · 2 albums", formatQueueStats(stats))
    }

    @Test
    fun `a queue with no readable duration says so instead of claiming zero`() {
        val stats = computeQueueStats(listOf(song(videoId = "1"), song(videoId = "2")))
        assertNull(stats.totalDurationSeconds)
        assertEquals("2 songs · 1 artist", formatQueueStats(stats))
    }

    @Test
    fun `a single song reads in the singular`() {
        val stats = computeQueueStats(listOf(song(duration = "0:30", album = "One")))
        assertEquals("1 song · <1m · 1 artist · 1 album", formatQueueStats(stats))
    }

    @Test
    fun `an empty queue offers no tags at all`() {
        assertTrue(computeQueueTags(emptyList()).isEmpty())
        assertEquals("0 songs", formatQueueStats(computeQueueStats(emptyList())))
        assertEquals("All", ALL_TAGS_LABEL)
    }
}
