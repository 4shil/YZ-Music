package com.music.yzmusic.ui.replay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.music.yzmusic.R
import com.music.yzmusic.data.model.CARD_ART_PX
import com.music.yzmusic.data.model.artworkAt
import com.music.yzmusic.data.stats.ReplaySummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * The Replay as one picture, drawn to a bitmap you can send.
 *
 * ## Why this is drawn rather than screenshotted
 *
 * The obvious way to share a story card is to capture the one on screen. It is
 * also the wrong picture: a story card is one fact, and the thing people
 * actually want to send is the whole year — the minutes *and* the five songs
 * *and* the five artists. A screenshot would also carry the status bar, the
 * progress segments and whatever letterboxing that particular phone needed, so
 * the same Replay would come out looking different on every device.
 *
 * ## Why it isn't Compose
 *
 * Composing offscreen means laying out a composition that is not attached to a
 * window and then reading pixels back out of it, which on this app's minimum SDK
 * is a set of caveats about hardware bitmaps and layer support rather than an
 * API. A canvas is a canvas on every version, and the layout below is fixed —
 * nothing here reflows, so nothing here needs a layout system.
 *
 * The size is 1080 × 1920: exactly the 9:16 the story cards are held to (see
 * `StoryFrame`), so what gets shared is the shape of what was being looked at,
 * and it is the size every messaging app and story surface expects.
 */
