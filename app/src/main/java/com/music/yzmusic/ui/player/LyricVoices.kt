package com.music.yzmusic.ui.player

import android.os.SystemClock
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableLongState
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.withFrameMillis
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.yzmusic.R
import com.music.yzmusic.data.lyrics.CharGrowth
import com.music.yzmusic.data.lyrics.LyricLine
import com.music.yzmusic.data.settings.AppSettings
import com.music.yzmusic.ui.icons.YZMusicIcons
import com.music.yzmusic.ui.rememberIsForeground
import kotlinx.coroutines.delay

import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * How far the words of a line that have not been sung yet sit back.
 *
 * Not very far: a line that is only just coming up still has to be readable as
 * words, and a dimmer tail reads as greyed-out text rather than as something
 * about to be sung. The same value is what a line that has been sung *and* left
 * animates up to, so a finished line closes up as it dims away instead of
 * popping to full brightness in a single frame.
 */
internal const val UNSUNG_ALPHA = 0.45f

/**
 * Slightly brighter on the one-line strip above the scrubber, which has no
 * stack of other lines to be read against and so has to carry the hierarchy
 * on its own.
 */
internal const val UNSUNG_ALPHA_STRIP = 0.55f

/**
 * How the answering vocal is drawn: smaller than the lead and a shade behind
 * it, the way Apple Music hangs a backing line under the one it answers.
 *
 * Small enough to be read as a second voice at a glance and no smaller —
 * these are the words of the song, not a caption.
 */
internal val BACKING_FONT_SIZE = 23.sp
internal val BACKING_LINE_HEIGHT = 29.sp
internal const val BACKING_ALPHA = 0.72f

/**
 * The romanization or translation hung under each line — caption-sized, the
 * way Apple Music prints pronunciation under the lyric, and tucked up into the
 * lead's glow inset so the two read as one line rather than two rows.
 */
internal val SUB_LYRIC_FONT_SIZE = 20.sp
internal val SUB_LYRIC_LINE_HEIGHT = 25.sp
internal val SUB_BACKING_FONT_SIZE = 16.sp
internal val SUB_BACKING_LINE_HEIGHT = 21.sp
internal const val SUB_LYRIC_ALPHA = 0.85f
internal val SUB_LYRIC_TUCK = 6.dp
internal const val SUB_LYRIC_OPEN_MS = 460
internal const val SUB_LYRIC_CLOSE_MS = 260

/**
 * The lane kept clear on the far side of a duet line.
 *
 * Only ever applied to a song that actually has two voices laid out. Without
 * it a long right-hand line reaches all the way back across the panel and the
 * split stops reading as a split at all; with it, each voice keeps its own
 * column even when only one of them is singing.
 */
internal val DUET_LANE = 44.dp

/**
 * How tall a break stands while it is playing.
 *
 * Nothing when it is not: an interlude that held its row open all through the
 * verse either side of it left a hole in the list, and the panel scrolled past
 * empty space to get to the next thing sung. It opens as the singing stops and
 * closes again as it comes back, so the list only carries a break while there
 * is one.
 */
internal val GAP_ROW_HEIGHT = 40.dp
internal val GAP_ROW_SPACING = 16.dp

/**
 * How the stack falls away either side of the line being sung.
 *
 * Indexed by distance from it. Far subtler than a linear ramp: the two lines
 * around the playing one stay legible so you can read ahead and behind, and
 * only past that does the panel let go. The last entry stands for everything
 * further out, which is most of the list.
 */
internal val LINE_FALLOFF_ALPHA = floatArrayOf(1f, 0.8f, 0.7f, 0.58f, 0.46f)
internal val LINE_FALLOFF_BLUR = arrayOf(0.dp, 1.dp, 1.dp, 1.7.dp, 2.4.dp)

/**
 * The shape of the page the lyrics are going to fill.
 *
 * One entry per line of the song, and one number per row that line wraps to.
 * That wrapping is the whole point: at this size a line of a song is rarely one
 * row, so the rows that wrap run nearly the full column and only the last one
 * of each is short. A ladder of evenly spaced bars of assorted lengths is what
 * a loading list looks like — text is blocks with ragged bottoms.
 */
private val SKELETON_BLOCKS = listOf(
    floatArrayOf(0.97f, 0.54f),
    floatArrayOf(0.92f, 0.99f, 0.41f),
    floatArrayOf(0.68f),
    floatArrayOf(0.95f, 0.73f),
    floatArrayOf(0.89f, 0.96f, 0.37f),
)

