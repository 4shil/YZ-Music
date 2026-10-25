package com.music.bitchord.ui.player

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

enum class NowPlayingMode {
    FULL_PLAYER,
    QUEUE,
    LYRICS,
}

enum class PanelSheetState {
    COLLAPSED,  // 0.0f (Full Player)
    PEEK,       // 0.55f (Peeked Queue or Lyrics)
    EXPANDED,   // 1.0f (Full Queue or Lyrics)
}

const val PEEK_PROGRESS = 0.55f

/**
 * State machine managing Now Playing navigation and panel dismissal.
 *
 * Rules enforced:
 * 1. Full Player swipe-up opens QUEUE only (never Lyrics).
 * 2. Lyrics is opened only via explicit Lyrics action/click.
 * 3. Downward dismissal for Queue and Lyrics:
 *    - Content scroll -> Scroll position 0.
 *    - Deliberate downward gesture at list top -> PEEK.
 *    - Second deliberate downward gesture from PEEK -> COLLAPSED / FULL_PLAYER.
 *    - Downward gesture on Full Player -> sheet dismissal (exit player).
 * 4. Upward gesture from PEEK restores EXPANDED.
 */
class NowPlayingNavigationController(
    initialMode: NowPlayingMode = NowPlayingMode.FULL_PLAYER,
    initialSheetState: PanelSheetState = PanelSheetState.COLLAPSED,
) {
    var mode: NowPlayingMode = initialMode
        private set

    var sheetState: PanelSheetState = initialSheetState
        private set

    val targetProgress: Float
        get() = when (sheetState) {
            PanelSheetState.COLLAPSED -> 0f
            PanelSheetState.PEEK -> PEEK_PROGRESS
            PanelSheetState.EXPANDED -> 1f
        }

    fun openQueue() {
        mode = NowPlayingMode.QUEUE
        sheetState = PanelSheetState.EXPANDED
    }

    fun openLyrics() {
        mode = NowPlayingMode.LYRICS
        sheetState = PanelSheetState.EXPANDED
    }

    /**
     * Handles deliberate downward gesture when at the top of the content.
     * For Queue & Lyrics: transitions directly to COLLAPSED / FULL_PLAYER (no Peek state).
     */
    fun onDownwardGestureAtTop(): PanelSheetState {
        when (mode) {
            NowPlayingMode.QUEUE,
            NowPlayingMode.LYRICS -> {
                sheetState = PanelSheetState.COLLAPSED
                mode = NowPlayingMode.FULL_PLAYER
            }
            NowPlayingMode.FULL_PLAYER -> Unit
        }
        return sheetState
    }

    /**
     * Handles Lyrics deliberate downward gesture.
     * - If not at top: stays in LYRICS at EXPANDED (scroll to top).
     * - If at top: transitions directly to COLLAPSED / FULL_PLAYER.
     * No Peek state.
     */
    fun onLyricsDownwardGesture(isAtTop: Boolean): PanelSheetState {
        if (mode == NowPlayingMode.LYRICS) {
            if (isAtTop) {
                sheetState = PanelSheetState.COLLAPSED
                mode = NowPlayingMode.FULL_PLAYER
            } else {
                sheetState = PanelSheetState.EXPANDED
            }
        }
        return sheetState
    }

    /**
     * Handles Queue deliberate downward gesture.
     * - If not at top: stays in QUEUE at EXPANDED (scroll to top).
     * - If at top: transitions directly to COLLAPSED / FULL_PLAYER.
     * No Peek state.
     */
    fun onQueueDownwardGesture(isAtTop: Boolean): PanelSheetState {
        if (mode == NowPlayingMode.QUEUE) {
            if (isAtTop) {
                sheetState = PanelSheetState.COLLAPSED
                mode = NowPlayingMode.FULL_PLAYER
            } else {
                sheetState = PanelSheetState.EXPANDED
            }
        }
        return sheetState
    }

    /**
     * Handles upward gesture from PEEK, restoring EXPANDED.
     */
    fun onUpwardGestureFromPeek(): PanelSheetState {
        if (sheetState == PanelSheetState.PEEK) {
            sheetState = PanelSheetState.EXPANDED
        }
        return sheetState
    }

    /**
     * Direct close (e.g. back button or thumbnail tap) returning to Full Player.
     */
    fun closeToFullPlayer() {
        sheetState = PanelSheetState.COLLAPSED
        mode = NowPlayingMode.FULL_PLAYER
    }
}

/**
 * Tracks drag displacement accumulation and enforces the single-action-per-gesture
 * contract for nested scrolling in Queue and Lyrics.
 *
 * Prevents continuous downward drags or drag+fling combinations from triggering
 * multiple consecutive state transitions (e.g. EXPANDED -> PEEK -> COLLAPSED)
 * within a single touch gesture.
 */
