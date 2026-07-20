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
