package com.music.yzmusic

import com.music.yzmusic.data.innertube.hasExplicitBadge
import com.music.yzmusic.data.model.Song
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExplicitBadgeTest {

    @Test
    fun `song data class retains isExplicit flag`() {
        val explicitSong = Song(
            videoId = "vid_explicit",
            title = "Explicit Song",
            artist = "Explicit Artist",
            thumbnailUrl = "https://example.com/art.jpg",
            isExplicit = true,
        )
        assertEquals(true, explicitSong.isExplicit)

        val cleanSong = Song(
            videoId = "vid_clean",
            title = "Clean Song",
            artist = "Clean Artist",
            thumbnailUrl = null,
            isExplicit = false,
        )
        assertEquals(false, cleanSong.isExplicit)

        val defaultSong = Song(
            videoId = "vid_default",
            title = "Default Song",
            artist = "Default Artist",
            thumbnailUrl = null,
        )
        assertNull(defaultSong.isExplicit)
    }

    @Test
    fun `hasExplicitBadge detects nested MUSIC_EXPLICIT_BADGE json token`() {
        val jsonString = """
            {
                "badges": [
                    {
                        "musicInlineBadgeRenderer": {
                            "icon": {
                                "iconType": "MUSIC_EXPLICIT_BADGE"
                            }
                        }
                    }
                ]
            }
        """.trimIndent()
        val element = Json.parseToJsonElement(jsonString)
        assertEquals(true, element.hasExplicitBadge())
    }

    @Test
    fun `hasExplicitBadge returns null when badge absent`() {
        val jsonString = """
            {
                "badges": [
                    {
                        "musicInlineBadgeRenderer": {
                            "icon": {
                                "iconType": "MUSIC_LIKE"
                            }
                        }
                    }
                ]
            }
        """.trimIndent()
        val element = Json.parseToJsonElement(jsonString)
        assertNull(element.hasExplicitBadge())
    }
}
