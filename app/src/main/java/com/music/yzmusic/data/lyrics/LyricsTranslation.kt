package com.music.yzmusic.data.lyrics

import com.music.yzmusic.data.DebugLog as Log
import com.music.yzmusic.data.Http
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.text.BreakIterator
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** State exposed to the player while it prepares or displays a translation. */
sealed interface LyricsTranslationState {
    data object Idle : LyricsTranslationState

    data class Loading(val targetLanguageTag: String) : LyricsTranslationState

    data class Ready(
        val targetLanguageTag: String,
        val sourceLanguageTag: String,
        val lines: List<LyricLine>,
    ) : LyricsTranslationState

    data class AlreadyInTargetLanguage(
        val targetLanguageTag: String,
    ) : LyricsTranslationState

    data class Unavailable(
        val targetLanguageTag: String,
    ) : LyricsTranslationState

    /**
     * The request could not be made right now, but nothing is wrong with the
     * song and the same tap is worth making again later.
     *
     * This is its own state rather than a flavour of [Unavailable] because the
     * two want opposite things from the button. Unavailable means the endpoint
     * cannot answer this at all, so offering the disc again would only fail
     * identically. Blocked means the network said no *this time* — a dead
     * connection, a refused request, or the reader's data rule — so the disc
     * stays lit and tappable, because moving to WiFi and tapping again is
     * exactly the action that resolves it.
     */
    data class Blocked(
        val targetLanguageTag: String,
    ) : LyricsTranslationState
}


/**
 * Translates lyrics through Google's web endpoint while leaving their musical
 * clock untouched.
 *
 * Nothing is downloaded and nothing is stored on the device: a lyric sheet is
 * a few kilobytes, which is cheaper to send than a translation model is to
 * fetch, and the reader's data rule is honoured by refusing outright rather
 * than by waiting — see [networkAllowed].
 *
 * Answers are kept twice, because a miss here is a network round trip. A small
 * in-process cache covers the repeated tap and the back-and-forth within a
 * session, and a GZIP'd copy under [android.content.Context.getCacheDir]
 * covers the next launch, bounded by total bytes and reclaimable by Android at
 * any time.
 */
object LyricsTranslation {
    private const val ENDPOINT = "https://translate.googleapis.com/translate_a/single"
    private const val INPUT_TOOLS_ENDPOINT = "https://inputtools.google.com/request"

    /**
     * Bumped whenever the answer's *meaning* changes, so a song that once came
     * back untranslated is not served the stale answer forever. Version 3 is
     * the endpoint switch; a version 2 answer came from a different engine.
     */
    private const val CACHE_VERSION = 3
    private const val CACHE_DIRECTORY = "lyrics_translation_v3"
    private const val MAX_CACHE_BYTES = 2L * 1024L * 1024L
    private const val MAX_BATCH_CHARS = 3_500
    private const val MAX_PARALLEL_REQUESTS = 2
    private const val MEMORY_ENTRIES = 12
    private const val TAG = "LyricsTranslation"

    // Input Tools changes ASCII digits to the destination script, so match the
    // private-use wrapper rather than assuming the counter remains ASCII.
    private val markerRegex = Regex("[^]*")
    private val json = Json { ignoreUnknownKeys = true }
    private val diskMutex = Mutex()
    private val memory = android.util.LruCache<String, CachedTranslation>(MEMORY_ENTRIES)

    /**
     * The app's shared client, narrowed to the deadlines a lyric request can
     * afford. The connection pool, DNS and address family stay shared — that is
     * what the sharing in [Http] is for — but a whole-song batch that has not
     * come back in a dozen seconds is a dead request, not a slow one, and the
     * reader deserves a retry button rather than a spinner.
     */
    private val client by lazy {
        Http.client.newBuilder()
            .callTimeout(12, TimeUnit.SECONDS)
            .connectTimeout(5, TimeUnit.SECONDS)
            .build()
    }

    @Serializable
    private data class CachedTranslation(
        val version: Int = CACHE_VERSION,
        val sourceLanguage: String,
        val targetLanguage: String,
        val texts: List<String>,
    )

    private data class TextSlot(
        val lineIndex: Int,
        val background: Boolean,
        val text: String,
        val sectionHeader: Boolean,
    )

    private data class Batch(
        val slots: List<TextSlot>,
        val payload: String,
    )