suspend fun renderReplayPoster(
    context: Context,
    summary: ReplaySummary,
    holder: String,
    memberSince: String?,
    /**
     * Which story card to draw, or null for the whole Replay.
     *
     * A card's share button sends the card. That is not the same picture as the
     * summary and should not be: somebody taps share on the genre card because
     * the genre surprised them, and receiving a table of every chart instead is
     * a different message. The Replay page's own button is where "all of it"
     * lives.
     */
    page: ReplayStoryPage? = null,
): Bitmap = coroutineScope {
    // The artwork this needs is a dozen small fetches that are all in Coil's
    // disk cache already — the page they came from has been on screen. Started
    // together rather than in a loop so a cold cache costs one round trip
    // rather than twelve.
    val songs = summary.songRows(POSTER_ROWS)
    val artists = summary.artistRows(POSTER_ROWS)
    val albums = summary.albumRows(POSTER_ALBUMS)
    val covers = (songs + artists + albums)
        .mapNotNull { it.artworkUrl }
        .distinct()
        .associateWith { url -> async(Dispatchers.IO) { loadBitmap(context, url) } }
        .mapValues { it.value.await() }

    withContext(Dispatchers.Default) {
        val lit = page?.let { summary.storyArtwork(it) }
            ?: songs.firstOrNull()?.artworkUrl
        val bitmap = Bitmap.createBitmap(POSTER_W, POSTER_H, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val type = Fonts(context)

        // The same cover and the same turn of the colour wheel the card on
        // screen was drawn with, so the picture is recognisably that card.
        drawBackdrop(canvas, covers[lit], page?.let(::storyHue) ?: 0f)
        var y = drawHeader(canvas, context, type, summary, holder, memberSince)
        if (page == null) {
            y = drawTotals(canvas, type, summary, y)
            y = drawColumns(canvas, type, songs, artists, covers, y)
            y = drawAlbums(canvas, type, albums, covers, y)
            drawGenres(canvas, type, summary, y)
        } else {
            y = drawRuns(canvas, type, summary.storyHeadline(page), MARGIN, y, CONTENT_W)
            drawCard(canvas, type, summary, page, covers, y + 56f)
        }
        drawFooter(canvas, type)
        bitmap
    }
}

// ── One story card ──────────────────────────────────────────────────────────

/**
 * The body of [page], under its headline.
 *
 * Deliberately not a pixel copy of the composable — a card on screen fills its
 * height with weighted spacers and this cannot, since it has no measurement pass
 * to spend. What it copies is the *content*: the same sentence, the same hero,
 * the same four runners-up, in the same order. Somebody who sends a card and
 * somebody who saw it are looking at the same facts.
 */
private fun drawCard(
    canvas: Canvas,
    type: Fonts,
    summary: ReplaySummary,
    page: ReplayStoryPage,
    covers: Map<String, Bitmap?>,
    top: Float,
) {
    when (page) {
        ReplayStoryPage.INTRO, ReplayStoryPage.MINUTES ->
            drawCollage(canvas, summary, covers, top)
        ReplayStoryPage.SONGS ->
            drawLeaderboard(canvas, type, summary.songRows(CARD_ROWS), covers, top, false)
        ReplayStoryPage.ARTISTS ->
            drawLeaderboard(canvas, type, summary.artistRows(CARD_ROWS), covers, top, true)
        ReplayStoryPage.ALBUMS ->
            drawLeaderboard(canvas, type, summary.albumRows(CARD_ROWS), covers, top, false)
        ReplayStoryPage.GENRES -> drawBigList(canvas, type, summary.genreRows(CARD_ROWS), top)
        ReplayStoryPage.HABITS -> drawHabits(canvas, type, summary, top)
        ReplayStoryPage.SUMMARY -> drawRecap(canvas, type, summary, top)
    }
}

/** The number one, large, with its runners-up under it. */
private fun drawLeaderboard(
    canvas: Canvas,
    type: Fonts,
    rows: List<ReplayRow>,
    covers: Map<String, Bitmap?>,
    top: Float,
    circular: Boolean,
) {
    val lead = rows.firstOrNull() ?: return
    val hero = 348f
    drawArtwork(canvas, lead.artworkUrl?.let { covers[it] }, lead.title, MARGIN, top, hero, circular)

    val textX = MARGIN + hero + 48f
    val textWidth = POSTER_W - MARGIN - textX
    val title = type.heading(78f, Color.WHITE)
    canvas.drawText(ellipsised(lead.title, title, textWidth), textX, top + 86f, title)
    var y = top + 86f
    lead.subtitle?.let {
        val sub = type.body(50f, 0xB3FFFFFF.toInt())
        y += 62f
        canvas.drawText(ellipsised(it, sub, textWidth), textX, y, sub)
    }
    val stats = type.body(44f, 0x8CFFFFFF.toInt())
    canvas.drawText(
        "${formatListening(lead.ms)} · ${countOf(lead.plays, "play")}",
        textX,
        y + 62f,
        stats,
    )

    var rowY = top + hero + 90f
    rows.drop(1).forEach { row ->
        canvas.drawText(
            row.rank.toString(),
            MARGIN,
            rowY + 72f,
            type.heading(44f, 0x73FFFFFF),
        )
        val artX = MARGIN + 62f
        drawArtwork(canvas, row.artworkUrl?.let { covers[it] }, row.title, artX, rowY, 108f, circular)
        val name = type.body(48f, Color.WHITE, bold = true)
        val nameX = artX + 108f + 28f
        val stat = type.body(38f, 0x80FFFFFF.toInt())
        val statWidth = stat.measureText(formatListening(row.ms))
        canvas.drawText(
            ellipsised(row.title, name, POSTER_W - MARGIN - nameX - statWidth - 32f),
            nameX,
            rowY + 72f,
            name,
        )
        stat.textAlign = Paint.Align.RIGHT
        canvas.drawText(formatListening(row.ms), POSTER_W - MARGIN, rowY + 72f, stat)
        rowY += 148f
    }
}

/** The genre card: one word, big, then the rest as a ranked list. */
private fun drawBigList(canvas: Canvas, type: Fonts, rows: List<ReplayRow>, top: Float) {
    val lead = rows.firstOrNull() ?: return
    val word = type.heading(150f, Color.WHITE)
    canvas.drawText(ellipsised(lead.title, word, CONTENT_W), MARGIN, top + 120f, word)
    canvas.drawText(formatListening(lead.ms), MARGIN, top + 186f, type.body(46f, 0x99FFFFFF.toInt()))

    var y = top + 300f
    rows.drop(1).forEach { row ->
        canvas.drawText(row.rank.toString(), MARGIN, y, type.heading(48f, 0x73FFFFFF))
        val name = type.body(52f, Color.WHITE, bold = true)
        canvas.drawText(row.title, MARGIN + 80f, y, name)
        val stat = type.body(38f, 0x80FFFFFF.toInt()).apply { textAlign = Paint.Align.RIGHT }
        canvas.drawText(formatListening(row.ms), POSTER_W - MARGIN, y, stat)
        y += 106f
    }
}

private fun drawHabits(canvas: Canvas, type: Fonts, summary: ReplaySummary, top: Float) {
    var y = top + 40f
    fun stat(value: String, label: String) {
        canvas.drawText(value, MARGIN, y, type.heading(84f, Color.WHITE))
        canvas.drawText(label, MARGIN, y + 56f, type.body(42f, 0x99FFFFFF.toInt()))
        y += 176f
    }
    if (summary.distinctAlbums > 0) {
        stat(grouped(summary.distinctAlbums.toLong()), "different albums")
    }
    summary.busiestDay?.let {
        stat(formatDay(it), "your biggest day — ${formatListening(summary.busiestDayMs)}")
    }
    summary.peakHour?.let { stat(formatHour(it), "when you listen most") }
}

private fun drawRecap(canvas: Canvas, type: Fonts, summary: ReplaySummary, top: Float) {
