package com.music.yzmusic

import com.music.yzmusic.data.model.BrowseItem
import com.music.yzmusic.data.model.BrowseType
import com.music.yzmusic.data.model.SearchEntityType
import com.music.yzmusic.data.model.SearchHistoryEntity
import com.music.yzmusic.data.model.Song
import com.music.yzmusic.ui.search.RecentSearchAction
import com.music.yzmusic.ui.search.toRecentAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What a recent is recorded as, and what tapping one does with it.
 *
 * The second half is the reason any of this is stored: a recent has to be
 * openable from what is on the device, with nothing asked of the network.
 */
class SearchHistoryEntityTest {

    private val song = Song(
        videoId = "vid_001",
        title = "Midnight City",
        artist = "M83",
        thumbnailUrl = "https://i.ytimg.com/vi/vid_001/hqdefault.jpg",
        durationText = "4:03",
    )

    @Test
    fun `a recorded track keeps the artist and cover that were on screen`() {
        val entry = SearchHistoryEntity.forSong(song, timestamp = 42L)

        assertEquals(SearchEntityType.SONG, entry.entityType)
        assertEquals("vid_001", entry.id)
        assertEquals("M83", entry.artist)
        assertEquals("https://i.ytimg.com/vi/vid_001/hqdefault.jpg", entry.artworkUrl)
        assertEquals("4:03", entry.durationText)
        assertEquals(42L, entry.timestamp)
    }

    @Test
    fun `a music video is kept apart from the catalogue track of the same name`() {
        val entry = SearchHistoryEntity.forSong(song.copy(isVideo = true), timestamp = 1L)

        // Same video id, different kind of thing: a recent list that merged
        // these would show one of them twice and open it as the other.
        assertEquals(SearchEntityType.VIDEO, entry.entityType)
        assertEquals("SONG:vid_001", SearchHistoryEntity.forSong(song, 1L).identity)
        assertEquals("VIDEO:vid_001", entry.identity)
    }

    @Test
    fun `a browse row is recorded as the kind of page it was found as`() {
        fun browse(type: BrowseType) = BrowseItem("page_1", "Discovery", "Playlist", null, type)

        assertEquals(SearchEntityType.ALBUM, SearchHistoryEntity.forBrowse(browse(BrowseType.ALBUM)).entityType)
        assertEquals(SearchEntityType.ARTIST, SearchHistoryEntity.forBrowse(browse(BrowseType.ARTIST)).entityType)
        assertEquals(SearchEntityType.PLAYLIST, SearchHistoryEntity.forBrowse(browse(BrowseType.PLAYLIST)).entityType)
        assertEquals(SearchEntityType.OTHER, SearchHistoryEntity.forBrowse(browse(BrowseType.CHARTS)).entityType)
    }

    @Test
    fun `a typed term is one entry however it is capitalised`() {
        val first = SearchHistoryEntity.forQuery("M83", timestamp = 1L)
        val again = SearchHistoryEntity.forQuery("  m83  ", timestamp = 2L)

        // Same id, so recording the term again moves the one row to the top
        // rather than adding a second one that reads identically.
        assertEquals(first!!.identity, again!!.identity)
        assertEquals("M83", first.title)
        assertEquals("M83", first.searchTerm)
    }

    @Test
    fun `there is no entry for text that is not there`() {
        assertNull(SearchHistoryEntity.forQuery(""))
        assertNull(SearchHistoryEntity.forQuery("   "))
    }

    @Test
    fun `opening a track recent plays it from what is stored`() {
        val action = SearchHistoryEntity.forSong(song, 1L).toRecentAction()

        val play = action as RecentSearchAction.PlayTrack
        assertEquals("vid_001", play.song.videoId)
        assertEquals("Midnight City", play.song.title)
        assertEquals("M83", play.song.artist)
        assertEquals("https://i.ytimg.com/vi/vid_001/hqdefault.jpg", play.song.thumbnailUrl)
        assertEquals("4:03", play.song.durationText)
    }

    @Test
    fun `opening a page recent opens the page rather than searching for it`() {
        val entry = SearchHistoryEntity(
            id = "MPREb_1",
            entityType = SearchEntityType.PLAYLIST,
            title = "Hurry Up, We're Dreaming",
            artist = "M83",
            artworkUrl = "https://example.test/cover.jpg",
        )

        val open = entry.toRecentAction() as RecentSearchAction.OpenDetail
        assertEquals("MPREb_1", open.item.browseId)
        assertEquals(BrowseType.PLAYLIST, open.item.type)
        assertEquals("Hurry Up, We're Dreaming", open.item.title)
        assertEquals("https://example.test/cover.jpg", open.item.thumbnailUrl)
    }

    @Test
    fun `an album recent opens as an album and an artist recent as an artist`() {
        fun actionFor(type: SearchEntityType) = SearchHistoryEntity(
            id = "page_1",
            entityType = type,
            title = "M83",
        ).toRecentAction() as RecentSearchAction.OpenDetail

        assertEquals(BrowseType.ALBUM, actionFor(SearchEntityType.ALBUM).item.type)
        assertEquals(BrowseType.ARTIST, actionFor(SearchEntityType.ARTIST).item.type)
    }

    @Test
    fun `a typed term has nothing to open, so it is searched for`() {
        val entry = SearchHistoryEntity.forQuery("coldplay yellow", timestamp = 7L)

        val run = entry!!.toRecentAction() as RecentSearchAction.RunQuery
        assertEquals("coldplay yellow", run.query)
        assertEquals(7L, entry.timestamp)
    }

    @Test
    fun `an entry with nothing to show and nothing to open opens nothing`() {
        val nameless = SearchHistoryEntity(
            id = "vid_001",
            entityType = SearchEntityType.SONG,
            title = "   ",
        )
        val idless = SearchHistoryEntity(
            id = "",
            entityType = SearchEntityType.ARTIST,
            title = "M83",
        )

        assertNull(nameless.toRecentAction())
        assertNull(idless.toRecentAction())
    }
}
