package com.music.yzmusic.data.lyrics

import android.content.res.AssetManager
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader

/**
 * Japanese to romaji, from a bundled lexicon.
 *
 * The reason this exists at all: ICU's `Any-Latin` routes *Han* through
 * Mandarin pinyin, so a Japanese lyric came out with Chinese readings mixed
 * into its correct kana — そんな顔が嫌いだ -> `son na yán ga xiáni da`, and
 * 知らない -> `zhīrazu`. Both are pinyin of the kanji, neither is a reading
 * anybody in Japan would use. ICU has no Japanese kanji reading lexicon and
 * no transform id that supplies one, so this is not configurable away.
 *
 * ## How a line is read
 *
 * Two tiers, longest match first, then a single pass that decides how a
 * kanji that stands alone is pronounced.
 *
 * 1. **Words.** `W` entries in the asset: 自分 -> じぶん, 東京 -> とうきょう,
 *    好き -> すき. Around 8,500 of them. This tier exists because a compound
 *    is not the sum of its kanji: 東京 is とうきょう and not ひがしきょう,
 *    and no per-kanji rule can recover that. Longest match matters — if 好き
 *    is also the tail of 好き嫌い, the longer word has to win.
 * 2. **Single kanji**, for anything the word tier missed. See
 *    [JapaneseLexicon.readingFor] for the kun/on rule.
 * 3. **Kana**, which [KanaRomaji] handles and this class only routes.
 *
 * ## The kun/on rule
 *
 * A kanji has two kinds of reading and which one applies is a fact about the
 * word, not the character. Okurigana — the kana written after a kanji as part
 * of the same word — is the signal that survives without a parser:
 *
 *     分 + かる  -> わかる    (wakaru)   わかる ends in かる
 *     見 + える  -> みえる    (mieru)    みえる ends in える
 *     大 + きい  -> おおきい  (ookii)    おおきい ends in きい
 *
 * So: prefer the kun reading whose tail best matches the okurigana, emit the
 * part of the reading that the okurigana does not already supply, and let the
 * okurigana itself be pronounced normally. No okurigana means the kanji is
 * the whole word, which is where the on reading belongs — 大人 is だいじん,
 * not ひとびと.
 *
 * A single kanji that is its own word is the case the on default gets wrong,
 * and the word tier is what rescues it: 顔 is がん by rule, but 顔 is in the
 * word list as かお, so 顔 is read kao. This is why the word tier is consulted
 * before the kanji tier and not the other way round.
 *
* ## What is still wrong
*
* A kanji read on'yomi where Japanese would inflect it — 人 as にん in 二人
* against じん in 一人, where both readings exist and only the surrounding
* words decide — is not reachable from a kanji-plus-okurigana rule. So is a
* compound missing from the word list, and an okurigana set off from its kanji
* by a particle, as in 書をく, which is read as 書's stem plus a okurigana
* that includes the particle を.
*
* These produce odd but pronounceable output. The thing that must never come
* back is pinyin: that is what this engine exists to stop, and every tier here
* exists to keep Han out of the Mandarin path.
 */
