package com.music.yzmusic.data.lyrics

import android.content.Context

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest

/**
 * One piece of singable text waiting to be transformed, and the place it came
 * from.
 *
 * The index is what makes reconstruction safe. A transform hands back a bag of
 * strings; without a stable way back to the line each one came from, a short
 * or reordered result slides line *n*'s answer onto line *n+1*'s words — which
 * is not a cosmetic fault, it sings the wrong words in time with the right
 * music.
 */
internal data class LyricTextSlot(
    val lineIndex: Int,
    val background: Boolean,
    val text: String,
)

/** What a romanization attempt produced. */
sealed interface RomanizationResult {
    /**
     * Latin script already — the words are romanized by being what they are,
     * so there is nothing to do and no reason to spend a pass over them.
     */
    data object AlreadyRomanized : RomanizationResult

    /**
     * The text now reads in Latin script. [sourceLanguage] is what the script
     * was identified as, kept for display and for the cache key.
     */
    data class Romanized(
        val lines: List<LyricLine>,
        val sourceLanguage: String,
        val fromCache: Boolean = false,
    ) : RomanizationResult

    /**
     * The attempt could not be completed — an unsupported script, a transform
     * that changed nothing, or a slot count that came back wrong. Distinct
     * from [AlreadyRomanized], which is a *successful* answer: the original
     * stays on screen in both cases, but only one of them is a reason to stop
     * offering the mode.
     */
    data object Unavailable : RomanizationResult
}

/**
 * The one thing this file cannot decide for itself.
 *
 * Kept behind an interface because the platform transliterator is a stubbed
 * `android.jar` binding under unit test, and because the choice of engine is
 * the one genuinely open decision in romanization — see [IcuTransliterator].
 */
internal fun interface ScriptTransliterator {
    /** Latin-script rendering of [text], or null when this engine cannot do it. */
    fun toLatin(text: String): String?
}

/**
 * Android's own transliteration, via ICU's `Any-Latin` transform.
 *
 * Why this and not BitChord's engine, which posts the lyric to Google's
 * `translate_a/single` with `tl=Latn`: that path needs a network, a service
 * that can be down, and permission to send a song's words to a third party.
 * YZ's translation already runs on device and the app states that no lyric text
 * is sent to an application server; a remote romanizer would be the one part of
 * the feature that quietly broke that. ICU is in the platform at API 24 and this
 * app's floor is 26, so it costs nothing and works on a plane.
 *
 * ## It is not the engine for Japanese, and that is deliberate
 *
 * `Any-Latin` routes *Han* through Mandarin pinyin regardless of the kana
 * around it, so a real Japanese song came out with Chinese readings fused to
 * its correct kana:
 *
 *     そんな顔が嫌いだ  ->  son na yán ga xiáni da
 *     知らない            ->  zhīrazu
 *
 * 知らない is `shiranai` and 顔 is `kao`; `zhīrazu` and `yán` are Mandarin
 * readings of characters Japanese reads differently. ICU has no Japanese kanji
 * reading lexicon and no transform id that supplies one, so this is not
 * configurable away. It also spells kana as a transcription rather than a
 * reading — `kon'nichiha` for こんにちは — because it is a direct
 * transliteration, not a common-name romanisation.
 *
 * [ScriptRoutingTransliterator] therefore sends Japanese to
 * [JapaneseTransliterator] and keeps this engine for everything else, where it
 * is genuinely good and was measured to be so:
 *
 *     안녕하세요  -> annyeonghaseyo (correct)
 *     Привет     -> Privet         (correct)
 *     مرحبا     -> mrḥbạ          (common romanisation: marhaba)
 *
 * The Arabic marks are kept deliberately — stripping them discards
 * distinctions that carry meaning in several scripts.
 *
 * `IcuRomanizationTest`, run on a device, is what keeps the claims above
 * honest. Re-run it after any engine change.
 */
internal object IcuTransliterator : ScriptTransliterator {
    private val anyLatin by lazy {
        runCatching { android.icu.text.Transliterator.getInstance("Any-Latin") }.getOrNull()
    }

    override fun toLatin(text: String): String? {
        val transform = anyLatin ?: return null
        val out = runCatching { transform.transliterate(text) }.getOrNull() ?: return null
        return out.trim().takeIf { it.isNotEmpty() }
    }
}

/**
 * Turns a song's lyrics into Latin script, entirely on device.
 *
 * This is not translation. Japanese becomes romaji, Cyrillic becomes Latin
 * letters, Arabic becomes Latin letters — the words stay the words that were
 * sung, and nothing is said in another language. It shares no code path with
 * [LyricsTranslation] beyond the shape of its result, and never will: a
 * translation that came back as a different *language* would be wrong here in
 * a way that is invisible until someone reads it.
 */
interface LyricsRomanizer {
    suspend fun romanize(
        lines: List<LyricLine>,
        targetLanguageTag: String,
    ): RomanizationResult
}

