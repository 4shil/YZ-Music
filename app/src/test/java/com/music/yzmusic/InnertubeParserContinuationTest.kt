package com.music.yzmusic

import com.music.yzmusic.data.innertube.InnertubeParser
import com.music.yzmusic.data.model.ShelfType
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for Innertube continuation response parsing and continuation token extraction.
 */
class InnertubeParserContinuationTest {

    @Test
    fun `parseHomeContinuation parses carousel shelf in continuation envelope`() {
        val json = """
        {
          "continuationContents": {
            "sectionListContinuation": {
              "contents": [
                {
                  "musicCarouselShelfRenderer": {
                    "header": {
                      "musicCarouselShelfBasicHeaderRenderer": {
                        "title": { "runs": [{ "text": "More Recommendations" }] }
                      }
                    },
                    "contents": [
                      {
                        "musicTwoRowItemRenderer": {
                          "title": { "runs": [{ "text": "Song A" }] },
                          "subtitle": { "runs": [{ "text": "Artist A" }] },
                          "navigationEndpoint": {
                            "watchEndpoint": { "videoId": "video123" }
                          },
                          "thumbnailRenderer": {
                            "musicThumbnailRenderer": {
                              "thumbnail": {
                                "thumbnails": [{ "url": "https://img.test/1.jpg", "width": 544, "height": 544 }]
                              }
                            }
                          }
                        }
                      }
                    ]
                  }
                }
              ]
            }
          }
        }
        """.trimIndent()
        val root = Json.parseToJsonElement(json)
        val shelves = InnertubeParser.parseHomeContinuation(root)
        assertEquals(1, shelves.size)
        assertEquals("More Recommendations", shelves[0].title)
        assertEquals(ShelfType.DEFAULT, shelves[0].type)
        assertEquals(1, shelves[0].items.size)
        assertEquals("Song A", shelves[0].items[0].title)
        assertEquals("video123", shelves[0].items[0].videoId)
    }

    @Test
    fun `parseHomeContinuation skips video charts carousel`() {
        val json = """
        {
          "continuationContents": {
            "sectionListContinuation": {
              "contents": [
                {
                  "musicCarouselShelfRenderer": {
                    "header": {
                      "musicCarouselShelfBasicHeaderRenderer": {
                        "title": { "runs": [{ "text": "Video charts" }] }
                      }
                    },
                    "contents": []
                  }
                },
                {
                  "musicCarouselShelfRenderer": {
                    "header": {
                      "musicCarouselShelfBasicHeaderRenderer": {
                        "title": { "runs": [{ "text": "Valid Shelf" }] }
                      }
                    },
                    "contents": [
                      {
                        "musicTwoRowItemRenderer": {
                          "title": { "runs": [{ "text": "Track 1" }] },
                          "subtitle": { "runs": [{ "text": "Artist 1" }] },
                          "navigationEndpoint": { "watchEndpoint": { "videoId": "v1" } }
                        }
                      }
                    ]
                  }
                }
              ]
            }
          }
        }
        """.trimIndent()
        val root = Json.parseToJsonElement(json)
        val shelves = InnertubeParser.parseHomeContinuation(root)
        assertEquals(1, shelves.size)
        assertEquals("Valid Shelf", shelves[0].title)
    }

    @Test
    fun `parseHomeContinuation parses grid shelf`() {
        val json = """
        {
          "sectionListContinuation": {
            "contents": [
              {
                "gridRenderer": {
                  "header": {
                    "gridHeaderRenderer": {
                      "title": { "runs": [{ "text": "Moods & Genres" }] }
                    }
                  },
                  "items": [
                    {
                      "musicNavigationButtonRenderer": {
                        "buttonText": { "runs": [{ "text": "Chill" }] },
                        "solid": { "leftStripeColor": 4288453798 },
                        "clickCommand": {
                          "browseEndpoint": {
                            "browseId": "FEmusic_chill",
                            "params": "ggMCEgA%3D"
                          }
                        }
                      }
                    }
                  ]
                }
              }
            ]
          }
        }
        """.trimIndent()
        val root = Json.parseToJsonElement(json)
        val shelves = InnertubeParser.parseHomeContinuation(root)
        assertEquals(1, shelves.size)
        assertEquals("Moods & Genres", shelves[0].title)
        assertEquals(ShelfType.MOOD_GENRE, shelves[0].type)
        assertEquals(1, shelves[0].items.size)
        assertEquals("Chill", shelves[0].items[0].title)
    }

    @Test
    fun `continuationToken extracts from continuationItemRenderer`() {
        val json = """
        {
          "continuationContents": {
            "sectionListContinuation": {
              "continuations": [
                {
                  "continuationItemRenderer": {
                    "continuationEndpoint": {
                      "continuationCommand": {
                        "token": "NEXT_PAGE_TOKEN_123"
                      }
                    }
                  }
                }
              ]
            }
          }
        }
        """.trimIndent()
        val root = Json.parseToJsonElement(json)
        val token = InnertubeParser.continuationToken(root)
        assertEquals("NEXT_PAGE_TOKEN_123", token)
    }

    @Test
    fun `continuationToken extracts from legacy nextContinuationData`() {
        val json = """
        {
          "sectionListContinuation": {
            "continuations": [
              {
                "nextContinuationData": {
                  "continuation": "LEGACY_TOKEN_456"
                }
              }
            ]
          }
        }
        """.trimIndent()
        val root = Json.parseToJsonElement(json)
        val token = InnertubeParser.continuationToken(root)
        assertEquals("LEGACY_TOKEN_456", token)
    }

    @Test
    fun `continuationToken returns null when token absent`() {
        val json = """
        {
          "sectionListContinuation": {
            "contents": []
          }
        }
        """.trimIndent()
        val root = Json.parseToJsonElement(json)
        val token = InnertubeParser.continuationToken(root)
        assertNull(token)
    }
}
