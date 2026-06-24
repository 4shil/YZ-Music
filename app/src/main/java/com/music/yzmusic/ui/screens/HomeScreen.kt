package com.music.yzmusic.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.LibraryMusic
import com.music.yzmusic.ui.icons.YZMusicIcons
import com.music.yzmusic.R
import coil3.compose.AsyncImage
import com.music.yzmusic.data.model.CARD_ART_PX
import com.music.yzmusic.data.model.HEADER_ART_PX
import com.music.yzmusic.data.model.HomeShelf
import com.music.yzmusic.data.model.ShelfItem
import com.music.yzmusic.data.model.UiState
import com.music.yzmusic.data.model.artworkAt
import com.music.yzmusic.ui.components.HERO_CARD_RATIO
import com.music.yzmusic.ui.components.MessageState
import com.music.yzmusic.ui.components.PAGE_GUTTER
import com.music.yzmusic.ui.components.PullToRefresh
import com.music.yzmusic.ui.components.SHELF_CARD_WIDTH
import com.music.yzmusic.ui.components.SignInBanner
import com.music.yzmusic.ui.components.feedMoreSkeleton
import com.music.yzmusic.ui.components.feedSkeleton
import com.music.yzmusic.ui.components.heroCardWidth
import com.music.yzmusic.ui.components.thumbnailBorder
import com.music.yzmusic.ui.player.MeshGradientBackground
import com.music.yzmusic.ui.player.MeshPalette

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: UiState<List<HomeShelf>>,
    listState: LazyListState,
    onItemClick: (ShelfItem) -> Unit,
    onRetry: () -> Unit,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    pullState: PullToRefreshState,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues,
    title: String = "Listen Now",
    signedIn: Boolean = true,
    onSignIn: (() -> Unit)? = null,
    /**
     * Holding a card rather than tapping it — the album/playlist menu. Only the
     * cards that point at a collection have one; a card that is a single track
     * is a track, and its own menu lives on the rows in the lists below.
     */
    onItemLongPress: ((ShelfItem) -> Unit)? = null,
    // Explore doesn't page — only Home has a continuation worth following.
    onLoadMore: (() -> Unit)? = null,
    loadingMore: Boolean = false,
) {
    PullToRefresh(
        refreshing = refreshing,
        onRefresh = onRefresh,
        state = pullState,
        modifier = modifier,
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding,
        ) {
            item {
                Text(
                    text = title,
                    style = MaterialTheme.typography.displayLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(horizontal = PAGE_GUTTER, vertical = 8.dp),
                )
            }
            if (!signedIn && onSignIn != null) {
                item {
                    SignInBanner(onSignIn = onSignIn, modifier = Modifier.padding(bottom = 8.dp))
                }
            }
            when (state) {
                is UiState.Loading -> feedSkeleton()
                is UiState.Error -> item {
                    MessageState(state.message, actionLabel = "Retry", onAction = onRetry)
                }
                is UiState.Success -> {
                    itemsIndexedShelves(state.data, onItemClick, onItemLongPress)
                    if (loadingMore) feedMoreSkeleton()
                }
            }
        }
    }

    if (onLoadMore != null && state is UiState.Success) {
        // Fires again each time the tail end of the list comes back into
        // view — appending shelves doesn't reset it, only leaving the
        // bottom and scrolling back down does, which is exactly when
        // another page is worth asking for.
        val nearEnd by remember {
            derivedStateOf {
