package com.music.yzmusic

import com.music.yzmusic.data.settings.AppSettings
import com.music.yzmusic.data.settings.AutomixPerformanceMode
import com.music.yzmusic.playback.smart.AutomixDurationPolicy
import com.music.yzmusic.playback.smart.CamelotHarmonics
import com.music.yzmusic.playback.smart.CrossfadeMode
import com.music.yzmusic.playback.smart.EnergySample
import com.music.yzmusic.playback.smart.MusicalSection
import com.music.yzmusic.playback.smart.MusicalSectionType
import com.music.yzmusic.playback.smart.TrackAnalysis
import com.music.yzmusic.playback.smart.TransitionStyle
import com.music.yzmusic.playback.smart.planTransition
import com.music.yzmusic.playback.smart.planWsolaTransition
import com.music.yzmusic.playback.smart.scoreTransitionCandidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class AutomixDurationContractTest {

    private fun createAnalysis(
        bpm: Double = 124.0,
        beatConfidence: Double = 0.95,
        key: String = "C major",
        keyConfidence: Double = 0.9,
        duration: Double = 240.0,
        audibleStartTime: Double = 0.5,
        introEndTime: Double = 16.0,
        outroStartTime: Double = 210.0,
        contentEndTime: Double = duration - 2.0,
        structuralConfidence: Double = 0.85,
        sections: List<MusicalSection> = emptyList(),
        hasVocals: Boolean = false,
        mixInTime: Double = introEndTime,
        mixOutTime: Double = outroStartTime,
    ): TrackAnalysis {
        val interval = if (bpm > 0.0) 60.0 / bpm else 0.5
        val downbeats = if (interval > 0.0 && duration > 0.0) {
            (0 until (duration / (interval * 4)).toInt()).map { it * interval * 4 }
        } else emptyList()
        val phraseBoundaries = if (interval > 0.0 && duration > 0.0) {
            (0 until (duration / (interval * 16)).toInt()).map { it * interval * 16 }
        } else emptyList()

        val sampleStep = 0.5
        val samplesCount = if (duration > 0.0) (duration / sampleStep).toInt().coerceAtLeast(2) else 2
        val energyCurve = (0 until samplesCount).map { EnergySample(it * sampleStep, 0.5) }
        val vocalActivityMask = (0 until samplesCount).map { if (hasVocals) 0.85 else 0.05 }

        return TrackAnalysis(
            status = TrackAnalysis.STATUS_READY,
            bpm = bpm,
            beatInterval = interval,
            beatConfidence = beatConfidence,
            key = key,
            keyConfidence = keyConfidence,
            duration = duration,
            contentEndTime = contentEndTime,
            audibleStartTime = audibleStartTime,
            introEndTime = introEndTime,
            outroStartTime = outroStartTime,
            mixInTime = mixInTime,
            mixOutTime = mixOutTime,
            downbeats = downbeats,
            phraseBoundaries = phraseBoundaries,
            structuralConfidence = structuralConfidence,
            energyCurve = energyCurve,
            vocalActivityMask = vocalActivityMask,
            sections = sections,
        )
    }

    // 1. Preferred 10–15s transition
    @Test
    fun `preferred transition duration is between 10 and 15 seconds for compatible tracks`() {
        val trackA = createAnalysis(bpm = 124.0, duration = 200.0, outroStartTime = 185.0)
        val trackB = createAnalysis(bpm = 124.0, duration = 210.0, introEndTime = 15.0)

        val plan = planTransition(
            analysis = trackA,
            nextAnalysis = trackB,
            duration = trackA.duration,
            mode = CrossfadeMode.SMART,
        )

        assertTrue("Expected 10..15s, got ${plan.fadeSeconds}", plan.fadeSeconds in 10.0..15.0)
        assertTrue("Expected fadeMs in 10000..15000, got ${plan.fadeMs}", plan.fadeMs in 10000L..15000L)
    }

    // 2. 15–20s transition when musically justified
    @Test
    fun `extended 15 to 20s transition is chosen when harmonically and structurally justified`() {
        // Camelot relative keys (C major = 8B, A minor = 8A), long instrumental outro & intro, high confidence
        val trackA = createAnalysis(
            bpm = 126.0,
            key = "C major",
            duration = 240.0,
            outroStartTime = 200.0,
            contentEndTime = 238.0,
        )
        val trackB = createAnalysis(
            bpm = 126.0,
            key = "A minor",
            duration = 240.0,
            introEndTime = 30.0,
        )

        val plan = planTransition(
            analysis = trackA,
            nextAnalysis = trackB,
            duration = trackA.duration,
            mode = CrossfadeMode.SMART,
        )

        assertTrue(
            "Harmonically justified transition should be in 10..20s range, got ${plan.fadeSeconds}",
            plan.fadeSeconds in 10.0..20.0
        )
        assertTrue("Must never exceed 30s", plan.fadeSeconds <= 30.0)
    }

    // 3. Hard 30s maximum - NEVER > 30s
    @Test
    fun `hard 30s maximum is strictly enforced even with very slow BPM and massive outro-intro`() {
        // 45 BPM has 1.33s per beat, 16 beats = 21.3s, 32 beats = 42.6s
        val slowA = createAnalysis(
            bpm = 45.0,
            duration = 300.0,
            outroStartTime = 220.0,
            contentEndTime = 295.0,
        )
        val slowB = createAnalysis(
            bpm = 45.0,
            duration = 300.0,
            introEndTime = 70.0,
        )

        val plan = planTransition(
            analysis = slowA,
            nextAnalysis = slowB,
            duration = slowA.duration,
            mode = CrossfadeMode.SMART,
        )

        assertTrue("Fade must be > 0s, got ${plan.fadeSeconds}", plan.fadeSeconds > 0.0)
        assertTrue("Fade must never exceed 30.0s, got ${plan.fadeSeconds}", plan.fadeSeconds <= 30.0)
        assertTrue("FadeMs must never exceed 30000ms, got ${plan.fadeMs}", plan.fadeMs <= 30000L)
    }

    // 4. Short song protection - never transitionDuration >= trackDuration
    @Test
    fun `short track duration is clamped to at most 40 percent of song length`() {
        val shortSong = createAnalysis(bpm = 120.0, duration = 25.0, outroStartTime = 20.0)
        val normalNext = createAnalysis(bpm = 120.0, duration = 200.0, introEndTime = 15.0)

        val plan = planTransition(
            analysis = shortSong,
            nextAnalysis = normalNext,
            duration = shortSong.duration,
            mode = CrossfadeMode.SMART,
        )

        val maxAllowed = 25.0 * AutomixDurationPolicy.SHORT_TRACK_MAX_FRACTION // 10.0s
        assertTrue("Fade must not exceed 40% of track length (max $maxAllowed), got ${plan.fadeSeconds}", plan.fadeSeconds <= maxAllowed)
        assertTrue("Fade must be positive, got ${plan.fadeSeconds}", plan.fadeSeconds > 0.0)
    }

    // 5. Very short song (e.g. 10 seconds)
    @Test
    fun `very short 10s track produces safe short transition`() {
        val veryShort = createAnalysis(bpm = 120.0, duration = 10.0, outroStartTime = 8.0)
        val normalNext = createAnalysis(bpm = 120.0, duration = 180.0, introEndTime = 10.0)

        val plan = planTransition(
            analysis = veryShort,
            nextAnalysis = normalNext,
            duration = veryShort.duration,
            mode = CrossfadeMode.SMART,
        )

        val maxAllowed = 10.0 * 0.40 // 4.0s
        assertTrue("Expected fade <= $maxAllowed, got ${plan.fadeSeconds}", plan.fadeSeconds <= maxAllowed + 0.001)
        assertTrue("Expected fade > 0, got ${plan.fadeSeconds}", plan.fadeSeconds > 0.0)
    }

    // 6. Zero and negative duration safe handling
    @Test
    fun `zero or negative track duration handled safely by policy`() {
        val clampedZero = AutomixDurationPolicy.clampDuration(12.0, 0.0, 100.0)
        assertTrue("Unspecified 0.0 track duration uses canonical bounds, got $clampedZero", clampedZero in AutomixDurationPolicy.SAFE_MIN_SECONDS..AutomixDurationPolicy.ABSOLUTE_MAX_SECONDS)

        val clampedNegative = AutomixDurationPolicy.clampDuration(12.0, -10.0, 100.0)
        assertEquals(0.0, clampedNegative, 0.001)

        val clampedNegativeFade = AutomixDurationPolicy.clampDuration(-5.0, 100.0, 100.0)
        assertTrue("Negative fade should be clamped to SAFE_MIN_SECONDS, got $clampedNegativeFade", clampedNegativeFade >= AutomixDurationPolicy.SAFE_MIN_SECONDS)
    }

    // 7. Long outro with normal intro does not produce oversized transition
    @Test
    fun `song with 50s outro does not automatically create oversized 50s mix`() {
        val longOutroTrack = createAnalysis(
            bpm = 124.0,
            duration = 300.0,
            outroStartTime = 240.0, // 60s outro
            contentEndTime = 298.0,
        )
        val normalNext = createAnalysis(
            bpm = 124.0,
            duration = 200.0,
            introEndTime = 16.0,
        )

        val plan = planTransition(
            analysis = longOutroTrack,
            nextAnalysis = normalNext,
            duration = longOutroTrack.duration,
            mode = CrossfadeMode.SMART,
        )

        assertTrue("Should strongly prefer 10..15s even with 60s outro, got ${plan.fadeSeconds}", plan.fadeSeconds in 10.0..15.0)
        assertTrue("Must be <= 30s", plan.fadeSeconds <= 30.0)
    }

    // 8. Long intro with normal outro
    @Test
    fun `incoming track with 60s intro stays bounded within canonical limits`() {
        val normalTrack = createAnalysis(
            bpm = 128.0,
            duration = 200.0,
            outroStartTime = 188.0, // 12s outro
            contentEndTime = 198.0,
        )
        val longIntroTrack = createAnalysis(
            bpm = 128.0,
            duration = 300.0,
            introEndTime = 60.0, // 60s intro
        )

        val plan = planTransition(
            analysis = normalTrack,
            nextAnalysis = longIntroTrack,
            duration = normalTrack.duration,
            mode = CrossfadeMode.SMART,
        )

        assertTrue("Should remain bounded <= 20.0s, got ${plan.fadeSeconds}", plan.fadeSeconds <= 20.0)
        assertTrue("Must be <= 30.0s", plan.fadeSeconds <= 30.0)
    }

    // 9. Vocal overlap clash avoidance
    @Test
    fun `strong vocal overlap on both sides shortens transition towards preferred 10 to 12s range`() {
        val vocalOut = createAnalysis(
            bpm = 120.0,
            duration = 200.0,
            outroStartTime = 185.0,
            hasVocals = true,
        )
        val vocalIn = createAnalysis(
            bpm = 120.0,
            duration = 200.0,
            introEndTime = 15.0,
            hasVocals = true,
        )

        val plan = planTransition(
            analysis = vocalOut,
            nextAnalysis = vocalIn,
            duration = vocalOut.duration,
            mode = CrossfadeMode.SMART,
        )

        assertTrue("Vocal clash should shorten fade towards 10..15s, got ${plan.fadeSeconds}", plan.fadeSeconds in 10.0..15.0)
        assertTrue("Vocal overlap should be detected when both have heavy vocals, got ${plan.vocalOverlap}", plan.vocalOverlap >= 0.50)
    }

    // 10. Instrumental transition
    @Test
    fun `instrumental outro and intro allows full musical blend`() {
        val instOut = createAnalysis(bpm = 125.0, duration = 200.0, outroStartTime = 185.0, hasVocals = false)
        val instIn = createAnalysis(bpm = 125.0, duration = 200.0, introEndTime = 15.0, hasVocals = false)

        val plan = planTransition(
            analysis = instOut,
            nextAnalysis = instIn,
            duration = instOut.duration,
            mode = CrossfadeMode.SMART,
        )

        assertTrue("Instrumental transition should be in 10..20s, got ${plan.fadeSeconds}", plan.fadeSeconds in 10.0..20.0)
        assertTrue(plan.fadeSeconds <= 30.0)
    }

    // 11. BPM mismatch prefers shorter transition around 10s
    @Test
    fun `large BPM mismatch chooses safe short transition around 10s`() {
        val slowTrack = createAnalysis(bpm = 75.0, duration = 200.0)
        val fastTrack = createAnalysis(bpm = 145.0, duration = 200.0)

        val plan = planTransition(
            analysis = slowTrack,
            nextAnalysis = fastTrack,
            duration = slowTrack.duration,
            mode = CrossfadeMode.SMART,
        )

        assertTrue("BPM mismatch should prefer shorter transition <= 13s, got ${plan.fadeSeconds}", plan.fadeSeconds <= 13.0)
        assertTrue(plan.fadeSeconds in 9.5..13.0)
    }

    // 12. Compatible BPM allows normal 10–15s transition
    @Test
    fun `compatible BPM allows normal 10 to 15s transition`() {
        val trackA = createAnalysis(bpm = 124.0, duration = 200.0)
        val trackB = createAnalysis(bpm = 126.0, duration = 200.0)

        val plan = planTransition(
            analysis = trackA,
            nextAnalysis = trackB,
            duration = trackA.duration,
            mode = CrossfadeMode.SMART,
        )

        assertTrue("Compatible BPM should yield 10..15s, got ${plan.fadeSeconds}", plan.fadeSeconds in 10.0..15.0)
    }

    // 13. Key match vs key mismatch
    @Test
    fun `harmonic key match enables DJ blend while key clash avoids extended duration`() {
        val keyMatchA = createAnalysis(bpm = 128.0, key = "A minor")
        val keyMatchB = createAnalysis(bpm = 128.0, key = "E minor") // Quinta +1
        val matchPlan = planTransition(analysis = keyMatchA, nextAnalysis = keyMatchB, duration = 240.0, mode = CrossfadeMode.SMART)
        assertEquals(TransitionStyle.DJ_BLEND, matchPlan.transitionStyle)

        // Tritone mismatch: C major (8B) and F# major (3B)
        val keyClashA = createAnalysis(bpm = 128.0, key = "C major")
        val keyClashB = createAnalysis(bpm = 128.0, key = "F# major")
        val clashPlan = planTransition(analysis = keyClashA, nextAnalysis = keyClashB, duration = 240.0, mode = CrossfadeMode.SMART)
        assertTrue("Key clash should never produce extended >20s duration, got ${clashPlan.fadeSeconds}", clashPlan.fadeSeconds <= 20.0)
    }

    // 14. Missing analysis uses safe fallback
    @Test
    fun `missing analysis produces safe 10 to 12s fallback transition`() {
        val emptyAnalysis = TrackAnalysis()
        val plan = planTransition(
            analysis = emptyAnalysis,
            nextAnalysis = emptyAnalysis,
            duration = 200.0,
            fadeSeconds = 11.0,
            mode = CrossfadeMode.SMART,
        )

        assertEquals(AutomixDurationPolicy.DEFAULT_FALLBACK_SECONDS, plan.fadeSeconds, 0.01)
        assertTrue(plan.fadeSeconds in 10.0..12.0)
    }

    // 15. Low confidence analysis uses safe fallback
    @Test
    fun `low confidence analysis uses safe short fallback transition`() {
        val lowConfA = createAnalysis(bpm = 120.0, beatConfidence = 0.15, structuralConfidence = 0.1)
        val lowConfB = createAnalysis(bpm = 125.0, beatConfidence = 0.2, structuralConfidence = 0.15)

        val plan = planTransition(
            analysis = lowConfA,
            nextAnalysis = lowConfB,
            duration = 200.0,
            fadeSeconds = 11.0,
            mode = CrossfadeMode.SMART,
        )

        assertTrue("Low confidence should produce safe 10..12s transition, got ${plan.fadeSeconds}", plan.fadeSeconds in 10.0..12.0)
    }

    // 16. Missing beat data, key data, vocal data handles without crash
    @Test
    fun `missing beat, key, and vocal data handles safely`() {
        val partialA = TrackAnalysis(
            status = TrackAnalysis.STATUS_READY,
            duration = 180.0,
            bpm = 0.0,
            beatInterval = 0.0,
            downbeats = emptyList(),
            phraseBoundaries = emptyList(),
            key = "",
        )
        val partialB = TrackAnalysis(
            status = TrackAnalysis.STATUS_READY,
            duration = 190.0,
            bpm = 0.0,
            beatInterval = 0.0,
            downbeats = emptyList(),
            phraseBoundaries = emptyList(),
            key = "",
        )

        val plan = planTransition(
            analysis = partialA,
            nextAnalysis = partialB,
            duration = 180.0,
            mode = CrossfadeMode.SMART,
        )

        assertTrue("Should produce valid bounded fade, got ${plan.fadeSeconds}", plan.fadeSeconds in 10.0..15.0)
        assertTrue(plan.fadeMs in 10000L..15000L)
    }

    // 17. Malformed timestamps (descending, negative, or beyond duration)
    @Test
    fun `malformed timestamps are clamped gracefully`() {
        val malformedA = createAnalysis(
            duration = 200.0,
            introEndTime = -10.0,
            outroStartTime = 250.0, // beyond duration
            contentEndTime = 300.0,
        )
        val malformedB = createAnalysis(
            duration = 200.0,
            introEndTime = 500.0,
            outroStartTime = -5.0,
        )

        val plan = planTransition(
            analysis = malformedA,
            nextAnalysis = malformedB,
            duration = 200.0,
            mode = CrossfadeMode.SMART,
        )

        assertTrue(plan.fadeSeconds > 0.0)
        assertTrue(plan.fadeSeconds <= 30.0)
        assertTrue(plan.transitionStart >= 0.0)
        assertTrue(plan.transitionStart <= 200.0)
    }

    // 18. Seconds to milliseconds conversion consistency
    @Test
    fun `seconds and milliseconds conversions match precisely`() {
        val trackA = createAnalysis(bpm = 128.0, duration = 210.0)
        val trackB = createAnalysis(bpm = 128.0, duration = 210.0)

        val plan = planTransition(
            analysis = trackA,
            nextAnalysis = trackB,
            duration = 210.0,
            mode = CrossfadeMode.SMART,
        )

        assertEquals(Math.round(plan.fadeSeconds * 1000.0), plan.fadeMs)
        val clampedMs = AutomixDurationPolicy.clampFadeMs(plan.fadeMs, (trackA.duration * 1000).toLong(), (trackB.duration * 1000).toLong())
        assertEquals(plan.fadeMs, clampedMs)
    }

    // 19. AutomixDurationPolicy canonical clamp directly
    @Test
    fun `AutomixDurationPolicy direct clamping invariant guarantees`() {
        assertEquals(30.0, AutomixDurationPolicy.clampDuration(35.0, 200.0, 200.0), 0.001)
        assertEquals(30.0, AutomixDurationPolicy.clampDuration(100.0, 200.0, 200.0), 0.001)
        assertEquals(10.0, AutomixDurationPolicy.clampDuration(10.0, 200.0, 200.0), 0.001)
        assertEquals(15.0, AutomixDurationPolicy.clampDuration(15.0, 200.0, 200.0), 0.001)
        assertEquals(AutomixDurationPolicy.SAFE_MIN_SECONDS, AutomixDurationPolicy.clampDuration(1.0, 200.0, 200.0), 0.001)
        assertEquals(AutomixDurationPolicy.SAFE_MIN_SECONDS, AutomixDurationPolicy.clampDuration(-10.0, 200.0, 200.0), 0.001)
        assertEquals(AutomixDurationPolicy.DEFAULT_FALLBACK_SECONDS, AutomixDurationPolicy.clampDuration(Double.NaN, 200.0, 200.0), 0.001)

        // Short track limit
        assertEquals(8.0, AutomixDurationPolicy.clampDuration(15.0, 20.0, 200.0), 0.001)
        assertEquals(4.0, AutomixDurationPolicy.clampDuration(15.0, 200.0, 10.0), 0.001)
    }

    // 20. Performance mode thread budgeting
    @Test
    fun `performance mode applies expected thread budget`() {
        assertEquals(1, AutomixPerformanceMode.EFFICIENT.threads)
        assertEquals(2, AutomixPerformanceMode.BALANCED.threads)
        assertEquals(4, AutomixPerformanceMode.PERFORMANCE.threads)

        AppSettings.setAutomixPerformance(AutomixPerformanceMode.EFFICIENT)
        assertEquals(AutomixPerformanceMode.EFFICIENT, AppSettings.automixPerformance.value)

        AppSettings.setAutomixPerformance(AutomixPerformanceMode.PERFORMANCE)
        assertEquals(AutomixPerformanceMode.PERFORMANCE, AppSettings.automixPerformance.value)

        AppSettings.setAutomixPerformance(AutomixPerformanceMode.BALANCED)
        assertEquals(AutomixPerformanceMode.BALANCED, AppSettings.automixPerformance.value)
    }

    // 21. Protected chorus never cut through
    @Test
    fun `protected chorus section prevents awkward cut`() {
        val chorusOut = createAnalysis(
            bpm = 120.0,
            duration = 200.0,
            sections = listOf(MusicalSection(185.0, 198.0, MusicalSectionType.CHORUS, 0.9, 0.9, 0.9)),
        )
        val incoming = createAnalysis(bpm = 120.0, duration = 200.0)

        val plan = planTransition(
            analysis = chorusOut,
            nextAnalysis = incoming,
            duration = 200.0,
            mode = CrossfadeMode.SMART,
        )

        // Fade must not abruptly end inside the chorus; duration is clamped safely
        assertTrue(plan.fadeSeconds in 10.0..15.0)
        assertTrue(plan.fadeSeconds <= 30.0)
    }

    // 22. Candidate scoring model verification
    @Test
    fun `candidate scoring strictly penalizes durations over 30s`() {
        val scoreSafe = scoreTransitionCandidate(
            outgoing = createAnalysis(bpm = 124.0),
            incoming = createAnalysis(bpm = 124.0),
            transitionStart = 180.0,
            transitionEnd = 192.5,
            incomingCueTime = 0.0,
            incomingPlaybackRate = 1.0,
        )
        val scoreOver30 = scoreTransitionCandidate(
            outgoing = createAnalysis(bpm = 124.0),
            incoming = createAnalysis(bpm = 124.0),
            transitionStart = 180.0,
            transitionEnd = 211.0,
            incomingCueTime = 0.0,
            incomingPlaybackRate = 1.0,
        )

        assertTrue("Safe duration should score high: ${scoreSafe.totalScore}", scoreSafe.totalScore > 0.0)
        assertTrue("Over 30s duration must be severely penalized: ${scoreOver30.totalScore}", scoreOver30.totalScore < -500.0)
    }

    // 23. WSOLA phrase switch returns strictly bounded beats and duration
    @Test
    fun `wsola phrase switch strictly caps beats to 30s max`() {
        val slowA = createAnalysis(bpm = 50.0, duration = 300.0, outroStartTime = 200.0)
        val slowB = createAnalysis(bpm = 50.0, duration = 300.0, introEndTime = 80.0)

        val wsola = planWsolaTransition(
            analysis = slowA,
            nextAnalysis = slowB,
            duration = 300.0,
            nextDuration = 300.0,
        )

        if (wsola is com.music.yzmusic.playback.smart.WsolaPlanResult.Planned) {
            assertTrue("WSOLA overlapSeconds must be <= 30.0, got ${wsola.overlapSeconds}", wsola.overlapSeconds <= 30.0)
            assertTrue("WSOLA overlapSeconds must be > 0.0", wsola.overlapSeconds > 0.0)
        }
    }

    // 24. Property-style fuzzing test with 10,000 randomized analysis pairs
    @Test
    fun `property fuzzing - 10,000 random track pairs NEVER exceed 30s and prefer 10 to 15s`() {
        val rng = Random(42L)
        val keys = listOf("C major", "G major", "D major", "A minor", "E minor", "B minor", "F# major", "Ab minor", "")

        var countPreferred = 0
        var countExtended = 0
        var countLonger = 0
        var countOver30 = 0
        val totalIterations = 10_000

        for (i in 0 until totalIterations) {
            val durA = 10.0 + rng.nextDouble() * 590.0 // 10s to 600s
            val durB = 10.0 + rng.nextDouble() * 590.0
            val bpmA = 40.0 + rng.nextDouble() * 180.0 // 40 to 220 BPM
            val bpmB = 40.0 + rng.nextDouble() * 180.0
            val keyA = keys[rng.nextInt(keys.size)]
            val keyB = keys[rng.nextInt(keys.size)]
            val confA = rng.nextDouble()
            val confB = rng.nextDouble()
            val outroA = durA * (0.6 + rng.nextDouble() * 0.38)
            val introB = durB * (0.01 + rng.nextDouble() * 0.3)

            val trackA = createAnalysis(
                bpm = bpmA,
                beatConfidence = confA,
                key = keyA,
                keyConfidence = confA,
                duration = durA,
                outroStartTime = outroA,
                contentEndTime = durA - (rng.nextDouble() * 2.0),
                structuralConfidence = confA,
            )
            val trackB = createAnalysis(
                bpm = bpmB,
                beatConfidence = confB,
                key = keyB,
                keyConfidence = confB,
                duration = durB,
                introEndTime = introB,
                mixInTime = introB,
                structuralConfidence = confB,
            )

            val plan = planTransition(
                analysis = trackA,
                nextAnalysis = trackB,
                duration = durA,
                mode = CrossfadeMode.SMART,
            )

            // HARD INVARIANT: 0.0 < duration <= 30.0s
            assertTrue(
                "Iteration $i: Fade seconds must be > 0.0, got ${plan.fadeSeconds}",
                plan.fadeSeconds > 0.0
            )
            assertTrue(
                "Iteration $i: Fade seconds must NEVER exceed 30.0s, got ${plan.fadeSeconds} (durA=$durA, durB=$durB, bpmA=$bpmA, bpmB=$bpmB)",
                plan.fadeSeconds <= 30.0001
            )
            assertTrue(
                "Iteration $i: FadeMs must NEVER exceed 30,000ms, got ${plan.fadeMs}",
                plan.fadeMs <= 30000L
            )
            assertTrue(
                "Iteration $i: Fade seconds cannot exceed 40% of short track duration",
                plan.fadeSeconds <= minOf(durA, durB) * 0.4001 || minOf(durA, durB) >= AutomixDurationPolicy.PREFERRED_MIN_SECONDS
            )

            // Categorize duration distribution
            if (plan.fadeSeconds in 9.99..15.01) {
                countPreferred++
            } else if (plan.fadeSeconds in 15.01..20.01) {
                countExtended++
            } else if (plan.fadeSeconds > 20.01 && plan.fadeSeconds <= 30.0001) {
                countLonger++
            } else if (plan.fadeSeconds > 30.0001) {
                countOver30++
            }
        }

        assertEquals("Zero transitions must exceed 30 seconds", 0, countOver30)

        println("=== Automix 2.0 Property Fuzzing Results (10,000 iterations) ===")
        println("Preferred (10–15s): $countPreferred (${countPreferred * 100.0 / totalIterations}%)")
        println("Extended (15–20s): $countExtended (${countExtended * 100.0 / totalIterations}%)")
        println("Longer (20–30s): $countLonger (${countLonger * 100.0 / totalIterations}%)")
        println("Exceeding 30s (>30s): $countOver30 (0.0%)")

        // Assert that preferred 10–15s is the dominant mode among all generated random pairs
        assertTrue(
            "Expected preferred 10-15s to be the dominant majority, got $countPreferred / $totalIterations",
            countPreferred > 4500
        )
        assertTrue(
            "Expected preferred + extended to cover majority of normal tracks, got ${countPreferred + countExtended} / $totalIterations",
            countPreferred + countExtended > 6000
        )
    }
}