class PanelGestureTracker(
    val thresholdPx: Float,
    val flickVelocity: Float = 450f,
) {
    var accumulatedDown = 0f
        private set
    var accumulatedUp = 0f
        private set
    var gestureHandled = false
        private set

    /**
     * Records downward movement at top of list.
     * Returns true if this delta crossed threshold and triggers a step down.
     */
    fun onDownwardDelta(deltaY: Float): Boolean {
        if (deltaY < 0f) {
            accumulatedDown = 0f
            return false
        }
        if (deltaY == 0f) {
            return false
        }
        accumulatedDown += deltaY
        accumulatedUp = 0f
        if (!gestureHandled && accumulatedDown >= thresholdPx) {
            gestureHandled = true
            accumulatedDown = 0f
            return true
        }
        return false
    }

    /**
     * Records upward movement.
     * Returns true if this delta crossed threshold and triggers a step up.
     */
    fun onUpwardDelta(deltaY: Float): Boolean {
        if (deltaY > 0f) {
            accumulatedUp = 0f
            return false
        }
        if (deltaY == 0f) {
            return false
        }
        accumulatedUp += kotlin.math.abs(deltaY)
        accumulatedDown = 0f
        if (!gestureHandled && accumulatedUp >= thresholdPx) {
            gestureHandled = true
            accumulatedUp = 0f
            return true
        }
        return false
    }

    /**
     * Checks downward flick velocity at release.
     * Returns true if downward flick triggers a step down.
     */
    fun onDownwardFlick(velocity: Float): Boolean {
        if (!gestureHandled && velocity >= flickVelocity) {
            gestureHandled = true
            accumulatedDown = 0f
            accumulatedUp = 0f
            return true
        }
        return false
    }

    /**
     * Checks upward flick velocity at release.
     * Returns true if upward flick triggers a step up.
     */
    fun onUpwardFlick(velocity: Float): Boolean {
        if (!gestureHandled && velocity <= -flickVelocity) {
            gestureHandled = true
            accumulatedDown = 0f
            accumulatedUp = 0f
            return true
        }
        return false
    }

    /**
     * Resets gesture tracking when a gesture ends (finger lift / post-fling).
     */
    fun onGestureEnd() {
        accumulatedDown = 0f
        accumulatedUp = 0f
        gestureHandled = false
    }
}

/**
 * Detects deliberate upward swipe on the Full Player to open Queue.
 *
 * Requirements enforced:
 * 1. Works anywhere on the Full Player (artwork, metadata, background, controls).
 * 2. Exactly ONE deliberate swipe opens Queue (never requires two swipes).
 * 3. Directionally locked: clear UP = Queue. Horizontal movement (|dx| > |dy| * 1.25)
 *    locks out Queue opening for the gesture.
 * 4. Threshold: sensible distance (e.g. 48dp) or flick velocity (e.g. 450dp/s) so small
 *    accidental touches do nothing.
 * 5. Downward movement (dy > 0) never opens Queue.
 */
class FullPlayerSwipeUpTracker(
    val thresholdPx: Float,
    val flickVelocityPx: Float = 450f,
    val minFlickDistancePx: Float = 20f,
    val touchSlopPx: Float = 24f,
) {
    var gestureHandled: Boolean = false
        private set

    var isLockedHorizontal: Boolean = false
        private set

    var totalDx: Float = 0f
        private set

    var totalDy: Float = 0f
        private set

    /**
     * Updates pointer displacement from start position.
     * Returns true if this movement qualifies as a deliberate upward swipe to open Queue.
     */
    fun onPosition(totalX: Float, totalY: Float): Boolean {
        if (gestureHandled) return false

        totalDx = totalX
        totalDy = totalY

        val absDx = kotlin.math.abs(totalDx)
        val absDy = kotlin.math.abs(totalDy)

        // Direction lock: if horizontal movement exceeds touch slop and dominates vertical, lock horizontal
        if (!isLockedHorizontal && absDx > touchSlopPx && absDx > absDy * 1.25f) {
            isLockedHorizontal = true
        }

        if (isLockedHorizontal) return false

        // Downward movement must never trigger Queue
        if (totalDy > 0f) return false

        // Check clear deliberate upward movement: -totalDy meets threshold and dominates horizontal
        val isDirectionUp = -totalDy > absDx * 1.25f
        if (-totalDy >= thresholdPx && isDirectionUp) {
            gestureHandled = true
            return true
        }

        return false
    }

    /**
     * Checks flick on pointer release.
     * Returns true if upward flick qualifies to open Queue.
     */
    fun onRelease(velocityY: Float): Boolean {
        if (gestureHandled || isLockedHorizontal) return false

        val absDx = kotlin.math.abs(totalDx)
        val isDirectionUp = -totalDy > absDx * 1.25f
        if (totalDy <= -minFlickDistancePx && velocityY <= -flickVelocityPx && isDirectionUp) {
            gestureHandled = true
            return true
        }

        return false
    }

    fun onGestureEnd() {
        gestureHandled = false
        isLockedHorizontal = false
        totalDx = 0f
        totalDy = 0f
    }
}

