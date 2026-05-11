package com.music.yzmusic.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ComposeShader
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.util.LruCache
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.music.yzmusic.data.model.CARD_ART_PX
import com.music.yzmusic.data.model.HEADER_ART_PX
import com.music.yzmusic.data.model.NOTIFICATION_ART_PX
import com.music.yzmusic.data.model.ROW_ART_PX
import com.music.yzmusic.data.model.artworkAt
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The single image a widget draws: the album cover, filling it edge to edge,
 * with its bottom dissolving into a blur for the transport to sit on.
 *
 * All of it is baked into one bitmap because a widget cannot blur anything at
 * runtime — [android.widget.RemoteViews] has no RenderEffect, no Haze, no
 * shaders, and no way to reach a view's render node. So the effect the app gets
 * live from
 * [BottomFadeBlur][com.music.yzmusic.ui.components.BottomFadeBlur] has to be
 * drawn here instead, once per track, on the CPU.
 *
 * That component is also where the shape of it comes from, and it is worth
 * repeating its two findings because they are the whole difference between a
 * blur that reads as artwork dissolving and one that reads as a panel stuck over
 * a picture:
 *
 *  - The blur has to **ramp in over a region far taller than the bar it serves**
 *    (180dp of fade for a bar a fraction of that), and spend most of that run
 *    too faint to notice. The long invisible lead-in is what hides the line
 *    where the effect begins. Here that is [BLUR_REGION_SCALE].
 *  - It has to **stop short of full**, because a blur has nothing to sample past
 *    the edge of its own layer, so the harder it is pushed at that edge the more
 *    of what is left is flat colour rather than blurred content — and flat
 *    colour at the bottom of the artwork is exactly the band being avoided.
 *    Here that is the cap on [BLUR_SIGMAS].
 *
 * The ramp is four progressively blurrier copies of the bottom of the cover,
 * drawn back over it softest-first, each masked by a vertical alpha gradient
 * starting lower than the last — which adds up to a blur that accelerates
 * downwards. Each copy is a separable box blur ([blurInPlace]) run on a
 * quarter-ish-scale working image and sampled back up.
 *
 * The strengths cannot come from the downscale itself — from a mip pyramid, the
 * obvious cheap trick. Halving does average each 2×2 block, so a mip really is
 * blurred, but it holds one *sample* per block, and reconstructing a full-size
 * image from samples that far apart is bilinear interpolation between them: at
 * the strengths a band this size needs, the last mip is a handful of pixels
 * across and what lands on screen is its grid, as big soft rectangles. Blurring
 * *after* the downscale instead is what avoids that — it leaves the working
 * image with no detail finer than its own pixels, which is precisely the
 * condition under which sampling back up adds nothing visible.
 */
internal object MediaWidgetArt {

    /**
     * Draws the widget's artwork at exactly [widthPx] × [heightPx].
     *
     * [bandPx] is the height of the transport strip the layout will lay over the
     * result — the blur is sized from it, so the two stay locked together. See
     * `@dimen/widget_band_compact`.
     *
     * [key] identifies the track this is for, and is what the composite is
     * remembered under. Pass null only when there is nothing to remember (no
     * track at all), so the placeholder isn't cached under a shared name.
     */
    suspend fun render(
        context: Context,
        artworkUrl: String?,
        widthPx: Int,
        heightPx: Int,
        bandPx: Int,
        key: String?,
        cornerRadiusPx: Float,
    ): Bitmap {
        peek(key, widthPx, heightPx, bandPx)?.let { return it }
        val cacheKey = key?.let { cacheKey(it, widthPx, heightPx, bandPx) }

        val cover = loadArtwork(context, artworkUrl, maxOf(widthPx, heightPx))
        val composed = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(composed)
        if (cover != null) canvas.fillCentreCropped(cover) else canvas.fillPlaceholder()

        // Taller than the band, so the ramp has room to start invisibly above
        // it — but never taller than the widget. On two cells the band is more
        // than half the height and this clamp binds, which is fine: the ramp's
        // first stop is a quarter of the way down and the artwork above it is
        // untouched.
        val blurRegion = (bandPx * BLUR_REGION_SCALE).coerceIn(1, heightPx)
        canvas.blurBottom(composed, blurRegion, bandPx)
        canvas.scrimBottom(bandPx)

        val rounded = composed.withRoundedCorners(cornerRadiusPx)
        composed.recycle()
        cacheKey?.let { composites.put(it, rounded) }
        return rounded
    }

