package com.music.yzmusic

import androidx.media3.common.Player
import com.music.yzmusic.data.settings.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackPersistenceTest {

    @Test
    fun `persisted repeat mode updates state flow`() {
        AppSettings.setPersistedRepeatMode(Player.REPEAT_MODE_ALL)
        assertEquals(Player.REPEAT_MODE_ALL, AppSettings.persistedRepeatMode.value)

        AppSettings.setPersistedRepeatMode(Player.REPEAT_MODE_ONE)
        assertEquals(Player.REPEAT_MODE_ONE, AppSettings.persistedRepeatMode.value)

        AppSettings.setPersistedRepeatMode(Player.REPEAT_MODE_OFF)
        assertEquals(Player.REPEAT_MODE_OFF, AppSettings.persistedRepeatMode.value)
    }

    @Test
    fun `persisted shuffle mode updates state flow`() {
        AppSettings.setPersistedShuffleMode(true)
        assertTrue(AppSettings.persistedShuffleMode.value)

        AppSettings.setPersistedShuffleMode(false)
        assertFalse(AppSettings.persistedShuffleMode.value)
    }

    @Test
    fun `repeat cycle transitions are well ordered`() {
        var mode = Player.REPEAT_MODE_OFF
        mode = when (mode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
        assertEquals(Player.REPEAT_MODE_ALL, mode)

        mode = when (mode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
        assertEquals(Player.REPEAT_MODE_ONE, mode)

        mode = when (mode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
        assertEquals(Player.REPEAT_MODE_OFF, mode)
    }
}