/**
 * The romanization pipeline.
 *
 * Kept free of Android types so it can be exercised directly: a unit test runs
 * against a stubbed `android.jar` in which every framework method throws, so a
 * class that touched [IcuTransliterator] inline would test nothing at all.
 * Everything that decides *what happens* lives here; only the script conversion
 * itself is handed out to [ScriptTransliterator].
 */
internal class RomanizationPipeline(
    private val transliterator: ScriptTransliterator,
    private val cache: BoundedCache = BoundedCache(ROMANIZATION_CACHE_ENTRIES),
) : LyricsRomanizer {

    override suspend fun romanize(
        lines: List<LyricLine>,
        targetLanguageTag: String,
    ): RomanizationResult {
        if (lines.isEmpty()) return RomanizationResult.Unavailable
        val slots = flatten(lines)
        if (slots.isEmpty()) return RomanizationResult.Unavailable

        // Checked before any work is done, and on the real text rather than a
        // sample: a lyric is short enough that sampling it is not worth the
        // risk of missing the one non-Latin line in a Latin song.
        if (!hasNonLatinLetters(slots)) return RomanizationResult.AlreadyRomanized

        val key = cacheKey(targetLanguageTag, slots)
        cache.get(key)?.let { return it.toResult(lines, slots) }

        val converted = withContext(Dispatchers.Default) {
            // A cancellation has to escape rather than be absorbed into
            // "Unavailable" — the caller is waiting on a track that has
            // already moved on, and a failed result would be reported as
            // though the new track could not be romanized.
            var failure: Throwable? = null
            val out = ArrayList<String>(slots.size)
            for (slot in slots) {
                val romanized = try {
                    transliterator.toLatin(slot.text)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    failure = error
                    null
                }
                if (romanized == null) {
                    LyricsLog.w(TAG, "Romanization failed at line ${slot.lineIndex}: ${failure?.message}")
                    return@withContext null
                }
                out += romanized
            }
            out
        } ?: return RomanizationResult.Unavailable

        // The whole point of the slot index. A transform that dropped, added or
        // reordered a line would otherwise be written straight over the
        // original list and every line after the gap would be wrong.
        if (converted.size != slots.size) {
            LyricsLog.w(
                TAG,
                "Romanization returned ${converted.size} texts for ${slots.size} slots; rejecting",
            )
            return RomanizationResult.Unavailable
        }

        val rebuilt = rebuild(lines, slots, converted)
        // Non-Latin input that came back unchanged means this engine does not
        // know the script. Rendering it anyway would put the original script
        // under a "Romanized" label, which is worse than not offering the mode.
        if (unchangedWeight(slots, converted) >= converted.sumOf { it.length }) {
            LyricsLog.w(TAG, "Romanization left non-Latin text unchanged; script unsupported")
            return RomanizationResult.Unavailable
        }

        val language = detectScript(slots)
        cache.put(key, CacheEntry(converted, language))
        return RomanizationResult.Romanized(rebuilt, language, fromCache = false)
    }

    private fun CacheEntry.toResult(
        original: List<LyricLine>,
        slots: List<LyricTextSlot>,
    ): RomanizationResult {
        // A cache entry is only valid for the slot layout it was made from.
        // Bumping the cache key on any change to the pipeline is the intended
        // way to retire old entries; this is the belt to that braces, and
        // catches a key collision rather than a stale build.
        if (texts.size != slots.size) return RomanizationResult.Unavailable
        return RomanizationResult.Romanized(rebuild(original, slots, texts), language, fromCache = true)
    }
}

private const val TAG = "LyricsRomanization"
private const val ROMANIZATION_CACHE_ENTRIES = 8

internal data class CacheEntry(
    val texts: List<String>,
    val language: String,
)

/**
 * Cache key over the text that will actually be transformed, not the track it
 * came from.
 *
 * The same song resolves to different lyrics from different providers, and the
 * same track id can be re-fetched with corrected timings. Keying on identity
 * would happily hand a romanization of yesterday's words back over today's.
 */