/**
 * Set to the panel's own metrics: a bar stands the cap height of the 34sp the
 * lines are drawn in, rows of one line sit a line-height apart, and lines are a
 * row's own padding further apart again than that.
 */
private val SKELETON_BAR = 26.dp
private val SKELETON_LEADING = 15.dp
private val SKELETON_BLOCK_GAP = 35.dp
private const val SKELETON_PERIOD_MS = 1_400

/** What a line reads at while the list is being scrolled by hand. */
internal const val BROWSING_ALPHA = 0.8f

/** The playing line sits at 1; the rest sit fractionally back from it. */
internal const val INACTIVE_SCALE = 0.98f

/** A line under a finger dips, the way a button does. */
internal const val PRESSED_SCALE = 0.96f

/**
 * The break between verses, counted out rather than marked.
 *
 * Sized off the same 34sp the lines are set in, so a break sits in the list at
 * the weight of the words either side of it. [GAP_DOT_REST] is what an unlit
 * dot still shows: enough to say how many are coming, not enough to be read as
 * already counted.
 */
internal const val GAP_DOTS = 3
internal val GAP_DOT_SIZE = 13.dp
internal val GAP_DOT_GAP = 5.dp
internal const val GAP_DOT_REST = 0.25f
internal const val GAP_REST_SCALE = 0.76f

/** How long the panel takes to settle on a new line, and how far ahead it starts. */
private const val SCROLL_LEAD_MIN_MS = 350L
private const val SCROLL_LEAD_MAX_MS = 500L

/**
 * The curve every handover runs on: away quickly, in slowly and softly.
 *
 * One curve for the lot — dimming, blurring, scaling and the scroll — so a
 * line handing over reads as a single movement rather than four that happen to
 * start together.
 */
internal val LYRIC_EASING = CubicBezierEasing(0.41f, 0f, 0.12f, 0.99f)
internal const val LYRIC_SETTLE_MS = 400

/**
 * How the rows fan out as the panel moves between lines.
 *
 * They do not travel as a block. Each row after the one being scrolled to sets
 * off slightly later than the row before it, up to a few rows back, so the
 * spacing opens as the panel leaves and closes as it arrives. A block of text
 * sliding rigidly is a list being scrolled; the same lines arriving one behind
 * the other is the panel handing over.
 *
 * Deliberately under half of what the renderer this came from uses. Its lines
 * carry the whole scroll themselves, so a long delay only means arriving late;
 * here the list has already moved underneath them, and the same delay reads as
 * the rows being dragged rather than following.
 */
internal const val STAGGER_STEPS = 3
internal const val STAGGER_FRACTION = 0.06f

/** One handover: how far the panel is going, and how long it is taking. */
internal class ScrollRun(val id: Int, val delta: Float, val durationMs: Int) {
    /** The last row to arrive does so this long after the panel sets off. */
    val spanMs: Float get() = durationMs * (1f + STAGGER_FRACTION * STAGGER_STEPS)
}

/**
 * How long before a line lands the panel starts moving to it — and how long
 * the move then takes, which is the same number.
 *
 * It is the run-up: the silence between the last word of the line being sung
 * and the first of the next. Bounded either side, because that silence is a
 * held breath in one song and half a verse in another, and neither the snap
 * nor the drift is what you want to be reading against.
 */
internal fun scrollLead(lines: List<LyricLine>, positionMs: Long): Long {
    val current = lines.indexOfLast { it.timeMs <= positionMs }
    // Before the first line's own timestamp there is no current line to
    // measure a run-up from. `indexOfLast` answers -1 there, and the guard
    // below does not catch it: `current + 1` is 0, which is a perfectly real
    // line, so the elvis never fires and `lines[current]` indexes at -1.
    //
    // Only reachable while the playhead is genuinely before the first lyric —
    // a track paused at 0:00 whose words start a few seconds in, which is
    // every track that opens on an intro.
    if (current < 0) return SCROLL_LEAD_MIN_MS
    val next = lines.getOrNull(current + 1) ?: return SCROLL_LEAD_MIN_MS
    val gap = next.timeMs - lines[current].endMs
    return gap.coerceIn(SCROLL_LEAD_MIN_MS, SCROLL_LEAD_MAX_MS)
}

/**
 * Keep every unfinished vocal visible, including overlaps spanning more than
 * two rows.
 */
internal fun activeLyricRows(lines: List<LyricLine>, positionMs: Long): List<Int> {
    val latest = lines.indexOfLast { it.timeMs <= positionMs }
    if (latest < 0) return emptyList()
    return (0..latest).filter { index ->
        val line = lines[index]
        index == latest || (!line.isGap &&
            (line.hasKnownEnd || line.background?.hasKnownEnd == true) &&
            line.timeMs <= positionMs && positionMs < line.endMs)
    }
}


