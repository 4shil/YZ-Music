package com.music.yzmusic.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.music.yzmusic.R
import com.music.yzmusic.auth.AuthStore
import com.music.yzmusic.data.AppUpdateChecker
import com.music.yzmusic.data.LocalMediaRepository
import com.music.yzmusic.data.LikeState
import com.music.yzmusic.data.YtMusicRepository
import com.music.yzmusic.data.lyrics.EmbeddedLyrics
import com.music.yzmusic.data.lyrics.LyricDisplayRow
import com.music.yzmusic.data.lyrics.LyricLine
import com.music.yzmusic.data.lyrics.LyricsRepository
import com.music.yzmusic.data.lyrics.LyricsSource
import com.music.yzmusic.data.lyrics.LyricsTranslation
import com.music.yzmusic.data.lyrics.LyricsTranslationStage
import com.music.yzmusic.data.lyrics.LyricsTranslationState
import com.music.yzmusic.data.lyrics.LyricsRomanization
import com.music.yzmusic.data.lyrics.RomanizationResult
import com.music.yzmusic.data.lyrics.pairLyricLayers
import com.music.yzmusic.data.settings.AppSettings
import com.music.yzmusic.data.DebugLog as Log
import com.music.yzmusic.data.innertube.Innertube
import com.music.yzmusic.data.innertube.PlaybackTracker
import com.music.yzmusic.data.innertube.StreamResolver
import com.music.yzmusic.data.model.Account
import com.music.yzmusic.data.model.BrowseItem
import com.music.yzmusic.data.model.BrowseType
import com.music.yzmusic.data.model.DetailPage
import com.music.yzmusic.data.model.HomeShelf
import com.music.yzmusic.data.model.LibraryPage
import com.music.yzmusic.data.model.LibraryState
import com.music.yzmusic.data.model.LikeStatus
import com.music.yzmusic.data.model.PlaylistPrivacy
import com.music.yzmusic.data.model.SearchFilter
import com.music.yzmusic.data.model.SearchHistoryEntity
import com.music.yzmusic.data.model.SearchResult
import com.music.yzmusic.data.model.ShelfItem
import com.music.yzmusic.data.model.Song
import com.music.yzmusic.data.model.SongMenu
import com.music.yzmusic.data.model.UiState
import com.music.yzmusic.data.model.UserPlaylist
import com.music.yzmusic.data.settings.SearchHistory
import com.music.yzmusic.download.Downloads
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.music.yzmusic.data.sources.SourceKind
import com.music.yzmusic.data.sources.SourceRegistry
import com.music.yzmusic.data.sources.SourceResolver
import com.music.yzmusic.data.sources.TrackMatcher
import com.music.yzmusic.playback.StreamChoice
import com.music.yzmusic.ui.search.RecentSearchAction
import com.music.yzmusic.ui.search.SearchController
import com.music.yzmusic.ui.search.SearchPage
import com.music.yzmusic.ui.search.SearchState
import java.util.concurrent.atomic.AtomicLong
import java.util.Locale

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val authStore = AuthStore(app)

    private val _signedIn = MutableStateFlow(authStore.isSignedIn)
    val signedIn: StateFlow<Boolean> = _signedIn.asStateFlow()

    private val _home = MutableStateFlow<UiState<List<HomeShelf>>>(UiState.Loading)
    val home: StateFlow<UiState<List<HomeShelf>>> = _home.asStateFlow()

    /**
     * Token for the next page of Home shelves; null once there's nothing
     * more. Declared here rather than by [loadMoreHome] because [init] calls
     * [loadHome] synchronously up to its first suspension point — a property
     * declared after [init] would still be null when that runs.
     */
    private var homeContinuation: String? = null

    /** Titles already on screen, so a later page can't repeat a shelf. */
    private val homeSeenTitles = mutableSetOf<String>()

    private val _homeLoadingMore = MutableStateFlow(false)
    val homeLoadingMore: StateFlow<Boolean> = _homeLoadingMore.asStateFlow()

    private var loadMoreJob: Job? = null
    private var lastLoadMoreErrorTime = 0L
    private var consecutiveEmptyPages = 0

    private val _explore = MutableStateFlow<UiState<List<HomeShelf>>>(UiState.Loading)
    val explore: StateFlow<UiState<List<HomeShelf>>> = _explore.asStateFlow()

    /**
     * The search page: what it is showing, and the three pipelines that fill
     * it. Everything about *when* something is wanted lives in
     * [SearchController] rather than here — which request the user is still
     * waiting for, which answer is too late to show, what an empty result
     * says — so what is left of the search page in this class is the wiring:
     * where the requests come from, and the one thing worth doing with a fresh
     * page of results.
     */
    private val search = SearchController(
        scope = viewModelScope,
        history = SearchHistory,
        searchPage = { query, filter ->
            YtMusicRepository.searchPage(query, filter).map { SearchPage(it.rows, it.continuation) }
        },
        searchContinuation = { token, filter ->
            YtMusicRepository.searchContinuation(token, filter)
                .map { SearchPage(it.rows, it.continuation) }
        },
        fetchSuggestions = YtMusicRepository::searchSuggestions,
        fetchTypeahead = { input -> YtMusicRepository.searchTypeahead(input).map { it.rows } },
        friendlyError = { it.friendly() },
        noResultsText = text(R.string.no_results),
        onResultsLanded = { rows -> prefetchTopResult(rows) },
    )

    val query: StateFlow<String> = search.query

    /**
     * What the search page is showing.
     *
     * A type of its own rather than the app-wide [UiState] because a search has
     * two very different nothings to say — nothing searched for, nothing
     * matched, and the search could not be done — and one type for all three is
     * what made the last two look alike. See [SearchState].
     */
    val searchState: StateFlow<SearchState> = search.state

    val searchLoadingMore: StateFlow<Boolean> = search.loadingMore
    val isLoadingMore: StateFlow<Boolean> = search.loadingMore

    val searchScrollReset: StateFlow<Int> = search.scrollReset

    /** The mixed YouTube Music result page is the fast, useful default. */
    val filter: StateFlow<SearchFilter> = search.filter

    /**
     * What the search page offers while a query is being typed, led by the
     * query itself.
     *
     * Non-empty *is* the signal that the field is mid-edit, so the screen needs
     * no second flag: these rows are shown in place of the results whenever
     * there are any, and cleared the moment a search is actually run.
     */
    val suggestions: StateFlow<List<String>> = search.suggestions

    val typeaheadResults: StateFlow<List<SearchResult>> = search.typeaheadResults

    /** Synced lyrics for whatever is playing; null while unknown or absent. */
    private val _lyrics = MutableStateFlow<List<LyricLine>?>(null)
    val lyrics: StateFlow<List<LyricLine>?> = _lyrics.asStateFlow()

    /** Which of the four databases [lyrics] came from, for the panel's credit. */
    private val _lyricsSource = MutableStateFlow<LyricsSource?>(null)
    val lyricsSource: StateFlow<LyricsSource?> = _lyricsSource.asStateFlow()

    /**
     * Whether the lookup for the current track has finished. [lyrics] alone
     * can't tell "still looking" apart from "looked, found nothing" — both
     * are null — and the player needs that distinction to show "Lyrics not
     * available" only once it actually means that.
     */
    private val _lyricsChecked = MutableStateFlow(false)
    val lyricsChecked: StateFlow<Boolean> = _lyricsChecked.asStateFlow()

    // ---- Lyrics Translation ----
    private val _lyricsTranslation = MutableStateFlow<LyricsTranslationState>(LyricsTranslationState.Idle)
    val lyricsTranslation: StateFlow<LyricsTranslationState> = _lyricsTranslation.asStateFlow()

    /**
     * Which rendering of the lyrics is on screen.
     *
     * Not a preference for its own sake: [availableLyricsModes] is what the
     * player reads to decide whether a mode is worth offering, and a mode
     * whose layer never arrived is not offered. Falling back silently would be
     * worse than not offering it — the reader would ask for a translation and
     * be handed the original with no way to tell the difference.
     */
    enum class LyricsDisplayMode { ORIGINAL, TRANSLATED, ROMANIZED }

    /** The alternate renderings the source brought with the words. */
    data class LyricsAlternates(
        val translation: List<LyricLine>? = null,
        val romanization: List<LyricLine>? = null,
    ) {
        fun forMode(mode: LyricsDisplayMode): List<LyricLine>? = when (mode) {
            LyricsDisplayMode.ORIGINAL -> null
            LyricsDisplayMode.TRANSLATED -> translation
            LyricsDisplayMode.ROMANIZED -> romanization
        }

        val isEmpty: Boolean get() = translation == null && romanization == null
    }

    private val _lyricsAlternates = MutableStateFlow(LyricsAlternates())
    val lyricsAlternates: StateFlow<LyricsAlternates> = _lyricsAlternates.asStateFlow()

    /**
     * Where a generated romanization has got to.
     *
     * Distinct from "not applicable" on purpose. A lyric written in Latin
     * script needs no romanization and saying so is a *success*; a track the
     * engine could not convert is a different answer, and a request still
     * running is a third. Collapsing them would leave the player either
     * hiding a mode that works or offering one that never will.
     */
    sealed interface RomanizationState {
        /** Nothing asked for yet. */
        data object Idle : RomanizationState

        /** A request is in flight for [trackId]. */
        data class Generating(val trackId: String) : RomanizationState

        /** Done. [lines] replace the original on its own clock. */
        data class Ready(
            val lines: List<LyricLine>,
            val sourceScript: String,
            val fromCache: Boolean,
        ) : RomanizationState

        /** The words are already Latin script; there is nothing to convert. */
        data object AlreadyRomanized : RomanizationState

        /** Tried, or knowable up front, and it cannot be done for this track. */
        data class Unavailable(val reason: String) : RomanizationState

        /**
         * Is it still worth tapping Romanized?
         *
         * True while idle, mid-request, or finished. False for the two states
         * that describe a finished answer with nothing in it — the words are
         * already Latin, or the engine could not render them. Public because
         * this is the decision that makes the feature reachable at all: get it
         * wrong in the cautious direction and the mode silently never appears,
         * with nothing reporting an error.
         */
        val offersRomanized: Boolean
            get() = when (this) {
                Idle, is Generating, is Ready -> true
                AlreadyRomanized, is Unavailable -> false
            }
    }

    private val _lyricsRomanization = MutableStateFlow<RomanizationState>(RomanizationState.Idle)
    val lyricsRomanization: StateFlow<RomanizationState> = _lyricsRomanization.asStateFlow()
    private var lyricsRomanizationJob: Job? = null
    private val lyricsRomanizationGeneration = AtomicLong(0)

    private val _lyricsDisplayMode = MutableStateFlow(LyricsDisplayMode.ORIGINAL)
    val lyricsDisplayMode: StateFlow<LyricsDisplayMode> = _lyricsDisplayMode.asStateFlow()

    /**
     * The modes worth offering for the current track.
     *
     * TRANSLATED counts as available when *either* the source shipped one or
     * the on-device engine has produced one — the first is instant and the
     * second is what the reader is really waiting for, and both satisfy the
     * same request.
     */
    val availableLyricsModes: StateFlow<Set<LyricsDisplayMode>> = combine(
        _lyricsAlternates,
        _lyricsTranslation,
        _lyricsRomanization,
        _lyrics,
    ) { alternates, translation, romanization, lines ->
        if (lines.isNullOrEmpty()) {
            emptySet()
        } else {
            buildSet {
                add(LyricsDisplayMode.ORIGINAL)
                if (alternates.translation != null ||
                    (translation is LyricsTranslationState.Ready && translation.lines.isNotEmpty())
                ) {
                    add(LyricsDisplayMode.TRANSLATED)
                }
                // ROMANIZED is offered whenever the mode could produce
                // something, not only when a provider already did. Requiring
                // a publisher's x-roman meant the mode existed for the handful
                // of tracks that shipped one and was absent everywhere else,
                // which is the whole gap this feature closes. Both
                // AlreadyRomanized and Unavailable keep it hidden: those are
                // answers, and re-asking would only reach the same one.
                if (alternates.romanization != null || romanization.offersRomanized) {
                    add(LyricsDisplayMode.ROMANIZED)
                }
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), setOf(LyricsDisplayMode.ORIGINAL))

    /**
     * The rows to actually draw: the original line, plus whichever alternate
     * layer is switched on beneath it.
     *
     * The original is never replaced. An alternate resolves to no sub-line at
     * all rather than to a different song, so the panel keeps one row per
     * line of the track and the singer's words stay put whatever the reader
     * has toggled on top of them.
     */
    val displayRows: StateFlow<List<LyricDisplayRow>?> = combine(
        _lyrics,
        _lyricsAlternates,
        _lyricsTranslation,
        _lyricsRomanization,
        _lyricsDisplayMode,
    ) { lines, alternates, translation, romanization, mode ->
        if (lines == null) return@combine null
        val layer = when (mode) {
            LyricsDisplayMode.ORIGINAL -> null
            LyricsDisplayMode.TRANSLATED ->
                (translation as? LyricsTranslationState.Ready)?.lines?.takeIf { it.isNotEmpty() }
                    ?: alternates.translation
            // Publisher first, generated second. The provider's own romanization
            // is authoritative — it was written against the song, not derived
            // from a rule set — and a generated one is a fallback for when
            // there is no provider answer at all, never a replacement.
            LyricsDisplayMode.ROMANIZED ->
                alternates.romanization
                    ?: (romanization as? RomanizationState.Ready)?.lines?.takeIf { it.isNotEmpty() }
        }
        pairLyricLayers(lines, layer)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * Which second voice is currently drawn. [LyricsDisplayMode.ORIGINAL]
     * means the panel is showing the song alone. The discs read this for their
     * lit state.
     */
    val activeSubLayer: StateFlow<LyricsDisplayMode> = _lyricsDisplayMode

    /**
     * Turns a second voice on, or back off if that one is already showing.
     *
     * The two discs are switches, not a three-way selector, so the same tap
     * that asks for a layer takes it away again. Tapping the lit disc leaves
     * the original on screen alone rather than falling through to a different
     * layer, which is what a segmented control would do and would be
     * indistinguishable from a bug.
     */
    fun toggleLyricsSubLayer(mode: LyricsDisplayMode) {
        if (mode == LyricsDisplayMode.ORIGINAL) {
            setLyricsDisplayMode(LyricsDisplayMode.ORIGINAL)
            return
        }
        setLyricsDisplayMode(
            if (_lyricsDisplayMode.value == mode) LyricsDisplayMode.ORIGINAL else mode,
        )
    }

    /**
     * Chooses which second voice is drawn. A layer the current track cannot
     * satisfy is ignored rather than stored, so the control can never leave
     * the player showing something the mode does not name.
     */
    fun setLyricsDisplayMode(mode: LyricsDisplayMode) {
        if (mode != LyricsDisplayMode.ORIGINAL && mode !in availableLyricsModes.value) return
        _lyricsDisplayMode.value = mode
        // Asking for Romanized is the request. Started here rather than in the
        // screen so the trigger is the layer itself, wherever it was set from,
        // and so the reader's one tap on the disc is the only thing that causes
        // work — no second control, and no work for a layer never chosen.

        if (mode == LyricsDisplayMode.ROMANIZED &&
            _lyricsRomanization.value is RomanizationState.Idle
        ) {
            romanizeLyrics(deviceLanguageTag())
        }
    }

    /** The same locale the player resolves its translation target from. */
    private fun deviceLanguageTag(): String =
        getApplication<android.app.Application>()
            .resources.configuration.locales[0]
            .language

    private var lyricsTranslationJob: Job? = null
    private val lyricsTranslationGeneration = AtomicLong(0L)

    private var lyricsJob: Job? = null
    /**
     * Bumped on every load so a slow answer for a track the listener has
     * already left cannot land on the next one. Cancelling the job alone was
     * not enough: a lookup that had already resumed and was past its last
     * cancellation point would still write, and the player would show the
     * previous song's lyrics.
     */
    private val lyricsGeneration = AtomicLong(0L)

    /**
     * What the loaded lyrics are for. Both the track *and* the settings that
     * chose them, so switching a source on or off re-runs the lookup rather
     * than leaving the last answer sitting on a player that would now find a
     * different one.
     */
    private var lyricsFor: Pair<String, Set<LyricsSource>>? = null

    /**
     * Called as the playing track changes; cheap no-op when already loaded.
     *
     * [localUri] is the file this track plays from when it is on the device,
     * and it is tried before the network: a downloaded track had its lyrics
     * fetched once already and written into its own file (see `LyricsTag`), so
     * asking the same servers again is a round trip to arrive at a string that
     * is on disk — and one that fails outright with the connection off, which
     * is what made a downloaded song show nothing offline.
     */
    fun loadLyrics(
        videoId: String,
        title: String,
        artist: String,
        durationMs: Long,
        album: String? = null,
        localUri: String? = null,
    ) {
        val sources = if (AppSettings.syncedLyrics.value) {
            AppSettings.lyricsSources.value
        } else {
            emptySet()
        }
        val key = videoId to sources
        if (lyricsFor == key) return
        lyricsFor = key
        _lyrics.value = null
        _lyricsSource.value = null
        // A new track has no alternates yet, and a display mode chosen for
        // the last one would otherwise be left pointing at a layer that is
        // not coming.
        _lyricsAlternates.value = LyricsAlternates()
        _lyricsDisplayMode.value = LyricsDisplayMode.ORIGINAL
        lyricsTranslationJob?.cancel()
        lyricsTranslationGeneration.incrementAndGet()
        _lyricsTranslation.value = LyricsTranslationState.Idle
        // Same reasoning as the translation above, and it matters more here:
        // a romanization left over from the previous track is a set of words
        // in the wrong language standing in for the current song's words.
        lyricsRomanizationJob?.cancel()
        lyricsRomanizationGeneration.incrementAndGet()
        _lyricsRomanization.value = RomanizationState.Idle
        lyricsJob?.cancel()
        val generation = lyricsGeneration.incrementAndGet()
        if (sources.isEmpty()) {
            // Switched off, or every source unticked. Nothing to look up, and
            // nothing to say about it — the player drops the lyric strip
            // rather than reporting a track with no lyrics.
            _lyricsChecked.value = true
            return
        }
        _lyricsChecked.value = false
        lyricsJob = viewModelScope.launch {
            // The file first, and without the duration gate below: a length is
            // only needed to *match* a track against a stranger's database, and
            // nothing is being matched here — these lyrics were written into
            // this exact file, for this exact recording.
            if (localUri != null) {
                EmbeddedLyrics.forUri(getApplication(), localUri)?.let { embedded ->
                    if (generation == lyricsGeneration.get() && lyricsFor?.first == videoId) {
                        _lyrics.value = embedded
                        // No source to name: what the file records is the lyrics,
                        // not which of the eight services they came from months ago.
                        _lyricsSource.value = null
                        _lyricsChecked.value = true
                    }
                    return@launch
                }
            }
            if (durationMs <= 0L) {
                // Duration arrives a beat after the track does; wait for it.
                lyricsFor = null
                return@launch
            }
            val found = LyricsRepository.lyrics(
                videoId, title, artist, durationMs, album, sources,
                AppSettings.lyricsSourceOrder.value, AppSettings.prioritizeSyllableSync.value,
            )
            if (generation != lyricsGeneration.get() || lyricsFor?.first != videoId) {
                // The listener moved on while this was in flight. Dropping the
                // answer is the whole point: it is correct for a track nobody
                // is playing any more.
                return@launch
            }
            _lyrics.value = found?.lines
            _lyricsSource.value = found?.source
            _lyricsAlternates.value = LyricsAlternates(found?.translation, found?.romanization)
            _lyricsChecked.value = true
        }
    }

    /**
     * Translates lyrics on device via ML Kit. Bounded to recent songs and
     * cancels automatically if track changes before completion.
     */
    fun translateLyrics(targetLanguageTag: String) {
        // Lyrics, not `lyricsFor`: a track change clears the lyrics first, so
        // a non-empty list is always the current song's, while `lyricsFor` is
        // transiently null while the duration gate is waiting. Returning
        // silently on that null made a tap do nothing at all.
        val sourceLines = _lyrics.value?.takeIf { it.isNotEmpty() } ?: return
        val target = Locale.forLanguageTag(targetLanguageTag).language.ifBlank { targetLanguageTag }

        if (!shouldStartTranslation(_lyricsTranslation.value, lyricsTranslationJob, target)) return

        lyricsTranslationJob?.cancel()
        val generation = lyricsTranslationGeneration.incrementAndGet()
        lyricsTranslationJob = viewModelScope.launch {
            val result = try {
                LyricsTranslation.translate(
                    lines = sourceLines,
                    targetLanguageTag = target,
                    // The reader's data rule, already applied. A model is a few
                    // megabytes of the reader's data; asking ML Kit to wait for
                    // WiFi instead of being told no left the panel spinning on a
                    // download that was never going to start.
                    networkAllowsDownload = AppSettings.downloadsAllowedNow,
                ) { stage ->
                    if (generation == lyricsTranslationGeneration.get()) {
                        _lyricsTranslation.value = LyricsTranslationState.Loading(target, stage)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                // ML Kit and Play Services fail in ways `catch (Exception)`
                // does not catch — UnsatisfiedLinkError when the optional
                // module is missing, AssertionError out of the model loader.
                // Letting those escape is what used to strand the player: the
                // state kept saying Loading, the job was gone, and every later
                // tap was refused by the guard above, so the disc showed
                // "Downloading English…" with no way out of it but changing
                // song. Nothing below may leave a Loading behind.
                Log.w(TAG, "Lyric translation ended unexpectedly", error)
                LyricsTranslationState.Unavailable(target)
            }
            // The generation is the whole guard. It moves on a new request and
            // on a track change, and a track change also resets the state to
            // Idle — so a stale result is always one that has already been
            // superseded. Reading `lyricsFor` here as well only added a way to
            // drop a live result and strand the state.
            if (generation == lyricsTranslationGeneration.get()) {
                _lyricsTranslation.value = result
            }
        }
    }

    /**
     * Produces a romanization for the current track, on device.
     *
     * Nothing about this touches playback: it reads lyrics, writes lyrics, and
     * never seeks, never changes the queue. The generation counter is the
     * important part — a transliteration that finishes after the reader has
     * skipped to the next song would otherwise publish the previous track's
     * words onto the new one, and the reader would sing the wrong lyric with
     * the right music and no way to know.
     */
    fun romanizeLyrics(targetLanguageTag: String) {
        val sourceLines = _lyrics.value?.takeIf { it.isNotEmpty() } ?: return
        val trackId = lyricsFor?.first ?: return
        // Recorded with the request rather than read off the device later, so
        // the cache key names the target the work was actually done for.
        val target = Locale.forLanguageTag(targetLanguageTag).language
            .ifBlank { targetLanguageTag }

        when (val current = _lyricsRomanization.value) {
            is RomanizationState.Generating -> if (current.trackId == trackId) return
            is RomanizationState.Ready -> return
            else -> Unit
        }

        lyricsRomanizationJob?.cancel()
        val generation = lyricsRomanizationGeneration.incrementAndGet()
        _lyricsRomanization.value = RomanizationState.Generating(trackId)
        lyricsRomanizationJob = viewModelScope.launch {
            val result = LyricsRomanization.forContext(getApplication()).romanize(sourceLines, target)
            // Checked twice: once for a newer request, once for a newer track.
            // Either means this answer describes words nobody is looking at.
            if (generation != lyricsRomanizationGeneration.get() || lyricsFor?.first != trackId) {
                return@launch
            }
            _lyricsRomanization.value = when (result) {
                is RomanizationResult.Romanized -> RomanizationState.Ready(
                    lines = result.lines,
                    sourceScript = result.sourceLanguage,
                    fromCache = result.fromCache,
                )
                // A successful answer, not a failure: the words were Latin
                // already, so there is nothing to offer and nothing went wrong.
                RomanizationResult.AlreadyRomanized -> RomanizationState.AlreadyRomanized
                // The original stays on screen either way. The distinction is
                // only about whether to keep offering the mode.
                RomanizationResult.Unavailable -> RomanizationState.Unavailable(
                    "Script not supported on device",
                )
            }
        }
    }

    private val _account = MutableStateFlow<Account?>(null)
    val account: StateFlow<Account?> = _account.asStateFlow()

    private val _history = MutableStateFlow<UiState<List<Song>>>(UiState.Loading)
    val history: StateFlow<UiState<List<Song>>> = _history.asStateFlow()

    private val _library = MutableStateFlow<UiState<LibraryPage>>(UiState.Loading)
    val library: StateFlow<UiState<LibraryPage>> = _library.asStateFlow()

    /**
     * Album / artist / playlist pages, as a stack — opening an artist from an
     * album page and pressing back returns to the album, not to search.
     */
    private val _detailStack = MutableStateFlow<List<DetailPage>>(emptyList())
    val detailStack: StateFlow<List<DetailPage>> = _detailStack.asStateFlow()


    /** Set once per launch if GitHub has a release newer than this build. */
    val updateAvailable: StateFlow<AppUpdateChecker.UpdateInfo?> = AppUpdateChecker.available

    // ---- Ratings, library and playlists -------------------------------------

    /**
     * Ratings this session has set, which win over whatever the library feed
     * last said.
     *
     * Kept apart from the library rather than folded into it because the two
     * answer different questions: Liked Music is what YouTube knew when the
     * page was fetched, and this is what the user has done since. Layering
     * them ([likeStatuses]) means a tap shows immediately without the library
     * having to be re-fetched, and a later refresh can't undo it.
     */
    /** Every rating known for this account: the library's, then this session's. */
    val likeStatuses: StateFlow<Map<String, LikeStatus>> =
        combine(_library, LikeState.overrides) { library, overrides ->
            val liked = (library as? UiState.Success)?.data?.likedSongs
                ?.associate { it.videoId to LikeStatus.LIKE }
                .orEmpty()
            liked + overrides
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    fun likeStatusOf(videoId: String): LikeStatus =
        likeStatuses.value[videoId] ?: LikeStatus.INDIFFERENT

    /**
     * Sets (or clears) the thumbs rating on [videoId].
     *
     * Written to the screen first and rolled back if YouTube refuses. A rating
     * is a one-tap, low-stakes action taken while a song is playing; waiting
     * on a round trip before the heart fills reads as the tap not having
     * registered, and people tap again.
     */
    fun setLike(videoId: String, status: LikeStatus) {
        if (!requireSignIn()) return
        val previous = likeStatusOf(videoId)
        if (previous == status) return
        LikeState.set(videoId, status)
        viewModelScope.launch {
            YtMusicRepository.rate(videoId, status).fold(
                onSuccess = {
                    // Liked Music is now out of date either way.
                    libraryStale = true
                    if (status != LikeStatus.LIKE) dropFromLikedLists(videoId)
                    // Clearing the heart means forgetting the song, not
                    // demoting it — see [forgetFromLibrary].
                    val unliked = previous == LikeStatus.LIKE &&
                        status == LikeStatus.INDIFFERENT
                    if (unliked) forgetFromLibrary(videoId)
                },
                onFailure = {
                    LikeState.set(videoId, previous)
                },
            )
        }
    }

    /**
     * Takes an un-liked track out of the library as well, and reports whether
     * it did.
     *
     * Liking and saving are two independent flags on YouTube's side, and
     * clearing only the first leaves the song saved — still feeding the
     * Library tab's Artists shelf, still in the library feeds, with nowhere
     * left in this app to reach it and finish the job. Clearing the heart
     * reads as "forget this song", so it clears both.
     *
     * The token is fetched here rather than taken from [songMenu] because the
     * heart in the player never opens a menu, so there is often nothing
     * cached to take. One extra request, on an action nobody performs in bulk.
     * A song that was never saved has no removal token and this is a no-op.
     */
    private suspend fun forgetFromLibrary(videoId: String): Boolean {
        val menu = YtMusicRepository.songMenu(videoId).getOrNull() ?: return false
        val token = menu.removeFromLibraryToken?.takeIf { menu.inLibrary } ?: return false
        if (YtMusicRepository.setLibraryStatus(token).isFailure) return false
        // The menu may be the one on screen; don't leave it offering a
        // removal that has already happened.
        _songMenu.value = _songMenu.value?.copy(inLibrary = false)
        return true
    }

    /**
     * Takes an un-liked track out of the lists that exist *because* it was
     * liked — the Library tab's Liked Music section, and the Liked Music page
     * itself if it happens to be open.
     *
     * Marking the library stale isn't enough on its own: that only acts when
     * the tab is next opened, and un-liking is nearly always done from inside
     * one of these two lists, looking straight at the row. Leaving it there
     * reads as the tap not having worked — the menu says "Like" again while
     * the song sits in Liked Music.
     *
     * Only ever removes. A track liked from somewhere else doesn't get spliced
     * into a list that YouTube orders for itself; the next fetch places it.
     */
    private fun dropFromLikedLists(videoId: String) {
        val library = (_library.value as? UiState.Success)?.data
        if (library != null && library.likedSongs.any { it.videoId == videoId }) {
            _library.value = UiState.Success(
                library.copy(likedSongs = library.likedSongs.filterNot { it.videoId == videoId }),
            )
        }
        _detailStack.value = _detailStack.value.map { page ->
            val songs = (page.songs as? UiState.Success)?.data
            if (page.browseId != YtMusicRepository.LIKED_MUSIC || songs == null) {
                page
            } else {
                page.copy(songs = UiState.Success(songs.filterNot { it.videoId == videoId }))
            }
        }
    }

    /** The heart: liked becomes neutral, anything else becomes liked. */
    fun toggleLike(videoId: String) = setLike(
        videoId,
        if (likeStatusOf(videoId) == LikeStatus.LIKE) LikeStatus.INDIFFERENT else LikeStatus.LIKE,
    )

    /** As [toggleLike], for the thumb-down. */
    fun toggleDislike(videoId: String) = setLike(
        videoId,
        if (likeStatusOf(videoId) == LikeStatus.DISLIKE) {
            LikeStatus.INDIFFERENT
        } else {
            LikeStatus.DISLIKE
        },
    )

    /**
     * Saves the album or playlist [browseId] to the library, or takes it out.
     *
     * Written to the screen first and rolled back if YouTube refuses, for the
     * same reason [setLike] is: it is one tap on a page the user is looking at,
     * and a control that waits on a round trip before it changes reads as a tap
     * that missed.
     *
     * A page with no [DetailPage.library] is one YouTube never offered to save
     * — a local page, an auto-playlist, a generated mix — and the UI has no
     * control on it to have been tapped, so this is a no-op rather than a guess.
     */
    fun toggleLibrary(browseId: String) {
        if (!requireSignIn()) return
        val current = _detailStack.value.firstOrNull { it.browseId == browseId }?.library ?: return
        val target = !current.saved
        setSavedOnPage(browseId, target)
        viewModelScope.launch {
            if (YtMusicRepository.setSaved(current.playlistId, target).isSuccess) {
                // The Library tab's Albums/Playlists shelf is now out of date.
                libraryStale = true
            } else {
                setSavedOnPage(browseId, current.saved)
            }
        }
    }

    /**
     * Restates whether a page is saved. By id rather than by index: the user may
     * have pushed or popped pages while the write was in flight.
     */
    private fun setSavedOnPage(browseId: String, saved: Boolean) {
        _detailStack.value = _detailStack.value.map { page ->
            val library = page.library
            if (page.browseId != browseId || library == null) {
                page
            } else {
                page.copy(library = library.copy(saved = saved))
            }
        }
    }

    /**
     * The open track menu's account state, or null while it is still being
     * fetched. Only one menu can be open at a time, so one slot is enough.
     */
    private val _songMenu = MutableStateFlow<SongMenu?>(null)
    val songMenu: StateFlow<SongMenu?> = _songMenu.asStateFlow()

    private var songMenuJob: Job? = null

    /**
     * Loads the account state behind an opening track menu — the library
     * tokens, and any rating the response happens to state.
     *
     * The rating is only ever taken when it *adds* something: a LIKE or a
     * DISLIKE the library couldn't have told us, such as a disliked track or
     * one liked past the tenth page of Liked Music. An INDIFFERENT is
     * discarded.
     *
     * That asymmetry is not fussiness. This lookup reads a watch queue, and a
     * watch queue routinely renders a liked track with no rating on it at all;
     * believing that silence downgraded songs sitting in Liked Music to
     * "not liked" a beat after their menu opened — the label changing under
     * the user, with no request sent and nothing removed.
     */
    fun loadSongMenu(videoId: String?) {
        songMenuJob?.cancel()
        _songMenu.value = null
        if (videoId == null || !_signedIn.value) return
        songMenuJob = viewModelScope.launch {
            val menu = YtMusicRepository.songMenu(videoId).getOrNull() ?: return@launch
            _songMenu.value = menu
            val stated = menu.likeStatus
            if (stated != null && stated != LikeStatus.INDIFFERENT &&
                videoId !in LikeState.overrides.value
            ) {
                LikeState.set(videoId, stated)
            }
        }
    }

    /** The account's own playlists, for the picker and the library tab. */
    private val _playlists = MutableStateFlow<List<UserPlaylist>>(emptyList())
    val playlists: StateFlow<List<UserPlaylist>> = _playlists.asStateFlow()

    private val _playlistsLoading = MutableStateFlow(false)
    val playlistsLoading: StateFlow<Boolean> = _playlistsLoading.asStateFlow()

    /** Re-fetched rather than cached for the session: playlists are edited here. */
    fun loadPlaylists() {
        if (!_signedIn.value || _playlistsLoading.value) return
        _playlistsLoading.value = true
        viewModelScope.launch {
            YtMusicRepository.userPlaylists().onSuccess { _playlists.value = it }
            _playlistsLoading.value = false
        }
    }

    /**
     * The library feed's Playlists shelf, rewritten by [edit].
     *
     * Every playlist edit has to do this by hand, because the library tab reads
     * `_library` and nothing else — [playlists] is the picker's list, not the
     * tab's — so a rename that only updated that list left the card on screen
     * still bearing the old name.
     *
     * Re-fetching instead is what this replaces, and it did not work: YouTube's
     * `FEmusic_liked_playlists` is eventually consistent, and a fetch fired the
     * moment an edit returns reliably answers with the state from *before* it.
     * So the edit was applied, the feed denied it, and the denial is what
     * reached the screen — the bug this exists to fix. The re-fetch still
     * happens, via [libraryStale], once the tab is next opened and the feed has
     * caught up.
     *
     * A shelf that isn't there yet is created by [edit] returning rows for it
     * (a first playlist has no shelf to add to), and one left empty is dropped —
     * see [LibraryScreen], which draws the create tile with or without a shelf.
     */
    private fun editPlaylistShelf(edit: (List<ShelfItem>) -> List<ShelfItem>) {
        val page = (_library.value as? UiState.Success)?.data ?: return
        val existing = page.shelves.firstOrNull { it.title == YtMusicRepository.PLAYLISTS_SHELF }
        val items = edit(existing?.items.orEmpty())
        if (items == existing?.items) return
        val shelves = when {
            existing == null && items.isEmpty() -> return
            // No shelf yet: this is the account's first playlist, so the feed
            // has never had one to send. Leads the page, as the feed orders it.
            existing == null ->
                listOf(HomeShelf(YtMusicRepository.PLAYLISTS_SHELF, items)) + page.shelves
            // Emptied by deleting the last playlist. Dropped rather than left as
            // a heading over nothing; the create tile is drawn either way.
            items.isEmpty() -> page.shelves.filterNot { it === existing }
            else -> page.shelves.map { if (it === existing) existing.copy(items = items) else it }
        }
        _library.value = UiState.Success(page.copy(shelves = shelves))
    }

    /**
     * Restates a playlist's name everywhere it is currently drawn: its card in
     * the library, the picker's list, and its own open page — header and top
     * bar both, which read [DetailPage.title].
     */
    private fun setPlaylistTitle(playlist: UserPlaylist, title: String) {
        _playlists.value = _playlists.value.map {
            if (it.playlistId == playlist.playlistId) it.copy(title = title) else it
        }
        editPlaylistShelf { items ->
            items.map { if (it.browseId == playlist.browseId) it.copy(title = title) else it }
        }
        _detailStack.value = _detailStack.value.map {
            if (it.browseId == playlist.browseId) it.copy(title = title) else it
        }
    }

    /**
     * Adds [song] to a playlist, from the picker.
     *
     * Not optimistic, unlike a rating: the picker closes on the tap and there is
     * nothing left of it to update, and a playlist that shows a track it turned
     * out not to have taken is worse than one that shows it a moment late.
     *
     * The playlist's own page is the exception, because it can be the thing
     * behind the picker — a row's menu on a playlist offers "Add to playlist" —
     * and a page that doesn't show what was just added to it is the bug this is
     * part of fixing. Still after the answer, not ahead of it.
     */
    fun addToPlaylist(playlist: UserPlaylist, song: Song) {
        if (!requireSignIn()) return
        viewModelScope.launch {
            YtMusicRepository.addToPlaylist(playlist.playlistId, listOf(song.videoId)).fold(
                onSuccess = { added ->
                    libraryStale = true
                    // The playlist's page may be open behind the picker — it is
                    // reachable from a row's own menu on it — so the track goes
                    // into it for the same reason [addSuggestedSong] does.
                    appendToOpenPlaylist(playlist.browseId, song, added[song.videoId])
                },
                onFailure = {},
            )
        }
    }

    /**
     * Creates a playlist, seeded with [song] when the flow started from a
     * track's menu — one request, so it can't half-succeed into an empty
     * playlist the user has to add to again.
     */
    fun createPlaylist(title: String, privacy: PlaylistPrivacy, song: Song? = null) {
        if (!requireSignIn()) return
        val name = title.trim().ifBlank { "New playlist" }
        viewModelScope.launch {
            YtMusicRepository.createPlaylist(
                title = name,
                privacy = privacy,
                videoIds = listOfNotNull(song?.videoId),
            ).fold(
                onSuccess = { playlistId ->
                    // Nothing to look up for a playlist this account has just
                    // made: it is the owner by construction, so its card is
                    // editable the moment it appears rather than one request
                    // after someone holds it.
                    setPlaylistOwned("VL$playlistId", true)
                    libraryStale = true
                    val created = UserPlaylist(
                        playlistId = playlistId,
                        title = name,
                        // Only what this request itself establishes. Both
                        // surfaces that draw it leave a blank one out, so an
                        // unseeded playlist gets a card of just its name rather
                        // than a guess at what the feed will call it.
                        subtitle = if (song != null) "1 song" else "",
                        thumbnailUrl = song?.thumbnailUrl,
                    )
                    // Drawn from what was just sent rather than waited for: the
                    // library feed does not have this playlist yet, and the
                    // fetch that used to run here answered without it — see
                    // [editPlaylistShelf]. Leads the shelf because it is the
                    // newest, which is the order the feed itself comes in.
                    _playlists.value = listOf(created) +
                        _playlists.value.filterNot { it.playlistId == created.playlistId }
                    editPlaylistShelf { items ->
                        listOf(
                            ShelfItem(
                                title = created.title,
                                subtitle = created.subtitle,
                                thumbnailUrl = created.thumbnailUrl,
                                videoId = null,
                                browseId = created.browseId,
                            ),
                        ) + items.filterNot { it.browseId == created.browseId }
                    }
                },
                onFailure = {},
            )
        }
    }

    /**
     * Drops [song] from the playlist page it is being read on, and takes the
     * row out from under the reader rather than waiting for a re-fetch.
     */
    fun removeFromPlaylist(browseId: String, song: Song) {
        val setVideoId = song.setVideoId ?: return
        if (!requireSignIn()) return
        val playlistId = browseId.removePrefix("VL")
        viewModelScope.launch {
            YtMusicRepository.removeFromPlaylist(
                playlistId,
                listOf(setVideoId to song.videoId),
            ).fold(
                onSuccess = {
                    libraryStale = true
                    _detailStack.value = _detailStack.value.map { page ->
                        val songs = (page.songs as? UiState.Success)?.data
                        if (page.browseId != browseId || songs == null) {
                            page
                        } else {
                            page.copy(
                                songs = UiState.Success(
                                    songs.filterNot { it.setVideoId == setVideoId },
                                ),
                            )
                        }
                    }
                },
                onFailure = {},
            )
        }
    }

    /**
     * Adds one of [DetailPage.suggestedSongs] to the playlist it was suggested
     * for: out of that section, and into the track list above it.
     *
     * Both halves, because either alone is a worse answer than doing nothing.
     * Only removing it — which is what this used to do — reads as the track
     * having been discarded rather than added: it leaves the Suggested list and
     * turns up nowhere, and the playlist it was added to looks unchanged until
     * the page is closed and reopened. Only adding it would leave YouTube still
     * suggesting a track that is now in the playlist.
     *
     * The row goes in complete, per-entry id included, because
     * [YtMusicRepository.addToPlaylist] reports the one it was just filed
     * under — so "Remove from this playlist" works on it immediately rather
     * than after a re-fetch. A response that named no id still adds the row;
     * it just can't offer to take it back out yet.
     */
    fun addSuggestedSong(browseId: String, song: Song) {
        if (!requireSignIn()) return
        val playlistId = browseId.removePrefix("VL")
        viewModelScope.launch {
            YtMusicRepository.addToPlaylist(playlistId, listOf(song.videoId)).fold(
                onSuccess = { added ->
                    libraryStale = true
                    _detailStack.value = _detailStack.value.map { page ->
                        if (page.browseId != browseId) {
                            page
                        } else {
                            page.copy(
                                suggestedSongs = page.suggestedSongs
                                    .filterNot { it.videoId == song.videoId },
                            )
                        }
                    }
                    appendToOpenPlaylist(browseId, song, added[song.videoId])
                },
                onFailure = {},
            )
        }
    }

    /**
     * Puts [song] at the end of the playlist page at [browseId], if that page
     * is open — where YouTube itself puts it, so the order survives the next
     * fetch.
     *
     * An empty playlist counts as open: it renders as [NO_TRACKS], and the
     * first track added to one has to replace that message rather than be
     * dropped for want of a list to join. Only that message, though — any other
     * error is a page that failed to load, whose real contents are unknown, and
     * answering it with a one-track listing would be a playlist invented out of
     * a network failure. A page still loading is left alone too: the fetch in
     * flight is newer than this and will land with the addition already in it.
     */
    private fun appendToOpenPlaylist(browseId: String, song: Song, setVideoId: String?) {
        val added = song.copy(setVideoId = setVideoId)
        _detailStack.value = _detailStack.value.map { page ->
            if (page.browseId != browseId) return@map page
            val songs = when (val state = page.songs) {
                is UiState.Success -> state.data
                is UiState.Error -> if (state.message == NO_TRACKS) emptyList() else return@map page
                UiState.Loading -> return@map page
            }
            // Already there — a track added twice is two real entries on
            // YouTube's side, but a duplicate row from a double tap is not
            // something the user asked for.
            if (songs.any { it.videoId == song.videoId }) return@map page
            page.copy(
                songs = UiState.Success(
                    songs + added.copy(
                        thumbnailUrl = added.thumbnailUrl ?: page.thumbnailUrl,
                    ),
                ),
            )
        }
    }

    /**
     * Renames a playlist, and says so everywhere it is named — see
     * [setPlaylistTitle]. Renaming is nearly always done from the playlist's
     * own page or its card, so there is always something on screen still
     * showing the old name.
     */
    fun renamePlaylist(playlist: UserPlaylist, title: String) {
        if (!requireSignIn()) return
        val name = title.trim()
        if (name.isBlank() || name == playlist.title) return
        viewModelScope.launch {
            YtMusicRepository.renamePlaylist(playlist.playlistId, name).fold(
                onSuccess = {
                    setPlaylistTitle(playlist, name)
                    libraryStale = true
                },
                onFailure = {},
            )
        }
    }

    fun deletePlaylist(playlist: UserPlaylist) {
        if (!requireSignIn()) return
        viewModelScope.launch {
            YtMusicRepository.deletePlaylist(playlist.playlistId).fold(
                onSuccess = {
                    _playlists.value = _playlists.value
                        .filterNot { it.playlistId == playlist.playlistId }
                    // The card in the library tab, which is the surface the
                    // deletion was almost certainly ordered from — and which the
                    // re-fetch that used to stand in for this left in place; see
                    // [editPlaylistShelf].
                    editPlaylistShelf { items ->
                        items.filterNot { it.browseId == playlist.browseId }
                    }
                    // Its page may be the one open; a deleted playlist has
                    // nothing left to show.
                    _detailStack.value = _detailStack.value
                        .filterNot { it.browseId == playlist.browseId }
                    libraryStale = true
                },
                onFailure = {},
            )
        }
    }

    /**
     * Whether [browseId] is a playlist this account can be asked to edit.
     *
     * Only ever yes for a playlist [playlistOwned] has confirmed the account
     * made. "In this account's library" is not the same thing and cannot stand
     * in for it: `FEmusic_liked_playlists` lists a playlist saved from someone
     * else in exactly the shape it lists one this account created, so a lookup
     * in [playlists] alone called a stranger's playlist editable and the menus
     * offered Rename and Delete on it — neither of which YouTube would have
     * honoured.
     *
     * Strict rather than permissive-until-proven, so that every surface gives
     * the same answer for the same playlist. The permissive version was right on
     * a playlist's own page — where the page load supplies the answer — and
     * wrong on a card until that page had been opened once, which is a menu that
     * changes its mind about what a playlist is depending on where it is held.
     */
    fun editablePlaylist(browseId: String?): UserPlaylist? {
        if (browseId == null || _playlistOwned.value[browseId] != true) return null
        return _playlists.value.firstOrNull { it.browseId == browseId }
    }

    /**
     * Which playlists in this account's library the account actually made, by
     * browse id — see
     * [com.music.yzmusic.data.innertube.InnertubeParser.parsePlaylistOwned].
     * An id absent from the map is one nothing has asked about yet, which is not
     * the same as a no.
     *
     * Observable, because the answer routinely arrives after whatever wanted it
     * is already on screen: a card's menu opens with nothing fetched, and the
     * rows that depend on this appear as [resolvePlaylistOwnership] answers.
     */
    private val _playlistOwned = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val playlistOwned: StateFlow<Map<String, Boolean>> = _playlistOwned.asStateFlow()

    /**
     * Finds out who made the playlist at [browseId], if it isn't already known.
     *
     * Only the playlist's own page states this, so a surface that has no page —
     * a card in the library, a search result — has to ask for one. Which is why
     * this is on demand rather than swept up front: the alternative is a request
     * per playlist every time the library loads, for a question most of them
     * will never be asked.
     *
     * Silent about anything that isn't a playlist in this account's library.
     * Nothing else can be renamed or deleted whatever the answer, so asking
     * would be a request spent to rule out what was never on offer.
     */
    fun resolvePlaylistOwnership(browseId: String?) {
        if (!_signedIn.value || browseId == null) return
        if (browseId in _playlistOwned.value || browseId in ownershipInFlight) return
        if (_playlists.value.none { it.browseId == browseId }) return
        ownershipInFlight += browseId
        viewModelScope.launch {
            YtMusicRepository.playlistOwned(browseId).onSuccess { owned ->
                if (owned != null) setPlaylistOwned(browseId, owned)
            }
            // Released either way. A failed lookup that stayed marked would
            // never be retried, leaving Rename off the user's own playlist for
            // the rest of the session over one dropped request.
            ownershipInFlight -= browseId
        }
    }

    /** Guards against a second lookup while the first is still out. */
    private val ownershipInFlight = mutableSetOf<String>()

    private fun setPlaylistOwned(browseId: String, owned: Boolean) {
        _playlistOwned.value = _playlistOwned.value + (browseId to owned)
    }


    /**
     * Guards every account write. All of them are signed-in-only, and the UI
     * hides them for guests — this is the backstop for a session that expired
     * between the menu opening and the tap.
     */
    private fun requireSignIn(): Boolean = _signedIn.value

    /**
     * Whether the library needs re-fetching. Set by every write above and
     * acted on when the tab is next opened, for the same reason [homeStale]
     * exists: rearranging a page under whoever is reading it is worse than
     * showing it a moment out of date.
     */
    private var libraryStale = false

    /** Call when the library tab becomes visible. */
    fun onLibraryShown() {
        loadPlaylists()
        if (!libraryStale) return
        libraryStale = false
        if (_library.value is UiState.Success) refresh(Feed.LIBRARY)
    }

    init {
        loadHome()
        loadExplore()
        if (_signedIn.value) {
            loadLibrary()
            loadAccount()
            loadPlaylists()
        }
        viewModelScope.launch {
            // drop(1): the current value is just the count so far, not a play.
            PlaybackTracker.registeredPlays.drop(1).collect { homeStale = true }
        }
        viewModelScope.launch {
            // A leftover APK only means "Install Now" for the session that
            // downloaded it — see AppUpdateChecker.clearCache.
            AppUpdateChecker.clearCache(getApplication())
            AppUpdateChecker.check()
        }
    }

    /**
     * Whether a play has been registered since the home feed was last fetched.
     *
     * The feed leads with listening history, so it's out of date the moment a
     * track starts — but re-fetching there would rearrange the page under
     * whoever is reading it, and the tab is usually in the background anyway.
     * It's re-fetched when the tab is next opened instead.
     */
    private var homeStale = false

    /** Call when the home tab becomes visible. */
    fun onHomeShown() {
        if (!homeStale) return
        homeStale = false
        // A first load already in flight will pick the new play up by itself.
        if (_home.value is UiState.Success) refresh(Feed.HOME)
    }

    private fun loadAccount() {
        viewModelScope.launch {
            _account.value = YtMusicRepository.account().getOrNull()
        }
    }

    /**
     * A feed that can be pulled down to refresh. Tracked per feed rather than
     * as one flag: a pull on Library while Home is still refreshing in the
     * background shouldn't leave the wrong tab showing a loader.
     */
    enum class Feed { HOME, EXPLORE, LIBRARY }

    private val _refreshing = MutableStateFlow(emptySet<Feed>())
    val refreshing: StateFlow<Set<Feed>> = _refreshing.asStateFlow()

    /**
     * Re-fetches [feed] in place. Unlike the `load*` entry points this leaves
     * the current content on screen rather than dropping back to the loading
     * state — a refresh that swapped the page for a spinner would be a worse
     * experience than the stale content it replaces.
     */
    fun refresh(feed: Feed) {
        if (feed in _refreshing.value) return
        if (feed == Feed.LIBRARY && !_signedIn.value) return
        _refreshing.value = _refreshing.value + feed
        viewModelScope.launch {
            when (feed) {
                Feed.HOME -> fetchHome()
                Feed.EXPLORE -> fetchExplore()
                Feed.LIBRARY -> fetchLibrary()
            }
            _refreshing.value = _refreshing.value - feed
        }
    }

    fun loadExplore() {
        _explore.value = UiState.Loading
        viewModelScope.launch { fetchExplore() }
    }

    private suspend fun fetchExplore() {
        _explore.value = YtMusicRepository.explore().fold(
            onSuccess = { shelves ->
                if (shelves.isEmpty()) UiState.Error("Nothing to explore right now")
                else UiState.Success(shelves)
            },
            onFailure = { UiState.Error(it.friendly()) },
        )
    }

    /** Tapping a tab should leave any pushed page behind. */
    fun clearDetail() {
        if (_detailStack.value.isNotEmpty()) _detailStack.value = emptyList()
    }

    fun loadHome() {
        _home.value = UiState.Loading
        viewModelScope.launch { fetchHome() }
    }

    private suspend fun fetchHome() {
        loadMoreJob?.cancel()
        _homeLoadingMore.value = false
        lastLoadMoreErrorTime = 0L
        consecutiveEmptyPages = 0
        homeContinuation = null
        synchronized(homeSeenTitles) {
            homeSeenTitles.clear()
        }
        _home.value = YtMusicRepository.home().fold(
            onSuccess = { feed ->
                homeContinuation = feed.continuation
                val shelves = feed.shelves.filter { shelf ->
                    val titleKey = shelf.title.trim().lowercase(Locale.ROOT)
                    if (titleKey.isEmpty()) true
                    else synchronized(homeSeenTitles) { homeSeenTitles.add(titleKey) }
                }
                if (shelves.isEmpty()) UiState.Error("No results from YouTube Music")
                else UiState.Success(shelves)
            },
            onFailure = { UiState.Error(it.friendly()) },
        )
    }

    /**
     * Called as the Home list nears its end. A no-op while a page is already
     * in flight, once the feed is exhausted, or before the first page has
     * loaded — [homeContinuation] covers all three by construction.
     */
    fun loadMoreHome() {
        val token = homeContinuation ?: return
        if (_homeLoadingMore.value) return
        if (System.currentTimeMillis() - lastLoadMoreErrorTime < 2500L) return
        _homeLoadingMore.value = true
        loadMoreJob = viewModelScope.launch {
            try {
                YtMusicRepository.moreHome(token).fold(
                    onSuccess = { feed ->
                        lastLoadMoreErrorTime = 0L
                        val nextToken = feed.continuation?.takeIf { it != token }
                        val added = feed.shelves.filter { shelf ->
                            val titleKey = shelf.title.trim().lowercase(Locale.ROOT)
                            if (titleKey.isEmpty()) true
                            else synchronized(homeSeenTitles) { homeSeenTitles.add(titleKey) }
                        }
                        if (added.isEmpty()) {
                            consecutiveEmptyPages++
                            if (consecutiveEmptyPages >= 3 || nextToken == null) {
                                homeContinuation = null
                            } else {
                                homeContinuation = nextToken
                            }
                        } else {
                            consecutiveEmptyPages = 0
                            homeContinuation = nextToken
                            val existing = (_home.value as? UiState.Success)?.data ?: emptyList()
                            _home.value = UiState.Success(existing + added)
                        }
                    },
                    onFailure = { error ->
                        lastLoadMoreErrorTime = System.currentTimeMillis()
                        Log.w("YZ Music", "Failed to load more home: ${error.message}")
                    }
                )
            } finally {
                _homeLoadingMore.value = false
            }
        }
    }

    fun loadLibrary() {
        if (!_signedIn.value) return
        _library.value = UiState.Loading
        viewModelScope.launch { fetchLibrary() }
    }

    private suspend fun fetchLibrary() {
        _library.value = YtMusicRepository.library().fold(
            onSuccess = { page ->
                if (page.isEmpty) UiState.Error("Nothing in your library yet")
                else UiState.Success(page)
            },
            onFailure = { UiState.Error(it.friendly()) },
        )
    }

    /**
     * The account's listening history.
     *
     * Loaded on each visit rather than cached: it is a page whose whole subject
     * is what happened most recently, and one that opened showing the state it
     * was in last time would be answering a different question. Guests get the
     * signed-out message straight away, since there is no account to have a
     * history on.
     */
    fun loadHistory() {
        if (!_signedIn.value) {
            _history.value = UiState.Error("Sign in to see what you've been listening to")
            return
        }
        _history.value = UiState.Loading
        viewModelScope.launch {
            _history.value = YtMusicRepository.history().fold(
                onSuccess = { songs ->
                    if (songs.isEmpty()) UiState.Error("Nothing played yet")
                    else UiState.Success(songs)
                },
                onFailure = { UiState.Error(it.friendly()) },
            )
        }
    }

    /**
     * Recent searches, kept on the device and read by the search screen. The
     * list is the user's own, so it has to be worth having without a
     * connection — which is why an entry carries the thing it was found as and
     * not only the words that found it. See [SearchHistoryEntity].
     */
    val searchHistory: StateFlow<List<SearchHistoryEntity>> = SearchHistory.recent

    fun onQueryChange(value: String) = search.onQueryChange(value)

    /**
     * Records what the user acted on in a result row, with the metadata that
     * row carries: the id, the kind, the name, the artist and the cover. See
     * [SearchController.recordResult] for why that is not the same as
     * recording the query that found it.
     */
    fun recordResult(result: SearchResult) = search.recordResult(result)

    fun recordTrack(song: Song) = search.recordTrack(song)

    fun recordBrowseItem(item: BrowseItem) = search.recordBrowseItem(item)

    /**
     * The search button — the keyboard's search action, or the magnifier in
     * the field. The only thing that runs a search for text the user typed:
     * keystrokes themselves ask for completions and nothing more.
     */
    fun submitSearch() = search.submitSearch()

    /**
     * Runs a term the user picked out of a list rather than typed — one of the
     * completions, or a recent that was a term — and floats it to the top of
     * the history. Picking is as deliberate as submitting, so it searches on
     * the spot.
     */
    fun searchFor(term: String) = search.searchFor(term)

    /**
     * Opens a recent as the thing it was recorded as being.
     *
     * A track plays from what is stored and a page opens from what is stored,
     * so neither needs a search — or a connection — to have been made first.
     * Only a typed term is searched for, because a typed term is all it is.
     */
    fun openRecent(entity: SearchHistoryEntity): RecentSearchAction? = search.openRecent(entity)

    fun removeRecent(identity: String) = search.removeRecent(identity)

    fun clearSearchHistory() = search.clearRecent()

    fun onFilterChange(value: SearchFilter) = search.onFilterChange(value)

    /**
     * Runs the search that failed again, exactly as it was. The field is not
     * touched: the query is still what the user wants, and a retry that also
     * retyped it would be a different search.
     */
    fun retrySearch() = search.retrySearch()


    /**
     * Continues the visible search only when the list reaches its end. This is
     * deliberately separate from the first-page request: waiting for every
     * continuation was the reason a search sat on a spinner for seconds.
     */
    fun loadMoreSearchResults() = search.loadMore()

    fun loadMoreSearch() = loadMoreSearchResults()


    /**
     * The enabled non-YouTube sources, asked at the same time and returned
     * split at YouTube's own place in the order.
     *
     * The split is what makes the Sources screen's ordering visible where it
     * matters most. A library server ranked above YouTube puts its own copies
     * at the top of the results — which is the whole point of ranking it there —
     * and one ranked below appears under them instead.
     *
     * Only the Songs filter fans out: albums, artists and playlists are
     * browse-shaped, and [MusicSource] deliberately answers for tracks only.
     */
    private suspend fun sourceResults(
        query: String,
        filter: SearchFilter,
    ): Pair<List<SearchResult>, List<SearchResult>> = coroutineScope {
        if (filter != SearchFilter.SONGS) return@coroutineScope emptyList<SearchResult>() to emptyList()
        val active = SourceRegistry.active()
        val youtubeRank = active.indexOfFirst { it.kind == SourceKind.YOUTUBE }
            .let { if (it < 0) active.size else it }

        val answers = active
            .filter { it.kind != SourceKind.YOUTUBE }
            .map { source ->
                source to async {
                    // Per-source, so one slow or unreachable server delays the
                    // results by at most this much rather than for as long as
                    // its socket takes to give up.
                    runCatching {
                        withTimeout(SOURCE_SEARCH_TIMEOUT_MS) { source.search(query, SOURCE_SEARCH_LIMIT) }
                    }.getOrDefault(emptyList())
                }
            }

        val above = mutableListOf<SearchResult>()
        val below = mutableListOf<SearchResult>()
        answers.forEach { (source, job) ->
            val rows = job.await().map { SearchResult.Track(it) }
            val rank = active.indexOfFirst { it.configId == source.configId }
            if (rank in 0 until youtubeRank) above += rows else below += rows
        }
        above to below
    }

    /**
     * Warms the stream URL for the top song result the instant results land,
     * not when it's tapped. [AudioCache] gives a head start to whatever's
     * already queued; a fresh search has nothing queued yet, and the top
     * result is overwhelmingly what gets tapped — see [play][MainActivity.play].
     * [resolveAudio][YtMusicRepository.resolveAudio] first, same as the tap
     * path itself, so a video-tagged result warms the catalogue audio's id
     * rather than one nothing will ever ask for.
     */
    private fun prefetchTopResult(rows: List<SearchResult>) {
        val song = rows.firstNotNullOfOrNull {
            when (it) {
                is SearchResult.TopTrack -> it.song
                is SearchResult.Track -> it.song
                is SearchResult.Browse -> null
            }
        } ?: return
        viewModelScope.launch {
            runCatching {
                val audio = YtMusicRepository.resolveAudio(song)
                // A source-backed row resolves through its own source already
                // and never takes the YouTube path — warming either half of
                // this for one would be work nothing asks for.
                if (SourceRegistry.parseTrackKey(audio.videoId) != null) return@runCatching
                // JioSaavn first, on the same reasoning as the queue's
                // read-ahead: it is the copy that will actually be played if it
                // has the track, so warming YouTube's URL instead warms the one
                // that loses. Pinned through [StreamChoice] so playback opens
                // this very stream rather than racing for it again — see
                // [SourceResolver.prefetchSubstitute], which requires it.
                val warmed = SourceResolver.prefetchSubstitute(
                    TrackMatcher.Target(
                        title = audio.title,
                        artist = audio.artist,
                        durationSec = TrackMatcher.secondsOf(audio.durationText),
                    ),
                )
                if (warmed != null) {
                    StreamChoice.remember(audio.videoId, warmed, substituted = true)
                    return@runCatching
                }
                // Disabled, or hasn't got it: the tap path falls back to
                // YouTube, so that is what is worth having ready.
                StreamResolver.resolve(audio.videoId)
            }
        }
    }

    private companion object {
        const val TAG = "MainViewModel"

        /**
         * How long any one source gets to answer a search.
         *
         * Short on purpose: these run alongside the YouTube search, and their
         * only job is to be *there* when it lands. A home server reached over
         * a VPN that takes eight seconds has effectively not answered, and
         * holding the whole result list for it would make search feel worse
         * for the sake of results the user can still get by searching again.
         */
        const val SOURCE_SEARCH_TIMEOUT_MS = 4000L

        /** Enough to be worth scrolling, short enough not to bury YouTube's own rows. */
        const val SOURCE_SEARCH_LIMIT = 12

        /**
         * What a page with an empty listing says.
         *
         * Named because it is a state one can be got *out* of, not just a
         * message: an own playlist with nothing in it lands here, and adding the
         * first track to it has to be able to tell "this page is empty" apart
         * from "this page failed to load" — see [appendToOpenPlaylist].
         */
        private const val NO_TRACKS = "No tracks here"

        /**
         * What a downloaded playlist's page says once the files under it are
         * gone.
         *
         * A record here outlives the folder it names — the user is expected to
         * manage Downloads with a file manager — so this is a state its page has
         * to be able to reach, not an error. Named because three places say it:
         * the page, its refresh, and the long-press menu that queues it without
         * opening it.
         */
        private const val DOWNLOADS_GONE = "Nothing from this playlist is on this device any more"
    }

    fun openDetail(
        browseId: String,
        title: String,
        subtitle: String = "",
        thumbnailUrl: String? = null,
        type: BrowseType = BrowseType.OTHER,
        params: String? = null,
    ) {
        val resolved = browseTypeOf(browseId, type)
        _detailStack.value += DetailPage(
            browseId = browseId,
            title = title,
            subtitle = subtitle,
            thumbnailUrl = thumbnailUrl,
            songs = UiState.Loading,
            type = resolved,
            params = params,
        )
        viewModelScope.launch {
            var sections = emptyList<HomeShelf>()
            // Callers that open an artist from a track — the player, the
            // long-press menu — only have that track's cover art and its full
            // credit ("A, B & C") to hand, so the page swaps in the artist's
            // own picture and name once they arrive.
            var artwork: String? = null
            var name: String? = null
            /**
             * The credit line, when the page had to supply its own.
             *
             * Only a link tapped outside the app arrives with neither — see
             * [com.music.yzmusic.playback.MusicLink]. Every other caller was
             * looking at a card that already said this.
             */
            var credit: String? = null
            /** Set when the track list carries on past its first response. */
            var more: String? = null
            /** Tracks YouTube offers to round the playlist out — see [DetailPage.suggestedSongs]. */
            var suggested: List<Song> = emptyList()
            /** Whether this release is already saved — see [DetailPage.library]. */
            var library: LibraryState? = null
            /** YouTube's own "About" blurb — see [DetailPage.description]. */
            var description: String? = null
            /** Artist header stats — see [DetailPage.subscriberCountText]. */
            var subscriberCountText: String? = null
            var monthlyListenerCount: String? = null
            val state = when {
                Downloads.recordIdOf(browseId) != null -> {
                    val songs = downloadedPlaylist(browseId)
                    if (songs.isEmpty()) UiState.Error(DOWNLOADS_GONE) else UiState.Success(songs)
                }
                browseId == "local:downloads" -> {
                    val context = getApplication<Application>()
                    val songs = LocalMediaRepository.getDownloadedSongs(context)
                    if (songs.isEmpty()) UiState.Error("No downloaded tracks in Music/YZ Music")
                    else UiState.Success(songs)
                }
                browseId == "local:all" -> {
                    val context = getApplication<Application>()
                    if (!LocalMediaRepository.hasStoragePermission(context)) {
                        UiState.Error("Storage permission required to view local audio files")
                    } else {
                        val songs = LocalMediaRepository.getLocalMusic(context)
                        if (songs.isEmpty()) UiState.Error("No audio files found on device")
                        else UiState.Success(songs)
                    }
                }
                resolved == BrowseType.ARTIST -> {
                    YtMusicRepository.artistPage(browseId).fold(
                        onSuccess = { page ->
                            sections = page.sections
                            artwork = page.thumbnailUrl
                            name = page.name
                            description = page.description
                            subscriberCountText = page.subscriberCountText
                            monthlyListenerCount = page.monthlyListenerCount
                            if (page.songs.isEmpty()) {
                                UiState.Error(NO_TRACKS)
                            } else {
                                UiState.Success(page.songs.withArtwork(thumbnailUrl))
                            }
                        },
                        onFailure = { UiState.Error(it.friendly()) },
                    )
                }
                else -> {
                    YtMusicRepository.browseSongs(browseId, params).fold(
                        onSuccess = { page ->
                            // Free here — the page that returned these rows is
                            // the one thing that states who made the playlist,
                            // so its own menu never has to go and ask. Recorded
                            // even when the listing came back empty.
                            page.owned?.let { setPlaylistOwned(browseId, it) }
                            // Only for the caller that had nothing: a card's own
                            // title is what the user just tapped, and must not
                            // be swapped for the header's wording underneath them.
                            page.header?.let { header ->
                                if (title.isBlank()) name = header.title
                                if (subtitle.isBlank()) credit = header.subtitle
                                if (thumbnailUrl == null) artwork = header.thumbnailUrl
                            }
                            description = page.description
                            sections = page.sections
                            if (page.songs.isEmpty() && page.sections.isEmpty()) {
                                val emptyMsg = if (resolved == BrowseType.CATEGORY || resolved == BrowseType.CHARTS) {
                                    "No content available in this section"
                                } else {
                                    NO_TRACKS
                                }
                                UiState.Error(emptyMsg)
                            } else {
                                more = page.continuation
                                suggested = page.suggested.withArtwork(thumbnailUrl)
                                library = page.library
                                UiState.Success(page.songs.withArtwork(thumbnailUrl))
                            }
                        },
                        onFailure = { UiState.Error(it.friendly()) },
                    )
                }
            }
            // Update by id — the user may have pushed another page meanwhile.
            _detailStack.value = _detailStack.value.map {
                if (it.browseId == browseId && it.songs is UiState.Loading) {
                    it.copy(
                        songs = state,
                        sections = sections,
                        thumbnailUrl = artwork ?: it.thumbnailUrl,
                        title = name ?: it.title,
                        subtitle = credit ?: it.subtitle,
                        suggestedSongs = suggested,
                        library = library,
                        description = description,
                        subscriberCountText = subscriberCountText,
                        monthlyListenerCount = monthlyListenerCount,
                    )
                } else {
                    it
                }
            }
            // Only once the first page is on screen: [fillIn] appends to it,
            // and has nothing to append to before this.
            more?.let { fillIn(browseId, it, thumbnailUrl) }
        }
    }

    fun reloadLocalDetail(browseId: String) {
        viewModelScope.launch {
            val context = getApplication<Application>()
            val state: UiState<List<Song>> = when {
                Downloads.recordIdOf(browseId) != null -> {
                    val songs = downloadedPlaylist(browseId)
                    if (songs.isEmpty()) UiState.Error(DOWNLOADS_GONE) else UiState.Success(songs)
                }
                browseId == "local:downloads" -> {
                    val songs = LocalMediaRepository.getDownloadedSongs(context)
                    if (songs.isEmpty()) UiState.Error("No downloaded tracks in Music/YZ Music")
                    else UiState.Success(songs)
                }
                browseId == "local:all" -> {
                    if (!LocalMediaRepository.hasStoragePermission(context)) {
                        UiState.Error("Storage permission required to view local audio files")
                    } else {
                        val songs = LocalMediaRepository.getLocalMusic(context)
                        if (songs.isEmpty()) UiState.Error("No audio files found on device")
                        else UiState.Success(songs)
                    }
                }
                else -> return@launch
            }
            _detailStack.value = _detailStack.value.map {
                if (it.browseId == browseId) {
                    it.copy(songs = state)
                } else it
            }
        }
    }

    /**
     * The tracks of the downloaded playlist [browseId] names that are still on
     * disk, in the order the playlist had.
     *
     * Reads the whole Downloads folder rather than the record's own uris,
     * because that read is what fills in an album tag the record never carried
     * and what collapses a music video's two ids down to the one file it saved —
     * see [Downloads.collectionsAmong], of which this is a single-playlist view.
     *
     * Empty is the honest answer for a record whose files have all been deleted
     * from under it, and callers turn that into [DOWNLOADS_GONE] rather than
     * into a blank page.
     */
    private suspend fun downloadedPlaylist(browseId: String): List<Song> {
        val id = Downloads.recordIdOf(browseId) ?: return emptyList()
        val folder = LocalMediaRepository.getDownloadedSongs(getApplication())
        return Downloads.collectionsAmong(folder).firstOrNull { it.id == id }?.songs.orEmpty()
    }

    /**
     * Follows a detail page's continuations in the background, appending each
     * page to what is already being read.
     *
     * A playlist of a few hundred tracks is several round trips, and taking
     * them before showing anything meant a spinner for all of them. Growing
     * the list underneath the reader is also what makes it safe to keep
     * following continuations [YtMusicRepository.MAX_PAGES] deep — nobody is
     * waiting on the last one.
     *
     * Stops the moment the page leaves the stack: there is no one to append
     * for.
     */
    private fun fillIn(browseId: String, token: String, artworkFallback: String?) {
        viewModelScope.launch {
            var next: String? = token
            var page = 1
            while (next != null && page++ < YtMusicRepository.MAX_PAGES) {
                val fetched = YtMusicRepository.moreSongs(next).getOrNull() ?: return@launch
                val stack = _detailStack.value
                val index = stack.indexOfFirst { it.browseId == browseId }
                if (index < 0) return@launch
                val current = stack[index]
                val existing = (current.songs as? UiState.Success)?.data.orEmpty()
                val known = existing.mapTo(HashSet()) { it.videoId }
                val added = fetched.songs
                    .filter { known.add(it.videoId) }
                    .withArtwork(artworkFallback)
                // Suggestions can arrive on a later page than the real
                // tracks, once the playlist's own continuation runs dry —
                // see parsePlaylistShelf — so they're tracked separately
                // rather than folded into [known].
                val knownSuggested = current.suggestedSongs.mapTo(HashSet()) { it.videoId }
                val addedSuggested = fetched.suggested
                    .filter { it.videoId !in known && knownSuggested.add(it.videoId) }
                    .withArtwork(artworkFallback)
                val addedSections = fetched.sections
                // A page with nothing new on it means the feed has looped back
                // rather than run dry with a token still attached.
                if (added.isEmpty() && addedSuggested.isEmpty() && addedSections.isEmpty()) return@launch
                _detailStack.value = stack.toMutableList().also {
                    it[index] = current.copy(
                        songs = UiState.Success(existing + added),
                        sections = if (addedSections.isNotEmpty()) current.sections + addedSections else current.sections,
                        suggestedSongs = current.suggestedSongs + addedSuggested,
                    )
                }
                next = fetched.continuation
            }
        }
    }

    /**
     * An album's track listing doesn't repeat the cover on every row — the
     * page carries it once — so rows arrive with no artwork and stay blank
     * through to the queue and the notification. Fall back to the page's.
     */
    private fun List<Song>.withArtwork(fallback: String?): List<Song> {
        if (fallback == null) return this
        return map { if (it.thumbnailUrl == null) it.copy(thumbnailUrl = fallback) else it }
    }

    /**
     * Home and Explore cards don't say what they point at, and an artist
     * fetched as an album only yields the five songs on its landing page.
     * YouTube's browse ids are prefixed by kind, so use that.
     *
     * Public because the long-press menus ask the same question of a card
     * before offering to queue what is behind it — an artist is not a running
     * order, so it gets no queue actions.
     */
    fun browseTypeOf(browseId: String, fallback: BrowseType = BrowseType.OTHER): BrowseType = when {
        // Not one of YouTube's, and the only one of these that says outright what
        // it is rather than being read off a prefix convention.
        browseId.startsWith(Downloads.PLAYLIST_PREFIX) -> BrowseType.PLAYLIST
        browseId.startsWith("UC") -> BrowseType.ARTIST
        browseId.startsWith("MPREb") -> BrowseType.ALBUM
        browseId.startsWith("VL") || browseId.startsWith("PL") -> BrowseType.PLAYLIST
        browseId == "FEmusic_charts" -> BrowseType.CHARTS
        browseId == "FEmusic_moods_and_genres" || browseId == "FEmusic_moods_and_genres_category" -> BrowseType.CATEGORY
        browseId == "FEmusic_new_releases" || browseId == "FEmusic_new_releases_albums" -> BrowseType.NEW_RELEASES_GRID
        else -> fallback
    }

    /**
     * Every track behind an album or playlist, handed to [onResult] once it is
     * all in.
     *
     * What a long-press on a card is acting on. A card has nothing but a browse
     * id — its page was never opened, so there is no track list anywhere to
     * read — and "add this album to the queue" means the whole album, so this
     * follows continuations to the end rather than taking the first page.
     *
     * Runs in [viewModelScope], not the caller's: the sheet the tap came from
     * closes immediately, and a three-hundred-track playlist must not be
     * abandoned halfway because of it.
     */
    fun collectSongs(
        browseId: String,
        artworkFallback: String? = null,
        onResult: (Result<List<Song>>) -> Unit,
    ) {
        viewModelScope.launch {
            val context = getApplication<Application>()
            val result = when {
                Downloads.recordIdOf(browseId) != null -> runCatching {
                    downloadedPlaylist(browseId).ifEmpty { error(DOWNLOADS_GONE) }
                }
                browseId == "local:downloads" -> runCatching {
                    LocalMediaRepository.getDownloadedSongs(context)
                        .ifEmpty { error("No downloaded tracks in Music/YZ Music") }
                }
                browseId == "local:all" -> runCatching {
                    if (!LocalMediaRepository.hasStoragePermission(context)) {
                        error("Storage permission required to read local audio files")
                    }
                    LocalMediaRepository.getLocalMusic(context)
                        .ifEmpty { error("No audio files found on device") }
                }
                else -> YtMusicRepository.allSongs(browseId)
            }
            onResult(result.map { it.withArtwork(artworkFallback) })
        }
    }

    /** Pops one page; returns false when there was nothing to pop. */
    fun closeDetail(): Boolean {
        val stack = _detailStack.value
        if (stack.isEmpty()) return false
        _detailStack.value = stack.dropLast(1)
        return true
    }

    fun onSignedIn(cookie: String) {
        authStore.cookie = cookie
        Innertube.cookie = cookie
        // Every "this track can't be played" the resolver recorded while there
        // was no session was recorded under different rules. An age-gated track
        // is the whole point of signing in, and it is the one verdict a session
        // overturns — so a listener who signs in to play a track must not spend
        // the next ten minutes being told it still cannot be played.
        StreamResolver.onSessionChanged()
        _signedIn.value = true
        // Before the reloads below, and the reason they are inside a coroutine
        // now: which of the cookie's accounts was just signed into decides what
        // "the library" and "the history" even refer to. Loading them first and
        // scoping second shows the listener the wrong account's music and then
        // silently disagrees with itself.
        viewModelScope.launch {
            Innertube.ensureSessionScope()
            loadHome()
            loadLibrary()
            loadAccount()
            loadPlaylists()
        }
    }

    fun signOut() {
        authStore.signOut()
        Innertube.cookie = null
        // The mirror image: verdicts reached with a session in hand say nothing
        // about what an anonymous walk will be told, and the clients stood down
        // for refusing the session deserve a fresh hearing without it.
        StreamResolver.onSessionChanged()
        _signedIn.value = false
        _account.value = null
        _library.value = UiState.Loading
        // Ratings and playlists belong to the account that just left; keeping
        // them would show the next signed-in user someone else's hearts.
        LikeState.clear()
        _playlists.value = emptyList()
        _playlistOwned.value = emptyMap()
        ownershipInFlight.clear()
        _songMenu.value = null
        loadHome()
    }

    private fun Throwable.friendly(): String = when {
        message?.contains("resolve host", true) == true ||
            message?.contains("Unable to resolve", true) == true -> "No internet connection"
        message?.contains("401") == true || message?.contains("403") == true ->
            "YouTube Music rejected the request — try signing in again"
        else -> message ?: "Something went wrong"
    }

    private fun text(id: Int): String = getApplication<Application>().getString(id)
}

/**
 * Whether the reader should be offered a translate disc for this song.
 *
 * Once a track has lyrics the disc stays for the whole lifecycle — idle,
 * downloading, blocked, done. Only [LyricsTranslationState.Unavailable]
 * takes it away, and that is the one answer that cannot change by asking
 * again. Everything else, including the in-flight [LyricsTranslationState.Loading],
 * is a state the button can still speak to: it shows the spinner, offers the
 * retry, and turns into the switch once the words exist. A control that
 * removes itself the moment it is pressed is a control the reader has to
 * trust blind, because the failure looks identical to the working case.
 */
internal fun offersTranslationDisc(
    availableModes: Set<MainViewModel.LyricsDisplayMode>,
    canTranslate: Boolean,
    translationState: LyricsTranslationState,
): Boolean = MainViewModel.LyricsDisplayMode.TRANSLATED in availableModes ||
    (canTranslate && translationState !is LyricsTranslationState.Unavailable)

/**
 * Whether a tap should start a translation, or would be a duplicate of one.
 *
 * The obvious version of this asks only whether the state says Loading, and
 * that is the bug: [LyricsTranslationState.Loading] is what the disc *shows*,
 * not a record of whether anything is still running. Ask it alone and the
 * answer stays "already loading" after the job is gone — which is how the
 * player came to sit on "Downloading English…" and refuse every tap, with
 * changing song as the only way out.
 *
 * So the running job is the test, and the state only narrows it to the
 * language actually being fetched: a live job for Spanish must not stop the
 * reader from asking for English.
 */
internal fun shouldStartTranslation(
    translationState: LyricsTranslationState,
    job: Job?,
    targetLanguage: String,
): Boolean {
    if (translationState is LyricsTranslationState.Ready &&
        Locale.forLanguageTag(translationState.targetLanguageTag).language == targetLanguage
    ) {
        // The words are on screen; the disc is a switch now, not a start.
        return false
    }
    if (job?.isActive != true) return true
    return translationState !is LyricsTranslationState.Loading ||
        Locale.forLanguageTag(translationState.targetLanguageTag).language != targetLanguage
}
