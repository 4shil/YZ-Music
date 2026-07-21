package com.music.yzmusic

import com.music.yzmusic.data.lyrics.EnhancedLrc
import com.music.yzmusic.data.lyrics.LyricLine
import com.music.yzmusic.data.lyrics.LyricWord
import com.music.yzmusic.data.lyrics.LyricsPlus
import com.music.yzmusic.data.lyrics.TtmlLyrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WordSyncTest {

    private fun List<LyricLine>.sung() = filterNot { it.isGap }

    // ---- Apple TTML, as BetterLyrics serves it ------------------------------

    /** Trimmed from a live lyrics-api.boidu.dev response. */
    private val ttml = """
        <tt xmlns="http://www.w3.org/ns/ttml" itunes:timing="Word" xml:lang="en">
          <body dur="3:21.570">
            <div begin="27.395" end="32.529" itunes:songPart="Verse">
              <p begin="27.395" end="28.960" itunes:key="L1" ttm:agent="v1">
                <span begin="27.395" end="27.549">I</span> <span begin="27.549" end="27.740">been</span> <span begin="27.740" end="28.077">tryna</span> <span begin="28.077" end="28.960">call</span>
              </p>
              <p begin="30.189" end="32.529" itunes:key="L2" ttm:agent="v1">
                <span begin="30.189" end="30.396">long</span> <span begin="31.839" end="31.996">e</span><span begin="31.996" end="32.529">nough</span>
              </p>
            </div>
          </body>
        </tt>
    """.trimIndent()

    @Test
    fun `reads word timings out of apple ttml`() {
        val lines = TtmlLyrics.parse(ttml).sung()
        assertEquals(2, lines.size)
        assertEquals("I been tryna call", lines[0].text)
        assertEquals(27_395L, lines[0].timeMs)
        assertTrue(lines[0].isWordSynced)
        assertEquals(
            listOf("I", "been", "tryna", "call"),
            lines[0].words.map { it.text },
        )
        assertEquals(27_549L, lines[0].words[0].endMs)
        assertEquals(28_960L, lines[0].endMs)
    }

    @Test
    fun `merges adjacent spans with no space between them into one word`() {
        // "e" + "nough" are separate spans; split, they would render "e nough".
        val line = TtmlLyrics.parse(ttml).sung()[1]
        assertEquals("long enough", line.text)
        assertEquals(listOf("long", "enough"), line.words.map { it.text })
        val enough = line.words[1]
        assertEquals(31_839L, enough.startMs)
        assertEquals(32_529L, enough.endMs)
    }

    @Test
