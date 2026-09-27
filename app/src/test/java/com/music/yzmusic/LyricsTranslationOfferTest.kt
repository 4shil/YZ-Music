package com.music.yzmusic

import com.music.yzmusic.data.lyrics.LyricLine
import com.music.yzmusic.data.lyrics.LyricsTranslationState
import com.music.yzmusic.data.lyrics.LyricsTranslationStage
import com.music.yzmusic.ui.MainViewModel.LyricsDisplayMode
import com.music.yzmusic.ui.offersTranslationDisc
import org.junit.Assert.assertFalse
import com.music.yzmusic.ui.shouldStartTranslation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whether the reader is offered a translate disc.
 *
 * The rule that matters here is that a *blocked* download keeps its button. A
 * download can be blocked for reasons the reader can fix — mobile data with the
 * WiFi-only rule on, a flaky network — and taking the disc away in response
 * leaves a refusal with no way to act on it, which is indistinguishable from
 * being stuck.
 */
class LyricsTranslationOfferTest {
    private val line = LyricLine(timeMs = 0L, text = "夜に駆ける")

    private fun offers(
        modes: Set<LyricsDisplayMode> = setOf(LyricsDisplayMode.ORIGINAL),
        canTranslate: Boolean = true,
        state: LyricsTranslationState = LyricsTranslationState.Idle,
    ) = offersTranslationDisc(modes, canTranslate, state)

    @Test
    fun `idle offers the disc because the tap is what starts the work`() {
        assertTrue(offers())
    }

    @Test
    fun `blocked keeps the disc so a retry is possible`() {
        assertTrue(offers(state = LyricsTranslationState.Blocked("en")))
    }

    @Test
    fun `blocked offers the disc for any target language`() {
        // The language is incidental to the refusal, so none of them may be
        // special-cased into withholding the retry.
        listOf("en", "hi", "es", "ar").forEach { tag ->
            assertTrue(tag, offers(state = LyricsTranslationState.Blocked(tag)))
        }
    }

    @Test
    fun `unavailable withholds the disc because retrying cannot help`() {
        assertFalse(offers(state = LyricsTranslationState.Unavailable("en")))
    }

    @Test
    fun `a track the engine would refuse gets no disc even while idle`() {
        assertFalse(offers(canTranslate = false))
    }

    @Test
    fun `a source that already shipped a translation needs no engine`() {
        // Available from the host: the disc is a switch onto existing text, and
        // it stays a switch even if the engine is meanwhile refusing, because
        // the words are already on the device.
        val modes = setOf(LyricsDisplayMode.ORIGINAL, LyricsDisplayMode.TRANSLATED)
        assertTrue(offers(modes = modes, state = LyricsTranslationState.Unavailable("en")))
        assertTrue(offers(modes = modes, canTranslate = false, state = LyricsTranslationState.Blocked("en")))
    }

    @Test
    fun `already in the reader's language keeps the disc`() {
        // Nothing to switch on, but the button stays and simply goes quiet.
        // Disappearing reads the same as "this song cannot be translated", and
        // the reader has no way to tell those apart from a single frame.
        assertTrue(offers(state = LyricsTranslationState.AlreadyInTargetLanguage("en")))
    }

    @Test
    fun `a download in flight keeps the disc`() {
        // The whole point of the rule: pressing the button must not take the
        // button away, or the reader is left at a spinner with no control left
        // to cancel it or to retry if it stalls.
        listOf(LyricsTranslationStage.DOWNLOADING_MODEL, LyricsTranslationStage.TRANSLATING)
            .forEach { stage ->
                assertTrue(stage.name, offers(state = LyricsTranslationState.Loading("en", stage)))
            }
    }

    @Test
    fun `romanized alone does not conjure a translate disc`() {
        val modes = setOf(LyricsDisplayMode.ORIGINAL, LyricsDisplayMode.ROMANIZED)
        assertFalse(offers(modes = modes, canTranslate = false, state = LyricsTranslationState.Idle))
    }

    @Test
    fun `a ready translation is a switch not a start`() {
        val modes = setOf(LyricsDisplayMode.ORIGINAL, LyricsDisplayMode.TRANSLATED)
        assertTrue(
            offers(
                modes = modes,
                state = LyricsTranslationState.Ready(
                    targetLanguageTag = "en",
                    sourceLanguageTag = "ja",
                    lines = listOf(line),
                ),
            ),
        )
    }
}

/**
 * Whether a tap reaches the translator at all.
 *
 * The defect these cover is the one the reader sees: the disc says
 * "Downloading English…", nothing is running, and every tap is refused, so the
 * only way out is changing song. It happened because the guard asked the
 * *state* whether work was in flight, and the state keeps saying Loading long
 * after the job that was filling it has ended.
 */
class LyricsTranslationRetryTest {
    private fun job(block: suspend CoroutineScope.() -> Unit): Job =
        CoroutineScope(Dispatchers.Unconfined).launch(block = block)

    @Test
    fun `a finished job does not block a retry even though the state still says loading`() {
        // The trap itself: Loading is on screen, the job that produced it is
        // over, and the tap must still start new work.
        val dead = job { }
        assertTrue(
            shouldStartTranslation(
                LyricsTranslationState.Loading("en", LyricsTranslationStage.DOWNLOADING_MODEL),
                dead,
                "en",
            ),
        )
    }

    @Test
    fun `a running job for the same language is not restarted`() {
        val gate = CompletableDeferred<Unit>()
        val running = job { gate.await() }
        assertFalse(
            shouldStartTranslation(
                LyricsTranslationState.Loading("en", LyricsTranslationStage.DOWNLOADING_MODEL),
                running,
                "en",
            ),
        )
        gate.complete(Unit)
    }

    @Test
    fun `a running job for another language does not block this one`() {
        val gate = CompletableDeferred<Unit>()
        val running = job { gate.await() }
        assertTrue(
            shouldStartTranslation(
                LyricsTranslationState.Loading("es", LyricsTranslationStage.DOWNLOADING_MODEL),
                running,
                "en",
            ),
        )
        gate.complete(Unit)
    }

    @Test
    fun `a ready translation for the target is a switch not a start`() {
        assertFalse(
            shouldStartTranslation(
                LyricsTranslationState.Ready("en", "ja", listOf(LyricLine(timeMs = 0L, text = "x"))),
                job { },
                "en",
            )
        )
    }

    @Test
    fun `blocked starts again because that is the retry the disc offers`() {
        assertTrue(shouldStartTranslation(LyricsTranslationState.Blocked("en"), job { }, "en"))
    }

    @Test
    fun `no job at all means nothing to wait for`() {
        assertTrue(
            shouldStartTranslation(
                LyricsTranslationState.Loading("en", LyricsTranslationStage.TRANSLATING),
                null,
                "en",
            ),
        )
    }
}