internal class JapaneseTransliterator(
    private val lexicon: JapaneseLexicon,
) : ScriptTransliterator {

    override fun toLatin(text: String): String? {
        if (text.isBlank()) return null
        val out = StringBuilder(text.length * 2)
        var i = 0
        var lastWasKanji = false
        while (i < text.length) {
            val c = text[i]
            when {
                c == '々' -> {
                    // Iteration mark: repeats the kanji before it. Re-reading
                    // the same character is the whole meaning, so 人々 is
                    // hito + hito and not hito + 々.
                    val previous = lastKanjiAt(text, i)
                    if (previous == null) {
                        out.append(c)
                    } else {
                        out.append(kanjiReading(previous, text, i))
                    }
                    lastWasKanji = true
                    i++
                }

                isKanji(c) -> {
                    // Only words of two characters or more are matched here.
                    // A one-character entry is a kanji standing alone as a
                    // word — 顔 as かお — and it is deliberately left to the
                    // kanji tier, which can see the kana that follows and so
                    // can tell 顔 from 顔が嫌いだ.
                    val word = lexicon.wordStartingAt(text, i, minLength = 2)
                    if (word != null) {
                        out.append(KanaRomaji.toRomaji(word.kana) ?: word.kana)
                        i += word.kanjiCount
                    } else {
                        out.append(kanjiReading(c, text, i))
                        i++
                    }
                    lastWasKanji = true
                }

                KanaRomaji.isKana(c) -> {
                    // Consume the whole kana run at once: the rules that decide
                    // は -> wa and ん -> n' are positional, and a run boundary
                    // would throw away exactly the context they need.
                    var end = i
                    while (end < text.length && KanaRomaji.isKana(text[end])) end++
                    out.append(KanaRomaji.toRomaji(text.substring(i, end)) ?: text.substring(i, end))
                    i = end
                    lastWasKanji = false
                }

                else -> {
                    out.append(c)
                    i++
                    lastWasKanji = false
                }
            }
        }
        return out.toString().trim().takeIf { it.isNotEmpty() }
    }

    /**
     * Romaji for the single kanji at [index], consuming no okurigana — the
     * caller is walking the line and will meet the okurigana as kana in its
     * own right.
     */
    private fun kanjiReading(kanji: Char, text: String, index: Int): String {
        val readings = lexicon.readingsOf(kanji)
        val okurigana = okuriganaAt(text, index + 1)

        if (readings != null && okurigana.isNotEmpty()) {
            for (reading in readings.kun) {
                val overlap = sharedTail(reading, okurigana)
                if (overlap > 0) {
                    // The okurigana will be read as kana in its own right a
                    // moment later, so only the part of the reading it does not
                    // already account for is emitted: わかる minus かる is わ,
                    // and わ + かる is wakaru.
                    val stem = reading.substring(0, reading.length - overlap)
                    KanaRomaji.toRomaji(stem)?.let { return it }
                    KanaRomaji.toRomaji(reading)?.let { return it }
                }
            }
        }

        // A kun reading that is a *stem* — one that another reading in the same
        // list extends — followed by okurigana, means the kanji supplies only
        // the stem and the okurigana carries the rest. This tier is what makes
        // 知らない come out as shiranai: the lexicon lists 知 as し, しる, and
        // し + らない is the whole word. Falling through instead takes the on
        // reading and gives `chiranai` — pronounceable, wrong, and a mistake a
        // listener cannot catch but a reader certainly can.
        //
        // "Is a stem" is the whole test, and it is narrower than it looks. A
        // reading counts only when a longer one in the same list starts with
        // it, which is what the data means by listing a short form alongside
        // its own extensions. 笑 is え then えむ, but え is also a complete
        // reading in its own right — 笑う, 笑える — and わら has nothing to do
        // with it, so 笑った must not come out as `etta` when the answer is
        // `waratta`.
        //
        // The stem is emitted whole. Stripping the okurigana out of it is what
        // the tier above does, and applying both would drop the mora twice:
        // よ is the stem of よむ, and 読んで is yonde, not onde.
        //
        // A lone kana falls back to being the whole reading, which is what
        // あの*日* needs: ひ is not a stem of anything, and the okurigana
        // まで has no verb to continue, so it supplies the reading itself.
        if (readings != null && okurigana.isNotEmpty()) {
            val stem = readings.kun.firstOrNull { part ->
                readings.kun.any { it.length > part.length && it.startsWith(part) }
            }
            (stem ?: readings.kun.firstOrNull { it.length == 1 })?.let { chosen ->
                KanaRomaji.toRomaji(chosen)?.let { return it }
            }
        }

        // A one-character word entry beats either reading list, because it is
        // the reading a word actually uses: 顔 is がん on'yomi and かお in every
        // sentence it appears in. It applies only when the kanji stands alone.
        //
        // Isolation is the whole condition, and it is not decoration. The entry
        // for 人 is ひと, which is right for 人 by itself and badly wrong for
        // the second half of 大人: reading it there gives `daihito` instead of
        // `daijin`. A kanji with a kanji either side of it is in a compound,
        // and compounds are read on'yomi.
        if (isIsolated(text, index)) {
            val alone = lexicon.wordStartingAt(kanji.toString(), 0, minLength = 1)
            if (alone != null) return KanaRomaji.toRomaji(alone.kana) ?: alone.kana
        }

        // Still nothing. The kanji is part of a compound the lexicon does not
        // carry, and Japanese reads those on'yomi.
        if (readings != null) {
            readings.on.firstOrNull()?.let { return KanaRomaji.toRomaji(it).orEmpty() }
            readings.kun.firstOrNull()?.let { return KanaRomaji.toRomaji(it).orEmpty() }
        }
        return kanji.toString()
    }

    /**
     * True when the kanji at [index] has no kanji beside it, so it is a word
     * in its own right rather than part of a compound.
     */
    private fun isIsolated(text: String, index: Int): Boolean {
        val before = text.getOrNull(index - 1)
        val after = text.getOrNull(index + 1)
        return (before == null || !isKanji(before)) && (after == null || !isKanji(after))
    }

    /**
     * Length of the longest tail of [reading] that is also the head of
     * [okurigana]. わかる against かる gives かる, length 3.
     */
    private fun sharedTail(reading: String, okurigana: String): Int {
        val max = minOf(reading.length, okurigana.length)
        for (n in max downTo 1) {
            if (reading.regionMatches(reading.length - n, okurigana, 0, n)) return n
        }
        return 0
    }

    private fun okuriganaAt(text: String, from: Int): String {
        var end = from
        while (end < text.length && isHiragana(text[end])) end++
        return text.substring(from, end)
    }

    private fun lastKanjiAt(text: String, before: Int): Char? {
        var i = before - 1
        while (i >= 0) {
            val c = text[i]
            if (isKanji(c)) return c
            if (!c.isWhitespace()) return null
            i--
        }
        return null
    }

    private fun isHiragana(c: Char): Boolean = c in 'ぁ'..'ゖ'

    private fun isKanji(c: Char): Boolean = c.code in 0x3400..0x4DBF || c.code in 0x4E00..0x9FFF ||
        c.code in 0xF900..0xFAFF || c.code in 0x20000..0x2A6DF
}