    private data class BatchAnswer(
        val translations: List<String>,
        val sourceLanguage: String,
        val sourceWeight: Int,
    )

    /**
     * @param networkAllowed the reader's data rule, already decided by the
     *   caller. A refusal costs a millisecond and a question; the alternative
     *   is a request that goes out over a metered connection the reader said
     *   no to.
     */
    suspend fun translate(
        context: android.content.Context,
        lines: List<LyricLine>,
        targetLanguageTag: String,
        networkAllowed: Boolean = true,
    ): LyricsTranslationState {
        // Sent as given rather than reduced to a base language: zh-CN and
        // zh-TW are the same language in two scripts, and canonicalising either
        // to "zh" hands back Simplified whichever one was asked for.
        val target = targetLanguageTag.trim()
        if (target.isBlank() || lines.isEmpty()) {
            return LyricsTranslationState.Unavailable(targetLanguageTag)
        }

        val slots = flatten(lines)
        if (slots.isEmpty()) return LyricsTranslationState.Unavailable(target)

        val key = cacheKey(target, slots)
        val cached = memory.get(key) ?: readCache(context, key)?.also { memory.put(key, it) }
        if (cached != null && cached.version == CACHE_VERSION && cached.texts.size == slots.size) {
            return if (sameLanguage(cached.sourceLanguage, target)) {
                LyricsTranslationState.AlreadyInTargetLanguage(target)
            } else {
                LyricsTranslationState.Ready(
                    targetLanguageTag = target,
                    sourceLanguageTag = cached.sourceLanguage,
                    lines = rebuild(lines, slots, cached.texts),
                )
            }
        }

        if (!networkAllowed) return LyricsTranslationState.Blocked(target)

        val batches = batches(slots)
        val answers = coroutineScope {
            // Two short requests at a time keeps a long lyric fast without
            // competing with playback for every connection in the pool.
            batches.chunked(MAX_PARALLEL_REQUESTS).flatMap { group ->
                group.map { batch -> async { requestBatch(batch, target) } }.awaitAll()
            }
        }
        if (answers.any { it == null }) return LyricsTranslationState.Blocked(target)
        val complete = answers.filterNotNull()
        val source = complete
            .groupBy { canonicalLanguage(it.sourceLanguage) }
            .maxByOrNull { (_, values) -> values.sumOf { it.sourceWeight } }
            ?.key
            .orEmpty()
        if (source.isBlank()) return LyricsTranslationState.Unavailable(target)

        var translated = complete.flatMap { it.translations }
        if (translated.size != slots.size) return LyricsTranslationState.Unavailable(target)

        // Google's ordinary auto-detection understands many Latin-script
        // Hindi/Urdu/Punjabi lyrics, but its NMT occasionally returns whole
        // phrases unchanged ("tera hone laga hoon" is a common example). If a
        // sizeable part of a Latin-script source survived untouched, use
        // Google's Input Tools to restore the detected language's native script
        // and translate that. Keep the first answer unless the retry actually
        // transforms more of the song, so names and genuinely bilingual lyrics
        // do not get worse merely because they contain Latin text.
        if (
            !sameLanguage(source, target) &&
            predominantlyLatin(slots) &&
            unchangedWeight(slots, translated) * 3 >= slots.sumOf { it.text.length }
        ) {
            val retried = retryRomanizedTranslation(batches, source, target)
            if (
                retried != null &&
                retried.size == slots.size &&
                unchangedWeight(slots, retried) < unchangedWeight(slots, translated)
            ) {
                translated = retried
            }
        }

        val entry = CachedTranslation(
            sourceLanguage = source,
            targetLanguage = target,
            texts = translated,
        )
        memory.put(key, entry)
        writeCache(context, key, entry)

        return if (sameLanguage(source, target)) {
            LyricsTranslationState.AlreadyInTargetLanguage(target)
        } else {
            LyricsTranslationState.Ready(target, source, rebuild(lines, slots, translated))
        }
    }

    private fun flatten(lines: List<LyricLine>): List<TextSlot> = buildList {
        lines.forEachIndexed { index, line ->
            if (line.isGap) return@forEachIndexed
            if (line.text.isNotBlank()) {
                val header = Genius.isSectionHeader(line.text)
                add(
                    TextSlot(
                        lineIndex = index,
                        background = false,
                        text = if (header) {
                            line.text.removePrefix("[").removeSuffix("]").trim()
                        } else {
                            line.text
                        },
                        sectionHeader = header,
                    ),
                )
            }
            line.background?.takeIf { it.text.isNotBlank() }?.let { background ->
                add(TextSlot(index, background = true, background.text, sectionHeader = false))
            }
        }
    }

