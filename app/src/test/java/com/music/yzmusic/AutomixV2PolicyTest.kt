package com.music.yzmusic

import com.music.yzmusic.playback.smart.AutomixPreservation
import com.music.yzmusic.playback.smart.MusicalSection
import com.music.yzmusic.playback.smart.MusicalSectionType
import com.music.yzmusic.playback.smart.TrackAnalysis
import com.music.yzmusic.playback.smart.TransitionTier
import com.music.yzmusic.playback.smart.assessTransitionTier
import com.music.yzmusic.playback.smart.maximumStretch
import com.music.yzmusic.playback.smart.preserveMixOut
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomixV2PolicyTest {
    private fun analysis(bpm: Double = 120.0, confidence: Double = .9) = TrackAnalysis(
        status = TrackAnalysis.STATUS_READY,
        bpm = bpm,
        beatInterval = 60 / bpm,
        beatConfidence = confidence,
        duration = 200.0,
        contentEndTime = 200.0,
        structuralConfidence = .85,
    )

    @Test
    fun `octave bpm pair can beatmatch`() {
        assertEquals(TransitionTier.BEATMATCHED, assessTransitionTier(analysis(63.0), analysis(126.0)).tier)
    }

    @Test
    fun `assisted tier never earns stretch`() {
        val verdict = assessTransitionTier(analysis(120.0, .4), analysis(123.0, .4))
        assertEquals(TransitionTier.DJ_ASSISTED, verdict.tier)
    }

    @Test
    fun `freedom rail is six percent and balanced is four`() {
        assertEquals(.04, maximumStretch(AutomixPreservation.BALANCED), 0.0)
        assertEquals(.06, maximumStretch(AutomixPreservation.DJ_FREEDOM), 0.0)
    }

    @Test
    fun `low structure confidence degrades cuts to full track`() {
        val weak = analysis().copy(structuralConfidence = .2)
        assertTrue(preserveMixOut(weak, 160.0, AutomixPreservation.DJ_FREEDOM) >= 198.0)
    }

    @Test
    fun `balanced never cuts through a protected chorus`() {
        val song = analysis().copy(
            sections = listOf(MusicalSection(186.0, 197.0, MusicalSectionType.CHORUS, .9, .9, .9)),
            phraseBoundaries = listOf(184.0, 192.0, 200.0),
        )
        assertTrue(preserveMixOut(song, 188.0, AutomixPreservation.BALANCED) >= 197.0)
    }
}