/**
 * The song position, ticking every frame.
 *
 * The player reports where it is about twice a second, which is fine for a
 * scrubber and far too coarse for a highlight that has to keep up with a
 * singer. This carries that report forward on the frame clock between
 * reports. Small corrections hold the highlight until playback catches up;
 * discontinuities still reset immediately so seeking remains responsive.
 *
 * Returned as state rather than a plain value on purpose: read inside a draw
 * lambda, only the draw phase re-runs each frame. Read in composition, the
 * whole line would recompose sixty times a second.
 */
@Composable
internal fun rememberLyricClock(
    trackKey: Any,
    positionMs: Long,
    isPlaying: Boolean,
): MutableLongState {
    val startedAtMs = remember(trackKey) { SystemClock.elapsedRealtime() }
    val clock = remember(trackKey) { mutableLongStateOf(positionMs) }
    val reconciler = remember(trackKey) {
        LyricClockReconciler(positionMs, startedAtMs, isPlaying)
    }
    // Gated on the app being on screen. The loop asks for a frame, writes a
    // value that invalidates a drawing, and is handed the next frame for it —
    // which is a request to render continuously for as long as it runs. That is
    // the right trade for a lyric being read and the wrong one for a phone in a
    // pocket, and the composition alone cannot tell the two apart.
    //
    // Resuming needs no catch-up: [positionMs] is a key, so coming back
    // restarts the effect and reconciles the latest playback report before
    // requesting another frame.
    val foreground = rememberIsForeground()
    LaunchedEffect(positionMs, isPlaying, foreground) {
        clock.longValue = reconciler.reconcile(
            displayedMs = clock.longValue,
            reportedMs = positionMs,
            observedAtMs = SystemClock.elapsedRealtime(),
            isPlaying = isPlaying,
        )
        if (!isPlaying || !foreground) return@LaunchedEffect
        val firstFrame = withFrameMillis { it }
        while (true) {
            withFrameMillis { frame ->
                // Advance from the authoritative report, not the held display value:
                // otherwise each small correction would accumulate permanent drift.
                clock.longValue = maxOf(clock.longValue, positionMs + frame - firstFrame)
            }
        }
    }
    return clock
}

/** The position the lyrics draw themselves against, given the user's offset. */
internal fun adjustedLyricsPosition(positionMs: Long, offsetMs: Int): Long =
    (positionMs - offsetMs.toLong()).coerceAtLeast(0L)

/** Where a tap on a lyric should seek, given the user's offset. */
internal fun adjustedLyricsSeekTarget(lineTimeMs: Long, offsetMs: Int): Long =
    (lineTimeMs + offsetMs.toLong()).coerceAtLeast(0L)

private data class TranslationParticle(
    val anchor: Offset,
    val drift: Offset,
    val radius: Float,
    val delay: Float,
)

private const val TRANSLATION_MOTION_MS = 540
private const val PARTICLES_PER_VOICE = 18

/** Glyph positions are cached at layout time; the shared clock is draw-only. */
private fun Modifier.lyricParticles(
    layout: TextLayoutResult?,
    progress: State<Float>?,
    room: Dp,
): Modifier {
    if (layout == null || progress == null) return this
    return drawWithCache {
        val text = layout.layoutInput.text.text
        val candidates = text.indices.filter { text[it].isLetterOrDigit() }
        val random = Random(text.hashCode())
        val inset = room.toPx()
        val particles = candidates.shuffled(random).take(PARTICLES_PER_VOICE).map { index ->
            val glyph = layout.getBoundingBox(index)
            TranslationParticle(
                anchor = glyph.center + Offset(inset, inset),
                drift = Offset(
                    (random.nextFloat() - 0.5f) * 12.dp.toPx(),
                    -(5f + random.nextFloat() * 11f).dp.toPx(),
                ),
                radius = (0.65f + random.nextFloat() * 0.65f).dp.toPx(),
                delay = 0.16f * index / text.length.coerceAtLeast(1),
            )
        }
        onDrawWithContent {
            drawContent()
            val value = progress.value
            if (value > 0f && value < 1f) {
                particles.forEach { particle ->
                    val t = ((value - particle.delay) / 0.84f).coerceIn(0f, 1f)
                    val envelope = sin(PI * t).toFloat()
                    val ease = 1f - (1f - t) * (1f - t)
                    val center = particle.anchor + Offset(
                        particle.drift.x * ease,
                        particle.drift.y * ease + 3.dp.toPx() * t * t,
                    )
                    // Two inexpensive circles give a soft halo without another
                    // blur layer; opacity rises and falls without a flash.
                    drawCircle(Color.White, particle.radius * 2.7f, center, alpha = envelope * 0.07f)
                    drawCircle(Color.White, particle.radius, center, alpha = envelope * 0.58f)
                }
            }
        }
    }
}

