/*
 * Vendored from Kyant0/backdrop v2.0.0 (io.github.kyant0:backdrop)
 * https://github.com/Kyant0/backdrop — Copyright 2025 Kyant0, Apache License 2.0
 *
 * Vendored so the library ships as source with this app (binary AARs compiled
 * against older Compose broke at runtime) and to add a backdrop resolution
 * scale for cheaper effect rendering. KMP expect/actual declarations were
 * merged into this single Android source set. Package renamed accordingly.
 */
package com.music.yzmusic.ui.components.backdrop.highlight

import androidx.annotation.FloatRange
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.util.fastCoerceAtMost
import com.music.yzmusic.ui.components.backdrop.RuntimeShader
import com.music.yzmusic.ui.components.backdrop.RuntimeShaderCache
import com.music.yzmusic.ui.components.backdrop.internal.AmbientHighlightShaderString
import com.music.yzmusic.ui.components.backdrop.internal.DefaultHighlightShaderString
import com.music.yzmusic.ui.components.backdrop.isRuntimeShaderSupported
import kotlin.math.PI

@Immutable
interface HighlightStyle {

    val color: Color

    val blendMode: BlendMode

    fun DrawScope.createShader(
        shape: Shape,
        runtimeShaderCache: RuntimeShaderCache
    ): RuntimeShader?

    @Immutable
    data class Plain(
        override val color: Color = Color.White.copy(alpha = 0.38f),
        override val blendMode: BlendMode = BlendMode.Plus
    ) : HighlightStyle {

        override fun DrawScope.createShader(
            shape: Shape,
            runtimeShaderCache: RuntimeShaderCache
        ): RuntimeShader? = null
    }

    @Immutable
    data class Default(
        override val color: Color = Color.White.copy(alpha = 0.5f),
        override val blendMode: BlendMode = BlendMode.Plus,
        val angle: Float = 45f,
        @param:FloatRange(from = 0.0) val falloff: Float = 1f
    ) : HighlightStyle {

        override fun DrawScope.createShader(
            shape: Shape,
            runtimeShaderCache: RuntimeShaderCache
        ): RuntimeShader? {
            if (!isRuntimeShaderSupported()) return null
            val currentSize = size
            if (!currentSize.isSpecified || currentSize.width <= 0f || currentSize.height <= 0f || currentSize.width.isNaN() || currentSize.height.isNaN()) return null
            return runtimeShaderCache.obtainRuntimeShader(
                "Default",
                DefaultHighlightShaderString
            ).apply {
                setFloatUniform("size", currentSize.width, currentSize.height)
                setFloatUniform("cornerRadii", getCornerRadii(shape))
                setColorUniform("color", color.copy(alpha = 1f))
                setFloatUniform("angle", angle * (PI / 180f).toFloat())
                setFloatUniform("falloff", falloff)
            }
        }
    }

    @Immutable
    data class Ambient(
        @param:FloatRange(from = 0.0, to = 1.0) val intensity: Float = 0.38f
    ) : HighlightStyle {

        override val color: Color = Color.White.copy(alpha = intensity)

        override val blendMode: BlendMode = DrawScope.DefaultBlendMode

        override fun DrawScope.createShader(
            shape: Shape,
            runtimeShaderCache: RuntimeShaderCache
        ): RuntimeShader? {
            if (!isRuntimeShaderSupported()) return null
            val currentSize = size
            if (!currentSize.isSpecified || currentSize.width <= 0f || currentSize.height <= 0f || currentSize.width.isNaN() || currentSize.height.isNaN()) return null
            return runtimeShaderCache.obtainRuntimeShader(
                "Ambient",
                AmbientHighlightShaderString
            ).apply {
                setFloatUniform("size", currentSize.width, currentSize.height)
                setFloatUniform("cornerRadii", getCornerRadii(shape))
                setFloatUniform("angle", 45f * (PI / 180f).toFloat())
                setFloatUniform("falloff", 1f)
            }
        }
    }

    companion object {

        @Stable
        val Default: Default = Default()

        @Stable
        val Ambient: Ambient = Ambient()

        @Stable
        val Plain: Plain = Plain()
    }
}

private fun DrawScope.getCornerRadii(shape: Shape): FloatArray {
    val size = size
    val maxRadius = size.minDimension / 2f
    val shape = shape as? CornerBasedShape ?: return FloatArray(4) { maxRadius }
    val isLtr = layoutDirection == LayoutDirection.Ltr
    val topLeft =
        if (isLtr) shape.topStart.toPx(size, this)
        else shape.topEnd.toPx(size, this)
    val topRight =
        if (isLtr) shape.topEnd.toPx(size, this)
        else shape.topStart.toPx(size, this)
    val bottomRight =
        if (isLtr) shape.bottomEnd.toPx(size, this)
        else shape.bottomStart.toPx(size, this)
    val bottomLeft =
        if (isLtr) shape.bottomStart.toPx(size, this)
        else shape.bottomEnd.toPx(size, this)
    return floatArrayOf(
        topLeft.fastCoerceAtMost(maxRadius),
        topRight.fastCoerceAtMost(maxRadius),
        bottomRight.fastCoerceAtMost(maxRadius),
        bottomLeft.fastCoerceAtMost(maxRadius)
    )
}