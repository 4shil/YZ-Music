package com.music.yzmusic

import com.music.yzmusic.data.lyrics.BoundedCache
import com.music.yzmusic.data.lyrics.LyricLine
import com.music.yzmusic.data.lyrics.LyricWord
import com.music.yzmusic.data.lyrics.LyricsRomanization
import com.music.yzmusic.data.lyrics.RomanizationPipeline
import com.music.yzmusic.data.lyrics.RomanizationResult
import com.music.yzmusic.data.lyrics.ScriptTransliterator
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * The engine is exercised through a fake transliterator rather than ICU.
 *
 * Unit tests run against a stubbed `android.jar` whose framework methods throw
 * rather than return, so the real engine is unreachable here by construction.
 * What these tests pin down is the part that decides correctness — which texts
 * are treated as needing work, whether a result is safe to write back over the
 * original, and what a cache is allowed to serve — and the fake is exact enough
 * to decide all of that.
 */
class LyricsRomanizationTest {

    /**
     * Stands in for a real transliteration. A text in [table] becomes its Latin
     * rendering; anything else comes back unchanged, which is what an engine
     * that does not know the script does. [failOn] models a transform that
     * simply gives up partway through a song.
     */
    private class FakeTransliterator(
        private val table: Map<String, String>,
        private val failOn: String? = null,
    ) : ScriptTransliterator {
        var calls = 0

        override fun toLatin(text: String): String? {
            calls++
            if (text == failOn) return null
            return table[text] ?: text
        }
    }

    private fun engine(
        table: Map<String, String>,
        cache: BoundedCache = BoundedCache(8),
        failOn: String? = null,
    ): Pair<RomanizationPipeline, FakeTransliterator> {
        val fake = FakeTransliterator(table, failOn)
        return LyricsRomanization.engineFor(fake, cache) to fake
    }

    private fun line(time: Long, text: String, background: LyricLine? = null) =
        LyricLine(timeMs = time, text = text, background = background)

    private val japanese = listOf(
        line(1_000, "こんにちは"),
        line(4_000, "さようなら"),
    )

    private val japaneseTable = mapOf("こんにちは" to "konnichiwa", "さようなら" to "sayounara")

    // ---- scripts that need work ------------------------------------------------

    @Test
    fun `japanese lyrics are romanized`() = runBlocking {
        val (pipeline, _) = engine(japaneseTable)
        val result = pipeline.romanize(japanese, "en")
        assertTrue(result is RomanizationResult.Romanized)
        val lines = (result as RomanizationResult.Romanized).lines
        assertEquals(listOf("konnichiwa", "sayounara"), lines.map { it.text })
    }

    @Test
    fun `korean lyrics are romanized`() = runBlocking {
        val (pipeline, _) = engine(mapOf("안녕하세요" to "annyeonghaseyo"))
        val result = pipeline.romanize(listOf(line(0, "안녕하세요")), "en")
        assertEquals("annyeonghaseyo", (result as RomanizationResult.Romanized).lines.single().text)
    }

    @Test
    fun `cyrillic lyrics are romanized`() = runBlocking {
        val (pipeline, _) = engine(mapOf("Привет" to "Privet"))
        val result = pipeline.romanize(listOf(line(0, "Привет")), "en")
        assertEquals("Privet", (result as RomanizationResult.Romanized).lines.single().text)
    }

    @Test
    fun `arabic lyrics are romanized`() = runBlocking {
        val (pipeline, _) = engine(mapOf("مرحبا" to "marhaba"))
        val result = pipeline.romanize(listOf(line(0, "مرحبا")), "en")
        assertEquals("marhaba", (result as RomanizationResult.Romanized).lines.single().text)
    }

    // ---- scripts that need no work ---------------------------------------------

    @Test
    fun `latin lyrics are already romanized and are not touched`() = runBlocking {
        val (pipeline, fake) = engine(mapOf("hello" to "HELLO"))
        val result = pipeline.romanize(listOf(line(0, "hello world")), "en")
        assertEquals(RomanizationResult.AlreadyRomanized, result)
        // The point of the check: no work spent on a lyric that needs none.
        assertEquals(0, fake.calls)
    }

    @Test
    fun `digits and punctuation are not evidence of a non-latin script`() = runBlocking {
        val (pipeline, fake) = engine(emptyMap())
        val result = pipeline.romanize(listOf(line(0, "1, 2 & 3 — oh!")), "en")
        assertEquals(RomanizationResult.AlreadyRomanized, result)
        assertEquals(0, fake.calls)
    }

