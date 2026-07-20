package com.music.yzmusic

import com.music.yzmusic.data.innertube.InnertubeParser
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Telling a playlist this account made from one it merely saved.
 *
 * The distinction only exists on the playlist's own page — the library feed
 * lists the two identically — and getting it wrong is silent either way: a
 * Delete button that YouTube refuses, or a Rename that quietly vanishes from
 * the user's own playlist. So each of the three readings
 * [InnertubeParser.parsePlaylistOwned] makes is pinned to a response shaped
 * like the one it was written for.
 */
class PlaylistOwnershipTest {

    private fun owned(json: String): Boolean? =
        InnertubeParser.parsePlaylistOwned(Json.parseToJsonElement(json))

    // ---- Own playlists ------------------------------------------------------

    /** The renderer YouTube wraps a header in when it belongs to the caller. */
    @Test
    fun `editable header renderer means own playlist`() {
        val json = """
        {"header":{"musicEditablePlaylistDetailHeaderRenderer":{
          "header":{"musicResponsiveHeaderRenderer":{"buttons":[
            {"toggleButtonRenderer":{
              "defaultIcon":{"iconType":"BOOKMARK_BORDER"},
              "toggledIcon":{"iconType":"BOOKMARK"}}}
          ]}}
        }}}
        """
        // Decided before the buttons are read, so a stray bookmark inside an
        // editable header cannot talk it back out of being the account's.
        assertEquals(true, owned(json))
    }

    /** Edit and Delete in the header menu are offered to nobody else. */
    @Test
    fun `delete in the header menu means own playlist`() {
        val json = """
        {"header":{"musicResponsiveHeaderRenderer":{
          "buttons":[{"menuRenderer":{"items":[
            {"menuNavigationItemRenderer":{"icon":{"iconType":"DELETE"}}}
          ]}}]
        }}}
        """
        assertEquals(true, owned(json))
    }

    /** Nothing to save a playlist already yours into, so there is no bookmark. */
    @Test
    fun `header with no save bookmark means own playlist`() {
        val json = """
        {"header":{"musicResponsiveHeaderRenderer":{
          "buttons":[
            {"musicPlayButtonRenderer":{}},
            {"menuRenderer":{"items":[
              {"menuNavigationItemRenderer":{"icon":{"iconType":"SHARE"}}}
            ]}}
          ]
        }}}
        """
        assertEquals(true, owned(json))
    }

    // ---- Saved playlists ----------------------------------------------------

    /** The reported bug: a saved playlist offering Rename and Delete. */
    @Test
