package com.music.yzmusic

import com.music.yzmusic.ui.player.adjustedLyricsPosition
import com.music.yzmusic.ui.player.adjustedLyricsSeekTarget
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The offset moves the lyrics and nothing else. These pin the one pairing that
 * makes that true: the drawing is shifted one way and the seek target the
 * other, so a line drawn at some moment is still a line that can be tapped to
 * hear that moment. Break the pairing and the two stop agreeing — the panel
 * would light a line and then send the player somewhere else.
 */
class LyricsOffsetTest {

    @Test
    fun `no offset leaves both the drawing and the seek target alone`() {
        assertEquals(30_000L, adjustedLyricsPosition(30_000L, 0))
        assertEquals(30_000L, adjustedLyricsSeekTarget(30_000L, 0))
    }

    @Test
    fun `a positive offset draws the lyrics earlier by exactly that much`() {
        assertEquals(29_500L, adjustedLyricsPosition(30_000L, 500))
        assertEquals(30_500L, adjustedLyricsPosition(30_000L, -500))
    }
    @Test
    fun `tapping a drawn line seeks to where the player actually is for that line`() {
        // A line stamped at 30s with a +500 offset is *drawn* when the player
        // reports 30.5s — the panel shows it early — so tapping it has to take
        // the player to 30.5s, which is that same instant, not the raw stamp.
        val lineTimeMs = 30_000L
        val offsetMs = 500
        assertEquals(30_500L, adjustedLyricsSeekTarget(lineTimeMs, offsetMs))
    }

    @Test
    fun `the seek a tapped line takes is the position that draws it`() {
        // Well clear of the start of the track, so the clamp at zero is not what
        // is under test here — that has its own case below.
        for (lineTimeMs in listOf(2_000L, 45_000L, 600_000L)) {
            for (offsetMs in listOf(-1_500, -100, 0, 100, 1_500)) {
                // Seek from a line's stamp, feed that back in as a player
                // position, and the panel must decide the line is playing. If
                // the two ever drifted apart, a tapped line would go somewhere
                // other than the moment it was tapped at.
                val seekedTo = adjustedLyricsSeekTarget(lineTimeMs, offsetMs)
                assertEquals(lineTimeMs, adjustedLyricsPosition(seekedTo, offsetMs))
            }
        }
    }

    @Test
    fun `nothing is allowed before the start of the track`() {
        assertEquals(0L, adjustedLyricsPosition(200L, 500))
        assertEquals(0L, adjustedLyricsSeekTarget(100L, -500))
    }
}
