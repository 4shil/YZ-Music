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
    fun `skips translations and hangs background vocals under the lead`() {
        val lines = TtmlLyrics.parse(
            """
            <tt><body><div>
              <p begin="1.0" end="2.0">
                <span begin="1.0" end="2.0">hello</span>
                <span ttm:role="x-bg" begin="1.5" end="2.4"><span begin="1.5" end="2.4">(ooh)</span></span>
                <span ttm:role="x-translation" xml:lang="es">hola</span>
              </p>
            </div></body></tt>
            """.trimIndent(),
        ).sung()
        val line = lines.single()
        assertEquals("hello", line.text)
        assertEquals("(ooh)", line.background?.text)
        // Its own stamp, not the lead's — it starts partway through the line.
        assertEquals(1_500L, line.background?.timeMs)
        assertTrue(line.background!!.isWordSynced)
        // And the line is not over until the answer is.
        assertEquals(2_400L, line.endMs)
    }

    /** A backing span written as a single timed leaf, with no syllables in it. */
    @Test
    fun `reads a background vocal that is one timed span`() {
        val line = TtmlLyrics.parse(
            """
            <tt><body><div>
              <p begin="1.0" end="2.0">
                <span begin="1.0" end="2.0">hello</span>
                <span ttm:role="x-bg" begin="1.5" end="2.0">(ooh)</span>
              </p>
            </div></body></tt>
            """.trimIndent(),
        ).sung().single()
        assertEquals("hello", line.text)
        assertEquals("(ooh)", line.background?.text)
    }

    @Test
    fun `falls back to plain text for line-synced ttml`() {
        val lines = TtmlLyrics.parse(
            """<tt><body><div><p begin="00:12.50">just a line</p></div></body></tt>""",
        ).sung()
        assertEquals("just a line", lines.single().text)
        assertEquals(12_500L, lines.single().timeMs)
        assertTrue(lines.single().words.isEmpty())
    }

    @Test
    fun `parses every ttml clock shape`() {
        assertEquals(27_395L, TtmlLyrics.time("27.395"))
        assertEquals(65_200L, TtmlLyrics.time("1:05.20"))
        assertEquals(3_723_400L, TtmlLyrics.time("1:02:03.4"))
        assertEquals(1_500L, TtmlLyrics.time("1.5s"))
        assertEquals(250L, TtmlLyrics.time("250ms"))
        assertNull(TtmlLyrics.time(""))
        assertNull(TtmlLyrics.time(null))
    }

    @Test
    fun `bad xml yields nothing rather than throwing`() {
        assertEquals(emptyList<LyricLine>(), TtmlLyrics.parse("<tt><body><p begin="))
    }

    // ---- Enhanced LRC, as SimpMusic serves rich sync ------------------------

    @Test
    fun `reads word timings out of enhanced lrc`() {
        val lines = EnhancedLrc.parse(
            """
            [00:27.39]<00:27.39>I <00:27.54>been <00:27.74>tryna <00:28.07>call
            [00:30.18]<00:30.18>on <00:30.39>my <00:30.64>own
            """.trimIndent(),
        ).sung()
        assertEquals(2, lines.size)
        assertEquals("I been tryna call", lines[0].text)
        assertEquals(listOf("I", "been", "tryna", "call"), lines[0].words.map { it.text })
        // Only starts are written down, so a word ends where the next begins...
        assertEquals(27_540L, lines[0].words[0].endMs)
        // ...and the last word of a line ends where the next line starts.
        assertEquals(30_180L, lines[0].words.last().endMs)
    }

    @Test
    fun `plain lrc is left for the line-synced parser`() {
        assertEquals(
            emptyList<LyricLine>(),
            EnhancedLrc.parse("[00:12.00] no word stamps here\n[00:15.00] none here either"),
        )
    }

    @Test
    fun `decodes the html entities simpmusic escapes`() {
        val line = EnhancedLrc.parse("[00:01.00]<00:01.00>don&#x27;t <00:01.50>stop").sung().single()
        assertEquals("don't stop", line.text)
    }

    @Test
    fun `does not double-decode an escaped ampersand`() {
        assertEquals("&#x27;", EnhancedLrc.decodeEntities("&amp;#x27;"))
    }

    // ---- LyricsPlus / YouLy+ syllables --------------------------------------

    @Test
