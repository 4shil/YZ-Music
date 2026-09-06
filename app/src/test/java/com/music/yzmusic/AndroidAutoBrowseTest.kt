package com.music.yzmusic

import com.music.yzmusic.playback.PlaybackService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidAutoBrowseTest {

    @Test
    fun `android auto browse tree root contains expected top-level categories`() {
        val rootChildren = listOf(
            PlaybackService.MEDIA_RECENTS_ID,
            PlaybackService.MEDIA_QUICK_PICKS_ID,
            PlaybackService.MEDIA_PLAYLISTS_ID,
            PlaybackService.MEDIA_MORE_ID,
        )

        assertEquals(4, rootChildren.size)
        assertEquals("recents", rootChildren[0])
        assertEquals("quick_picks", rootChildren[1])
        assertEquals("playlists", rootChildren[2])
        assertEquals("more", rootChildren[3])
    }

    @Test
    fun `android auto more category contains secondary collections`() {
        val moreChildren = listOf(
            PlaybackService.MEDIA_LIKED_ID,
            PlaybackService.MEDIA_DOWNLOADS_ID,
            PlaybackService.MEDIA_LOCAL_MUSIC_ID,
        )

        assertEquals(3, moreChildren.size)
        assertEquals("liked", moreChildren[0])
        assertEquals("downloads", moreChildren[1])
        assertEquals("local_music", moreChildren[2])
    }

    @Test
    fun `android auto content style hints match platform specifications`() {
        assertEquals(
            "android.media.browse.extra.CONTENT_STYLE_SUPPORTED",
            PlaybackService.EXTRA_CONTENT_STYLE_SUPPORTED,
        )
        assertEquals(
            "android.media.browse.extra.CONTENT_STYLE_BROWSABLE_HINT",
            PlaybackService.EXTRA_CONTENT_STYLE_BROWSABLE_HINT,
        )
        assertEquals(
            "android.media.browse.extra.CONTENT_STYLE_PLAYABLE_HINT",
            PlaybackService.EXTRA_CONTENT_STYLE_PLAYABLE_HINT,
        )
        assertEquals(1, PlaybackService.CONTENT_STYLE_LIST_ITEM_HINT_VALUE)
        assertEquals(2, PlaybackService.CONTENT_STYLE_GRID_ITEM_HINT_VALUE)
    }

    @Test
    fun `android auto folder style differentiation`() {
        val gridFolders = setOf(
            PlaybackService.MEDIA_RECENTS_ID,
            PlaybackService.MEDIA_QUICK_PICKS_ID,
        )

        assertTrue(gridFolders.contains("recents"))
        assertTrue(gridFolders.contains("quick_picks"))
        assertFalse(gridFolders.contains("playlists"))
        assertFalse(gridFolders.contains("more"))
        assertFalse(gridFolders.contains("downloads"))
    }

    @Test
    fun `android auto prefix routing parses IDs correctly`() {
        val playlistParent = "playlist:PL1234567890"
        assertTrue(playlistParent.startsWith("playlist:"))
        assertEquals("PL1234567890", playlistParent.removePrefix("playlist:"))

        val browseParent = "browse:FEmusic_home"
        assertTrue(browseParent.startsWith("browse:"))
        assertEquals("FEmusic_home", browseParent.removePrefix("browse:"))
    }
}
