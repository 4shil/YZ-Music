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
