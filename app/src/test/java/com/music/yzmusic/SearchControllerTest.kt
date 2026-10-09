package com.music.yzmusic

import com.music.yzmusic.data.model.BrowseItem
import com.music.yzmusic.data.model.BrowseType
import com.music.yzmusic.data.model.SearchEntityType
import com.music.yzmusic.data.model.SearchFilter
import com.music.yzmusic.data.model.SearchHistoryEntity
import com.music.yzmusic.data.model.SearchResult
import com.music.yzmusic.data.model.Song
import com.music.yzmusic.data.settings.SearchHistoryList
import com.music.yzmusic.data.settings.SearchHistoryStore
import com.music.yzmusic.ui.search.RecentSearchAction
import com.music.yzmusic.ui.search.SearchController
import com.music.yzmusic.ui.search.SearchPage
import com.music.yzmusic.ui.search.SearchState
import com.music.yzmusic.ui.search.searchResultKey
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The search page's decisions about *when* something is wanted: which request
 * the user is still waiting for, which answer is too late to show, and what
 * the page says when a search finds nothing.
 *
 * Nothing here is asked of the network. The requests are handed in as functions
 * and answer only when the test says so, which makes the order things arrive in
 * something the test decides rather than something it waits for.
 */
class SearchControllerTest {

    private val history = FakeHistory()
    private val pages = AnswerGate()
    private val continuations = AnswerGate()
    private val completions = AnswerGate()
    private val typeahead = AnswerGate()

    private fun track(id: String) = SearchResult.Track(
        Song(videoId = id, title = "Track $id", artist = "M83", thumbnailUrl = null),
    )

    private fun page(id: String) = SearchResult.Browse(
        BrowseItem(id, "Page $id", "Album", null, BrowseType.ALBUM),
    )

    private suspend fun newController(): SearchController {
        val controller = SearchController(
            scope = CoroutineScope(Dispatchers.Unconfined),
            history = history,
            searchPage = { query, filter -> pages.take<SearchPage>("${filter.name}:$query") },
            searchContinuation = { token, filter -> continuations.take<SearchPage>("${filter.name}:$token") },
            fetchSuggestions = { input -> completions.take<List<String>>(input) },
            fetchTypeahead = { input -> typeahead.take<List<SearchResult>>(input) },
            friendlyError = { "could not search: ${it.message}" },
            noResultsText = "nothing matched",
            // Short enough to be a real pause and not a real one; long enough
            // for the ordering these tests care about to be decided by the
            // requests rather than by the clock.
            suggestDebounceMs = 1,
            typeaheadDebounceMs = 2,
            now = { 0L },
        )
        // The three pipelines subscribe in the controller's constructor; let
        // them finish arriving so the first emission has somewhere to go.
        settle()
        return controller
    }

    @Test
    fun `typing asks for completions and not for a search`() = runBlocking {
        val search = newController()

        search.onQueryChange("m83")
        settle()

        assertEquals("m83", search.query.value)
        assertTrue("a keystroke must not run a search", pages.asked.isEmpty())
        // The field is mid-edit, so what is on screen is the field.
        assertEquals(listOf("m83"), search.suggestions.value)
        assertEquals(SearchState.Idle, search.state.value)
    }

    @Test
    fun `a search is run when the term is committed, and the term is remembered`() = runBlocking {
        val search = newController()
        search.onQueryChange("m83")
        settle()

        search.submitSearch()

        assertEquals(listOf("ALL:m83"), pages.asked)
        assertEquals("m83", history.recent.value.single().searchTerm)
        // Committed: a typeahead is a list of things the field could still be
        // run as, and it has been run.
        assertTrue(search.suggestions.value.isEmpty())
        assertTrue(search.typeaheadResults.value.isEmpty())

        val rows = listOf(track("vid_001"))
        pages.answerSuccess("ALL:m83", SearchPage(rows, null))
        assertEquals(SearchState.Results(rows), search.state.await())
    }

    @Test
    fun `a search that matched nothing is not a search that failed`() = runBlocking {
        val search = newController()
        search.searchFor("m83")
        pages.answerSuccess("ALL:m83", SearchPage(emptyList(), null))

        assertEquals(SearchState.Empty, search.state.await { it is SearchState.Empty })
        assertFalse(search.state.value is SearchState.Failed)

        search.searchFor("daft punk")
        pages.answerFailure("ALL:daft punk", IllegalStateException("no network"))

        val failed = search.state.await { it is SearchState.Failed } as SearchState.Failed
        assertEquals("could not search: no network", failed.message)
    }

