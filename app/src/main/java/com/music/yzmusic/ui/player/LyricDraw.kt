package com.music.yzmusic.ui.player

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import com.music.yzmusic.data.lyrics.CharGrowth
import com.music.yzmusic.data.lyrics.GrowingWord
import com.music.yzmusic.data.lyrics.LyricLine

/**
 * The bloom behind the line being sung, at its very strongest.
 *
 * Kept well under half strength: the halo is drawn from the same white as the
 * text, so at full alpha it stops reading as light and starts reading as a
 * second, badly printed copy of the words. What is actually drawn is this
 * scaled by each letter's own bloom, so only a properly carried note ever sees
 * the whole of it.
 *
 * The bloom used to be a band of light trailing the sweep's leading edge across
 * every line, which is a lamp being dragged along under the words: a shape that
 * belongs to the highlight rather than to the singing, present on patter and
 * held notes alike. It is now attached to the letters of the held words
 * themselves — see [LyricLine.growingWords] — so a line of quick syllables has
 * no glow at all and a carried note lights up letter by letter, which is where
 * the light was always meant to come from.
 */
internal const val GLOW_ALPHA = 0.62f

/**
 * How far the bloom spreads off a letter. Tight, because it is a letter's worth
 * of light now rather than a word's: a wide radius on something this small is
 * a smudge behind the text instead of a glow coming off it.
 */
internal val GLOW_RADIUS = 6.dp

/**
 * Room reserved inside each copy of a line for the halo to spread into.
 *
 * A blur is computed on its layer's own bitmap, so a halo with nowhere to go
 * inside those bounds is a halo with a hard edge — which is what cropped the
 * bloom to the line's box. Every copy carries the same inset so they still lay
 * out identically, and the list gives the width back by taking it off its own
 * padding and row spacing.
 */
internal val GLOW_ROOM = 10.dp

/**
 * How far the sweep's leading edge fades out instead of ending on a cut.
 *
 * A hard boundary is legible as a boundary: the eye reads a bar travelling
 * across the words rather than the words themselves lighting up as they are
 * sung. Feathering it over roughly a character and a half is what turns the
 * cut back into a wavefront.
 */
internal val WIPE_FEATHER = 30.dp

/**
 * How far the word being sung lifts off the line.
 *
 * Two pixels, and it has to be about two: enough that the eye catches the
 * words moving under the sweep, little enough that nothing appears to come
 * loose from the line it belongs to.
 */
internal val WORD_RISE = 2.dp

/**
 * How much further up a row is opened when it holds a word being animated
 * letter by letter, in multiples of [WORD_RISE].
 *
 * A letter that swells has to be given the room above the line it grew out of
 * or the top of it is shaved off by the band it is drawn in. Covers the lift
 * and the swell together, which is why it is well over the one rise an
 * ordinary word needs.
 */
internal const val GROW_HEADROOM = 3f

/**
 * Draws this text clipped to the letters of the words being held, each at its
 * own brightness — the light the singing is actually giving off, rather than a
 * band of it dragged along behind the highlight.
 *
 * Nothing at all on a line of ordinary syllables: the words that light up are
 * the ones held long enough to have earned it, so a verse of patter is simply
 * dark and costs one comparison to establish. That selectiveness is the point.
 * A glow present on every word is a property of the highlight; a glow that
 * arrives only when a note is carried is a property of the voice.
 *
 * Each letter is masked to its own bloom rather than drawn at it, because the
 * caller's layer is what this erases into — see `SweptLyricLine`. The mask
 * lands before the blur, so what spreads is already the right brightness.
 */
