package com.music.yzmusic

import com.music.yzmusic.data.innertube.InnertubeParser
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies that watch queue items (AutoPlay recommendations from the `next` endpoint)
 * are classified correctly as audio tracks or music videos using structured metadata.
 */
class InnertubeParserWatchQueueTest {

    private fun parse(json: String) =
        InnertubeParser.parseWatchQueue(Json.parseToJsonElement(json))

    private fun itemJson(
        videoId: String = "test1",
        musicVideoType: String? = null,
        albumId: String? = null,
        thumbWidth: Int? = null,
        thumbHeight: Int? = null,
    ): String {
        val config = if (musicVideoType != null) {
            """
            "navigationEndpoint": {
              "watchEndpoint": {
                "watchEndpointMusicSupportedConfigs": {
                  "watchEndpointMusicConfig": {
                    "musicVideoType": "$musicVideoType"
                  }
                }
              }
            },
            """
        } else ""

        val albumRun = if (albumId != null) {
            """
            ,
            { "text": " • " },
            {
              "text": "Album Name",
              "navigationEndpoint": {
                "browseEndpoint": {
                  "browseId": "$albumId",
                  "browseEndpointContextSupportedConfigs": {
                    "browseEndpointContextMusicConfig": { "pageType": "MUSIC_PAGE_TYPE_ALBUM" }
                  }
                }
              }
            }
            """
        } else ""

        val thumbs = if (thumbWidth != null && thumbHeight != null) {
            """
            "thumbnail": {
              "thumbnails": [{ "url": "https://img.jpg", "width": $thumbWidth, "height": $thumbHeight }]
            },
            """
        } else """
            "thumbnail": { "thumbnails": [] },
        """

        return """
        {
          "playlistPanelVideoRenderer": {
            "videoId": "$videoId",
            "title": { "runs": [{ "text": "Test Song" }] },
            "longBylineText": {
              "runs": [
                { "text": "Test Artist" }
                $albumRun
              ]
            },
            $config
            $thumbs
            "lengthText": { "runs": [{ "text": "3:30" }] }
          }
        }
        """.trimIndent()
    }

    @Test
    fun `case A - OMV with album metadata is still marked isVideo`() {
        val json = itemJson(
            musicVideoType = "MUSIC_VIDEO_TYPE_OMV",
            albumId = "MPREb_album123",
            thumbWidth = 500,
            thumbHeight = 500,
        )
        val songs = parse(json)
        assertEquals(1, songs.size)
        assertTrue("Explicit OMV must be isVideo=true even with album metadata and square thumb", songs[0].isVideo)
    }

    @Test
    fun `case B - OMV with missing thumbnail dimensions is marked isVideo`() {
        val json = itemJson(
            musicVideoType = "MUSIC_VIDEO_TYPE_OMV",
            thumbWidth = null,
            thumbHeight = null,
        )
        val songs = parse(json)
        assertEquals(1, songs.size)
        assertTrue("Explicit OMV must be isVideo=true even with missing thumbnail dimensions", songs[0].isVideo)
    }

    @Test
    fun `case C - UGC with square thumbnail is marked isVideo`() {
        val json = itemJson(
            musicVideoType = "MUSIC_VIDEO_TYPE_UGC",
            thumbWidth = 500,
            thumbHeight = 500,
        )
        val songs = parse(json)
        assertEquals(1, songs.size)
        assertTrue("Explicit UGC must be isVideo=true even with square thumbnail", songs[0].isVideo)
    }

    @Test
    fun `case D - ATV with widescreen thumbnail is NOT marked isVideo`() {
        val json = itemJson(
            musicVideoType = "MUSIC_VIDEO_TYPE_ATV",
            thumbWidth = 800,
            thumbHeight = 450,
        )
        val songs = parse(json)
        assertEquals(1, songs.size)
        assertFalse("Explicit ATV must be isVideo=false even with widescreen thumbnail", songs[0].isVideo)
    }

    @Test
    fun `case E - catalogue track with album metadata is NOT marked isVideo`() {
        val json = itemJson(
            musicVideoType = null,
            albumId = "MPREb_album456",
            thumbWidth = 500,
            thumbHeight = 500,
        )
        val songs = parse(json)
        assertEquals(1, songs.size)
        assertFalse("Track with album metadata and square thumbnail must be isVideo=false", songs[0].isVideo)
    }

    @Test
    fun `case F - unknown type with widescreen thumbnail is marked isVideo`() {
        val json = itemJson(
            musicVideoType = null,
            albumId = null,
            thumbWidth = 800,
            thumbHeight = 450,
        )
        val songs = parse(json)
        assertEquals(1, songs.size)
        assertTrue("Unknown type with widescreen thumbnail must be isVideo=true", songs[0].isVideo)
    }

    @Test
    fun `case G - unknown type with square thumbnail and no album metadata is NOT marked isVideo`() {
        val json = itemJson(
            musicVideoType = null,
            albumId = null,
            thumbWidth = 500,
            thumbHeight = 500,
        )
        val songs = parse(json)
        assertEquals(1, songs.size)
        assertFalse("Unknown type with square thumbnail in music queue defaults to isVideo=false", songs[0].isVideo)
    }
}
