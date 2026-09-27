package com.music.yzmusic

import com.music.yzmusic.data.lyrics.LyricDisplayRow
import com.music.yzmusic.data.lyrics.LyricLine
import com.music.yzmusic.data.lyrics.pairLyricLayers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The second voice is drawn under the line it belongs to. Pairing is by
 * position and nothing else, so these are mostly about proving it never
 * guesses.
 */
class LyricDisplayRowTest {

    private fun line(text: String, timeMs: Long = 0L) = LyricLine(text = text, timeMs = timeMs)

    @Test
    fun noLayerLeavesEveryRowBare() {
        val rows = pairLyricLayers(listOf(line("one"), line("two")), sub = null)

        assertEquals(2, rows.size)
        assertEquals("one", rows[0].primary.text)
        assertNull(rows[0].sub)
        assertNull(rows[1].sub)
    }

    @Test
    fun eachSubLandsUnderItsOwnLine() {
        val rows = pairLyricLayers(
            listOf(line("one"), line("two"), line("three")),
            listOf(line("1"), line("2"), line("3")),
        )

        assertEquals(listOf("1", "2", "3"), rows.map { it.sub?.text })
    }

    /**
     * The one that must never break. A sub-line under the wrong words is worse
     * than no sub-line: the reader is looking at "two" while reading the
     * translation of "three".
     */
    @Test
    fun aShortLayerIsTruncatedRatherThanNudged() {
        val rows = pairLyricLayers(
            listOf(line("one"), line("two"), line("three")),
            listOf(line("1"), line("2")),
        )

        assertEquals(listOf("1", "2", null), rows.map { it.sub?.text })
        assertEquals("three", rows[2].primary.text)
    }

    @Test
    fun aLongLayerIsIgnoredWhereItOverruns() {
        val rows = pairLyricLayers(
            listOf(line("one"), line("two")),
            listOf(line("1"), line("2"), line("3"), line("4")),
        )

        assertEquals(listOf("1", "2"), rows.map { it.sub?.text })
        assertEquals(2, rows.size)
    }

    @Test
    fun anEmptyLayerOfTheWrongLengthStillNeverShifts() {
        // A layer that came back mostly blank: only position 0 has words, and
        // they must not drift onto the next line.
        val rows = pairLyricLayers(
            listOf(line("one"), line("two")),
            listOf(line("1"), line("")),
        )

        assertEquals("1", rows[0].sub?.text)
        assertNull(rows[1].sub)
    }

    @Test
    fun aBlankSubIsDroppedRatherThanDrawed() {
        val rows = pairLyricLayers(
            listOf(line("one"), line("two")),
            listOf(line("   "), line("2")),
        )

        assertNull(rows[0].sub)
        assertEquals("2", rows[1].sub?.text)
    }

    @Test
    fun aSubThatOnlyRepeatsTheOriginalIsDropped() {
        val rows = pairLyricLayers(
            listOf(line("Sora"), line("two")),
            listOf(line("sora"), line("2")),
        )

        assertNull(rows[0].sub)
        assertEquals("2", rows[1].sub?.text)
    }

    @Test
    fun anEmptySongIsStillEmpty() {
        assertEquals(0, pairLyricLayers(emptyList(), listOf(line("1"))).size)
    }

    @Test
    fun pairingKeepsEveryOriginalLineEvenWithNoSubAtAll() {
        val original = listOf(line("a"), line("b"), line("c"))

        val rows = pairLyricLayers(original, sub = null)

        assertEquals(original.map { it.text }, rows.map { it.primary.text })
    }
}
