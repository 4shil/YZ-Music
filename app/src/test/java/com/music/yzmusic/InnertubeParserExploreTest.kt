package com.music.yzmusic

import com.music.yzmusic.data.innertube.InnertubeParser
import com.music.yzmusic.data.model.ShelfType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for YouTube Music Explore & Charts response parsing.
 * Verifies that:
 * 1. New Albums & Singles are parsed with browse targets.
 * 2. Moods & Genres buttons are parsed into chips with colors and params.
 * 3. Trending items are parsed with rank indices and video IDs.
 * 4. New Music Videos (16:9) are parsed with video IDs and marked as videos.
 * 5. Top Artists are parsed with artist browse IDs.
 * 6. Popular Episodes (podcasts) are parsed with show names and video IDs.
 * 7. Unknown or malformed renderers are safely dropped without crashing.
 * 8. Empty shelves are pruned cleanly.
 */
class InnertubeParserExploreTest {

    private fun parseSection(carouselContent: String): JsonObject {
        val json = """
        {
          "contents": {
            "singleColumnBrowseResultsRenderer": {
              "tabs": [
                {
                  "tabRenderer": {
                    "content": {
                      "sectionListRenderer": {
                        "contents": [
                          $carouselContent
                        ]
                      }
                    }
                  }
                }
              ]
            }
          }
        }
        """.trimIndent()
        return Json.parseToJsonElement(json) as JsonObject
    }

    @Test
    fun testParseNewAlbumsAndSingles() {
        val carousel = """
        {
          "musicCarouselShelfRenderer": {
            "header": {
              "musicCarouselShelfBasicHeaderRenderer": {
                "title": { "runs": [{ "text": "New albums & singles" }] }
              }
            },
            "contents": [
              {
                "musicTwoRowItemRenderer": {
                  "title": { "runs": [{ "text": "Album One" }] },
                  "subtitle": { "runs": [{ "text": "Single • Artist Name" }] },
                  "navigationEndpoint": {
                    "browseEndpoint": {
                      "browseId": "MPREb_12345"
                    }
                  },
                  "thumbnailRenderer": {
                    "musicThumbnailRenderer": {
                      "thumbnail": {
                        "thumbnails": [
                          { "url": "https://lh3.googleusercontent.com/art1=w544-h544", "width": 544, "height": 544 }
                        ]
                      }
                    }
                  }
                }
              }
            ]
          }
        }
        """
        val shelves = InnertubeParser.parseHome(parseSection(carousel))
        assertEquals(1, shelves.size)
        val shelf = shelves[0]
        assertEquals("New albums & singles", shelf.title)
        assertEquals(ShelfType.DEFAULT, shelf.type)
        assertEquals(1, shelf.items.size)
        val item = shelf.items[0]
        assertEquals("Album One", item.title)
        assertEquals("Single • Artist Name", item.subtitle)
        assertEquals("MPREb_12345", item.browseId)
        assertNull(item.videoId)
        assertFalse(item.isVideo)
    }

