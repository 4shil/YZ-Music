package com.music.yzmusic.ui.screens

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.music.yzmusic.R
import com.music.yzmusic.data.innertube.InnertubeParser
import com.music.yzmusic.data.model.CARD_ART_PX
import com.music.yzmusic.data.model.HEADER_ART_PX
import com.music.yzmusic.data.model.HomeShelf
import com.music.yzmusic.data.model.ShelfItem
import com.music.yzmusic.data.model.ShelfType
import com.music.yzmusic.data.model.Song
import com.music.yzmusic.data.model.UiState
import com.music.yzmusic.data.model.artworkAt
import com.music.yzmusic.ui.components.HERO_CARD_RATIO
import com.music.yzmusic.ui.components.MessageState
import com.music.yzmusic.ui.components.PAGE_GUTTER
import com.music.yzmusic.ui.components.PullToRefresh
import com.music.yzmusic.ui.components.SHELF_CARD_WIDTH
import com.music.yzmusic.ui.components.ShimmerBox
import com.music.yzmusic.ui.components.heroCardWidth
import com.music.yzmusic.ui.components.thumbnailBorder
import com.music.yzmusic.ui.icons.YZMusicIcons
import java.util.Locale

/**
 * Explore screen for music discovery.
 * Apple Music-inspired discovery presentation powered by YouTube Music data.
 *
 * Core architectural features:
 * - Single authoritative "Explore" title with zero duplicate headings.
 * - Top frosted navigation pill row for quick category filtering (Charts, New Releases, Moods & Genres).
 * - Distinct visual classification for Trending & Video Charts (16:9 widescreen) vs Top Artists (circular avatars) vs Top Songs (ranked multi-row).
 * - Curated 1:1 release cards with badge highlights and responsive "See All" grids.
 * - Dedicated Explore skeleton eliminating layout shifts and empty hero boxes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExploreScreen(
    state: UiState<List<HomeShelf>>,
    listState: LazyListState,
    onItemClick: (ShelfItem) -> Unit,
    onSongClick: ((List<Song>, Int) -> Unit)? = null,
    onRetry: () -> Unit,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    pullState: PullToRefreshState,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues,
    onItemLongPress: ((ShelfItem) -> Unit)? = null,
    onShowAll: ((HomeShelf) -> Unit)? = null,
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
            // Screen Header: Single "Explore" title matching Apple Music Discovery
            item(key = "explore_screen_header") {
                Text(
                    text = "Explore",
                    style = MaterialTheme.typography.displayLarge.copy(
                        fontWeight = FontWeight.Bold,
                    ),
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(horizontal = PAGE_GUTTER, vertical = 8.dp),
                )
            }

            when (state) {
                is UiState.Loading -> {
                    item(key = "explore_skeleton") {
                        ExploreSkeleton()
                    }
                }
                is UiState.Error -> {
                    item(key = "explore_error") {
                        MessageState(state.message, actionLabel = "Retry", onAction = onRetry)
                    }
                }
                is UiState.Success -> {
                    val allShelves = state.data
                    // Identify top navigation shelf (New releases, Charts, Moods & genres buttons)
                    val navShelf = allShelves.firstOrNull { shelf ->
                        shelf.type == ShelfType.NAV_CHIPS ||
                            shelf.items.all { it.thumbnailUrl == null && it.browseId?.startsWith("FEmusic_") == true } ||
                            (shelf.title.equals("Explore", ignoreCase = true) && shelf.items.any { it.thumbnailUrl == null })
                    }
                    val contentShelves = if (navShelf != null) {
                        allShelves.filter { it !== navShelf }
                    } else {
                        allShelves
                    }

                    // Render Top Quick Navigation Pills
                    if (navShelf != null && navShelf.items.isNotEmpty()) {
                        item(key = "explore_nav_pills") {
                            ExploreNavChipsRow(
                                navShelf = navShelf,
                                onItemClick = onItemClick,
                            )
                        }
                    }

                    // Render Categorized Shelves
                    contentShelves.forEachIndexed { index, shelf ->
                        // Skip any shelf that accidentally repeats "Explore" with no items
                        if (shelf.items.isEmpty()) return@forEachIndexed

                        val displayTitle = if (shelf.title.equals("Explore", ignoreCase = true)) {
                            shelf.strapline ?: "Featured"
                        } else {
                            shelf.title
                        }

                        val showAllAction = if (shelf.moreBrowseId != null && onShowAll != null) {
                            { onShowAll(shelf) }
                        } else null

                        item(key = "shelf_${shelf.title}_$index") {
                            when {
                                // 1. Editorial Hero Shelf (only when items have valid high-res promotional artwork)
                                (shelf.type == ShelfType.HERO || (index == 0 && (shelf.title.contains("Featured", ignoreCase = true) || shelf.title.contains("Spotlight", ignoreCase = true)))) &&
                                    shelf.items.firstOrNull()?.thumbnailUrl != null -> {
                                    ExploreHeroShelf(
                                        title = displayTitle,
                                        shelf = shelf,
                                        onItemClick = onItemClick,
                                        onItemLongPress = onItemLongPress,
                                        onShowAll = showAllAction,
                                    )
                                }

                                // 2. Top Songs / Chart Songs (Ranked Multi-Row layout)
                                shelf.type == ShelfType.CHART_SONGS ||
                                    (shelf.title.contains("Song", ignoreCase = true) && shelf.items.any { it.customIndex != null }) ||
                                    (shelf.title.contains("Top", ignoreCase = true) && shelf.title.contains("Song", ignoreCase = true)) -> {
                                    RankedSongShelf(
                                        title = displayTitle,
                                        shelf = shelf,
                                        onItemClick = onItemClick,
                                        onSongClick = onSongClick,
                                        onItemLongPress = onItemLongPress,
                                        onShowAll = showAllAction,
                                    )
                                }

                                // 3. Top Artists (Circular Avatars with Rank Badges)
                                shelf.type == ShelfType.CHART_ARTISTS ||
                                    (shelf.title.contains("Artist", ignoreCase = true) && shelf.items.any { it.customIndex != null }) ||
                                    (shelf.items.any { it.browseId?.startsWith("UC") == true } && shelf.items.any { it.customIndex != null }) -> {
                                    RankedArtistShelf(
                                        title = displayTitle,
                                        shelf = shelf,
                                        onItemClick = onItemClick,
                                        onItemLongPress = onItemLongPress,
                                        onShowAll = showAllAction,
                                    )
                                }

                                // 4. Video Charts / Trending Videos (16:9 Widescreen)
                                shelf.type == ShelfType.CHART_VIDEOS ||
                                    shelf.type == ShelfType.VIDEO ||
                                    shelf.items.all { it.isVideo } ||
                                    (shelf.title.contains("Video", ignoreCase = true) && shelf.items.any { it.customIndex != null || it.isVideo }) ||
                                    (shelf.title.contains("Trending", ignoreCase = true) && shelf.items.any { it.isVideo }) -> {
                                    RankedVideoShelf(
                                        title = displayTitle,
                                        shelf = shelf,
                                        onItemClick = onItemClick,
                                        onSongClick = onSongClick,
                                        onItemLongPress = onItemLongPress,
                                        onShowAll = showAllAction,
                                    )
                                }

                                // 5. Moods & Genres (Curated Gradient Cards)
                                shelf.type == ShelfType.MOOD_GENRE ||
                                    shelf.items.any { it.stripeColor != null || it.browseId?.startsWith("FEmusic_moods_and_genres") == true } -> {
                                    ExploreMoodGenreShelf(
                                        title = displayTitle,
                                        shelf = shelf,
                                        onItemClick = onItemClick,
                                        onShowAll = showAllAction,
                                    )
                                }

                                // 6. General Ranked Shelf fallback
                                shelf.type == ShelfType.RANKED -> {
                                    // Check if items look like videos or standard releases
                                    if (shelf.items.any { it.isVideo }) {
                                        RankedVideoShelf(
                                            title = displayTitle,
                                            shelf = shelf,
                                            onItemClick = onItemClick,
                                            onSongClick = onSongClick,
                                            onItemLongPress = onItemLongPress,
                                            onShowAll = showAllAction,
                                        )
                                    } else {
                                        ExploreReleaseShelf(
                                            title = displayTitle,
                                            shelf = shelf,
                                            onItemClick = onItemClick,
                                            onItemLongPress = onItemLongPress,
                                            onShowAll = showAllAction,
                                            showRankBadge = true,
                                        )
                                    }
                                }

                                // 7. Standard New Releases / Playlists / Albums
                                else -> {
                                    ExploreReleaseShelf(
                                        title = displayTitle,
                                        shelf = shelf,
                                        onItemClick = onItemClick,
                                        onItemLongPress = onItemLongPress,
                                        onShowAll = showAllAction,
                                        showRankBadge = false,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Top Quick Navigation Pills in Apple Music frosted aesthetic.
 */