    @Test
    fun `a search already in flight is not asked for a second time`() = runBlocking {
        val search = newController()

        search.searchFor("m83")
        search.searchFor("m83")

        // Two answers to the same question would land in either order, and the
        // one that is not wanted would be the one that landed.
        assertEquals(1, pages.countOf("ALL:m83"))

        val rows = listOf(track("vid_001"))
        pages.answerSuccess("ALL:m83", SearchPage(rows, null))
        assertEquals(SearchState.Results(rows), search.state.await())
    }

    @Test
    fun `a retry runs the failed search again even while it is still in flight`() = runBlocking {
        val search = newController()
        search.searchFor("m83")
        pages.answerFailure("ALL:m83", IllegalStateException("no network"))
        search.state.await { it is SearchState.Failed }

        search.retrySearch()

        // Asked again rather than swallowed by the duplicate guard, and the
        // field is left exactly as the user left it.
        assertEquals(2, pages.countOf("ALL:m83"))
        assertEquals("m83", search.query.value)
    }

    @Test
    fun `a query already searched is answered from what is held`() = runBlocking {
        val search = newController()
        val rows = listOf(track("vid_001"))
        search.searchFor("m83")
        pages.answerSuccess("ALL:m83", SearchPage(rows, null))
        search.state.await()

        search.onQueryChange("")
        search.onQueryChange("m83")
        settle()
        search.searchFor("m83")

        assertEquals("the second search for this query should cost nothing", 1, pages.countOf("ALL:m83"))
        assertEquals(SearchState.Results(rows), search.state.value)
    }

    @Test
    fun `an answer to a search the user has moved on from never lands`() = runBlocking {
        val search = newController()
        search.searchFor("m83")
        search.searchFor("daft punk")

        val wanted = listOf(page("page_1"))
        pages.answerSuccess("ALL:daft punk", SearchPage(wanted, null))
        search.state.await { it is SearchState.Results }

        // The first search finally answers, far too late to be shown.
        pages.answerSuccess("ALL:m83", SearchPage(listOf(track("vid_001")), null))
        settle()

        assertEquals(wanted, (search.state.value as SearchState.Results).rows)
    }

    @Test
    fun `another page of a search that has been replaced does not join the new results`() = runBlocking {
        val search = newController()
        search.searchFor("m83")
        pages.answerSuccess("ALL:m83", SearchPage(listOf(track("vid_001")), "token_1"))
        search.state.await()
        search.loadMore()

        search.searchFor("daft punk")
        val wanted = listOf(page("page_1"))
        continuations.answerSuccess("ALL:token_1", SearchPage(listOf(track("vid_002")), "token_2"))
        pages.answerSuccess("ALL:daft punk", SearchPage(wanted, null))
        search.state.await { it is SearchState.Results }
        settle()

        assertEquals(wanted, (search.state.value as SearchState.Results).rows)
    }

    @Test
    fun `another page is appended without repeating a row already shown`() = runBlocking {
        val search = newController()
        search.searchFor("m83")
        pages.answerSuccess("ALL:m83", SearchPage(listOf(track("vid_001"), track("vid_002")), "token_1"))
        search.state.await()

        search.loadMore()
        assertTrue("the page is being fetched", search.loadingMore.value)
        continuations.answerSuccess(
            "ALL:token_1",
            SearchPage(listOf(track("vid_002"), track("vid_003")), "token_2"),
        )

        val state = search.state.await { (it as? SearchState.Results)?.rows?.size == 3 } as SearchState.Results
        assertEquals(
            listOf("v:vid_001", "v:vid_002", "v:vid_003"),
            state.rows.map(::searchResultKey),
        )
        assertFalse(search.loadingMore.value)
    }

    @Test
    fun `the list goes back to the top for a new search and for a cleared field, but not for another page`() =
        runBlocking {
            val search = newController()
            assertEquals(0, search.scrollReset.value)

            search.searchFor("m83")
            assertEquals(1, search.scrollReset.value)
            pages.answerSuccess("ALL:m83", SearchPage(listOf(track("vid_001")), "token_1"))
            search.state.await()

            search.loadMore()
            continuations.answerSuccess("ALL:token_1", SearchPage(listOf(track("vid_002")), null))
            search.state.await { (it as? SearchState.Results)?.rows?.size == 2 }
            assertEquals("another page must not move the list", 1, search.scrollReset.value)

            // Emptying the field is how the recents are got back to, and they
            // are not wanted at the bottom of a result list.
            search.onQueryChange("")
            assertEquals(2, search.scrollReset.value)
        }

