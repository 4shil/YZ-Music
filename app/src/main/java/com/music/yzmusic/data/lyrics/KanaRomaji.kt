package com.music.yzmusic.data.lyrics

/**
 * Kana to romaji, Hepburn.
 *
 * ICU's `Any-Latin` maps kana too, but as a *direct* transliteration, which
 * is why it produced `kon'nichiha` for こんにちは: the kana were right and only
 * the `ha`, the long vowel and the syllabic `n` were spelled the way a
 * transcription writes them rather than the way the word is read. This table
 * is the one the whole lexicon runs through, so a reading that the kanji tier
 * splits across a kanji boundary and one that a word entry hands over whole
 * are spelled the same way.
 *
 * The decisions worth knowing about:
 *
 *  - `ん` before a vowel or `y` keeps its apostrophe (`shin'ai`), because
 *    dropping it merges a syllable boundary that is audible.
 *  - `は` is `wa` when kana precede it and `ha` when it opens a word:
 *    わたし -> watashi, はな -> hana. Only position distinguishes the
 *    particle from the first syllable of a word.
 *  - `ー` is dropped rather than doubled: ラーメン -> ramen, not raaamen. A
 *    doubled vowel reads as a transcription artifact in a lyric line.
 *
 * Katakana is not a second table. The block sits a fixed 0x60 above hiragana,
 * so a katakana character is normalised to its hiragana twin before lookup,
 * and every loanword, prop name and katakana lyric takes the same path.
 */
internal object KanaRomaji {

    private val SYLLABLES: Map<String, String> = buildMap {
        fun put(vararg pairs: Pair<String, String>) = pairs.forEach { put(it.first, it.second) }
        // Monographs.
        put(
            "あ" to "a", "い" to "i", "う" to "u", "え" to "e", "お" to "o",
            "か" to "ka", "き" to "ki", "く" to "ku", "け" to "ke", "こ" to "ko",
            "が" to "ga", "ぎ" to "gi", "ぐ" to "gu", "げ" to "ge", "ご" to "go",
            "さ" to "sa", "し" to "shi", "す" to "su", "せ" to "se", "そ" to "so",
            "ざ" to "za", "じ" to "ji", "ず" to "zu", "ぜ" to "ze", "ぞ" to "zo",
            "た" to "ta", "ち" to "chi", "つ" to "tsu", "て" to "te", "と" to "to",
            "だ" to "da", "ぢ" to "ji", "づ" to "zu", "で" to "de", "ど" to "do",
            "な" to "na", "に" to "ni", "ぬ" to "nu", "ね" to "ne", "の" to "no",
            "は" to "ha", "ひ" to "hi", "ふ" to "fu", "へ" to "he", "ほ" to "ho",
            "ば" to "ba", "び" to "bi", "ぶ" to "bu", "べ" to "be", "ぼ" to "bo",
            "ぱ" to "pa", "ぴ" to "pi", "ぷ" to "pu", "ぺ" to "pe", "ぽ" to "po",
            "ま" to "ma", "み" to "mi", "む" to "mu", "め" to "me", "も" to "mo",
            "や" to "ya", "ゆ" to "yu", "よ" to "yo",
            "ら" to "ra", "り" to "ri", "る" to "ru", "れ" to "re", "ろ" to "ro",
            "わ" to "wa", "ゐ" to "i", "ゑ" to "e", "を" to "wo", "ん" to "n",
            "ゔ" to "vu",
        )
        // Palatalised digraphs. Tried before the monographs, so しゃ is `sha`
        // rather than `shi` followed by a stranded ゃ.
        put(
            "きゃ" to "kya", "きゅ" to "kyu", "きょ" to "kyo",
            "ぎゃ" to "gya", "ぎゅ" to "gyu", "ぎょ" to "gyo",
            "しゃ" to "sha", "しゅ" to "shu", "しょ" to "sho",
            "じゃ" to "ja", "じゅ" to "ju", "じょ" to "jo",
            "ちゃ" to "cha", "ちゅ" to "chu", "ちょ" to "cho",
            "ぢゃ" to "ja", "ぢゅ" to "ju", "ぢょ" to "jo",
            "にゃ" to "nya", "にゅ" to "nyu", "にょ" to "nyo",
            "ひゃ" to "hya", "ひゅ" to "hyu", "ひょ" to "hyo",
            "びゃ" to "bya", "びゅ" to "byu", "びょ" to "byo",
            "ぴゃ" to "pya", "ぴゅ" to "pyu", "ぴょ" to "pyo",
            "みゃ" to "mya", "みゅ" to "myu", "みょ" to "myo",
            "りゃ" to "rya", "りゅ" to "ryu", "りょ" to "ryo",
            // Sounds that only exist in katakana, reachable once the offset
            // has been undone: ジュンCarlo, ジェット, ティー.
            "ふぁ" to "fa", "ふぃ" to "fi", "ふぇ" to "fe", "ふぉ" to "fo",
            "うぃ" to "wi", "うぇ" to "we", "うぉ" to "wo",
            "つぁ" to "tsa", "つぃ" to "tsi", "つぇ" to "tse", "つぉ" to "tso",
            "ゔぁ" to "va", "ゔぃ" to "vi", "ゔぇ" to "ve", "ゔぉ" to "vo",
            "てぃ" to "ti", "でぃ" to "di",
            "とぉ" to "two", "どぉ" to "dwo",
            "とぅ" to "tu", "どぅ" to "du", "てゅ" to "tyu", "でゅ" to "dyu",
            "しぇ" to "she", "じぇ" to "je", "ちぇ" to "che",
            "すぃ" to "si", "ずぃ" to "zi",
            "くぁ" to "kwa", "くぃ" to "kwi", "くぇ" to "kwe", "くぉ" to "kwo",
            "ぐぁ" to "gwa",
        )
        // Small kana. A digraph above still wins, because the two-character
        // lookup runs first; these are the fallbacks for a small kana that is
        // all the text there is, as in a katakana loanword.
        put(
            "ぁ" to "a", "ぃ" to "i", "ぅ" to "u", "ぇ" to "e", "ぉ" to "o",
            "ゃ" to "ya", "ゅ" to "yu", "ょ" to "yo", "ゎ" to "wa",
            "ゕ" to "ka", "ゖ" to "ke",
        )
    }

