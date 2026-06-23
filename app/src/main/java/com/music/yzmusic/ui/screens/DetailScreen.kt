package com.music.yzmusic.ui.screens

import androidx.compose.animation.animateContentSize
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.border
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials
import com.music.yzmusic.data.canvas.CanvasArtwork
import com.music.yzmusic.data.canvas.CanvasRepository
import com.music.yzmusic.data.model.BrowseType
import com.music.yzmusic.data.model.DetailPage
import com.music.yzmusic.data.model.CARD_ART_PX
import com.music.yzmusic.data.model.HEADER_ART_PX
import com.music.yzmusic.data.model.ROW_ART_PX
import com.music.yzmusic.data.model.ShelfItem
import com.music.yzmusic.data.model.Song
import com.music.yzmusic.data.model.UiState
import com.music.yzmusic.data.model.artworkAt
import com.music.yzmusic.data.settings.AppSettings
import com.music.yzmusic.ui.components.ArtworkWash
import com.music.yzmusic.ui.components.DownloadedBadge
import com.music.yzmusic.ui.components.MessageState
import com.music.yzmusic.ui.components.PAGE_GUTTER
import com.music.yzmusic.ui.components.ROW_DIVIDER_INSET
import com.music.yzmusic.ui.components.SHELF_CARD_WIDTH
import com.music.yzmusic.ui.components.SongRow
import com.music.yzmusic.ui.components.thumbnailBorder
import com.music.yzmusic.ui.components.detailSkeleton
import com.music.yzmusic.ui.components.topBarContentPadding
import com.music.yzmusic.ui.haptics.Haptic
import com.music.yzmusic.ui.haptics.rememberHaptics
import com.music.yzmusic.ui.icons.YZMusicIcons
import com.music.yzmusic.ui.player.CanvasArtworkPlayer
import com.music.yzmusic.ui.theme.ArtworkPalette
import com.music.yzmusic.ui.theme.rememberArtworkPalette
import kotlin.math.roundToInt
import java.util.Locale

private const val MAX_ARTIST_SONGS = 20
private const val SONGS_PER_COLUMN = 4

/** The artist photo, very slightly taller than it is wide. */
private const val ARTIST_PHOTO_RATIO = 0.95f

/** A release's sleeve, given a little more height than the artist photo. */
private const val SLEEVE_RATIO = 0.92f

/** The sleeve on a release page, as a fraction of the page width. */
private const val SLEEVE_FRACTION = 0.80f

private val SLEEVE_SHAPE = RoundedCornerShape(12.dp)
private val PILL_SHAPE = RoundedCornerShape(12.dp)

/**
 * Where the search field sits once it is open — directly under the header,
 * which is item zero. The one place that has to know, so that opening the
 * search can carry the page up to it.
 */
private const val SEARCH_ITEM_INDEX = 1

/** The inset the header text and the action pills share. */
private val HEADER_GUTTER = PAGE_GUTTER + 14.dp

/**
 * How far past the foot of the artwork the title block is allowed to hang.
 *
 * Sat flush to the bottom of the picture it lands wherever the picture happens
 * to be busy, and on a sleeve with anything going on down there the title reads
 * as part of the artwork rather than as a caption to it. Dropped clear, it sits
 * on the blurred colour instead, which has nothing in it to compete.
 */
private val HEADER_DROP = 44.dp

/**
 * Album / artist / playlist page. Rendered inside the main content area
 * rather than as a sheet, so the tab bar and mini player stay visible.
 *
 * The page paints itself in the artwork's own colours — a tint behind
 * everything, the artwork itself across the top of it, and an accent taken off
 * the sleeve for the credit line and the Play/Shuffle pair. See
 * [rememberArtworkPalette] for how those are derived and kept legible.
 *
 * It is built in three layers rather than the obvious one, and the order is the
 * whole trick:
 *
 *  1. [PageBackground] — the wash and the artwork, and nothing you can read.
 *  2. [MergeBand] — one pane of glass laid across the join, blurring layer 1.
 *  3. The list — titles, buttons and rows, drawn over the glass and so sharp.
 *
 * Blurring only the artwork leaves the artwork and the page as two surfaces
 * that have been made to *resemble* each other, and the eye finds that edge
 * every time. A single blur that samples across the join has no edge to find:
 * the picture, the colour under it and the colour under the song rows are all
 * one smear of the same glass. It is the same thing [TopFadeBlur] does to the
 * head of the screen, pointed at the middle of this one.
 */
@Composable
fun DetailScreen(
    page: DetailPage,
    onSongClick: (List<Song>, Int) -> Unit,
    onSongLongPress: (Song) -> Unit,
    onSongSwipe: (Song) -> Unit,
    onShuffle: (List<Song>) -> Unit,
    onSectionItemClick: (ShelfItem) -> Unit,
    onArtistClick: (String, String) -> Unit,
    onAddSuggested: (Song) -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    /**
     * Holding one of the album cards on an artist page — the same menu the
     * shelves on every other tab open, so a release can be queued from
     * wherever it is seen rather than only from its own page.
     */
    onSectionItemLongPress: ((ShelfItem) -> Unit)? = null,
    /**
     * The header's overflow: everything this page can do to its whole track
     * list that isn't already one of the buttons beside it — downloading it
     * among them, see [com.music.yzmusic.ui.components.BrowseActionsSheet].
     * Null on the pages with no list to act on.
     */
    onMore: ((List<Song>) -> Unit)? = null,
    /**
     * Saves this release to the account's library, or takes it out —
     * [DetailPage.library] says which way round. Null hides the control
     * entirely, which is the answer for a guest and for the pages YouTube never
     * offers to save; there is nothing to show a signed-out user here that
     * wouldn't just be refused.
     */
    onToggleLibrary: (() -> Unit)? = null,
) {
    val songs = (page.songs as? UiState.Success)?.data.orEmpty()
    val isArtist = page.type == BrowseType.ARTIST
    val palette = rememberArtworkPalette(page.thumbnailUrl)

    // Narrowing the running order in place — the release equivalent of the
    // filter box on the Local Music tab, and the one thing a long track list
    // needs that scrolling can't give it. Off by default and reset with the
    // page: a filter left on an album that was closed and reopened would be a
    // page that appears to have lost most of its tracks.
    var searching by rememberSaveable(page.browseId) { mutableStateOf(false) }
    var query by rememberSaveable(page.browseId) { mutableStateOf("") }
    // Whether the field still owes the keyboard an appearance. Held here rather
    // than in the field, which is a row in a lazy list: scrolled out of sight it
    // is disposed, and a field that asks for focus every time it is composed
    // would throw the keyboard back up each time it scrolled into view.
    var focusSearch by remember(page.browseId) { mutableStateOf(false) }
    val closeSearch = {
        searching = false
        query = ""
    }
    // Back closes the search first — this handler is registered after the one
    // that pops the page, so it is the one that answers while it's enabled.
    BackHandler(enabled = searching) { closeSearch() }

    // Each surviving row still knows where it sat in the full running order, so
    // an album's track numbers stay the album's rather than becoming positions
    // in the filtered list.
    val matches = remember(songs, query) { songs.matching(query) }
    // What a tap plays: the list as it is being read. Playing the whole release
    // from a filtered row would start a queue the user cannot see.