    @Test
    fun testParseMoodsAndGenresNavigationButtons() {
        val carousel = """
        {
          "musicCarouselShelfRenderer": {
            "header": {
              "musicCarouselShelfBasicHeaderRenderer": {
                "title": { "runs": [{ "text": "Moods & genres" }] }
              }
            },
            "contents": [
              {
                "musicNavigationButtonRenderer": {
                  "buttonText": { "runs": [{ "text": "Chill" }] },
                  "solid": { "leftStripeColor": 4288988671 },
                  "clickCommand": {
                    "browseEndpoint": {
                      "browseId": "FEmusic_moods_and_genres_category",
                      "params": "ggMPOg1uX1JOQWZFeDByc2Jm"
                    }
                  }
                }
              },
              {
                "musicNavigationButtonRenderer": {
                  "buttonText": { "runs": [{ "text": "Party" }] },
                  "solid": { "leftStripeColor": 4294951424 },
                  "clickCommand": {
                    "browseEndpoint": {
                      "browseId": "FEmusic_moods_and_genres_category",
                      "params": "ggMPOg1uX044Z2o5WERLckpU"
                    }
                  }
                }
              }
            ]
          }
        }
        """
        val shelves = InnertubeParser.parseHome(parseSection(carousel))
        assertEquals(1, shelves.size)
        val shelf = shelves[0]
        assertEquals("Moods & genres", shelf.title)
        assertEquals(ShelfType.MOOD_GENRE, shelf.type)
        assertEquals(2, shelf.items.size)

        val item1 = shelf.items[0]
        assertEquals("Chill", item1.title)
        assertEquals("FEmusic_moods_and_genres_category", item1.browseId)
        assertEquals("ggMPOg1uX1JOQWZFeDByc2Jm", item1.params)
        assertEquals(4288988671L, item1.stripeColor)

        val item2 = shelf.items[1]
        assertEquals("Party", item2.title)
        assertEquals("FEmusic_moods_and_genres_category", item2.browseId)
        assertEquals("ggMPOg1uX044Z2o5WERLckpU", item2.params)
        assertEquals(4294951424L, item2.stripeColor)
    }

    @Test
    fun testParseTrendingRankedItems() {
        val carousel = """
        {
          "musicCarouselShelfRenderer": {
            "header": {
              "musicCarouselShelfBasicHeaderRenderer": {
                "title": { "runs": [{ "text": "Trending" }] }
              }
            },
            "contents": [
              {
                "musicResponsiveListItemRenderer": {
                  "customIndexColumn": {
                    "musicCustomIndexColumnRenderer": {
                      "text": { "runs": [{ "text": "1" }] }
                    }
                  },
                  "flexColumns": [
                    {
                      "musicResponsiveListItemFlexColumnRenderer": {
                        "text": { "runs": [{ "text": "Trending Hit Song" }] }
                      }
                    },
                    {
                      "musicResponsiveListItemFlexColumnRenderer": {
                        "text": { "runs": [{ "text": "Star Singer • 10M views" }] }
                      }
                    }
                  ],
                  "playlistItemData": {
                    "videoId": "video123"
                  },
                  "thumbnail": {
                    "musicThumbnailRenderer": {
                      "thumbnail": {
                        "thumbnails": [
                          { "url": "https://lh3.googleusercontent.com/art=w544-h544", "width": 544, "height": 544 }
                        ]
                      }
                    }
                  }
                }
              }
            ]
          }
        }
        """
        val shelves = InnertubeParser.parseHome(parseSection(carousel))
        assertEquals(1, shelves.size)
        val shelf = shelves[0]
        assertEquals("Trending", shelf.title)
        assertEquals(ShelfType.RANKED, shelf.type)
        assertEquals(1, shelf.items.size)

        val item = shelf.items[0]
        assertEquals("Trending Hit Song", item.title)
        assertEquals("Star Singer • 10M views", item.subtitle)
        assertEquals("1", item.customIndex)
        assertEquals("video123", item.videoId)
        assertTrue(item.isVideo)
    }

    @Test
    fun testParseNewMusicVideos169() {
        val carousel = """
        {
          "musicCarouselShelfRenderer": {
            "header": {
              "musicCarouselShelfBasicHeaderRenderer": {
                "title": { "runs": [{ "text": "New music videos" }] }
              }
            },
            "contents": [
              {
                "musicTwoRowItemRenderer": {
                  "title": { "runs": [{ "text": "Music Video Title" }] },
                  "subtitle": { "runs": [{ "text": "Artist • 500K views" }] },
                  "aspectRatio": "MUSIC_TWO_ROW_ITEM_THUMBNAIL_ASPECT_RATIO_RECTANGLE_16_9",
                  "navigationEndpoint": {
                    "watchEndpoint": {
                      "videoId": "vid_xyz"
                    }
                  },
                  "thumbnailRenderer": {
                    "musicThumbnailRenderer": {
                      "thumbnail": {
                        "thumbnails": [
                          { "url": "https://i.ytimg.com/vi/vid_xyz/hqdefault.jpg", "width": 480, "height": 360 }
                        ]
                      }
                    }
                  }
                }
              }
            ]
          }
        }
        """
        val shelves = InnertubeParser.parseHome(parseSection(carousel))
        assertEquals(1, shelves.size)
        val shelf = shelves[0]
        assertEquals("New music videos", shelf.title)
        assertEquals(ShelfType.VIDEO, shelf.type)
        assertEquals(1, shelf.items.size)

        val item = shelf.items[0]
        assertEquals("Music Video Title", item.title)
        assertEquals("Artist • 500K views", item.subtitle)
        assertEquals("vid_xyz", item.videoId)
        assertNull(item.browseId)
        assertTrue(item.isVideo)
    }

