package com.music.yzmusic.data.innertube

import com.music.yzmusic.data.model.Account
import com.music.yzmusic.data.model.ArtistPage
import com.music.yzmusic.data.model.BrowseItem
import com.music.yzmusic.data.model.BrowseType
import com.music.yzmusic.data.model.HomeShelf
import com.music.yzmusic.data.model.LibraryState
import com.music.yzmusic.data.model.LikeStatus
import com.music.yzmusic.data.model.SearchResult
import com.music.yzmusic.data.model.ShelfItem
import com.music.yzmusic.data.model.Song
import com.music.yzmusic.data.model.SongMenu
import com.music.yzmusic.data.model.UserPlaylist
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.util.Locale

/**
 * Innertube responses are deeply nested and their shape drifts between
 * layouts (single-column vs two-column browse, shelf vs carousel). Rather
 * than hard-coding every path, structured parsing is used where the layout
 * is stable (search, home) and a recursive scan where it is not (playlists,
 * library) — see [collectSongsDeep].
 */
object InnertubeParser {

    // ---- Search -------------------------------------------------------------

    fun parseSearchSongs(response: JsonObject): List<Song> =
        parseSearch(response).filterIsInstance<SearchResult.Track>().map { it.song }

    /**
     * Search results are heterogeneous: songs carry a videoId, while albums,
     * artists and playlists carry a browseId plus a page type. Both arrive as
     * `musicResponsiveListItemRenderer`, so each row is classified on the way out.
     */
    fun parseSearch(response: JsonObject): List<SearchResult> {
        // The "All" tab spreads results across several shelf types (card shelf
        // for the top result, then one shelf per category), and the shapes
        // differ per filter. Walking for the row renderer itself is far more
        // robust than chasing each container path.
        val rows = collectRenderers(response, "musicResponsiveListItemRenderer")

        val seen = HashSet<String>()
        return rows.mapNotNull { renderer ->
            // Browse rows are tested first: an album row also carries a
            // "play album" videoId in its overlay, so checking for a track
            // first would misread every album as a single song.
            parseBrowseItem(renderer)?.let { item ->
                return@mapNotNull if (seen.add("b:${item.browseId}")) {
                    SearchResult.Browse(item)
                } else {
                    null
                }
            }
            parseResponsiveListItem(renderer)?.let { song ->
                if (song.isVideo) return@mapNotNull null
                if (seen.add("v:${song.videoId}")) SearchResult.Track(song) else null
            }
        }
    }

    /**
     * The typeahead queries out of a `music/get_search_suggestions` response.
     *
     * Two sections come back. The first is what this reads: query strings, as
     * `searchSuggestionRenderer`. The second — present signed in, and for
     * some terms signed out — is entity rows for songs and artists, as the
     * same `musicResponsiveListItemRenderer` a search result uses. Those are
     * deliberately ignored: what the field is being filled in with is a
     * query, and a row that navigates straight to a track instead is a
     * different feature with a different tap target.
     *
     * `searchEndpoint.query` is preferred over the display text because the
     * display text arrives split into runs purely so the typed prefix can be
     * bold-faced, with no separator of its own to rejoin on.
     */
    fun parseSearchSuggestions(response: JsonObject): List<String> =
        collectRenderers(response, "searchSuggestionRenderer")
            .mapNotNull { renderer ->
                val query = renderer.o("navigationEndpoint").o("searchEndpoint").s("query")
                    ?: renderer.o("suggestion").runs()
                query.takeIf { it.isNotBlank() }
            }
            .distinct()

    /** Depth-first collection of a named renderer, preserving document order. */
    private fun collectRenderers(root: JsonElement, name: String): List<JsonObject> {
        val out = mutableListOf<JsonObject>()
        fun walk(node: JsonElement) {
            when (node) {
                is JsonObject -> {
                    (node[name] as? JsonObject)?.let(out::add)
                    node.values.forEach(::walk)
                }
                is JsonArray -> node.forEach(::walk)
                else -> Unit
            }
        }
        walk(root)
        return out
    }

