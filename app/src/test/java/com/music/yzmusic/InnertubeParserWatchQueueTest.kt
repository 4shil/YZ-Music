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

