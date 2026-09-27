package com.music.yzmusic

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.music.yzmusic.data.lyrics.IcuTransliterator
import com.music.yzmusic.data.lyrics.JapaneseLexiconLoader
import com.music.yzmusic.data.lyrics.JapaneseTransliterator
import com.music.yzmusic.data.lyrics.LyricLine
import com.music.yzmusic.data.lyrics.LyricsRomanization
import com.music.yzmusic.data.lyrics.RomanizationResult
import com.music.yzmusic.data.lyrics.ScriptRoutingTransliterator
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The engines, on a real device.
 *
 * Unit tests run against a stubbed `android.jar` in which every framework
 * method throws, so [IcuTransliterator] — a real binding to
 * `android.icu.text.Transliterator` — is unexecuted there. This file is the only
 * place its behaviour is actually known, which is the difference between "the
 * pipeline maps results correctly" and "the thing we ship produces romanization".
 *
 * The Japanese engine is here for a different reason: it loads
 * [JapaneseLexiconLoader.ASSET_NAME] through `AssetManager`, which a JVM test
 * cannot reach at all. The lexicon's *contents* are pinned by
 * `JapaneseTransliteratorTest` on the JVM; this pins that the packaged asset
 * parses and that routing picks the right engine for a real line.
 */
@RunWith(AndroidJUnit4::class)
class IcuRomanizationTest {

    private fun line(time: Long, text: String) = LyricLine(timeMs = time, text = text)

    private val japanese: JapaneseTransliterator by lazy {
        JapaneseTransliterator(
            JapaneseLexiconLoader.load(
                androidx.test.platform.app.InstrumentationRegistry
                    .getInstrumentation().context.assets,
            ),
        )
    }

    private val router: ScriptRoutingTransliterator by lazy {
        ScriptRoutingTransliterator(japanese)
    }

    // --- the asset -----------------------------------------------------------

    @Test
    fun theBundledAssetParsesInsideTheApk() {
        // The failure this guards is packaging, not logic: an asset that is
        // absent from the build, renamed, or truncated would leave every
        // kanji in a Japanese song unromanized with nothing anywhere saying so.
        val lexicon = JapaneseLexiconLoader.load(
            androidx.test.platform.app.InstrumentationRegistry
                .getInstrumentation().context.assets,
        )
        assertNotNull("自分 is missing from the packaged asset", lexicon.readingsOf('自'))
        assertNotNull("顔 is missing from the packaged asset", lexicon.readingsOf('顔'))
    }

    // --- Japanese, end to end through the packaged asset --------------------

    @Test
    fun japaneseKanjiIsRomanizedAsJapaneseNotAsMandarin() {
        // The defect this whole engine exists to fix. The old ICU-only path
        // produced `yán` and `zhīranai` here; `sonnakaogakiraida` and
        // `shiranai` are what a reader of this lyric needs.
        val out = router.toLatin("そんな顔が嫌いだ")
        assertNotNull("no romanization for a mixed kana/kanji line", out)
        assertFalse("Mandarin pinyin leaked into \"$out\"", out!!.contains("yán"))
        assertEquals("sonnakaogakiraida", out)

        val shiranai = router.toLatin("知らない")
        assertEquals("shiranai", shiranai)
    }

    @Test
    fun aKanaOnlyLineIsRomanizedByTheJapaneseEngine() {
        assertEquals("konnichiwa", router.toLatin("こんにちは"))
    }

    @Test
    fun routingSendsChineseHanToIcuRatherThanToTheJapaneseEngine() {
        // The reason the split is per line and not per song: Chinese is Han too,
        // and the Japanese engine would read 我爱你 with Japanese readings.
        // All this asserts is that it is not left as Han.
        val out = router.toLatin("我爱你")
        assertNotNull(out)
        assertFalse("Han survived romanization: \"$out\"", out!!.any { it.code > 0x2E7F })
    }

    @Test
    fun routingSendsKoreanAndCyrillicToIcu() {
        // These are the scripts ICU is genuinely good at, and the reason it is
        // still in the app.
        assertEquals("annyeonghaseyo", router.toLatin("안녕하세요"))
        assertEquals("Privet", router.toLatin("Привет"))
    }

    // --- ICU directly -------------------------------------------------------

    /**
     * Asserting the diacritics deliberately — this expectation was wrong when
     * first written, and the device is what proved it.
     *
     * `Any-Latin` is a direct transliteration, not a common-name
     * romanisation, so it renders Arabic as `mrḥbạ` where a dictionary-backed
     * engine gives `marhaba`. A real difference from the engine this replaces,
     * kept rather than papered over: stripping the marks would discard
     * distinctions that carry meaning in several scripts.
     */
    @Test
    fun arabicBecomesLatinScriptThroughIcu() {
        assertEquals("mrḥbạ", IcuTransliterator.toLatin("مرحبا"))
    }

    @Test
    fun latinTextIsPassedThroughUntouched() {
        // The engine's own guard rejects Latin before it reaches here; this
        // asserts the binding is not what makes that decision.
        assertEquals("Hello world", IcuTransliterator.toLatin("Hello world"))
    }

    // --- the pipeline, on a real engine -------------------------------------

    /**
     * End to end through the real engine, with no fake in the path.
     *
     * The whole claim of the feature is that a track with no publisher-supplied
     * `x-roman` still gets one, so this is the run that would have caught a
     * wrongly-chosen ICU transform, a slot-index bug, or a rebuild that wrote
     * the wrong line's text onto the wrong timestamp.
     */
    @Test
    fun theRealEngineProducesRomanizedLyricsForALyricWithNoProviderRomanization() = runBlocking {
        val source = listOf(line(1_000, "こんにちは"), line(4_000, "さようなら"))

        val result = LyricsRomanization.forContext(
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext,
        ).romanize(source, "en")

        assertTrue("expected a romanization, got $result", result is RomanizationResult.Romanized)
        val lines = (result as RomanizationResult.Romanized).lines
        assertEquals(2, lines.size)
        assertEquals("konnichiwa", lines[0].text)
        assertEquals("sayounara", lines[1].text)
        // The two answers must be genuinely different lines' text, not one
        // answer smeared across both.
        assertTrue(
            "both lines got the same text: '${lines[0].text}' / '${lines[1].text}'",
            lines[0].text != lines[1].text,
        )
        // And the clock is untouched — this is a display layer, not a retime.
        assertEquals(1_000L, lines[0].timeMs)
        assertEquals(4_000L, lines[1].timeMs)
    }

    @Test
    fun aLatinLyricIsReportedAsAlreadyRomanizedByTheRealEngine() = runBlocking {
        val result = LyricsRomanization.forContext(
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext,
        ).romanize(listOf(line(0, "Hello world")), "en")
        assertEquals(RomanizationResult.AlreadyRomanized, result)
    }

    @Test
    fun aBlankLyricIsNotReportedAsRomanized() = runBlocking {
        // Whitespace holds no non-Latin letters, so the pipeline must answer
        // AlreadyRomanized rather than run the engine and hand back an empty
        // string dressed up as a result.
        val result = LyricsRomanization.forContext(
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext,
        ).romanize(listOf(line(0, "   ")), "en")
        assertEquals(RomanizationResult.AlreadyRomanized, result)
    }
}