internal fun ContentDrawScope.glowGrown(
    layout: TextLayoutResult,
    line: LyricLine,
    positionMs: Long,
    inset: Float,
    peak: Float,
    growth: CharGrowth,
) {
    if (!line.isGrowing(positionMs)) return
    val em = layout.layoutInput.style.fontSize.toPx()
    val length = layout.layoutInput.text.length
    for (word in line.growingWords) {
        if (positionMs < word.startMs || positionMs > word.restsAtMs) continue
        val span = line.wordSpans[word.index]
        val fall = line.wordFall(word.index, positionMs)
        for (char in span.first..minOf(span.last, length - 1)) {
            word.sampleInto(char - span.first, positionMs, growth)
            if (growth.bloom <= 0.01f) continue
            val visualLine = layout.getLineForOffset(char)
            // Row-aware, for the same reason the sweep is; see [xOn].
            val from = layout.xOn(char, visualLine, inset)
            val to = layout.xOn(char + 1, visualLine, inset)
            if (to <= from) continue
            val dx = growth.shift * em
            val dy = -growth.rise * peak * fall
            val rowTop = layout.getLineTop(visualLine) + inset
            val bottom = layout.getLineBottom(visualLine) + inset
            val overhang = (to - from) * (growth.scale - 1f) / 2f
            clipRect(
                left = from - overhang + dx,
                top = rowTop - peak * GROW_HEADROOM,
                right = to + overhang + dx,
                bottom = bottom,
            ) {
                translate(left = dx, top = dy) {
                    scale(
                        growth.scale,
                        growth.scale,
                        Offset((from + to) / 2f, (rowTop + bottom) / 2f),
                    ) {
                        this@glowGrown.drawContent()
                    }
                }
                // Scoped to this letter's own clip, so it takes this letter's
                // brightness down and leaves its neighbours — which have their
                // own, a beat behind — where they are.
                drawRect(
                    color = Color.White.copy(alpha = growth.bloom),
                    blendMode = BlendMode.DstIn,
                )
            }
        }
    }
}

/**
 * Redraws this row with the word being sung lifted off the line, and the ones
 * behind it settling back down.
 *
 * The line is cut at word boundaries and each piece replayed at its own
 * height, which is what CSS gets for free by making every syllable its own
 * box. Cutting between words rather than inside one means no glyph is ever
 * sliced, and the pieces that are on the floor — which is most of them, most
 * of the time — are one replay between them rather than one each.
 *
 * Costs nothing at all until something is off the floor: a line with no lift
 * on it draws exactly once, the same as it did before any of this.
 */
internal fun ContentDrawScope.riseWith(
    layout: TextLayoutResult,
    line: LyricLine,
    positionMs: Long,
    inset: Float,
    peak: Float,
    growth: CharGrowth,
) {
    if (!line.isLifted(positionMs)) {
        drawContent()
        return
    }
    val em = layout.layoutInput.style.fontSize.toPx()
    for (visualLine in 0 until layout.lineCount) {
        val lineStart = layout.getLineStart(visualLine)
        val lineEnd = layout.getLineEnd(visualLine, visibleEnd = true)
        // The row's own box. Anything standing still is clipped to exactly
        // this: a band opened upwards would take in the bottom of the row
        // above and draw it a second time, and two passes of a half-transparent
        // line do not add up to the same line. That doubled sliver along every
        // row is what read as the lines overlapping.
        val top = layout.getLineTop(visualLine) + inset
        val bottom = layout.getLineBottom(visualLine) + inset
        var at = lineStart
        var edge = layout.getLineLeft(visualLine) + inset
        for (index in line.words.indices) {
            val span = line.wordSpans[index]
            val start = maxOf(span.first, lineStart)
            val end = minOf(span.last + 1, lineEnd)
            if (start >= end) continue
            // Only while it is actually moving. Once the last letter has come to
            // rest the word is back to being an ordinary sung word settling
            // down, and the two agree exactly at the handover — a letter rests
            // at precisely the lift [LyricLine.wordLift] would give it — so the
            // cheaper single slice takes over without a step.
            val held = line.growingAt(index)?.takeIf { positionMs in it.startMs..it.restsAtMs }
            val lift = line.wordLift(index, positionMs)
            // A word with nothing happening to it is left to the flat run,
            // which is the whole of the line for all but a syllable of it.
            if (held == null && lift <= 0.01f) continue
            val from = layout.xOn(start, visualLine, inset)
            val to = layout.xOn(end, visualLine, inset)
            // Nothing to cut. Left where it is rather than stepped over, so the
            // flat run still has it and the row keeps its words.
            if (to <= from) continue
            // Everything between the last risen word and this one is flat, and
            // goes down in a single piece however many words that spans.
            if (start > at) sliceRisen(edge, top, from, bottom, 0f)
            if (held != null) {
                growEach(
                    layout, held, line, positionMs, visualLine,
                    start, end, top, bottom, inset, peak, em, growth,
                )
            } else {
                // Only what is off the floor gets room above the row to be off
                // it in; see [top].
                sliceRisen(from, top - peak, to, bottom, -lift * peak)
            }
            at = end
            edge = to
        }
        if (at < lineEnd) {
            sliceRisen(edge, top, layout.getLineRight(visualLine) + inset, bottom, 0f)
        }
    }
}