/**
 * A lyric line with the sung part of it lit, the rest dimmed, and the boundary
 * travelling across the words in time with the vocal.
 *
 * Two copies of the same text stacked: a dim one and a bright one clipped to
 * whatever has been sung. Same string, same style, same constraints, so the
 * two lay out identically and the bright copy lands exactly on top of the dim
 * one. The alternative — colouring an AnnotatedString word by word — can only
 * change a whole word at a time, which turns the sweep into a flicker.
 *
 * The clip is recomputed in the draw phase, so a frame costs one clip and one
 * redraw of already-measured text.
 *
 * [glowAlpha] adds Apple's bloom: a third copy, blurred, behind the other two
 * and clipped to the letters of whatever word is being held. Blurring *after*
 * the clip rather than before is what makes the halo bleed out past the letter
 * it belongs to, which is the part that reads as light coming off a carried
 * note rather than a drop shadow sitting under the line.
 */
@Composable
internal fun SweptLyricLine(
    line: LyricLine,
    clock: MutableLongState,
    style: TextStyle,
    dimAlpha: Float,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    glowAlpha: Float = 0f,
    glowRadius: Dp = GLOW_RADIUS,
    glowRoom: Dp = 0.dp,
    feather: Boolean = false,
    rise: Boolean = true,
    alignEnd: Boolean = false,
    translationProgress: State<Float>? = null,
) {
    var layout by remember(line) { mutableStateOf<TextLayoutResult?>(null) }

    // Filled in and read back a letter at a time inside the draw lambdas, and
    // shared by all three copies of the line — they draw one after another on
    // the same thread, so there is only ever one letter in hand. Held here
    // rather than allocated per frame: a held word is seven letters at the
    // outside, but this runs on every frame of every line that has one.
    val growth = remember { CharGrowth() }

    // Carried by every copy: identical insets keep them laying out identically,
    // and the inset is what gives the blurred copy's layer somewhere to put the
    // halo. Sits inside the blur and outside the draw lambdas, so text-layout
    // coordinates and draw coordinates still agree.
    //
    // Off unless asked for. Only the full panel can afford it — it takes the
    // space back off its own row spacing and content padding. Handed to the
    // one-line strip above the scrubber, where there is no glow to make room
    // for and nothing paying the space back, it just left the line sitting in
    // a pocket of air with the chevron pushed off it.
    val room = if (glowRoom > 0.dp) Modifier.padding(glowRoom) else Modifier

    // Sits outside [room] and outside the sweep, so what it moves is the
    // finished picture of the word — dim tail, lit head and all — rather than
    // one copy sliding out from under another. Carried by both copies from the
    // same arithmetic, which is what keeps them on top of each other.
    //
    // Off for the one-line strip above the scrubber ([rise] = false). The lift
    // belongs to a page of lyrics, where a word rising out of the line it sits
    // in is the thing being read; on a single line pinned between the credits
    // and the slider it has nothing to rise away from and reads as the strip
    // itself twitching.
    val riseAgainst: (Modifier) -> Modifier = { inner ->
        if (!rise) {
            inner
        } else {
            Modifier
                .drawWithContent {
                    val measured = layout
                    if (measured == null || line.words.isEmpty()) {
                        drawContent()
                    } else {
                        riseWith(
                            layout = measured,
                            line = line,
                            positionMs = clock.longValue,
                            inset = glowRoom.toPx(),
                            peak = WORD_RISE.toPx(),
                            growth = growth,
                        )
                    }
                }
                .then(inner)
        }
    }

    val sweep = Modifier.drawWithContent {
        val position = clock.longValue
        when {
            // Sung and done with: all of it is lit. Checked first so the lines
            // above and below the playing one — which are in this same state
            // for minutes at a time — cost a comparison per frame rather than
            // a walk of their words.
            position >= line.endMs -> drawContent()
            // Not started: nothing lit, the dim copy is the whole of it.
            position <= line.timeMs -> Unit
            else -> layout?.let { sweepTo(it, line.revealedChars(position), feather) }
        }
    }

    // A right-hand duet line right-aligns twice over: the block within the row,
    // for the case where it is one short line in a wide panel, and the lines
    // within the block, for the case where it has wrapped. Neither alone is
    // enough, and the three copies all take both, so they still land on top of
    // each other.
    Box(
        modifier.lyricParticles(layout, translationProgress, glowRoom),
        contentAlignment = if (alignEnd) Alignment.TopEnd else Alignment.TopStart,
    ) {
        Text(
            text = line.text,
            style = style,
            color = Color.White.copy(alpha = dimAlpha),
            maxLines = maxLines,
            overflow = overflow,
            onTextLayout = { layout = it },
            modifier = riseAgainst(room),
        )
        if (glowAlpha > 0.01f) {
            Text(
                text = line.text,
                style = style,
                color = Color.White,
                maxLines = maxLines,
                overflow = overflow,
                modifier = Modifier
                    .graphicsLayer { alpha = glowAlpha }
                    .blur(glowRadius, BlurredEdgeTreatment.Unbounded)
                    .then(room)
                    // Each letter is masked to its own brightness with DstIn,
                    // which needs a layer of its own to erase into — against the
                    // backdrop it would take the artwork with it.
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    .drawWithContent {
                        // Deliberately not the shared sweep: that lights
                        // everything sung so far, and this lights only the words
                        // being held. Most lines draw nothing here at all, which
                        // is the whole difference between this and a halo
                        // travelling under the highlight.
                        val measured = layout ?: return@drawWithContent
                        glowGrown(
                            layout = measured,
                            line = line,
                            positionMs = clock.longValue,
                            inset = glowRoom.toPx(),
                            peak = WORD_RISE.toPx(),
                            growth = growth,
                        )
                    },
            )
        }
        Text(
            text = line.text,
            style = style,
            color = Color.White,
            maxLines = maxLines,
            overflow = overflow,
            // The feather erases into this layer, so the layer has to exist —
            // and only while it is being drawn. Every line carrying one would
            // put the whole panel through an offscreen buffer to soften an edge
            // that at most two of them have.
            modifier = riseAgainst(
                Modifier
                    .graphicsLayer {
                        compositingStrategy = if (feather) {
                            CompositingStrategy.Offscreen
                        } else {
                            CompositingStrategy.Auto
                        }
                    }
                    .then(room)
                    .then(sweep),
            ),
        )
    }
}