    @Test
    fun `one non-latin line inside a latin lyric is still detected`() = runBlocking {
        val (pipeline, _) = engine(mapOf("愛" to "ai"))
        val result = pipeline.romanize(
            listOf(line(0, "hello"), line(1_000, "愛"), line(2_000, "world")),
            "en",
        )
        val lines = (result as RomanizationResult.Romanized).lines
        assertEquals("ai", lines[1].text)
        // Its neighbours are untouched — the pass is per line, not per song.
        assertEquals("hello", lines[0].text)
        assertEquals("world", lines[2].text)
    }

    // ---- refusals ---------------------------------------------------------------

    @Test
    fun `empty lyrics are unavailable`() = runBlocking {
        val (pipeline, _) = engine(emptyMap())
        assertEquals(RomanizationResult.Unavailable, pipeline.romanize(emptyList(), "en"))
    }

    @Test
    fun `a lyric of only gaps has nothing to convert`() = runBlocking {
        val (pipeline, _) = engine(emptyMap())
        val result = pipeline.romanize(
            listOf(LyricLine(timeMs = 0, text = ""), LyricLine(timeMs = 1_000, text = "")),
            "en",
        )
        assertEquals(RomanizationResult.Unavailable, result)
    }

    @Test
    fun `a non-latin lyric the engine cannot convert is unavailable rather than faked`() =
        runBlocking {
            // No table entry, so the fake hands the text back untouched — the
            // same shape as an engine meeting a script it does not know.
            val (pipeline, _) = engine(emptyMap())
            val result = pipeline.romanize(listOf(line(0, "ქართული")), "en")
            assertEquals(RomanizationResult.Unavailable, result)
        }

    @Test
    fun `a transform that fails partway does not write back the lines it did finish`() =
        runBlocking {
            // The dangerous case is a partial answer: writing it back would
            // slide the first line's romanization onto the second line's words.
            val (pipeline, _) = engine(japaneseTable, failOn = "さようなら")
            val result = pipeline.romanize(japanese, "en")
            assertEquals(RomanizationResult.Unavailable, result)
        }

    // ---- structure that must survive -------------------------------------------

    @Test
    fun `line order and count are preserved`() = runBlocking {
        val source = listOf(line(1_000, "一"), line(2_000, "二"), line(3_000, "三"))
        val (pipeline, _) = engine(mapOf("一" to "ichi", "二" to "ni", "三" to "san"))
        val lines = (pipeline.romanize(source, "en") as RomanizationResult.Romanized).lines
        assertEquals(3, lines.size)
        assertEquals(listOf("ichi", "ni", "san"), lines.map { it.text })
        assertEquals(source.map { it.timeMs }, lines.map { it.timeMs })
    }

    @Test
    fun `transforming the text never moves the timing`() = runBlocking {
        val source = listOf(
            LyricLine(
                timeMs = 1_234,
                text = "適用",
                words = listOf(LyricWord(1_234, 2_000, "適"), LyricWord(2_100, 3_000, "用")),
                sungUntilMs = 3_500,
            ),
        )
        val (pipeline, _) = engine(mapOf("適用" to "tekiyou"))
        val line = (pipeline.romanize(source, "en") as RomanizationResult.Romanized).lines.single()
        assertEquals(1_234L, line.timeMs)
        assertEquals(3_500L, line.sungUntilMs)
        // Word timings are re-projected onto the new text rather than moved: the
        // sweep still starts and ends where the singing does.
        assertEquals(1_234L, line.words.first().startMs)
        assertEquals(3_000L, line.words.last().endMs)
    }

    @Test
    fun `background vocals are romanized in place and stay separate from the lead`() = runBlocking {
        val source = listOf(line(1_000, "歌", background = line(1_500, "コーラス")))
        val (pipeline, _) = engine(mapOf("歌" to "uta", "コーラス" to "korasu"))
        val line = (pipeline.romanize(source, "en") as RomanizationResult.Romanized).lines.single()
        assertEquals("uta", line.text)
        // The answering vocal keeps its own identity and its own clock; folding
        // it into the lead would drag it onto the lead's highlight.
        assertEquals("korasu", line.background?.text)
        assertEquals(1_500L, line.background?.timeMs)
    }

    @Test
    fun `a gap line is left exactly as it was`() = runBlocking {
        val source = listOf(line(0, "再生"), LyricLine(timeMs = 2_000, text = ""))
        val (pipeline, _) = engine(mapOf("再生" to "saisei"))
        val lines = (pipeline.romanize(source, "en") as RomanizationResult.Romanized).lines
        assertEquals("", lines[1].text)
        assertEquals(2_000L, lines[1].timeMs)
    }

