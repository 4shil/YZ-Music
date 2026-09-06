package com.music.yzmusic

import com.music.yzmusic.data.innertube.InnertubeParser
import com.music.yzmusic.data.model.BrowseItem
import com.music.yzmusic.data.model.BrowseType
import com.music.yzmusic.data.model.SearchResult
import com.music.yzmusic.data.model.ShelfItem
import com.music.yzmusic.data.model.Song
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchPagingTest {

    @Test
    fun `search page keeps rows and next continuation`() {
        val json = """
        {
          "contents": {
            "sectionListRenderer": {
              "contents": [
                {
                  "musicShelfRenderer": {
                    "contents": [
                      {
                        "musicResponsiveListItemRenderer": {
                          "navigationEndpoint": {
                            "browseEndpoint": {
                              "browseId": "VLPL123",
                              "browseEndpointContextSupportedConfigs": {
                                "browseEndpointContextMusicConfig": {
                                  "pageType": "MUSIC_PAGE_TYPE_PLAYLIST"
                                }
                              }
                            }
                          },
                          "flexColumns": [
                            {
                              "musicResponsiveListItemFlexColumnRenderer": {
                                "text": { "runs": [{ "text": "One hundred songs" }] }
                              }
                            },
                            {
                              "musicResponsiveListItemFlexColumnRenderer": {
                                "text": { "runs": [{ "text": "Playlist" }] }
                              }
                            }
                          ],
                          "thumbnail": {
                            "musicThumbnailRenderer": {
                              "thumbnail": { "thumbnails": [{ "url": "https://example.test/art.jpg" }] }
                            }
                          }
                        }
                      }
                    ]
                  }
                },
                {
                  "continuationItemRenderer": {
                    "continuationEndpoint": {
                      "continuationCommand": { "token": "SEARCH_MORE" }
                    }
                  }
                }
              ]
            }
          }
        }
        """.trimIndent()

        val page = InnertubeParser.parseSearchPage(Json.parseToJsonElement(json).jsonObject)

        assertEquals("SEARCH_MORE", page.continuation)
        assertEquals(1, page.rows.size)
        val item = (page.rows.single() as SearchResult.Browse).item
        assertEquals("VLPL123", item.browseId)
        assertEquals("One hundred songs", item.title)
        assertEquals(BrowseType.PLAYLIST, item.type)
    }

    @Test
    fun `search page deduplicates repeated rows before pagination`() {
        val row = """
        {
          "musicResponsiveListItemRenderer": {
            "navigationEndpoint": {
              "browseEndpoint": {
                "browseId": "VLPL123",
                "browseEndpointContextSupportedConfigs": {
                  "browseEndpointContextMusicConfig": {
                    "pageType": "MUSIC_PAGE_TYPE_PLAYLIST"
                  }
                }
              }
            },
            "flexColumns": [
              {
                "musicResponsiveListItemFlexColumnRenderer": {
                  "text": { "runs": [{ "text": "Repeated playlist" }] }
                }
              }
            ]
          }
        }
        """.trimIndent()
        val json = """
        {
          "contents": [ $row, $row ],
          "continuations": [
            { "nextContinuationData": { "continuation": "NEXT_PAGE" } }
          ]
        }
        """.trimIndent()

        val page = InnertubeParser.parseSearchPage(Json.parseToJsonElement(json).jsonObject)

        assertEquals("NEXT_PAGE", page.continuation)
        assertEquals(1, page.rows.size)
        assertTrue(page.rows.single() is SearchResult.Browse)
    }

    @Test
    fun `playlist continuation rows are real tracks even without set video ids`() {
        val json = """
        {
          "continuationContents": {
            "musicPlaylistShelfContinuation": {
              "contents": [
                {
                  "musicResponsiveListItemRenderer": {
                    "playlistItemData": { "videoId": "song-2" },
                    "flexColumns": [
                      {
                        "musicResponsiveListItemFlexColumnRenderer": {
                          "text": { "runs": [{ "text": "Second page song" }] }
                        }
                      },
                      {
                        "musicResponsiveListItemFlexColumnRenderer": {
                          "text": { "runs": [{ "text": "Artist" }, { "text": " • " }, { "text": "3:21" }] }
                        }
                      }
                    ]
                  }
                }
              ],
              "continuations": [
                { "nextContinuationData": { "continuation": "PLAYLIST_MORE" } }
              ]
            }
          }
        }
        """.trimIndent()

        val page = InnertubeParser.parsePlaylistShelf(Json.parseToJsonElement(json).jsonObject)

        requireNotNull(page)
        assertEquals(listOf("song-2"), page.songs.map { it.videoId })
        assertEquals(emptyList<String>(), page.suggested.map { it.videoId })
        assertEquals("PLAYLIST_MORE", page.continuation)
    }

    @Test
    fun `library item page keeps saved cards and next continuation`() {
        val json = """
        {
          "contents": [
            {
              "musicTwoRowItemRenderer": {
                "title": { "runs": [{ "text": "Road songs" }] },
                "subtitle": { "runs": [{ "text": "Playlist" }] },
                "navigationEndpoint": { "browseEndpoint": { "browseId": "VLPLROAD" } },
                "thumbnailRenderer": {
                  "musicThumbnailRenderer": {
                    "thumbnail": { "thumbnails": [{ "url": "https://example.test/road.jpg" }] }
                  }
                }
              }
            }
          ],
          "continuations": [
            { "nextContinuationData": { "continuation": "LIBRARY_MORE" } }
          ]
        }
        """.trimIndent()

        val page = InnertubeParser.parseLibraryItemPage(Json.parseToJsonElement(json).jsonObject)

        assertEquals("LIBRARY_MORE", page.continuation)
        assertEquals(1, page.items.size)
        assertEquals("VLPLROAD", page.items.single().browseId)
        assertEquals("Road songs", page.items.single().title)
    }

    @Test
    fun `user playlists filter still excludes non editable auto playlists after paging`() {
        val playlists = InnertubeParser.parseUserPlaylists(
            listOf(
                ShelfItem("Road songs", "Playlist", null, null, "VLPLROAD"),
                ShelfItem("Liked Music", "Auto playlist", null, null, "VLLM"),
                ShelfItem("Album", "Album", null, null, "MPREb_album"),
            ),
        )

        assertEquals(listOf("PLROAD"), playlists.map { it.playlistId })
    }

    @Test
    fun `identityKey correctly identifies track and browse search results`() {
        val song = Song("vid_123", "Title", "Artist", null)
        val trackResult = SearchResult.Track(song)
        val browseItem = BrowseItem("MPREb_album", "Album Title", "Album", null, BrowseType.ALBUM)
        val browseResult = SearchResult.Browse(browseItem)
        val topTrack = SearchResult.TopTrack(song)

        with(com.music.yzmusic.data.YtMusicRepository) {
            assertEquals("v:vid_123", trackResult.identityKey())
            assertEquals("v:vid_123", topTrack.identityKey())
            assertEquals("b:MPREb_album", browseResult.identityKey())
        }
    }

    @Test
    fun `search page parses promoted musicCardShelfRenderer and deduplicates identical track row`() {
        val json = """
        {
          "contents": {
            "sectionListRenderer": {
              "contents": [
                {
                  "musicCardShelfRenderer": {
                    "title": { "runs": [{ "text": "Blinding Lights" }] },
                    "subtitle": { "runs": [{ "text": "Song • The Weeknd • 3:20" }] },
                    "onTap": {
                      "watchEndpoint": { "videoId": "4NRXx6U8ABQ" }
                    },
                    "thumbnail": {
                      "musicThumbnailRenderer": {
                        "thumbnail": { "thumbnails": [{ "url": "https://example.test/art.jpg", "width": 544, "height": 544 }] }
                      }
                    }
                  }
                },
                {
                  "musicShelfRenderer": {
                    "contents": [
                      {
                        "musicResponsiveListItemRenderer": {
                          "playlistItemData": { "videoId": "4NRXx6U8ABQ" },
                          "flexColumns": [
                            {
                              "musicResponsiveListItemFlexColumnRenderer": {
                                "text": { "runs": [{ "text": "Blinding Lights" }] }
                              }
                            },
                            {
                              "musicResponsiveListItemFlexColumnRenderer": {
                                "text": { "runs": [{ "text": "The Weeknd" }, { "text": " • " }, { "text": "3:20" }] }
                              }
                            }
                          ]
                        }
                      },
                      {
                        "musicResponsiveListItemRenderer": {
                          "playlistItemData": { "videoId": "other_song" },
                          "flexColumns": [
                            {
                              "musicResponsiveListItemFlexColumnRenderer": {
                                "text": { "runs": [{ "text": "Save Your Tears" }] }
                              }
                            },
                            {
                              "musicResponsiveListItemFlexColumnRenderer": {
                                "text": { "runs": [{ "text": "The Weeknd" }, { "text": " • " }, { "text": "3:35" }] }
                              }
                            }
                          ]
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

        val page = InnertubeParser.parseSearchPage(Json.parseToJsonElement(json).jsonObject)

        assertEquals(2, page.rows.size)
        val top = page.rows[0] as SearchResult.TopTrack
        assertEquals("4NRXx6U8ABQ", top.song.videoId)
        assertEquals("Blinding Lights", top.song.title)

        val second = page.rows[1] as SearchResult.Track
        assertEquals("other_song", second.song.videoId)
        assertEquals("Save Your Tears", second.song.title)
    }

    @Test
    fun `search page parses promoted artist cardShelfBrowse`() {
        val json = """
        {
          "contents": {
            "sectionListRenderer": {
              "contents": [
                {
                  "musicCardShelfRenderer": {
                    "title": { "runs": [{ "text": "The Weeknd" }] },
                    "subtitle": { "runs": [{ "text": "Artist • 50M subscribers" }] },
                    "onTap": {
                      "browseEndpoint": {
                        "browseId": "UC_artist_123",
                        "browseEndpointContextSupportedConfigs": {
                          "browseEndpointContextMusicConfig": {
                            "pageType": "MUSIC_PAGE_TYPE_ARTIST"
                          }
                        }
                      }
                    },
                    "thumbnail": {
                      "musicThumbnailRenderer": {
                        "thumbnail": { "thumbnails": [{ "url": "https://example.test/artist.jpg" }] }
                      }
                    }
                  }
                }
              ]
            }
          }
        }
        """.trimIndent()

        val page = InnertubeParser.parseSearchPage(Json.parseToJsonElement(json).jsonObject)

        assertEquals(1, page.rows.size)
        val item = (page.rows.single() as SearchResult.Browse).item
        assertEquals("UC_artist_123", item.browseId)
        assertEquals("The Weeknd", item.title)
        assertEquals(BrowseType.ARTIST, item.type)
    }

    @Test
    fun `video filtering separates songs and music video uploads`() {
        val json = """
        {
          "contents": [
            {
              "musicResponsiveListItemRenderer": {
                "playlistItemData": { "videoId": "song_track" },
                "flexColumns": [
                  {
                    "musicResponsiveListItemFlexColumnRenderer": {
                      "text": { "runs": [{ "text": "Audio Track" }] }
                    }
                  },
                  {
                    "musicResponsiveListItemFlexColumnRenderer": {
                      "text": { "runs": [{ "text": "Song • Artist • 3:00" }] }
                    }
                  }
                ],
                "thumbnail": {
                  "musicThumbnailRenderer": {
                    "thumbnail": { "thumbnails": [{ "url": "https://example.test/art.jpg", "width": 544, "height": 544 }] }
                  }
                }
              }
            },
            {
              "musicResponsiveListItemRenderer": {
                "playlistItemData": { "videoId": "video_track" },
                "flexColumns": [
                  {
                    "musicResponsiveListItemFlexColumnRenderer": {
                      "text": { "runs": [{ "text": "Music Video" }] }
                    }
                  },
                  {
                    "musicResponsiveListItemFlexColumnRenderer": {
                      "text": { "runs": [{ "text": "Video • Artist • 4:00" }] }
                    }
                  }
                ],
                "thumbnail": {
                  "musicThumbnailRenderer": {
                    "thumbnail": { "thumbnails": [{ "url": "https://example.test/video.jpg", "width": 1280, "height": 720 }] }
                  }
                }
              }
            }
          ]
        }
        """.trimIndent()

        val musicPage = InnertubeParser.parseSearchPage(Json.parseToJsonElement(json).jsonObject, includeVideos = false)
        assertEquals(1, musicPage.rows.size)
        assertEquals("song_track", (musicPage.rows.single() as SearchResult.Track).song.videoId)

        val videoPage = InnertubeParser.parseSearchPage(Json.parseToJsonElement(json).jsonObject, includeVideos = true)
        assertEquals(1, videoPage.rows.size)
        assertEquals("video_track", (videoPage.rows.single() as SearchResult.Track).song.videoId)
    }
}
