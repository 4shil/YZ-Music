package com.music.yzmusic.ui.replay

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.ColorUtils
import coil3.compose.AsyncImage
import com.music.yzmusic.R
import com.music.yzmusic.data.model.CARD_ART_PX
import com.music.yzmusic.data.model.HEADER_ART_PX
import com.music.yzmusic.data.model.ROW_ART_PX
import com.music.yzmusic.data.model.artworkAt
import com.music.yzmusic.data.stats.ReplaySummary
import com.music.yzmusic.ui.player.MeshGradientBackground
import com.music.yzmusic.ui.player.MeshPalette
import com.music.yzmusic.ui.player.rememberArtworkColors
import com.music.yzmusic.ui.theme.AccentRed
import kotlinx.coroutines.launch

/**
 * The Replay as a run of full-screen cards you tap through.
 *
 * ## Why a story rather than a scroll
 *
 * The page below this already has every number on it, ranked and scrollable, and
 * that is the right shape for looking something up. It is the wrong shape for
 * being *told* something: a scroll gives every fact the same weight and lets the
 * eye run past the one that matters. A story gives each fact the whole screen
 * and a few seconds of nobody else's attention.
 *
 * ## The layout
 *
 * Every card is the same three bands, which is what makes eight of them read as
 * one piece rather than eight designs: a sentence at the top with the number in
 * it set bold, the thing that sentence is about in the middle, and the share
 * button at the bottom. The sentence carries the meaning, so a card that has
 * lost its artwork to a slow connection still says something.
 *
 * ## The mechanics people already know
 *
 * Tap the right of the screen to move on, the left to go back, hold anywhere to
 * stop the clock, swipe to move by hand, back or the cross to leave. None of it
 * is explained on screen: it is the same everywhere, and explaining it would be
 * the first thing on a page whose whole job is to be effortless.
 *
 * The auto-advance stops at the last card rather than closing — closing on a
 * timer takes the share button away from someone reaching for it.
 */
@Composable
fun ReplayStories(
    summary: ReplaySummary,
    start: ReplayStoryPage,
    onClose: () -> Unit,
    /** Shares the card that was on screen when the button was pressed. */
    onShare: (ReplayStoryPage) -> Unit,
    /**
     * Holds the clock while something is up in front of the story.
     *
     * The share sheet is the case: it covers the lower half of the card, takes a
     * second to draw its picture, and behind it the story was still counting
     * down — so by the time anyone had chosen an app, the card they were sending
     * was two cards further on than the one on screen.
     */
    paused: Boolean = false,
) {
    // A category with nothing in it has no card: an empty page in the middle of
    // a story reads as the app having lost something.
    val pages = remember(summary) {
        ReplayStoryPage.ordered.filter { page ->
            when (page) {
                ReplayStoryPage.ALBUMS -> summary.albums.isNotEmpty()
                ReplayStoryPage.GENRES -> summary.genres.isNotEmpty()
                else -> true
            }
        }
    }
    val pagerState = rememberPagerState(
        initialPage = pages.indexOf(start).coerceAtLeast(0),
        pageCount = { pages.size },
    )
    val scope = rememberCoroutineScope()
    var held by remember { mutableStateOf(false) }
    val progress = remember { Animatable(0f) }
    val current by remember { derivedStateOf { pagerState.currentPage } }

    /**
     * Where a tap sends the story.
     *
     * Two things here are the fix for two separate bugs, and both come from a
     * tap and the auto-advance being able to move the pager at the same time.
     *
     *  - **The step is taken from [PagerState.settledPage], not `currentPage`.**
     *    `currentPage` flips to the destination halfway through a scroll, so a
     *    tap landing during the auto-advance was computing "next" from the page
     *    the story was already on its way to — and skipping one. `settledPage`
     *    is the last page that actually came to rest, so the worst a mistimed
     *    tap can do is ask for the transition already in flight.
     *  - **It snaps rather than animating.** An `animateScrollToPage` that gets
     *    cancelled mid-flight — which is exactly what a second scroll request
     *    does to it — leaves the pager wherever it had got to, which is the
     *    story sitting between two cards with neither readable. A snap has no
     *    in-between state to be interrupted in. It is also what every story
     *    player does: tapping is meant to feel like turning a page, not like
     *    starting a transition.
     */
    fun goTo(target: Int, animate: Boolean) {
