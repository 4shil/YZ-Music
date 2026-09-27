package com.music.yzmusic.playback

import com.music.yzmusic.data.listentogether.PartyPlayback
import com.music.yzmusic.data.listentogether.PartyTrack
import com.music.yzmusic.data.model.Song
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PartyQueueMoveTest {

    @Test
    fun partyTrackKeepsAutoplaySectionMetadataOnTheWire() {
        val encoded = Json.encodeToString(PartyTrack.serializer(), PartyTrack("A", fromAutoplay = true))
        val decoded = Json.decodeFromString(PartyTrack.serializer(), encoded)

        assertEquals(true, decoded.fromAutoplay)
    }

    @Test
    fun testDetectSingleMoveForward() {
        val oldList = listOf("A", "B", "C", "D")
        // Move "B" (index 1) to index 3 -> "A", "C", "D", "B"
        val newList = listOf("A", "C", "D", "B")
        val delta = detectSingleMove(oldList, newList)

        assertNotNull(delta)
        assertEquals(1, delta?.fromIndex)
        assertEquals(3, delta?.toIndex)
        assertEquals("B", delta?.videoId)
    }

    @Test
    fun testDetectSingleMoveBackward() {
        val oldList = listOf("A", "C", "D", "B")
        // Move "B" (index 3) to index 1 -> "A", "B", "C", "D"
        val newList = listOf("A", "B", "C", "D")
        val delta = detectSingleMove(oldList, newList)

        assertNotNull(delta)
        assertEquals(3, delta?.fromIndex)
        assertEquals(1, delta?.toIndex)
        assertEquals("B", delta?.videoId)
    }

    @Test
    fun testDetectSingleMoveWithBaseOffset() {
        val oldList = listOf("B", "C", "D")
        // Move "D" (local index 2) to local index 0 -> "D", "B", "C"
        val newList = listOf("D", "B", "C")
        val delta = detectSingleMove(oldList, newList, baseOffset = 5)

        assertNotNull(delta)
        assertEquals(7, delta?.fromIndex)
        assertEquals(5, delta?.toIndex)
        assertEquals("D", delta?.videoId)
    }

    @Test
    fun testIdenticalListsReturnNull() {
        val list = listOf("A", "B", "C")
        assertNull(detectSingleMove(list, list))
    }

    @Test
    fun testDifferentSizesReturnNull() {
        val oldList = listOf("A", "B")
        val newList = listOf("A", "B", "C")
        assertNull(detectSingleMove(oldList, newList))
    }

    @Test
    fun testMultipleSwapsReturnNull() {
        val oldList = listOf("A", "B", "C", "D")
        // Two independent swaps: (A, B) and (C, D) -> "B", "A", "D", "C"
        val newList = listOf("B", "A", "D", "C")
        assertNull(detectSingleMove(oldList, newList))
    }

    @Test
    fun testDuplicatesInList() {
        val oldList = listOf("A", "B", "A", "C")
        val newList = listOf("A", "A", "B", "C")
        val delta = detectSingleMove(oldList, newList)

        assertNotNull(delta)
        val reconstructed = oldList.toMutableList().apply {
            val item = removeAt(delta!!.fromIndex)
            add(delta.toIndex, item)
        }
        assertEquals(newList, reconstructed)
    }

    @Test
    fun testPartyPlaybackSerializationPreservesSeparateSequences() {
        val playback = PartyPlayback(
            seq = 10,
            queueSeq = 25,
            queueIndex = 2,
            isPlaying = true,
            positionMs = 5000L,
            anchorMs = 100000L,
        )
        val encoded = Json.encodeToString(PartyPlayback.serializer(), playback)
        val decoded = Json.decodeFromString(PartyPlayback.serializer(), encoded)

        assertEquals(10L, decoded.seq)
        assertEquals(25L, decoded.queueSeq)
        assertEquals(true, decoded.isPlaying)
        assertEquals(5000L, decoded.positionMs)
        assertEquals(100000L, decoded.anchorMs)
    }

    @Test
    fun testQueueOperationDoesNotAdvancePlaybackSeqContract() {
        val initialPlayback = PartyPlayback(
            seq = 5,
            queueSeq = 1,
            isPlaying = true,
            positionMs = 12000L,
            anchorMs = 50000L,
        )
        val updatedPlayback = initialPlayback.copy(
            queueSeq = initialPlayback.queueSeq + 1,
        )

        assertEquals(5L, updatedPlayback.seq)
        assertEquals(2L, updatedPlayback.queueSeq)
        assertEquals(initialPlayback.anchorMs, updatedPlayback.anchorMs)
        assertEquals(initialPlayback.positionMs, updatedPlayback.positionMs)
    }

    @Test
    fun testPartyTrackToSongMapsTiersCorrectly() {
        val manualTrack = PartyTrack("manual", title = "Manual", fromAutoplay = false)
        val autoTrack = PartyTrack("auto", title = "Auto", fromAutoplay = true)

        val manualSong = manualTrack.toSong()
        val autoSong = autoTrack.toSong()

        assertEquals(false, manualSong.fromAutoplay)
        assertEquals(true, autoSong.fromAutoplay)
    }

    @Test
    fun testSongToPartyTrackMapsAutoplayFlagCorrectly() {
        val autoSong = Song(videoId = "1", title = "1", artist = "Artist 1", thumbnailUrl = null, fromAutoplay = true)
        val userSong = Song(videoId = "2", title = "2", artist = "Artist 2", thumbnailUrl = null, fromAutoplay = false)

        assertEquals(true, autoSong.toPartyTrack(1000L).fromAutoplay)
        assertEquals(false, userSong.toPartyTrack(1000L).fromAutoplay)
    }
}