enum class FullPlayerGestureAction {
    NONE,
    SWIPE_UP_QUEUE,
    SWIPE_LEFT_NEXT,
    SWIPE_RIGHT_PREVIOUS,
    DOUBLE_TAP_SEEK_BACKWARD,
    DOUBLE_TAP_SEEK_FORWARD,
}

/**
 * Calculates target seek position clamped to boundaries.
 */
object FullPlayerSeekCalculator {
    const val DEFAULT_GUARD_MS = 1500L

    fun calculateTarget(
        isForward: Boolean,
        currentPosMs: Long,
        durationMs: Long,
        seekAmountSeconds: Int,
        guardMs: Long = DEFAULT_GUARD_MS,
    ): Long {
        val deltaMs = seekAmountSeconds * 1000L
        return if (isForward) {
            if (durationMs > 0L) {
                (currentPosMs + deltaMs).coerceAtMost((durationMs - guardMs).coerceAtLeast(0L))
            } else {
                (currentPosMs + deltaMs).coerceAtLeast(0L)
            }
        } else {
            (currentPosMs - deltaMs).coerceAtLeast(0L)
        }
    }
}

/**
 * Coordinated gesture tracker for the Full Player.
 *
 * Disambiguates between:
 * - Tap / Double-Tap: Movement <= touchSlop, two taps within doubleTapTimeoutMs on the same half (left = backward, right = forward).
 * - Horizontal Swipe: |dx| >= thresholdPx & |dx| > |dy| * 1.25, or horizontal flick. (dx < 0 -> next, dx > 0 -> previous).
 * - Vertical Upward Swipe: -dy >= thresholdPx & -dy > |dx| * 1.25, or upward flick. (opens Queue).
 * - Vertical Downward Swipe: dy > 0 & dy > |dx| * 1.25. (no-op on player body).
 */
class FullPlayerGestureTracker(
    val thresholdPx: Float,
    val flickVelocityPx: Float = 400f,
    val minFlickDistancePx: Float = 20f,
    val touchSlopPx: Float = 24f,
    val doubleTapTimeoutMs: Long = 350L,
) {
    var gestureHandled: Boolean = false
        private set

    var isLockedHorizontal: Boolean = false
        private set

    var isLockedVertical: Boolean = false
        private set

    var totalDx: Float = 0f
        private set

    var totalDy: Float = 0f
        private set

    var lastTapTimeMs: Long = 0L
        private set

    var lastTapX: Float = 0f
        private set

    var lastTapY: Float = 0f
        private set

    /**
     * Updates pointer displacement from start position.
     * Returns the detected gesture action if a threshold was crossed.
     */
    fun onPosition(totalX: Float, totalY: Float): FullPlayerGestureAction {
        if (gestureHandled) return FullPlayerGestureAction.NONE

        totalDx = totalX
        totalDy = totalY

        val absDx = kotlin.math.abs(totalDx)
        val absDy = kotlin.math.abs(totalDy)

        // Direction locking once movement exceeds touch slop
        if (!isLockedHorizontal && !isLockedVertical) {
            if (absDx > touchSlopPx && absDx > absDy * 1.25f) {
                isLockedHorizontal = true
                lastTapTimeMs = 0L // invalidate tap candidate
            } else if (absDy > touchSlopPx && absDy > absDx * 1.25f) {
                isLockedVertical = true
                lastTapTimeMs = 0L // invalidate tap candidate
            }
        }

        // Horizontal navigation
        if (isLockedHorizontal || (!isLockedVertical && absDx > absDy * 1.25f)) {
            if (absDx >= thresholdPx) {
                gestureHandled = true
                lastTapTimeMs = 0L
                return if (totalDx < 0f) {
                    FullPlayerGestureAction.SWIPE_LEFT_NEXT
                } else {
                    FullPlayerGestureAction.SWIPE_RIGHT_PREVIOUS
                }
            }
            return FullPlayerGestureAction.NONE
        }

        // Vertical upward navigation (Queue)
        if (isLockedVertical || (!isLockedHorizontal && totalDy < 0f && -totalDy > absDx * 1.25f)) {
            if (totalDy < 0f && -totalDy >= thresholdPx && -totalDy > absDx * 1.25f) {
                gestureHandled = true
                lastTapTimeMs = 0L
                return FullPlayerGestureAction.SWIPE_UP_QUEUE
            }
            return FullPlayerGestureAction.NONE
        }

        return FullPlayerGestureAction.NONE
    }

    /**
     * Checks flick velocity on pointer release.
     */
    fun onRelease(velocityX: Float, velocityY: Float): FullPlayerGestureAction {
        if (gestureHandled) return FullPlayerGestureAction.NONE

        val absDx = kotlin.math.abs(totalDx)
        val absDy = kotlin.math.abs(totalDy)

        // Upward flick
        if (!isLockedHorizontal && totalDy <= -minFlickDistancePx && velocityY <= -flickVelocityPx && -totalDy > absDx * 1.25f) {
            gestureHandled = true
            lastTapTimeMs = 0L
            return FullPlayerGestureAction.SWIPE_UP_QUEUE
        }

        // Horizontal flick
        val absVx = kotlin.math.abs(velocityX)
        if (!isLockedVertical && absDx >= minFlickDistancePx && absVx >= flickVelocityPx && absDx > absDy * 1.25f) {
            gestureHandled = true
            lastTapTimeMs = 0L
            return if (velocityX < 0f) {
                FullPlayerGestureAction.SWIPE_LEFT_NEXT
            } else {
                FullPlayerGestureAction.SWIPE_RIGHT_PREVIOUS
            }
        }

        return FullPlayerGestureAction.NONE
    }

    /**
     * Records a tap that lifted without exceeding touchSlop.
     * Returns DOUBLE_TAP_SEEK_BACKWARD or DOUBLE_TAP_SEEK_FORWARD if this tap completes a double-tap.
     */
    fun onTap(x: Float, y: Float, containerWidth: Float, currentTimeMs: Long): FullPlayerGestureAction {
        val absDx = kotlin.math.abs(totalDx)
        val absDy = kotlin.math.abs(totalDy)
        if (absDx > touchSlopPx || absDy > touchSlopPx || isLockedHorizontal || isLockedVertical || gestureHandled) {
            lastTapTimeMs = 0L
            return FullPlayerGestureAction.NONE
        }

        val midpoint = containerWidth / 2f
        val thisIsRight = x >= midpoint
        val timeSinceLast = currentTimeMs - lastTapTimeMs
        val isWithinWindow = lastTapTimeMs > 0L && timeSinceLast in 1..doubleTapTimeoutMs
        val lastIsRight = lastTapX >= midpoint

        if (isWithinWindow && thisIsRight == lastIsRight) {
            // Double-tap recognized! Reset last tap so subsequent tap starts a new cycle.
            lastTapTimeMs = 0L
            return if (thisIsRight) {
                FullPlayerGestureAction.DOUBLE_TAP_SEEK_FORWARD
            } else {
                FullPlayerGestureAction.DOUBLE_TAP_SEEK_BACKWARD
            }
        } else {
            // First tap recorded
            lastTapTimeMs = currentTimeMs
            lastTapX = x
            lastTapY = y
            return FullPlayerGestureAction.NONE
        }
    }

    fun invalidateTap() {
        lastTapTimeMs = 0L
    }

    fun onGestureEnd() {
        gestureHandled = false
        isLockedHorizontal = false
        isLockedVertical = false
        totalDx = 0f
        totalDy = 0f
    }
}