    private fun rebuild(
        original: List<LyricLine>,
        slots: List<TextSlot>,
        translated: List<String>,
    ): List<LyricLine> {
        val byLine = slots.zip(translated).groupBy { it.first.lineIndex }
        return original.mapIndexed { index, line ->
            if (line.isGap) return@mapIndexed line
            val entries = byLine[index].orEmpty()
            val lead = entries.firstOrNull { !it.first.background }
            val backing = entries.firstOrNull { it.first.background }
            val leadText = lead?.let { (slot, text) ->
                if (slot.sectionHeader) wrapSection(line.text, text) else text
            } ?: line.text
            val leadBackground = line.background?.let { source ->
                val text = backing?.second ?: source.text
                source.retimedForTranslation(text)
            }
            line.retimedForTranslation(leadText, leadBackground)
        }
    }

    private fun batches(slots: List<TextSlot>): List<Batch> {
        val result = mutableListOf<Batch>()
        var current = mutableListOf<TextSlot>()
        var length = 0

        fun flush() {
            if (current.isEmpty()) return
            result += Batch(current.toList(), payload(current))
            current = mutableListOf()
            length = 0
        }

        slots.forEach { slot ->
            val added = slot.text.length + if (current.isEmpty()) 0 else 8
            if (current.isNotEmpty() && length + added > MAX_BATCH_CHARS) flush()
            current += slot
            length += added
        }
        flush()
        return result
    }

    private fun payload(slots: List<TextSlot>): String = buildString {
        slots.forEachIndexed { index, slot ->
            if (index > 0) append('\n').append(marker(index)).append('\n')
            append(slot.text)
        }
    }

    /**
     * A private-use delimiter carried through translation.
     *
     * Splitting the answer on a word the endpoint could translate is not
     * possible, and a plain newline is not safe either — the translation may
     * return one line for a two-line input. These code points are in the
     * private use area, so they pass through the endpoint untouched and come
     * back still marking the boundaries.
     */
    private fun marker(index: Int): String = "${index.toString().padStart(4, '0')}"

    private suspend fun requestBatch(
        batch: Batch,
        target: String,
        sourceLanguage: String = "auto",
    ): BatchAnswer? {
        val body = FormBody.Builder()
            .add("client", "dict-chrome-ex")
            .add("sl", sourceLanguage)
            .add("tl", target)
            .add("dt", "t")
            .add("q", batch.payload)
            .build()
        val request = Request.Builder()
            .url(ENDPOINT)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .post(body)
            .build()
        val response = try {
            client.newCall(request).awaitBody()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            return null
        }
        return runCatching {
            val root = json.parseToJsonElement(response).jsonArray
            val translatedBody = root[0].jsonArray.joinToString(separator = "") { segment ->
                segment.jsonArray.getOrNull(0)?.jsonPrimitive?.contentOrNull.orEmpty()
            }
            val source = root.getOrNull(2)?.jsonPrimitive?.contentOrNull.orEmpty()
            val parts = translatedBody.split(markerRegex).map(String::trim)
            if (parts.size != batch.slots.size || parts.any { it.isBlank() }) return@runCatching null
            BatchAnswer(parts, source, batch.payload.length)
        }.getOrNull()
    }

    private suspend fun retryRomanizedTranslation(
        batches: List<Batch>,
        source: String,
        target: String,
    ): List<String>? {
        val answers = coroutineScope {
            batches.chunked(MAX_PARALLEL_REQUESTS).flatMap { group ->
                group.map { batch ->
                    async {
                        val nativePayload = requestNativeScript(batch.payload, source)
                            ?: return@async null
                        requestBatch(batch.copy(payload = nativePayload), target, source)
                    }
                }.awaitAll()
            }
        }
        if (answers.any { it == null }) return null
        return answers.filterNotNull().flatMap { it.translations }
    }

