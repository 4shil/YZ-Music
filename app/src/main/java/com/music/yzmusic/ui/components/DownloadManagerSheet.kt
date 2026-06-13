package com.music.yzmusic.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Downloading
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.music.yzmusic.R
import com.music.yzmusic.data.model.ROW_ART_PX
import com.music.yzmusic.data.model.artworkAt
import com.music.yzmusic.download.DownloadProgress
import com.music.yzmusic.download.DownloadSession
import com.music.yzmusic.download.Downloads
import com.music.yzmusic.ui.haptics.Haptic
import com.music.yzmusic.ui.haptics.rememberHaptics

/**
 * The download indicator in the top bar, beside the account photo.
 *
 * Absent until something is actually downloading, and absent again once the user
 * has looked at the result — neither of which is this composable's decision.
 * [DownloadSession.State.visible] owns both, because "should this be on screen"
 * is a question about a batch of work rather than about a bar, and the same
 * answer has to hold whichever page is showing.
 *
 * It draws the batch's overall progress rather than the running track's. The
 * track is what the notification reports on; from here the interesting number is
 * how much of the *album* is left, and a ring that restarts from zero forty
 * times says nothing about that. Once the queue is quiet the ring is dropped for
 * a tick or a warning — a full circle and a finished job look identical, and only
 * one of them is worth walking over to.
 */
@Composable
fun TopBarDownloadButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val session by DownloadSession.state.collectAsStateWithLifecycle()
    if (!session.visible) return

    val haptics = rememberHaptics()
    // Animated, because the fraction lands in steps — one track at a time, plus
    // whatever the running one reports — and a ring that jumps in twenty-fifths
    // reads as a stutter rather than as progress.
    val progress by animateFloatAsState(
        targetValue = session.fraction,
        animationSpec = tween(300),
        label = "downloadRingProgress",
    )
    val failed = session.failed > 0
    val tint by animateColorAsState(
        targetValue = when {
            failed -> MaterialTheme.colorScheme.error
            else -> MaterialTheme.colorScheme.primary
        },
        animationSpec = tween(220),
        label = "downloadRingTint",
    )

    IconButton(
        onClick = {
            haptics.play(Haptic.Select)
            onClick()
        },
        modifier = modifier,
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (session.busy) {
                CircularProgressIndicator(
                    // Never quite zero: a ring pinned at nothing reads as
                    // stalled where the first sliver reads as starting.
                    progress = { progress.coerceAtLeast(0.02f) },
                    modifier = Modifier.size(RING_SIZE),
                    color = tint,
                    trackColor = tint.copy(alpha = 0.22f),
                    strokeWidth = 2.dp,
                    strokeCap = StrokeCap.Round,
                    gapSize = 0.dp,
                )
            }
            Icon(
                imageVector = when {
                    failed -> Icons.Rounded.ErrorOutline
                    session.busy -> Icons.Rounded.Downloading
                    else -> Icons.Rounded.DownloadDone
                },
                contentDescription = when {
                    session.busy -> "Downloads · ${(session.fraction * 100).toInt()}%"
                    failed -> "Downloads · ${session.failed} failed"
                    else -> "Downloads · finished"
                },
                tint = tint,
                modifier = Modifier.size(if (session.busy) GLYPH_IN_RING else GLYPH_SIZE),
            )
        }
    }
}

/**
 * The list behind that indicator: every track asked for this session, what
 * became of it, and a way out of the ones still going.
 *
 * A list rather than a single line because the thing being reported on is a
 * batch. `SongActionsSheet`'s download row already answers "what about *this*
 * song" perfectly well and is the right size for that question; the question
 * here is the one it cannot answer — forty tracks were asked for, which of them
 * arrived — and that has as many answers as there were tracks.
 *
 * Rows carry the cover, the title and the credit because a filename is not how
 * anybody remembers a song, and because a batch download is precisely when a
 * user cannot tell from a name whether the right thing is being fetched: the
 * track that fails is one of forty and the only way to recognise it is to see it.
 *
 * @param onDismiss closes the sheet. Called by the header's own control rather
 *   than left to the drag, so there is something obvious to press once the list
 *   is read — and the host marks the batch seen on the way out, which is what
 *   takes the indicator down.
 */
@Composable
fun DownloadManagerSheet(onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val session by DownloadSession.state.collectAsStateWithLifecycle()
    // Newest ask last, the order the queue will actually reach them in.
    val items = remember(session.items) { session.items.sortedBy { it.sequence } }

    Column(modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 8.dp, top = 4.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.downloads),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    text = session.summary(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (session.failed > 0 && !session.busy) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            if (session.busy) {
                TextButton(
                    onClick = {
                        // Only what is still going. Cancelling a finished row
                        // would drop it from the list, which is the one thing
                        // this sheet exists to still be showing.
                        items.filterNot { it.progress.settled }
                            .forEach { Downloads.cancel(it.videoId) }
                    },
                ) {
                    Text(stringResource(R.string.cancel_all))
                }
            } else {
                TextButton(
                    onClick = {
                        DownloadSession.clear()
                        onDismiss()
                    },
                ) {
                    Text(stringResource(R.string.clear))
                }
            }
        }

        // The batch's own bar, under the heading it belongs to. The per-row bars
        // below are about one track each; this is the one that answers "how much
        // longer", which is what someone opening this sheet mid-album wants.
        if (session.busy) {
            LinearProgressIndicator(
                progress = { session.fraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .height(3.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                strokeCap = StrokeCap.Round,
                gapSize = 0.dp,
                drawStopIndicator = {},
            )
            Spacer(Modifier.height(8.dp))
        }

        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outline)

        // Capped rather than left to grow: a hundred-track playlist would
        // otherwise be a sheet that covers the screen and has to be scrolled
        // back up before anything else can be reached.
        LazyColumn(Modifier.heightIn(max = LIST_MAX_HEIGHT)) {
            items(items, key = { it.videoId }) { item ->
                DownloadManagerRow(
                    item = item,
                    onCancel = { Downloads.cancel(item.videoId) },
                    onRetry = { Downloads.enqueue(context, item.song, item.from) },
                )
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** One track: its cover, what it is, and where it has got to. */
@Composable
