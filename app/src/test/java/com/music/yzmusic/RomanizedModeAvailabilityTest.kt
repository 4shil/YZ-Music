package com.music.yzmusic

import com.music.yzmusic.data.lyrics.LyricLine
import com.music.yzmusic.ui.MainViewModel.RomanizationState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whether the reader can reach the Romanized mode at all.
 *
 * This rule is the feature. Before it, ROMANIZED appeared only when a
 * publisher's TTML already carried an `x-roman` layer, so the mode existed for
 * a handful of tracks and was absent everywhere else — and the engine had no
 * producer to fill the gap. Getting this predicate wrong in the cautious
 * direction silently reinstates that: the mode simply never appears, and
 * nothing reports an error.
 */
class RomanizedModeAvailabilityTest {

    private val line = LyricLine(timeMs = 0, text = "konnichiwa")

    @Test
    fun `the mode is offered before any attempt has been made`() {
        // The whole point. Idle is the state a Japanese track spends most of
        // its life in, and the mode has to be reachable from there.
        assertTrue(RomanizationState.Idle.offersRomanized)
    }

    @Test
    fun `the mode stays offered while a request is in flight`() {
        // Withdrawing the pill mid-request would leave the reader with no
        // control and no answer — the request is still running, and the tap
        // they made has not been undone.
        assertTrue(RomanizationState.Generating("abc").offersRomanized)
    }

    @Test
    fun `the mode stays offered once there is a result`() {
        assertTrue(
            RomanizationState.Ready(listOf(line), sourceScript = "japanese", fromCache = false)
                .offersRomanized,
        )
    }

    @Test
    fun `the mode is withdrawn when the words need no romanization`() {
        // A successful answer, not a failure: the original already is the
        // romanized text. Re-offering it would spend a tap to arrive back
        // where the reader started.
        assertFalse(RomanizationState.AlreadyRomanized.offersRomanized)
    }

    @Test
    fun `the mode is withdrawn when the engine could not render the script`() {
        // Asking again would reach the same answer, so the control would be
        // a way to spend a tap being told no a second time.
        assertFalse(RomanizationState.Unavailable("unsupported").offersRomanized)
    }

    @Test
    fun `a cached result is still a result`() {
        // fromCache only describes where the answer came from. Treating a cache
        // hit as a reason to hide the mode would make the second play of a
        // song behave differently from the first.
        assertTrue(
            RomanizationState.Ready(listOf(line), sourceScript = "japanese", fromCache = true)
                .offersRomanized,
        )
    }
}
