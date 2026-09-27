package com.music.yzmusic.ui.search

import com.music.yzmusic.data.model.BrowseItem
import com.music.yzmusic.data.model.BrowseType
import com.music.yzmusic.data.model.SearchEntityType
import com.music.yzmusic.data.model.SearchHistoryEntity
import com.music.yzmusic.data.model.SearchResult
import com.music.yzmusic.data.model.Song

/** One page of results, and the token for the next one. */
data class SearchPage(
    val rows: List<SearchResult>,
    val continuation: String?,
)

/**
 * What the search page is showing.
 *
 * A separate type from the app-wide `UiState` because a search has two very
 * different nothings to say, and folding them into one is what made a search
 * that matched nothing look exactly like a search that failed:
 *
 *  - [Idle] — nothing has been searched for, or the field was emptied. The
 *    recents belong here, and so does nothing being wrong.
 *  - [Empty] — a search ran and matched nothing. The query is good, there is
 *    no answer for it, and the field is the way out.
 *  - [Failed] — the search could not be carried out. [message] says so in the
 *    user's terms and the page offers to try again, which is worth offering
 *    exactly here and never for [Empty].
 */
sealed interface SearchState {
    data object Idle : SearchState
    data object Loading : SearchState
    data object Empty : SearchState
    data class Failed(val message: String) : SearchState
    data class Results(val rows: List<SearchResult>) : SearchState
}

/**
 * What a search row is, for two purposes at once: keeping the same thing from
 * appearing twice, and telling the list which row it is keeping.
 *
 * The promoted top card and the same track in the list below it are one song
 * reached twice, so both answer [SearchResult.TopTrack] and [SearchResult.Track]
 * with the track's own id — an unfiltered page returns the two for the same
 * recording and showing both would be showing one song twice. The prefix is
 * what keeps a browse id and a video id apart: ids are not shared across
 * kinds, and two different things that happen to read alike are two things.
 */
fun searchResultKey(row: SearchResult): String = when (row) {
    is SearchResult.TopTrack -> "v:${row.song.videoId}"
    is SearchResult.Track -> "v:${row.song.videoId}"
    is SearchResult.Browse -> "b:${row.item.browseId}"
}

/** [rows] in order, with the same entity once. */
fun dedupeSearchRows(rows: List<SearchResult>): List<SearchResult> = rows.distinctBy(::searchResultKey)

/** A page appended to what is already shown, with repeats dropped across the join. */
fun mergeSearchRows(
    current: List<SearchResult>,
    incoming: List<SearchResult>,
): List<SearchResult> = dedupeSearchRows(current + incoming)

/**
 * What opening a recent should do.
 *
 * A recent is not a term to search for any more. An entry that was recorded
 * from something the user actually picked can be opened as that thing, from
 * what is stored on the device, with nothing asked of the network — which is
 * the whole point of keeping the id and the metadata on the entry. Only a
 * typed term has nothing but the term, and so only that one searches.
 */
sealed interface RecentSearchAction {
    data class RunQuery(val query: String) : RecentSearchAction
    data class PlayTrack(val song: Song) : RecentSearchAction
    data class OpenDetail(val item: BrowseItem) : RecentSearchAction
}

/**
 * What tapping [entity] should open, or null if there is nothing to open.
 *
 * A track is rebuilt as the [Song] it was — enough to hand straight to the
 * player, which then does what it does with any other track. A page is rebuilt
 * as the [BrowseItem] it was. A term is a term.
 */
fun SearchHistoryEntity.toRecentAction(): RecentSearchAction? = when (entityType) {
    SearchEntityType.SONG, SearchEntityType.VIDEO -> {
        if (!isUsable) null else RecentSearchAction.PlayTrack(
            Song(
                videoId = id,
                title = title,
                artist = artist.orEmpty(),
                thumbnailUrl = artworkUrl,
                durationText = durationText,
                isVideo = entityType == SearchEntityType.VIDEO,
            ),
        )
    }
    SearchEntityType.ALBUM, SearchEntityType.ARTIST, SearchEntityType.PLAYLIST -> {
        if (!isUsable) null else RecentSearchAction.OpenDetail(
            BrowseItem(
                browseId = id,
                title = title,
                subtitle = artist.orEmpty(),
                thumbnailUrl = artworkUrl,
                type = when (entityType) {
                    SearchEntityType.ALBUM -> BrowseType.ALBUM
                    SearchEntityType.ARTIST -> BrowseType.ARTIST
                    else -> BrowseType.PLAYLIST
                },
            ),
        )
    }
    SearchEntityType.OTHER -> if (isUsable) {
        RecentSearchAction.OpenDetail(
            BrowseItem(id, title, artist.orEmpty(), artworkUrl, BrowseType.OTHER),
        )
    } else {
        null
    }
    SearchEntityType.QUERY -> searchTerm
        ?.takeIf { it.isNotBlank() }
        ?.let { RecentSearchAction.RunQuery(it) }
}
