package com.music.yzmusic

import com.music.yzmusic.data.listentogether.JamInviteLink
import com.music.yzmusic.data.listentogether.ListenTogether
import com.music.yzmusic.data.listentogether.PartyMember
import com.music.yzmusic.data.listentogether.PartyMembership
import com.music.yzmusic.data.listentogether.PartyPlayback
import com.music.yzmusic.data.listentogether.PartyQueue
import com.music.yzmusic.data.listentogether.PartySnapshot
import com.music.yzmusic.data.listentogether.PartyTrack
import com.music.yzmusic.data.listentogether.ServerClock
import com.music.yzmusic.data.model.Song
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ListenTogetherTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    @Test
    fun `party models serialization and deserialization`() {
        val track = PartyTrack(
            videoId = "vid_12345",
            title = "Starlight",
            artist = "Muse",
            thumbnailUrl = "https://example.com/thumb.jpg",
            durationMs = 240000L
        )

        val member = PartyMember(
            memberId = "mem_1",
            userId = "user_abc",
            displayName = "Alice",
            avatarUrl = "https://example.com/avatar.jpg",
            isHost = true,
            connected = true,
            joinedAtMs = 1000L,
            lastSeenMs = 1500L
        )

        val playback = PartyPlayback(
            seq = 10L,
            track = track,
            queueSeq = 2L,
            queueLength = 5,
            queueIndex = 0,
            isPlaying = true,
            positionMs = 45000L,
            anchorMs = 50000L,
            effectivePositionMs = 45000L,
            updatedBy = "mem_1",
            startedBy = "mem_1",
            startedByName = "Alice",
            updatedAtMs = 50000L
        )

        val queue = PartyQueue(
            seq = 2L,
            index = 0,
            items = listOf(track)
        )

        val snapshot = PartySnapshot(
            code = "PARTY1",
            createdAtMs = 500L,
            maxMembers = 5,
            members = listOf(member),
            playback = playback,
            queue = queue,
            serverMs = 50000L
        )

        val membership = PartyMembership(
            code = "PARTY1",
            token = "secret_token_123",
            you = member,
            party = snapshot,
            serverMs = 50000L
        )

        val encoded = json.encodeToString(membership)
        val decoded = json.decodeFromString<PartyMembership>(encoded)

        assertEquals("PARTY1", decoded.code)
        assertEquals("secret_token_123", decoded.token)
        assertEquals("Alice", decoded.you.displayName)
        assertTrue(decoded.you.isHost)
        assertEquals("vid_12345", decoded.party.playback.track?.videoId)
        assertEquals(45000L, decoded.party.playback.positionMs)
        assertTrue(decoded.party.playback.isPlaying)
    }

    @Test
    fun `jam invite link parsing and formatting`() {
        // CODE_LENGTH is 6
        val url = JamInviteLink.url("ABC123")
        assertEquals("${JamInviteLink.ORIGIN}/invite/ABC123", url)

        val parsed = JamInviteLink.parse(url)
        assertEquals("ABC123", parsed)

        // Case insensitivity
        val lowerUrl = "${JamInviteLink.ORIGIN}/invite/abc123"
        assertEquals("ABC123", JamInviteLink.parse(lowerUrl))

        // Invalid urls
        assertNull(JamInviteLink.parse("https://google.com"))
        assertNull(JamInviteLink.parse("https://bitchord.kushagrasingh.in/wrong/ABC123"))
        assertNull(JamInviteLink.parse(null))
        assertNull(JamInviteLink.parse(""))
    }

    @Test
    fun `server clock calculates offset and estimates correct server time`() {
        val clock = ServerClock()
        assertFalse(clock.synced)
        assertNull(clock.offsetMs)

        // sentAt=1000, server=2050, receivedAt=1100
        // roundTrip = 100, midpoint = 1000 + 50 = 1050, offset = 2050 - 1050 = 1000
        clock.record(sentAtLocalMs = 1000L, serverMs = 2050L, receivedAtLocalMs = 1100L)
        assertTrue(clock.synced)
        assertEquals(1000L, clock.offsetMs)
        assertEquals(100L, clock.roundTripMs)

        // Reset clears offset
        clock.reset()
        assertFalse(clock.synced)
        assertNull(clock.offsetMs)
    }

    @Test
    fun `listen together state initial values`() {
        val defaultState = ListenTogether.State()
        assertFalse(defaultState.inParty)
        assertNull(defaultState.code)
        assertEquals(ListenTogether.Connection.OFFLINE, defaultState.connection)
        assertFalse(defaultState.clockSynced)
        assertEquals(0L, defaultState.roundTripMs)
        assertNull(defaultState.playback.track)
        assertFalse(defaultState.playback.isPlaying)
        assertEquals(0, defaultState.members.size)
    }
}
