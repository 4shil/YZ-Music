package com.music.yzmusic.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.NorthWest
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import coil3.compose.AsyncImage
import com.music.yzmusic.data.model.BrowseItem
import com.music.yzmusic.data.model.BrowseType
import com.music.yzmusic.data.model.ROW_ART_PX
import com.music.yzmusic.data.model.SearchFilter
import com.music.yzmusic.data.model.artworkAt
import com.music.yzmusic.data.model.SearchResult
import com.music.yzmusic.data.model.Song
import com.music.yzmusic.data.model.UiState
import com.music.yzmusic.R
import com.music.yzmusic.ui.components.MessageState
import com.music.yzmusic.ui.components.PAGE_GUTTER
import com.music.yzmusic.ui.components.ROW_DIVIDER_INSET
import com.music.yzmusic.ui.components.SongRow
import com.music.yzmusic.ui.components.thumbnailBorder
import com.music.yzmusic.ui.components.songListSkeleton
import com.music.yzmusic.ui.haptics.Haptic
import com.music.yzmusic.ui.haptics.rememberHaptics
import java.util.Locale

@Composable
fun SearchScreen(
    query: String,
    onQueryChange: (String) -> Unit,
    filter: SearchFilter,
    onFilterChange: (SearchFilter) -> Unit,
    results: UiState<List<SearchResult>>?,
    listState: LazyListState,
    focusTrigger: Int = 0,
    onSongClick: (List<Song>, Int) -> Unit,
    onSongLongPress: (Song) -> Unit,
    onSongSwipe: (Song) -> Unit,
    onBrowseClick: (BrowseItem) -> Unit,
    /**
     * Holding an album or playlist hit rather than tapping it — the same menu
     * the shelves open, so a release found by searching can go on the queue
     * without a trip through its page.
     */
    onBrowseLongPress: ((BrowseItem) -> Unit)? = null,
    history: List<String>,
    suggestions: List<String>,
    onSubmit: () -> Unit,
    onSuggestionClick: (String) -> Unit,
    onHistoryClick: (String) -> Unit,
    onHistoryRemove: (String) -> Unit,
    onHistoryClear: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues,
) {
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    // Re-tapping the search tab from the nav bar increments focusTrigger;
    // respond by focusing the field and opening the keyboard.
    LaunchedEffect(focusTrigger) {
        if (focusTrigger > 0) focusRequester.requestFocus()
    }
    // A non-empty suggestion list means the field is mid-edit — see
    // MainViewModel.suggestions. Nothing below it is worth showing while it is
    // up: the results are for whatever was searched before this edit began,
    // and so are the filter tabs above them.
    val suggesting = suggestions.isNotEmpty()
    Column(modifier = modifier.fillMaxSize()) {
        // Search field and filter tabs stay fixed at the top, outside the
        // scrolling list, so they're always reachable rather than scrolling
        // away with the results or recent searches beneath them.
        Column(modifier = Modifier.padding(top = contentPadding.calculateTopPadding())) {
            SearchField(
                query = query,
                onQueryChange = onQueryChange,
                onSubmit = onSubmit,
                focusRequester = focusRequester,
                modifier = Modifier.padding(start = PAGE_GUTTER, end = PAGE_GUTTER, bottom = 4.dp),
            )
            // The filters only mean something once there is a result set to narrow;
            // they stay up for an empty or failed search too, or picking a filter
            // that finds nothing would take away the control needed to leave it.
            if (results != null && !suggesting) {
                SearchFilterTabs(filter = filter, onFilterChange = onFilterChange)
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding()),
        ) {
            when {
                suggesting -> searchSuggestions(
                    suggestions = suggestions,
                    // Picking one is done typing, so the keyboard comes down
                    // with it and the results get the whole screen.
                    onClick = { term ->
                        onSuggestionClick(term)
                        focusManager.clearFocus()
                    },
                    onFill = onQueryChange,
                )
                results == null -> if (history.isEmpty()) {
                    item { MessageState(stringResource(R.string.search_empty)) }
                } else {
                    recentSearches(history, onHistoryClick, onHistoryRemove, onHistoryClear)
                }
                results is UiState.Loading -> songListSkeleton(circular = filter == SearchFilter.ARTISTS)
                results is UiState.Error -> item { MessageState(results.message) }
                results is UiState.Success -> {
                    // Tapping a track plays the tracks around it, not the browse rows.
                    val tracks = results.data
                        .filterIsInstance<SearchResult.Track>()
                        .map { it.song }
                    itemsIndexed(results.data) { index, row ->
                        when (row) {
                            is SearchResult.Track -> SongRow(
                                song = row.song,
                                onClick = {
                                    onSongClick(tracks, tracks.indexOf(row.song).coerceAtLeast(0))
                                },
                                onLongPress = { onSongLongPress(row.song) },
                                onSwipeToQueue = { onSongSwipe(row.song) },
                            )
                            is SearchResult.Browse -> BrowseRow(
                                item = row.item,
                                onClick = { onBrowseClick(row.item) },
                                onLongPress = onBrowseLongPress?.let { { it(row.item) } },
                            )
                        }
                        if (index < results.data.lastIndex) {
                            HorizontalDivider(
                                modifier = Modifier.padding(start = ROW_DIVIDER_INSET),
                                thickness = 0.5.dp,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * What YouTube would complete the half-typed query to, in place of the results
 * while it is being typed.
 *
 * The first row is the text as typed, put there by the view model rather than
 * taken from YouTube's answer, so running exactly what was asked for is always
 * the nearest row to the keyboard rather than something the thumb has to aim
 * past.
 */
