package com.music.yzmusic

import com.music.yzmusic.data.model.Song
import com.music.yzmusic.playback.QueueBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueBuilderTest {

    private fun song(id: String, title: String, artist: String = "Arijit Singh") =
        Song(videoId = id, title = title, artist = artist, thumbnailUrl = null)

    private fun video(id: String, title: String, artist: String = "Arijit Singh") =
        song(id, title, artist).copy(isVideo = true)

    @Test
    fun `the music video cut of a mix is left out`() {
        val extra = QueueBuilder.extend(
            existing = emptyList(),
            candidates = listOf(
                song("aaa", "Dildaara (Stand By Me)", "Vishal-Shekhar"),
                video("bbb", "Lyrical Video: Dildara Song", "Shafqat Amanat Ali"),
                song("ccc", "Sajni"),
            ),
            limit = 10,
        )
        assertEquals(listOf("aaa", "ccc"), extra.map { it.videoId })
    }

    @Test
    fun `a mix of nothing but videos still plays`() {