enum class QueueSwipeDownAction {
    NONE,
    SCROLL_TO_TOP,
    CLOSE_QUEUE,
}

/**
 * Tracks touch movement for Queue downward gestures.
 *
 * Rules enforced:
 * 1. CASE 1 - QUEUE NOT AT TOP:
 *    Deliberate swipe DOWN -> SCROLL_TO_TOP (remain in Queue).
 *    Never closes Queue in this gesture.
 * 2. CASE 2 - QUEUE AT TOP:
 *    Deliberate swipe DOWN -> CLOSE_QUEUE (return to Full Player).
 * 3. NO Peek state, NO intermediate animation.
 * 4. Directionally locked: totalDy > |totalDx| * 1.25. Horizontal movement locks out actions.
 * 5. Small accidental movements (< thresholdPx and low velocity) do nothing.
 * 6. Exactly ONE action per touch stream; once handled, gestureHandled = true.
 */
class QueueSwipeDownTracker(
    val thresholdPx: Float,
    val scrollToTopThresholdPx: Float = thresholdPx,
    val flickVelocityPx: Float = 450f,
    val minFlickDistancePx: Float = 20f,
    val minScrollToTopFlickDistancePx: Float = minFlickDistancePx,
    val touchSlopPx: Float = 24f,
) {
    var gestureHandled: Boolean = false
        private set

    var isLockedHorizontal: Boolean = false
        private set

    var isAtTopAtGestureStart: Boolean = false
        private set

    var totalDx: Float = 0f
        private set

    var totalDy: Float = 0f
        private set

    fun onGestureStart(isAtTop: Boolean) {
        gestureHandled = false
        isLockedHorizontal = false
        isAtTopAtGestureStart = isAtTop
        totalDx = 0f
        totalDy = 0f
    }

    fun onPosition(totalX: Float, totalY: Float): QueueSwipeDownAction {
        if (gestureHandled) return QueueSwipeDownAction.NONE

        totalDx = totalX
        totalDy = totalY

        val absDx = kotlin.math.abs(totalDx)
        val absDy = kotlin.math.abs(totalDy)

        // Direction lock: if horizontal movement exceeds touch slop and dominates vertical, lock horizontal
        if (!isLockedHorizontal && absDx > touchSlopPx && absDx > absDy * 1.25f) {
            isLockedHorizontal = true
        }

        if (isLockedHorizontal) return QueueSwipeDownAction.NONE

        // Upward movement (browsing deeper into queue) never triggers swipe-down actions
        if (totalDy <= 0f) return QueueSwipeDownAction.NONE

        // Check clear deliberate downward movement
        val isDirectionDown = totalDy > absDx * 1.25f
        val neededThreshold = if (isAtTopAtGestureStart) thresholdPx else scrollToTopThresholdPx
        if (totalDy >= neededThreshold && isDirectionDown) {
            gestureHandled = true
            return if (isAtTopAtGestureStart) {
                QueueSwipeDownAction.CLOSE_QUEUE
            } else {
                QueueSwipeDownAction.SCROLL_TO_TOP
            }
        }

        return QueueSwipeDownAction.NONE
    }

    fun onRelease(velocityY: Float): QueueSwipeDownAction {
        if (gestureHandled || isLockedHorizontal) return QueueSwipeDownAction.NONE

        val absDx = kotlin.math.abs(totalDx)
        val isDirectionDown = totalDy > absDx * 1.25f
        val neededDistance = if (isAtTopAtGestureStart) minFlickDistancePx else minScrollToTopFlickDistancePx

        // Check downward flick: positive velocity, sufficient displacement, direction down
        if (totalDy >= neededDistance && velocityY >= flickVelocityPx && isDirectionDown) {
            gestureHandled = true
            return if (isAtTopAtGestureStart) {
                QueueSwipeDownAction.CLOSE_QUEUE
            } else {
                QueueSwipeDownAction.SCROLL_TO_TOP
            }
        }

        return QueueSwipeDownAction.NONE
    }

    fun onGestureEnd() {
        gestureHandled = false
        isLockedHorizontal = false
        totalDx = 0f
        totalDy = 0f
    }
}

