package com.music.yzmusic.playback.smart

import kotlin.math.max
import kotlin.math.min

/** How much of a recording Automix is allowed to reshape. */
enum class AutomixPreservation { FULL_TRACK, BALANCED, DJ_FREEDOM }

/** Arrangement label inferred without adding another model to the APK. */
enum class MusicalSectionType { INTRO, BUILD, DROP, VERSE, CHORUS, BREAK, OUTRO, UNKNOWN }

private const val POLICY_VOCAL_ACTIVE_THRESHOLD = 0.55

data class MusicalSection(
    val start: Double,
    val end: Double,
    val type: MusicalSectionType,
    val confidence: Double,
    val energy: Double,
    val vocalActivity: Double,
)

data class PreservationLimits(
    val maximumAudibleTrimSeconds: Double,
    val maximumTrimBeats: Int,
    val minimumPlayedFraction: Double,
)

fun preservationLimits(policy: AutomixPreservation): PreservationLimits = when (policy) {
    AutomixPreservation.FULL_TRACK -> PreservationLimits(2.0, 0, 0.98)
    AutomixPreservation.BALANCED -> PreservationLimits(12.0, 16, 0.75)
    AutomixPreservation.DJ_FREEDOM -> PreservationLimits(30.0, 64, 0.60)
}

/** Low-confidence structure never authorizes an editorial cut. */
fun effectivePreservation(
    requested: AutomixPreservation,
    structuralConfidence: Double,
): AutomixPreservation = if (structuralConfidence >= 0.55) requested else AutomixPreservation.FULL_TRACK

/** Maximum transparent stretch for the selected preservation personality. */
fun maximumStretch(policy: AutomixPreservation): Double =
    if (policy == AutomixPreservation.DJ_FREEDOM) 0.06 else 0.04

/**
 * Applies the audible-tail, beat and minimum-content rails to a proposed exit.
 * Protected sections (choruses, drops, active vocals) are never cut through;
 * the next boundary at or after the proposal wins, with content end as the safe fallback.
 */
fun preserveMixOut(
    analysis: TrackAnalysis,
    proposed: Double,
    requested: AutomixPreservation = AutomixPreservation.BALANCED,
): Double {
    val end = analysis.contentEndTime.takeIf { it > 0 } ?: analysis.duration
    if (end <= 0) return proposed.coerceAtLeast(0.0)
    val policy = effectivePreservation(requested, analysis.structuralConfidence)
    val limits = preservationLimits(policy)
    val beat = analysis.beatInterval.takeIf { it > 0 }
        ?: analysis.bpm.takeIf { it > 0 }?.let { 60.0 / it }
    val beatLimit = beat?.let { limits.maximumTrimBeats * it }
        ?.takeIf { limits.maximumTrimBeats > 0 } ?: limits.maximumAudibleTrimSeconds
    val maxTrim = min(limits.maximumAudibleTrimSeconds, beatLimit)
    val earliestByTail = end - maxTrim
    val earliestByFraction = end * limits.minimumPlayedFraction
    var safe = max(proposed, max(earliestByTail, earliestByFraction)).coerceIn(0.0, end)

    val protected = analysis.sections.firstOrNull { section ->
        safe > section.start && safe < section.end &&
            (section.type == MusicalSectionType.CHORUS ||
                section.type == MusicalSectionType.DROP ||
                section.vocalActivity >= POLICY_VOCAL_ACTIVE_THRESHOLD)
    }
    if (protected != null) safe = protected.end.coerceAtMost(end)

    // Creative policies still cut on a phrase or downbeat, never mid-beat.
    if (policy != AutomixPreservation.FULL_TRACK) {
        val phrase = analysis.phraseBoundaries
            .filter { it >= safe && it <= end }
            .minOrNull()
        val downbeat = analysis.downbeats
            .filter { it >= safe && it <= end }
            .minOrNull()
        safe = phrase ?: downbeat ?: safe
    }
    return safe
}
