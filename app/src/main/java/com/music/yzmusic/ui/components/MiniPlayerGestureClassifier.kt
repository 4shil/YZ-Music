package com.music.yzmusic.ui.components

import kotlin.math.abs
import kotlin.math.hypot

enum class MiniPlayerAction {
    NONE,
    NEXT,
    PREVIOUS,
    EXPAND,
}

enum class DirectionLock {
    NONE,
    HORIZONTAL,
    VERTICAL,
}

/**
 * Classifies gestures on the Mini Player with strict direction locking.
 *
 * Once a gesture crosses touch slop, it is classified as either [DirectionLock.HORIZONTAL]
 * or [DirectionLock.VERTICAL] and locked for the entire duration of the touch stream.
 * Diagonal movements are dominated by the larger component and never trigger both actions.
 */
class MiniPlayerGestureClassifier(
    val touchSlopPx: Float,
    val minDisplacementPx: Float,
    val minVelocityPx: Float,
) {
    var lock: DirectionLock = DirectionLock.NONE
        private set

    /**
     * Updates pointer displacement and locks direction once [touchSlopPx] is exceeded.
     */
    fun onMove(totalX: Float, totalY: Float): DirectionLock {
        if (lock == DirectionLock.NONE) {
            val dist = hypot(totalX, totalY)
            if (dist > touchSlopPx) {
                lock = if (abs(totalX) > abs(totalY)) {
                    DirectionLock.HORIZONTAL
                } else {
                    DirectionLock.VERTICAL
                }
            }
        }
        return lock
    }

    /**
     * Resolves the gesture action on release and resets direction lock.
     */
    fun onRelease(totalX: Float, totalY: Float, velocityX: Float, velocityY: Float): MiniPlayerAction {
        val currentLock = lock
        lock = DirectionLock.NONE
        return when (currentLock) {
            DirectionLock.HORIZONTAL -> {
                val exceedsDisplacement = abs(totalX) >= minDisplacementPx
                val exceedsVelocity = abs(velocityX) >= minVelocityPx
                if (exceedsDisplacement || exceedsVelocity) {
                    if (totalX < 0 || velocityX < -minVelocityPx) {
                        MiniPlayerAction.NEXT
                    } else if (totalX > 0 || velocityX > minVelocityPx) {
                        MiniPlayerAction.PREVIOUS
                    } else {
                        MiniPlayerAction.NONE
                    }
                } else {
                    MiniPlayerAction.NONE
                }
            }
            DirectionLock.VERTICAL -> {
                val exceedsDisplacement = abs(totalY) >= minDisplacementPx
                val exceedsVelocity = abs(velocityY) >= minVelocityPx
                if ((exceedsDisplacement || exceedsVelocity) && (totalY < 0 || velocityY < -minVelocityPx)) {
                    MiniPlayerAction.EXPAND
                } else {
                    MiniPlayerAction.NONE
                }
            }
            DirectionLock.NONE -> MiniPlayerAction.NONE
        }
    }

    fun reset() {
        lock = DirectionLock.NONE
    }
}
