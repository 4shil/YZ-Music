package com.music.yzmusic.ui.components

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.music.yzmusic.data.model.ROW_ART_PX
import com.music.yzmusic.data.model.Song
import com.music.yzmusic.data.model.artworkAt
import com.music.yzmusic.data.settings.AppSettings
import com.music.yzmusic.ui.components.thumbnailBorder
import com.music.yzmusic.ui.haptics.Haptic
import com.music.yzmusic.ui.haptics.rememberHaptics
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials
import kotlinx.coroutines.launch

/**
 * The transport buttons' touch target. Material's default 48dp is what a bar
 * this slim is really made of, so it sets the height on its own.
 */
private val GLYPH_SLOT = 40.dp

/**
 * The play and skip glyphs themselves.
 *
 * Deliberately grown inside [GLYPH_SLOT] rather than by growing the slot: the
 * slot is level with the 40dp artwork opposite it, and it is the taller of the
 * two that sets the row's height — so a bigger slot would make the whole bar
 * taller, which is not what a bigger glyph is being asked for. At 32 there is
 * still 4dp of clearance to the slot's edge on every side.
 */
private val GLYPH_SIZE = 32.dp

/** The spinner that stands in for the play glyph, kept in proportion to it. */
private val SPINNER_SIZE = 22.dp

/**
 * The gap between the two transport controls.
 *
 * Material asks for at least 8dp between adjacent touch targets, and these had
 * none: two [GLYPH_SLOT] boxes sharing an edge, so the boundary between "pause"
 * and "skip" was a line with nothing either side of it. What space there looked
 * to be was only the margin each glyph keeps inside its own slot, and a thumb
 * lands on a target's edge far more often than it lands on a glyph's.
 *
 * Taken from the title's width rather than the bar's height, so nothing above
 * or below it moves.
 */
private val TRANSPORT_GAP = 8.dp

/**
 * Vertical padding, which with the 40dp artwork sets the bar's height at 56dp
 * and so its pill radius at 28.
 */
private val ROW_PADDING_VERTICAL = 8.dp

/**
 * Horizontal padding, deliberately larger than the vertical.
 *
 * A pill's ends are semicircles, so the edge nearest the artwork is not the
 * one beside it but the one curving away above and below it. At the artwork's
 * top corner that edge has already come 8.4dp in from the left — level with
 * where square corners would have put the whole side. Padding the ends by the
 * vertical figure would leave the artwork touching the curve; 12 clears it
 * with room, and reads as centred rather than jammed into the round.
 */
private val ROW_PADDING_HORIZONTAL = 12.dp

/**
 * The artwork's corner, on the 8dp every other thumbnail in the app carries.
 *
 * It used to be 7, picked so the bar's corner could sit concentric with it.
 * A pill has no corner to be concentric with — its radius is whatever half the
 * height happens to be — so that constraint is gone and the artwork can go
 * back to matching [SongRow].
 */
private val ART_CORNER = 8.dp

