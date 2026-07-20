package com.music.yzmusic

import com.music.yzmusic.playback.QueueShuffle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reordering behind the shuffle toggle. Playing the queue is ExoPlayer's
 * job; getting the queue into the order it should play in is this one's.
 */
class QueueShuffleTest {

    /** Runs the moves the way [androidx.media3.common.Player] would. */
    private fun applied(current: List<String>, from: Int, target: List<String>): List<String> {
        val ids = current.toMutableList()
        QueueShuffle.moves(current, from, target).forEach { (at, to) ->
            ids.add(to, ids.removeAt(at))
        }
        return ids
    }

    @Test
    fun `the queue ends up in the order asked for`() {
