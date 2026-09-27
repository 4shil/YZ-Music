package com.music.yzmusic

import com.music.yzmusic.data.lyrics.LyricAlignment
import com.music.yzmusic.data.lyrics.LyricLine
import com.music.yzmusic.data.lyrics.LyricWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The per-letter growth treatment: which words earn it, and what shape the
 * swell takes as it travels along them.
 */
class LyricGrowthTest {

    private fun line(vararg words: Pair<String, Pair<Long, Long>>) = LyricLine(
        timeMs = 0,
        text = words.joinToString(" ") { it.first },
        words = words.map { LyricWord(it.second.first, it.second.second, it.first) },
    )

    private fun sample(growing: com.music.yzmusic.data.lyrics.GrowingWord, charIndex: Int, at: Long) =
        com.music.yzmusic.data.lyrics.CharGrowth().also { growing.sampleInto(charIndex, at, it) }

    @Test
    fun `a word held long enough earns the letter treatment`() {
        val held = line("golden" to (0L to 1_800L))
        assertEquals(1, held.growingWords.size)
        assertEquals(0, held.growingWords.single().index)
    }

    @Test
    fun `a word rattled off does not`() {
        val patter = line("go" to (0L to 200L))
        assertTrue(patter.growingWords.isEmpty())
        assertNull(patter.growingAt(0))
    }

    @Test
    fun `each length has to clear its own hold to qualify`() {
        // Thresholds are steeper for short words than the length alone
        // suggests: a two-letter word held for a second is a held note,
        // whereas a six-letter one held for the same second is being sung at
        // an ordinary pace and has nothing to show.
        assertTrue(line("go" to (0L to 1_359L)).growingWords.isEmpty())
        assertTrue(line("go" to (0L to 1_360L)).growingWords.isNotEmpty())
        assertTrue(line("golden" to (0L to 1_199L)).growingWords.isEmpty())
        assertTrue(line("golden" to (0L to 1_200L)).growingWords.isNotEmpty())
    }

    @Test
    fun `past seven letters it reads as a ripple and is left alone`() {
        assertTrue(line("wonderful" to (0L to 4_000L)).growingWords.isEmpty())
    }

    @Test
    fun `scripts that draw as blocks are left whole`() {
        // Han, kana and Hangul sit side by side; moving one letter of them
        // would come apart rather than swell.
        assertTrue(line("光" to (0L to 2_000L)).growingWords.isEmpty())
        assertTrue(line("ひかり" to (0L to 2_000L)).growingWords.isEmpty())
        assertTrue(line("빛" to (0L to 2_000L)).growingWords.isEmpty())
    }

    @Test
    fun `a hyphenated word is really two words`() {
        assertTrue(line("ever-\u200bright" to (0L to 3_000L)).growingWords.isEmpty())
    }

    @Test
    fun `the swell travels along the word rather than pulsing`() {
        val word = line("golden" to (0L to 1_800L)).growingWords.single()

        // Letter 0 is on the move long before the last letter has started:
        // each one begins a fixed share of the word's length after the one
        // before it.
        val early = sample(word, 0, 200L)
        val late = sample(word, 5, 200L)
        assertTrue(early.scale > 1f)
        assertTrue(late.scale < early.scale)
    }

    @Test
    fun `a letter rests lifted once the move is over`() {
        val word = line("golden" to (0L to 1_800L)).growingWords.single()
        val rest = sample(word, 0, word.restsAtMs + 5_000L)
        assertEquals(1f, rest.scale, 0.0001f)
        assertEquals(0f, rest.bloom, 0.0001f)
        // Every sung word carries the same small lift at rest, so the line
        // does not jump as the last letter settles.
        assertTrue(rest.rise > 0f)
    }

    @Test
    fun `nothing is in flight before the word starts or after the line has passed`() {
        val held = line("golden" to (0L to 1_800L))
        assertFalse(held.isGrowing(-1L))
        assertTrue(held.isGrowing(900L))
        assertFalse(held.isGrowing(900L + held.growingWords.single().restsAtMs + 1L))
    }

    @Test
    fun `a line is not lifted before its first word or after the last has settled`() {
        val held = line("golden" to (0L to 1_800L))
        assertFalse(held.isLifted(0L))
        assertTrue(held.isLifted(900L))
    }

    @Test
    fun `word spans line up with a word repeated in the line`() {
        val text = "oh oh yes"
        val repeated = LyricLine(
            timeMs = 0,
            text = text,
            words = listOf(
                LyricWord(0, 300, "oh"),
                LyricWord(300, 600, "oh"),
                LyricWord(600, 900, "yes"),
            ),
        )
        assertEquals(listOf(0..1, 3..4, 6..8), repeated.wordSpans.map { it.first..it.last })
    }

    @Test
    fun `a translation line is swept from the vocal it was translated from`() {
        val vocal = line("golden" to (0L to 1_800L))
        val translated = LyricLine(timeMs = 0, text = "dorado", timingSource = vocal)

        // Halfway through the word, the original has sung half its characters;
        // the translation reveals the same share of its own.
        val through = vocal.revealedChars(900L) / vocal.text.length
        assertEquals(through * translated.text.length, translated.revealedChars(900L), 0.001f)
    }

    @Test
    fun `alignment defaults to the start side`() {
        assertEquals(LyricAlignment.Start, LyricLine(timeMs = 0, text = "x").alignment)
        assertEquals(
            LyricAlignment.End,
            LyricLine(timeMs = 0, text = "x", alignment = LyricAlignment.End).alignment,
        )
    }

    @Test
    fun `the answering vocal counts towards how long the line runs`() {
        val withBacking = LyricLine(
            timeMs = 0,
            text = "oh",
            words = listOf(LyricWord(0, 500, "oh")),
            background = LyricLine(timeMs = 200, text = "ooh", words = listOf(LyricWord(200, 900, "ooh"))),
        )
        // The backing holds well past the lead's last word, and the line is
        // still being sung while it does.
        assertEquals(900L, withBacking.endMs)
        assertNotNull(withBacking.background)
    }
}