/** One kanji's readings, kana in both spellings. */
internal data class KanjiReadings(val kun: List<String>, val on: List<String>)

/**
 * A Japanese word matched at [kanjiCount] characters from the start of a line.
 * Carries the reading as kana, never as romaji: the reading belongs to the
 * word, and letting [KanaRomaji] spell it keeps は and ー identical to how the
 * same characters are spelled when they are met in running text.
 */
internal class LexiconWordMatch(val kana: String, val kanjiCount: Int)

/**
 * The reading data, behind an interface so [JapaneseTransliterator] can be
 * tested against a handful of entries rather than the whole asset.
 */
internal interface JapaneseLexicon {
    /**
     * The longest word in the lexicon that starts at [from] in [text] and is
     * at least [minLength] characters, or null.
     *
     * Longest match is what keeps 好き嫌い from being read as 好き followed by
     * two loose kanji. [minLength] is what keeps a lone kanji from being
     * matched as a whole word: 分+かる is one word with okurigana, and taking
     * the one-character entry for 分 would commit to a reading before the
     * kana after it had been read.
     */
    fun wordStartingAt(text: String, from: Int, minLength: Int = 1): LexiconWordMatch?

    /** Kun and on readings of a single kanji, or null if the lexicon lacks it. */
    fun readingsOf(kanji: Char): KanjiReadings?
}

/**
 * The lexicon held in memory.
 *
 * Two maps, built once from the asset and read many times. The words are
 * keyed by their full surface form and matched by slicing the line at each
 * candidate length: a prefix tree would avoid the substring, but only for a
 * song's worth of short lines, and the map is the boring version.
 */
internal class InMemoryJapaneseLexicon(
    private val words: Map<String, String>,
    private val kanjiReadings: Map<Char, KanjiReadings>,
) : JapaneseLexicon {

    /** Longest surface form in [words], so a lookup can never run past it. */
    private val maxWordLength = words.keys.maxOfOrNull { it.length } ?: 0

    override fun wordStartingAt(text: String, from: Int, minLength: Int): LexiconWordMatch? {
        val limit = minOf(maxWordLength, text.length - from)
        for (length in limit downTo minLength.coerceAtLeast(1)) {
            val kana = words[text.substring(from, from + length)] ?: continue
            return LexiconWordMatch(kana, length)
        }
        return null
    }

    override fun readingsOf(kanji: Char): KanjiReadings? = kanjiReadings[kanji]
}

