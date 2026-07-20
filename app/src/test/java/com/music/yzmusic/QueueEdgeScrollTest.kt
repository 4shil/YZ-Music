package com.music.yzmusic

import com.music.yzmusic.ui.player.edgeScrollSpeed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How fast the queue list carries itself along under a row held at one of its
 * edges, so a track can be dragged across a queue longer than the screen.
 */
class QueueEdgeScrollTest {

    /** A 600px viewport, a 100px row, and a 50px zone at each end of it. */
    private fun speed(top: Float, viewportStart: Int = 0, viewportEnd: Int = 600) =
        edgeScrollSpeed(
            top = top,
            bottom = top + 100f,
            viewportStart = viewportStart,
            viewportEnd = viewportEnd,
            zone = 50f,
            speed = 1000f,
        )

    @Test
    fun `a row in the middle of the list scrolls it neither way`() {
        assertEquals(0f, speed(top = 250f), 0f)
    }

    @Test
    fun `a row clear of the zone by a single pixel still scrolls nothing`() {
        assertEquals(0f, speed(top = 51f), 0f)
        assertEquals(0f, speed(top = 449f), 0f)
    }

    @Test
    fun `reaching the top of the list scrolls back towards the start`() {
        assertTrue(speed(top = 40f) < 0f)
    }

    @Test
