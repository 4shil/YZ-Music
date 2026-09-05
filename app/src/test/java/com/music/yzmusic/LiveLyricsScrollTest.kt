package com.music.yzmusic

import com.music.yzmusic.data.lyrics.LyricLine
import com.music.yzmusic.data.lyrics.LyricWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveLyricsScrollTest {

    private fun sampleLines(): List<LyricLine> = listOf(
        LyricLine(
            timeMs = 1_000L,
            text = "First verse line",
            words = listOf(
                LyricWord(1_000L, 1_500L, "First"),
                LyricWord(1_500L, 2_000L, "verse"),
                LyricWord(2_000L, 2_500L, "line"),
            ),
        ),
        LyricLine(
            timeMs = 3_000L,
            text = "Second verse line",
            words = listOf(
                LyricWord(3_000L, 3_500L, "Second"),
                LyricWord(3_500L, 4_000L, "verse"),
                LyricWord(4_000L, 4_500L, "line"),
            ),
            background = LyricLine(
                timeMs = 4_000L,
                text = "(backing vocal)",
                words = listOf(
                    LyricWord(4_000L, 5_200L, "backing"),
                    LyricWord(5_200L, 5_800L, "vocal"),
                ),
            ),
        ),
        LyricLine(
            timeMs = 5_000L,
            text = "Third verse line",
            words = listOf(
                LyricWord(5_000L, 5_500L, "Third"),
                LyricWord(5_500L, 6_000L, "verse"),
                LyricWord(6_000L, 6_500L, "line"),
            ),
        ),
        LyricLine(
            timeMs = 7_000L,
            text = "",
        ),
        LyricLine(
            timeMs = 12_000L,
            text = "Chorus line after break",
            words = listOf(
                LyricWord(12_000L, 12_500L, "Chorus"),
                LyricWord(12_500L, 13_000L, "line"),
            ),
        ),
    )

    private fun activeLineIndex(lines: List<LyricLine>, clockMs: Long): Int =
        lines.indexOfLast { it.timeMs <= clockMs }

    private fun alsoActiveIndex(lines: List<LyricLine>, activeLine: Int, clockMs: Long): Int {
        val previous = activeLine - 1
        val line = lines.getOrNull(previous)
        return if (line != null && line.hasKnownEnd && clockMs < line.endMs) previous else -1
    }

    @Test
    fun `active line follows clock continuously across lines`() {
        val lines = sampleLines()

        // Before first line starts
        assertEquals(-1, activeLineIndex(lines, 500L))

        // On first line
        assertEquals(0, activeLineIndex(lines, 1_000L))
        assertEquals(0, activeLineIndex(lines, 2_000L))

        // On second line
        assertEquals(1, activeLineIndex(lines, 3_000L))
        assertEquals(1, activeLineIndex(lines, 4_500L))

        // On third line
        assertEquals(2, activeLineIndex(lines, 5_000L))

        // Gap instrumental line
        assertEquals(3, activeLineIndex(lines, 7_000L))
        assertTrue(lines[3].isGap)
        assertEquals(3, activeLineIndex(lines, 11_999L))

        // Chorus line
        assertEquals(4, activeLineIndex(lines, 12_000L))
    }

    @Test
    fun `alsoActive keeps previous line active while background vocal sustains past line handover`() {
        val lines = sampleLines()

        // At 4_500ms, line 1 is active (starts at 3_000ms), line 0 ended at 2_500ms -> alsoActive is -1
        assertEquals(1, activeLineIndex(lines, 4_500L))
        assertEquals(-1, alsoActiveIndex(lines, 1, 4_500L))

        // At 5_100ms, line 2 is active (starts at 5_000ms).
        // Line 1's lead ended at 4_500ms, but its background vocal ends at 5_800ms (endMs = 5_800ms).
        // Therefore, line 1 must still be alsoActive!
        assertEquals(2, activeLineIndex(lines, 5_100L))
        assertEquals(1, alsoActiveIndex(lines, 2, 5_100L))

        // At 5_799ms, line 1 is still alsoActive
        assertEquals(1, alsoActiveIndex(lines, 2, 5_799L))

        // At 5_801ms, background vocal ended -> line 1 drops out of alsoActive
        assertEquals(-1, alsoActiveIndex(lines, 2, 5_801L))
    }

    @Test
    fun `seek jumps active line without corruption`() {
        val lines = sampleLines()

        // Normal play at line 0
        assertEquals(0, activeLineIndex(lines, 1_200L))

        // Seek forward to chorus (12_500ms)
        assertEquals(4, activeLineIndex(lines, 12_500L))

        // Seek back to line 1 (3_200ms)
        assertEquals(1, activeLineIndex(lines, 3_200L))

        // Seek before song starts
        assertEquals(-1, activeLineIndex(lines, 100L))
    }

    @Test
    fun `idle timeout contract distinguishes visible active line from scrolled away`() {
        fun resolveTimeoutMs(activeOnScreen: Boolean): Long =
            if (activeOnScreen) 2_000L else 5_000L

        // Scrolled back / active line visible: brief settle delay of 2s
        assertEquals(2_000L, resolveTimeoutMs(activeOnScreen = true))

        // Scrolled away: longer read-ahead allowance of 5s
        assertEquals(5_000L, resolveTimeoutMs(activeOnScreen = false))
    }

    @Test
    fun `scroll jump distance threshold distinguishes instant jump from smooth animation`() {
        fun shouldUseInstantJump(targetIndex: Int, firstVisibleIndex: Int): Boolean =
            kotlin.math.abs(targetIndex - firstVisibleIndex) > 5

        // Line-by-line progression: smooth animation
        assertFalse(shouldUseInstantJump(targetIndex = 6, firstVisibleIndex = 5))
        assertFalse(shouldUseInstantJump(targetIndex = 7, firstVisibleIndex = 5))

        // Large seek or browse recovery: instant snap to prevent stuttering across 20 items
        assertTrue(shouldUseInstantJump(targetIndex = 25, firstVisibleIndex = 5))
        assertTrue(shouldUseInstantJump(targetIndex = 0, firstVisibleIndex = 15))
    }
}