/**
 * Redraws one held word a letter at a time, each at its own swell and height.
 *
 * The word is cut between characters rather than between words, so a letter can
 * be scaled about its own centre without the ones either side of it coming
 * along. Each piece is clipped to where its letter is *going* rather than where
 * it sits: a glyph grown about its middle reaches past the box it was laid out
 * in, and clipping to that box would shave both sides off it as it swells.
 *
 * The overlap that buys — a letter's clip reaching a pixel or so into its
 * neighbour's — is why this is only ever run on a word that has earned it. Two
 * copies of a glyph edge a pixel apart is nothing on a letter mid-swell and
 * would be an obvious double image across a whole line.
 */
@Suppress("LongParameterList")
private fun ContentDrawScope.growEach(
    layout: TextLayoutResult,
    word: GrowingWord,
    line: LyricLine,
    positionMs: Long,
    visualLine: Int,
    start: Int,
    end: Int,
    top: Float,
    bottom: Float,
    inset: Float,
    peak: Float,
    em: Float,
    growth: CharGrowth,
) {
    // The settle is shared with every other word: a letter comes to rest at the
    // same small lift, and then goes down with the rest of the line.
    val fall = line.wordFall(word.index, positionMs)
    val first = line.wordSpans[word.index].first
    // Room to swell into, above the row rather than inside it. The pivot stays
    // on the row's own middle: scaling about the middle of the *band* would
    // walk every letter downwards as it grew.
    val ceiling = top - peak * GROW_HEADROOM
    val middle = (top + bottom) / 2f
    for (char in start until end) {
        word.sampleInto(char - first, positionMs, growth)
        val from = layout.xOn(char, visualLine, inset)
        val to = layout.xOn(char + 1, visualLine, inset)
        if (to <= from) continue
        val dx = growth.shift * em
        val dy = -growth.rise * peak * fall
        val overhang = (to - from) * (growth.scale - 1f) / 2f
        clipRect(
            left = from - overhang + dx,
            top = ceiling,
            right = to + overhang + dx,
            bottom = bottom,
        ) {
            translate(left = dx, top = dy) {
                scale(growth.scale, growth.scale, Offset((from + to) / 2f, middle)) {
                    this@growEach.drawContent()
                }
            }
        }
    }
}

/**
 * Where an offset sits horizontally *on the row it was cut out of*.
 *
 * [TextLayoutResult.getHorizontalPosition] answers for the row the offset
 * itself belongs to — and the offset one past the last character of a wrapped
 * row belongs to the next row, so asking where a word that runs up to a wrap
 * *ends* gives a position at the far left, one row down. A slice cut between
 * there and the word's start is empty, and the walk then treats the row as
 * finished: everything from that word to the end of the row is never drawn.
 *
 * Whole rows disappeared that way, and Japanese lines disappeared most, because
 * Apple's word spans there are whole phrases and reach a wrap on their own where
 * an English word rarely does.
 *
 * So both ends of a row are answered with the row's own edges, and anything in
 * between is held inside them.
 */