enum class LyricsSwipeDownAction {
    NONE,
    SCROLL_TO_TOP,
    CLOSE_LYRICS,
}

/**
 * Tracks touch movement for Lyrics downward gestures.
 *
 * Rules enforced:
 * 1. CASE A - LYRICS NOT AT TOP:
 *    Deliberate swipe DOWN -> SCROLL_TO_TOP (remain in Lyrics).
 *    Never closes Lyrics in this gesture.
 * 2. CASE B - LYRICS AT TOP:
 *    Deliberate swipe DOWN -> CLOSE_LYRICS (return to Full Player).
 * 3. NO Peek state, NO intermediate animation.
 *    Direct scroll to top, direct return to Full Player.
 * 4. Directionally locked: totalDy > |totalDx| * 1.25. Horizontal movement locks out actions.
 * 5. Small accidental movements (< thresholdPx and low velocity) do nothing.
 * 6. Exactly ONE action per touch stream; once handled, gestureHandled = true.
 */
class LyricsSwipeDownTracker(
    val thresholdPx: Float,
    val scrollToTopThresholdPx: Float = thresholdPx,
    val flickVelocityPx: Float = 450f,
    val minFlickDistancePx: Float = 20f,
    val minScrollToTopFlickDistancePx: Float = minFlickDistancePx,
    val touchSlopPx: Float = 24f,
) {
    var gestureHandled: Boolean = false
        private set

    var isLockedHorizontal: Boolean = false
        private set

    var isAtTopAtGestureStart: Boolean = false
        private set

    var totalDx: Float = 0f
        private set

    var totalDy: Float = 0f
        private set

    fun onGestureStart(isAtTop: Boolean) {
        gestureHandled = false
        isLockedHorizontal = false
        isAtTopAtGestureStart = isAtTop
        totalDx = 0f
        totalDy = 0f
    }

    fun onPosition(totalX: Float, totalY: Float): LyricsSwipeDownAction {
        if (gestureHandled) return LyricsSwipeDownAction.NONE

        totalDx = totalX
        totalDy = totalY

        val absDx = kotlin.math.abs(totalDx)
        val absDy = kotlin.math.abs(totalDy)

        // Direction lock: if horizontal movement exceeds touch slop and dominates vertical, lock horizontal
        if (!isLockedHorizontal && absDx > touchSlopPx && absDx > absDy * 1.25f) {
            isLockedHorizontal = true
        }

        if (isLockedHorizontal) return LyricsSwipeDownAction.NONE

        // Upward movement (browsing deeper into lyrics) never triggers swipe-down actions
        if (totalDy <= 0f) return LyricsSwipeDownAction.NONE

        // Check clear deliberate downward movement: totalDy meets threshold and dominates horizontal
        val isDirectionDown = totalDy > absDx * 1.25f
        val neededThreshold = if (isAtTopAtGestureStart) thresholdPx else scrollToTopThresholdPx
        if (totalDy >= neededThreshold && isDirectionDown) {
            gestureHandled = true
            return if (isAtTopAtGestureStart) {
                LyricsSwipeDownAction.CLOSE_LYRICS
            } else {
                LyricsSwipeDownAction.SCROLL_TO_TOP
            }
        }

        return LyricsSwipeDownAction.NONE
    }

    fun onRelease(velocityY: Float): LyricsSwipeDownAction {
        if (gestureHandled || isLockedHorizontal) return LyricsSwipeDownAction.NONE

        val absDx = kotlin.math.abs(totalDx)
        val isDirectionDown = totalDy > absDx * 1.25f
        val neededDistance = if (isAtTopAtGestureStart) minFlickDistancePx else minScrollToTopFlickDistancePx

        // Check downward flick: positive velocity, sufficient displacement, direction down
        if (totalDy >= neededDistance && velocityY >= flickVelocityPx && isDirectionDown) {
            gestureHandled = true
            return if (isAtTopAtGestureStart) {
                LyricsSwipeDownAction.CLOSE_LYRICS
            } else {
                LyricsSwipeDownAction.SCROLL_TO_TOP
            }
        }

        return LyricsSwipeDownAction.NONE
    }

    fun onGestureEnd() {
        gestureHandled = false
        isLockedHorizontal = false
        totalDx = 0f
        totalDy = 0f
    }
}