/**
 * What the panel draws under each line, and how far it has opened.
 *
 * One clock for the whole panel rather than an animation per row: a long song
 * is a hundred rows, and each would otherwise start, run and stop its own
 * Animatable on every toggle. [progress] is only ever read in layout and draw
 * (see [revealBelow]), so opening it costs a relayout of the rows on screen
 * and not one recomposition.
 */
internal class SubLyricsReveal(
    val progress: State<Float>,
    lines: State<List<LyricLine?>?>,
) {
    /**
     * Held through the collapse, so the words fold away rather than vanish.
     *
     * Nullable per row rather than per list, and index-for-index with the
     * primary list. Pairing is already done — and already filtered for rows
     * that would add nothing — by `pairLyricLayers`, which builds the rows
     * this reads. Compressing the gaps out here and re-deriving an index into
     * the primary list from the position in this one would undo that and put
     * a translation under the wrong words, which is the one failure this
     * whole arrangement exists to prevent.
     */
    val lines: List<LyricLine?>? by lines
}

@Composable
internal fun rememberSubLyricsReveal(
    target: List<LyricLine?>?,
    trackKey: String,
): SubLyricsReveal {
    val reduceAnimation by AppSettings.reduceAnimation.collectAsStateWithLifecycle()
    // Keyed to the track: a new song arrives with nothing under it, and must
    // not fold the last song's translation away over its own opening lines.
    val shown = remember(trackKey) { mutableStateOf(target) }
    val progress = remember(trackKey) { Animatable(if (target != null) 1f else 0f) }
    LaunchedEffect(target, trackKey, reduceAnimation) {
        if (reduceAnimation) {
            shown.value = target
            progress.snapTo(if (target != null) 1f else 0f)
            return@LaunchedEffect
        }
        // Switching straight from romanized to translated closes the one
        // before opening the other, so two scripts never share the gap.
        if (shown.value != null && shown.value !== target && progress.value > 0f) {
            progress.animateTo(0f, tween(SUB_LYRIC_CLOSE_MS, easing = FastOutSlowInEasing))
        }
        if (target != null) {
            shown.value = target
            progress.animateTo(1f, tween(SUB_LYRIC_OPEN_MS, easing = LYRIC_EASING))
        } else {
            shown.value = null
        }
    }
    return remember(trackKey) { SubLyricsReveal(progress.asState(), shown) }
}

