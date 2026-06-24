package com.music.yzmusic.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.yzmusic.data.YtMusicRepository
import com.music.yzmusic.data.model.HomeShelf
import com.music.yzmusic.R
import com.music.yzmusic.data.model.LibraryPage
import com.music.yzmusic.data.model.ShelfItem
import com.music.yzmusic.data.model.UiState
import com.music.yzmusic.data.settings.AppSettings
import com.music.yzmusic.download.Downloads
import com.music.yzmusic.download.SavedCollection
import com.music.yzmusic.ui.icons.YZMusicIcons
import com.music.yzmusic.ui.components.LIBRARY_GRID_SPACING
import com.music.yzmusic.ui.components.MessageState
import com.music.yzmusic.ui.components.PAGE_GUTTER
import com.music.yzmusic.ui.components.PullToRefresh
import com.music.yzmusic.ui.components.SHELF_CARD_WIDTH
import com.music.yzmusic.ui.components.libraryGrid
import com.music.yzmusic.ui.components.librarySkeleton
import com.music.yzmusic.ui.player.MeshGradientBackground
import com.music.yzmusic.ui.player.rememberArtworkColors
import com.music.yzmusic.ui.replay.ReplayHeroCard
import java.util.Locale

/**
 * The signed-in library: the saved collections, as shelves of cards.
 *
 * Deliberately only the collections. This page used to end with two runs of
 * track rows — "Liked Music" and "Songs" — which are two overlapping answers
 * to the same question and read as one list that couldn't make up its mind: a
 * track that stopped being liked didn't leave the page, it moved down it, into
 * a section most people had taken for more of the same. Liked Music is a
 * playlist, and it is reached the way every other playlist here is, by opening
 * its card.
 *
 * The liked list is still fetched — it is what the rest of the app reads a
 * track's rating off (see MainViewModel's `likeStatuses`); it just isn't a
 * second place to browse it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    signedIn: Boolean,
    state: UiState<LibraryPage>,
    listState: LazyListState,
    onShelfItemClick: (ShelfItem) -> Unit,
    onShelfItemLongPress: (ShelfItem) -> Unit,
    onNewPlaylist: () -> Unit,
    /**
     * A shelf's "Show all" — every shelf's row here stops at five cards (see
     * [LibraryGridShelf]), so this is the only way to reach whatever didn't
     * fit.
     */
    onShowAll: (HomeShelf) -> Unit,
    /**
     * The Replay's leading card — minutes listened — or null before anything has
     * been played.
     *
     * Not drawn as a card here. This page is a list of places to go, and a card
     * is an object to look at; one sitting at the top of it read as the Replay
     * page's opening reprinted on a page about playlists and downloads. What the
     * card is used for instead is its *numbers* and its *artwork*: the button
     * below says what is behind it, and is painted in the colours of the record
     * that year was mostly spent on.
     */
    replayCard: ReplayHeroCard?,
    onOpenReplay: () -> Unit,
    onSignIn: () -> Unit,
    onRetry: () -> Unit,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    pullState: PullToRefreshState,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues,
    /**
     * The playlists downloaded whole, as cards behind the two device folders.
     *
     * They belong on that shelf because they are the same promise everything
     * else on it makes — here, now, without a network. Nothing is truncated:
     * the shelf is a row that scrolls, so "all of them" costs nothing.
     *
     * Downloaded *albums* are deliberately not here. An album stamps its name
     * onto each of its tracks, so the Downloads folder's Albums tab groups it
     * back up on its own and a card here would be a second door onto the same
     * list. A playlist has no tag anything can derive it from — its tracks are
     * off forty different releases — so this is the only place it can be reached
     * without going through that folder.
     */
    downloadedPlaylists: List<SavedCollection> = emptyList(),
) {