private fun TextLayoutResult.xOn(offset: Int, visualLine: Int, inset: Float): Float {
    val left = getLineLeft(visualLine) + inset
    val right = getLineRight(visualLine) + inset
    return when {
        offset <= getLineStart(visualLine) -> left
        offset >= getLineEnd(visualLine, visibleEnd = true) -> right
        else -> (getHorizontalPosition(offset, usePrimaryDirection = true) + inset)
            .coerceIn(left, right)
    }
}

/** One piece of a line, clipped to its own width and drawn at its own height. */
private fun ContentDrawScope.sliceRisen(
    from: Float,
    top: Float,
    to: Float,
    bottom: Float,
    dy: Float,
) {
    if (to <= from) return
    clipRect(left = from, top = top, right = to, bottom = bottom) {
        translate(top = dy) { this@sliceRisen.drawContent() }
    }
}

/** Where a fractional character index sits across a visual line, in pixels. */
private fun horizontalAt(
    layout: TextLayoutResult,
    chars: Float,
    visualLine: Int,
): Float {
    val lineStart = layout.getLineStart(visualLine)
    val lineEnd = layout.getLineEnd(visualLine, visibleEnd = true)
    val index = chars.toInt().coerceIn(lineStart, lineEnd)
    // Row-aware at both ends: on the last character of a wrapped row the next
    // position belongs to the row below, and read straight it puts the edge
    // back at the left margin — the highlight jumped backwards a letter before
    // every wrap.
    val here = layout.xOn(index, visualLine, 0f)
    val next = layout.xOn((index + 1).coerceAtMost(lineEnd), visualLine, 0f)
    return here + (next - here) * (chars - index)
}

/**
 * Draws this text clipped to its first [revealedChars] characters.
 *
 * Wrapped lines are handled a visual line at a time: the ones already passed
 * are drawn whole, the one holding the boundary is cut at it, and the rest are
 * left to the dim copy. Within a word the cut sits between two character
 * positions, so the edge advances smoothly rather than jumping a letter at a
 * time.
 *
 * The boundary itself is then feathered over [WIPE_FEATHER] rather than left
 * as the cut, which needs the caller to give this an offscreen layer to erase
 * into — see `SweptLyricLine`. Only the line actually being sung carries one;
 * everywhere else the boundary is at one end of the text or the other and
 * there is nothing to soften.
 */
internal fun ContentDrawScope.sweepTo(
    layout: TextLayoutResult,
    revealedChars: Float,
    feather: Boolean,
) {
    if (revealedChars <= 0f) return
    if (revealedChars >= layout.layoutInput.text.length) {
        drawContent()
        return
    }
    for (visualLine in 0 until layout.lineCount) {
        val start = layout.getLineStart(visualLine)
        // Lines beyond the boundary have nothing lit on them, and neither has
        // anything after them.
        if (revealedChars <= start) return
        val end = layout.getLineEnd(visualLine, visibleEnd = true)
        val cut = revealedChars < end
        val right = if (cut) {
            horizontalAt(layout, revealedChars, visualLine)
        } else {
            layout.getLineRight(visualLine)
        }
        val top = layout.getLineTop(visualLine)
        val bottom = layout.getLineBottom(visualLine)
        clipRect(
            left = layout.getLineLeft(visualLine),
            top = top,
            right = right,
            bottom = bottom,
        ) {
            this@sweepTo.drawContent()
        }
        // Only the visual line holding the boundary has an edge to soften; a
        // line revealed to its end runs into the wrap, which is not an edge.
        if (!feather || !cut) continue
        // Scoped to this line's band so the mask cannot reach the lines above
        // and below it: DstIn erases whatever the source does not cover, and
        // outside the clip there is no source at all, so they are left alone.
        // Within it the brush clamps — opaque behind the feather, gone past it.
        clipRect(top = top, bottom = bottom) {
            drawRect(
                brush = Brush.horizontalGradient(
                    0f to Color.White,
                    1f to Color.Transparent,
                    startX = (right - WIPE_FEATHER.toPx())
                        .coerceAtLeast(layout.getLineLeft(visualLine)),
                    endX = right,
                ),
                blendMode = BlendMode.DstIn,
            )
        }
    }
}
