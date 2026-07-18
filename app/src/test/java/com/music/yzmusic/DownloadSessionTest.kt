package com.music.yzmusic

import com.music.yzmusic.data.model.Song
import com.music.yzmusic.download.DownloadProgress
import com.music.yzmusic.download.DownloadSession
import com.music.yzmusic.download.DownloadTarget
import com.music.yzmusic.download.Downloads
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The two things about a download that are remembered rather than observed: what
 * release a batch was, and whether the user has been told how it went.
 *
 * Both are worth pinning because both fail silently. A release that isn't
 * recorded doesn't throw — it just arrives in the Downloads folder as forty
 * unrelated rows, which is exactly what it looked like before any of this
 * existed. And a visibility rule that is off by one visit is either an indicator
 * that can't be dismissed or one that was never seen, and neither is visible
 * from the code: the whole point of the rule is that it holds while nobody is
 * looking.
 */
class DownloadSessionTest {

    private fun song(id: String, title: String = id, artist: String = "Artist") =
        Song(videoId = id, title = title, artist = artist, thumbnailUrl = null)

    /** As a downloaded track comes back off the Downloads page: with a file. */
    private fun onDisk(id: String, album: String? = null) = song(id).copy(
        albumName = album,
        localUri = "content://media/external/audio/media/$id",
    )

    @Before
    fun reset() = clearState()

    @After
    fun tearDown() = clearState()

    /**
     * Both of these are process-wide singletons by design — the queue outlives
     * every screen that can look at it — so a test that leaves anything behind
     * is a test that breaks the next one.
     */
    private fun clearState() {
        DownloadSession.clear()
        Downloads.collections.value.keys.toList().forEach(Downloads::forgetCollection)
    }

    // ---- Whether the indicator is up ---------------------------------------

    @Test
    fun `nothing asked for means no indicator at all`() {
        assertFalse(DownloadSession.state.value.visible)
    }

    @Test
    fun `an indicator survives the batch finishing, and goes on being seen`() {
        DownloadSession.queued(song("a"))
        assertTrue(DownloadSession.state.value.visible)

        DownloadSession.done("a")
        // The whole reason this class exists: a download is started and walked
        // away from, so the moment it finishes is the moment nobody is watching.
        // An indicator that goes with it was never there for the person it was
        // for.
        assertTrue(DownloadSession.state.value.visible)
        assertFalse(DownloadSession.state.value.busy)

        DownloadSession.markSeen()
        assertFalse(DownloadSession.state.value.visible)
    }

    @Test
    fun `looking in halfway through does not sign the batch off`() {
        DownloadSession.queued(song("a"))
        DownloadSession.queued(song("b"))
        DownloadSession.done("a")

        // Opened while "b" is still going: this is checking in, not confirming
        // an outcome, because the outcome hasn't happened yet.
        DownloadSession.markSeen()
        assertTrue(DownloadSession.state.value.visible)

        DownloadSession.done("b")
        assertTrue(DownloadSession.state.value.visible)

        DownloadSession.markSeen()
        assertFalse(DownloadSession.state.value.visible)
    }

    @Test
    fun `a new batch after a seen one brings the indicator back`() {
        DownloadSession.queued(song("a"))
        DownloadSession.done("a")
        DownloadSession.markSeen()
        assertFalse(DownloadSession.state.value.visible)

        DownloadSession.queued(song("b"))
        assertTrue(DownloadSession.state.value.visible)
    }

    @Test
    fun `a failure is something to be told about, not something to hide`() {
        DownloadSession.queued(song("a"))
        DownloadSession.failed("a", "Download failed — check your connection")

        val state = DownloadSession.state.value
        assertTrue(state.visible)
        assertFalse(state.busy)
        assertEquals(1, state.failed)
    }

    @Test
    fun `cancelling the only download leaves nothing to report`() {
        DownloadSession.queued(song("a"))
        DownloadSession.forget("a")
        assertFalse(DownloadSession.state.value.visible)
    }

    /**
     * A retry is the same errand, not a second one. Two rows for one song would
     * put a failure on screen next to its own retry, and the count under the
     * heading would claim more tracks were asked for than were.
     */
    @Test
    fun `re-asking for a failed track replaces its row rather than adding one`() {
        DownloadSession.queued(song("a"))
        DownloadSession.failed("a", "nope")
        DownloadSession.queued(song("a"))

        val state = DownloadSession.state.value
        assertEquals(1, state.items.size)
        assertEquals(DownloadProgress.Queued, state.items.single().progress)
        assertEquals(0, state.failed)
    }

    // ---- How far through it is ---------------------------------------------

    @Test
    fun `progress counts settled tracks whole, however they settled`() {
        DownloadSession.queued(song("a"))
        DownloadSession.queued(song("b"))
        DownloadSession.queued(song("c"))
        DownloadSession.queued(song("d"))

        assertEquals(0f, DownloadSession.state.value.fraction, 0.001f)

        DownloadSession.done("a")
        // A failure is not progress, but it is finished — a bar that can never
        // fill because one track died reads as a download still going.
        DownloadSession.failed("b", "nope")
        DownloadSession.running("c", 0.5f)

        assertEquals(0.625f, DownloadSession.state.value.fraction, 0.001f)
    }

    @Test
    fun `the catalogue swap corrects the row rather than adding another`() {
        DownloadSession.queued(song("vid", title = "Kesariya (Official Video)"))
        DownloadSession.retitle("vid", song("audio", title = "Kesariya"))

        val item = DownloadSession.state.value.items.single()
        // Keyed by what was tapped, titled by what is being fetched.
        assertEquals("vid", item.videoId)
        assertEquals("Kesariya", item.song.title)
    }

    // ---- What a batch was --------------------------------------------------

    @Test
    fun `a playlist is grouped by what was tapped, not by any tag on its tracks`() {
        val target = DownloadTarget(
            id = "VLPL123",
            title = "Late night drive",
            thumbnailUrl = "https://example/cover.jpg",
            playlist = true,
        )
        // A playlist's tracks are off different releases, and two of these name
        // no release at all — which is why the tag grouping the Albums tab used
        // to do on its own could never put this back together.
        val tracks = listOf(onDisk("a", album = "One"), onDisk("b"), onDisk("c"))
        Downloads.rememberCollection(target, tracks)

        val found = Downloads.collectionsAmong(tracks).single()
        assertEquals("Late night drive", found.title)
        assertTrue(found.playlist)
        assertEquals("https://example/cover.jpg", found.thumbnailUrl)
        assertEquals(listOf("a", "b", "c"), found.songs.map { it.videoId })
    }

    /**
     * The record is a claim about a folder the user manages themselves, and the
     * page is the only thing that has actually looked at the disk — so a release
     * is worth exactly the tracks that came back, and one with none left is not
     * worth a row.
     */
    @Test
    fun `a release is only drawn for the files that are still there`() {