    private const val SOKUON_HIRA = 'っ'
    private const val SOKUON_KATA = 'ッ'
    private const val PROLONGED = 'ー'
    private const val N_HIRA = 'ん'
    private const val N_KATA = 'ン'
    private const val HA_HIRA = 'は'
    private const val HA_KATA = 'ハ'

    private const val VOWELS = "aeiou"
    private val VOWEL_KANA = "あいうえお".toSet()

    private const val KATAKANA_FIRST = 'ァ'
    private const val KATAKANA_LAST = 'ヶ'
    private const val KATAKANA_OFFSET = 0x60

    /** True for anything this table can pronounce, in either kana block. */
    fun isKana(c: Char): Boolean = c in 'ぁ'..'ゖ' || c in KATAKANA_FIRST..KATAKANA_LAST ||
        c == PROLONGED

    private fun toHiragana(c: Char): Char =
        if (c in KATAKANA_FIRST..KATAKANA_LAST) (c.code - KATAKANA_OFFSET).toChar() else c

    /**
     * Romaji for [kana], or null when the string holds no kana this table can
     * pronounce.
     *
     * Null rather than the input matters: it is how a caller tells "nothing
     * to pronounce" apart from "could not read it", and the pipeline refuses
     * to present an unchanged line as romanized. Kanji in [kana] is passed
     * through untouched — deciding how a kanji is read is the kanji tier's
     * job, and this table never guesses at one.
     */
    fun toRomaji(kana: String): String? {
        if (kana.isEmpty()) return null
        val out = StringBuilder(kana.length * 2)
        var i = 0
        var pronounced = false
        while (i < kana.length) {
            val c = kana[i]
            // Anything that is not kana — punctuation, spacing, a kanji the
            // caller left in place — is carried over as written.
            if (!isKana(c)) {
                out.append(c)
                i++
                continue
            }
            pronounced = true
            when (c) {
                PROLONGED -> i++

                SOKUON_HIRA, SOKUON_KATA -> {
                    // Geminate: double the consonant that follows. In front of
                    // a vowel there is no consonant to double and the mark is
                    // dropped, which is what いって -> itte needs.
                    val first = romajiAt(kana, i + 1).firstOrNull()
                    if (first != null && first !in VOWELS) out.append(first)
                    i++
                }

                N_HIRA, N_KATA -> {
                    out.append('n')
                    // しんあい -> shin'ai. Without the mark the two morae read
                    // as one and the line is hard to trace back to the kana.
                    val next = kana.getOrNull(i + 1)
                    if (next != null && (toHiragana(next) in VOWEL_KANA || next in "やゆよ")) {
                        out.append('\'')
                    }
                    i++
                }

                HA_HIRA, HA_KATA -> {
                    // Only the hiragana は is ever a particle. Written in
                    // katakana it is a loanword sound and always `ha`, which
                    // is what makes オハヨウ read ohayou and not owayou.
                    out.append(if (c == HA_HIRA && isParticle(kana, i)) "wa" else "ha")
                    i++
                }

                'へ', 'ヘ' -> {
                    // へ is `he` as a particle and as a direction. The rare `e`
                    // reading is not worth a wrong guess in a lyric, where へ
                    // is overwhelmingly the particle.
                    out.append("he")
                    i++
                }

                'を', 'ヲ' -> {
                    out.append("wo")
                    i++
                }

                else -> {
                    val romaji = romajiAt(kana, i)
                    if (romaji.isEmpty()) out.append(c) else out.append(romaji)
                    i += syllableLengthAt(kana, i)
                }
            }
        }
        return out.toString().takeIf { pronounced && it.isNotEmpty() }
    }

    /**
     * は is the particle `wa` when kana precede it and the first syllable `ha`
     * when it opens a word: わたし -> watashi, はな -> hana. There is no other
     * signal in the string, so position is the entire rule.
     */
    private fun isParticle(kana: String, index: Int): Boolean {
        val previous = kana.getOrNull(index - 1) ?: return false
        if (!isKana(previous) || previous == PROLONGED) return false
        return previous != SOKUON_HIRA && previous != SOKUON_KATA
    }

    /** Romaji of the syllable starting at [index], longest match first. */
    private fun romajiAt(kana: String, index: Int): String {
        if (index >= kana.length) return ""
        val first = toHiragana(kana[index])
        if (index + 1 < kana.length) {
            val digraph = SYLLABLES["$first${toHiragana(kana[index + 1])}"]
            if (digraph != null) return digraph
        }
        return SYLLABLES[first.toString()] ?: ""
    }

    private fun syllableLengthAt(kana: String, index: Int): Int {
        if (index + 1 < kana.length &&
            SYLLABLES["${toHiragana(kana[index])}${toHiragana(kana[index + 1])}"] != null
        ) return 2
        return 1
    }
}
