package com.music.yzmusic
import com.music.yzmusic.data.lyrics.LyricAlignment
import com.music.yzmusic.data.lyrics.LyricsLog
import com.music.yzmusic.data.lyrics.TtmlLyrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TTML documents that carry a translation or a romanization alongside the
 * words. Those spans used to be skipped outright, so the whole point here is
 * that they now come back out — and, more importantly, that they come back out
 * on the original's clock rather than carrying timings of their own.
 */
class TtmlLyricsTest {

    private fun ttml(body: String) = """
        <?xml version="1.0" encoding="UTF-8"?>
        <tt xmlns="http://www.w3.org/ns/ttml"
            xmlns:ttm="http://www.w3.org/ns/ttml#metadata"
            xmlns:ttp="http://www.w3.org/ns/ttml#parameter"
            ttp:tickRate="10000">
          <body><div>
            $body
          </div></body>
        </tt>
    """.trimIndent()

    private fun paragraph(
        begin: String,
        end: String,
        text: String,
        translation: String? = null,
        romanization: String? = null,
    ): String = buildString {
        append("""<p begin="$begin" end="$end">""")
        append("""<span begin="$begin" end="$end" ttm:role="x-ala">""")
        text.split(' ').forEachIndexed { index, word ->
            val from = perWordStart(begin, index)
            val to = perWordEnd(begin, index)
            append("""<span begin="$from" end="$to">$word </span>""")
        }
        append("</span>")
        translation?.let {
            append("""<span ttm:role="x-translation" begin="${0.0}" end="99.0">$it</span>""")
        }
        romanization?.let {
            append("""<span ttm:role="x-roman" begin="${0.0}" end="99.0">$it</span>""")
        }
        append("</p>")
    }

    /** A paragraph sung by a named voice, so the duet lane has something to alternate. */
    private fun voiced(begin: String, end: String, text: String, agent: String) = buildString {
        append("""<p begin="$begin" end="$end" ttm:agent="$agent">""")
        append("""<span begin="$begin" end="$end" ttm:role="x-ala">""")
        text.split(' ').forEach { append("""<span>$it </span>""") }
        append("</span></p>")
    }

    /** Deliberately wrong per-word stamps — the alternates must not inherit these. */
    private fun perWordStart(begin: String, index: Int) = begin.toDouble() + index * 0.25
    private fun perWordEnd(begin: String, index: Int) = begin.toDouble() + index * 0.25 + 0.2

    @Test
    fun `translation and romanization are read as their own line lists`() {
        val document = TtmlLyrics.parseDocument(
            ttml(
                paragraph(
                    begin = "1.0", end = "5.0",
                    text = "I will always love you",
                    translation = "Siempre te amaré",
                    romanization = "Ai uonzu aiwaysu rabu yu",
                )
            )
        )

        assertEquals(listOf("I will always love you"), document.lines.map { it.text })

        assertNotNull(document.translation)
        val translation = document.translation!!
        assertEquals(1, translation.size)
        assertEquals("Siempre te amaré", translation.single().text)

        assertNotNull(document.romanization)
        val romanization = document.romanization!!
        assertEquals(1, romanization.size)
        assertEquals("Ai uonzu aiwaysu rabu yu", romanization.single().text)
    }

    @Test
    fun `alternates sit on the original's clock, not the spans own`() {
        val document = TtmlLyrics.parseDocument(
            ttml(
                paragraph(
                    begin = "1.0", end = "5.0",
                    text = "I will always love you",
                    translation = "Siempre te amaré",
                    romanization = "Ai uonzu aiwaysu rabu yu",
                )
            )
        )

        val original = document.lines.single()
        val translation = document.translation!!.single()
        val romanization = document.romanization!!.single()

        // The alternates were written with begin=0.0 end=99.0. Taking those at
        // face value would put the translated line at the start of the song
        // and stretch it over the whole track.
        assertEquals(original.timeMs, translation.timeMs)
        assertEquals(original.timeMs, romanization.timeMs)

        // And the per-word sweep is preserved, so the highlight still tracks the
        // singing rather than sitting still under a line of foreign text.
        assertTrue(translation.words.isNotEmpty())
        assertEquals(original.words.first().startMs, translation.words.first().startMs)
        assertTrue(translation.words.last().endMs <= original.words.last().endMs)
        assertTrue(romanization.words.isNotEmpty())
    }

