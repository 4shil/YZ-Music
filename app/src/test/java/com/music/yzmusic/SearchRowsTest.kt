package com.music.yzmusic

import com.music.yzmusic.data.model.BrowseItem
import com.music.yzmusic.data.model.BrowseType
import com.music.yzmusic.data.model.SearchResult
import com.music.yzmusic.data.model.Song
import com.music.yzmusic.ui.search.dedupeSearchRows
import com.music.yzmusic.ui.search.mergeSearchRows
import com.music.yzmusic.ui.search.searchResultKey
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What a result row is, which is what both keeps a thing from being shown twice
 * and tells a list which row it is keeping.
 *
 * The identity rules themselves are pinned by `SearchHybridTest`; what is worth
 * checking here is that the rows the production code dedupes and the keys the
 * production list is built on are these ones, since a list that keys on
 * something other than what it dedupes by is how a row ends up with another
 * row's contents.
 */
class SearchRowsTest {

    private fun track(id: String) = Song(videoId = id, title = "Track $id", artist = "M83", thumbnailUrl = null)
    private fun page(id: String) = BrowseItem(id, "Page $id", "Album", null, BrowseType.ALBUM)

    @Test
    fun `the same recording reached twice is one row`() {
        val song = track("vid_001")

        // An unfiltered page promotes the same track it also lists, and the
        // promoted card and the list below it have to agree that it is one.
        assertEquals(searchResultKey(SearchResult.TopTrack(song)), searchResultKey(SearchResult.Track(song)))
    }

    @Test
    fun `a browse id and a video id that read alike are two rows`() {
        assertEquals("v:shared", searchResultKey(SearchResult.Track(track("shared"))))
        assertEquals("b:shared", searchResultKey(SearchResult.Browse(page("shared"))))
    }

    @Test
    fun `a page that repeats within one result set is shown once`() {
        val rows = listOf<SearchResult>(
            SearchResult.Track(track("vid_001")),
            SearchResult.Browse(page("page_1")),
            SearchResult.Track(track("vid_001")),
            SearchResult.TopTrack(track("vid_002")),
            SearchResult.Browse(page("page_1")),
        )

        assertEquals(
            listOf("v:vid_001", "b:page_1", "v:vid_002"),
            dedupeSearchRows(rows).map(::searchResultKey),
        )
    }

    @Test
    fun `a page that repeats a row already shown is not shown again, and order holds`() {
        val first = listOf<SearchResult>(
            SearchResult.TopTrack(track("vid_001")),
            SearchResult.Browse(page("page_1")),
            SearchResult.Track(track("vid_002")),
        )
        val second = listOf<SearchResult>(
            SearchResult.Track(track("vid_002")),
            SearchResult.Track(track("vid_003")),
        )

        assertEquals(
            listOf("v:vid_001", "b:page_1", "v:vid_002", "v:vid_003"),
            mergeSearchRows(first, second).map(::searchResultKey),
        )
    }
}
