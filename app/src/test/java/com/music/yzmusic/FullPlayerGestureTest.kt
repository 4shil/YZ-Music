package com.music.yzmusic

import com.music.yzmusic.data.settings.AppSettings
import com.music.yzmusic.ui.player.FullPlayerGestureAction
import com.music.yzmusic.ui.player.FullPlayerGestureTracker
import com.music.yzmusic.ui.player.FullPlayerSeekCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FullPlayerGestureTest {

    // =========================================================================
    // PART 1: SEEK CALCULATOR & BOUNDARY TESTS
    // =========================================================================

    @Test
    fun `seek backward decreases position by exact configured seconds`() {
        // 5 seconds backward
        val target5 = FullPlayerSeekCalculator.calculateTarget(
            isForward = false,
            currentPosMs = 30000L,
            durationMs = 180000L,
            seekAmountSeconds = 5,
        )
        assertEquals(25000L, target5)

        // 10 seconds backward
        val target10 = FullPlayerSeekCalculator.calculateTarget(
            isForward = false,
            currentPosMs = 30000L,
            durationMs = 180000L,
            seekAmountSeconds = 10,
        )
        assertEquals(20000L, target10)

        // 25 seconds backward
        val target25 = FullPlayerSeekCalculator.calculateTarget(
            isForward = false,
            currentPosMs = 30000L,
            durationMs = 180000L,
            seekAmountSeconds = 25,
        )
        assertEquals(5000L, target25)
    }

    @Test
    fun `seek backward clamps cleanly to 0ms when near beginning`() {
        val target = FullPlayerSeekCalculator.calculateTarget(
            isForward = false,
            currentPosMs = 3000L,
            durationMs = 180000L,
            seekAmountSeconds = 10,
        )
        assertEquals(0L, target)
    }

    @Test
    fun `seek forward increases position by exact configured seconds`() {
        // 5 seconds forward
        val target5 = FullPlayerSeekCalculator.calculateTarget(
            isForward = true,
            currentPosMs = 30000L,
            durationMs = 180000L,
            seekAmountSeconds = 5,
        )
        assertEquals(35000L, target5)

        // 10 seconds forward
        val target10 = FullPlayerSeekCalculator.calculateTarget(
            isForward = true,
            currentPosMs = 30000L,
            durationMs = 180000L,
            seekAmountSeconds = 10,
        )
        assertEquals(40000L, target10)

        // 25 seconds forward
        val target25 = FullPlayerSeekCalculator.calculateTarget(
            isForward = true,
            currentPosMs = 30000L,
            durationMs = 180000L,
            seekAmountSeconds = 25,
        )
        assertEquals(55000L, target25)
    }

    @Test
    fun `seek forward clamps to duration minus guard when near end`() {
        val durationMs = 100000L
        val guardMs = 1500L
        val target = FullPlayerSeekCalculator.calculateTarget(
            isForward = true,
            currentPosMs = 95000L,
            durationMs = durationMs,
            seekAmountSeconds = 10,
            guardMs = guardMs,
        )
        assertEquals(durationMs - guardMs, target)
    }

    @Test
    fun `seek forward and backward handle unknown or zero duration without crashing`() {
        // Unknown duration (durationMs <= 0L)
        val targetFwd = FullPlayerSeekCalculator.calculateTarget(
            isForward = true,
            currentPosMs = 15000L,
            durationMs = -1L,
            seekAmountSeconds = 10,
        )
        assertEquals(25000L, targetFwd)

        val targetBwd = FullPlayerSeekCalculator.calculateTarget(
            isForward = false,
            currentPosMs = 5000L,
            durationMs = 0L,
            seekAmountSeconds = 10,
        )
        assertEquals(0L, targetBwd)
    }

    // =========================================================================
    // PART 2: DOUBLE-TAP DETECTION & LOCATION TESTS
    // =========================================================================

    @Test
    fun `two quick taps on left half trigger DOUBLE_TAP_SEEK_BACKWARD`() {
        val tracker = FullPlayerGestureTracker(thresholdPx = 100f)
        val containerWidth = 400f
        val leftX = 100f // left half < 200f

        // Tap 1
        val action1 = tracker.onTap(x = leftX, y = 300f, containerWidth = containerWidth, currentTimeMs = 1000L)
        assertEquals(FullPlayerGestureAction.NONE, action1)

        // Tap 2 within 350ms on left half
        val action2 = tracker.onTap(x = leftX + 5f, y = 305f, containerWidth = containerWidth, currentTimeMs = 1200L)
        assertEquals(FullPlayerGestureAction.DOUBLE_TAP_SEEK_BACKWARD, action2)
    }

    @Test
    fun `two quick taps on right half trigger DOUBLE_TAP_SEEK_FORWARD`() {
        val tracker = FullPlayerGestureTracker(thresholdPx = 100f)
        val containerWidth = 400f
        val rightX = 300f // right half >= 200f

        // Tap 1
        val action1 = tracker.onTap(x = rightX, y = 300f, containerWidth = containerWidth, currentTimeMs = 1000L)
        assertEquals(FullPlayerGestureAction.NONE, action1)

        // Tap 2 within 350ms on right half
        val action2 = tracker.onTap(x = rightX + 2f, y = 298f, containerWidth = containerWidth, currentTimeMs = 1250L)
        assertEquals(FullPlayerGestureAction.DOUBLE_TAP_SEEK_FORWARD, action2)
    }

    @Test
    fun `taps on opposite halves do not trigger seek`() {
        val tracker = FullPlayerGestureTracker(thresholdPx = 100f)
        val containerWidth = 400f

        // Tap 1 on left
        tracker.onTap(x = 80f, y = 300f, containerWidth = containerWidth, currentTimeMs = 1000L)

        // Tap 2 on right within timeout
        val action2 = tracker.onTap(x = 320f, y = 300f, containerWidth = containerWidth, currentTimeMs = 1200L)
        assertEquals(FullPlayerGestureAction.NONE, action2)
    }

    @Test
    fun `taps separated by more than 350ms do not trigger seek`() {
        val tracker = FullPlayerGestureTracker(thresholdPx = 100f)
        val containerWidth = 400f
        val leftX = 100f

        // Tap 1
        tracker.onTap(x = leftX, y = 300f, containerWidth = containerWidth, currentTimeMs = 1000L)

        // Tap 2 after 400ms (> 350ms)
        val action2 = tracker.onTap(x = leftX, y = 300f, containerWidth = containerWidth, currentTimeMs = 1450L)
        assertEquals(FullPlayerGestureAction.NONE, action2)
    }

    @Test
    fun `subsequent double tap after successful double tap accumulates cleanly`() {
        val tracker = FullPlayerGestureTracker(thresholdPx = 100f)
        val containerWidth = 400f
        val rightX = 300f

        // Cycle 1: double tap right
        tracker.onTap(x = rightX, y = 300f, containerWidth = containerWidth, currentTimeMs = 1000L)
        val firstResult = tracker.onTap(x = rightX, y = 300f, containerWidth = containerWidth, currentTimeMs = 1200L)
        assertEquals(FullPlayerGestureAction.DOUBLE_TAP_SEEK_FORWARD, firstResult)

        // Cycle 2: another double tap right
        val thirdTap = tracker.onTap(x = rightX, y = 300f, containerWidth = containerWidth, currentTimeMs = 1300L)
        assertEquals(FullPlayerGestureAction.NONE, thirdTap) // first tap of new cycle
        val fourthTap = tracker.onTap(x = rightX, y = 300f, containerWidth = containerWidth, currentTimeMs = 1450L)
        assertEquals(FullPlayerGestureAction.DOUBLE_TAP_SEEK_FORWARD, fourthTap)
    }

    // =========================================================================
    // PART 3: HORIZONTAL SWIPE SONG NAVIGATION TESTS
    // =========================================================================

    @Test
    fun `horizontal swipe left past threshold triggers SWIPE_LEFT_NEXT`() {
        val tracker = FullPlayerGestureTracker(thresholdPx = 48f)

        // Drag left: totalDx = -55f, totalDy = 5f (horizontal dominant)
        val action = tracker.onPosition(totalX = -55f, totalY = 5f)
        assertEquals(FullPlayerGestureAction.SWIPE_LEFT_NEXT, action)
        assertTrue(tracker.gestureHandled)
        assertTrue(tracker.isLockedHorizontal)
    }

    @Test
    fun `horizontal swipe right past threshold triggers SWIPE_RIGHT_PREVIOUS`() {
        val tracker = FullPlayerGestureTracker(thresholdPx = 48f)

        // Drag right: totalDx = 52f, totalDy = -3f (horizontal dominant)
        val action = tracker.onPosition(totalX = 52f, totalY = -3f)
        assertEquals(FullPlayerGestureAction.SWIPE_RIGHT_PREVIOUS, action)
        assertTrue(tracker.gestureHandled)
        assertTrue(tracker.isLockedHorizontal)
    }

    @Test
    fun `horizontal quick flick on release triggers song change`() {
        val tracker = FullPlayerGestureTracker(thresholdPx = 100f, flickVelocityPx = 400f, minFlickDistancePx = 20f)

        // Movement below distance threshold but enough for flick: totalDx = -25f
        tracker.onPosition(totalX = -25f, totalY = 2f)
        assertFalse(tracker.gestureHandled)

        // Release with leftward flick velocity
        val releaseAction = tracker.onRelease(velocityX = -500f, velocityY = 10f)
        assertEquals(FullPlayerGestureAction.SWIPE_LEFT_NEXT, releaseAction)
    }

    // =========================================================================
    // PART 4: VERTICAL SWIPE UP QUEUE & DISAMBIGUATION TESTS
    // =========================================================================

    @Test
    fun `vertical upward swipe triggers SWIPE_UP_QUEUE and does not trigger track change`() {
        val tracker = FullPlayerGestureTracker(thresholdPx = 48f)

        // Drag up: totalDx = 4f, totalDy = -55f (vertical dominant upward)
        val action = tracker.onPosition(totalX = 4f, totalY = -55f)
        assertEquals(FullPlayerGestureAction.SWIPE_UP_QUEUE, action)
        assertTrue(tracker.gestureHandled)
        assertTrue(tracker.isLockedVertical)
    }

    @Test
    fun `vertical upward flick triggers SWIPE_UP_QUEUE`() {
        val tracker = FullPlayerGestureTracker(thresholdPx = 100f, flickVelocityPx = 400f, minFlickDistancePx = 20f)

        tracker.onPosition(totalX = 2f, totalY = -25f)
        assertFalse(tracker.gestureHandled)

        val releaseAction = tracker.onRelease(velocityX = 50f, velocityY = -550f)
        assertEquals(FullPlayerGestureAction.SWIPE_UP_QUEUE, releaseAction)
    }

    @Test
    fun `horizontal dominant gesture locks out vertical swipe up`() {
        val tracker = FullPlayerGestureTracker(thresholdPx = 48f, touchSlopPx = 20f)

        // Move horizontally past touch slop: dx = 25f, dy = 5f
        tracker.onPosition(totalX = 25f, totalY = 5f)
        assertTrue(tracker.isLockedHorizontal)
        assertFalse(tracker.isLockedVertical)

        // Now move up: dy = -60f, but horizontal lock is active
        val action = tracker.onPosition(totalX = 25f, totalY = -60f)
        // Should not trigger SWIPE_UP_QUEUE when locked horizontal
        assertEquals(FullPlayerGestureAction.NONE, action)
    }

    @Test
    fun `vertical downward swipe does not trigger Queue and does not trigger track change`() {
        val tracker = FullPlayerGestureTracker(thresholdPx = 48f)

        // Drag downward: totalDx = 5f, totalDy = 60f
        val action = tracker.onPosition(totalX = 5f, totalY = 60f)
        assertEquals(FullPlayerGestureAction.NONE, action)
        assertFalse(tracker.gestureHandled)

        val releaseAction = tracker.onRelease(velocityX = 0f, velocityY = 600f)
        assertEquals(FullPlayerGestureAction.NONE, releaseAction)
    }

    @Test
    fun `invalidating tap after child consumes touch prevents accidental seek`() {
        val tracker = FullPlayerGestureTracker(thresholdPx = 100f)
        val containerWidth = 400f

        // User taps once
        tracker.onTap(x = 100f, y = 300f, containerWidth = containerWidth, currentTimeMs = 1000L)

        // Child control consumes next touch -> invalidate tap
        tracker.invalidateTap()

        // Subsequent tap should not complete a double-tap
        val action = tracker.onTap(x = 100f, y = 300f, containerWidth = containerWidth, currentTimeMs = 1150L)
        assertEquals(FullPlayerGestureAction.NONE, action)
    }

    // =========================================================================
    // PART 5: CONFIGURABLE SEEK DURATION SETTING TESTS
    // =========================================================================

    @Test
    fun `setSeekDurationSeconds accepts 5, 10, and 25 and defaults invalid values to 10`() {
        AppSettings.setSeekDurationSeconds(5)
        assertEquals(5, AppSettings.seekDurationSeconds.value)

        AppSettings.setSeekDurationSeconds(25)
        assertEquals(25, AppSettings.seekDurationSeconds.value)

        AppSettings.setSeekDurationSeconds(10)
        assertEquals(10, AppSettings.seekDurationSeconds.value)

        // Obsolete or invalid values sanitize to 10
        AppSettings.setSeekDurationSeconds(15)
        assertEquals(10, AppSettings.seekDurationSeconds.value)

        AppSettings.setSeekDurationSeconds(0)
        assertEquals(10, AppSettings.seekDurationSeconds.value)

        AppSettings.setSeekDurationSeconds(99)
        assertEquals(10, AppSettings.seekDurationSeconds.value)
    }
}