    @Test
    fun `a line the engine leaves alone is not marked as changed`() = runBlocking {
        // The last line comes back identical. The song still romanizes, and the
        // untouched line keeps its own word timings rather than being rebuilt.
        val source = listOf(
            LyricLine(0, "適用", words = listOf(LyricWord(0, 500, "適用"))),
            line(1_000, "カタカナ"),
        )
        val (pipeline, _) = engine(mapOf("適用" to "tekiyou"))
        val lines = (pipeline.romanize(source, "en") as RomanizationResult.Romanized).lines
        assertEquals("tekiyou", lines[0].text)
        assertEquals("カタカナ", lines[1].text)
        assertEquals(source[1], lines[1])
    }

    // ---- caching ----------------------------------------------------------------

    @Test
    fun `a repeated request is served from cache without transforming again`() = runBlocking {
        val (pipeline, fake) = engine(japaneseTable, cache = BoundedCache(8))
        val first = pipeline.romanize(japanese, "en") as RomanizationResult.Romanized
        assertEquals(false, first.fromCache)
        val callsAfterFirst = fake.calls
        val second = pipeline.romanize(japanese, "en") as RomanizationResult.Romanized
        assertEquals(true, second.fromCache)
        assertEquals(callsAfterFirst, fake.calls)
        assertEquals(first.lines.map { it.text }, second.lines.map { it.text })
    }

    @Test
    fun `one song's cached answer is not served over another's words`() = runBlocking {
        val (pipeline, _) = engine(
            japaneseTable + mapOf("ありがとう" to "arigatou"),
            cache = BoundedCache(8),
        )
        pipeline.romanize(japanese, "en")
        // Same track id, different provider, different words. A cache keyed on
        // identity would serve the first song's romanization over these.
        val other = listOf(line(1_000, "ありがとう"))
        val result = pipeline.romanize(other, "en") as RomanizationResult.Romanized
        assertEquals("arigatou", result.lines.single().text)
        assertEquals(false, result.fromCache)
    }

    @Test
    fun `a cache entry is not reused for a different target language`() = runBlocking {
        val (pipeline, _) = engine(japaneseTable, cache = BoundedCache(8))
        pipeline.romanize(japanese, "en")
        val other = pipeline.romanize(japanese, "fr") as RomanizationResult.Romanized
        assertEquals(false, other.fromCache)
    }

    @Test
    fun `the cache is bounded`() = runBlocking {
        val cache = BoundedCache(2)
        val (pipeline, _) = engine(
            mapOf("一" to "ichi", "二" to "ni", "三" to "san", "四" to "shi"),
            cache = cache,
        )
        listOf("一", "二", "三", "四")
            .map { listOf(line(0, it)) }
            .forEach { pipeline.romanize(it, "en") }
        assertTrue("cache grew to ${cache.size()} entries for a limit of 2", cache.size() <= 2)
    }

    @Test
    fun `a mixed-script song keeps its Latin capitals when the locale folds them`() =
        runBlocking {
            // Under a Turkish locale "I" folds to a dotless ı, so a comparison
            // done in the default locale can score a line that *was* converted
            // as unchanged and throw the result away. The song carries a
            // Cyrillic line so it is worth romanizing at all, and a Latin one
            // that exercises the fold.
            val original = Locale.getDefault()
            try {
                Locale.setDefault(Locale.forLanguageTag("tr-TR"))
                val (pipeline, _) = engine(mapOf("И" to "i", "I" to "i"))
                val result = pipeline.romanize(listOf(line(0, "И"), line(500, "I")), "en")

                assertTrue("expected a romanization, got $result", result is RomanizationResult.Romanized)
                assertEquals(listOf("i", "i"), (result as RomanizationResult.Romanized).lines.map { it.text })
            } finally {
                Locale.setDefault(original)
            }
        }

    @Test
    fun `the same song romanizes the same way whatever the device language is`() =
        runBlocking {
            suspend fun run(tag: String): String {
                val original = Locale.getDefault()
                try {
                    Locale.setDefault(Locale.forLanguageTag(tag))
                    val (pipeline, _) = engine(mapOf("I" to "i", "И" to "i"))
                    val result = pipeline.romanize(listOf(line(0, "И"), line(500, "I")), "en")
                    assertTrue("expected a romanization, got $result", result is RomanizationResult.Romanized)
                    return (result as RomanizationResult.Romanized).lines.joinToString("|") { it.text }
                } finally {
                    Locale.setDefault(original)
                }
            }

            assertEquals(run("en-US"), run("tr-TR"))
        }
}