@Composable
private fun ExploreNavChipsRow(
    navShelf: HomeShelf,
    onItemClick: (ShelfItem) -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = PAGE_GUTTER, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(navShelf.items) { item ->
            val icon = when {
                item.title.contains("New", ignoreCase = true) || item.title.contains("Release", ignoreCase = true) ->
                    Icons.Rounded.AutoAwesome
                item.title.contains("Chart", ignoreCase = true) || item.title.contains("Top", ignoreCase = true) ->
                    Icons.Rounded.BarChart
                item.title.contains("Mood", ignoreCase = true) || item.title.contains("Genre", ignoreCase = true) ->
                    Icons.Rounded.GraphicEq
                item.title.contains("Podcast", ignoreCase = true) ->
                    Icons.Rounded.Mic
                else -> YZMusicIcons.Explore
            }
            Surface(
                onClick = { onItemClick(item) },
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                border = BorderStroke(
                    0.8.dp,
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                ),
                modifier = Modifier.height(40.dp),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontWeight = FontWeight.SemiBold,
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

/**
 * Section Header with optional Apple Music style strapline and "See All" chevron action.
 */
@Composable
private fun ExploreSectionHeader(
    title: String,
    subtitle: String = "",
    strapline: String? = null,
    onShowAll: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PAGE_GUTTER, vertical = 8.dp),
    ) {
        if (!strapline.isNullOrBlank()) {
            Text(
                text = strapline.uppercase(Locale.ROOT),
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.1.sp,
                ),
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(bottom = 2.dp),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontWeight = FontWeight.Bold,
                    ),
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle.isNotBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (onShowAll != null) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onShowAll)
                        .padding(start = 8.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.show_all),
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontWeight = FontWeight.SemiBold,
                        ),
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(2.dp))
                    Icon(
                        imageVector = Icons.Rounded.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

/**
 * 16:9 Widescreen Video Cards for Trending & Video Charts.
 */
@Composable
private fun RankedVideoShelf(
    title: String,
    shelf: HomeShelf,
    onItemClick: (ShelfItem) -> Unit,
    onSongClick: ((List<Song>, Int) -> Unit)? = null,
    onItemLongPress: ((ShelfItem) -> Unit)? = null,
    onShowAll: (() -> Unit)? = null,
) {
    val shelfSongs = remember(shelf.items) {
        shelf.items.mapNotNull { itm ->
            itm.videoId?.let { vid ->
                Song(
                    videoId = vid,
                    title = itm.title,
                    artist = InnertubeParser.artistFromSubtitle(itm.subtitle),
                    thumbnailUrl = itm.thumbnailUrl,
                    isVideo = itm.isVideo,
                )
            }
        }
    }

    Column(Modifier.padding(bottom = 26.dp)) {
        ExploreSectionHeader(
            title = title,
            subtitle = shelf.subtitle,
            strapline = shelf.strapline ?: "TRENDING",
            onShowAll = onShowAll,
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = PAGE_GUTTER),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            items(shelf.items) { item ->
                RankedVideoCard(
                    item = item,
                    onClick = {
                        val songIndex = shelfSongs.indexOfFirst { it.videoId == item.videoId }
                        if (songIndex >= 0 && onSongClick != null) {
                            onSongClick(shelfSongs, songIndex)
                        } else {
                            onItemClick(item)
                        }
                    },
                    onLongPress = onItemLongPress?.let { { it(item) } },
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RankedVideoCard(
    item: ShelfItem,
    onClick: () -> Unit,
    onLongPress: (() -> Unit)? = null,
    modifier: Modifier = Modifier.width(250.dp),
) {
    Column(
        modifier = modifier.combinedClickable(onClick = onClick, onLongClick = onLongPress),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(12.dp))
                .thumbnailBorder(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            AsyncImage(
                model = item.thumbnailUrl.artworkAt(HEADER_ART_PX),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            // Bottom Scrim
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f)),
                        ),
                    ),
            )
            // Rank badge in top-left
            if (!item.customIndex.isNullOrBlank()) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.Black.copy(alpha = 0.75f))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Text(
                        text = "#${item.customIndex}",
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.Bold,
                        ),
                        color = Color.White,
                    )
                }
            }
            // Video play icon pill in bottom-right
            Box(
                modifier = Modifier
                    .padding(8.dp)
                    .align(Alignment.BottomEnd)
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.65f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = YZMusicIcons.Play,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(13.dp),
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = item.title,
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.SemiBold,
            ),
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = item.subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Top Artists Shelf with Circular Avatars and Rank Badges.
 */