/**
 * Opens downward out of the line above: the height grows from nothing while
 * the words slide down from behind the original and fade up. Both read
 * [progress] outside composition, so only layout and draw run per frame.
 */
internal fun Modifier.revealBelow(progress: State<Float>): Modifier = this
    .layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        val open = progress.value
        val height = (placeable.height * open).roundToInt()
        layout(placeable.width, height) {
            placeable.placeWithLayer(0, 0) {
                translationY = -placeable.height * (1f - open) * 0.6f
                alpha = open * open
            }
        }
    }

/**
 * The answering vocal without the parentheses every text-only source wraps it
 * in. Apple Music draws its own equivalent line bare, and the brackets were
 * only ever there to mark the split before there was a row of its own to draw
 * it on.
 *
 * The LRC writer still gets the line with its brackets: that punctuation is
 * what the provider published, so a downloaded file keeps it. This is a
 * display-only trim, done here rather than in the data layer, and applied to
 * the words too, not just [LyricLine.text] — [SweptLyricLine] measures the
 * words against the text it draws, and a sweep reading "(echoed" against a
 * line reading "echoed" would search for a substring that is no longer there.
 */
internal fun LyricLine.withoutBracketPunctuation(): LyricLine = copy(
    text = text.stripParens(),
    words = words.mapNotNull { word ->
        word.text.stripParens().takeIf { it.isNotEmpty() }?.let { word.copy(text = it) }
    },
)

private fun String.stripParens(): String = replace("(", "").replace(")", "").trim()

/**
 * One voice of a row in the lyrics panel — the lead, or the answering line
 * drawn under it.
 *
 * Both go through the same sweep. A backing vocal carries its own word
 * timings, so it lights up on its own clock rather than borrowing the lead's:
 * that is the whole point of splitting it out, and it is why the bracket no
 * longer gets cut off when the next line's stamp arrives mid-phrase.
 */
@Composable
internal fun PanelVoice(
    line: LyricLine,
    clock: MutableLongState,
    style: TextStyle,
    isActive: Boolean,
    /** Whether the panel has already left this line behind. */
    sung: Boolean,
    /** Whether the source stamps its lines at all. */
    synced: Boolean,
    browsing: Boolean,
    glowAlpha: Float,
    room: Dp,
    /** Whether this line is one of the right-hand voice's. */
    alignEnd: Boolean,
    translationProgress: State<Float>? = null,
    modifier: Modifier = Modifier,
) {
    if (line.isWordSynced && !browsing) {
        // Every word-synced line goes through the sweep, not just the playing
        // one — a line that has already been sung is fully revealed and one
        // still to come is not, which falls out of the same arithmetic.
        //
        // Running it only on the active line meant swapping this composable
        // for a plain Text the instant a line handed over, and the two
        // disagreed about the brightness of the words: the tail of the line
        // popped up to meet the rest of it in a single frame. Animating the
        // tail instead lets a finished line close up as it dims away.
        val tail by animateFloatAsState(
            targetValue = if (sung) 1f else UNSUNG_ALPHA,
            label = "lyricTail",
        )
        SweptLyricLine(
            line = line,
            clock = clock,
            style = style,
            dimAlpha = tail,
            modifier = modifier,
            glowAlpha = glowAlpha,
            glowRoom = room,
            feather = isActive,
            alignEnd = alignEnd,
            translationProgress = translationProgress,
        )
    } else if (line.isWordSynced) {
        // Browsing: keep the sweep so sung lines stay fully lit and unsung
        // ones stay dim, but skip the bloom — it is a playback flourish, not
        // a browsing aid. Non-active lines get the same dim tail as when we
        // are not browsing; the active line stays at full brightness.
        val tail by animateFloatAsState(
            targetValue = if (sung) 1f else UNSUNG_ALPHA,
            label = "lyricTail",
        )
        SweptLyricLine(
            line = line,
            clock = clock,
            style = style,
            dimAlpha = tail,
            modifier = modifier,
            glowAlpha = 0f,
            glowRoom = room,
            alignEnd = alignEnd,
            translationProgress = translationProgress,
        )
    } else {
        // No word timings, so there is no sweep to light the words as they are
        // sung: the line lights whole, the moment it starts.
        //
        // It still has to hold itself back until then. The parent's falloff
        // alone left a line not yet sung reading brighter here than the same
        // line does on a word-synced source, where the unsung words sit at
        // [UNSUNG_ALPHA] underneath it — the two have to agree about what "not
        // yet" looks like, or changing provider changes the panel rather than
        // the words. Lyrics with no timing at all are all "now", and stay lit.
        val lit by animateFloatAsState(
            targetValue = if (!synced || sung || isActive) 1f else UNSUNG_ALPHA,
            label = "lyricLit",
        )
        var layout by remember(line.text) { mutableStateOf<TextLayoutResult?>(null) }
        Text(
            text = line.text,
            style = style,
            color = Color.White.copy(alpha = lit),
            onTextLayout = { layout = it },
            modifier = modifier.lyricParticles(layout, translationProgress, room).padding(room),
        )
    }
}

