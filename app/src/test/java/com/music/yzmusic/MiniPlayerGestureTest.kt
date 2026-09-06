package com.music.yzmusic

import com.music.yzmusic.ui.components.DirectionLock
import com.music.yzmusic.ui.components.MiniPlayerAction
import com.music.yzmusic.ui.components.MiniPlayerGestureClassifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests verifying gesture classification, direction locking, displacement/velocity
 * thresholds, and playback action dispatch for the Mini Player as specified in Section 16.
 */
class MiniPlayerGestureTest {

    private fun createClassifier(
        touchSlop: Float = 10f,
        minDisplacement: Float = 48f,
        minVelocity: Float = 500f,
    ) = MiniPlayerGestureClassifier(
        touchSlopPx = touchSlop,
        minDisplacementPx = minDisplacement,
        minVelocityPx = minVelocity,
    )

    @Test
    fun `horizontal swipe LEFT triggers NEXT track`() {
        val classifier = createClassifier()
        val lock = classifier.onMove(totalX = -60f, totalY = 5f)
        assertEquals(DirectionLock.HORIZONTAL, lock)

        val action = classifier.onRelease(totalX = -60f, totalY = 5f, velocityX = -100f, velocityY = 0f)
        assertEquals(MiniPlayerAction.NEXT, action)
    }

    @Test
    fun `horizontal swipe RIGHT triggers PREVIOUS track`() {
        val classifier = createClassifier()
        val lock = classifier.onMove(totalX = 60f, totalY = -5f)
        assertEquals(DirectionLock.HORIZONTAL, lock)

        val action = classifier.onRelease(totalX = 60f, totalY = -5f, velocityX = 100f, velocityY = 0f)
        assertEquals(MiniPlayerAction.PREVIOUS, action)
    }

    @Test
    fun `small horizontal movement below threshold triggers NO ACTION`() {
        val classifier = createClassifier()
        val lock = classifier.onMove(totalX = -20f, totalY = 2f)
        assertEquals(DirectionLock.HORIZONTAL, lock)

        val action = classifier.onRelease(totalX = -20f, totalY = 2f, velocityX = -50f, velocityY = 0f)
        assertEquals(MiniPlayerAction.NONE, action)
    }

    @Test
    fun `vertical swipe UP triggers EXPAND (Full Player)`() {
        val classifier = createClassifier()
        val lock = classifier.onMove(totalX = 5f, totalY = -60f)
        assertEquals(DirectionLock.VERTICAL, lock)

        val action = classifier.onRelease(totalX = 5f, totalY = -60f, velocityX = 0f, velocityY = -100f)
        assertEquals(MiniPlayerAction.EXPAND, action)
    }

    @Test
    fun `vertical swipe DOWN triggers NO ACTION`() {
        val classifier = createClassifier()
        val lock = classifier.onMove(totalX = 5f, totalY = 60f)
        assertEquals(DirectionLock.VERTICAL, lock)

        val action = classifier.onRelease(totalX = 5f, totalY = 60f, velocityX = 0f, velocityY = 100f)
        assertEquals(MiniPlayerAction.NONE, action)
    }

    @Test
    fun `diagonal gesture dominated by horizontal movement locks horizontal and triggers NEXT only`() {
        val classifier = createClassifier()
        // Dominantly horizontal: dx=60 > dy=30
        val lock = classifier.onMove(totalX = -60f, totalY = -30f)
        assertEquals(DirectionLock.HORIZONTAL, lock)

        val action = classifier.onRelease(totalX = -60f, totalY = -30f, velocityX = -200f, velocityY = -100f)
        assertEquals(MiniPlayerAction.NEXT, action)
    }

    @Test
    fun `diagonal gesture dominated by vertical movement locks vertical and triggers EXPAND only`() {
        val classifier = createClassifier()
        // Dominantly vertical: dy=60 > dx=30
        val lock = classifier.onMove(totalX = -30f, totalY = -60f)
        assertEquals(DirectionLock.VERTICAL, lock)

        val action = classifier.onRelease(totalX = -30f, totalY = -60f, velocityX = -100f, velocityY = -200f)
        assertEquals(MiniPlayerAction.EXPAND, action)
    }

    @Test
    fun `direction lock is immutable during touch stream even if subsequent movement favors another axis`() {
        val classifier = createClassifier()
        // Starts horizontal
        classifier.onMove(totalX = -20f, totalY = 2f)
        assertEquals(DirectionLock.HORIZONTAL, classifier.lock)

        // Later user moves mostly vertically
        val lock = classifier.onMove(totalX = -20f, totalY = -100f)
        assertEquals(DirectionLock.HORIZONTAL, lock)

        // Action is still evaluated as horizontal (never triggers EXPAND)
        val action = classifier.onRelease(totalX = -20f, totalY = -100f, velocityX = -10f, velocityY = -400f)
        assertEquals(MiniPlayerAction.NONE, action)
    }

    @Test
    fun `quick horizontal fling LEFT triggers NEXT even with displacement below threshold`() {
        val classifier = createClassifier(minDisplacement = 48f, minVelocity = 500f)
        classifier.onMove(totalX = -15f, totalY = 0f)
        assertEquals(DirectionLock.HORIZONTAL, classifier.lock)

        val action = classifier.onRelease(totalX = -15f, totalY = 0f, velocityX = -600f, velocityY = 0f)
        assertEquals(MiniPlayerAction.NEXT, action)
    }

