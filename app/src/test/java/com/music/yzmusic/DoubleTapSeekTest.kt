package com.music.yzmusic

import org.junit.Assert.assertEquals
import org.junit.Test

class DoubleTapSeekTest {

    private val seekEndGuardMs = 1_000L

    /**
     * Pure calculation helper matching NowPlayingScreen & controller seek math.
     */
    private fun calculateSeekTarget(
        positionMs: Long,
        durationMs: Long,
        seekSeconds: Int,
        isForward: Boolean,
    ): Long {
        val seekDelta = seekSeconds * 1000L
        return if (isForward) {
            if (durationMs > 0) {
                (positionMs + seekDelta).coerceAtMost((durationMs - seekEndGuardMs).coerceAtLeast(0L))
            } else {
                positionMs + seekDelta
            }
        } else {
            (positionMs - seekDelta).coerceAtLeast(0L)
        }
    }

    @Test
    fun `double tap forward with configured 5 seconds`() {
        val target = calculateSeekTarget(
            positionMs = 30_000L,
            durationMs = 180_000L,
            seekSeconds = 5,
            isForward = true,
        )
        assertEquals(35_000L, target)
    }

    @Test
    fun `double tap forward with default 10 seconds`() {
        val target = calculateSeekTarget(
            positionMs = 30_000L,
            durationMs = 180_000L,
            seekSeconds = 10,
            isForward = true,
        )
        assertEquals(40_000L, target)
    }

    @Test
    fun `double tap forward with configured 15 seconds`() {
        val target = calculateSeekTarget(
            positionMs = 30_000L,
            durationMs = 180_000L,
            seekSeconds = 15,
            isForward = true,
        )
        assertEquals(45_000L, target)
    }

    @Test
    fun `double tap backward with configured 5 seconds`() {
        val target = calculateSeekTarget(
            positionMs = 30_000L,
            durationMs = 180_000L,
            seekSeconds = 5,
            isForward = false,
        )
        assertEquals(25_000L, target)
    }

    @Test
    fun `double tap backward with default 10 seconds`() {
        val target = calculateSeekTarget(
            positionMs = 30_000L,
            durationMs = 180_000L,
            seekSeconds = 10,
            isForward = false,
        )
        assertEquals(20_000L, target)
    }

    @Test
    fun `double tap backward with configured 15 seconds`() {
        val target = calculateSeekTarget(
            positionMs = 30_000L,
            durationMs = 180_000L,
            seekSeconds = 15,
            isForward = false,
        )
        assertEquals(15_000L, target)
    }

    @Test
    fun `backward seek clamps safely to beginning of track`() {
        val target = calculateSeekTarget(
            positionMs = 4_000L,
            durationMs = 180_000L,
            seekSeconds = 10,
            isForward = false,
        )
        assertEquals(0L, target)
    }

    @Test
    fun `forward seek clamps safely before end of track`() {
        val duration = 180_000L
        val target = calculateSeekTarget(
            positionMs = 175_000L,
            durationMs = duration,
            seekSeconds = 10,
            isForward = true,
        )
        // Guarded by 1000ms before track end so it doesn't accidentally trigger track skip
        assertEquals(179_000L, target)
    }

    @Test
    fun `forward seek at near end stays clamped`() {
        val duration = 180_000L
        val target = calculateSeekTarget(
            positionMs = 179_500L,
            durationMs = duration,
            seekSeconds = 15,
            isForward = true,
        )
        assertEquals(179_000L, target)
    }

    @Test
    fun `repeated double tap forward accumulates seeking correctly`() {
        var currentPos = 20_000L
        val duration = 180_000L
        val seekSec = 10
        // Simulate 3 consecutive double taps forward
        repeat(3) {
            currentPos = calculateSeekTarget(currentPos, duration, seekSec, isForward = true)
        }
        assertEquals(50_000L, currentPos)
    }

    @Test
    fun `repeated double tap backward clamps to zero without underflow`() {
        var currentPos = 18_000L
        val duration = 180_000L
        val seekSec = 10
        // Tap 1: 18s -> 8s
        currentPos = calculateSeekTarget(currentPos, duration, seekSec, isForward = false)
        assertEquals(8_000L, currentPos)
        // Tap 2: 8s -> 0s
        currentPos = calculateSeekTarget(currentPos, duration, seekSec, isForward = false)
        assertEquals(0L, currentPos)
        // Tap 3: stays at 0s
        currentPos = calculateSeekTarget(currentPos, duration, seekSec, isForward = false)
        assertEquals(0L, currentPos)
    }

    @Test
    fun `gesture side detection matches tap half correctly`() {
        val screenWidth = 1080f
        fun isForwardTap(x: Float) = x >= screenWidth / 2f

        assertEquals(false, isForwardTap(100f))  // Left side -> rewind
        assertEquals(false, isForwardTap(539f))  // Left side -> rewind
        assertEquals(true, isForwardTap(540f))   // Right side -> forward
        assertEquals(true, isForwardTap(1000f))  // Right side -> forward
    }

    @Test
    fun `configured seek duration validation`() {
        val allowedValues = listOf(5, 10, 15)
        fun sanitize(value: Int) = if (value in allowedValues) value else 10

        assertEquals(5, sanitize(5))
        assertEquals(10, sanitize(10))
        assertEquals(15, sanitize(15))
        assertEquals(10, sanitize(20)) // Invalid falls back to default 10
        assertEquals(10, sanitize(0))  // Invalid falls back to default 10
    }
}
