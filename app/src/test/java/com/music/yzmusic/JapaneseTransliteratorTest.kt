package com.music.yzmusic

import com.music.yzmusic.data.lyrics.InMemoryJapaneseLexicon
import com.music.yzmusic.data.lyrics.JapaneseLexicon
import com.music.yzmusic.data.lyrics.JapaneseTransliterator
import com.music.yzmusic.data.lyrics.KanjiReadings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The Japanese engine, run against the lexicon the app actually ships.
 *
 * The fixture is [BUNDLED] read straight from `src/main/assets`, not a
 * hand-built stand-in. A test lexicon would only prove the tier logic and
 * would keep passing if the asset were emptied, truncated or reformatted —
 * which is the failure that actually matters here, because the asset is
 * generated data and nothing else in the build would notice.
 */
class JapaneseTransliteratorTest {

    private val lexicon: JapaneseLexicon by lazy {
        val file = File("src/main/assets/ja_romaji.txt")
        assertTrue("romaji asset missing at ${file.absolutePath}", file.isFile)
        parse(file.readText(Charsets.UTF_8))
    }

    private val engine = JapaneseTransliterator(lexicon)

    private fun read(text: String): String = engine.toLatin(text)!!

    @Test
    fun `the bundled asset is present and full`() {
        // If this fails, every other test in the class is asserting against
        // whatever a truncated parse happened to keep.
        val words = mutableListOf<String>()
        val kanji = mutableListOf<String>()
        File("src/main/assets/ja_romaji.txt").readLines(Charsets.UTF_8).forEach { line ->
            when (line.substringBefore('\t')) {
                "W" -> words += line
                "K" -> kanji += line
            }
        }
        assertTrue("only ${words.size} words in the lexicon", words.size > 8_000)
        assertTrue("only ${kanji.size} kanji in the lexicon", kanji.size > 1_200)
    }

    @Test
    fun `kanji is read as a Japanese reading, never as Mandarin`() {
        // The line the old engine turned into `son na yán ga xiáni da`.
        // `yán` is pinyin for 顔, which is `kao`. Spacing is the engine's to
        // choose; what this pins is the reading, and that no Mandarin syllable
        // appears anywhere in the output.
        val out = read("そんな顔が嫌いだ")
        assertFalse("Mandarin pinyin leaked into \"$out\"", out.contains("yán"))
        assertEquals("sonnakaogakiraida", out)
        assertEquals("shiranai", read("知らない"))
        assertEquals("koi", read("恋"))
        assertEquals("yume", read("夢"))
    }

    @Test
    fun `okurigana decides between the kun and on readings`() {
        // 分 + かる. The on reading is ぶん and would be wrong here; わかる
        // ends in exactly the かる that follows, so the kanji supplies わ.
        assertEquals("wakaru", read("分かる"))
        // 見 is みる, and the okurigana is the える of 見える, not みえる.
        assertEquals("mieru", read("見える"))
        assertEquals("ookii", read("大きい"))
    }

    @Test
    fun `a compound is read as a word, not as the sum of its kanji`() {
        // ひがし + きょう would be what the kanji tier alone produced. The word
        // entry is the only thing that can get this right, and it is why the
        // word tier is consulted first.
        assertEquals("toukyou", read("東京"))
        assertEquals("jikan", read("時間"))
        assertEquals("jibun", read("自分"))
        assertEquals("suki", read("好き"))
    }

    @Test
    fun `the longest word wins`() {
        // 好き嫌い is in the lexicon as a whole. Matching 好き first and
        // leaving 嫌い loose would read the line as two different words.
        assertEquals("sukikirai", read("好き嫌い"))
    }

    @Test
    fun `a kanji with no word entry falls back to the on reading`() {
        // 大人 is not in the lexicon, and neither kanji is a word on its own
        // here, so both are read on'yomi — which is what Japanese does for a
        // compound this engine has no entry for.
        assertEquals("daijin", read("大人"))
    }

    @Test
    fun `a line of kana and kanji comes back as one romanized line`() {
        // The shape the player actually renders: kana, kanji, kana, in one
        // string with no gaps. 日 is ひ here, not にち — the bare kun reading
        // is the one Japanese uses for あの *日*, where にち would only be
        // right before a counter like 一日.
        assertEquals("anohimade", read("あの日まで"))
        assertEquals("imamo", read("今も"))
    }

    @Test
    fun `kanji iteration repeats the previous kanji`() {
        assertEquals("hito", read("人"))
        assertEquals("hitobito", read("人々"))
    }

    @Test
    fun `inflected verbs take the kanji's stem and let the okurigana finish`() {
        // 笑 is listed as え, えむ among its readings, and taking the first
        // bare one gives `etta` for 笑った instead of `waratta`. The stem rule
        // is what keeps the okurigana attached to the right part of the kanji.
        assertEquals("waratta", read("笑った"))
        assertEquals("kaita", read("書いた"))
        assertEquals("yonde", read("読んで"))
        assertEquals("tonda", read("飛んだ"))
        assertEquals("oyoida", read("泳いだ"))
        assertEquals("iku", read("行く"))
    }

    @Test
    fun `katakana lyrics take the same path`() {
        assertEquals("sakura", read("サクラ"))
        assertEquals("ohayou", read("オハヨウ"))
    }

    @Test
    fun `blank text is not a romanization`() {
        // Null, not the input — the pipeline relies on this to refuse to
        // present an unchanged line as Romanized.
        assertNull(engine.toLatin(""))
        assertNull(engine.toLatin("   "))
    }

    @Test
    fun `a kanji the lexicon does not carry survives as itself`() {
        // Better a visible untranslated character than a dropped one or a
        // wrong guess: the line still has a position and the other words
        // around it are still readable.
        val rare = "𠮟" // a kanji outside the asset's 1,234
        assertEquals(rare, read(rare))
    }

    @Test
    fun `romaji output contains no kana or kanji`() {
        // The property the whole feature rests on: whatever the tiers decide,
        // nothing non-Latin escapes into the Romanized line.
        val samples = listOf(
            "そんな顔が嫌いだ", "知らない", "東京", "自分", "大きい", "ikusu?",
            "sakura", "人群", "時間だよ", "Fade away",
        )
        for (sample in samples) {
            val out = engine.toLatin(sample) ?: continue
            val leftovers = out.filter { it.code > 0x2E7F && it != '—' }
            assertTrue(
                "\"$sample\" left non-Latin output: \"$out\" ($leftovers)",
                leftovers.isEmpty(),
            )
        }
    }

    private fun parse(asset: String): JapaneseLexicon {
        val words = HashMap<String, String>(9_000)
        val kanji = HashMap<Char, KanjiReadings>(1_300)
        asset.lineSequence().forEach { line ->
            val parts = line.split('\t')
            when {
                parts.size >= 3 && parts[0] == "W" -> words[parts[1]] = parts[2]
                parts.size >= 3 && parts[0] == "K" && parts[1].length == 1 ->
                    kanji[parts[1][0]] = KanjiReadings(
                        kun = parts[2].substringBefore('|').split(',').filter { it.isNotEmpty() },
                        on = parts[2].substringAfter('|').split(',').filter { it.isNotEmpty() },
                    )
            }
        }
        return InMemoryJapaneseLexicon(words, kanji)
    }
}