    @Test
    fun testParseTopArtists() {
        val carousel = """
        {
          "musicCarouselShelfRenderer": {
            "header": {
              "musicCarouselShelfBasicHeaderRenderer": {
                "title": { "runs": [{ "text": "Top artists" }] }
              }
            },
            "contents": [
              {
                "musicResponsiveListItemRenderer": {
                  "customIndexColumn": {
                    "musicCustomIndexColumnRenderer": {
                      "text": { "runs": [{ "text": "1" }] }
                    }
                  },
                  "navigationEndpoint": {
                    "browseEndpoint": {
                      "browseId": "UCartist123",
                      "browseEndpointContextSupportedConfigs": {
                        "browseEndpointContextMusicConfig": {
                          "pageType": "MUSIC_PAGE_TYPE_ARTIST"
                        }
                      }
                    }
                  },
                  "flexColumns": [
                    {
                      "musicResponsiveListItemFlexColumnRenderer": {
                        "text": { "runs": [{ "text": "Great Artist" }] }
                      }
                    },
                    {
                      "musicResponsiveListItemFlexColumnRenderer": {
                        "text": { "runs": [{ "text": "5M subscribers" }] }
                      }
                    }
                  ],
                  "thumbnail": {
                    "musicThumbnailRenderer": {
                      "thumbnail": {
                        "thumbnails": [
                          { "url": "https://lh3.googleusercontent.com/art=w544-h544", "width": 544, "height": 544 }
                        ]
                      }
                    }
                  }
                }
              }
            ]
          }
        }
        """
        val shelves = InnertubeParser.parseHome(parseSection(carousel))
        assertEquals(1, shelves.size)
        val shelf = shelves[0]
        assertEquals("Top artists", shelf.title)
        assertEquals(1, shelf.items.size)

        val item = shelf.items[0]
        assertEquals("Great Artist", item.title)
        assertEquals("5M subscribers", item.subtitle)
        assertEquals("UCartist123", item.browseId)
        assertEquals("1", item.customIndex)
        assertNull(item.videoId)
    }

    @Test
    fun testParsePopularEpisodes() {
        val carousel = """
        {
          "musicCarouselShelfRenderer": {
            "header": {
              "musicCarouselShelfBasicHeaderRenderer": {
                "title": { "runs": [{ "text": "Popular episodes" }] }
              }
            },
            "contents": [
              {
                "musicMultiRowListItemRenderer": {
                  "title": { "runs": [{ "text": "Episode 42: The Future" }] },
                  "secondTitle": { "runs": [{ "text": "Tech Talk Show" }] },
                  "subtitle": { "runs": [{ "text": "100K views • 45 min" }] },
                  "onTap": {
                    "watchEndpoint": {
                      "videoId": "ep_vid_42"
                    }
                  },
                  "thumbnail": {
                    "musicThumbnailRenderer": {
                      "thumbnail": {
                        "thumbnails": [
                          { "url": "https://lh3.googleusercontent.com/ep.jpg", "width": 544, "height": 544 }
                        ]
                      }
                    }
                  }
                }
              }
            ]
          }
        }
        """
        val shelves = InnertubeParser.parseHome(parseSection(carousel))
        assertEquals(1, shelves.size)
        val shelf = shelves[0]
        assertEquals("Popular episodes", shelf.title)
        assertEquals(1, shelf.items.size)

        val item = shelf.items[0]
        assertEquals("Episode 42: The Future", item.title)
        assertTrue(item.subtitle.contains("Tech Talk Show"))
        assertEquals("ep_vid_42", item.videoId)
        assertTrue(item.isVideo)
    }

