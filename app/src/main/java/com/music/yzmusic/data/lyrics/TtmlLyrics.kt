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
     * Alternate renderings Apple ships in the same document as the words.
     *
     * These used to be discarded, which threw away a translation the host had
     * already fetched and paid for. They are read into their own line lists
     * instead, each one a rendering of the same line rather than a line of its
     * own — see [Document].
     */
    private const val TRANSLATION_ROLE = "x-translation"
    private const val ROMANIZATION_ROLE = "x-roman"

    /**
     * Spans arrive with the document's own line breaks and indentation still
     * in them, which would otherwise be taken for spaces between words.
     */
    private val WHITESPACE = Regex("\\s+")

    /**
     * The answering vocal. It is this line, sung by a second voice over the
     * lead and often past the *next* line's stamp, so it is collected apart
     * and carried as [LyricLine.background] — run into the lead's own words it
     * dragged the sweep along and the tail of the line was skipped.
     */
    private const val BACKGROUND_ROLE = "x-bg"

    /**
     * One TTML document read whole.
     *
     * [lines] is always the original words and stays the canonical rendering:
     * the other two are [LyricLine]s built on the *same* timings, one for one,
     * so switching between them cannot move a line off its music. A document
     * with no `x-translation` span simply leaves [translation] null — which is
     * what tells the player not to offer it, rather than offering a layer that
     * would render empty.
     */
    data class Document(
        val lines: List<LyricLine>,
        val translation: List<LyricLine>? = null,
        val romanization: List<LyricLine>? = null,
    )

    /** The original lines only — what every caller that wants one rendering wants. */
    fun parse(ttml: String): List<LyricLine> = parseDocument(ttml).lines

    fun parseDocument(ttml: String): Document = runCatching {
        val factory = DocumentBuilderFactory.newInstance().apply {
            // The document declares four namespaces and we address attributes
            // by their qualified names (ttm:role), so leave prefixes intact.
            isNamespaceAware = false
            // Lyrics arrive from a third-party host; refuse to resolve
            // anything the document asks us to go and fetch.
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }
        val document = factory.newDocumentBuilder().parse(InputSource(StringReader(ttml)))
        val paragraphs = document.getElementsByTagName("p")

        val lines = ArrayList<LyricLine>(paragraphs.length)
        val translations = ArrayList<LyricLine>()
        val romanizations = ArrayList<LyricLine>()
        for (i in 0 until paragraphs.length) {
            val paragraph = paragraphs.item(i) as? Element ?: continue
            val read = lineFrom(paragraph) ?: continue
            lines += read.line
            // Kept in step with [lines] by index: both are appended together
            // and dropped together, so a missing alternate never shifts a
            // later line's translation onto the wrong words.
            read.translation?.let { translations += it }
            read.romanization?.let { romanizations += it }
        }
        // The completeness check is made on the paragraphs as they came, and
        // the breaks are added afterwards to every layer alike. Doing it the
        // other way round — breaking the original first — would leave the
        // alternates a row short, and swapping one for the other mid-song
        // would shorten the list under the reader's thumb.
        val sorted = lines.sortedBy { it.timeMs }
        Document(
            lines = sorted.withInstrumentalGaps(),
            translation = aligned(translations, sorted, "x-translation"),
            romanization = aligned(romanizations, sorted, "x-roman"),
        )
    }.getOrDefault(Document(emptyList()))

    /**
     * Accept an alternate layer only if it lines up one-to-one with the lines
     * it is meant to sit beside.
     *
     * Index-matching a short layer would put line 47's translation onto line
     * 48's words — a silent wrongness that reads as a translation bug and
     * cannot be traced back to here. Rejecting costs the reader a rendering
     * they were never reliably getting anyway, so the safe side is the whole
     * layer going rather than half of it landing on the wrong verse.
     *
     * The rejection is logged because silence here is indistinguishable from
     * the document never having carried the layer, and those two need
     * opposite fixes.
     */
    private fun aligned(
        layer: List<LyricLine>,
        lines: List<LyricLine>,
        role: String,
    ): List<LyricLine>? {
        if (layer.isEmpty()) return null
        if (layer.size != lines.size) {
            LyricsLog.w(
                "TtmlLyrics",
                "Rejected $role: ${layer.size} lines for ${lines.size} originals; " +
                    "not index-matchable",
            )
            return null
        }
        return layer.sortedBy { it.timeMs }.withInstrumentalGaps()
    }

    /**
     * One paragraph read whole: its own line, plus the alternates beside it.
     * The alternates are already retimed onto [line]'s clock, so a caller can
     * treat all three as interchangeable renderings of the same moment.
     */
    private class Read(
        val line: LyricLine,
        val translation: LyricLine?,
        val romanization: LyricLine?,
    )

    /**
     * Where a paragraph's spans go, one lane per role. A translation and a
     * romanization are alternate *renderings* of the same line, not extra
     * lines, which is exactly what keeping them in their own lanes buys: they
     * come back out one for one with the lead and can be swapped in without
     * the list ever having had a different number of rows in it.
     */
    private class Lanes {
        val lead = mutableListOf<Piece>()
        val backing = mutableListOf<Piece>()
        val translation = mutableListOf<Piece>()
        val romanization = mutableListOf<Piece>()

        fun text(pieces: List<Piece>): String? = pieces
            .joinToString("") { it.text }
            .replace(WHITESPACE, " ")
            .trim()
            .takeIf { it.isNotEmpty() }
    }

    private fun lineFrom(paragraph: Element): Read? {
        val lanes = Lanes()
        collect(paragraph, lanes)
        val words = mergeIntoWords(lanes.lead)
        val backing = mergeIntoWords(lanes.backing).takeIf { it.isNotEmpty() }?.let {
            LyricLine(
                timeMs = it.first().startMs,
                text = it.joinToString(" ") { word -> word.text },
                words = it,
            )
        }

        val line = if (words.isEmpty()) {
            // Line-synced TTML: a <p> with a stamp and bare text, no spans.
            // textContent is the whole paragraph, backing vocal included, so
            // there is nothing here to hang underneath — the bracket in the
            // text is all the separation the document gave.
            val text = paragraph.textContent?.trim().orEmpty()
            val begin = time(paragraph.getAttribute("begin")) ?: return null
            if (text.isEmpty()) return null
            // The paragraph's own end is the only thing that says when the
            // singing stops, so carry it — a break can't be found without it.
            val end = time(paragraph.getAttribute("end"))?.takeIf { it > begin }
            LyricLine(timeMs = begin, text = text, sungUntilMs = end)
        } else {
            // Prefer the paragraph's own stamp: Apple sets it a hair before the
            // first syllable on lines that open with a soft consonant, and that
            // lead-in is when the line should appear.
            val begin = time(paragraph.getAttribute("begin")) ?: words.first().startMs
            LyricLine(
                timeMs = minOf(begin, words.first().startMs),
                text = words.joinToString(" ") { it.text },
                words = words,
                background = backing,
            )
        }

        val translation = lanes.text(lanes.translation)
        val romanization = lanes.text(lanes.romanization)
        return Read(
            line = line,
            // Projected onto the lead's own clock rather than given the
            // alternate span's stamps: the original timing is what the song is
            // sung to, and a translation that drifted off it would sweep out
            // of step with the audio the moment it was shown.
            translation = translation?.let { line.retimedForTranslation(it) },
            romanization = romanization?.let { line.retimedForTranslation(it) },
        )
    }

    /**
     * Flattens a paragraph into timed spans and the whitespace between them.
     * Nested spans (Apple wraps background vocals, translations and
     * romanizations, and occasionally whole phrases, in an outer timed span)
     * recurse to their leaves, so only the innermost timings — the ones
     * actually per-syllable — survive.
     *
     * A span's role picks the [sink] for it *and for everything under it*, so
     * that a translation nested inside a wrapper still lands in the
     * translation lane rather than leaking into the lead.
     */
    private fun collect(node: Node, lanes: Lanes, sink: MutableList<Piece> = lanes.lead) {
        val children = node.childNodes
        for (i in 0 until children.length) {
            when (val child = children.item(i)) {
                is Element -> {
                    val next = when (child.getAttribute("ttm:role")) {
                        TRANSLATION_ROLE -> lanes.translation
                        ROMANIZATION_ROLE -> lanes.romanization
                        BACKGROUND_ROLE -> lanes.backing
                        else -> sink
                    }
                    val begin = time(child.getAttribute("begin"))
                    val end = time(child.getAttribute("end"))
                    if (begin != null && end != null && !hasTimedChild(child)) {
                        next += Piece.Timed(child.textContent.orEmpty(), begin, end)
                    } else {
                        collect(child, lanes, next)
                    }
                }
                else -> if (child.nodeType == Node.TEXT_NODE) {
                    val text = child.textContent.orEmpty()
                    if (text.isNotEmpty()) sink += Piece.Text(text)
                }
            }
        }
    }


    private fun hasTimedChild(element: Element): Boolean {
        val children = element.childNodes
        for (i in 0 until children.length) {
            val child = children.item(i) as? Element ?: continue
            if (child.getAttribute("begin").isNotEmpty() || hasTimedChild(child)) return true
        }
        return false
    }

    /**
     * Glues syllables back into words. A word ends at the first whitespace
     * after it — whether that whitespace is a text node between two spans or
     * part of a span's own text — and its span runs from the first syllable's
     * start to the last one's end.
     */
    private fun mergeIntoWords(pieces: List<Piece>): List<LyricWord> {
        val words = mutableListOf<LyricWord>()
        val current = StringBuilder()
        var start = 0L
        var end = 0L
        // Untimed text is punctuation hanging off a span, or a line that was
        // never word-timed at all. Either way it can't carry a word of its
        // own — a word needs a span to get its timing from.
        var timed = false

        fun flush() {
            val text = current.toString().trim()
            current.setLength(0)
            if (text.isNotEmpty() && timed) words += LyricWord(start, end, text)
            timed = false
        }

        pieces.forEach { piece ->
            when (piece) {
                is Piece.Text -> when {
                    piece.text.isBlank() -> flush()
                    // Trailing punctuation belongs to the word it follows;
                    // anything before the first span has no timing to join.
                    timed -> current.append(piece.text)
                    else -> Unit
                }
                is Piece.Timed -> {
                    if (piece.text.isBlank()) return@forEach
                    // Leading whitespace closes off whatever came before it.
                    if (piece.text.first().isWhitespace()) flush()
                    if (current.isEmpty()) start = piece.start
                    current.append(piece.text.trim())
                    end = piece.end
                    timed = true
                    if (piece.text.last().isWhitespace()) flush()
                }
            }
        }
        flush()
        return words
    }

    /**
     * TTML clock values: `27.395`, `1:05.20`, `1:02:03.4`, or a plain number
     * with a `s`/`ms` unit. Returned in milliseconds.
     */
    internal fun time(value: String?): Long? {
        val raw = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (raw.endsWith("ms")) return raw.dropLast(2).toDoubleOrNull()?.toLong()
        val stripped = raw.removeSuffix("s")
        val parts = stripped.split(':')
        val seconds = when (parts.size) {
            1 -> parts[0].toDoubleOrNull()
            2 -> parts[0].toDoubleOrNull()?.let { m -> parts[1].toDoubleOrNull()?.let { m * 60 + it } }
            3 -> parts[0].toDoubleOrNull()?.let { h ->
                parts[1].toDoubleOrNull()?.let { m ->
                    parts[2].toDoubleOrNull()?.let { h * 3600 + m * 60 + it }
                }
            }
            else -> null
        } ?: return null
        return (seconds * 1000).toLong()
    }

    /** One piece of a flattened paragraph, timed or not. */
    private sealed interface Piece {
        val text: String
        data class Text(override val text: String) : Piece
        data class Timed(override val text: String, val start: Long, val end: Long) : Piece
    }
}