/** Frosted mini player that rides just above the floating tab bar. */
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun MiniPlayer(
    song: Song,
    isPlaying: Boolean,
    isLoading: Boolean,
    hazeState: HazeState,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit = {},
    onExpand: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val reduceDynamicBlur by AppSettings.reduceDynamicBlur.collectAsStateWithLifecycle()
    val haptics = rememberHaptics()
    val density = LocalDensity.current
    val touchSlop = LocalViewConfiguration.current.touchSlop
    val minDisplacement = remember(density) { with(density) { 48.dp.toPx() } }
    val minVelocity = remember(density) { with(density) { 500.dp.toPx() } }
    val maxDragPx = remember(density) { with(density) { 120.dp.toPx() } }
    val coroutineScope = rememberCoroutineScope()
    val offsetX = remember { Animatable(0f) }
    var lastActionTime by remember { mutableLongStateOf(0L) }
    val classifier = remember(touchSlop, minDisplacement, minVelocity) {
        MiniPlayerGestureClassifier(
            touchSlopPx = touchSlop,
            minDisplacementPx = minDisplacement,
            minVelocityPx = minVelocity,
        )
    }

    LaunchedEffect(song.videoId) {
        if (offsetX.value != 0f) {
            offsetX.snapTo(0f)
        }
    }

    val gestureModifier = Modifier.pointerInput(song.videoId) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val velocityTracker = VelocityTracker()
            velocityTracker.addPosition(down.uptimeMillis, down.position)
            var totalX = 0f
            var totalY = 0f
            classifier.reset()

            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) {
                    velocityTracker.addPosition(change.uptimeMillis, change.position)
                    break
                }
                velocityTracker.addPosition(change.uptimeMillis, change.position)
                val delta = change.positionChange()
                totalX += delta.x
                totalY += delta.y

                val lock = classifier.onMove(totalX, totalY)
                if (lock == DirectionLock.HORIZONTAL) {
                    change.consume()
                    coroutineScope.launch {
                        offsetX.snapTo((totalX * 0.65f).coerceIn(-maxDragPx, maxDragPx))
                    }
                }
            }

            val velocity = velocityTracker.calculateVelocity()
            val action = classifier.onRelease(totalX, totalY, velocity.x, velocity.y)
            val now = SystemClock.uptimeMillis()
            val canAct = (now - lastActionTime) >= 300L

            when (action) {
                MiniPlayerAction.NEXT -> {
                    if (canAct) {
                        lastActionTime = now
                        haptics.play(Haptic.SkipNext)
                        onNext()
                    }
                    coroutineScope.launch {
                        offsetX.animateTo(
                            targetValue = 0f,
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioLowBouncy,
                                stiffness = Spring.StiffnessMedium,
                            ),
                        )
                    }
                }
                MiniPlayerAction.PREVIOUS -> {
                    if (canAct) {
                        lastActionTime = now
                        haptics.play(Haptic.SkipNext)
                        onPrevious()
                    }
                    coroutineScope.launch {
                        offsetX.animateTo(
                            targetValue = 0f,
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioLowBouncy,
                                stiffness = Spring.StiffnessMedium,
                            ),
                        )
                    }
                }
                MiniPlayerAction.EXPAND -> {
                    if (canAct) {
                        lastActionTime = now
                        onExpand()
                    }
                    if (offsetX.value != 0f) {
                        coroutineScope.launch {
                            offsetX.animateTo(
                                targetValue = 0f,
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioLowBouncy,
                                    stiffness = Spring.StiffnessMedium,
                                ),
                            )
                        }
                    }
                }
                MiniPlayerAction.NONE -> {
                    if (offsetX.value != 0f) {
                        coroutineScope.launch {
                            offsetX.animateTo(
                                targetValue = 0f,
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioLowBouncy,
                                    stiffness = Spring.StiffnessMedium,
                                ),
                            )
                        }
                    }
                }
            }
        }
    }

    // percent rather than a dp figure, so the corner stays exactly half the
    // height if the row's contents ever change it — which is what keeps a pill
    // a pill instead of a rounded rectangle. Same idiom as [FloatingBottomBar]
    // directly below it, so the two shapes are the same family.
    val shape = RoundedCornerShape(percent = 50)
    Box(
        modifier = modifier
            .padding(horizontal = PAGE_GUTTER)
            .clip(shape)
            .then(
                if (reduceDynamicBlur) {
                    Modifier.background(MaterialTheme.colorScheme.surface)
                } else {
                    Modifier.hazeEffect(state = hazeState, style = HazeMaterials.thin(MaterialTheme.colorScheme.surface))
                },
            )
            .border(0.5.dp, Color.White.copy(alpha = 0.10f), shape)
            .semantics {
                customActions = listOf(
                    CustomAccessibilityAction("Next track") {
                        onNext()
                        true
                    },
                    CustomAccessibilityAction("Previous track") {
                        onPrevious()
                        true
                    },
                )
            }
            .then(gestureModifier)
            // Deliberately silent: the whole bar is the target, so it catches
            // stray taps meant for the page behind it, and the sheet rising is
            // its own confirmation. The glyphs on it still buzz.
            .clickable(onClick = onExpand),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer {
                    translationX = offsetX.value
                }
                .padding(
                    horizontal = ROW_PADDING_HORIZONTAL,
                    vertical = ROW_PADDING_VERTICAL,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AsyncImage(
                model = song.artworkAt(ROW_ART_PX),
                contentDescription = null,
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(ART_CORNER))
                    .thumbnailBorder(RoundedCornerShape(ART_CORNER))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = song.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = song.artist,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (isLoading) {
                Box(Modifier.size(GLYPH_SLOT), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.onBackground,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(SPINNER_SIZE),
                    )
                }
            } else {
                IconButton(
                    onClick = {
                        haptics.play(if (isPlaying) Haptic.Pause else Haptic.Resume)
                        onPlayPause()
                    },
                    modifier = Modifier.size(GLYPH_SLOT),
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = if (isPlaying) "Pause" else "Play",
                        tint = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.size(GLYPH_SIZE),
                    )
                }
            }
            Spacer(Modifier.width(TRANSPORT_GAP))
            IconButton(
                onClick = {
                    haptics.play(Haptic.SkipNext)
                    onNext()
                },
                modifier = Modifier.size(GLYPH_SLOT),
            ) {
                Icon(
                    Icons.Rounded.SkipNext,
                    contentDescription = "Next",
                    tint = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.size(GLYPH_SIZE),
                )
            }
        }
    }
}
