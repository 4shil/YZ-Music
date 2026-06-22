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
