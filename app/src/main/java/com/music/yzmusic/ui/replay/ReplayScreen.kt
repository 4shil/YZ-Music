package com.music.yzmusic.ui.replay

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.music.yzmusic.R
import com.music.yzmusic.data.model.ROW_ART_PX
import com.music.yzmusic.data.model.Song
import com.music.yzmusic.data.model.artworkAt
import com.music.yzmusic.data.stats.ArtistFacts
import com.music.yzmusic.data.stats.ReplayPeriod
import com.music.yzmusic.data.stats.ReplaySummary
import com.music.yzmusic.ui.components.PAGE_GUTTER
import com.music.yzmusic.ui.icons.YZMusicIcons
import com.music.yzmusic.ui.player.MeshGradientBackground
import com.music.yzmusic.ui.player.rememberArtworkColors
import com.music.yzmusic.ui.theme.AccentRed

/**
 * The Replay page: four cards, four charts and a way to share the lot.
 *
 * ## Two ways in to the same numbers
 *
 * The cards along the top are the *story* — one fact each, tappable, and what
 * anyone who opened this page out of curiosity actually wants. Everything under
 * them is the *table* — the same four categories ranked out to ten, for the
 * person who wants to know what came fourth. Wrapped-style apps usually ship
 * only the first and leave the second to a support article; the ranked lists
 * cost a scroll and answer every follow-up question the cards provoke.
 *
 * The Library page carries the *first* of those cards — the minutes — on its
 * own, as the way in. One card there is an invitation; four is a second copy of
 * this page's opening on a page that is about something else.
 *
 * ## Why the page is washed in the top song's colours
 *
 * The mesh backdrop is the player's, and that is the point: this page is about
 * one particular year of listening, and running it in the colours of the record
 * that defined that year ties the two together without a line of copy saying so.
 * It also means two people's Replays do not look alike, which a fixed brand
 * gradient could never manage.
 */
@Composable
fun ReplayScreen(
    state: ReplayState,
    /**
     * The name on the cards — the signed-in account's, or blank for a guest,
     * where [DEFAULT_HOLDER] stands in.
     */
    holder: String,
    onPeriodChange: (ReplayPeriod) -> Unit,
    onOpenStory: (ReplayStoryPage) -> Unit,
    onPlaySong: (Song) -> Unit,
    /**
     * Opens the artist's page. The browse id is null for most rows — an artist
     * is counted by name, and the id only rides along when the track that
     * credited them carried one — so the caller has to be able to find the page
     * from the name alone. Same for the album below.
     */
    onOpenArtist: (String?, String) -> Unit,
    onOpenAlbum: (String?, String, String?, String?) -> Unit,
    onShare: () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
) {
    val summary = state.summary
    val leadArtwork = summary?.songs?.firstOrNull()?.song?.thumbnailUrl
    val palette = rememberArtworkColors(leadArtwork)
    val topSongs = stringResource(R.string.top_songs)
    val topArtists = stringResource(R.string.top_artists)
    val topAlbums = stringResource(R.string.top_albums)
    val topGenres = stringResource(R.string.top_genres)

    Box(modifier.fillMaxSize()) {
        MeshGradientBackground(palette = palette, trackKey = leadArtwork, animated = false)
        // The mesh is built to sit behind a player, where the only thing over it
        // is a handful of large controls. A page of ranked lists needs a good
        // deal more separation than that, so most of it is put back under ink.
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color.Black.copy(alpha = 0.30f),
                            Color.Black.copy(alpha = 0.72f),
                            Color.Black.copy(alpha = 0.88f),
                        ),
                    ),
                ),
        )

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding,
        ) {
            item("heading") { Heading(state, onPeriodChange) }

            when {
                state.loading && summary == null -> item("loading") {
                    Box(Modifier.fillMaxWidth().padding(64.dp), Alignment.Center) {
                        CircularProgressIndicator(color = Color.White.copy(alpha = 0.6f))
                    }
                }
                summary == null || summary.isEmpty -> item("empty") { EmptyReplay(state.period) }
                else -> {
                    item("cards") {
                        ReplayCardRow(
                            cards = summary.cards(),
                            holder = holder,
                            memberSince = state.memberSince,
                            onOpenStory = onOpenStory,
                        )
                    }
                    item("open") {
                        ReplayActionRow(YZMusicIcons.Play, "Play your Replay") {
                            onOpenStory(ReplayStoryPage.INTRO)
                        }
                    }

                    chart(
                        key = "songs",
                        title = topSongs,
                        rows = summary.songRows(CHART_LENGTH),
                        onClick = { index ->
                            summary.songs.getOrNull(index)?.let { onPlaySong(it.song) }
                        },
                    )
                    chart(
                        key = "artists",
                        title = topArtists,
                        rows = summary.artistRows(CHART_LENGTH),
                        circular = true,
                        onClick = { index ->
                            val artist = summary.artists.getOrNull(index) ?: return@chart
                            onOpenArtist(artist.browseId, artist.title)
                        },
                    )
                    chart(
                        key = "albums",
                        title = topAlbums,
                        rows = summary.albumRows(CHART_LENGTH),
                        onClick = { index ->
                            val album = summary.albums.getOrNull(index) ?: return@chart
                            onOpenAlbum(album.browseId, album.title, album.subtitle, album.artworkUrl)
                        },
                    )
                    if (summary.genres.isNotEmpty()) {
                        chart(
                            key = "genres",
                            title = topGenres,
                            rows = summary.genreRows(CHART_LENGTH),
                            onClick = {},
                        )
                    } else if (ArtistFacts.genresAvailable) {
                        item("genres-pending") {
                            Note(
                                text = "Genres are still being worked out. They fill in " +
                                    "as you listen, and the chart appears once there is " +
                                    "enough to rank.",
                                modifier = Modifier.padding(horizontal = PAGE_GUTTER + 10.dp),
                            )
                        }
                    }

                    item("habits") { Habits(summary) }
                    item("share") {
                        ReplayActionRow(Icons.Rounded.IosShare, "Share my Replay", onShare)
                    }
                }
            }
        }
    }
}

