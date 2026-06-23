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
    val queue = remember(matches) { matches.map { it.value } }
    val suggested = remember(page.suggestedSongs, query) {
        page.suggestedSongs.matching(query).map { it.value }
    }

    // What marks a row as already downloaded, tinted from the sleeve like the
    // rest of the page. Null on any page that is itself a reading of this
    // device — the Downloads folder, one downloaded playlist — where every row
    // qualifies and the badge would be decoration rather than information.
    val downloadedTint = palette.accent.takeUnless { page.browseId.startsWith("local:") }

    // Animated cover art on the header, the same feature the player has.
    // Albums only: a playlist's artwork is a collage and an artist page's is a
    // photograph, and neither is something a label publishes a canvas for.
    val canvasEnabled by AppSettings.animatedCanvas.collectAsStateWithLifecycle()
    // The credit line the header shows is the artist as far as the catalogue
    // services are concerned. A browse card's subtitle sometimes omits it, in
    // which case the tracks themselves know who it is.
    val credit = remember(page.subtitle, songs) {
        page.headerLines(songs.size).first.ifBlank { songs.firstOrNull()?.artist.orEmpty() }
    }
    var canvas by remember(page.browseId) { mutableStateOf<CanvasArtwork?>(null) }
    LaunchedEffect(page.browseId, page.title, credit, canvasEnabled) {
        if (!canvasEnabled || page.type != BrowseType.ALBUM) {
            canvas = null
            return@LaunchedEffect
        }
        // As on the player: the credit fills in once the tracks load, so this
        // can run twice. Keep a clip that is already playing if the second
        // pass comes back empty.
        canvas = CanvasRepository.canvasForAlbum(page.title, credit) ?: canvas
    }

    val pageHaze = remember { HazeState() }

    // Opening the search carries the page up to it, so the field lands just
    // clear of the frosted bar with the tracks under it rather than at the foot
    // of a screen still filled with artwork. Done as an effect rather than in
    // the tap, so the row it scrolls to is already in the list by the time it
    // runs.
    val searchStop = with(LocalDensity.current) { topBarContentPadding().roundToPx() }
    LaunchedEffect(searching) {
        if (searching) listState.animateScrollToItem(SEARCH_ITEM_INDEX, -searchStop)
    }

    BoxWithConstraints(modifier.fillMaxSize()) {
        // The artwork is drawn behind the list rather than in it, so both need
        // to agree on its height without being able to ask each other. The
        // width is the page's, so the ratio decides it and both can work it out
        // alone.
        //
        // Measured rather than read off the window, because the two are not the
        // same number everywhere: on a tablet the page is the column left over
        // once the player has its pane, and a height derived from the whole
        // window there is a sleeve half again as tall as it is wide.
        val artHeight = maxWidth / if (isArtist) ARTIST_PHOTO_RATIO else SLEEVE_RATIO

        PageBackground(
            page = page,
            palette = palette,
            canvas = canvas,
            artHeight = artHeight,
            listState = listState,
            hazeState = pageHaze,
            modifier = Modifier.matchParentSize(),
        )

        MergeBand(
            palette = palette,
            artHeight = artHeight,
            listState = listState,
            hazeState = pageHaze,
        )

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            // Both artist photos and release artwork run edge-to-edge up under
            // the glass bar — the image is the top of the page, not a card on it.
            contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding()),
        ) {
            item(key = "header") {
                if (isArtist) {
                    ArtistHeader(page = page, palette = palette, artHeight = artHeight)
                } else {
                    ReleaseHeader(
                        page = page,
                        palette = palette,
                        artHeight = artHeight,
                        trackCount = songs.size,
                        songs = songs,
                        onPlay = { onSongClick(songs, 0) },
                        onShuffle = { onShuffle(songs) },
                        searching = searching,
                        onSearch = {
                            if (searching) {
                                closeSearch()
                            } else {
                                searching = true
                                focusSearch = true
                            }
                        },
                        onMore = onMore,
                        onArtistClick = onArtistClick,
                        onToggleLibrary = onToggleLibrary,
                    )
                }
            }

            if (isArtist && (page.subscriberCountText != null || page.monthlyListenerCount != null)) {
                item(key = "artist-stats") {
                    ArtistStatsRow(
                        subscriberCountText = page.subscriberCountText,
                        monthlyListenerCount = page.monthlyListenerCount,
                        palette = palette,
                    )
                }
            }

            if (searching) {
                item(key = "search") {
                    DetailSearchField(
                        query = query,
                        onQueryChange = { query = it },
                        onClose = closeSearch,
                        autoFocus = focusSearch,
                        onFocused = { focusSearch = false },
                        palette = palette,
                        type = page.type,
                    )
                }
            }

            if (songs.isNotEmpty() && isArtist) {
                item(key = "actions") {
                    ActionRow(
                        palette = palette,
                        onPlay = { onSongClick(songs, 0) },
                        onShuffle = { onShuffle(songs) },
                        // Halved when an About section follows directly — see
                        // [AboutSection]'s own top inset, which makes up the
                        // rest of that shorter gap.
                        bottomSpace = if (page.description.isNullOrBlank()) 22.dp else 11.dp,
                    )
                }
            }

            // YouTube's own editorial blurb — an album or an artist only, per
            // [DetailPage.description]. A playlist never carries one, and the
            // section is skipped for it even on the rare response that does.
            if (!page.description.isNullOrBlank() &&
                (page.type == BrowseType.ALBUM || isArtist)
            ) {
                item(key = "about") {
                    AboutSection(
                        title = if (isArtist) "About the artist" else "About the album",
                        text = page.description,
                        palette = palette,
                    )
                }
            }

            when (val state = page.songs) {
                is UiState.Loading -> detailSkeleton(isArtist)
                is UiState.Error -> item { MessageState(state.message) }
                is UiState.Success -> if (isArtist) {
                    // An artist's full song list would bury the album shelves, so
                    // it pages sideways four at a time and stops at twenty.
                    item {
                        val top = state.data.take(MAX_ARTIST_SONGS)
                        SectionHeading("Top songs", palette)
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = PAGE_GUTTER),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(top.chunked(SONGS_PER_COLUMN)) { column ->
                                Column(Modifier.fillParentMaxWidth(0.88f)) {
                                    column.forEach { song ->
                                        CompactSongRow(
                                            song = song,
                                            palette = palette,
                                            onClick = { onSongClick(top, top.indexOf(song)) },
                                            onLongPress = { onSongLongPress(song) },
                                            downloadedTint = downloadedTint,
                                        )
                                    }
                                }
                            }
                        }
                    }
                } else {
                    // Every row on an album carries the same sleeve, which is
                    // already the largest thing on the page — Apple Music
                    // numbers those rows instead, and so does this.
                    val numbered = page.type == BrowseType.ALBUM
                    if (matches.isEmpty() && state.data.isNotEmpty()) {
                        item(key = "no-matches") {
                            MessageState("Nothing here matches “$query”")
                        }
                    }
                    itemsIndexed(matches) { position, entry ->
                        val song = entry.value
                        SongRow(
                            song = if (numbered) {
                                song
                            } else {
                                song.copy(thumbnailUrl = song.thumbnailUrl ?: page.thumbnailUrl)
                            },
                            onClick = { onSongClick(queue, position) },
                            onLongPress = { onSongLongPress(song) },
                            onSwipeToQueue = { onSongSwipe(song) },
                            rowBackground = Color.Transparent,
                            // The track's place on the release, not its place in
                            // what the filter left standing.
                            trackNumber = (entry.index + 1).takeIf { numbered },
                            subtitleColor = palette.onBackgroundVariant,
                            downloadedTint = downloadedTint,
                        )
                        if (position < matches.lastIndex) {
                            HorizontalDivider(
                                modifier = Modifier.padding(start = ROW_DIVIDER_INSET),
                                thickness = 0.5.dp,
                                color = palette.divider,
                            )
                        }
                    }
                }
            }

            // Tracks YouTube offers to round the playlist out, never folded
            // into the list above — see [DetailPage.suggestedSongs].
            if (suggested.isNotEmpty()) {
                item(key = "suggested-heading") {
                    SectionHeading("Suggested", palette)
                }
                itemsIndexed(
                    suggested,
                    key = { _, song -> "suggested-${song.videoId}" },
                ) { index, song ->
                    SuggestedSongRow(
                        song = song,
                        palette = palette,
                        onClick = { onSongClick(suggested, index) },
                        onLongPress = { onSongLongPress(song) },
                        onAdd = { onAddSuggested(song) },
                        downloadedTint = downloadedTint,
                    )
                    if (index < suggested.lastIndex) {
                        HorizontalDivider(
                            modifier = Modifier.padding(start = ROW_DIVIDER_INSET),
                            thickness = 0.5.dp,
                            color = palette.divider,
                        )
                    }
                }
            }

            // Albums / Singles & EPs carousels (artist pages).
            items(page.sections) { shelf ->
                Column(Modifier.padding(top = 22.dp)) {
                    SectionHeading(shelf.title, palette)
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = PAGE_GUTTER),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        items(shelf.items) { item ->
                            SectionCard(
                                item = item,
                                palette = palette,
                                onClick = { onSectionItemClick(item) },
                                onLongPress = onSectionItemLongPress?.let { { it(item) } },
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * An album or playlist: the title, credit, meta and action buttons that sit
 * over the foot of the artwork.
 *
 * The artwork itself is not here — [PageBackground] draws it, so that
 * [MergeBand] can blur it without blurring any of this. What this item holds in
 * its place is a spacer of exactly the picture's height, which is what keeps
 * the two in step: the list reserves the room, the background fills it.
 */
@Composable
private fun ReleaseHeader(
    page: DetailPage,
    palette: ArtworkPalette,
    artHeight: Dp,
    trackCount: Int,
    songs: List<Song>,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    searching: Boolean,
    onSearch: () -> Unit,
    onMore: ((List<Song>) -> Unit)?,
    onArtistClick: (String, String) -> Unit,
    onToggleLibrary: (() -> Unit)?,
) {
    val (credit, meta) = page.headerLines(trackCount)
    // Every row on a release carries the same credit — see [pageCredit] — so
    // the first one speaks for the whole page, the same source the rows'
    // own long-press "Open artist" already reads from.
    val artist = songs.firstOrNull()

    // The outer Box just needs to be as tall as its content — we don't force
    // an aspect ratio here so the action buttons can extend below the artwork.
    Box(Modifier.fillMaxWidth()) {

        Spacer(Modifier.fillMaxWidth().height(artHeight + HEADER_DROP))

        // Text + action row stacked, pinned to the bottom of the Box.
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(bottom = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = page.title,
                style = MaterialTheme.typography.headlineMedium,
                color = palette.onBackground,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = HEADER_GUTTER),
            )
            // Artist / credit line
            if (credit.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = credit,
                    style = MaterialTheme.typography.titleMedium,
                    color = palette.accent,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .padding(horizontal = HEADER_GUTTER)
                        .let { m ->
                            val id = artist?.artistId
                            if (id == null) {
                                m
                            } else {
                                m.clip(RoundedCornerShape(6.dp))
                                    .clickable { onArtistClick(id, artist.artist) }
                            }
                        },
                )
            }
            // Metadata (kind • year • count)
            if (meta.isNotBlank()) {
                Spacer(Modifier.height(5.dp))
                Text(
                    text = meta,
                    style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.7.sp),
                    color = palette.onBackgroundVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = HEADER_GUTTER),
                )
            }

            // Action buttons — live inside the header so there is zero gap
            // between the cover zone and the first song row.
            if (songs.isNotEmpty()) {
                // Only where YouTube said the release can be saved and the
                // caller is willing to take the write — see [onToggleLibrary].
                val library = page.library?.takeIf { onToggleLibrary != null }
                // Four circles and the pill is as much as this row can carry,
                // and on a 360dp screen it only carries it by giving something
                // up: the pill sheds padding first, being the widest thing here,
                // and the circles come down 4dp after that. The alternative is a
                // row that runs off the edge of the screen.
                val circles = listOfNotNull(library, onMore).size + 2 // + Shuffle, Search
                val full = circles >= 4
