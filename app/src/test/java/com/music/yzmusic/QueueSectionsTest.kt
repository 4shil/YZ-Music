package com.music.yzmusic

import com.music.yzmusic.playback.autoplaySectionStart
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Where the queue's AutoPlay section begins — the index the heading is drawn
 * at, and the one a track queued by hand is inserted at.
 */
class QueueSectionsTest {

    /** `.` is a track the user queued, `~` one AutoPlay did. */
    private fun start(queue: String, currentIndex: Int) =
        autoplaySectionStart(queue.map { it == '~' }, currentIndex)

    @Test
    fun `the section starts where AutoPlay's tracks do`() {
        assertEquals(3, start("...~~~", currentIndex = 0))
    }

    @Test
    fun `a queue with nothing from AutoPlay has the section at its end`() {
        assertEquals(4, start("....", currentIndex = 1))
    }

    @Test
    fun `AutoPlay tracks already played sit above the section, not in it`() {
        // Playing the third of the mix: the two behind it have had their turn.
        assertEquals(5, start("..~~~~", currentIndex = 4))
    }

    @Test
    fun `the section closes the queue once the mix is on its last track`() {
        assertEquals(4, start("..~~", currentIndex = 3))
    }

    @Test
