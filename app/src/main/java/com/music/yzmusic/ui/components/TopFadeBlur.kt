package com.music.yzmusic.ui.components

import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.yzmusic.data.settings.AppSettings
import dev.chrisbanes.haze.HazeProgressive
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials

/**
 * The run the fade needs below the bar to get from full blur to none without
 * the eye finding where it got there.
 *
 * Shortened from 120: the page's first heading sits a fixed distance down the
 * screen, well inside this run, and over 120dp the ramp still had something
 * like a tenth of its blur left there — enough to leave a heavy 30sp title
 * looking soft before it had been scrolled anywhere. The tail is what hides the
 * layer's end, so it cannot simply be cut; 88 is as short as it goes before the
 * ramp starts to be findable. The rest of the clearance is bought by starting
 * the page's content lower — the two are tuned against each other, and neither
 * fixes it alone.
 */
private val FADE_RUN = 88.dp

/**
 * How much blur the fade reaches at its outer edge — short of all of it.
 *
 * The last quarter buys almost nothing visually and costs the most: a blur has
 * nothing to sample past the edge of its own layer, so the harder it is pushed
 * there the more of the layer is flat material colour rather than blurred
 * content, and the more that edge reads as a band of colour laid over the page.
 * Stopping at three quarters keeps the ramp and loses the band.
 */
private const val PEAK = 0.75f

/**
 * How dark the readability scrim starts, at the very top of the strip.
 *
 * Modest on purpose: it is there to give white glyphs a floor on a pale sleeve,
 * not to grey out the artwork. Anything heavier and the bar stops being a fade
 * over a picture and starts being a header with a picture behind it.