    private suspend fun requestNativeScript(text: String, sourceLanguage: String): String? {
        val body = FormBody.Builder()
            .add("text", text)
            .add("itc", "$sourceLanguage-t-i0-und")
            .add("num", "1")
            .add("cp", "0")
            .add("cs", "1")
            .add("ie", "utf-8")
            .add("oe", "utf-8")
            .build()
        val request = Request.Builder()
            .url(INPUT_TOOLS_ENDPOINT)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .post(body)
            .build()
        val response = try {
            client.newCall(request).awaitBody()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            return null
        }
        return runCatching {
            val root = json.parseToJsonElement(response).jsonArray
            if (root.getOrNull(0)?.jsonPrimitive?.contentOrNull != "SUCCESS") return@runCatching null
            root[1].jsonArray[0].jsonArray[1].jsonArray[0].jsonPrimitive.contentOrNull
                ?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    private suspend fun Call.awaitBody(): String = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                if (continuation.isActive) continuation.resumeWithException(error)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val body = if (it.isSuccessful) it.body?.string() else null
                    if (!continuation.isActive) return
                    if (body != null) continuation.resume(body)
                    else continuation.resumeWithException(IOException("Translation HTTP ${it.code}"))
                }
            }
        })
    }

    private fun predominantlyLatin(slots: List<TextSlot>): Boolean {
        var latin = 0
        var other = 0
        slots.forEach { slot ->
            slot.text.codePoints().forEach { codePoint ->
                if (Character.isLetter(codePoint)) {
                    if (Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.LATIN) latin++
                    else other++
                }
            }
        }
        return latin > 0 && latin >= other * 4
    }

    private fun unchangedWeight(slots: List<TextSlot>, transformed: List<String>): Int =
        slots.zip(transformed).sumOf { (slot, text) ->
            if (comparable(slot.text) == comparable(text)) slot.text.length else 0
        }

    private fun comparable(text: String): String = text
        .trim()
        // ROOT, not the default locale, so a name is compared the same way on a
        // Turkish phone as on an English one.
        .lowercase(Locale.ROOT)
        .replace(Regex("\\s+"), " ")

    private fun canonicalLanguage(tag: String): String =
        Locale.forLanguageTag(tag.replace('_', '-')).language.lowercase(Locale.ROOT)

    private fun sameLanguage(first: String, second: String): Boolean =
        canonicalLanguage(first) == canonicalLanguage(second)

    private fun cacheKey(target: String, slots: List<TextSlot>): String {
        val source = buildString {
            append(target)
            slots.forEach { append(' ').append(it.text) }
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(source.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private suspend fun readCache(
        context: android.content.Context,
        key: String,
    ): CachedTranslation? = withContext(Dispatchers.IO) {
        diskMutex.withLock {
            val file = File(File(context.cacheDir, CACHE_DIRECTORY), "$key.json.gz")
            if (!file.isFile) return@withLock null
            runCatching {
                val value = GZIPInputStream(FileInputStream(file)).bufferedReader().use {
                    json.decodeFromString<CachedTranslation>(it.readText())
                }
                file.setLastModified(System.currentTimeMillis())
                value.takeIf { it.version == CACHE_VERSION }
            }.getOrNull()
        }
    }

    private suspend fun writeCache(
        context: android.content.Context,
        key: String,
        value: CachedTranslation,
    ) = withContext(Dispatchers.IO) {
        diskMutex.withLock {
            val directory = File(context.cacheDir, CACHE_DIRECTORY)
            if (!directory.exists() && !directory.mkdirs()) return@withLock
            val destination = File(directory, "$key.json.gz")
            val temporary = File(directory, "$key.tmp")
            runCatching {
                GZIPOutputStream(FileOutputStream(temporary)).bufferedWriter().use {
                    it.write(json.encodeToString(value))
                }
                // Renaming within a directory is atomic, so a reader never sees
                // a half-written file. The copy is the fallback for the filesystems
                // where it is not.
                if (!temporary.renameTo(destination)) {
                    temporary.copyTo(destination, overwrite = true)
                    temporary.delete()
                }
                trimCache(directory)
            }.onFailure {
                temporary.delete()
                Log.w(TAG, "Could not cache a translation", it)
            }
        }
    }

    private fun trimCache(directory: File) {
        val files = directory.listFiles { file -> file.extension == "gz" }
            ?.sortedByDescending(File::lastModified)
            .orEmpty()
        var kept = 0L
        files.forEach { file ->
            kept += file.length()
            if (kept > MAX_CACHE_BYTES) file.delete()
        }
    }

    /**
     * Every language the endpoint can translate into, for the settings picker.
     *
     * Taken from the endpoint's own list rather than from an installed model
     * set, so the picker cannot offer a language the request would refuse.
     */
    val supportedTargetLanguages: List<String>
        get() = TRANSLATION_LANGUAGES.map { it.code }
}

private const val USER_AGENT = "YZMusic/1.6.5 (Android)"

/**
 * The words of a section title, with the brackets that mark it as one taken
 * off.
 *
 * The unsynced layout draws a line as a section title only while it still has
 * that shape — `Genius.isSectionHeader` and the strip at the draw site both
 * key on the literal brackets. Handing the brackets to the endpoint and taking
 * whatever comes back risks it dropping or re-paginating them, and a demoted
 * section title is an ordinary lyric line with no way back. So the endpoint sees
 * the words alone and [wrapSection] puts the brackets back on its answer.
 */
internal fun sectionWords(text: String): String =
    if (Genius.isSectionHeader(text)) {
        text.removePrefix("[").removeSuffix("]").trim()
    } else {
        text
    }

/** The inverse of [sectionWords]: the brackets return iff the source had them. */
internal fun wrapSection(original: String, translated: String): String =
    if (Genius.isSectionHeader(original)) "[$translated]" else translated

/**
 * Puts translated words back on the source line's timing curve.
 */
internal fun LyricLine.retimedForTranslation(
    translatedText: String,
    translatedBackground: LyricLine? = background,
): LyricLine {
    val clean = translatedText.trim()
    if (clean.isEmpty()) return copy(background = translatedBackground)
    val translatedWords = if (words.isEmpty()) {
        emptyList()
    } else {
        translationTokenRanges(clean).map { range ->
            val denominator = clean.length.coerceAtLeast(1).toFloat()
            val startFraction = range.first / denominator
            val endFraction = (range.last + 1) / denominator
            LyricWord(
                startMs = timeAtTextFraction(startFraction),
                endMs = timeAtTextFraction(endFraction),
                text = clean.substring(range),
            )
        }
    }
    return copy(
        text = clean,
        words = translatedWords,
        background = translatedBackground,
    )
}

private fun translationTokenRanges(text: String): List<IntRange> {
    val words = Regex("\\S+").findAll(text).map { it.range }.toList()
    if (words.size != 1 || text.any(Char::isWhitespace)) return words

    val breaker = BreakIterator.getCharacterInstance(Locale.ROOT).apply { setText(text) }
    val graphemes = mutableListOf<IntRange>()
    var start = breaker.first()
    var end = breaker.next()
    while (end != BreakIterator.DONE) {
        if (text.substring(start, end).isNotBlank()) graphemes += start until end
        start = end
        end = breaker.next()
    }
    return graphemes.ifEmpty { words }
}

private fun LyricLine.timeAtTextFraction(fraction: Float): Long {
    val clamped = fraction.coerceIn(0f, 1f)
    val first = words.first()
    val last = words.last()
    if (clamped <= 0f) return first.startMs
    if (clamped >= 1f) return last.endMs

    val target = clamped * text.length.coerceAtLeast(1)
    var cursor = 0
    var previousChar = 0
    var previousTime = first.startMs
    words.forEach { word ->
        val startChar = text.indexOf(word.text, cursor).takeIf { it >= 0 } ?: cursor
        val endChar = (startChar + word.text.length).coerceAtMost(text.length)
        if (target <= startChar) {
            return interpolateTime(previousChar, startChar, previousTime, word.startMs, target)
        }
        if (target <= endChar) {
            return interpolateTime(startChar, endChar, word.startMs, word.endMs, target)
        }
        cursor = endChar
        previousChar = endChar
        previousTime = word.endMs
    }
    return interpolateTime(previousChar, text.length, previousTime, last.endMs, target)
}

private fun interpolateTime(
    startChar: Int,
    endChar: Int,
    startMs: Long,
    endMs: Long,
    targetChar: Float,
): Long {
    if (endChar <= startChar || endMs <= startMs) return startMs
    val through = ((targetChar - startChar) / (endChar - startChar)).coerceIn(0f, 1f)
    return (startMs + (endMs - startMs) * through).toLong()
}
