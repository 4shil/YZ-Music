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