@Composable
private fun RankedArtistShelf(
    title: String,
    shelf: HomeShelf,
    onItemClick: (ShelfItem) -> Unit,
    onItemLongPress: ((ShelfItem) -> Unit)? = null,
    onShowAll: (() -> Unit)? = null,
) {
    Column(Modifier.padding(bottom = 26.dp)) {
        ExploreSectionHeader(
            title = title,
            subtitle = shelf.subtitle,
            strapline = shelf.strapline ?: "CHARTS",
            onShowAll = onShowAll,
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = PAGE_GUTTER),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(shelf.items) { item ->
                RankedArtistCard(
                    item = item,
                    onClick = { onItemClick(item) },
                    onLongPress = onItemLongPress?.let { { it(item) } },
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RankedArtistCard(
    item: ShelfItem,
    onClick: () -> Unit,
    onLongPress: (() -> Unit)? = null,
    modifier: Modifier = Modifier.width(128.dp),
) {
    Column(
        modifier = modifier.combinedClickable(onClick = onClick, onLongClick = onLongPress),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(118.dp)
                .clip(CircleShape)
                .thumbnailBorder(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            AsyncImage(
                model = item.thumbnailUrl.artworkAt(CARD_ART_PX),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            if (!item.customIndex.isNullOrBlank()) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(4.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                        .padding(horizontal = 7.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = "#${item.customIndex}",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                        ),
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = item.title,
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.SemiBold,
            ),
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        if (item.subtitle.isNotBlank()) {
            Text(
                text = item.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * Top Songs multi-row layout (Apple Music Charts format: 4 rows stacked per column).
 */
@Composable
private fun RankedSongShelf(
    title: String,
    shelf: HomeShelf,
    onItemClick: (ShelfItem) -> Unit,
    onSongClick: ((List<Song>, Int) -> Unit)? = null,
    onItemLongPress: ((ShelfItem) -> Unit)? = null,
    onShowAll: (() -> Unit)? = null,
) {
    Column(Modifier.padding(bottom = 26.dp)) {
        ExploreSectionHeader(
            title = title,
            subtitle = shelf.subtitle,
            strapline = shelf.strapline ?: "CHARTS",
            onShowAll = onShowAll,
        )
        val shelfSongs = remember(shelf.items) {
            shelf.items.filter { it.videoId != null }.map { item ->
                Song(
                    videoId = item.videoId!!,
                    title = item.title,
                    artist = InnertubeParser.artistFromSubtitle(item.subtitle).ifBlank { item.subtitle },
                    thumbnailUrl = item.thumbnailUrl,
                    isVideo = item.isVideo,
                )
            }
        }
        val columns = remember(shelf.items) { shelf.items.chunked(4) }
        LazyRow(
            contentPadding = PaddingValues(horizontal = PAGE_GUTTER),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(columns) { columnItems ->
                Column(
                    modifier = Modifier.width(310.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    columnItems.forEach { item ->
                        RankedSongRowItem(
                            item = item,
                            onClick = {
                                val songIndex = shelfSongs.indexOfFirst { it.videoId == item.videoId }
                                if (songIndex >= 0 && onSongClick != null) {
                                    onSongClick(shelfSongs, songIndex)
                                } else {
                                    onItemClick(item)
                                }
                            },
                            onLongPress = onItemLongPress?.let { { it(item) } },
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RankedSongRowItem(
    item: ShelfItem,
    onClick: () -> Unit,
    onLongPress: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongPress)
            .padding(vertical = 4.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Rank number
        val rankText = item.customIndex ?: ""
        if (rankText.isNotBlank()) {
            Text(
                text = rankText,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                ),
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.width(28.dp),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.width(8.dp))
        }
        // Artwork
        AsyncImage(
            model = item.thumbnailUrl.artworkAt(CARD_ART_PX),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(6.dp))
                .thumbnailBorder(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
        Spacer(Modifier.width(12.dp))
        // Title & Artist
        Column(Modifier.weight(1f)) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontWeight = FontWeight.SemiBold,
                ),
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = item.subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Curated Moods & Genres 2-Row Gradient Grid.
 */
@Composable
private fun ExploreMoodGenreShelf(
    title: String,
    shelf: HomeShelf,
    onItemClick: (ShelfItem) -> Unit,
    onShowAll: (() -> Unit)? = null,
) {
    Column(Modifier.padding(bottom = 26.dp)) {
        ExploreSectionHeader(
            title = title,
            subtitle = shelf.subtitle,
            strapline = shelf.strapline ?: "MOODS & GENRES",
            onShowAll = onShowAll,
        )
        val pairs = remember(shelf.items) { shelf.items.chunked(2) }
        LazyRow(
            contentPadding = PaddingValues(horizontal = PAGE_GUTTER),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(pairs) { pair ->
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    pair.forEach { item ->
                        ExploreMoodGenreTile(
                            item = item,
                            onClick = { onItemClick(item) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ExploreMoodGenreTile(
    item: ShelfItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val stripeColor = item.stripeColor?.let { Color(it.toInt()) }
    val bgBrush = remember(item.title, stripeColor) {
        if (stripeColor != null) {
            Brush.horizontalGradient(
                listOf(stripeColor.copy(alpha = 0.45f), stripeColor.copy(alpha = 0.15f)),
            )
        } else {
            val hash = item.title.hashCode()
            val c1 = when (kotlin.math.abs(hash) % 5) {
                0 -> Color(0xFF6C5CE7)
                1 -> Color(0xFFE17055)
                2 -> Color(0xFF00B894)
                3 -> Color(0xFF0984E3)
                else -> Color(0xFFFD79A8)
            }
            Brush.horizontalGradient(listOf(c1.copy(alpha = 0.48f), c1.copy(alpha = 0.16f)))
        }
    }

    Box(
        modifier = modifier
            .width(168.dp)
            .height(56.dp)
            .clip(RoundedCornerShape(12.dp))
            .thumbnailBorder(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .background(bgBrush)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = item.title,
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.Bold,
            ),
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Editorial Hero Shelf for high-impact discovery cards.
 */
@Composable
private fun ExploreHeroShelf(
    title: String,
    shelf: HomeShelf,
    onItemClick: (ShelfItem) -> Unit,
    onItemLongPress: ((ShelfItem) -> Unit)? = null,
    onShowAll: (() -> Unit)? = null,
) {
    Column(Modifier.padding(bottom = 26.dp)) {
        ExploreSectionHeader(
            title = title,
            subtitle = shelf.subtitle,
            strapline = shelf.strapline ?: "FEATURED",
            onShowAll = onShowAll,
        )
        BoxWithConstraints {
            val cardWidth = heroCardWidth(maxWidth)
            LazyRow(
                contentPadding = PaddingValues(horizontal = PAGE_GUTTER),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                items(shelf.items) { item ->
                    Box(
                        modifier = Modifier
                            .width(cardWidth)
                            .aspectRatio(HERO_CARD_RATIO)
                            .clip(RoundedCornerShape(18.dp))
                            .thumbnailBorder(RoundedCornerShape(18.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .clickable { onItemClick(item) },
                    ) {
                        AsyncImage(
                            model = item.thumbnailUrl.artworkAt(HEADER_ART_PX),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .align(Alignment.BottomStart)
                                .background(
                                    Brush.verticalGradient(
                                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.78f)),
                                    ),
                                )
                                .padding(start = 16.dp, end = 16.dp, top = 34.dp, bottom = 14.dp),
                        ) {
                            Text(
                                text = item.title,
                                style = MaterialTheme.typography.titleLarge.copy(
                                    fontWeight = FontWeight.Bold,
                                ),
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (item.subtitle.isNotBlank()) {
                                Text(
                                    text = item.subtitle,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Color.White.copy(alpha = 0.72f),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Standard Release Shelf for New Albums & Singles with optional rank badge.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ExploreReleaseShelf(
    title: String,
    shelf: HomeShelf,
    onItemClick: (ShelfItem) -> Unit,
    onItemLongPress: ((ShelfItem) -> Unit)? = null,
    onShowAll: (() -> Unit)? = null,
    showRankBadge: Boolean = false,
) {
    Column(Modifier.padding(bottom = 26.dp)) {
        ExploreSectionHeader(
            title = title,
            subtitle = shelf.subtitle,
            strapline = shelf.strapline,
            onShowAll = onShowAll,
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = PAGE_GUTTER),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            items(shelf.items) { item ->
                Column(
                    modifier = Modifier
                        .width(SHELF_CARD_WIDTH)
                        .combinedClickable(
                            onClick = { onItemClick(item) },
                            onLongClick = onItemLongPress?.let { { it(item) } },
                        ),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .thumbnailBorder(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                    ) {
                        AsyncImage(
                            model = item.thumbnailUrl.artworkAt(CARD_ART_PX),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                        if (showRankBadge && !item.customIndex.isNullOrBlank()) {
                            Box(
                                modifier = Modifier
                                    .padding(8.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Color.Black.copy(alpha = 0.72f))
                                    .padding(horizontal = 7.dp, vertical = 3.dp),
                            ) {
                                Text(
                                    text = "#${item.customIndex}",
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        fontWeight = FontWeight.Bold,
                                    ),
                                    color = Color.White,
                                )
                            }
                        }
                        if (!item.badge.isNullOrBlank()) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.BottomStart)
                                    .padding(8.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(MaterialTheme.colorScheme.primary)
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                            ) {
                                Text(
                                    text = item.badge,
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = FontWeight.Bold,
                                    ),
                                    color = MaterialTheme.colorScheme.onPrimary,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                        ),
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = item.subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * Smooth Explore Loading Skeleton matching the real page layout.
 * Eliminates hero layout shifts and empty gray boxes.
 */
@Composable
private fun ExploreSkeleton() {
    Column(modifier = Modifier.fillMaxWidth()) {
        // Nav Pills Shimmer
        LazyRow(
            contentPadding = PaddingValues(horizontal = PAGE_GUTTER, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            userScrollEnabled = false,
        ) {
            items(4) {
                ShimmerBox(
                    modifier = Modifier.width(112.dp).height(40.dp),
                    shape = CircleShape,
                )
            }
        }
        Spacer(Modifier.height(18.dp))

        // Shelf 1 (Top Songs Ranked Multi-Row Shimmer)
        Column(Modifier.padding(bottom = 26.dp)) {
            Box(Modifier.padding(horizontal = PAGE_GUTTER, vertical = 8.dp)) {
                ShimmerBox(Modifier.width(160.dp).height(20.dp), RoundedCornerShape(4.dp))
            }
            LazyRow(
                contentPadding = PaddingValues(horizontal = PAGE_GUTTER),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                userScrollEnabled = false,
            ) {
                items(2) {
                    Column(
                        modifier = Modifier.width(310.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        repeat(4) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                ShimmerBox(Modifier.width(28.dp).height(20.dp), RoundedCornerShape(4.dp))
                                Spacer(Modifier.width(8.dp))
                                ShimmerBox(Modifier.size(48.dp), RoundedCornerShape(6.dp))
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    ShimmerBox(Modifier.fillMaxWidth(0.85f).height(14.dp), RoundedCornerShape(4.dp))
                                    Spacer(Modifier.height(6.dp))
                                    ShimmerBox(Modifier.fillMaxWidth(0.55f).height(12.dp), RoundedCornerShape(4.dp))
                                }
                            }
                        }
                    }
                }
            }
        }

        // Shelf 2 (Videos 16:9 Widescreen Cards)
        Column(Modifier.padding(bottom = 26.dp)) {
            Box(Modifier.padding(horizontal = PAGE_GUTTER, vertical = 8.dp)) {
                ShimmerBox(Modifier.width(180.dp).height(20.dp), RoundedCornerShape(4.dp))
            }
            LazyRow(
                contentPadding = PaddingValues(horizontal = PAGE_GUTTER),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                userScrollEnabled = false,
            ) {
                items(2) {
                    Column(Modifier.width(250.dp)) {
                        ShimmerBox(
                            Modifier.fillMaxWidth().aspectRatio(16f / 9f),
                            RoundedCornerShape(12.dp),
                        )
                        Spacer(Modifier.height(10.dp))
                        ShimmerBox(Modifier.fillMaxWidth(0.85f).height(14.dp), RoundedCornerShape(4.dp))
                        Spacer(Modifier.height(6.dp))
                        ShimmerBox(Modifier.fillMaxWidth(0.55f).height(12.dp), RoundedCornerShape(4.dp))
                    }
                }
            }
        }

        // Shelf 3 (Moods & Genres Grid Tiles)
        Column(Modifier.padding(bottom = 26.dp)) {
            Box(Modifier.padding(horizontal = PAGE_GUTTER, vertical = 8.dp)) {
                ShimmerBox(Modifier.width(150.dp).height(20.dp), RoundedCornerShape(4.dp))
            }
            LazyRow(
                contentPadding = PaddingValues(horizontal = PAGE_GUTTER),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                userScrollEnabled = false,
            ) {
                items(3) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        ShimmerBox(Modifier.width(168.dp).height(52.dp), RoundedCornerShape(12.dp))
                        ShimmerBox(Modifier.width(168.dp).height(52.dp), RoundedCornerShape(12.dp))
                    }
                }
            }
        }
    }
}
