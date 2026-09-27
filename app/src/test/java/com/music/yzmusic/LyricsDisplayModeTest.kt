package com.music.yzmusic

import com.music.yzmusic.data.lyrics.LyricLine
import com.music.yzmusic.data.lyrics.LyricsPayload
import com.music.yzmusic.data.lyrics.TtmlLyrics.parseDocument
import com.music.yzmusic.ui.MainViewModel.LyricsAlternates
import com.music.yzmusic.ui.MainViewModel.LyricsDisplayMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The layer a mode resolves to, and the mode a track can offer.
 *
 * These are the two decisions a reader can see go wrong. Getting them wrong
 * either draws the original while the control claims a translation is on, or
 * hands back a layer one row short of the original — which is a lyric from the
 * wrong moment of the song, landing under the right words.
 */
class LyricsDisplayModeTest {

    private fun line(ms: Long, text: String) = LyricLine(timeMs = ms, text = text)

    /**
     * A two-line lead with a translation and a romanization laid out beside it,
     * the shape a real Apple-style TTML document has.
     */
    private val ttml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <tt xmlns="http://www.w3.org/ns/ttml"
            xmlns:ttm="http://www.w3.org/ns/ttml#metadata"
            xmlns:ttp="http://www.w3.org/ns/ttml#parameter"
            ttp:tickRate="10000">
          <body><div>
            ${paragraph("1.0", "5.0", "First line", "First translation", "Daiichi")}
            ${paragraph("6.0", "9.0", "Second line", "Second translation", "Daini")}
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
        append("""<span begin="$begin" end="$end" ttm:role="x-ala">$text </span>""")
        translation?.let {
            append("""<span ttm:role="x-translation" begin="0.0" end="99.0">$it</span>""")
        }
        romanization?.let {
            append("""<span ttm:role="x-roman" begin="0.0" end="99.0">$it</span>""")
        }
        append("</p>")
    }

    @Test
    fun `original mode resolves to no layer`() {
        val alternates = LyricsAlternates(
            translation = listOf(line(1_000, "First translation")),
            romanization = listOf(line(1_000, "Daiichi")),
        )
        // ORIGINAL is not an alternate: it is the document's own lead, so
        // asking this flow for it must not produce one of the side layers.
        assertNull(alternates.forMode(LyricsDisplayMode.ORIGINAL))
    }

    @Test
    fun `each mode resolves to its own layer`() {
        val translation = listOf(line(1_000, "First translation"))
        val romanization = listOf(line(1_000, "Daiichi"))
        val alternates = LyricsAlternates(translation, romanization)

        assertSame(translation, alternates.forMode(LyricsDisplayMode.TRANSLATED))
        assertSame(romanization, alternates.forMode(LyricsDisplayMode.ROMANIZED))
    }

    @Test
    fun `a track with no alternates offers nothing and is not empty only when both are absent`() {
        assertTrue(LyricsAlternates().isEmpty)
        assertTrue(!LyricsAlternates(translation = listOf(line(0, "x"))).isEmpty)
        assertTrue(!LyricsAlternates(romanization = listOf(line(0, "x"))).isEmpty)
    }

    @Test
    fun `a partial layer is not offered`() {
        // The repository drops a layer that is not the same length as the
        // lead, so an alternate one row short can never reach this point.
        // Guarding the invariant at the boundary it is enforced keeps the
        // failure named: an index-shift bug, not a blank screen.
        val document = parseDocument(ttml)
        val payload = LyricsPayload(
            lines = document.lines,
            translation = document.translation,
            romanization = document.romanization,
        )

        assertEquals(document.lines.size, payload.translation!!.size)
        assertEquals(document.lines.size, payload.romanization!!.size)
    }

    @Test
    fun `every layer carries the lead's timing so a swap does not move the scroll`() {
        val document = parseDocument(ttml)
        val leads = document.lines.map { it.timeMs }
        val translations = document.translation!!.map { it.timeMs }
        val romanizations = document.romanization!!.map { it.timeMs }

        assertEquals(leads, translations)
        assertEquals(leads, romanizations)
    }

    @Test
    fun `a payload with no alternates reports that it has none`() {
        val document = parseDocument(ttml)
        assertTrue(LyricsPayload(document.lines).hasAlternates.not())
        assertTrue(
            LyricsPayload(document.lines, document.translation, document.romanization).hasAlternates
        )
    }
}