// ── Heading ─────────────────────────────────────────────────────────────────

@Composable
private fun Heading(state: ReplayState, onPeriodChange: (ReplayPeriod) -> Unit) {
    Column(Modifier.padding(horizontal = PAGE_GUTTER + 10.dp)) {
        // Clear of the fade under the top bar, which runs a good way past the
        // bar itself — see [TopFadeBlur]. A release page has its sleeve up here
        // and is meant to be blurred; this page leads with type, and type read
        // through a blur reads as a rendering fault.
        //
        // Only far enough to reach the weak tail of that fade, not past the
        // whole of it: the ramp eases out, so almost all of the blur is in its
        // first third and clearing that is enough to keep the title crisp. The
        // rest was a screen's worth of nothing above the heading.
        Spacer(Modifier.height(48.dp))
        Text(
            text = "Replay",
            style = MaterialTheme.typography.displayLarge,
            fontWeight = FontWeight.W800,
            color = Color.White,
        )
        Text(
            text = state.summary?.label ?: state.period.chip,
            style = MaterialTheme.typography.titleMedium,
            color = Color.White.copy(alpha = 0.6f),
        )
        Spacer(Modifier.height(14.dp))
        PeriodPicker(state.period, onPeriodChange)
        Spacer(Modifier.height(18.dp))
    }
}

/**
 * The three stretches a Replay can cover.
 *
 * Months and years rather than a date range, because that is the granularity the
 * listening is actually kept at — see [com.music.yzmusic.data.stats.ListeningStats].
 * A "last 30 days" chip would have to be answered from monthly totals, which
 * would make it a lie for the first thirty days of every month.
 */
@Composable
private fun PeriodPicker(selected: ReplayPeriod, onSelect: (ReplayPeriod) -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.10f))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        ReplayPeriod.entries.forEach { period ->
            val active = period == selected
            val background by animateColorAsState(
                if (active) Color.White.copy(alpha = 0.92f) else Color.Transparent,
                tween(160),
                label = "periodChip",
            )
            Text(
                text = period.chip,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.W700,
                color = if (active) Color.Black else Color.White.copy(alpha = 0.75f),
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(background)
                    .clickable { onSelect(period) }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }
}

// ── The cards ───────────────────────────────────────────────────────────────

/**
 * The wallet of cards, wherever it is drawn.
 *
 * It lives on the Library page rather than here — see [ReplayScreen]'s note on
 * where each half of Replay belongs — but it is defined alongside the page it
 * summarises, because the two have to keep saying the same thing.
 */
@Composable
private fun ReplayCardRow(
    cards: List<ReplayHeroCard>,
    holder: String,
    memberSince: String?,
    onOpenStory: (ReplayStoryPage) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = PAGE_GUTTER + 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(cards, key = { it.label }) { card ->
            ReplayCreditCard(
                label = card.label,
                value = card.value,
                detail = card.detail,
                artworkUrl = card.artworkUrl,
                holder = holder,
                memberSince = memberSince,
                onClick = { onOpenStory(card.page) },
                modifier = Modifier.width(300.dp),
            )
        }
    }
}

/**
 * The two things this page can do, drawn the same way.
 *
 * They sit at either end of it and are the same kind of act — open the Replay,
 * send the Replay — so they have no business looking unalike. The share button
 * used to be a filled red slab, which on a page with no destructive control on
 * it was the loudest thing there and read like one.
 */
@Composable