    private fun parseBrowseItem(renderer: JsonObject): BrowseItem? {
        val endpoint = renderer.o("navigationEndpoint").o("browseEndpoint") ?: return null
        val browseId = endpoint.s("browseId") ?: return null
        val pageType = endpoint.o("browseEndpointContextSupportedConfigs")
            .o("browseEndpointContextMusicConfig").s("pageType").orEmpty()

        val columns = renderer.a("flexColumns").orEmpty()
        val title = columns.getOrNull(0)
            .o("musicResponsiveListItemFlexColumnRenderer").o("text").runs()
        if (title.isBlank()) return null

        val subtitle = columns.getOrNull(1)
            .o("musicResponsiveListItemFlexColumnRenderer").o("text").runs()
        // A playlist/album billed as a video chart/compilation — "N videos"
        // in the subtitle, or "video" right in the title, e.g. "Daily Top
        // Music Videos" — would have every row dropped by
        // parseResponsiveListItem anyway, so skip the dead-end card rather
        // than link to an empty page.
        if (VIDEO_WORD.containsMatchIn(title) || VIDEO_WORD.containsMatchIn(subtitle)) return null

        return BrowseItem(
            browseId = browseId,
            title = title,
            subtitle = subtitle,
            thumbnailUrl = renderer.o("thumbnail").o("musicThumbnailRenderer")
                .o("thumbnail").a("thumbnails").best(),
            type = when {
                "ALBUM" in pageType -> BrowseType.ALBUM
                "ARTIST" in pageType -> BrowseType.ARTIST
                "PLAYLIST" in pageType -> BrowseType.PLAYLIST
                else -> BrowseType.OTHER
            },
        )
    }

    // ---- Home feed ----------------------------------------------------------

    fun parseHome(response: JsonObject): List<HomeShelf> {
        val sections = response.o("contents")
            .o("singleColumnBrowseResultsRenderer").a("tabs")?.firstOrNull()
            .o("tabRenderer").o("content").o("sectionListRenderer").a("contents")
            .orEmpty()

        return sections.mapNotNull { section ->
            section.o("musicCarouselShelfRenderer")?.let(::carouselShelf)
                ?: section.o("musicShelfRenderer")?.let(::plainShelf)
        }
    }

    /**
     * More Home shelves off a continuation response.
     *
     * Unlike the first page, a continuation envelope doesn't repeat the
     * tabs/section-list wrapper [parseHome] reads off a fixed path — so the
     * shelves are walked out wherever they land instead, the same tradeoff
     * [collectSongsDeep] makes for song rows. Preserves the order they were
     * found in, since a carousel and a plain shelf never share a parent node.
     */
    fun parseHomeContinuation(root: JsonElement): List<HomeShelf> {
        val out = mutableListOf<HomeShelf>()
        fun walk(node: JsonElement) {
            when (node) {
                is JsonObject -> {
                    (node["musicCarouselShelfRenderer"] as? JsonObject)
                        ?.let(::carouselShelf)?.let(out::add)
                    (node["musicShelfRenderer"] as? JsonObject)
                        ?.let(::plainShelf)?.let(out::add)
                    node.values.forEach(::walk)
                }
                is JsonArray -> node.forEach(::walk)
                else -> Unit
            }
        }
        walk(root)
        return out
    }

    private fun carouselShelf(carousel: JsonObject): HomeShelf? {
        val header = carousel.o("header").o("musicCarouselShelfBasicHeaderRenderer")
        val title = header.o("title").runs()
        val strapline = header.o("strapline").runs()
        // Whole shelves like "Video charts" carry nothing but video
        // compilations — each card would fail its own video check on the
        // way to a dead-end page, so the shelf is dropped outright.
        if (VIDEO_WORD.containsMatchIn(title)) return null
        val items = carousel.a("contents").orEmpty().mapNotNull { item ->
            parseTwoRowItem(item.o("musicTwoRowItemRenderer"))
                ?: parseResponsiveListItem(item.o("musicResponsiveListItemRenderer"))
                    ?.takeUnless { it.isVideo }
                    ?.let { song ->
                        ShelfItem(song.title, song.artist, song.thumbnailUrl, song.videoId, null)
                    }
                // A chart row with nothing to play — "Top artists" lists the
                // artist alone, no track — falls through parseResponsiveListItem
                // (it demands a videoId) and used to drop the whole shelf.
                ?: parseArtistRow(item.o("musicResponsiveListItemRenderer"))
        }
        return if (items.isEmpty()) null else HomeShelf(title.ifBlank { "For you" }, items, strapline)
    }

    private fun plainShelf(shelf: JsonObject): HomeShelf? {
        val title = shelf.o("title").runs()
        if (VIDEO_WORD.containsMatchIn(title)) return null
        val items = shelf.a("contents").orEmpty().mapNotNull {
            parseResponsiveListItem(it.o("musicResponsiveListItemRenderer"))
        }.filterNot { it.isVideo }
            .map { ShelfItem(it.title, it.artist, it.thumbnailUrl, it.videoId, null) }
        return if (items.isEmpty()) null else HomeShelf(title.ifBlank { "For you" }, items)
    }

