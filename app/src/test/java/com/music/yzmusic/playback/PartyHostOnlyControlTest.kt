package com.music.yzmusic.playback

import com.music.yzmusic.data.listentogether.ListenTogether
import com.music.yzmusic.data.listentogether.PartyMember
import com.music.yzmusic.data.listentogether.PartyPlayback
import com.music.yzmusic.data.listentogether.PartySnapshot
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PartyHostOnlyControlTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun member(id: String, host: Boolean) = PartyMember(
        memberId = id,
        displayName = id,
        isHost = host,
    )

    private fun state(
        code: String? = "ABC123",
        you: PartyMember? = member("me", host = false),
        hostOnly: Boolean = false,
    ) = ListenTogether.State(
        code = code,
        you = you,
        members = listOfNotNull(you),
        hostOnlyControl = hostOnly,
        playback = PartyPlayback(),
    )

    @Test
    fun `a listener is locked out while the host holds the controls`() {
        assertTrue(state(hostOnly = true).controlsLocked)
    }

    @Test
    fun `the host is never locked out of their own party`() {
        val asHost = state(you = member("me", host = true), hostOnly = true)
        assertFalse(asHost.controlsLocked)
    }

    @Test
    fun `an open party locks nobody`() {
        assertFalse(state(hostOnly = false).controlsLocked)
    }

    @Test
    fun `no party is not a locked party`() {
        assertFalse(state(code = null, hostOnly = true).controlsLocked)
    }

    @Test
    fun `being promoted to host unlocks the controls`() {
        val locked = state(hostOnly = true)
        assertTrue(locked.controlsLocked)

        val promoted = locked.copy(you = member("me", host = true))
        assertFalse(promoted.controlsLocked)
    }

    @Test
    fun `a snapshot from a server without the setting reads as unlocked`() {
        val snapshot = json.decodeFromString(
            PartySnapshot.serializer(),
            """{"code":"ABC123","maxMembers":5,"members":[]}""",
        )
        assertFalse(snapshot.hostOnlyControl)
    }

    @Test
    fun `a snapshot carrying the setting keeps it`() {
        val snapshot = json.decodeFromString(
            PartySnapshot.serializer(),
            """{"code":"ABC123","maxMembers":5,"hostOnlyControl":true,"members":[]}""",
        )
        assertTrue(snapshot.hostOnlyControl)
        assertEquals("ABC123", snapshot.code)
    }
}
