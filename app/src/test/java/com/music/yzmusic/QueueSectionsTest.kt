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
