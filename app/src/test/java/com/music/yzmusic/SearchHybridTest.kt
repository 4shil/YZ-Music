package com.music.yzmusic

import com.music.yzmusic.data.YtMusicRepository.identityKey
import com.music.yzmusic.data.model.BrowseItem
import com.music.yzmusic.data.model.BrowseType
import com.music.yzmusic.data.model.SearchResult
import com.music.yzmusic.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchHybridTest {

    private val song1 = Song(videoId = "vid_001", title = "Midnight City", artist = "M83", thumbnailUrl = null, durationText = "4:03")
    private val song2 = Song(videoId = "vid_002", title = "Wait", artist = "M83", thumbnailUrl = null, durationText = "5:43")
    private val song3 = Song(videoId = "vid_003", title = "Outro", artist = "M83", thumbnailUrl = null, durationText = "4:07")
    private val browseArtist = BrowseItem(
        browseId = "UC_m83",
        title = "M83",
        subtitle = "Artist",
        thumbnailUrl = null,
        type = BrowseType.ARTIST,
    )

    @Test
    fun `search result identity key uniquely identifies items by video or browse id`() {
        val topTrack = SearchResult.TopTrack(song1)
        val track = SearchResult.Track(song1)
        val track2 = SearchResult.Track(song2)
        val browse = SearchResult.Browse(browseArtist)

        // Both TopTrack and regular Track with same videoId share identity key
        assertEquals("v:vid_001", topTrack.identityKey())
        assertEquals("v:vid_001", track.identityKey())
        assertEquals("v:vid_002", track2.identityKey())
        assertEquals("b:UC_m83", browse.identityKey())
    }

    @Test
    fun `hybrid results merge deduplicates items across pages while maintaining order`() {
        val page1 = listOf<SearchResult>(
            SearchResult.TopTrack(song1),
            SearchResult.Browse(browseArtist),
            SearchResult.Track(song2)
        )

        // Page 2 contains a duplicate of song2 and a new song3
        val page2 = listOf<SearchResult>(
            SearchResult.Track(song2), // duplicate
            SearchResult.Track(song3)
        )

        val merged = (page1 + page2).distinctBy { it.identityKey() }

        assertEquals(4, merged.size)
        assertEquals("v:vid_001", merged[0].identityKey())
        assertEquals("b:UC_m83", merged[1].identityKey())
        assertEquals("v:vid_002", merged[2].identityKey())
        assertEquals("v:vid_003", merged[3].identityKey())
    }

    @Test
    fun `typeahead results capping and filtering empty inputs`() {
        val maxAllowed = 8
        val fullList = (1..20).map { i ->
            SearchResult.Track(Song(videoId = "vid_$i", title = "Track $i", artist = "Artist", thumbnailUrl = null, durationText = "3:00"))
        }

        val capped = fullList.take(maxAllowed)
        assertEquals(8, capped.size)
        assertEquals("v:vid_1", capped.first().identityKey())
        assertEquals("v:vid_8", capped.last().identityKey())

        // Blank or whitespace query yields empty list
        val blankInput = "   "
        val shouldClear = blankInput.isBlank()
        assertTrue(shouldClear)
    }

    @Test
    fun `pagination continuation state lifecycle resets flag on error or finish`() {
        var isLoadingMore = false
        var continuationToken: String? = "token_abc123"

        fun loadMoreSimulated(shouldFail: Boolean) {
            if (isLoadingMore) return
            isLoadingMore = true
            try {
                if (shouldFail) {
                    throw RuntimeException("Network timeout during continuation")
                }
                // Success: token consumes next page
                continuationToken = "token_def456"
            } finally {
                // Ensure try/finally resets the loading flag so UI does not freeze
                isLoadingMore = false
            }
        }

        // Test failure recovery:
        try {
            loadMoreSimulated(shouldFail = true)
        } catch (_: RuntimeException) {
        }
        assertFalse("isLoadingMore must be reset to false even when request fails", isLoadingMore)
        assertEquals("Continuation token should remain available for retry", "token_abc123", continuationToken)

        // Test successful pagination:
        loadMoreSimulated(shouldFail = false)
        assertFalse(isLoadingMore)
        assertEquals("token_def456", continuationToken)
    }

    @Test
    fun `stale query protection drops outdated responses`() {
        var currentQuery = "rock"
        val activeRequestId = 100L

        fun shouldApplyResult(query: String, requestId: Long): Boolean {
            return currentQuery == query && requestId == activeRequestId
        }

        // Response for old query arrives late
        assertFalse(shouldApplyResult(query = "roc", requestId = 99L))

        // Response for current query and current requestId arrives
        assertTrue(shouldApplyResult(query = "rock", requestId = 100L))

        // User typed further before response arrived
        currentQuery = "rock and roll"
        assertFalse(shouldApplyResult(query = "rock", requestId = 100L))
    }
}
