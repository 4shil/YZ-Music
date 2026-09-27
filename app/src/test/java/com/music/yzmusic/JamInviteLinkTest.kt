package com.music.yzmusic

import com.music.yzmusic.data.listentogether.JamInviteLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class JamInviteLinkTest {

    @Test
    fun `parses standard vercel join url`() {
        val invite = JamInviteLink.parseInvite("https://yz-music.vercel.app/join/ABC123")
        assertNotNull(invite)
        assertEquals("ABC123", invite?.code)
        assertNull(invite?.serverUrl)
    }

    @Test
    fun `parses standard vercel join url case insensitive`() {
        val invite = JamInviteLink.parseInvite("https://yz-music.vercel.app/join/abc123")
        assertNotNull(invite)
        assertEquals("ABC123", invite?.code)
    }

    @Test
    fun `parses vercel join url with custom server query param`() {
        val invite = JamInviteLink.parseInvite("https://yz-music.vercel.app/join/XYZ789?server=https%3A%2F%2Fparty.mycustomdomain.com")
        assertNotNull(invite)
        assertEquals("XYZ789", invite?.code)
        assertEquals("https://party.mycustomdomain.com", invite?.serverUrl)
    }

    @Test
    fun `parses legacy render invite url`() {
        val invite = JamInviteLink.parseInvite("https://yz-music-party.onrender.com/invite/PARTY1")
        assertNotNull(invite)
        assertEquals("PARTY1", invite?.code)
    }

    @Test
    fun `parses custom scheme yzmusic party path`() {
        val invite = JamInviteLink.parseInvite("yzmusic://party/CODE99")
        assertNotNull(invite)
        assertEquals("CODE99", invite?.code)
        assertNull(invite?.serverUrl)
    }

    @Test
    fun `parses custom scheme yzmusic party query param`() {
        val invite = JamInviteLink.parseInvite("yzmusic://party?code=CODE99&server=https%3A%2F%2Fcustom.party.org")
        assertNotNull(invite)
        assertEquals("CODE99", invite?.code)
        assertEquals("https://custom.party.org", invite?.serverUrl)
    }

    @Test
    fun `generates correct public url and scheme url`() {
        assertEquals("https://yz-music.vercel.app/join/ABC123", JamInviteLink.url("ABC123"))
        assertEquals("yzmusic://party/ABC123", JamInviteLink.schemeUrl("ABC123"))

        val withServer = JamInviteLink.url("ABC123", "https://custom.party.org")
        assertEquals("https://yz-music.vercel.app/join/ABC123?server=https%3A%2F%2Fcustom.party.org", withServer)
    }

    @Test
    fun `rejects invalid urls`() {
        assertNull(JamInviteLink.parse("https://google.com"))
        assertNull(JamInviteLink.parse("https://yz-music.vercel.app/wrong/ABC123"))
        assertNull(JamInviteLink.parse("yzmusic://other/ABC123"))
        assertNull(JamInviteLink.parse("yzmusic://party/toolongcode123"))
        assertNull(JamInviteLink.parse(null))
        assertNull(JamInviteLink.parse(""))
    }
}