/**
 * Stands in for the lyrics while the lookup is still out.
 *
 * Without it the panel had one empty state doing two jobs: a lookup that had
 * come back with nothing and a lookup that had not come back yet both said "No
 * lyrics for this track", so every track was declared to have none for as long
 * as it took to find out that it did.
 */
@Composable
internal fun LyricsSkeleton(modifier: Modifier = Modifier) {
    val sweep = rememberInfiniteTransition(label = "lyricsSkeleton")
        .animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                tween(SKELETON_PERIOD_MS, easing = LinearEasing),
            ),
            label = "sweep",
        )
    BoxWithConstraints(
        // No gutter of its own: the list this stands in for bleeds out to the
        // panel's full width and puts the gutter back as content padding, so
        // the words land level with the panel's own edge and so does this.
        modifier.padding(top = 40.dp),
    ) {
        // Every bar sweeps against the width of the column rather than its own,
        // so one band crosses the whole page. Measured per bar, a short row
        // lights end to end in the time a long one takes to get halfway, and
        // the block reads as a row of separate things loading separately.
        val column = maxWidth
        Column(verticalArrangement = Arrangement.spacedBy(SKELETON_BLOCK_GAP)) {
            SKELETON_BLOCKS.forEach { rows ->
                Column(verticalArrangement = Arrangement.spacedBy(SKELETON_LEADING)) {
                    rows.forEach { fraction ->
                        Box(
                            Modifier
                                .fillMaxWidth(fraction)
                                .height(SKELETON_BAR)
                                .clip(RoundedCornerShape(4.dp))
                                // Read in the draw block, not the body: a
                                // pageful of these would otherwise recompose on
                                // every frame, and all any of them needs per
                                // frame is a fresh gradient.
                                .drawWithCache {
                                    val full = column.toPx()
                                    val band = full * 0.45f
                                    val startX = -band + sweep.value * (full + band * 2)
                                    val brush = Brush.horizontalGradient(
                                        colors = listOf(
                                            Color.White.copy(alpha = 0.10f),
                                            Color.White.copy(alpha = 0.26f),
                                            Color.White.copy(alpha = 0.10f),
                                        ),
                                        startX = startX,
                                        endX = startX + band,
                                    )
                                    onDrawBehind { drawRect(brush) }
                                },
                        )
                    }
                }
            }
        }
    }
}

/**
 * The single lyric line above the scrubber.
 *
 * Transitions between lines use [AnimatedContent] with vertical slide and
 * fade, respecting [AppSettings.reduceAnimation].
 */
