package com.music.yzmusic.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Thick-stroke, round-capped icons in the spirit of Telegram's modern icon set.
 * Drawn as strokes (no fills) so the 2.2px weight + round joins read as a
 * single polished family. Tint is applied by [androidx.compose.material3.Icon].
 */
object YZMusicIcons {

    private const val STROKE = 2.2f
    private val stroke = SolidColor(Color.Black)

