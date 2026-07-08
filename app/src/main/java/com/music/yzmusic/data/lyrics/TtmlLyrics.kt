package com.music.yzmusic.data.lyrics

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource

/**
 * Apple Music's word-timed lyric format.
 *
 * A document is `<p>` per sung line, each holding one `<span>` per syllable
 * with its own `begin`/`end`:
 *
 * ```xml
 * <p begin="27.395" end="28.960" ttm:agent="v1">
 *   <span begin="27.395" end="27.549">I</span>
 *   <span begin="27.549" end="27.740">been</span>
 * </p>
 * ```
 *
 * Syllables of one word are written as adjacent spans with no whitespace
 * between them ("e" + "nough"), so whitespace — not the span boundary — is
 * what separates words. That is the whole trick to reading this format.
 *
 * Parsed with DOM rather than a pull parser so this stays plain JVM code and
 * can be unit tested off-device.
 */
object TtmlLyrics {

    /**
     * Roles that are not this line at all: translations and romanisations are
     * alternate renderings of the same words and would double the line up.
     */
    private val SKIPPED_ROLES = setOf("x-translation", "x-roman")

    /**
     * The answering vocal. It is this line, sung by a second voice over the
     * lead and often past the *next* line's stamp, so it is collected apart
     * and carried as [LyricLine.background] — run into the lead's own words it
     * dragged the sweep along and the tail of the line was skipped.
     */
    private const val BACKGROUND_ROLE = "x-bg"

    fun parse(ttml: String): List<LyricLine> = runCatching {
        val factory = DocumentBuilderFactory.newInstance().apply {
            // The document declares four namespaces and we address attributes
            // by their qualified names (ttm:agent), so leave prefixes intact.
            isNamespaceAware = false
            // Lyrics arrive from a third-party host; refuse to resolve
            // anything the document asks us to go and fetch.
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }
        val document = factory.newDocumentBuilder().parse(InputSource(StringReader(ttml)))
        val paragraphs = document.getElementsByTagName("p")

        val lines = ArrayList<LyricLine>(paragraphs.length)
        for (i in 0 until paragraphs.length) {
            val paragraph = paragraphs.item(i) as? Element ?: continue
            lineFrom(paragraph)?.let(lines::add)
        }
        lines.sortedBy { it.timeMs }.withInstrumentalGaps()
    }.getOrDefault(emptyList())

    private fun lineFrom(paragraph: Element): LyricLine? {
        val pieces = mutableListOf<Piece>()
        val backingPieces = mutableListOf<Piece>()
        collect(paragraph, pieces, backingPieces)
        val words = mergeIntoWords(pieces)
        val backing = mergeIntoWords(backingPieces).takeIf { it.isNotEmpty() }?.let {
            LyricLine(
                timeMs = it.first().startMs,
                text = it.joinToString(" ") { word -> word.text },
                words = it,
            )
        }

        if (words.isEmpty()) {
            // Line-synced TTML: a <p> with a stamp and bare text, no spans.
            // textContent is the whole paragraph, backing vocal included, so
            // there is nothing here to hang underneath — the bracket in the
            // text is all the separation the document gave.
            val text = paragraph.textContent?.trim().orEmpty()
