package com.music.yzmusic.data.lyrics

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.music.yzmusic.data.DebugLog as Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import java.security.MessageDigest
import java.text.BreakIterator
import java.util.Locale

/** The visible phase of an on-demand lyric translation. */
enum class LyricsTranslationStage {
    IDENTIFYING,
    DOWNLOADING_MODEL,
    TRANSLATING,
}

/** State exposed to the player while it prepares or displays a translation. */
sealed interface LyricsTranslationState {
    data object Idle : LyricsTranslationState

    data class Loading(
        val targetLanguageTag: String,
        val stage: LyricsTranslationStage,
    ) : LyricsTranslationState

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
     * The model could not be fetched right now, but nothing is wrong with the
     * song and the same tap is worth making again later.
     *
     * This is its own state rather than a flavour of [Unavailable] because the
     * two want opposite things from the button. Unavailable means the engine
     * cannot do this translation at all, so offering the disc again would only
     * fail identically. Blocked means the network said no *this time* — the
     * commonest cause being the WiFi-only rule on a metered connection — so
     * the disc stays lit and tappable, because the reader moving to WiFi and
     * tapping again is exactly the action that resolves it.
     */
    data class Blocked(
        val targetLanguageTag: String,
    ) : LyricsTranslationState
}

private sealed interface TranslationResult {
    data class Ready(
        val targetLanguageTag: String,
        val sourceLanguageTag: String,
        val lines: List<LyricLine>,
    ) : TranslationResult

    data class AlreadyInTargetLanguage(val targetLanguageTag: String) : TranslationResult
    data class Unavailable(val targetLanguageTag: String) : TranslationResult
}

/**
 * Translates lyrics on device while leaving their musical clock untouched.
 *
 * Translation models are downloaded by ML Kit on first use. Only the current
 * source and system-language models are retained; older language models are
 * removed once a new pair is safely on the device, which places a hard bound
 * on the disk cost even after translating songs in many languages. Results are
 * kept in a small process cache so reopening the player does not repeat a
 * whole song's inference. No lyric text is sent to an application server.
 *
 * The reader's data rule is honoured here, as a decision, rather than handed
 * to ML Kit as a condition. [networkAllowsDownload] is the call the caller has
 * already made, and a download that cannot run is refused in a millisecond
 * instead of waited on indefinitely.
 */
object LyricsTranslation {
    private const val LANGUAGE_SAMPLE_CHARS = 4_000
    private const val CACHE_ENTRIES = 6
    private const val MAX_BATCH_CHARS = 3_200
    private const val BATCH_SEPARATOR = "\n___YZMUSIC_TRANS_DELIM___\n"
    private const val TAG = "LyricsTranslation"

    /**
     * How long a model fetch may stall before it is called a failure. A model
     * is a few megabytes, so anything past this is a dead network, not a slow
     * one, and the reader deserves a retry button rather than a spinner.
     */
    private const val MODEL_DOWNLOAD_TIMEOUT_MS = 60_000L

    /**
     * The bound on the two stages that run entirely on the device.
     *
     * The download is not the only thing that can park. Language detection and
     * inference both come back as a task that never completes if the model
     * fails to load, and the player treats a running job as proof that the
     * "Downloading…" disc is still honest — so an unbounded stage here is a
     * disc that can never be tapped free. Generous, because this is a whole
     * song's inference and there is no network to blame when it overruns.
     */
    private const val LOCAL_STAGE_TIMEOUT_MS = 45_000L

    private data class CacheKey(
        val targetLanguageTag: String,
        val sourceFingerprint: String,
    )