/**
 * Detects coordinated gestures on the Full Player:
 * 1. Double-tap seek:
 *    - Left half (< width / 2) -> seek backward
 *    - Right half (>= width / 2) -> seek forward
 * 2. Horizontal swipe:
 *    - Left swipe (dx < 0) -> next track
 *    - Right swipe (dx > 0) -> previous track
 * 3. Vertical swipe up:
 *    - Upward swipe (-dy > 0) -> open Queue
 *
 * Requirements enforced:
 * - Directionally locked: horizontal dominant -> song change, vertical dominant up -> Queue.
 * - Tap vs Swipe: swipes exceed threshold, double tap requires two taps within doubleTapTimeoutMs with movement <= touchSlop.
 * - Single tap does not seek or change track.
 * - Child interactive controls consume pointer events on PointerEventPass.Main; if consumed, detector aborts.
 * - Downward movement is NOT consumed, so the parent sheet / window can dismiss the player.
 */
fun Modifier.fullPlayerGestures(
    enabled: Boolean,
    onSwipeUp: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onDoubleTapSeek: (isForward: Boolean) -> Unit,
    onHorizontalDragOffset: ((Float) -> Unit)? = null,
): Modifier {
    if (!enabled) return this

    return this.pointerInput(enabled) {
        val thresholdPx = 48.dp.toPx()
        val flickVelocityPx = 400.dp.toPx()
        val minFlickDistancePx = 20.dp.toPx()
        val touchSlopPx = viewConfiguration.touchSlop

        val tracker = FullPlayerGestureTracker(
            thresholdPx = thresholdPx,
            flickVelocityPx = flickVelocityPx,
            minFlickDistancePx = minFlickDistancePx,
            touchSlopPx = touchSlopPx,
            doubleTapTimeoutMs = 350L,
        )

        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val startPos = down.position
            val pointerId = down.id
            val velocityTracker = VelocityTracker()
            velocityTracker.addPosition(down.uptimeMillis, down.position)
            tracker.onGestureEnd()

            var wasConsumedByChild = false
            var completedAction = FullPlayerGestureAction.NONE

            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Main)
                val change = event.changes.firstOrNull { it.id == pointerId } ?: break

                // If a child control consumed position changes, abort
                if (change.isConsumed && !tracker.gestureHandled) {
                    wasConsumedByChild = true
                    tracker.invalidateTap()
                    onHorizontalDragOffset?.invoke(0f)
                    break
                }

                if (!change.pressed) {
                    // Finger lifted
                    val velocity = velocityTracker.calculateVelocity()
                    val flickAction = tracker.onRelease(velocity.x, velocity.y)
                    if (flickAction != FullPlayerGestureAction.NONE) {
                        completedAction = flickAction
                        change.consume()
                    } else if (!tracker.gestureHandled) {
                        val tapAction = tracker.onTap(
                            x = change.position.x,
                            y = change.position.y,
                            containerWidth = size.width.toFloat(),
                            currentTimeMs = System.currentTimeMillis(),
                        )
                        if (tapAction != FullPlayerGestureAction.NONE) {
                            completedAction = tapAction
                            change.consume()
                        }
                    }
                    onHorizontalDragOffset?.invoke(0f)
                    break
                }

                velocityTracker.addPosition(change.uptimeMillis, change.position)
                val totalDx = change.position.x - startPos.x
                val totalDy = change.position.y - startPos.y

                if (!tracker.isLockedVertical && kotlin.math.abs(totalDx) > touchSlopPx) {
                    onHorizontalDragOffset?.invoke(totalDx)
                }

                val action = tracker.onPosition(totalDx, totalDy)
                if (action != FullPlayerGestureAction.NONE) {
                    completedAction = action
                    change.consume()
                    onHorizontalDragOffset?.invoke(0f)
                    while (true) {
                        val nextEvent = awaitPointerEvent(PointerEventPass.Main)
                        val nextChange = nextEvent.changes.firstOrNull { it.id == pointerId } ?: break
                        nextChange.consume()
                        if (!nextChange.pressed) break
                    }
                    break
                }
            }

            if (!wasConsumedByChild) {
                when (completedAction) {
                    FullPlayerGestureAction.SWIPE_UP_QUEUE -> onSwipeUp()
                    FullPlayerGestureAction.SWIPE_LEFT_NEXT -> onNext()
                    FullPlayerGestureAction.SWIPE_RIGHT_PREVIOUS -> onPrevious()
                    FullPlayerGestureAction.DOUBLE_TAP_SEEK_BACKWARD -> onDoubleTapSeek(false)
                    FullPlayerGestureAction.DOUBLE_TAP_SEEK_FORWARD -> onDoubleTapSeek(true)
                    FullPlayerGestureAction.NONE -> Unit
                }
            }
        }
    }
}

