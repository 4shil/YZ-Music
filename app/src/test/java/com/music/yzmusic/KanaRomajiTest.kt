package com.music.yzmusic

import com.music.yzmusic.data.lyrics.KanaRomaji
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The kana rules, one behaviour each.
 *
 * These are the cases where ICU's direct transliteration and Hepburn differ,
 * so they are the ones worth pinning: a reader who cannot read the kana has
 * nothing to fall back on but these spellings.
 */
class KanaRomajiTest {

    private fun romaji(kana: String) = KanaRomaji.toRomaji(kana)

    @Test
    fun `kana run reads as words rather than as a transcription`() {
        assertEquals("konnichiwa", romaji("こんにちは"))
        assertEquals("arigatou", romaji("ありがとう"))
        assertEquals("sakura", romaji("さくら"))
    }

    @Test
    fun `は is wa as a particle and ha at the start of a word`() {
        // わたし — the particle, written in hiragana.
        assertEquals("watashi", romaji("わたし"))
        // はな, はい — the same character opening a word.
        assertEquals("hana", romaji("はな"))
        assertEquals("hai", romaji("はい"))
        // こんにちは closes on は with no kana after it at all.
        assertEquals("konnichiwa", romaji("こんにちは"))
    }

    @Test
    fun `katakana ha is a loanword sound and never a particle`() {
        // The particle は is written in hiragana. In katakana it is always
        // `ha`, which is what makes the stylised オハヨウ read ohayou.
        assertEquals("ohayou", romaji("オハヨウ"))
        assertEquals("hana", romaji("ハナ"))
    }

    @Test
    fun `ん keeps its apostrophe before a vowel`() {
        // しんあい, not shinai: the boundary between the two morae is audible.
        assertEquals("shin'ai", romaji("しんあい"))
        assertEquals("konnichiwa", romaji("こんにちは"))
        // No apostrophe before a consonant, and none at the end.
        assertEquals("hon", romaji("ほん"))
        assertEquals("kanta", romaji("かんた"))
    }

    @Test
    fun `small tsu doubles the following consonant`() {
        assertEquals("kitte", romaji("きって"))
        assertEquals("motto", romaji("もっと"))
        assertEquals("gakkou", romaji("がっこう"))
        assertEquals("zasshi", romaji("ざっし"))
    }

    @Test
    fun `small tsu before a vowel has no consonant to double`() {
        // いって -> itte, never itte-with-a-stray-u.
        assertEquals("itte", romaji("いって"))
        assertEquals("shiawase", romaji("しあわせ"))
    }

    @Test
    fun `long vowel mark is dropped without doubling`() {
        assertEquals("ramen", romaji("ラーメン"))
        assertEquals("kohi", romaji("コーヒー"))
    }

    @Test
    fun `katakana is read through the same table as hiragana`() {
        assertEquals("sakura", romaji("サクラ"))
        assertEquals("ai", romaji("アイ"))
        // A loanword sound that exists only in katakana.
        assertEquals("jetto", romaji("ジェット"))
        assertEquals("shi", romaji("シー"))
    }

    @Test
    fun `palatalised digraphs beat their leading syllable`() {
        // しゃ must be sha, not shi followed by a stranded small ya.
        assertEquals("shashin", romaji("しゃしん"))
        assertEquals("kyou", romaji("きょう"))
        assertEquals("ja", romaji("じゃ"))
    }

    @Test
    fun `を and へ are read as particles`() {
        assertEquals("wo", romaji("を"))
        assertEquals("he", romaji("へ"))
        assertEquals("hoho", romaji("ほほ"))
    }

    @Test
    fun `text with no kana is not a romanization`() {
        // Null, not the input: this is how the pipeline tells "nothing to
        // pronounce" apart from "could not read it", and it is why an
        // unchanged line is refused rather than labelled Romanized.
        assertNull(romaji(""))
        assertNull(romaji("Tokyo"))
        assertNull(romaji("123"))
    }

    @Test
    fun `punctuation is carried through as written`() {
        // The reader already knows the comma is a comma; rewriting it into a
        // Latin one would be a change of meaning, not of script.
        assertEquals("sakura、niji", romaji("さくら、にじ"))
        assertEquals("a i", romaji("あ い"))
    }

    @Test
    fun `kanji is passed through untouched`() {
        // Deciding how a kanji reads is the kanji tier's job. This table only
        // pronounces, and inventing a reading here would be the pinyin bug all
        // over again — so the character comes out as itself.
        assertNull(romaji("顔"))
        assertEquals("no", romaji("の"))
    }
}
