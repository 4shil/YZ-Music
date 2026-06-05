/*
 * Ported from Orchard (https://github.com/SFG5453/Orchard).
 *
 * Copyright (C) 2026 SFG545 (original Orchard implementation)
 * Copyright (C) 2026 Kushagra Singh (YZ Music adaptation)
 *
 * Orchard's original source is licensed under the GNU Affero General Public
 * License, version 3 or later. Per AGPLv3 section 13, this file is combined
 * here into YZ Music -- a work licensed under the GNU General Public
 * License, version 3 or later -- and remains itself governed by the AGPLv3
 * as part of that combination.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero
 * General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package com.music.yzmusic.playback.smart

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * The confidence-aware transition policy.
 *
 * Analysis happens ahead of playback (see [TrackAnalyzer]), and the runtime
 * only decides how ambitious a transition the stored evidence can support.
 * Ambition degrades in explicit tiers as certainty falls (see
 * [TransitionTier]) rather than letting one engine quietly do beat math on
 * junk data.
 *
 * Every judgement here is made from stored analysis fields and their
 * confidences; nothing in this file touches PCM.
 */

/**
 * Below this the analyzer's beat grid is treated as a guess, and no renderer
 * may stretch or phase-align against it. Catalog tempo lookups merge in with
 * `beatConfidence` 0, so a metadata BPM alone can never authorize
 * beat-matching.
 */
const val MIN_BEATMATCH_CONFIDENCE = 0.55

/**
 * Below this on both tracks, even the DJ-assisted crossfade (beat-quantized
 * anchors, EQ handoff) is off the table and the mix degrades to a plain fade.
 */
const val MIN_DJ_CONFIDENCE = 0.2

/** One octave either side of a typical dance tempo; outside this the analysis is noise. */
const val MIN_BPM = 40.0
const val MAX_BPM = 220.0

/** How far a tempo pairing may drift from unity and still be considered transparent to stretch. */
const val MAX_STRETCH_DEVIATION = 0.04

/**
 * A vocal-activity mask value at or above this counts as singing. A fallback
 * analyzer that emits a flat 0.5 mask never trips vocal logic; only a real
 * mask can.
 */
const val VOCAL_ACTIVE_THRESHOLD = 0.6

/**
 * How much of the outgoing track's remaining *music* a transition may skip by
 * ending before its content does. A transition is allowed to leave a short
 * tail unplayed; it is not allowed to cut the song short.
 */
const val MAX_DISCARDED_MUSIC_SECONDS = 12.0

/**
 * Fraction of a track's own loud-end reference below which a sample counts as
 * silence rather than music, so a genuine gap costs nothing against the budget.
 */
private const val AUDIBLE_ENERGY_FRACTION = 0.1

/**
 * How much each candidate type is trusted as an entry point before scoring.
 * Drops are where an arrangement arrives, so they dominate; a pickup is just
 * "the file starts making sound" and a phrase boundary is only a grid line.
 */
private val MIX_IN_TYPE_WEIGHT = mapOf(
    "main_drop" to 0.5,
    "intro_drop" to 0.4,
    "pickup" to 0.15,
    "phrase" to 0.1,
)

/**
 * Mirrors the analyzer's own scoring of mix-out candidates, used when an
 * analysis carries the scalar fields but not the candidate list.
 */
private val MIX_OUT_TYPE_SCORE = mapOf(
    "energy_cliff" to 0.95,
    "interior_mix_out" to 0.95,
    "outro_start" to 0.9,
    "content_end" to 0.75,
)

/** Non-finite guards, matching the desktop planner's coercion of `NaN`/`Infinity` to zero. */
internal fun Double.orZero(): Double = if (isFinite()) this else 0.0

internal fun Double?.orZero(): Double = if (this != null && isFinite()) this else 0.0

internal fun clamp(value: Double, min: Double, max: Double): Double =
    if (value.isFinite()) max(min, min(max, value)) else min

/**
 * Halves or doubles [incomingBpm] until it is as close as possible to
 * [outgoingBpm], the way a DJ counts a 63 BPM track against a 126 BPM one.
 */
fun alignTempoOctave(outgoingBpm: Double, incomingBpm: Double): Double {
    if (outgoingBpm <= 0 || incomingBpm <= 0) return incomingBpm
    var aligned = incomingBpm
    while (aligned / outgoingBpm > 1.5) aligned /= 2
    while (aligned / outgoingBpm < 0.67) aligned *= 2
    return aligned
}

