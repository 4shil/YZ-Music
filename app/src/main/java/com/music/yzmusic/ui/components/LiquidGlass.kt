/*
 * The glass rendering itself is Kyant0/backdrop (Apache-2.0), vendored at
 * [com.music.yzmusic.ui.components.backdrop] — see that package for the
 * upstream attribution. This file is the integration glue, adapted from
 * EchoMusicApp/Echo-Music's GlassEffectConfig/Modifier.liquidGlass
 * (GPL-3.0), cut down from Echo's full per-component/vibrancy-slider config
 * to the single on/off switch BitChord exposes in Settings, and scoped to
 * the floating nav bar only.
 */
package com.music.yzmusic.ui.components

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.yzmusic.data.settings.AppSettings
import com.music.yzmusic.ui.components.backdrop.backdrops.emptyBackdrop
import com.music.yzmusic.ui.components.backdrop.drawBackdrop
import com.music.yzmusic.ui.components.backdrop.effects.blur
import com.music.yzmusic.ui.components.backdrop.effects.colorControls
import com.music.yzmusic.ui.components.backdrop.effects.lens
import com.music.yzmusic.ui.components.backdrop.highlight.Highlight
import com.music.yzmusic.ui.components.backdrop.shadow.Shadow

/** Whether the liquid glass nav bar is turned on — see [AppSettings.liquidGlass]. */
val LocalLiquidGlassEnabled = staticCompositionLocalOf { false }

/** The backdrop content (app UI) that a liquid glass surface samples from. */
val LocalAppBackdrop = staticCompositionLocalOf { emptyBackdrop() }

/**
 * The backdrop blur pipeline requires [android.graphics.RenderEffect] on a
 * [android.graphics.RenderNode], available from Android 12 (API 31).
 */
fun isGlassSupported(sdkInt: Int = Build.VERSION.SDK_INT): Boolean = sdkInt >= Build.VERSION_CODES.S

/** Apple-matched defaults (Echo's GlassEffectConfig()), fixed rather than user sliders. */
private const val VIBRANCY = 1f
internal const val BLUR_RADIUS_DP = 8f
internal const val LENS_HEIGHT = 0.5f
internal const val LENS_AMOUNT = 0.5f
internal const val LENS_MAX_DP = 48f

/**
 * Calculates the lens refraction height in pixels, scaled by [glassRefraction] (0.0 .. 1.0).
 * 0.0 returns 0 (no refraction); 1.0 preserves 100% baseline refraction height.
 */
fun calculateGlassLensHeightPx(
    glassRefraction: Float,
    density: Density,
    baseHeight: Float = LENS_HEIGHT,
    maxDp: Float = LENS_MAX_DP,
    scale: Float = GLASS_RESOLUTION_SCALE,
): Float {
    val factor = glassRefraction.coerceIn(0f, 1f)
    if (factor <= 0f) return 0f
    return with(density) { (factor * baseHeight * maxDp).dp.toPx() } * scale
}

/**
 * Calculates the lens refraction amount in pixels, scaled by [glassRefraction] (0.0 .. 1.0).
 * 0.0 returns 0 (no refraction); 1.0 preserves 100% baseline refraction amount.
 */
fun calculateGlassLensAmountPx(
    glassRefraction: Float,
    density: Density,
    baseAmount: Float = LENS_AMOUNT,
    maxDp: Float = LENS_MAX_DP,
    scale: Float = GLASS_RESOLUTION_SCALE,
): Float {
    val factor = glassRefraction.coerceIn(0f, 1f)
    if (factor <= 0f) return 0f
    return with(density) { (factor * baseAmount * maxDp).dp.toPx() } * scale
}

/**
 * Maps the normalized [glassBlur] factor (0.0 .. 1.0) to a dp blur radius,
 * preserving the baseline [BLUR_RADIUS_DP] at [AppSettings.DEFAULT_GLASS_BLUR].
 */
fun calculateGlassBlurRadiusDp(
    glassBlur: Float,
    defaultBlur: Float = AppSettings.DEFAULT_GLASS_BLUR,
    baseRadiusDp: Float = BLUR_RADIUS_DP,
): Float {
    if (defaultBlur <= 0f) return baseRadiusDp
    return (glassBlur.coerceIn(0f, 1f) / defaultBlur) * baseRadiusDp
}
private const val SURFACE_OPACITY = 0.4f

/**
 * Resolution fraction the glass surface records and processes its backdrop at.
 */
private const val GLASS_RESOLUTION_SCALE = 0.33f

/**
 * The hairline along a bar's edge, and what stands in for the glass rim
 * wherever the glass itself is not drawn.
 */
internal val GLASS_EDGE_WIDTH = 0.5.dp
internal val GLASS_EDGE_COLOR = Color.White.copy(alpha = 0.10f)

/**
 * Icon and label colour for content sitting on a glass surface.
 */
@Composable
fun glassContentColor(): Color =
    if (MaterialTheme.colorScheme.surface.luminance() > 0.5f) Color.Black else Color.White

/**
 * Selected-tab indicator colour for a glass surface: the inverse of
 * [glassContentColor] rather than the same tint at lower alpha.
 */
@Composable
fun glassIndicatorColor(): Color =
    if (MaterialTheme.colorScheme.surface.luminance() > 0.5f) Color.White else Color.Black

/**
 * Renders this composable as a liquid glass surface sampling [LocalAppBackdrop]:
 * vibrancy, blur and lens refraction, then a theme-adaptive surface tint (light
 * glass on light theme, dark on dark). Returns the receiver unchanged on devices
 * without RenderEffect support — callers should still gate on [isGlassSupported]
 * to fall back to the regular Haze treatment there.
 */
@Composable
fun Modifier.liquidGlass(shape: CornerBasedShape): Modifier {
    if (!isGlassSupported()) return this
    val reduceDynamicBlur by AppSettings.reduceDynamicBlur.collectAsStateWithLifecycle()
    if (reduceDynamicBlur) {
        return background(MaterialTheme.colorScheme.surface, shape)
            .border(GLASS_EDGE_WIDTH, GLASS_EDGE_COLOR, shape)
    }
    val glassBlur by AppSettings.glassBlur.collectAsStateWithLifecycle()
    val glassRefraction by AppSettings.glassRefraction.collectAsStateWithLifecycle()
    val backdrop = LocalAppBackdrop.current
    val density = LocalDensity.current
    val blurRadiusDp = calculateGlassBlurRadiusDp(glassBlur)
    val blurPx = with(density) { blurRadiusDp.dp.toPx() } * GLASS_RESOLUTION_SCALE
    val lensHeightPx = calculateGlassLensHeightPx(glassRefraction, density)
    val lensAmountPx = calculateGlassLensAmountPx(glassRefraction, density)
    val surfaceTintColor = if (MaterialTheme.colorScheme.surface.luminance() > 0.5f) {
        Color(0xFFFAFAFA)
    } else {
        Color(0xFF121212)
    }

    return drawBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = {
            colorControls(saturation = 1f + 0.5f * VIBRANCY)
            blur(blurPx)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && lensHeightPx > 0f && lensAmountPx > 0f) {
                lens(
                    refractionHeight = lensHeightPx,
                    refractionAmount = lensAmountPx,
                    depthEffect = true,
                    chromaticAberration = true,
                )
            }
        },
        highlight = { Highlight.Default },
        shadow = { Shadow.Default },
        onDrawSurface = {
            drawRect(color = surfaceTintColor.copy(alpha = SURFACE_OPACITY), size = size)
        },
        backdropScale = GLASS_RESOLUTION_SCALE,
    )
}
