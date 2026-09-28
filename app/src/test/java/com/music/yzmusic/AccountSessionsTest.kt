package com.music.yzmusic

import com.music.yzmusic.auth.GoogleAccountSession
import com.music.yzmusic.auth.YouTubeProfile
import com.music.yzmusic.auth.adjacentProfile
import com.music.yzmusic.auth.flattenedProfiles
import com.music.yzmusic.auth.withProfilePhoto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AccountSessionsTest {
    private val a = GoogleAccountSession("a", "cookie-a", profiles = listOf(
        YouTubeProfile("a-personal", "Personal"), YouTubeProfile("a-brand", "Brand"),
    ))
    private val b = GoogleAccountSession("b", "cookie-b", profiles = listOf(
        YouTubeProfile("b-personal", "Personal"), YouTubeProfile("b-brand", "Brand"),
    ))

    @Test fun `profiles retain account then profile ordering`() {
        assertEquals(listOf("a" to "a-personal", "a" to "a-brand", "b" to "b-personal", "b" to "b-brand"),
            flattenedProfiles(listOf(a, b)))
    }

    @Test fun `next previous and boundaries do not wrap`() {
        assertEquals("a" to "a-brand", adjacentProfile(listOf(a, b), "a", "a-personal", true))
        assertEquals("b" to "b-personal", adjacentProfile(listOf(a, b), "b", "b-brand", false))
        assertNull(adjacentProfile(listOf(a, b), "a", "a-personal", false))
        assertNull(adjacentProfile(listOf(a, b), "b", "b-brand", true))
    }

    @Test fun `one profile has no adjacent profile`() {
        val only = GoogleAccountSession("a", "cookie", profiles = listOf(YouTubeProfile("p", "Personal")))
        assertNull(adjacentProfile(listOf(only), "a", "p", true))
        assertNull(adjacentProfile(listOf(only), "a", "p", false))
    }
    // The picture the party layer shows next to a member lives on the session
    // profile, but it is only ever fetched onto the account. Nothing copied it
    // across, so every member went out with no avatar at all. These pin that
    // the copy lands on the profile that is actually being shown — and only
    // when it would change something, since it runs on every launch.

    @Test fun `photo lands on the profile being shown, not the first one`() {
        val session = GoogleAccountSession("a", "cookie", activeProfileId = "a-brand", profiles = listOf(
            YouTubeProfile("a-personal", "Personal", avatar = "https://personal.jpg"),
            YouTubeProfile("a-brand", "Brand", avatar = "https://old-brand.jpg"),
        ))
        val updated = session.withProfilePhoto("https://new.jpg")!!
        assertEquals("https://new.jpg", updated.profiles[1].avatar)
        assertEquals("https://personal.jpg", updated.profiles[0].avatar)
    }

    @Test fun `photo falls back to the first profile when none is selected`() {
        val session = GoogleAccountSession("a", "cookie", activeProfileId = null, profiles = listOf(
            YouTubeProfile("a-personal", "Personal", avatar = "https://old.jpg"),
        ))
        assertEquals("https://new.jpg", session.withProfilePhoto("https://new.jpg")!!.profiles[0].avatar)
    }

    @Test fun `unchanged photo is not rewritten`() {
        val session = GoogleAccountSession("a", "cookie", activeProfileId = "p", profiles = listOf(
            YouTubeProfile("p", "Personal", avatar = "https://same.jpg"),
        ))
        assertNull(session.withProfilePhoto("https://same.jpg"))
    }

    @Test fun `photo is not written when the selected profile is gone`() {
        val session = GoogleAccountSession("a", "cookie", activeProfileId = "deleted", profiles = listOf(
            YouTubeProfile("p", "Personal"),
        ))
        assertNull(session.withProfilePhoto("https://new.jpg"))
    }
}
