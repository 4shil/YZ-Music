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
