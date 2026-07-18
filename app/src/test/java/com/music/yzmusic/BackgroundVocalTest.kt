package com.music.yzmusic

import com.music.yzmusic.data.lyrics.LyricLine
import com.music.yzmusic.data.lyrics.LyricWord
import com.music.yzmusic.data.lyrics.withBackgroundVocals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Only Apple's TTML marks the answering vocal outright. Every other provider
 * writes it into the line as a bracket, and left there it was sung over the
 * top of the *next* line's stamp — so the cursor moved on with the bracket
 * half-swept and the strip cut it off. These cover the split that pulls it out.
 *
 * The lines are the shape LyricsPlus serves "Heartbreak Anniversary" in, with
 * placeholder words.
 */
class BackgroundVocalTest {

    private fun words(vararg spans: Triple<Long, Long, String>) =
        spans.map { (start, end, text) -> LyricWord(start, end, text) }

    private fun wordSynced(vararg spans: Triple<Long, Long, String>): LyricLine {
        val list = words(*spans)
        return LyricLine(
            timeMs = list.first().startMs,
            text = list.joinToString(" ") { it.text },
            words = list,
        )
    }

    @Test
    fun `a trailing bracket becomes the answering line`() {
