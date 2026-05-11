package com.music.yzmusic.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import com.music.yzmusic.MainActivity
import com.music.yzmusic.R
import com.music.yzmusic.playback.PlayerDeepLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

/**
 * The home-screen widget: the cover of what's playing, with a transport across
 * the foot of it.
 *
 * Two providers, [MediaWidgetSquare] and [MediaWidgetWide], so the picker offers
 * a square one and a full-width one — but they are the same widget, and both can
 * be resized across the whole range. What they differ in is the size they arrive
 * at. Which of the two layouts a given instance draws is decided from its
 * *measured* width ([WIDE_LAYOUT_MIN_DP]), not from which provider it came from,
 * so a square dragged out to four cells sets its title beside the buttons and
 * picks up the artist, and a wide one squeezed back to two stacks the title over
 * them instead — rather than either being stuck with a layout its size doesn't
 * suit.
 *
 * Everything visual except the transport itself is one bitmap, drawn by
 * [MediaWidgetArt] — see there for why the blur has to work that way.
 */
abstract class MediaWidget : AppWidgetProvider() {

    /**
     * The width to assume when the host hasn't said. Launchers are supposed to
     * put a size in the options bundle before the first update and most do, but
     * not all, and a widget that guessed wrong renders its artwork at the wrong
     * aspect ratio until something moves it. This provider's own target size is
     * the best available guess.
     */
    protected abstract val fallbackWidthDp: Int

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        renderAsync(context, ids)
    }

    /** Fires on every resize, which is a re-render: the bitmap is size-specific. */
    override fun onAppWidgetOptionsChanged(
        context: Context,
        manager: AppWidgetManager,
        id: Int,
        newOptions: Bundle?,
    ) {
        renderAsync(context, intArrayOf(id))
    }

    override fun onDisabled(context: Context) {
        MediaWidgetArt.clear()
    }

    /**
     * Renders off the broadcast thread, holding the broadcast open while it runs.
     *
     * [goAsync] is what makes that legal — artwork may have to come off disk or
     * out of the network, and returning from `onUpdate` first would let the
     * process be killed mid-render. The timeout is well inside the window a
     * broadcast gets; past it the widget keeps whatever it last drew, which is
     * a better outcome than an ANR.
     */
    private fun renderAsync(context: Context, ids: IntArray) {
        val pending = goAsync()
        val app = context.applicationContext
        val fallback = fallbackWidthDp
        scope.launch {
            try {
                withTimeoutOrNull(RENDER_TIMEOUT_MS) { render(app, ids, fallback) }
            } finally {
                runCatching { pending.finish() }
            }
        }
    }

    companion object {

        /**
         * The measured width at which the track is worth setting beside the
         * transport rather than above it.
         *
         * The wide layout spends 158dp on chrome — 14dp of leading padding, three
         * 44dp buttons, 4dp trailing, 8dp between text and buttons — so below
         * roughly 230dp the title is a stub, and the compact layout, which gives
         * that width back to the title by putting it on its own line, reads
         * better.
         *
         * Set at the midpoint between a three-cell span (180dp) and a four-cell
         * one (250dp) rather than at either end. Cells are not really 70dp on
         * every launcher, and the midpoint is the value that tolerates the most
         * variance in both directions before a four-cell widget falls to compact
         * or a three-cell one is promoted to a layout it has no room for.
         */
        const val WIDE_LAYOUT_MIN_DP = 215

        /**
         * Redraws every placed widget of either kind.
         *
         * Called by
         * [PlaybackService][com.music.yzmusic.playback.PlaybackService] whenever
         * what the widget shows has changed — which is on every play, pause and
         * track change, so it does nothing on the calling thread beyond handing
         * off. Even asking the system which widgets exist is a binder round trip,
         * and that thread is the one ExoPlayer runs on.
         */
        fun refresh(context: Context) {
            val app = context.applicationContext
            scope.launch {
                withTimeoutOrNull(RENDER_TIMEOUT_MS) {
                    val manager =
                        runCatching { AppWidgetManager.getInstance(app) }.getOrNull() ?: return@withTimeoutOrNull
                    for ((provider, fallbackWidthDp) in PROVIDERS) {
                        val ids = runCatching {
                            manager.getAppWidgetIds(ComponentName(app, provider))
                        }.getOrNull()
                        if (ids == null || ids.isEmpty()) continue
                        render(app, ids, fallbackWidthDp)
                    }
                }
            }
        }

        private suspend fun render(context: Context, ids: IntArray, fallbackWidthDp: Int) {
            val manager = runCatching { AppWidgetManager.getInstance(context) }.getOrNull() ?: return
            val snapshot = MediaWidgetSnapshot.load(context)
            // Keyed on the artwork rather than the track: two tracks off one
            // album are one picture, so moving through an album redraws nothing.
            val key = snapshot.artworkUrl ?: KEY_NO_ARTWORK
            for (id in ids) {
                val size = measure(context, manager, id, fallbackWidthDp)