@Composable
internal fun CurrentLyricLine(
    lines: List<LyricLine>,
    trackKey: Any,
    positionMs: Long,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val isSynced = remember(lines) { lines.any { it.timeMs > 0L } }
    if (!isSynced) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onClick)
                .padding(vertical = 4.dp),
        ) {
            Icon(
                imageVector = YZMusicIcons.MusicNote,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = stringResource(
                    R.string.lyrics_available_tap_to_view,
                ),
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.width(6.dp))
            Icon(
                imageVector = YZMusicIcons.ChevronRight,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.5f),
                modifier = Modifier.size(14.dp),
            )
        }
        return
    }

    val clock = rememberLyricClock(trackKey, positionMs, isPlaying)

    val index by remember(lines) {
        derivedStateOf {
            lines.indexOfLast { it.timeMs <= clock.longValue }
        }
    }
    val current = lines.getOrNull(index)
    // Before the first line, and through instrumental breaks, show the note.
    val instrumental = current == null || current.isGap
    // Everything ahead of the first sung line is the intro — LRC files open on a
    // bare [00:00.00] gap, so that stretch is gap lines rather than nothing.
    val firstSung = remember(lines) { lines.indexOfFirst { !it.isGap } }
    val intro = instrumental && firstSung >= 0 && index < firstSung
    // The intro gets one of the slang lines; mid-song breaks stay plain.
    val introLines = stringArrayResource(
        R.array.lyrics_intro_lines,
    )
    // `stringArrayResource` may return a new array on every recomposition.
    // Keying this selection to that array made the intro copy change whenever
    // the playback clock recomposed the strip. Pick it once for this track.
    val introLine = remember(trackKey) { introLines.random() }
    // The strip is one line and switches the moment the next one is due, so
    // the answering vocal — where there is one — has nowhere to go: showing
    // it would mean either cutting it short when the next line arrives or
    // holding the strip back and leaving a gap before the next line's own
    // words appear. The panel has the room to draw it properly; here it
    // is simply left off, same as before this line had a bracket in it.
    val text = when {
        intro -> introLine
        instrumental -> stringResource(
            R.string.instrumental,
        )
        else -> current.text
    }

    val reduceAnimation by AppSettings.reduceAnimation.collectAsStateWithLifecycle()

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
    ) {
        if (instrumental) {
            Icon(
                imageVector = YZMusicIcons.MusicNote,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(6.dp))
        }
        AnimatedContent(
            targetState = Triple(index, current, text),
            transitionSpec = {
                val duration = if (reduceAnimation) 0 else 340
                if (reduceAnimation) {
                    (fadeIn(snap()) togetherWith
                        fadeOut(snap())).using(
                        SizeTransform(clip = false, sizeAnimationSpec = { _, _ ->
                            snap()
                        }),
                    )
                } else {
                    (fadeIn(animationSpec = tween(duration, easing = FastOutSlowInEasing)) +
                        slideInVertically(
                            animationSpec = tween(duration, easing = FastOutSlowInEasing),
                        ) { height -> (height * 0.35f).toInt() })
                        .togetherWith(
                            fadeOut(animationSpec = tween(duration, easing = FastOutSlowInEasing)) +
                                slideOutVertically(
                                    animationSpec = tween(duration, easing = FastOutSlowInEasing),
                                ) { height -> -(height * 0.35f).toInt() },
                        ).using(
                            SizeTransform(
                                clip = false,
                                sizeAnimationSpec = { _, _ ->
                                    tween(duration, easing = FastOutSlowInEasing)
                                },
                            ),
                        )
                }
            },
            label = "currentLyricTransition",
            modifier = Modifier.weight(1f, fill = false),
        ) { (_, lineItem, lineText) ->
            val itemInstrumental = lineItem == null || lineItem.isGap
            val swept = lineItem?.takeIf { !itemInstrumental && it.isWordSynced }
            if (swept != null) {
                SweptLyricLine(
                    line = swept,
                    clock = clock,
                    style = MaterialTheme.typography.titleMedium,
                    dimAlpha = UNSUNG_ALPHA_STRIP,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    rise = false,
                )
            } else {
                Text(
                    text = lineText,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (itemInstrumental) Color.White.copy(alpha = 0.5f) else Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(6.dp))
        // Disclosure hint: this strip opens the full lyrics screen.
        Icon(
            imageVector = YZMusicIcons.ChevronRight,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.5f),
            modifier = Modifier.size(14.dp),
        )
    }
}

private const val LYRICS_UNAVAILABLE_HOLD_MS = 5_000L
private const val LYRICS_UNAVAILABLE_FADE_MS = 900

/**
 * Stands in for [CurrentLyricLine] once a lookup has come back empty — shown
 * for a few seconds so it registers, then left to fade rather than snapping
 * out or lingering for the rest of the track.
 */
@Composable
internal fun LyricsUnavailableLine(trackKey: Any, modifier: Modifier = Modifier) {
    var visible by remember(trackKey) { mutableStateOf(true) }
    LaunchedEffect(trackKey) {
        delay(LYRICS_UNAVAILABLE_HOLD_MS)
        visible = false
    }
    val alpha by animateFloatAsState(
        targetValue = if (visible) 0.55f else 0f,
        animationSpec = tween(durationMillis = LYRICS_UNAVAILABLE_FADE_MS),
        label = "lyricsUnavailableAlpha",
    )
    Text(
        text = stringResource(
            R.string.lyrics_not_available,
        ),
        style = MaterialTheme.typography.titleMedium,
        color = Color.White,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .padding(vertical = 4.dp)
            .graphicsLayer { this.alpha = alpha },
    )
}

/** Stands in for [CurrentLyricLine] while a lookup is still in flight. */
@Composable
internal fun LyricsLoadingLine(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = Color.White.copy(alpha = 0.55f),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.padding(vertical = 4.dp),
    )
}
