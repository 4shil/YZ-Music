package com.music.yzmusic.ui.search

import com.music.yzmusic.data.model.BrowseItem
import com.music.yzmusic.data.model.SearchFilter
import com.music.yzmusic.data.model.SearchHistoryEntity
import com.music.yzmusic.data.model.SearchResult
import com.music.yzmusic.data.model.Song
import com.music.yzmusic.data.settings.SearchHistoryStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/**
 * The search page's state, and the three pipelines that fill it.
 *
 * It is a class rather than a part of the view model because everything it
 * decides is a decision about *when* something is wanted rather than a
 * decision about Android: which request is still the one the user is waiting
 * for, which answer is too late to show, what the list should say when the
 * search found nothing. Kept here, with the requests handed in as functions,
 * those can be asked directly instead of only by tapping through a device.
 *
 * Nothing is remembered for a process that is not this one: the state lives as
 * long as the scope passed in, and every field below is derived from a request
 * rather than from anything the user has to have saved.
 */
class SearchController(
    private val scope: CoroutineScope,
    private val history: SearchHistoryStore,
    private val searchPage: suspend (String, SearchFilter) -> Result<SearchPage>,
    private val searchContinuation: suspend (String, SearchFilter) -> Result<SearchPage>,
    private val fetchSuggestions: suspend (String) -> Result<List<String>>,
    private val fetchTypeahead: suspend (String) -> Result<List<SearchResult>>,
    /**
     * A failure in the user's terms. Outside this class on purpose: which
     * message a throwable earns is the app's business, and this only has to
     * know that a failure and an empty result are told apart.
     */
    private val friendlyError: (Throwable) -> String,
    /** What a search that matched nothing says. Also the app's wording. */
    private val noResultsText: String,
    /**
     * The top row of a fresh page of results is worth warming before it is
     * tapped — see `MainViewModel.prefetchTopResult`. Opt-in rather than
     * required, so a test can watch only the decisions this class makes.
     */
    private val onResultsLanded: (List<SearchResult>) -> Unit = {},
    private val suggestDebounceMs: Long = SUGGEST_DEBOUNCE_MS,
    private val typeaheadDebounceMs: Long = TYPEAHEAD_MEDIA_DEBOUNCE_MS,
    private val typeaheadMaxResults: Int = TYPEAHEAD_MAX_RESULTS,
    private val cacheEntries: Int = SEARCH_CACHE_ENTRIES,
    private val now: () -> Long = System::currentTimeMillis,
) {
    // ---- What the screen shows ----

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _filter = MutableStateFlow(SearchFilter.ALL)
    val filter: StateFlow<SearchFilter> = _filter.asStateFlow()

    private val _state = MutableStateFlow<SearchState>(SearchState.Idle)
    val state: StateFlow<SearchState> = _state.asStateFlow()

    private val _suggestions = MutableStateFlow<List<String>>(emptyList())
    val suggestions: StateFlow<List<String>> = _suggestions.asStateFlow()

    private val _typeaheadResults = MutableStateFlow<List<SearchResult>>(emptyList())
    val typeaheadResults: StateFlow<List<SearchResult>> = _typeaheadResults.asStateFlow()

    private val _loadingMore = MutableStateFlow(false)
    val loadingMore: StateFlow<Boolean> = _loadingMore.asStateFlow()

    /**
     * Increments once per first page actually asked for, and once for the
     * field being emptied, so the list can go back to the top for the new
     * thing without being thrown about by the old one.
     */
    private val _scrollReset = MutableStateFlow(0)
    val scrollReset: StateFlow<Int> = _scrollReset.asStateFlow()

    /**
     * Whether what is on screen was asked for rather than typed.
     *
     * True from the moment a term is submitted or picked until the field is
     * edited again. The completions and the typeahead are shown only while
     * this is false, and it is said here rather than worked out from whether
     * a list happens to be empty: "is anything being typed" is not the same
     * question as "is this list non-empty", and the answer to the first is
     * what a late answer has to be checked against.
     */
    private val _committed = MutableStateFlow(false)
    val committed: StateFlow<Boolean> = _committed.asStateFlow()

    // ---- The pipelines' own state ----

    /**
     * Buffered so an emission is never lost to a collector that happens to be
     * mid-search, and [BufferOverflow.DROP_OLDEST] because when two arrive
     * together the later one is the one meant.
     */
    private val searchRequests = MutableSharedFlow<SearchRequest>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /**
     * The same arrangement as [searchRequests], for the typeahead — where the
     * drop policy earns its keep rather than just being safe: this one really
     * does take a keystroke each, and a fast typist's backlog should collapse
     * to the prefix they ended on instead of being worked through a letter at
     * a time.
     */
    private val suggestRequests = MutableSharedFlow<String>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /**
     * What makes a late answer harmless: a response is only written to the
     * screen if its id is still the newest one asked for.
     */
    private val newestRequestId = AtomicLong(0L)

    /**
     * Results of recent searches, so a query searched before is answered
     * without asking again. That covers the two ways a query is repeated most:
     * a filter tab, which re-runs the same text against a different tab and
     * then usually goes back, and a term tapped out of the recent searches.
     *
     * Its other half is [prefixMatch], which is what the typeahead makes worth
     * keeping: picking "coldplay yellow" off a list is normally preceded by
     * having searched "coldplay", and those results are close enough to leave
     * up for the moment the narrower one takes rather than blanking the page
     * to a spinner.
     */
    private val cache = LruCache<String, SearchCacheEntry>(cacheEntries)
    private var session: SearchSession? = null

    /**
     * The key of the request in flight, so asking again for the thing already
     * being fetched does not put a second identical request on the wire. A
     * search the user made deliberately — a retry, a recent picked twice — is
     * asked for regardless; see [runSearch].
     */
    private var inFlightKey: String? = null

    /** The last search that was actually run, so it can be run again unchanged. */
    private var lastRequest: SearchRequest? = null

    private data class SearchCacheEntry(
        val rows: List<SearchResult>,
        val continuation: String?,
    )

    private data class SearchSession(
        val key: String,
        val requestId: Long,
        val filter: SearchFilter,
        val continuation: String?,
    )

    /**
     * A search asked for, as a request the pipeline below decides what to do
     * with. [requestId] is what makes a late answer harmless.
     */
    private data class SearchRequest(
        val query: String,
        val filter: SearchFilter,
        val requestId: Long,
    )

    init {
        start()
    }

    @OptIn(FlowPreview::class)
    private fun start() {
        // One long-lived collector each, left running for as long as [scope].
        //
        // A new search no longer cancels the request before it out of a fresh
        // coroutine. Cancelling a call mid-flight tears down its socket, and on
        // a pooled HTTP client that is felt by whatever picks that connection
        // up next — which is how one search could end in "Software caused
        // connection abort" for a request that was never itself in any
        // trouble. A superseded request is dropped by its id instead, and a
        // superseded *typeahead* is dropped by [stillWanted].
        scope.launch { searchRequests.collectLatest { handleSearch(it) } }

        // Both typeaheads debounce, and that is not a timer taken off the
        // search: it is short, and it is paid for by the request behind it
        // being a few hundred bytes rather than a page of results. A search is
        // only ever asked for by a deliberate act, so it is never on a timer.
        scope.launch {
            suggestRequests.debounce(suggestDebounceMs).collectLatest { handleSuggest(it) }
        }
        scope.launch {
            suggestRequests.debounce(typeaheadDebounceMs).collectLatest { handleTypeahead(it) }
        }
    }

    // ---- What the user does ----

    /**
     * A keystroke. Nothing is searched for here: the field asks for
     * completions, and a search is run by a deliberate act — the keyboard's
     * search key, the magnifier, a recent, a suggestion, a filter tab — so a
     * query is fetched once, when it is said to be finished, rather than once
     * per prefix on the way to it.
     */
    fun onQueryChange(value: String) {
        val previous = _query.value
        // The field can report a value it already had (a composition pass
        // re-emitting one keystroke, a suggestion put back after being
        // cleared). Asking again for it would start a lookup whose answer
        // could only be the answer already held.
        if (value == previous) return
        _query.value = value
        if (value.isBlank()) {
            // Emptying the field is how the recent searches are got back to,
            // so it takes down the suggestions, the results and the typeahead
            // together, and puts the list back at the top — the list the user
            // is about to see is the recents, not the tail of a result page
            // scrolled to the end.
            clearResults()
            return
        }
        _committed.value = false
        // The previous keystroke's completions are left up beneath the new
        // lead row while the fresh ones are fetched — the same reasoning as
        // [prefixMatch]: they were right a letter ago, and a list that
        // collapses to one row on every letter is what makes a typeahead feel
        // broken. Text that isn't a continuation of what they were for (the
        // whole field replaced at once, say) drops them instead of showing
        // completions of a query that's gone.
        val stale = if (value.startsWith(previous, true) || previous.startsWith(value, true)) {
            _suggestions.value.drop(1)
        } else {
            emptyList()
        }
        _suggestions.value = listOf(value) + stale.filterNot { it.equals(value, true) }
        suggestRequests.tryEmit(value)
    }

    /**
     * The search button — the keyboard's search action, or the magnifier in
     * the field. Records the term, since a term is what the user came for even
     * when the answer turns out to be an album they then open.
     */
    fun submitSearch() {
        val term = _query.value
        if (term.isBlank()) return
        SearchHistoryEntity.forQuery(term, now())?.let { history.record(it) }
        markCommitted()
        runSearch()
    }

    /**
     * Runs a term the user picked out of a list rather than typed — one of the
     * completions, or a recent that was a term — and floats it to the top of
     * the history. Picking is as deliberate as submitting, so it searches on
     * the spot.
     */
    fun searchFor(term: String) {
        if (term.isBlank()) return
        _query.value = term
        SearchHistoryEntity.forQuery(term, now())?.let { history.record(it) }
        markCommitted()
        runSearch()
    }

    /**
     * Opens a recent as the thing it was recorded as being.
     *
     * A track plays from what is stored and a page opens from what is stored,
     * so neither needs a search or a connection to have been made first. Only
     * a typed term is searched for, because a typed term is all it is.
     */
    fun openRecent(entity: SearchHistoryEntity): RecentSearchAction? {
        val action = entity.toRecentAction() ?: return null
        when (action) {
            is RecentSearchAction.RunQuery -> searchFor(action.query)
            // Not a search, so no results to replace — but the typeahead and
            // the completions have to go, or a late answer for the text that
            // was typed would open over what was just opened.
            is RecentSearchAction.PlayTrack, is RecentSearchAction.OpenDetail -> {
                history.record(entity.copy(timestamp = now()))
                markCommitted()
            }
        }
        return action
    }

    /**
     * Records what the user acted on in a result row, with the metadata that
     * row carries.
     *
     * This is the difference between the recent list being a list of things
     * that were typed and it being a list of what was played or opened: the
     * first has no cover and no artist and no idea what kind of thing it was,
     * and reopening it means searching for the same words again.
     */
    fun recordResult(result: SearchResult) {
        val entry = when (result) {
            is SearchResult.TopTrack -> SearchHistoryEntity.forSong(result.song, now())
            is SearchResult.Track -> SearchHistoryEntity.forSong(result.song, now())
            is SearchResult.Browse -> SearchHistoryEntity.forBrowse(result.item, now())
        }
        history.record(entry)
    }

    fun recordTrack(song: Song) = history.record(SearchHistoryEntity.forSong(song, now()))

    fun recordBrowseItem(item: BrowseItem) =
        history.record(SearchHistoryEntity.forBrowse(item, now()))

    fun removeRecent(identity: String) = history.remove(identity)

    fun clearRecent() = history.clear()

    fun onFilterChange(value: SearchFilter) {
        if (_filter.value == value) return
        _filter.value = value
        runSearch()
    }

    /**
     * Runs the search that failed, again, exactly as it was.
     *
     * The field is not touched: the query is still what the user wants, and a
     * retry that also retyped it would be a different search. The result is
     * deliberately not taken from the cache — a cached page is an answer, and
     * what failed was being asked.
     */
    fun retrySearch() {
        if (lastRequest == null) return
        runSearch(force = true)
    }

    fun loadMore() {
        val current = session ?: return
        val token = current.continuation ?: return
        if (_loadingMore.value) return
        _loadingMore.value = true
        scope.launch {
            try {
                val next = searchContinuation(token, current.filter).getOrNull()
                // The visible search is still this one only: a page that lands
                // after the user has moved on is not an answer to anything.
                if (next != null && session == current && current.requestId == newestRequestId.get()) {
                    val existing = (_state.value as? SearchState.Results)?.rows.orEmpty()
                    val merged = mergeSearchRows(existing, next.rows)
                    cache.put(current.key, SearchCacheEntry(merged, next.continuation))
                    session = current.copy(continuation = next.continuation)
                    _state.value = SearchState.Results(merged)
                }
            } finally {
                _loadingMore.value = false
            }
        }
    }

    // ---- The pipelines ----

    private suspend fun handleSearch(request: SearchRequest) {
        val key = cacheKey(request.query, request.filter)
        // Something to look at immediately: the exact answer if this query has
        // been run before, otherwise the closest earlier one. Only fall back
        // to a spinner with neither — a page that blanks to a spinner on every
        // query is the flicker this is here to stop.
        val exact = cache.get(key)
        val cached = exact?.rows ?: prefixMatch(request.query, request.filter)
        if (exact != null) {
            _state.value = SearchState.Results(exact.rows)
            session = SearchSession(key, request.requestId, request.filter, exact.continuation)
            inFlightKey = null
            return
        }
        _state.value = cached?.let { SearchState.Results(it) } ?: SearchState.Loading
        val result = searchPage(request.query, request.filter)
        // A search that has been superseded shouldn't land on screen, whether
        // it succeeded or failed. The id it would be checked against has
        // already moved past it.
        if (request.requestId != newestRequestId.get()) return
        inFlightKey = null
        _state.value = result.fold(
            onSuccess = { published(it, key, request) },
            onFailure = { SearchState.Failed(friendlyError(it)) },
        )
    }

    private suspend fun handleSuggest(input: String) {
        if (!stillWanted(input)) return
        val fetched = fetchSuggestions(input).getOrNull() ?: return
        // Asked again on the way back; the field is live throughout.
        if (!stillWanted(input)) return
        _suggestions.value = listOf(input) + fetched.filterNot { it.equals(input, true) }
    }

    private suspend fun handleTypeahead(input: String) {
        if (input.isBlank()) {
            _typeaheadResults.value = emptyList()
            return
        }
        // Only while the field is being typed into. A search that has been
        // committed owns the page, and a typeahead that opens over committed
        // results is a list the user did not ask for and cannot get rid of.
        if (!stillWanted(input)) {
            _typeaheadResults.value = emptyList()
            return
        }
        val rows = fetchTypeahead(input).getOrNull().orEmpty()
        if (!stillWanted(input)) {
            _typeaheadResults.value = emptyList()
            return
        }
        // Capped, so the dropdown doesn't grow without bound, and deduped: a
        // live page can repeat an entity, and a dropdown with the same album
        // in it twice reads as a bug.
        _typeaheadResults.value = dedupeSearchRows(rows).take(typeaheadMaxResults)
    }

    /**
     * Whether a list of results or completions for [input] is still what the
     * field should show. False once the field has moved on — typed further, or
     * searched.
     */
    private fun stillWanted(input: String) = _query.value == input && !_committed.value

    /** Caches and publishes the initial result page without waiting for later pages. */
    private fun published(
        page: SearchPage,
        key: String,
        request: SearchRequest,
    ): SearchState {
        val rows = dedupeSearchRows(page.rows)
        // An empty page is an answer, not a failure: nothing matched, the
        // search worked, and saying so with a way to try again would be asking
        // the user to fix something that isn't broken.
        if (rows.isEmpty()) return SearchState.Empty
        cache.put(key, SearchCacheEntry(rows, page.continuation))
        session = SearchSession(key, request.requestId, request.filter, page.continuation)
        onResultsLanded(rows)
        return SearchState.Results(rows)
    }

    private fun runSearch(force: Boolean = false) {
        val query = _query.value
        if (query.isBlank()) {
            clearResults()
            return
        }
        val key = cacheKey(query, _filter.value)
        if (!force && inFlightKey == key) {
            // Already being asked for. Two answers to the same question would
            // arrive in either order, and the one that isn't wanted would be
            // the one that landed.
            return
        }
        val request = SearchRequest(query, _filter.value, newestRequestId.incrementAndGet())
        lastRequest = request
        _scrollReset.value += 1
        session = null
        inFlightKey = key
        _loadingMore.value = false
        searchRequests.tryEmit(request)
    }

    private fun markCommitted() {
        _committed.value = true
        _suggestions.value = emptyList()
        _typeaheadResults.value = emptyList()
    }

    /**
     * The empty field again: no results, no completions, no typeahead, and
     * nothing in flight that can put any of them back.
     */
    private fun clearResults() {
        newestRequestId.incrementAndGet()
        session = null
        inFlightKey = null
        lastRequest = null
        _loadingMore.value = false
        _state.value = SearchState.Idle
        _committed.value = false
        _suggestions.value = emptyList()
        _typeaheadResults.value = emptyList()
        _scrollReset.value += 1
    }

    private fun cacheKey(query: String, filter: SearchFilter) = "${filter.name}:$query"

    /**
     * The results of the longest earlier query this one starts with — near
     * enough to leave up while the narrower search runs.
     */
    private fun prefixMatch(query: String, filter: SearchFilter): List<SearchResult>? {
        val prefix = "${filter.name}:"
        return cache.snapshot()
            .filterKeys { it.startsWith(prefix) && query.startsWith(it.removePrefix(prefix), true) }
            .maxByOrNull { it.key.length }
            ?.value
            ?.rows
    }

    /**
     * A bounded most-recently-used map.
     *
     * [android.util.LruCache] is the obvious one to reach for here, and it is
     * the wrong one: it is an Android class, and this is state worth being
     * able to exercise without a device. The size bound and the ordering are
     * all that is wanted of it, and they are two lines.
     */
    private class LruCache<K, V>(private val maxSize: Int) {
        private val entries = LinkedHashMap<K, V>(16, 0.75f, true)

        @Synchronized
        fun get(key: K): V? = entries[key]

        @Synchronized
        fun put(key: K, value: V) {
            entries[key] = value
            if (entries.size > maxSize) entries.keys.firstOrNull()?.let { entries.remove(it) }
        }

        /** A copy, for the prefix search above; the map is left as it was. */
        @Synchronized
        fun snapshot(): Map<K, V> = LinkedHashMap(entries)
    }

    companion object {
        /**
         * How long a keystroke waits before the typeahead is asked about it.
         *
         * Not the search's timer — searches aren't on a timer at all. This one
         * only stops a fast typist spending a round trip per letter, so it
         * wants to be as short as it can be while still collapsing a burst:
         * long enough that "cold" isn't four lookups, short enough that the
         * list is up by the time the thumb has left the key.
         */
        const val SUGGEST_DEBOUNCE_MS = 180L

        /**
         * Debounce for the parallel media-search pipeline. Slightly longer than
         * text suggestions so it doesn't fire on every single keystroke.
         */
        const val TYPEAHEAD_MEDIA_DEBOUNCE_MS = 350L

        /**
         * Maximum number of live media results shown in the typeahead dropdown.
         *
         * Fifteen rather than eight because that is roughly what fits without the
         * dropdown reaching the keyboard on a short screen, and eight rows left a
         * visible gap that read as "there's more" while offering nothing more.
         */
        const val TYPEAHEAD_MAX_RESULTS = 15

        const val SEARCH_CACHE_ENTRIES = 100
    }
}