    @Test
    fun testUnknownRendererAndEmptyShelvesSafelyHandled() {
        val carousel = """
        {
          "musicCarouselShelfRenderer": {
            "header": {
              "musicCarouselShelfBasicHeaderRenderer": {
                "title": { "runs": [{ "text": "Unknown Section" }] }
              }
            },
            "contents": [
              {
                "completelyUnknownRenderer": {
                  "dummyField": 123
                }
              }
            ]
          }
        }
        """
        val shelves = InnertubeParser.parseHome(parseSection(carousel))
        // Should return 0 shelves because empty shelves are pruned cleanly
        assertEquals(0, shelves.size)
    }

    @Test
    fun testVideoChartsCompilationDropped() {
        val carousel = """
        {
          "musicCarouselShelfRenderer": {
            "header": {
              "musicCarouselShelfBasicHeaderRenderer": {
                "title": { "runs": [{ "text": "Video charts" }] }
              }
            },
            "contents": [
              {
                "musicTwoRowItemRenderer": {
                  "title": { "runs": [{ "text": "Top Videos" }] },
                  "subtitle": { "runs": [{ "text": "Chart" }] },
                  "navigationEndpoint": {
                    "browseEndpoint": { "browseId": "VL123" }
                  }
                }
              }
            ]
          }
        }
        """
        val shelves = InnertubeParser.parseHome(parseSection(carousel))
        assertEquals(0, shelves.size)
    }

    @Test
    fun testPartialItemFailureTolerance() {
        val carousel = """
        {
          "musicCarouselShelfRenderer": {
            "header": {
              "musicCarouselShelfBasicHeaderRenderer": {
                "title": { "runs": [{ "text": "Trending" }] }
              }
            },
            "contents": [
              {
                "corruptedRenderer": {}
              },
              {
                "musicResponsiveListItemRenderer": {
                  "customIndexColumn": {
                    "musicCustomIndexColumnRenderer": {
                      "text": { "runs": [{ "text": "1" }] }
                    }
                  },
                  "flexColumns": [
                    {
                      "musicResponsiveListItemFlexColumnRenderer": {
                        "text": { "runs": [{ "text": "Surviving Valid Track" }] }
                      }
                    },
                    {
                      "musicResponsiveListItemFlexColumnRenderer": {
                        "text": { "runs": [{ "text": "Valid Artist" }] }
                      }
                    }
                  ],
                  "playlistItemData": {
                    "videoId": "survive_1"
                  }
                }
              }
            ]
          }
        }
        """
        val shelves = InnertubeParser.parseHome(parseSection(carousel))
        assertEquals(1, shelves.size)
        assertEquals(1, shelves[0].items.size)
        assertEquals("Surviving Valid Track", shelves[0].items[0].title)
    }

