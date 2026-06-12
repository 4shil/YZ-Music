package com.music.yzmusic.ui.components

import androidx.compose.animation.core.EaseInCubic
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * The strip the tab bar alone needs, above the gesture inset. Generous on
 * purpose: the ramp below spends most of its run at an alpha too low to see,
 * and that long invisible lead-in is what hides where the layer begins.
 */
private val FADE_HEIGHT = 180.dp

/**
 * Taller once the mini player is stacked on top of the tab bar — by the 56dp
 * the pill-shaped bar now stands, plus the 8dp gap above it and the run the
 * ramp wants over both.
 */
private val FADE_HEIGHT_WITH_MINI_PLAYER = 254.dp

/**
 * How many colour stops the ramp is cut into.
 *