    @Test
    fun `a document with no alternates reports none`() {
        val document = TtmlLyrics.parseDocument(ttml(paragraph("1.0", "5.0", "Just the two of us")))

        assertEquals(listOf("Just the two of us"), document.lines.map { it.text })
        assertNull(document.translation)
        assertNull(document.romanization)
    }

    @Test
    fun `a partial alternate is dropped whole rather than offered half empty`() {
        // The second line has no translation span. Keeping the first line's
        // alone would put line 1's translation against line 1 and leave line 2
        // blank, so the layer goes away entirely.
        val document = TtmlLyrics.parseDocument(
            ttml(
                paragraph("1.0", "5.0", "First line here", translation = "Primera linea") +
                    paragraph("6.0", "9.0", "Second line here"),
            )
        )

        assertNull(document.translation)
    }
    @Test
    fun `an alternate is the same length as the original, breaks and all`() {
        // The player swaps one list for the other wholesale. If the two were
        // different lengths the list would gain or lose rows mid-song and the
        // reader would lose their place, so the instrumental breaks that get
        // added to the original have to appear in the alternate too.
        val document = TtmlLyrics.parseDocument(
            ttml(
                paragraph("1.0", "5.0", "First line here", translation = "Primera linea", romanization = "Peurste lain") +
                    paragraph("20.0", "24.0", "Second line here", translation = "Segunda linea", romanization = "Sekonta lain")
            )
        )

        assertEquals(document.lines.size, document.translation!!.size)
        assertEquals(document.lines.size, document.romanization!!.size)
        // And every alternate row lands on the same moment as the row it
        // stands in for, break rows included.
        document.translation!!.forEachIndexed { index, line ->
            assertEquals(document.lines[index].timeMs, line.timeMs)
        }
    }

    @Test
    fun `parse still returns the original lines untouched`() {
        val source = ttml(paragraph("1.0", "5.0", "I will always love you", translation = "Siempre te amaré"))

        assertEquals(TtmlLyrics.parseDocument(source).lines, TtmlLyrics.parse(source))
    }

    @Test
    fun `a partial romanization is dropped whole, like a partial translation`() {
        // The dangerous shape is a short layer accepted against a long
        // original: index-matching would put line 1's romanization onto line
        // 2's words, and the reader would sing the previous line's syllables
        // over this line's music.
        val document = TtmlLyrics.parseDocument(
            ttml(
                paragraph("1.0", "5.0", "First line here", romanization = "Feasto lain") +
                    paragraph("6.0", "9.0", "Second line here"),
            )
        )

        assertNull(document.romanization)
        // The original survives intact — a bad alternate costs the alternate.
        assertEquals(
            // The 5s-6s silence is a real instrumental and is kept as a gap
            // row, so the words are compared rather than the whole list.
            document.lines.map { it.text }.filter { it.isNotBlank() },
            listOf("First line here", "Second line here"),
        )
    }

    @Test
    fun `an incomplete romanization does not take the translation down with it`() {
        // Two layers, two independent answers. A malformed x-roman says
        // nothing about the x-translation, and dropping both would take away a
        // rendering that was perfectly good.
        val document = TtmlLyrics.parseDocument(
            ttml(
                paragraph(
                    "1.0", "5.0", "First line here",
                    translation = "Primera linea", romanization = "Feasto lain",
                ) + paragraph(
                    "6.0", "9.0", "Second line here",
                    translation = "Segunda linea",
                ),
            )
        )

        assertNull(document.romanization)
        assertEquals(
            document.translation!!.map { it.text }.filter { it.isNotBlank() },
            listOf("Primera linea", "Segunda linea"),
        )
    }

    @Test
    fun `a rejected layer is logged, so silence can be told from absence`() {
        // Without this, "the provider sent nothing" and "the provider sent
        // something we refused" look identical from the outside, and those
        // two need opposite fixes.
        LyricsLog.clear()
        TtmlLyrics.parseDocument(
            ttml(
                paragraph("1.0", "5.0", "First line here", romanization = "Feasto lain") +
                    paragraph("6.0", "9.0", "Second line here"),
            )
        )

        val rejection = LyricsLog.entries.value.firstOrNull { it.message.contains("x-roman") }
        assertNotNull("rejection of a partial x-roman was not logged", rejection)
        assertEquals(LyricsLog.Level.WARN, rejection!!.level)
        // Both counts, because the count is what makes the log diagnosable.
        assertTrue(rejection.message.contains("1"))
        assertTrue(rejection.message.contains("2"))
    }