    @Test
    fun testGridRendererTopNavigationBadges() {
        val grid = """
        {
          "gridRenderer": {
            "header": {
              "gridHeaderRenderer": {
                "title": { "runs": [{ "text": "Explore" }] }
              }
            },
            "items": [
              {
                "musicNavigationButtonRenderer": {
                  "buttonText": { "runs": [{ "text": "New releases" }] },
                  "clickCommand": {
                    "browseEndpoint": {
                      "browseId": "FEmusic_new_releases"
                    }
                  }
                }
              },
              {
                "musicNavigationButtonRenderer": {
                  "buttonText": { "runs": [{ "text": "Charts" }] },
                  "clickCommand": {
                    "browseEndpoint": {
                      "browseId": "FEmusic_charts"
                    }
                  }
                }
              },
              {
                "musicNavigationButtonRenderer": {
                  "buttonText": { "runs": [{ "text": "Moods & genres" }] },
                  "clickCommand": {
                    "browseEndpoint": {
                      "browseId": "FEmusic_moods_and_genres"
                    }
                  }
                }
              }
            ]
          }
        }
        """
        val shelves = InnertubeParser.parseHome(parseSection(grid))
        assertEquals(1, shelves.size)
        val shelf = shelves[0]
        assertEquals("Explore", shelf.title)
        assertEquals(ShelfType.MOOD_GENRE, shelf.type)
        assertEquals(3, shelf.items.size)
        assertEquals("New releases", shelf.items[0].title)
        assertEquals("FEmusic_new_releases", shelf.items[0].browseId)
        assertEquals("Charts", shelf.items[1].title)
        assertEquals("FEmusic_charts", shelf.items[1].browseId)
        assertEquals("Moods & genres", shelf.items[2].title)
        assertEquals("FEmusic_moods_and_genres", shelf.items[2].browseId)
    }

    @Test
    fun testTopArtistsShelfWithRank() {
        val carousel = """
        {
          "musicCarouselShelfRenderer": {
            "header": {
              "musicCarouselShelfBasicHeaderRenderer": {
                "title": { "runs": [{ "text": "Top Artists" }] }
              }
            },
            "contents": [
              {
                "musicResponsiveListItemRenderer": {
                  "customIndexColumn": {
                    "musicCustomIndexColumnRenderer": {
                      "text": { "runs": [{ "text": "1" }] }
                    }
                  },
                  "navigationEndpoint": {
                    "browseEndpoint": {
                      "browseId": "UC1234567890",
                      "browseEndpointContextSupportedConfigs": {
                        "browseEndpointContextMusicConfig": {
                          "pageType": "MUSIC_PAGE_TYPE_ARTIST"
                        }
                      }
                    }
                  },
                  "flexColumns": [
                    {
                      "musicResponsiveListItemFlexColumnRenderer": {
                        "text": { "runs": [{ "text": "Taylor Swift" }] }
                      }
                    },
                    {
                      "musicResponsiveListItemFlexColumnRenderer": {
                        "text": { "runs": [{ "text": "100M Subscribers" }] }
                      }
                    }
                  ],
                  "thumbnail": {
                    "musicThumbnailRenderer": {
                      "thumbnail": {
                        "thumbnails": [
                          { "url": "https://lh3.googleusercontent.com/artist.jpg", "width": 544, "height": 544 }
                        ]
                      }
                    }
                  }
                }
              }
            ]
          }
        }
        """
        val shelves = InnertubeParser.parseHome(parseSection(carousel))
        assertEquals(1, shelves.size)
        val shelf = shelves[0]
        assertEquals("Top Artists", shelf.title)
        assertEquals(ShelfType.RANKED, shelf.type)
        assertEquals(1, shelf.items.size)
        val item = shelf.items[0]
        assertEquals("Taylor Swift", item.title)
        assertEquals("UC1234567890", item.browseId)
        assertEquals("1", item.customIndex)
        assertNull(item.videoId)
    }

