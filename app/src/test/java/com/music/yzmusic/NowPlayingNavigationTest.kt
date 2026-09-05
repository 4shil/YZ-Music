package com.music.yzmusic

import com.music.yzmusic.ui.player.FullPlayerSwipeUpTracker
import com.music.yzmusic.ui.player.NowPlayingMode
import com.music.yzmusic.ui.player.NowPlayingNavigationController
import com.music.yzmusic.ui.player.PanelGestureTracker
import com.music.yzmusic.ui.player.PanelSheetState
import com.music.yzmusic.ui.player.LyricsSwipeDownAction
import com.music.yzmusic.ui.player.LyricsSwipeDownTracker
import com.music.yzmusic.ui.player.QueueSwipeDownAction
import com.music.yzmusic.ui.player.QueueSwipeDownTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NowPlayingNavigationTest {

    @Test
    fun `Full Player initial state is collapsed with 0 progress`() {
        val controller = NowPlayingNavigationController()
        assertEquals(NowPlayingMode.FULL_PLAYER, controller.mode)
        assertEquals(PanelSheetState.COLLAPSED, controller.sheetState)
        assertEquals(0f, controller.targetProgress, 0.001f)
    }

    @Test
    fun `Full Player swipe-up opens QUEUE only and never Lyrics`() {
        val controller = NowPlayingNavigationController()
        controller.openQueue()

        assertEquals(NowPlayingMode.QUEUE, controller.mode)
        assertNotEquals(NowPlayingMode.LYRICS, controller.mode)
        assertEquals(PanelSheetState.EXPANDED, controller.sheetState)
        assertEquals(1f, controller.targetProgress, 0.001f)
    }

    @Test
    fun `Clicking Lyrics UI opens LYRICS mode`() {
        val controller = NowPlayingNavigationController()
        controller.openLyrics()

        assertEquals(NowPlayingMode.LYRICS, controller.mode)
        assertNotEquals(NowPlayingMode.QUEUE, controller.mode)
        assertEquals(PanelSheetState.EXPANDED, controller.sheetState)
        assertEquals(1f, controller.targetProgress, 0.001f)
    }

    @Test
    fun `Queue deliberate downward gesture at top closes Queue directly to Full Player without Peek state`() {
        val controller = NowPlayingNavigationController()
        controller.openQueue()
        assertEquals(PanelSheetState.EXPANDED, controller.sheetState)

        // Deliberate downward gesture at top -> closes Queue directly to Full Player
        val stateAfterClose = controller.onDownwardGestureAtTop()
        assertEquals(PanelSheetState.COLLAPSED, stateAfterClose)
        assertEquals(NowPlayingMode.FULL_PLAYER, controller.mode)
        assertEquals(0f, controller.targetProgress, 0.001f)
    }

    @Test
    fun `Queue downward gesture when not at top stays in Queue at EXPANDED`() {
        val controller = NowPlayingNavigationController()
        controller.openQueue()
        assertEquals(PanelSheetState.EXPANDED, controller.sheetState)

        // Downward gesture when not at top -> stays in Queue at EXPANDED (scroll to top in UI)
        val stateAfterDownward = controller.onQueueDownwardGesture(isAtTop = false)
        assertEquals(PanelSheetState.EXPANDED, stateAfterDownward)
        assertEquals(NowPlayingMode.QUEUE, controller.mode)
        assertEquals(1f, controller.targetProgress, 0.001f)

        // Subsequent gesture when at top -> closes Queue
        val stateAfterClose = controller.onQueueDownwardGesture(isAtTop = true)
        assertEquals(PanelSheetState.COLLAPSED, stateAfterClose)
        assertEquals(NowPlayingMode.FULL_PLAYER, controller.mode)
        assertEquals(0f, controller.targetProgress, 0.001f)
    }

    @Test
    fun `Lyrics deliberate downward gesture at top transitions directly to Full Player without Peek`() {
        val controller = NowPlayingNavigationController()
        controller.openLyrics()
        assertEquals(PanelSheetState.EXPANDED, controller.sheetState)

        // Deliberate downward gesture at top -> transitions directly to COLLAPSED (Full Player)
        val stateAfterStep = controller.onDownwardGestureAtTop()
        assertEquals(PanelSheetState.COLLAPSED, stateAfterStep)
        assertEquals(NowPlayingMode.FULL_PLAYER, controller.mode)
        assertEquals(0f, controller.targetProgress, 0.001f)
    }

    @Test
    fun `onLyricsDownwardGesture correctly distinguishes top from scrolled`() {
        val controller = NowPlayingNavigationController()
        controller.openLyrics()
        assertEquals(PanelSheetState.EXPANDED, controller.sheetState)

        // Gesture when scrolled down -> remains in Lyrics at EXPANDED (scroll to top)
        val stateScrolled = controller.onLyricsDownwardGesture(isAtTop = false)
        assertEquals(PanelSheetState.EXPANDED, stateScrolled)
        assertEquals(NowPlayingMode.LYRICS, controller.mode)
        assertEquals(1f, controller.targetProgress, 0.001f)

        // Subsequent gesture when at top -> closes Lyrics to Full Player
        val stateAtTop = controller.onLyricsDownwardGesture(isAtTop = true)
        assertEquals(PanelSheetState.COLLAPSED, stateAtTop)
        assertEquals(NowPlayingMode.FULL_PLAYER, controller.mode)
        assertEquals(0f, controller.targetProgress, 0.001f)
    }

    @Test
    fun `closeToFullPlayer immediately closes from any state`() {
        val controller = NowPlayingNavigationController()
        controller.openQueue()
        controller.closeToFullPlayer()

        assertEquals(PanelSheetState.COLLAPSED, controller.sheetState)
        assertEquals(NowPlayingMode.FULL_PLAYER, controller.mode)
        assertEquals(0f, controller.targetProgress, 0.001f)
    }

    @Test
    fun `PanelGestureTracker - small accidental downward movement does not trigger step down`() {
        val tracker = PanelGestureTracker(thresholdPx = 150f)

        // 30px downward drift
        assertFalse(tracker.onDownwardDelta(30f))
        assertFalse(tracker.gestureHandled)
        assertEquals(30f, tracker.accumulatedDown, 0.001f)

        // User lifts finger without deliberate threshold
        tracker.onGestureEnd()
        assertFalse(tracker.gestureHandled)
        assertEquals(0f, tracker.accumulatedDown, 0.001f)
    }

    @Test
    fun `PanelGestureTracker - zero delta does not wipe accumulated downward movement`() {
        val tracker = PanelGestureTracker(thresholdPx = 150f)

        assertFalse(tracker.onDownwardDelta(80f))
        assertEquals(80f, tracker.accumulatedDown, 0.001f)

        // Micro-pause or 0px movement frame should NOT reset accumulator
        assertFalse(tracker.onDownwardDelta(0f))
        assertEquals(80f, tracker.accumulatedDown, 0.001f)

        // Additional downward movement continues accumulating
        assertTrue(tracker.onDownwardDelta(80f)) // 160 >= 150 -> triggers
        assertTrue(tracker.gestureHandled)
    }

    @Test
    fun `PanelGestureTracker - deliberate downward drag at top closes Lyrics to Full Player`() {
        val tracker = PanelGestureTracker(thresholdPx = 150f)
        val controller = NowPlayingNavigationController()
        controller.openLyrics()

        // Drag down crossing 150px -> closes to Full Player
        assertFalse(tracker.onDownwardDelta(80f))
        assertTrue(tracker.onDownwardDelta(80f)) // Crosses 160 >= 150 -> fires once
        val stateAfterStep = controller.onDownwardGestureAtTop()
        assertEquals(PanelSheetState.COLLAPSED, stateAfterStep)
        assertEquals(NowPlayingMode.FULL_PLAYER, controller.mode)
        assertTrue(tracker.gestureHandled)

        // Continuing the SAME drag does not re-fire
        assertFalse(tracker.onDownwardDelta(100f))
        tracker.onGestureEnd()
    }

    @Test
    fun `PanelGestureTracker - standalone downward flick at top triggers direct close to Full Player`() {
        val tracker = PanelGestureTracker(thresholdPx = 150f, flickVelocity = 450f)
        val controller = NowPlayingNavigationController()
        controller.openLyrics()

        // Flick down without prior drag threshold -> closes directly to Full Player
        assertTrue(tracker.onDownwardFlick(500f))
        val state = controller.onDownwardGestureAtTop()
        assertEquals(PanelSheetState.COLLAPSED, state)
        assertEquals(NowPlayingMode.FULL_PLAYER, controller.mode)
        assertTrue(tracker.gestureHandled)

        tracker.onGestureEnd()
        assertFalse(tracker.gestureHandled)
    }

    @Test
    fun `PanelGestureTracker - upward drag and flick restores EXPANDED from Peek if ever set`() {
        val tracker = PanelGestureTracker(thresholdPx = 150f, flickVelocity = 450f)
        val controller = NowPlayingNavigationController(initialSheetState = PanelSheetState.PEEK)
        assertEquals(PanelSheetState.PEEK, controller.sheetState)

        // Upward drag exceeding threshold
        assertFalse(tracker.onUpwardDelta(-80f))
        assertTrue(tracker.onUpwardDelta(-80f)) // Crosses 160 >= 150 -> fires once
        val stateAfterUp = controller.onUpwardGestureFromPeek()
        assertEquals(PanelSheetState.EXPANDED, stateAfterUp)
        assertEquals(1f, controller.targetProgress, 0.001f)
        assertTrue(tracker.gestureHandled)

        tracker.onGestureEnd()

        // Test upward flick
        val controller2 = NowPlayingNavigationController(initialSheetState = PanelSheetState.PEEK)
        assertTrue(tracker.onUpwardFlick(-500f))
        val stateAfterFlick = controller2.onUpwardGestureFromPeek()
        assertEquals(PanelSheetState.EXPANDED, stateAfterFlick)
    }

    @Test
    fun `PanelGestureTracker - reverse movement resets opposite direction accumulation`() {
        val tracker = PanelGestureTracker(thresholdPx = 150f)

        // Accumulate downward drag
        assertFalse(tracker.onDownwardDelta(80f))
        assertEquals(80f, tracker.accumulatedDown, 0.001f)

        // User pulls UP -> cancels downward accumulation
        assertFalse(tracker.onUpwardDelta(-40f))
        assertEquals(0f, tracker.accumulatedDown, 0.001f)
        assertEquals(40f, tracker.accumulatedUp, 0.001f)

        // User pulls DOWN again -> cancels upward accumulation
        assertFalse(tracker.onDownwardDelta(40f))
        assertEquals(40f, tracker.accumulatedDown, 0.001f)
        assertEquals(0f, tracker.accumulatedUp, 0.001f)
    }

    @Test
    fun `Full Player swipe-up opens Queue on first deliberate gesture`() {
        val controller = NowPlayingNavigationController()
        assertEquals(NowPlayingMode.FULL_PLAYER, controller.mode)
        assertEquals(PanelSheetState.COLLAPSED, controller.sheetState)

        // ONE deliberate swipe UP
        controller.openQueue()
        assertEquals(NowPlayingMode.QUEUE, controller.mode)
        assertEquals(PanelSheetState.EXPANDED, controller.sheetState)
        assertEquals(1f, controller.targetProgress, 0.001f)
    }

    @Test
    fun `Queue lifecycle - Scrolled down swipe DOWN scrolls to top, second swipe DOWN closes Queue`() {
        val controller = NowPlayingNavigationController()

        // 1. Swipe UP from Full Player -> Opens Queue
        controller.openQueue()
        assertEquals(PanelSheetState.EXPANDED, controller.sheetState)
        assertEquals(NowPlayingMode.QUEUE, controller.mode)

        // 2. First deliberate swipe DOWN when NOT at top -> Stays in Queue at EXPANDED
        val stage1 = controller.onQueueDownwardGesture(isAtTop = false)
        assertEquals(PanelSheetState.EXPANDED, stage1)
        assertEquals(NowPlayingMode.QUEUE, controller.mode)

        // 3. Second deliberate swipe DOWN when AT top -> Closes Queue to Full Player directly
        val stage2 = controller.onQueueDownwardGesture(isAtTop = true)
        assertEquals(PanelSheetState.COLLAPSED, stage2)
        assertEquals(NowPlayingMode.FULL_PLAYER, controller.mode)

        // 4. Downward swipe on Full Player passes through
        val stage3 = controller.onDownwardGestureAtTop()
        assertEquals(PanelSheetState.COLLAPSED, stage3)
    }

    @Test
    fun `Lyrics lifecycle - Open to DOWN at top to Full Player`() {
        val controller = NowPlayingNavigationController()

        // 1. Explicit open of Lyrics
        controller.openLyrics()
        assertEquals(PanelSheetState.EXPANDED, controller.sheetState)
        assertEquals(NowPlayingMode.LYRICS, controller.mode)

        // 2. Deliberate swipe DOWN when at top -> Directly returns to Full Player (no Peek)
        val stage1 = controller.onDownwardGestureAtTop()
        assertEquals(PanelSheetState.COLLAPSED, stage1)
        assertEquals(NowPlayingMode.FULL_PLAYER, controller.mode)

        // 3. Downward swipe on Full Player is unhandled by controller (passes to ModalBottomSheet to exit)
        val stage2 = controller.onDownwardGestureAtTop()
        assertEquals(PanelSheetState.COLLAPSED, stage2)
    }

    @Test
    fun `FullPlayerSwipeUpTracker - deliberate upward drag opens Queue on first swipe`() {
        val tracker = FullPlayerSwipeUpTracker(
            thresholdPx = 100f,
            flickVelocityPx = 450f,
            minFlickDistancePx = 40f,
            touchSlopPx = 20f,
        )

        // Micro movement below threshold
        assertFalse(tracker.onPosition(0f, -30f))
        assertFalse(tracker.gestureHandled)

        // Continues moving up and crosses 100px threshold
        assertTrue(tracker.onPosition(0f, -105f))
        assertTrue(tracker.gestureHandled)

        // Further movement in same gesture does not re-fire
        assertFalse(tracker.onPosition(0f, -150f))
    }

    @Test
    fun `FullPlayerSwipeUpTracker - accidental micro movement does not open Queue`() {
        val tracker = FullPlayerSwipeUpTracker(
            thresholdPx = 100f,
            flickVelocityPx = 450f,
            minFlickDistancePx = 40f,
            touchSlopPx = 20f,
        )

        // Small 15px jitter
        assertFalse(tracker.onPosition(5f, -15f))
        assertFalse(tracker.gestureHandled)

        // Release with low velocity
        assertFalse(tracker.onRelease(-100f))
        assertFalse(tracker.gestureHandled)
    }

    @Test
    fun `FullPlayerSwipeUpTracker - downward movement never opens Queue`() {
        val tracker = FullPlayerSwipeUpTracker(
            thresholdPx = 100f,
            flickVelocityPx = 450f,
            minFlickDistancePx = 40f,
            touchSlopPx = 20f,
        )

        // Deliberate downward drag (e.g. 150px)
        assertFalse(tracker.onPosition(0f, 150f))
        assertFalse(tracker.gestureHandled)

        // Downward flick
        assertFalse(tracker.onRelease(800f))
        assertFalse(tracker.gestureHandled)
    }

    @Test
    fun `FullPlayerSwipeUpTracker - horizontal swipe locks out Queue opening`() {
        val tracker = FullPlayerSwipeUpTracker(
            thresholdPx = 100f,
            flickVelocityPx = 450f,
            minFlickDistancePx = 40f,
            touchSlopPx = 20f,
        )

        // User swipes horizontally (e.g. 40px right, 10px up) -> exceeds touch slop and dominates vertical
        assertFalse(tracker.onPosition(40f, -10f))
        assertTrue(tracker.isLockedHorizontal)
        assertFalse(tracker.gestureHandled)

        // Even if user drifts upward later in same gesture, horizontal lock blocks it
        assertFalse(tracker.onPosition(40f, -150f))
        assertFalse(tracker.gestureHandled)

        // Flick also blocked by horizontal lock
        assertFalse(tracker.onRelease(-800f))
        assertFalse(tracker.gestureHandled)
    }

    @Test
    fun `FullPlayerSwipeUpTracker - fast upward flick with sufficient displacement opens Queue`() {
        val tracker = FullPlayerSwipeUpTracker(
            thresholdPx = 100f,
            flickVelocityPx = 450f,
            minFlickDistancePx = 40f,
            touchSlopPx = 20f,
        )

        // Short movement (50px, below 100px drag threshold)
        assertFalse(tracker.onPosition(0f, -50f))
        assertFalse(tracker.gestureHandled)

        // Fast upward release velocity (>= 450f)
        assertTrue(tracker.onRelease(-600f))
        assertTrue(tracker.gestureHandled)
    }

    @Test
    fun `FullPlayerSwipeUpTracker - fast upward flick with insufficient displacement does not open Queue`() {
        val tracker = FullPlayerSwipeUpTracker(
            thresholdPx = 100f,
            flickVelocityPx = 450f,
            minFlickDistancePx = 40f,
            touchSlopPx = 20f,
        )

        // Only 10px displacement (micro tap-flick)
        assertFalse(tracker.onPosition(0f, -10f))

        // Fast velocity but displacement was only 10px < 40px
        assertFalse(tracker.onRelease(-800f))
        assertFalse(tracker.gestureHandled)
    }

    @Test
    fun `FullPlayerSwipeUpTracker - gesture end resets tracker allowing subsequent swipe to open Queue`() {
        val tracker = FullPlayerSwipeUpTracker(
            thresholdPx = 100f,
            flickVelocityPx = 450f,
            minFlickDistancePx = 40f,
            touchSlopPx = 20f,
        )

        // 1st Gesture: triggers Queue
        assertTrue(tracker.onPosition(0f, -120f))
        assertTrue(tracker.gestureHandled)

        // Gesture ends (finger lifted / queue closed back to full player)
        tracker.onGestureEnd()
        assertFalse(tracker.gestureHandled)
        assertFalse(tracker.isLockedHorizontal)

        // 2nd Gesture: opens Queue immediately on 1st swipe again
        assertTrue(tracker.onPosition(0f, -110f))
        assertTrue(tracker.gestureHandled)
    }

    // =======================================================
    // STEP 2 — QUEUE SWIPE DOWN TESTS
    // =======================================================

    @Test
    fun `FLOW 1 - Queue at top - ONE deliberate swipe DOWN closes Queue`() {
        val tracker = QueueSwipeDownTracker(
            thresholdPx = 100f,
            flickVelocityPx = 450f,
            minFlickDistancePx = 40f,
            touchSlopPx = 20f,
        )

        // Queue is at top
        tracker.onGestureStart(isAtTop = true)

        // ONE deliberate swipe DOWN exceeding threshold
        assertFalse(tracker.onPosition(0f, 30f) == QueueSwipeDownAction.CLOSE_QUEUE)
        val action = tracker.onPosition(0f, 110f)

        assertEquals(QueueSwipeDownAction.CLOSE_QUEUE, action)
        assertTrue(tracker.gestureHandled)

        // Subsequent drag events in same gesture do not re-trigger
        assertEquals(QueueSwipeDownAction.NONE, tracker.onPosition(0f, 150f))
    }

    @Test
    fun `FLOW 2 - Queue scrolled down - ONE deliberate swipe DOWN scrolls to top and stays in Queue`() {
        val tracker = QueueSwipeDownTracker(
            thresholdPx = 100f,
            flickVelocityPx = 450f,
            minFlickDistancePx = 40f,
            touchSlopPx = 20f,
        )

        // Queue is scrolled down (e.g. item 5)
        tracker.onGestureStart(isAtTop = false)

        // Deliberate swipe DOWN
        val action = tracker.onPosition(0f, 110f)

        assertEquals(QueueSwipeDownAction.SCROLL_TO_TOP, action)
        assertNotEquals(QueueSwipeDownAction.CLOSE_QUEUE, action)
        assertTrue(tracker.gestureHandled)

        // Even continuing to drag down in the same gesture MUST NOT close Queue
        assertEquals(QueueSwipeDownAction.NONE, tracker.onPosition(0f, 200f))
        assertEquals(QueueSwipeDownAction.NONE, tracker.onRelease(600f))
    }

    @Test
    fun `FLOW 3 - Queue scrolled down - Swipe 1 scrolls to top, Swipe 2 closes Queue`() {
        val tracker = QueueSwipeDownTracker(
            thresholdPx = 100f,
            flickVelocityPx = 450f,
            minFlickDistancePx = 40f,
            touchSlopPx = 20f,
        )

        // GESTURE 1: Starts when scrolled down
        tracker.onGestureStart(isAtTop = false)
        val action1 = tracker.onPosition(0f, 120f)
        assertEquals(QueueSwipeDownAction.SCROLL_TO_TOP, action1)
        tracker.onGestureEnd()

        // GESTURE 2: Starts after list is now at top
        tracker.onGestureStart(isAtTop = true)
        val action2 = tracker.onPosition(0f, 120f)
        assertEquals(QueueSwipeDownAction.CLOSE_QUEUE, action2)
    }

    @Test
    fun `FLOW 4 - Queue near top but not actually at top - DOWN scrolls to top and stays open`() {
        val tracker = QueueSwipeDownTracker(
            thresholdPx = 100f,
            flickVelocityPx = 450f,
            minFlickDistancePx = 40f,
            touchSlopPx = 20f,
        )

        // Scrolled slightly (e.g. offset = 10px, so isAtTop = false)
        tracker.onGestureStart(isAtTop = false)

        val action = tracker.onPosition(0f, 110f)
        assertEquals(QueueSwipeDownAction.SCROLL_TO_TOP, action)
        assertNotEquals(QueueSwipeDownAction.CLOSE_QUEUE, action)
    }

    @Test
    fun `FLOW 5 - Tiny accidental DOWN does nothing`() {
        val tracker = QueueSwipeDownTracker(
            thresholdPx = 100f,
            flickVelocityPx = 450f,
            minFlickDistancePx = 40f,
            touchSlopPx = 20f,
        )

        // At top: small 15px jitter
        tracker.onGestureStart(isAtTop = true)
        assertEquals(QueueSwipeDownAction.NONE, tracker.onPosition(0f, 15f))
        assertEquals(QueueSwipeDownAction.NONE, tracker.onRelease(100f))
        assertFalse(tracker.gestureHandled)

        // Scrolled down: small 15px jitter
        tracker.onGestureEnd()
        tracker.onGestureStart(isAtTop = false)
        assertEquals(QueueSwipeDownAction.NONE, tracker.onPosition(0f, 15f))
        assertEquals(QueueSwipeDownAction.NONE, tracker.onRelease(100f))
        assertFalse(tracker.gestureHandled)
    }

    @Test
    fun `FLOW 6 - Normal Queue scrolling produces no gesture actions`() {
        val tracker = QueueSwipeDownTracker(
            thresholdPx = 100f,
            flickVelocityPx = 450f,
            minFlickDistancePx = 40f,
            touchSlopPx = 20f,
        )

        tracker.onGestureStart(isAtTop = false)

        // Upward drag (scrolling deeper into queue)
        assertEquals(QueueSwipeDownAction.NONE, tracker.onPosition(0f, -80f))
        assertEquals(QueueSwipeDownAction.NONE, tracker.onPosition(0f, -150f))
        assertFalse(tracker.gestureHandled)
    }

    @Test
    fun `QueueSwipeDownTracker - horizontal swipe locks out actions`() {
        val tracker = QueueSwipeDownTracker(
            thresholdPx = 100f,
            flickVelocityPx = 450f,
            minFlickDistancePx = 40f,
            touchSlopPx = 20f,
        )

        tracker.onGestureStart(isAtTop = true)

        // Horizontal swipe (40px x, 10px y)
        assertEquals(QueueSwipeDownAction.NONE, tracker.onPosition(40f, 10f))
        assertTrue(tracker.isLockedHorizontal)

        // Later downward movement in same gesture is blocked
        assertEquals(QueueSwipeDownAction.NONE, tracker.onPosition(40f, 150f))
        assertEquals(QueueSwipeDownAction.NONE, tracker.onRelease(800f))
        assertFalse(tracker.gestureHandled)
    }

    @Test
    fun `QueueSwipeDownTracker - downward flick triggers appropriate action`() {
        val tracker = QueueSwipeDownTracker(
            thresholdPx = 100f,
            flickVelocityPx = 450f,
            minFlickDistancePx = 40f,
            touchSlopPx = 20f,
        )

        // At top: flick down -> CLOSE_QUEUE
        tracker.onGestureStart(isAtTop = true)
        tracker.onPosition(0f, 50f) // Below 100px threshold
        val actionAtTop = tracker.onRelease(600f) // Velocity >= 450f
        assertEquals(QueueSwipeDownAction.CLOSE_QUEUE, actionAtTop)

        // Not at top: flick down -> SCROLL_TO_TOP
        tracker.onGestureEnd()
        tracker.onGestureStart(isAtTop = false)
        tracker.onPosition(0f, 50f)
        val actionNotAtTop = tracker.onRelease(600f)
        assertEquals(QueueSwipeDownAction.SCROLL_TO_TOP, actionNotAtTop)
    }

    @Test
    fun `QueueSwipeDownTracker - separate scrollToTopThresholdPx allows smooth downward browsing without hijacking scroll`() {
        val tracker = QueueSwipeDownTracker(
            thresholdPx = 48f,
            scrollToTopThresholdPx = 140f,
            flickVelocityPx = 450f,
            minFlickDistancePx = 20f,
            minScrollToTopFlickDistancePx = 72f,
            touchSlopPx = 20f,
        )

        // User is scrolled down (browsing queue)
        tracker.onGestureStart(isAtTop = false)

        // Downward drag of 60px (more than 48px close threshold, but less than 140px scrollToTop threshold)
        // Must NOT trigger SCROLL_TO_TOP or CLOSE_QUEUE — must remain NONE so LazyColumn scrolls naturally
        assertEquals(QueueSwipeDownAction.NONE, tracker.onPosition(0f, 60f))
        assertFalse(tracker.gestureHandled)

        // Moderate flick of 40px with high velocity (less than minScrollToTopFlickDistancePx of 72px)
        // Must NOT trigger SCROLL_TO_TOP
        assertEquals(QueueSwipeDownAction.NONE, tracker.onRelease(600f))
        assertFalse(tracker.gestureHandled)

        // New gesture: deliberate long downward swipe of 150px >= 140px
        tracker.onGestureEnd()
        tracker.onGestureStart(isAtTop = false)
        val action = tracker.onPosition(0f, 150f)
        assertEquals(QueueSwipeDownAction.SCROLL_TO_TOP, action)
        assertTrue(tracker.gestureHandled)
    }

    @Test
    fun `QueueSwipeDownTracker - at top threshold triggers CLOSE_QUEUE without Peek or delay`() {
        val tracker = QueueSwipeDownTracker(
            thresholdPx = 48f,
            scrollToTopThresholdPx = 140f,
            flickVelocityPx = 450f,
            minFlickDistancePx = 20f,
            minScrollToTopFlickDistancePx = 72f,
            touchSlopPx = 20f,
        )

        // User is at top of queue
        tracker.onGestureStart(isAtTop = true)

        // Downward drag crossing 48px
        val action = tracker.onPosition(0f, 50f)
        assertEquals(QueueSwipeDownAction.CLOSE_QUEUE, action)
        assertTrue(tracker.gestureHandled)
    }

    @Test
    fun `QueueSwipeDownTracker - downward swipe when not at top never chains into close in single gesture`() {
        val tracker = QueueSwipeDownTracker(
            thresholdPx = 48f,
            scrollToTopThresholdPx = 140f,
            flickVelocityPx = 450f,
            minFlickDistancePx = 20f,
            minScrollToTopFlickDistancePx = 72f,
            touchSlopPx = 20f,
        )

        tracker.onGestureStart(isAtTop = false)

        // Reaches threshold -> SCROLL_TO_TOP
        val action1 = tracker.onPosition(0f, 150f)
        assertEquals(QueueSwipeDownAction.SCROLL_TO_TOP, action1)

        // User continues dragging further down in the SAME gesture
        val action2 = tracker.onPosition(0f, 250f)
        assertEquals(QueueSwipeDownAction.NONE, action2)

        val action3 = tracker.onPosition(0f, 400f)
        assertEquals(QueueSwipeDownAction.NONE, action3)

        // User releases with high downward velocity
        val actionRelease = tracker.onRelease(600f)
        assertEquals(QueueSwipeDownAction.NONE, actionRelease)
    }

    // ==================================================
    // STEP 3 — LYRICS SWIPE DOWN TESTS
    // ==================================================

    @Test
    fun `LyricsSwipeDownTracker - separate scrollToTopThresholdPx allows smooth downward browsing without hijacking scroll`() {
        val tracker = LyricsSwipeDownTracker(
            thresholdPx = 48f,
            scrollToTopThresholdPx = 180f,
            flickVelocityPx = 450f,
            minFlickDistancePx = 20f,
            minScrollToTopFlickDistancePx = 120f,
            touchSlopPx = 20f,
        )

        // User is scrolled down (browsing lyrics)
        tracker.onGestureStart(isAtTop = false)

        // Downward drag of 60px (more than 48px close threshold, but less than 180px scrollToTop threshold)
        // Must NOT trigger SCROLL_TO_TOP or CLOSE_LYRICS — must remain NONE so LazyColumn scrolls naturally
        assertEquals(LyricsSwipeDownAction.NONE, tracker.onPosition(0f, 60f))
        assertFalse(tracker.gestureHandled)

        // Moderate flick of 50px with high velocity (less than minScrollToTopFlickDistancePx of 120px)
        // Must NOT trigger SCROLL_TO_TOP
        assertEquals(LyricsSwipeDownAction.NONE, tracker.onRelease(600f))
        assertFalse(tracker.gestureHandled)

        // New gesture: deliberate long downward swipe of 190px >= 180px
        tracker.onGestureEnd()
        tracker.onGestureStart(isAtTop = false)
        val action = tracker.onPosition(0f, 190f)
        assertEquals(LyricsSwipeDownAction.SCROLL_TO_TOP, action)
        assertTrue(tracker.gestureHandled)
    }

    @Test
    fun `FLOW A - Lyrics scrolled down - deliberate downward swipe scrolls to top and stays open`() {
        val tracker = LyricsSwipeDownTracker(
            thresholdPx = 100f,
            flickVelocityPx = 450f,
            minFlickDistancePx = 40f,
            touchSlopPx = 20f,
        )

        tracker.onGestureStart(isAtTop = false)

        // Drag down exceeding threshold
        val action = tracker.onPosition(0f, 110f)
        assertEquals(LyricsSwipeDownAction.SCROLL_TO_TOP, action)
        assertNotEquals(LyricsSwipeDownAction.CLOSE_LYRICS, action)
        assertTrue(tracker.gestureHandled)

        // Controller check: remains at EXPANDED, mode = LYRICS
        val controller = NowPlayingNavigationController()
        controller.openLyrics()
        val sheetState = controller.onLyricsDownwardGesture(isAtTop = false)
        assertEquals(PanelSheetState.EXPANDED, sheetState)
        assertEquals(NowPlayingMode.LYRICS, controller.mode)
        assertEquals(1f, controller.targetProgress, 0.001f)
    }

    @Test
    fun `FLOW B - Lyrics at top - deliberate downward swipe closes to Full Player`() {
        val tracker = LyricsSwipeDownTracker(
            thresholdPx = 100f,
            flickVelocityPx = 450f,
            minFlickDistancePx = 40f,
            touchSlopPx = 20f,
        )

        tracker.onGestureStart(isAtTop = true)

        // Drag down exceeding threshold
        val action = tracker.onPosition(0f, 110f)
        assertEquals(LyricsSwipeDownAction.CLOSE_LYRICS, action)
        assertTrue(tracker.gestureHandled)

        // Controller check: transitions to COLLAPSED, mode = FULL_PLAYER
        val controller = NowPlayingNavigationController()
        controller.openLyrics()
        val sheetState = controller.onLyricsDownwardGesture(isAtTop = true)
        assertEquals(PanelSheetState.COLLAPSED, sheetState)
        assertEquals(NowPlayingMode.FULL_PLAYER, controller.mode)
        assertEquals(0f, controller.targetProgress, 0.001f)
    }

    @Test
    fun `FLOW C - Lyrics scrolled down - downward swipe never chains into close in single gesture`() {
        val tracker = LyricsSwipeDownTracker(
            thresholdPx = 100f,
            flickVelocityPx = 450f,
            minFlickDistancePx = 40f,
            touchSlopPx = 20f,
        )

        tracker.onGestureStart(isAtTop = false)

        // Action 1: Reaches threshold -> SCROLL_TO_TOP
        val action1 = tracker.onPosition(0f, 110f)
        assertEquals(LyricsSwipeDownAction.SCROLL_TO_TOP, action1)

        // User continues dragging further down in the SAME gesture
        val action2 = tracker.onPosition(0f, 200f)
        assertEquals(LyricsSwipeDownAction.NONE, action2)

        val action3 = tracker.onPosition(0f, 350f)
        assertEquals(LyricsSwipeDownAction.NONE, action3)

        // User releases with high downward velocity
        val actionRelease = tracker.onRelease(600f)
        assertEquals(LyricsSwipeDownAction.NONE, actionRelease)
    }

    @Test
    fun `FLOW C - Scrolled down - Swipe 1 scrolls to top, separate Swipe 2 closes Lyrics`() {
        val tracker = LyricsSwipeDownTracker(
            thresholdPx = 100f,
            flickVelocityPx = 450f,
            minFlickDistancePx = 40f,
            touchSlopPx = 20f,
        )

        // GESTURE 1: Starts when scrolled down
        tracker.onGestureStart(isAtTop = false)
        val action1 = tracker.onPosition(0f, 120f)
        assertEquals(LyricsSwipeDownAction.SCROLL_TO_TOP, action1)
        tracker.onGestureEnd()

        // GESTURE 2: Starts after list is now at top
        tracker.onGestureStart(isAtTop = true)
        val action2 = tracker.onPosition(0f, 120f)
        assertEquals(LyricsSwipeDownAction.CLOSE_LYRICS, action2)
    }

    @Test
    fun `FLOW D - Lyrics near top but not actually at top - DOWN scrolls to top and stays open`() {
        val tracker = LyricsSwipeDownTracker(
            thresholdPx = 100f,
            flickVelocityPx = 450f,
            minFlickDistancePx = 40f,
            touchSlopPx = 20f,
        )

        // Scrolled slightly (e.g. offset = 10px, so isAtTop = false)
        tracker.onGestureStart(isAtTop = false)

        val action = tracker.onPosition(0f, 110f)
        assertEquals(LyricsSwipeDownAction.SCROLL_TO_TOP, action)
        assertNotEquals(LyricsSwipeDownAction.CLOSE_LYRICS, action)
    }

    @Test
    fun `FLOW E - Tiny accidental DOWN does nothing on Lyrics`() {
        val tracker = LyricsSwipeDownTracker(
            thresholdPx = 100f,
            flickVelocityPx = 450f,
            minFlickDistancePx = 40f,
            touchSlopPx = 20f,
        )

        // At top: small 15px jitter
        tracker.onGestureStart(isAtTop = true)
        assertEquals(LyricsSwipeDownAction.NONE, tracker.onPosition(0f, 15f))
        assertEquals(LyricsSwipeDownAction.NONE, tracker.onRelease(100f))
        assertFalse(tracker.gestureHandled)

        // Scrolled down: small 15px jitter
        tracker.onGestureEnd()
        tracker.onGestureStart(isAtTop = false)
        assertEquals(LyricsSwipeDownAction.NONE, tracker.onPosition(0f, 15f))
        assertEquals(LyricsSwipeDownAction.NONE, tracker.onRelease(100f))
        assertFalse(tracker.gestureHandled)
    }

    @Test
    fun `FLOW F - Normal Lyrics scrolling produces no gesture actions`() {
        val tracker = LyricsSwipeDownTracker(
            thresholdPx = 100f,
            flickVelocityPx = 450f,
            minFlickDistancePx = 40f,
            touchSlopPx = 20f,
        )

        tracker.onGestureStart(isAtTop = false)

        // Upward drag (scrolling deeper into lyrics)
        assertEquals(LyricsSwipeDownAction.NONE, tracker.onPosition(0f, -80f))
        assertEquals(LyricsSwipeDownAction.NONE, tracker.onPosition(0f, -150f))
        assertFalse(tracker.gestureHandled)
    }

    @Test
    fun `FLOW G - LyricsSwipeDownTracker - horizontal swipe locks out actions`() {
        val tracker = LyricsSwipeDownTracker(
            thresholdPx = 100f,
            flickVelocityPx = 450f,
            minFlickDistancePx = 40f,
            touchSlopPx = 20f,
        )

        tracker.onGestureStart(isAtTop = true)

        // Horizontal swipe (40px x, 10px y)
        assertEquals(LyricsSwipeDownAction.NONE, tracker.onPosition(40f, 10f))
        assertTrue(tracker.isLockedHorizontal)

        // Later downward movement in same gesture is blocked
        assertEquals(LyricsSwipeDownAction.NONE, tracker.onPosition(40f, 150f))
        assertEquals(LyricsSwipeDownAction.NONE, tracker.onRelease(800f))
        assertFalse(tracker.gestureHandled)
    }

    @Test
    fun `FLOW H - LyricsSwipeDownTracker - downward flick triggers appropriate action`() {
        val tracker = LyricsSwipeDownTracker(
            thresholdPx = 100f,
            flickVelocityPx = 450f,
            minFlickDistancePx = 40f,
            touchSlopPx = 20f,
        )

        // At top: flick down -> CLOSE_LYRICS
        tracker.onGestureStart(isAtTop = true)
        tracker.onPosition(0f, 50f) // Below 100px threshold
        val actionAtTop = tracker.onRelease(600f) // Velocity >= 450f
        assertEquals(LyricsSwipeDownAction.CLOSE_LYRICS, actionAtTop)

        // Not at top: flick down -> SCROLL_TO_TOP
        tracker.onGestureEnd()
        tracker.onGestureStart(isAtTop = false)
        tracker.onPosition(0f, 50f)
        val actionNotAtTop = tracker.onRelease(600f)
        assertEquals(LyricsSwipeDownAction.SCROLL_TO_TOP, actionNotAtTop)
    }

    // =======================================================
    // STEP 4 — FINAL QUEUE SWIPE-DOWN MATRIX (ALL 10 SCENARIOS)
    // =======================================================

    @Test
    fun `Matrix 1 - Queue open and at top - swipe anywhere closes Queue to Full Player`() {
        val controller = NowPlayingNavigationController()
        controller.openQueue()
        assertEquals(PanelSheetState.EXPANDED, controller.sheetState)
        assertEquals(NowPlayingMode.QUEUE, controller.mode)

        // Downward swipe anywhere when at top
        val state = controller.onQueueDownwardGesture(isAtTop = true)
        assertEquals(PanelSheetState.COLLAPSED, state)
        assertEquals(NowPlayingMode.FULL_PLAYER, controller.mode)
    }

    @Test
    fun `Matrix 2 - Queue open and scrolled down - swipe anywhere scrolls to top and remains in Queue`() {
        val controller = NowPlayingNavigationController()
        controller.openQueue()
        assertEquals(PanelSheetState.EXPANDED, controller.sheetState)
        assertEquals(NowPlayingMode.QUEUE, controller.mode)

        // Downward swipe anywhere when NOT at top
        val state = controller.onQueueDownwardGesture(isAtTop = false)
        assertEquals(PanelSheetState.EXPANDED, state)
        assertEquals(NowPlayingMode.QUEUE, controller.mode)

        // Subsequent separate swipe when now at top closes Queue
        val stateAfterTop = controller.onQueueDownwardGesture(isAtTop = true)
        assertEquals(PanelSheetState.COLLAPSED, stateAfterTop)
        assertEquals(NowPlayingMode.FULL_PLAYER, controller.mode)
    }

    @Test
    fun `Matrix 3 - Queue open - swipe on Next control area moves Queue and does NOT move Full Player`() {
        val tracker = QueueSwipeDownTracker(thresholdPx = 100f, flickVelocityPx = 450f, touchSlopPx = 20f)
        val controller = NowPlayingNavigationController()
        controller.openQueue()

        // Swipe originates on Next control area when Queue at top
        tracker.onGestureStart(isAtTop = true)
        val action = tracker.onPosition(0f, 120f)
        assertEquals(QueueSwipeDownAction.CLOSE_QUEUE, action)

        // Queue closes to reveal Full Player; Full Player itself does not move down to dismiss
        val finalState = controller.onQueueDownwardGesture(isAtTop = true)
        assertEquals(PanelSheetState.COLLAPSED, finalState)
        assertEquals(NowPlayingMode.FULL_PLAYER, controller.mode)
    }

    @Test
    fun `Matrix 4 - Queue open - swipe on Previous control area moves Queue and does NOT move Full Player`() {
        val tracker = QueueSwipeDownTracker(thresholdPx = 100f, flickVelocityPx = 450f, touchSlopPx = 20f)
        val controller = NowPlayingNavigationController()
        controller.openQueue()

        // Swipe originates on Previous control area when Queue scrolled down
        tracker.onGestureStart(isAtTop = false)
        val action = tracker.onPosition(0f, 120f)
        assertEquals(QueueSwipeDownAction.SCROLL_TO_TOP, action)

        // Queue remains open and scrolls to top; Full Player does not move
        val finalState = controller.onQueueDownwardGesture(isAtTop = false)
        assertEquals(PanelSheetState.EXPANDED, finalState)
        assertEquals(NowPlayingMode.QUEUE, controller.mode)
    }

    @Test
    fun `Matrix 5 - Queue open - swipe on PlayPause control area moves Queue and does NOT move Full Player`() {
        val tracker = QueueSwipeDownTracker(thresholdPx = 100f, flickVelocityPx = 450f, touchSlopPx = 20f)
        val controller = NowPlayingNavigationController()
        controller.openQueue()

        tracker.onGestureStart(isAtTop = true)
        val action = tracker.onPosition(0f, 120f)
        assertEquals(QueueSwipeDownAction.CLOSE_QUEUE, action)

        val finalState = controller.onQueueDownwardGesture(isAtTop = true)
        assertEquals(PanelSheetState.COLLAPSED, finalState)
        assertEquals(NowPlayingMode.FULL_PLAYER, controller.mode)
    }

    @Test
    fun `Matrix 6 - Queue open - swipe on any exposed player area is handled by Queue`() {
        val tracker = QueueSwipeDownTracker(thresholdPx = 100f, flickVelocityPx = 450f, touchSlopPx = 20f)
        tracker.onGestureStart(isAtTop = true)

        // Swipe on exposed background / margins / empty space
        val action = tracker.onPosition(0f, 120f)
        assertEquals(QueueSwipeDownAction.CLOSE_QUEUE, action)
        assertTrue(tracker.gestureHandled)
    }

    @Test
    fun `Matrix 7 - Queue open - swipe starting inside Queue is handled by Queue`() {
        val tracker = QueueSwipeDownTracker(thresholdPx = 100f, flickVelocityPx = 450f, touchSlopPx = 20f)
        tracker.onGestureStart(isAtTop = true)

        val action = tracker.onPosition(0f, 110f)
        assertEquals(QueueSwipeDownAction.CLOSE_QUEUE, action)
    }

    @Test
    fun `Matrix 8 - Queue open - swipe starting outside Queue is still handled by Queue`() {
        val tracker = QueueSwipeDownTracker(thresholdPx = 100f, flickVelocityPx = 450f, touchSlopPx = 20f)
        tracker.onGestureStart(isAtTop = false)

        // Swipe outside Queue when scrolled down scrolls Queue to top
        val action = tracker.onPosition(0f, 110f)
        assertEquals(QueueSwipeDownAction.SCROLL_TO_TOP, action)
    }

    @Test
    fun `Matrix 9 - Queue closed - swipe down on Full Player behaves normally`() {
        val controller = NowPlayingNavigationController()
        // Queue is closed (FULL_PLAYER mode)
        assertEquals(NowPlayingMode.FULL_PLAYER, controller.mode)
        assertEquals(PanelSheetState.COLLAPSED, controller.sheetState)

        // Downward gesture on Full Player passes through to ModalBottomSheet
        val state = controller.onDownwardGestureAtTop()
        assertEquals(PanelSheetState.COLLAPSED, state)
        assertEquals(NowPlayingMode.FULL_PLAYER, controller.mode)
    }

    @Test
    fun `Matrix 10 - Normal taps on player controls while Queue is open are not intercepted as swipe-down`() {
        val tracker = QueueSwipeDownTracker(
            thresholdPx = 100f,
            flickVelocityPx = 450f,
            minFlickDistancePx = 40f,
            touchSlopPx = 20f,
        )
        tracker.onGestureStart(isAtTop = true)

        // Micro touch movement during a normal button tap (5px movement <= touchSlopPx)
        val actionDuringTap = tracker.onPosition(2f, 5f)
        assertEquals(QueueSwipeDownAction.NONE, actionDuringTap)

        // Finger lifts with zero release velocity
        val actionOnRelease = tracker.onRelease(0f)
        assertEquals(QueueSwipeDownAction.NONE, actionOnRelease)
        assertFalse(tracker.gestureHandled)
    }
}