/**
 * Detects deliberate downward swipe on the Queue.
 *
 * Rules:
 * - CASE 1: Queue is NOT at top
 *   -> scrolls list directly to top, remains in Queue.
 * - CASE 2: Queue IS at top (or header/strip)
 *   -> closes Queue and returns to Full Player.
 */
fun Modifier.queueSwipeDown(
    enabled: Boolean,
    listState: LazyListState? = null,
    scope: CoroutineScope? = null,
    isReordering: Boolean = false,
    allowScrollToTop: Boolean = true,
    consumeDownDeltas: Boolean = false,
    onClose: () -> Unit,
): Modifier {
    if (!enabled) return this

    return this.pointerInput(enabled, isReordering, allowScrollToTop, consumeDownDeltas) {
        val thresholdPx = 48.dp.toPx()
        val scrollToTopThresholdPx = 180.dp.toPx()
        val flickVelocityPx = 450.dp.toPx()
        val minFlickDistancePx = 20.dp.toPx()
        val minScrollToTopFlickDistancePx = 120.dp.toPx()
        val touchSlopPx = viewConfiguration.touchSlop

        val tracker = QueueSwipeDownTracker(
            thresholdPx = thresholdPx,
            scrollToTopThresholdPx = scrollToTopThresholdPx,
            flickVelocityPx = flickVelocityPx,
            minFlickDistancePx = minFlickDistancePx,
            minScrollToTopFlickDistancePx = minScrollToTopFlickDistancePx,
            touchSlopPx = touchSlopPx,
        )

        awaitEachGesture {
            if (isReordering) return@awaitEachGesture
            val down = awaitFirstDown(requireUnconsumed = false)
            val startPos = down.position
            val pointerId = down.id
            val velocityTracker = VelocityTracker()
            velocityTracker.addPosition(down.uptimeMillis, down.position)

            val isAtTop = listState == null ||
                !listState.canScrollBackward ||
                (listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset <= 4)

            if (!isAtTop && !allowScrollToTop && !consumeDownDeltas) {
                return@awaitEachGesture
            }

            tracker.onGestureStart(isAtTop)

            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == pointerId } ?: break

                if (isReordering) break

                val totalDx = change.position.x - startPos.x
                val totalDy = change.position.y - startPos.y

                if (totalDy < -touchSlopPx && !consumeDownDeltas) break

                velocityTracker.addPosition(change.uptimeMillis, change.position)

                if (!change.pressed) {
                    val velocity = velocityTracker.calculateVelocity()
                    val rawAction = tracker.onRelease(velocity.y)
                    val action = if (!allowScrollToTop && rawAction == QueueSwipeDownAction.SCROLL_TO_TOP) {
                        QueueSwipeDownAction.NONE
                    } else rawAction
                    when (action) {
                        QueueSwipeDownAction.CLOSE_QUEUE -> {
                            change.consume()
                            onClose()
                        }
                        QueueSwipeDownAction.SCROLL_TO_TOP -> {
                            change.consume()
                            if (listState != null && scope != null) {
                                scope.launch { listState.animateScrollToItem(0) }
                            }
                        }
                        QueueSwipeDownAction.NONE -> {
                            if (consumeDownDeltas && totalDy > touchSlopPx && totalDy > kotlin.math.abs(totalDx) * 1.25f) {
                                change.consume()
                            }
                        }
                    }
                    break
                }

                val rawAction = tracker.onPosition(totalDx, totalDy)
                val action = if (!allowScrollToTop && rawAction == QueueSwipeDownAction.SCROLL_TO_TOP) {
                    QueueSwipeDownAction.NONE
                } else rawAction
                when (action) {
                    QueueSwipeDownAction.CLOSE_QUEUE -> {
                        change.consume()
                        onClose()
                        while (true) {
                            val nextEvent = awaitPointerEvent(PointerEventPass.Initial)
                            val nextChange = nextEvent.changes.firstOrNull { it.id == pointerId } ?: break
                            nextChange.consume()
                            if (!nextChange.pressed) break
                        }
                        break
                    }
                    QueueSwipeDownAction.SCROLL_TO_TOP -> {
                        val currentVelocity = velocityTracker.calculateVelocity()
                        if (!consumeDownDeltas && currentVelocity.y < 400.dp.toPx() && change.pressed) {
                            // User is slowly dragging down to browse — let LazyColumn scroll naturally!
                        } else {
                            change.consume()
                            if (listState != null && scope != null) {
                                scope.launch { listState.animateScrollToItem(0) }
                            }
                            while (true) {
                                val nextEvent = awaitPointerEvent(PointerEventPass.Initial)
                                val nextChange = nextEvent.changes.firstOrNull { it.id == pointerId } ?: break
                                nextChange.consume()
                                if (!nextChange.pressed) break
                            }
                            break
                        }
                    }
                    QueueSwipeDownAction.NONE -> {
                        if (consumeDownDeltas && totalDy > touchSlopPx && totalDy > kotlin.math.abs(totalDx) * 1.25f) {
                            change.consume()
                        }
                    }
                }
            }
        }
    }
}