    private val cache = object : LinkedHashMap<CacheKey, TranslationResult.Ready>(
        CACHE_ENTRIES,
        0.75f,
        true,
    ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<CacheKey, TranslationResult.Ready>?,
        ): Boolean = size > CACHE_ENTRIES
    }

    suspend fun translate(
        lines: List<LyricLine>,
        targetLanguageTag: String,
        networkAllowsDownload: Boolean = true,
        onStage: (LyricsTranslationStage) -> Unit,
    ): LyricsTranslationState {
        val target = supportedTag(targetLanguageTag)
            ?: return LyricsTranslationState.Unavailable(targetLanguageTag)
        val key = CacheKey(target, sourceFingerprint(lines))
        synchronized(cache) { cache[key] }?.let { ready ->
            return ready.toState()
        }

        val sample = lines.asSequence()
            .flatMap { sequenceOf(it.text, it.background?.text.orEmpty()) }
            .filter { it.isNotBlank() }
            .joinToString("\n")
            .take(LANGUAGE_SAMPLE_CHARS)
        if (sample.isBlank()) return LyricsTranslationState.Unavailable(target)

        onStage(LyricsTranslationStage.IDENTIFYING)
        val identifier = LanguageIdentification.getClient()
        val detected = try {
            withTimeout(LOCAL_STAGE_TIMEOUT_MS) { identifier.identifyLanguage(sample).await() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(TAG, "Language identification did not finish", error)
            return LyricsTranslationState.Unavailable(target)
        } finally {
            identifier.close()
        }
        val source = supportedTag(detected)
            ?: return LyricsTranslationState.Unavailable(target)
        if (source == target) {
            return LyricsTranslationState.AlreadyInTargetLanguage(target)
        }
        if (!networkAllowsDownload) {
            return LyricsTranslationState.Blocked(target)
        }

        val translator = Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(source)
                .setTargetLanguage(target)
                .build(),
        )
        return try {
            onStage(LyricsTranslationStage.DOWNLOADING_MODEL)
            // ML Kit's own requireWifi() is a wait, not a refusal: hand it a
            // metered connection and downloadModelIfNeeded neither completes nor
            // fails, it just parks — which is how this screen came to sit on
            // "Downloading…" with no way out. The app already knows what the
            // reader's data rule permits, so that call is made above and the
            // network is left to do the one thing it can do.
            val fetchError = try {
                withTimeout(MODEL_DOWNLOAD_TIMEOUT_MS) {
                    translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
                }
                null
            } catch (timeout: TimeoutCancellationException) {
                timeout
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                error
            }
            if (fetchError != null) {
                Log.w(TAG, "Translation model download did not finish", fetchError)
                return LyricsTranslationState.Blocked(target)
            }
            // Only now that the pair is on the device is anything worth
            // deleting. Trimming first meant a download that could not start
            // took the working models down with it on its way to failing.
            trimDownloadedModels(setOf(source, target))
            onStage(LyricsTranslationStage.TRANSLATING)
            val sourceTexts = lines.asSequence()
                .filterNot { it.isGap }
                .flatMap { line -> sequenceOf(line.text, line.background?.text) }
                .filterNotNull()
                .filter { it.isNotBlank() }
                .distinct()
                .toList()
            val translatedTexts = try {
                withTimeout(LOCAL_STAGE_TIMEOUT_MS) { translateBatched(translator, sourceTexts) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w(TAG, "Translation inference did not finish", error)
                return LyricsTranslationState.Blocked(target)
            }
            val translated = lines.map { line ->
                if (line.isGap) {
                    line
                } else {
                    val lead = translatedTexts[line.text]
                        ?.trim()
                        ?.ifBlank { line.text }
                        ?: line.text
                    val background = line.background?.let { backing ->
                        val text = translatedTexts[backing.text]
                            ?.trim()
                            ?.ifBlank { backing.text }
                            ?: backing.text
                        backing.retimedForTranslation(text)
                    }
                    line.retimedForTranslation(lead, background)
                }
            }
            TranslationResult.Ready(target, source, translated).also { ready ->
                synchronized(cache) { cache[key] = ready }
            }.toState()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            LyricsTranslationState.Unavailable(target)
        } finally {
            translator.close()
        }
    }

    private suspend fun translateBatched(
        translator: Translator,
        texts: List<String>,
    ): Map<String, String> {
        if (texts.isEmpty()) return emptyMap()
        val batches = mutableListOf<MutableList<String>>()
        var batchChars = 0
        texts.forEach { text ->
            val cost = text.length + if (batchChars == 0) 0 else BATCH_SEPARATOR.length
            if (batchChars > 0 && batchChars + cost > MAX_BATCH_CHARS) {
                batches.add(mutableListOf())
                batchChars = 0
            }
            if (batches.isEmpty()) batches.add(mutableListOf())
            batches.last().add(text)
            batchChars += text.length + if (batchChars == 0) 0 else BATCH_SEPARATOR.length
        }

        return buildMap {
            batches.forEach { batch ->
                if (batch.size == 1) {
                    put(batch.first(), translator.translate(batch.first()).await())
                    return@forEach
                }
                val translatedBlock = translator
                    .translate(batch.joinToString(BATCH_SEPARATOR))
                    .await()
                val parts = translatedBlock.split(BATCH_SEPARATOR)
                if (parts.size == batch.size) {
                    batch.zip(parts).forEach { (source, translated) -> put(source, translated) }
                } else {
                    batch.forEach { source -> put(source, translator.translate(source).await()) }
                }
            }
        }
    }

    private fun TranslationResult.Ready.toState() = LyricsTranslationState.Ready(
        targetLanguageTag = targetLanguageTag,
        sourceLanguageTag = sourceLanguageTag,
        lines = lines,
    )

    private suspend fun trimDownloadedModels(keepLanguages: Set<String>) {
        try {
            val manager = RemoteModelManager.getInstance()
            val downloaded = manager
                .getDownloadedModels(TranslateRemoteModel::class.java)
                .await()
            downloaded
                .filterNot { it.language in keepLanguages }
                .forEach { manager.deleteDownloadedModel(it).await() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Model cleanup is housekeeping
        }
    }

    private fun sourceFingerprint(lines: List<LyricLine>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        lines.forEach { line ->
            digest.update(line.toString().toByteArray(Charsets.UTF_8))
            digest.update(0.toByte())
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun supportedTag(tag: String): String? {
        val exact = TranslateLanguage.fromLanguageTag(tag)
        if (exact != null) return exact
        val base = Locale.forLanguageTag(tag).language.takeIf { it.isNotBlank() } ?: return null
        return TranslateLanguage.fromLanguageTag(base)
    }

    /**
     * Every language this translator can produce, for the settings picker.
     *
     * Asked of ML Kit rather than written out here, so the list cannot drift
     * away from what the engine would actually accept — a picker offering a
     * language the model set lacks is a promise the translator cannot keep,
     * and the reader only finds out after waiting for a download.
     */
    val supportedTargetLanguages: List<String>
        get() = TranslateLanguage.getAllLanguages().sorted()
}

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