    @Test
    fun `a document with no romanization span logs nothing`() {
        // The log exists to explain a refusal. A document that simply never
        // carried the layer has nothing to refuse, and must not be made to
        // look as though it did.
        LyricsLog.clear()
        TtmlLyrics.parseDocument(ttml(paragraph("1.0", "5.0", "Just the two of us")))

        assertNull(LyricsLog.entries.value.firstOrNull { it.message.contains("x-roman") })
    }

    @Test
    fun `two voices alternate sides so the duet reads as a conversation`() {
        val document = TtmlLyrics.parseDocument(
            ttml(
                voiced("1.0", "2.0", "Are you coming", "v1") +
                    voiced("2.0", "3.0", "Already here", "v2") +
                    voiced("3.0", "4.0", "Then let us go", "v1") +
                    voiced("4.0", "5.0", "Lead the way", "v2")
            )
        )

        // The same voice twice in a row is the same turn, not two of them, so
        // only the changes of voice move the side. Both singers land on both
        // sides over four lines.
        assertEquals(
            listOf(LyricAlignment.Start, LyricAlignment.End, LyricAlignment.Start, LyricAlignment.End),
            document.lines.map { it.alignment },
        )
    }

    @Test
    fun `a chorus sung by everyone stays left and does not take a turn`() {
        val document = TtmlLyrics.parseDocument(
            ttml(
                voiced("1.0", "2.0", "Sing it with me", "v1") +
                    // Apple's reserved group id: it carries no declaration of
                    // its own, and it belongs to neither side.
                    voiced("2.0", "3.0", "We are the ones", "v1000") +
                    voiced("3.0", "4.0", "Sing it again", "v1") +
                    voiced("4.0", "5.0", "Sing it louder", "v2")
            )
        )

        // The chorus is nobody's turn, so the voice that sings either side of
        // it is the same voice and nothing moves. v1 keeps the left it opened
        // on, and the panel only changes side when v2 actually takes over.
        assertEquals(
            listOf(LyricAlignment.Start, LyricAlignment.Start, LyricAlignment.Start, LyricAlignment.End),
            document.lines.map { it.alignment },
        )
    }

    /**
     * A lead line and then nothing but the second singer: the shape that trips
     * the flip, because the walk starts the opening line on the left and every
     * line after it on the right.
     */
    private fun leadThenDuet(closingChorus: Boolean): String = buildString {
        append(voiced("1.0", "2.0", "Opening", "v1"))
        repeat(6) { index ->
            val at = 2.0 + index
            append(voiced("$at", "${at + 1.0}", "Answer ${index + 1}", "v2"))
        }
        if (closingChorus) append(voiced("8.0", "9.0", "All together", "v1000"))
    }

    @Test
    fun `a song that is almost entirely the second singer is flipped, not laid out down the right`() {
        val document = TtmlLyrics.parseDocument(ttml(leadThenDuet(closingChorus = false)))

        // Six of the seven lines came out on the right. Taken at face value
        // that is a whole song hugging the right edge, so the walk is turned
        // around and the opening line joins the other six.
        assertEquals(LyricAlignment.End, document.lines.first().alignment)
        assertTrue(document.lines.drop(1).all { it.alignment == LyricAlignment.Start })
    }

    @Test
    fun `a closing chorus is enough to stop a near-right song being flipped`() {
        val document = TtmlLyrics.parseDocument(ttml(leadThenDuet(closingChorus = true)))

        // A group chorus is laid out but never counted as a right-hand line,
        // so one of them takes six right lines down to six of eight and the
        // song reads the way it was written: a line on the left, the other
        // singer all the way through, then everyone at once.
        val alignments = document.lines.map { it.alignment }
        assertEquals(LyricAlignment.Start, alignments.first())
        assertEquals(LyricAlignment.End, alignments[1])
        assertEquals(LyricAlignment.End, alignments[6])
        assertEquals(LyricAlignment.Start, alignments.last())
    }
}