    @Test
    fun `emptying the field takes down everything, including a search still in flight`() = runBlocking {
        val search = newController()
        search.searchFor("m83")

        search.onQueryChange("")
        settle()
        pages.answerSuccess("ALL:m83", SearchPage(listOf(track("vid_001")), null))
        settle()

        assertEquals(SearchState.Idle, search.state.value)
        assertTrue(search.suggestions.value.isEmpty())
        assertTrue(search.typeaheadResults.value.isEmpty())
    }

    @Test
    fun `a typeahead that lands after a search does not open over the results`() = runBlocking {
        val search = newController()
        search.onQueryChange("m83")
        settle()
        assertTrue("the typeahead should have been asked", typeahead.asked.contains("m83"))

        val rows = listOf(track("vid_001"))
        search.submitSearch()
        pages.answerSuccess("ALL:m83", SearchPage(rows, null))
        search.state.await { it is SearchState.Results }

        // Both of these were asked about the text before it was committed.
        typeahead.answerSuccess("m83", listOf(page("page_1")))
        completions.answerSuccess("m83", listOf("m83", "m83 live"))
        settle()

        assertEquals(SearchState.Results(rows), search.state.value)
        assertTrue(search.typeaheadResults.value.isEmpty())
        assertTrue("committed results own the page", search.suggestions.value.isEmpty())
    }

    @Test
    fun `an obsolete typeahead is cleared rather than shown against a field it is not for`() = runBlocking {
        val search = newController()
        search.onQueryChange("m83")
        settle()

        search.onQueryChange("m83 outro")
        typeahead.answerSuccess("m83", listOf(page("page_1")))
        settle()

        assertTrue(search.typeaheadResults.value.isEmpty())
    }

    @Test
    fun `live results are shown once each, and no more of them than there is room for`() = runBlocking {
        val search = newController()
        search.onQueryChange("m83")
        settle()

        val cap = SearchController.TYPEAHEAD_MAX_RESULTS
        // The duplicates are inside the window on purpose: capping to [cap]
        // rows has to happen *after* the de-duplication, or a repeated id eats a
        // slot and the dropdown comes up short of what it asked to show.
        val rows = listOf(page("page_1"), track("vid_001"), page("page_1"), track("vid_001")) +
            (2..(cap + 5)).map { track("vid_%03d".format(it)) }
        typeahead.answerSuccess("m83", rows)

        val shown = search.typeaheadResults.first { it.isNotEmpty() }
        assertEquals(cap, shown.size)
        assertEquals(cap, shown.map(::searchResultKey).toSet().size)
    }

    @Test
    fun `acting on a result is remembered as the thing itself, not as the query`() = runBlocking {
        val search = newController()
        val song = Song(
            videoId = "vid_001",
            title = "Midnight City",
            artist = "M83",
            thumbnailUrl = "https://i.ytimg.com/vi/vid_001/hqdefault.jpg",
            durationText = "4:03",
        )
        search.searchFor("m83")

        search.recordResult(SearchResult.Track(song))

        val entry = history.recent.value.first()
        assertEquals(SearchEntityType.SONG, entry.entityType)
        assertEquals("vid_001", entry.id)
        assertEquals("M83", entry.artist)
        assertEquals("https://i.ytimg.com/vi/vid_001/hqdefault.jpg", entry.artworkUrl)
    }

    @Test
    fun `a recent opens as what it was found as, from what is on the device`() = runBlocking {
        val search = newController()
        val entry = SearchHistoryEntity(
            id = "MPREb_1",
            entityType = SearchEntityType.PLAYLIST,
            title = "Hurry Up, We're Dreaming",
            artist = "M83",
            artworkUrl = "https://example.test/playlist.jpg",
        )

        val action = search.openRecent(entry)

        val open = action as RecentSearchAction.OpenDetail
        assertEquals("MPREb_1", open.item.browseId)
        assertEquals(BrowseType.PLAYLIST, open.item.type)
        // Opening it is not a search: nothing was asked of the network, and the
        // recent is now the most recent one.
        assertTrue(pages.asked.isEmpty())
        assertEquals(entry, history.recent.value.first())
    }

