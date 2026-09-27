package com.music.yzmusic

import com.music.yzmusic.data.model.SearchEntityType
import com.music.yzmusic.data.model.SearchHistoryEntity
import com.music.yzmusic.data.settings.SearchHistoryList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the recent searches list does to a set of entries: how it is ordered,
 * how long it is, and what it makes of what an older install left behind.
 */
class SearchHistoryListTest {

    private fun track(id: String, title: String = "Track $id") = SearchHistoryEntity(
        id = id,
        entityType = SearchEntityType.SONG,
        title = title,
        artist = "M83",
        artworkUrl = "https://example.test/$id.jpg",
    )

    private fun term(text: String) = SearchHistoryEntity.forQuery(text, timestamp = 0L)!!

    @Test
    fun `the newest thing looked at is the first row`() {
        val list = SearchHistoryList.record(emptyList(), track("a"))

        assertEquals(listOf("a"), list.map { it.id })
    }

    @Test
    fun `looking at the same thing again moves it rather than repeating it`() {
        val list = SearchHistoryList.record(
            listOf(track("a"), track("b"), track("c")),
            track("b", title = "Track b"),
        )

        // One row for it, at the top, with the metadata as it is now — and the
        // rest of the list not shoved down by a second copy of it.
        assertEquals(listOf("b", "a", "c"), list.map { it.id })
    }

    @Test
    fun `two things that read alike are two entries`() {
        val asTrack = SearchHistoryEntity("shared", SearchEntityType.SONG, "M83")
        val asPage = SearchHistoryEntity("shared", SearchEntityType.ARTIST, "M83")

        val list = SearchHistoryList.record(SearchHistoryList.record(emptyList(), asPage), asTrack)

        assertEquals(2, list.size)
    }

    @Test
    fun `the list stops where it was told to`() {
        var list = emptyList<SearchHistoryEntity>()
        repeat(SearchHistoryList.MAX_ENTRIES + 7) { index ->
            list = SearchHistoryList.record(list, track("id_$index"))
        }

        assertEquals(SearchHistoryList.MAX_ENTRIES, list.size)
        // The cut falls on the oldest, and the newest is still there.
        assertEquals("id_${SearchHistoryList.MAX_ENTRIES + 6}", list.first().id)
        assertTrue(list.none { it.id == "id_0" })
    }

    @Test
    fun `an entry with no name is not recorded at all`() {
        val list = SearchHistoryList.record(
            listOf(track("a")),
            SearchHistoryEntity("", SearchEntityType.SONG, "nameless"),
        )

        assertEquals(listOf("a"), list.map { it.id })
    }

    @Test
    fun `removing takes out the entry with that identity and nothing else`() {
        val list = SearchHistoryList.record(
            listOf(track("a"), track("b")),
            track("c"),
        )

        val after = SearchHistoryList.remove(list, "SONG:b")

        assertEquals(listOf("c", "a"), after.map { it.id })
    }

    @Test
    fun `what is stored comes back as it was written`() {
        val original = listOf(
            track("a", "Midnight City"),
            term("coldplay yellow"),
            SearchHistoryEntity(
                id = "MPREb_1",
                entityType = SearchEntityType.PLAYLIST,
                title = "Hurry Up, We're Dreaming",
                artist = "M83",
                artworkUrl = "https://example.test/playlist.jpg",
                timestamp = 1_700_000_000_000L,
            ),
        )

        val decoded = SearchHistoryList.decode(SearchHistoryList.encode(original))

        assertEquals(original, decoded)
    }

    @Test
    fun `terms written by an older install are kept and still search the same`() {
        // What the previous version stored: a bare array of search terms.
        val legacy = """["M83","coldplay yellow","   ","M83"]"""

        val decoded = SearchHistoryList.decode(legacy)

        // The blank is dropped, the repeat is one row, and the two that are
        // left are terms — which is all such an entry ever could do.
        assertEquals(2, decoded.size)
        assertTrue(decoded.all { it.entityType == SearchEntityType.QUERY })
        assertEquals(listOf("M83", "coldplay yellow"), decoded.map { it.title })
        assertEquals(listOf("M83", "coldplay yellow"), decoded.map { it.searchTerm })
    }

    @Test
    fun `an entry that cannot be read is dropped rather than shown as a blank row`() {
        val stored = """
            [
              {"id":"vid_1","entityType":"SONG","title":"Midnight City","artist":"M83"},
              {"id":"vid_2","entityType":"SONG","title":""},
              {"id":"vid_3","entityType":"NOT_A_KIND","title":"Nothing"},
              {"id":"vid_4","entityType":"ALBUM","title":"Hurry Up"},
              "not even an object",
              42
            ]
        """.trimIndent()

        val decoded = SearchHistoryList.decode(stored)

        assertEquals(listOf("vid_1", "vid_4"), decoded.map { it.id })
        assertEquals("M83", decoded[0].artist)
    }

    @Test
    fun `nothing stored is no history, and unreadable storage is not a crash`() {
        assertTrue(SearchHistoryList.decode(null).isEmpty())
        assertTrue(SearchHistoryList.decode("").isEmpty())
        assertTrue(SearchHistoryList.decode("not json at all").isEmpty())
        // An object where a list was expected is as unreadable as nonsense.
        assertTrue(SearchHistoryList.decode("""{"terms":["M83"]}""").isEmpty())
    }

    @Test
    fun `a stored list longer than the cap is cut to the cap on the way in`() {
        val stored = (0..SearchHistoryList.MAX_ENTRIES + 4)
            .joinToString(",", "[", "]") { """"term_$it"""" }

        assertEquals(SearchHistoryList.MAX_ENTRIES, SearchHistoryList.decode(stored).size)
    }
}