/**
 * Mean vocal activity over [start]..[end] on a track's own timeline, or null
 * when the analysis carries no usable mask there. The mask is indexed against
 * [TrackAnalysis.energyCurve] times.
 */
fun vocalActivityBetween(analysis: TrackAnalysis, start: Double, end: Double): Double? {
    val mask = analysis.vocalActivityMask
    val curve = analysis.energyCurve
    if (mask.isEmpty() || mask.size != curve.size || end <= start) return null
    var sum = 0.0
    var count = 0
    for (index in mask.indices) {
        val time = curve[index].time
        if (!time.isFinite() || time < start || time > end) continue
        val value = mask[index]
        if (!value.isFinite()) continue
        sum += value
        count += 1
    }
    return if (count > 0) sum / count else null
}

/**
 * Both windows measurably singing at once. Null means "no evidence", which
 * never blocks; absence of a mask is not absence of a vocal, but acting on it
 * would punish every track a fallback analyzer handled.
 */
fun isVocalClash(outgoingActivity: Double?, incomingActivity: Double?): Boolean =
    outgoingActivity != null &&
        incomingActivity != null &&
        outgoingActivity >= VOCAL_ACTIVE_THRESHOLD &&
        incomingActivity >= VOCAL_ACTIVE_THRESHOLD

/**
 * How strongly two windows sing over each other: 0 for nothing worth acting on,
 * 1 for two fully vocal passages landing on one another.
 *
 * [isVocalClash]'s graded counterpart, and the reason for having both. A boolean
 * is the right shape for a routing decision — shorten the overlap or don't — but
 * it is the wrong shape for the renderer, which has to decide *how hard* to pull
 * the two voices apart. A pair scraping over the threshold and two choruses
 * colliding are the same `true` and want visibly different treatment.
 *
 * Governed by the quieter of the two, because a clash needs both sides: an
 * instrumental passage under a vocal is not a clash however loud the vocal is,
 * and taking a mean would let one strong side manufacture one.
 *
 * Null on either side is no evidence and answers zero, which leaves whatever the
 * caller would have done anyway. Absence of a mask is not absence of a vocal —
 * but acting on it would filter every track a fallback analyzer handled.
 */
fun vocalOverlapAmount(outgoingActivity: Double?, incomingActivity: Double?): Double {
    if (outgoingActivity == null || incomingActivity == null) return 0.0
    val both = min(outgoingActivity, incomingActivity)
    if (both <= VOCAL_ACTIVE_THRESHOLD) return 0.0
    return ((both - VOCAL_ACTIVE_THRESHOLD) / (1.0 - VOCAL_ACTIVE_THRESHOLD)).coerceIn(0.0, 1.0)
}

/**
 * The fraction of a planned overlap where **both** tracks are singing at the
 * same instant, or null when either side has no mask.
 *
 * Why this exists alongside [vocalActivityBetween]: that one answers with a
 * *mean* over the window, and a mean is the wrong statistic for a clash. Twelve
 * seconds holding three seconds of vocal and nine of instrumental averages well
 * under [VOCAL_ACTIVE_THRESHOLD] and reads as clear — while the listener plainly
 * hears two voices for those three seconds. Every clash short of about half the
 * overlap was being averaged into silence, which is why a transition could be
 * planned as clean and still land two vocals on top of each other.
 *
 * Instant by instant instead. The outgoing track's own energy-curve samples are
 * the clock; each is mapped onto the incoming timeline through [rate], because a
 * stretched incoming track covers proportionally more of its own timeline in the
 * same wall-clock second. Unmeasured regions sit at the analyzer's neutral 0.5,
 * below the threshold, so they count as "not singing" rather than as evidence.
 *
 * Both curves are time-ascending, so the incoming index only ever moves forward:
 * this is one pass over each, not a search per sample.
 */
fun simultaneousVocalFraction(
    outgoing: TrackAnalysis,
    incoming: TrackAnalysis,
    outStart: Double,
    outEnd: Double,
    inStart: Double,
    rate: Double,
): Double? {
    val outMask = outgoing.vocalActivityMask
    val outCurve = outgoing.energyCurve