    @Test
    fun `a recent that was a term is searched for, and floats to the top`() = runBlocking {
        val search = newController()
        val entry = SearchHistoryEntity.forQuery("coldplay yellow", timestamp = 0L)!!

        val action = search.openRecent(entry)

        assertEquals("coldplay yellow", (action as RecentSearchAction.RunQuery).query)
        assertEquals(listOf("ALL:coldplay yellow"), pages.asked)
        assertEquals("coldplay yellow", history.recent.value.first().title)
    }

    @Test
    fun `a recent with nothing to open does nothing at all`() = runBlocking {
        val search = newController()

        val action = search.openRecent(
            SearchHistoryEntity(id = "vid_001", entityType = SearchEntityType.SONG, title = "  "),
        )

        assertNull(action)
        assertTrue(pages.asked.isEmpty())
    }
    @Test
    fun `changing the filter re-runs the same query against that filter`() = runBlocking {
        val search = newController()

        search.searchFor("m83")
        pages.answerSuccess("ALL:m83", SearchPage(listOf(track("vid_001")), null))
        search.state.await()

        search.onFilterChange(SearchFilter.SONGS)

        assertEquals(SearchFilter.SONGS, search.filter.value)
        assertEquals(listOf("ALL:m83", "SONGS:m83"), pages.asked)
        // The filter they are already on is not a new search.
        search.onFilterChange(SearchFilter.SONGS)
        assertEquals(2, pages.asked.size)
    }

    @Test
    fun `a recent taken off the list takes only itself off`() = runBlocking {
        val search = newController()
        search.searchFor("m83")
        search.searchFor("daft punk")
        assertEquals(2, history.recent.value.size)

        search.removeRecent("QUERY:q:m83")

        assertEquals(listOf("daft punk"), history.recent.value.map { it.title })
    }

    /** Gives everything already in flight time to run to a stop. */
    private suspend fun settle() {
        repeat(10) { delay(2) }
    }

    /** The state the page is in, waited for rather than hoped for. */
    private suspend fun StateFlow<SearchState>.await(
        predicate: (SearchState) -> Boolean = { true },
    ): SearchState = withTimeout(TIMEOUT_MS) { first(predicate) }

    /**
     * Requests answered only when the test says so.
     *
     * Which response lands, and in what order, is the substance of several of
     * the things being checked, and a fake that answers on the spot takes that
     * away. A [CompletableDeferred] per request gives the test the ordering
     * directly, with no sleeping and no flakiness in it.
     */
    private class AnswerGate {
        private val pending = LinkedHashMap<String, MutableList<CompletableDeferred<Answered>>>()
        val asked = mutableListOf<String>()

        suspend fun <T> take(key: String): Result<T> {
            asked += key
            val slot = CompletableDeferred<Answered>()
            pending.getOrPut(key) { mutableListOf() } += slot
            val answered = slot.await()
            val error = answered.error
            @Suppress("UNCHECKED_CAST")
            return if (error != null) Result.failure(error) else Result.success(answered.value as T)
        }

        fun answerSuccess(key: String, value: Any) = answer(key, Answered(value = value))

        fun answerFailure(key: String, error: Throwable) = answer(key, Answered(error = error))

        private fun answer(key: String, answered: Answered) {
            val queue = pending[key] ?: throw AssertionError("nothing asked for '$key'; asked: $asked")
            val slot = queue.firstOrNull { !it.isCompleted }
                ?: throw AssertionError("'$key' was already answered; asked: $asked")
            slot.complete(answered)
        }

        fun countOf(key: String) = asked.count { it == key }
    }

    private class Answered(val value: Any? = null, val error: Throwable? = null)

    /** The recent searches, held the way the store holds them. */
    private class FakeHistory : SearchHistoryStore {
        private val _recent = MutableStateFlow<List<SearchHistoryEntity>>(emptyList())
        override val recent: StateFlow<List<SearchHistoryEntity>> = _recent

        override fun record(entry: SearchHistoryEntity) {
            _recent.value = SearchHistoryList.record(_recent.value, entry)
        }

        override fun remove(identity: String) {
            _recent.value = SearchHistoryList.remove(_recent.value, identity)
        }

        override fun clear() {
            _recent.value = emptyList()
        }
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
