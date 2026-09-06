package com.music.yzmusic.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.yzmusic.data.settings.AppSettings
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Applies a smooth iOS-style rubber-band bounce overscroll effect when a scrollable
 * container reaches its content boundaries. Uses spring physics to return cleanly to zero displacement.
 * Bypassed when the user has enabled [AppSettings.reduceAnimation].
 */
@Composable
fun Modifier.bouncingOverscroll(
    orientation: Orientation = Orientation.Vertical,
    dampingFactor: Float = 0.35f,
    maxOffset: Float = 300f,
): Modifier {
    val reduceMotion by AppSettings.reduceAnimation.collectAsStateWithLifecycle()
    if (reduceMotion) return this

    val scope = rememberCoroutineScope()
    val overscrollOffset = remember { Animatable(0f) }

    val connection = remember(orientation, dampingFactor, maxOffset) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                val delta = if (orientation == Orientation.Vertical) available.y else available.x
                val current = overscrollOffset.value
                if ((current > 0 && delta < 0) || (current < 0 && delta > 0)) {
                    val consumed = if (abs(delta) >= abs(current)) {
                        -current
                    } else {
                        delta
                    }
                    scope.launch {
                        overscrollOffset.snapTo(current + consumed)
                    }
                    return if (orientation == Orientation.Vertical) {
                        Offset(0f, consumed)
                    } else {
                        Offset(consumed, 0f)
                    }
                }
                return Offset.Zero
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                if (source == NestedScrollSource.UserInput) {
                    val delta = if (orientation == Orientation.Vertical) available.y else available.x
                    if (delta != 0f) {
                        val current = overscrollOffset.value
                        val resistance = (1f - (abs(current) / maxOffset)).coerceIn(0.05f, 1f)
                        val newOffset = (current + delta * dampingFactor * resistance)
                            .coerceIn(-maxOffset, maxOffset)
                        scope.launch {
                            overscrollOffset.snapTo(newOffset)
                        }
                        return if (orientation == Orientation.Vertical) {
                            Offset(0f, delta)
                        } else {
                            Offset(delta, 0f)
                        }
                    }
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (overscrollOffset.value != 0f) {
                    overscrollOffset.animateTo(
                        targetValue = 0f,
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioMediumBouncy,
                            stiffness = Spring.StiffnessMediumLow,
                        ),
                    )
                    return available
                }
                return Velocity.Zero
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                if (overscrollOffset.value != 0f) {
                    overscrollOffset.animateTo(
                        targetValue = 0f,
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioMediumBouncy,
                            stiffness = Spring.StiffnessMediumLow,
                        ),
                    )
                }
                return Velocity.Zero
            }
        }
    }

    return this
        .nestedScroll(connection)
        .graphicsLayer {
            if (orientation == Orientation.Vertical) {
                translationY = overscrollOffset.value
            } else {
                translationX = overscrollOffset.value
            }
        }
}