private fun cacheKey(targetLanguageTag: String, slots: List<LyricTextSlot>): String {
    val source = buildString {
        append(targetLanguageTag.trim().lowercase())
        slots.forEach {
            append('\u0000').append(it.lineIndex)
            append('\u0001').append(if (it.background) 'b' else 'l')
            append('\u0001').append(it.text)
        }
    }
    return MessageDigest.getInstance("SHA-256")
        .digest(source.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

/** Every singable string in the song, in the order a reader meets them. */
internal fun flatten(lines: List<LyricLine>): List<LyricTextSlot> {
    val slots = ArrayList<LyricTextSlot>(lines.size * 2)
    lines.forEachIndexed { index, line ->
        // A gap is the absence of a lyric. It has no text to convert and
        // keeping it as a slot would demand an answer the engine cannot give.
        if (line.isGap) return@forEachIndexed
        slots += LyricTextSlot(index, background = false, text = line.text)
        line.background?.let { backing ->
            if (backing.text.isNotBlank()) {
                slots += LyricTextSlot(index, background = true, text = backing.text)
            }
        }
    }
    return slots.filter { it.text.isNotBlank() }
}

/**
 * Put the transformed texts back onto the original lines.
 *
 * Every timing field is read from [line] and never written: the transform
 * changes what a line *says*, never when it is sung. A background vocal is
 * rebuilt against its own line rather than folded into the lead, because it is
 * a second voice with its own clock — merging it would drag the answering
 * vocal's words onto the lead's highlight.
 */
private fun rebuild(
    original: List<LyricLine>,
    slots: List<LyricTextSlot>,
    texts: List<String>,
): List<LyricLine> {
    val byLine = slots.zip(texts).groupBy { it.first.lineIndex }
    return original.mapIndexed { index, line ->
        val entries = byLine[index].orEmpty()
        if (entries.isEmpty()) return@mapIndexed line
        val lead = entries.firstOrNull { !it.first.background }?.second
        val backing = entries.firstOrNull { it.first.background }?.second
        val rebuiltBackground = line.background?.let { source ->
            val text = backing ?: source.text
            if (text == source.text) source else source.retimedForTranslation(text)
        }
        val leadText = lead ?: line.text
        if (leadText == line.text && rebuiltBackground === line.background) {
            line
        } else {
            line.retimedForTranslation(leadText, rebuiltBackground)
        }
    }
}

/**
 * Does anything here need romanizing?
 *
 * By Unicode script, per code point — an ASCII test would call a Cyrillic
 * lyric Latin, and digits and punctuation are not evidence of anything.
 */
internal fun hasNonLatinLetters(slots: List<LyricTextSlot>): Boolean = slots.any { slot ->
    slot.text.codePoints().anyMatch { codePoint ->
        Character.isLetter(codePoint) &&
            Character.UnicodeScript.of(codePoint) != Character.UnicodeScript.LATIN
    }
}

/** A coarse script name for the cache entry and for display. */
private fun detectScript(slots: List<LyricTextSlot>): String {
    val counts = HashMap<String, Int>()
    slots.forEach { slot ->
        slot.text.codePoints().forEach { codePoint ->
            if (!Character.isLetter(codePoint)) return@forEach
            val script = Character.UnicodeScript.of(codePoint)
            if (script == Character.UnicodeScript.LATIN) return@forEach
            counts.merge(script.name, 1, Int::plus)
        }
    }
    return counts.maxByOrNull { it.value }?.key?.lowercase() ?: "unknown"
}

/** How many characters came back identical, ignoring case and spacing. */
private fun unchangedWeight(slots: List<LyricTextSlot>, converted: List<String>): Int =
    slots.zip(converted).sumOf { (slot, text) ->
        if (comparable(slot.text) == comparable(text)) slot.text.length else 0
    }

private fun comparable(text: String): String = text
    .trim()
    .lowercase()
    .replace(Regex("\\s+"), " ")

/** A small LRU. The keys are content digests, so entries expire with their song. */
internal class BoundedCache(private val limit: Int) {
    private val entries = object : LinkedHashMap<String, Any>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Any>?): Boolean =
            size > limit
    }

    @Suppress("UNCHECKED_CAST")
    fun get(key: String): CacheEntry? = synchronized(entries) { entries[key] as? CacheEntry }

    fun put(key: String, value: CacheEntry) {
        synchronized(entries) { entries[key] = value }
    }

    fun size(): Int = synchronized(entries) { entries.size }

    fun clear() = synchronized(entries) { entries.clear() }
}

/**
 * The romanizer the app actually uses.
 *
 * The one place an engine is chosen. Everything above it — the player, the
 * display mode, the cache key — is written against [LyricsRomanizer] and does
 * not change if a different [ScriptTransliterator] is substituted.
 */
object LyricsRomanization {

    /**
     * The bundled kanji lexicon, loaded once per process.
     *
     * The load is the expensive part — a quarter of a megabyte of asset
     * parsed into two maps — so it is cached and the engine built around it is
     * not. A fresh pipeline per call is cheap and keeps the cache its own
     * business, which is what [engineFor] is for.
     *
     * Doubly checked because two tracks finishing together can both reach
     * here first, and parsing the asset twice on that race is a visible
     * stutter rather than a rounding error. Kotlin's `lazy` cannot be used:
     * it would cache the pipeline of whichever [Context] arrived first and
     * hand the same instance to every later caller.
     */
    @Volatile
    private var lexicon: InMemoryJapaneseLexicon? = null

    /**
     * The engine the app uses. A [Context] is only needed to open the asset,
     * and only the first time this is called.
     */
    fun forContext(context: Context): LyricsRomanizer {
        val loaded = lexicon ?: synchronized(this) {
            lexicon ?: JapaneseLexiconLoader.load(context.applicationContext.assets)
                .also { lexicon = it }
        }
        return RomanizationPipeline(
            ScriptRoutingTransliterator(JapaneseTransliterator(loaded)),
        )
    }

    internal fun engineFor(
        transliterator: ScriptTransliterator,
        cache: BoundedCache = BoundedCache(ROMANIZATION_CACHE_ENTRIES),
    ): RomanizationPipeline = RomanizationPipeline(transliterator, cache)
}