/**
 * Loads [ASSET_NAME] from the APK.
 *
 * Reading an asset is I/O, so this is only ever called from a background
 * dispatcher — the pipeline that uses it is already on
 * [kotlinx.coroutines.Dispatchers.Default]. A failure is logged and yields an
 * empty lexicon rather than throwing: a missing or truncated asset means
 * Japanese lyrics fall back to the original text, which is a worse but honest
 * outcome, and a crash on a song with kanji in it is not.
 *
 * Data: "Kanji alive" language data, CC BY 4.0, from
 * <https://github.com/kanjialive/kanji-data-media>.
 */
internal object JapaneseLexiconLoader {

    const val ASSET_NAME = "ja_romaji.txt"

    private const val TAG = "JapaneseLexicon"

    /**
     * The longest surface form the generator emits, capped so a corrupt or
     * future asset cannot make a lookup unbounded.
     */
    private const val MAX_WORD_CHARS = 12

    fun load(assets: AssetManager): InMemoryJapaneseLexicon =
        runCatching { parse(assets.open(ASSET_NAME)) }
            .onFailure { LyricsLog.w(TAG, "Kanji lexicon unavailable: ${it.message}") }
            .getOrDefault(InMemoryJapaneseLexicon(emptyMap(), emptyMap()))

    private fun parse(stream: java.io.InputStream): InMemoryJapaneseLexicon {
        val words = HashMap<String, String>(9_000)
        val kanji = HashMap<Char, KanjiReadings>(1_300)
        BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
            var line: String? = reader.readLine()
            while (line != null) {
                val parts = line.split('\t')
                when {
                    parts.size >= 3 && parts[0] == "W" && parts[1].length <= MAX_WORD_CHARS ->
                        words[parts[1]] = parts[2]
                    parts.size >= 3 && parts[0] == "K" && parts[1].length == 1 -> {
                        val kun = parts[2].substringBefore('|')
                        val on = parts[2].substringAfter('|')
                        kanji[parts[1][0]] = KanjiReadings(
                            kun = splitReadings(kun),
                            on = splitReadings(on),
                        )
                    }
                }
                line = reader.readLine()
            }
        }
        if (words.isEmpty() && kanji.isEmpty()) {
            LyricsLog.w(TAG, "Kanji lexicon asset $ASSET_NAME parsed to nothing")
        }
        return InMemoryJapaneseLexicon(words, kanji)
    }

    private fun splitReadings(cell: String): List<String> =
        if (cell.isEmpty()) emptyList() else cell.split(',').filter { it.isNotEmpty() }
}

/**
 * Sends Japanese to [JapaneseTransliterator] and everything else to
 * [IcuTransliterator].
 *
 * The split is per *line*, not per song, because a Japanese lyric routinely
 * has a Latin-script line in the middle of it — a shouted hook, an English
 * phrase — and that line must come back as it already is. Routing the other
 * way would send those lines through the kana rules and produce noise.
 *
 * The test is Japanese script, not Japanese language: hiragana, katakana or
 * Han. Han on its own is a weak signal, since Chinese lyrics are Han too, so
 * a Han-only line falls through to ICU — which is right for Chinese and only
 * imperfect for a kanji-only Japanese line the lexicon happens to miss.
 */
internal class ScriptRoutingTransliterator(
    private val japanese: ScriptTransliterator,
    private val other: ScriptTransliterator = IcuTransliterator,
) : ScriptTransliterator {

    override fun toLatin(text: String): String? = when {
        // Nothing to route, and both engines would return an empty string,
        // which the pipeline reads as "this line has no romanization". Saying
        // so here is cheaper than asking a transliterator to transliterate
        // whitespace, and it keeps the two engines from having to agree on it.
        text.isBlank() -> null
        hasJapaneseScript(text) -> japanese.toLatin(text)
        else -> other.toLatin(text)
    }

    private fun hasJapaneseScript(text: String): Boolean = text.any { c ->
        c in 'ぁ'..'ゖ' || c in 'ァ'..'ヶ' || c == 'ー' || isJapaneseKanji(c)
    }

    private fun isJapaneseKanji(c: Char): Boolean =
        c.code in 0x4E00..0x9FFF || c.code in 0x3400..0x4DBF
}