/**
 * Detects deliberate downward swipe on the Lyrics panel or top handle.
 */
fun Modifier.lyricsSwipeDown(
    enabled: Boolean,
    listState: LazyListState? = null,
    scope: CoroutineScope? = null,
    allowScrollToTop: Boolean = true,
    onScrolledToTop: () -> Unit = {},
    onClose: () -> Unit,
): Modifier {
    if (!enabled) return this

    return this.pointerInput(enabled, allowScrollToTop) {
        val thresholdPx = 48.dp.toPx()
        val scrollToTopThresholdPx = 180.dp.toPx()
        val flickVelocityPx = 450.dp.toPx()
        val minFlickDistancePx = 20.dp.toPx()
        val minScrollToTopFlickDistancePx = 120.dp.toPx()
        val touchSlopPx = viewConfiguration.touchSlop

        val tracker = LyricsSwipeDownTracker(
            thresholdPx = thresholdPx,
            scrollToTopThresholdPx = scrollToTopThresholdPx,
            flickVelocityPx = flickVelocityPx,
            minFlickDistancePx = minFlickDistancePx,
            minScrollToTopFlickDistancePx = minScrollToTopFlickDistancePx,
            touchSlopPx = touchSlopPx,
        )

        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val startPos = down.position
            val pointerId = down.id
            val velocityTracker = VelocityTracker()
            velocityTracker.addPosition(down.uptimeMillis, down.position)

            val isAtTop = listState == null ||
                !listState.canScrollBackward ||
                (listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset <= 4)
            tracker.onGestureStart(isAtTop)

            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == pointerId } ?: break

                if (!change.pressed) {
                    val velocity = velocityTracker.calculateVelocity()
                    val rawAction = tracker.onRelease(velocity.y)
                    val action = if (!allowScrollToTop && rawAction == LyricsSwipeDownAction.SCROLL_TO_TOP) {
                        LyricsSwipeDownAction.NONE
                    } else rawAction
                    when (action) {
                        LyricsSwipeDownAction.CLOSE_LYRICS -> {
                            change.consume()
                            onClose()
                        }
                        LyricsSwipeDownAction.SCROLL_TO_TOP -> {
                            change.consume()
                            onScrolledToTop()
                            if (listState != null && scope != null) {
                                scope.launch { listState.animateScrollToItem(0) }
                            }
                        }
                        LyricsSwipeDownAction.NONE -> Unit
                    }
                    break
                }

                velocityTracker.addPosition(change.uptimeMillis, change.position)
                val totalDx = change.position.x - startPos.x
                val totalDy = change.position.y - startPos.y

                val rawAction = tracker.onPosition(totalDx, totalDy)
                val action = if (!allowScrollToTop && rawAction == LyricsSwipeDownAction.SCROLL_TO_TOP) {
                    LyricsSwipeDownAction.NONE
                } else rawAction
                when (action) {
                    LyricsSwipeDownAction.CLOSE_LYRICS -> {
                        change.consume()
                        onClose()
                        while (true) {
                            val nextEvent = awaitPointerEvent(PointerEventPass.Initial)
                            val nextChange = nextEvent.changes.firstOrNull { it.id == pointerId } ?: break
                            nextChange.consume()
                            if (!nextChange.pressed) break
                        }
                        break
                    }
                    LyricsSwipeDownAction.SCROLL_TO_TOP -> {
                        change.consume()
                        onScrolledToTop()
                        if (listState != null && scope != null) {
                            scope.launch { listState.animateScrollToItem(0) }
                        }
                        while (true) {
                            val nextEvent = awaitPointerEvent(PointerEventPass.Initial)
                            val nextChange = nextEvent.changes.firstOrNull { it.id == pointerId } ?: break
                            nextChange.consume()
                            if (!nextChange.pressed) break
                        }
                        break
                    }
                    LyricsSwipeDownAction.NONE -> Unit
                }
            }
        }
    }
}