    /**
     * The composite for these arguments if it has already been drawn, without
     * drawing it if it hasn't.
     *
     * Lets a provider find out, on the thread it was called on, whether it can
     * push a finished widget in one go. Without it every update would have to
     * push the controls first and the artwork second, and the gap between the
     * two shows: a play/pause tap — which changes one glyph and nothing else —
     * would blink the cover away and back again.
     */
    fun peek(key: String?, widthPx: Int, heightPx: Int, bandPx: Int): Bitmap? =
        key?.let { composites[cacheKey(it, widthPx, heightPx, bandPx)] }?.takeIf { !it.isRecycled }

    /** Drops every remembered composite — the last widget has just been removed. */
    fun clear() = composites.evictAll()

    private fun cacheKey(key: String, widthPx: Int, heightPx: Int, bandPx: Int) =
        "$key|$widthPx|$heightPx|$bandPx"

    // ---- artwork ----

    private suspend fun loadArtwork(context: Context, url: String?, longestSidePx: Int): Bitmap? {
        if (url.isNullOrBlank()) return null
        val px = artPxFor(longestSidePx)
        val request = ImageRequest.Builder(context)
            // Through the app's own size ladder, so this shares a disk-cache
            // entry with the rows, cards and headers already drawing the same
            // cover instead of pulling a widget-sized copy of its own over the
            // wire. Local artwork (content://…/albumart/…) carries no size hint
            // and passes through untouched.
            .data(url.artworkAt(px) ?: url)
            .size(px)
            .allowHardware(false) // the blur below reads pixels
            .build()
        val result = runCatching { SingletonImageLoader.get(context).execute(request) }.getOrNull()
        return (result as? SuccessResult)?.image?.toBitmap()
    }

    /**
     * The smallest size in the app's existing artwork ladder that still covers a
     * widget this big.
     *
     * Deliberately not the widget's own pixel width. A size nothing else in the
     * app asks for is a cache entry nothing else in the app fills, so the widget
     * would fetch its own copy of every cover over the network; landing on one of
     * these means the artwork is usually already on disk — and for the playing
     * track, [NOTIFICATION_ART_PX] is the size the media session itself
     * requested, so it is certainly there. A 720px cover in an 860px-wide widget
     * is a 1.2× upscale that no one can see.
     */
    private fun artPxFor(longestSidePx: Int): Int = when {
        longestSidePx <= ROW_ART_PX -> ROW_ART_PX
        longestSidePx <= CARD_ART_PX -> CARD_ART_PX
        longestSidePx <= NOTIFICATION_ART_PX -> NOTIFICATION_ART_PX
        else -> HEADER_ART_PX
    }

    /** Fills the canvas with [src], cropped from its centre rather than squashed. */
    private fun Canvas.fillCentreCropped(src: Bitmap) {
        val scale = maxOf(width.toFloat() / src.width, height.toFloat() / src.height)
        val sampleW = (width / scale).coerceAtMost(src.width.toFloat())
        val sampleH = (height / scale).coerceAtMost(src.height.toFloat())
        val left = (src.width - sampleW) / 2f
        val top = (src.height - sampleH) / 2f
        drawBitmap(
            src,
            Rect(
                left.toInt(),
                top.toInt(),
                (left + sampleW).toInt(),
                (top + sampleH).toInt(),
            ),
            Rect(0, 0, width, height),
            Paint().apply { isFilterBitmap = true },
        )
    }

    /**
     * What stands in for a cover there isn't one of: a track with no artwork, a
     * fetch that failed, or nothing ever played.
     *
     * Run through the blur and scrim like real artwork rather than short-circuited
     * past them — one code path, and a gradient blurs to itself, so it costs
     * nothing to leave it in.
     */
    private fun Canvas.fillPlaceholder() {
        drawRect(
            0f,
            0f,
            width.toFloat(),
            height.toFloat(),
            Paint().apply {
                shader = LinearGradient(
                    0f, 0f, 0f, height.toFloat(),
                    intArrayOf(0xFF2E3446.toInt(), 0xFF1B2130.toInt(), 0xFF07090E.toInt()),
                    floatArrayOf(0f, 0.45f, 1f),
                    Shader.TileMode.CLAMP,
                )
            },
        )
    }

    // ---- the blur ----

    /**
     * Blurs the bottom [regionPx] of [source], accelerating downwards.
     *
     * [source] must be the bitmap this canvas draws into: the working image is
     * built from what has already been drawn, and then drawn back over it.
     *
     * [bandPx] is what the strengths are measured against, rather than the region
     * they are spread over. The band is a fixed height in dp, so a blur sized
     * from it looks the same on every widget; sized from the region it would
     * weaken on exactly the small widget where the region had to be clamped and
     * the blur matters most.
     */