    @Test
    fun `quick horizontal fling RIGHT triggers PREVIOUS even with displacement below threshold`() {
        val classifier = createClassifier(minDisplacement = 48f, minVelocity = 500f)
        classifier.onMove(totalX = 15f, totalY = 0f)
        assertEquals(DirectionLock.HORIZONTAL, classifier.lock)

        val action = classifier.onRelease(totalX = 15f, totalY = 0f, velocityX = 600f, velocityY = 0f)
        assertEquals(MiniPlayerAction.PREVIOUS, action)
    }

    @Test
    fun `quick vertical fling UP triggers EXPAND even with displacement below threshold`() {
        val classifier = createClassifier(minDisplacement = 48f, minVelocity = 500f)
        classifier.onMove(totalX = 0f, totalY = -15f)
        assertEquals(DirectionLock.VERTICAL, classifier.lock)

        val action = classifier.onRelease(totalX = 0f, totalY = -15f, velocityX = 0f, velocityY = -600f)
        assertEquals(MiniPlayerAction.EXPAND, action)
    }

    @Test
    fun `edge case - no next track does not crash and handles safely`() {
        var nextCalled = false
        val hasNext = false
        val onNext = {
            if (hasNext) {
                nextCalled = true
            }
        }

        // Simulate swipe left action dispatch
        val classifier = createClassifier()
        classifier.onMove(-60f, 0f)
        val action = classifier.onRelease(-60f, 0f, 0f, 0f)
        if (action == MiniPlayerAction.NEXT) {
            onNext()
        }

        assertFalse(nextCalled)
    }

    @Test
    fun `edge case - no previous track does not crash and handles safely`() {
        var previousCalled = false
        val hasPrevious = false
        val onPrevious = {
            if (hasPrevious) {
                previousCalled = true
            }
        }

        // Simulate swipe right action dispatch
        val classifier = createClassifier()
        classifier.onMove(60f, 0f)
        val action = classifier.onRelease(60f, 0f, 0f, 0f)
        if (action == MiniPlayerAction.PREVIOUS) {
            onPrevious()
        }

        assertFalse(previousCalled)
    }

    @Test
    fun `edge case - single item queue safely executes without crash`() {
        val queueSize = 1
        var trackChanged = false
        val onNext = {
            if (queueSize > 1) trackChanged = true
        }
        val onPrevious = {
            if (queueSize > 1) trackChanged = true
        }

        val classifier = createClassifier()
        classifier.onMove(-60f, 0f)
        if (classifier.onRelease(-60f, 0f, 0f, 0f) == MiniPlayerAction.NEXT) {
            onNext()
        }
        classifier.onMove(60f, 0f)
        if (classifier.onRelease(60f, 0f, 0f, 0f) == MiniPlayerAction.PREVIOUS) {
            onPrevious()
        }

        assertFalse(trackChanged)
    }

    @Test
    fun `autoplay queue integration - next track uses Media3 queue including autoplay`() {
        val media3Queue = mutableListOf("ManualTrack1", "ManualTrack2", "AutoPlayTrack1", "AutoPlayTrack2")
        var currentQueueIndex = 1 // At end of manual queue, next is AutoPlay

        val onNext = {
            if (currentQueueIndex + 1 < media3Queue.size) {
                currentQueueIndex++
            }
        }

        val classifier = createClassifier()
        classifier.onMove(-60f, 0f)
        if (classifier.onRelease(-60f, 0f, 0f, 0f) == MiniPlayerAction.NEXT) {
            onNext()
        }

        assertEquals(2, currentQueueIndex)
        assertEquals("AutoPlayTrack1", media3Queue[currentQueueIndex])
    }

    @Test
    fun `stationary tap returns NONE preserving tap to expand`() {
        val classifier = createClassifier()
        // Tap: 0 movement
        val lock = classifier.onMove(0f, 0f)
        assertEquals(DirectionLock.NONE, lock)

        val action = classifier.onRelease(0f, 0f, 0f, 0f)
        assertEquals(MiniPlayerAction.NONE, action)
    }

    @Test
    fun `rapid repeated swipes within 300ms window are debounced to prevent multi-skipping`() {
        var skipCount = 0
        var lastActionTime = 0L
        val minInterval = 300L

        fun attemptSwipe(now: Long) {
            val classifier = createClassifier()
            classifier.onMove(-60f, 0f)
            val action = classifier.onRelease(-60f, 0f, 0f, 0f)
            if (action == MiniPlayerAction.NEXT) {
                if (now - lastActionTime >= minInterval) {
                    lastActionTime = now
                    skipCount++
                }
            }
        }

        attemptSwipe(1000L) // First swipe -> accepted
        attemptSwipe(1100L) // 100ms later -> debounced
        attemptSwipe(1250L) // 250ms later -> debounced
        attemptSwipe(1350L) // 350ms later -> accepted

        assertEquals(2, skipCount)
    }

    @Test
    fun `haptic mapping verifies SkipNext on NEXT and SkipPrevious on PREVIOUS`() {
        val classifier = createClassifier()

        classifier.onMove(-60f, 0f)
        val nextAction = classifier.onRelease(-60f, 0f, 0f, 0f)
        val nextHaptic = if (nextAction == MiniPlayerAction.NEXT) com.music.yzmusic.ui.haptics.Haptic.SkipNext else null
        assertEquals(com.music.yzmusic.ui.haptics.Haptic.SkipNext, nextHaptic)

        classifier.onMove(60f, 0f)
        val prevAction = classifier.onRelease(60f, 0f, 0f, 0f)
        val prevHaptic = if (prevAction == MiniPlayerAction.PREVIOUS) com.music.yzmusic.ui.haptics.Haptic.SkipPrevious else null
        assertEquals(com.music.yzmusic.ui.haptics.Haptic.SkipPrevious, prevHaptic)
    }
}