    @Test
    fun testCarouselMoreButtonSeeAll() {
        val carousel = """
        {
          "musicCarouselShelfRenderer": {
            "header": {
              "musicCarouselShelfBasicHeaderRenderer": {
                "title": { "runs": [{ "text": "New albums & singles" }] },
                "moreContentButton": {
                  "buttonRenderer": {
                    "navigationEndpoint": {
                      "browseEndpoint": {
                        "browseId": "FEmusic_new_releases_albums",
                        "params": "ggMPO..."
                      }
                    }
                  }
                }
              }
            },
            "contents": [
              {
                "musicTwoRowItemRenderer": {
                  "title": { "runs": [{ "text": "Album A" }] },
                  "subtitle": { "runs": [{ "text": "Artist B" }] },
                  "navigationEndpoint": {
                    "browseEndpoint": { "browseId": "MPREb_album" }
                  }
                }
              }
            ]
          }
        }
        """
        val shelves = InnertubeParser.parseHome(parseSection(carousel))
        assertEquals(1, shelves.size)
        val shelf = shelves[0]
        assertEquals("New albums & singles", shelf.title)
        assertEquals("FEmusic_new_releases_albums", shelf.moreBrowseId)
        assertEquals("ggMPO...", shelf.moreParams)
        assertEquals(1, shelf.items.size)
        assertEquals("MPREb_album", shelf.items[0].browseId)
    }

    @Test
    fun testPlainShelfRankedCharts() {
        val plain = """
        {
          "musicShelfRenderer": {
            "title": { "runs": [{ "text": "Top Songs" }] },
            "contents": [
              {
                "musicResponsiveListItemRenderer": {
                  "customIndexColumn": {
                    "musicCustomIndexColumnRenderer": {
                      "text": { "runs": [{ "text": "1" }] }
                    }
                  },
                  "flexColumns": [
                    {
                      "musicResponsiveListItemFlexColumnRenderer": {
                        "text": { "runs": [{ "text": "Chart Song" }] }
                      }
                    },
                    {
                      "musicResponsiveListItemFlexColumnRenderer": {
                        "text": { "runs": [{ "text": "Top Artist" }] }
                      }
                    }
                  ],
                  "playlistItemData": {
                    "videoId": "song_vid_1"
                  }
                }
              }
            ]
          }
        }
        """
        val shelves = InnertubeParser.parseHome(parseSection(plain))
        assertEquals(1, shelves.size)
        val shelf = shelves[0]
        assertEquals("Top Songs", shelf.title)
        assertEquals(ShelfType.RANKED, shelf.type)
        assertEquals(1, shelf.items.size)
        assertEquals("Chart Song", shelf.items[0].title)
        assertEquals("song_vid_1", shelf.items[0].videoId)
        assertEquals("1", shelf.items[0].customIndex)
    }

    @Test
    fun testGridRendererPopularEpisodesParsing() {
        val gridJson = """
        {
          "gridRenderer": {
            "header": {
              "gridHeaderRenderer": {
                "title": { "runs": [{ "text": "" }] }
              }
            },
            "items": [
              {
                "musicMultiRowListItemRenderer": {
                  "title": {
                    "runs": [
                      {
                        "text": "The Joe Rogan Experience #2100",
                        "navigationEndpoint": {
                          "browseEndpoint": {
                            "browseId": "MPED_episode_vid_123",
                            "pageType": "MUSIC_PAGE_TYPE_NON_MUSIC_AUDIO_TRACK_PAGE"
                          }
                        }
                      }
                    ]
                  },
                  "secondTitle": {
                    "runs": [{ "text": "Joe Rogan" }]
                  },
                  "subtitle": {
                    "runs": [{ "text": "2 hrs 45 mins • Feb 2024" }]
                  },
                  "onTap": {
                    "watchEndpoint": {
                      "videoId": "episode_vid_123"
                    }
                  }
                }
              }
            ]
          }
        }
        """
        val shelves = InnertubeParser.parseHome(parseSection(gridJson))
        assertEquals(1, shelves.size)
        val shelf = shelves[0]
        assertEquals("", shelf.title) // Does not fabricate "Explore"
        assertEquals(ShelfType.VIDEO, shelf.type)
        assertEquals(1, shelf.items.size)
        val item = shelf.items[0]
        assertEquals("The Joe Rogan Experience #2100", item.title)
        assertEquals("Joe Rogan • 2 hrs 45 mins • Feb 2024", item.subtitle)
        assertEquals("episode_vid_123", item.videoId)
        assertNull(item.browseId) // Stripped MPED browseId so it doesn't 404
        assertTrue(item.isVideo)
    }
}