    /**
     * Artist landing page: a "Top songs" shelf (only ~5 rows, but its header
     * links to a playlist with the full list) plus carousels for Albums,
     * Singles & EPs and friends.
     */
    fun parseArtistPage(response: JsonObject): ArtistPage {
        val sections = response.o("contents")
            .o("singleColumnBrowseResultsRenderer").a("tabs")?.firstOrNull()
            .o("tabRenderer").o("content").o("sectionListRenderer").a("contents")
            .orEmpty()

        val songs = mutableListOf<Song>()
        var moreSongs: String? = null
        val shelves = mutableListOf<HomeShelf>()
        val header = response["header"]
        // "Top songs" rows are billed by the page they sit on: the subtitle
        // beside them counts plays where a search row names the artist.
        val credit = Credits(artistName = artistName(header))

        sections.forEach { section ->
            section.o("musicShelfRenderer")?.let { shelf ->
                shelf.a("contents").orEmpty().forEach { row ->
                    parseResponsiveListItem(row.o("musicResponsiveListItemRenderer"), credit)
                        ?.let(songs::add)
                }
                if (moreSongs == null) {
                    moreSongs = shelf.o("title").a("runs")?.firstOrNull()
                        .o("navigationEndpoint").o("browseEndpoint").s("browseId")
                }
            }
            section.o("musicCarouselShelfRenderer")?.let { carousel ->
                val header = carousel.o("header").o("musicCarouselShelfBasicHeaderRenderer")
                val title = header.o("title").runs()
                if (VIDEO_WORD.containsMatchIn(title)) return@let
                val items = carousel.a("contents").orEmpty().mapNotNull {
                    parseTwoRowItem(it.o("musicTwoRowItemRenderer"))
                }.filter { it.browseId != null }
                if (title.isNotBlank() && items.isNotEmpty()) {
                    shelves += HomeShelf(title, items)
                }
            }
        }
        return ArtistPage(
            songs, moreSongs, shelves,
            thumbnailUrl = artistThumbnail(header),
            name = credit.artistName,
            description = parseDescription(response),
            subscriberCountText = subscriberCount(header),
            monthlyListenerCount = monthlyListeners(header),
        )
    }

    /**
     * "1.2M subscribers" off the artist header's subscribe button — YouTube
     * ships two shapes of it depending on how the page was served, and the
     * button itself carries the count under one of three different keys
     * across those shapes.
     */
    private fun subscriberCount(header: JsonElement?): String? {
        val immersive = header.o("musicImmersiveHeaderRenderer") ?: return null
        val button2 = immersive.o("subscriptionButton2").o("subscribeButtonRenderer")
        val button1 = immersive.o("subscriptionButton").o("subscribeButtonRenderer")
        return button2.o("subscriberCountWithSubscribeText").firstRunText()
            ?: button1.o("longSubscriberCountText").firstRunText()
            ?: button1.o("shortSubscriberCountText").firstRunText()
    }

    /** "3.4M monthly listeners", off the same header as [subscriberCount]. */
    private fun monthlyListeners(header: JsonElement?): String? =
        header.o("musicImmersiveHeaderRenderer").o("monthlyListenerCount").firstRunText()

    /**
     * The name the page bills itself under. A track credited to a trio hands
     * its callers all three names at once, so the page's own header is what
     * says which of them is actually open.
     */
    private fun artistName(header: JsonElement?): String? {
        val renderer = header.o("musicImmersiveHeaderRenderer")
            ?: header.o("musicVisualHeaderRenderer")
            ?: return null
        return renderer.o("title").runs().takeIf { it.isNotBlank() }
    }

    /**
     * The artist's own picture, off whichever header shape came back — the
     * immersive header serves it as `thumbnail`, the visual header as
     * `foregroundThumbnail` over a banner. Callers that arrive from a track
     * only know that track's cover art, so this is what a page is meant to
     * show instead.
     */
    private fun artistThumbnail(header: JsonElement?): String? {
        if (header == null) return null
        val immersive = header.o("musicImmersiveHeaderRenderer")
        val visual = header.o("musicVisualHeaderRenderer")
        val renderer = (
            immersive.o("thumbnail")
                ?: visual.o("foregroundThumbnail")
                ?: visual.o("thumbnail")
            ).o("musicThumbnailRenderer")
            // Header shapes drift; fall back to the first image anywhere under
            // the header rather than to the caller's album art.
            ?: collectRenderers(header, "musicThumbnailRenderer").firstOrNull()
        return renderer.o("thumbnail").a("thumbnails").best()
    }

    // ---- Generic / robust ---------------------------------------------------

    /**
     * Walks the whole response collecting any `musicResponsiveListItemRenderer`
     * that carries a videoId. Layout-agnostic, so it survives the differences
     * between playlist, album, library and history pages.
     */
    fun collectSongsDeep(root: JsonElement): List<Song> {
        val out = LinkedHashMap<String, Song>()
        // A release's own rows are credited by its header, not one by one.
