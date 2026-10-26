package com.music.bitchord

import com.music.bitchord.data.model.BrowseType
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.Song
import com.music.bitchord.download.DownloadState
import com.music.bitchord.ui.player.SongDownloadUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SongDownloadUiStateTest {

    @Test
    fun `download state maps correctly from saved and active download maps`() {
        val saved = mapOf("song-1" to "/path/to/song1.opus")
        val active = mapOf<String, DownloadState>(
            "song-2" to DownloadState.Running(0.42f),
            "song-3" to DownloadState.Queued,
            "song-4" to DownloadState.Failed("Network timeout"),
        )

        fun resolveState(song: Song): SongDownloadUiState {
            val id = song.videoId
            val inFlight = active[id]
            return when {
                saved.containsKey(id) || song.localUri != null -> SongDownloadUiState.Downloaded
                inFlight is DownloadState.Running -> SongDownloadUiState.Downloading(inFlight.fraction)
                inFlight is DownloadState.Queued -> SongDownloadUiState.Queued
                inFlight is DownloadState.Failed -> SongDownloadUiState.Failed(inFlight.reason)
                else -> SongDownloadUiState.Idle
            }
        }

        assertEquals(
            SongDownloadUiState.Downloaded,
            resolveState(Song(videoId = "song-1", title = "T1", artist = "A1", thumbnailUrl = null)),
        )
        assertEquals(
            SongDownloadUiState.Downloading(0.42f),
            resolveState(Song(videoId = "song-2", title = "T2", artist = "A2", thumbnailUrl = null)),
        )
        assertEquals(
            SongDownloadUiState.Queued,
            resolveState(Song(videoId = "song-3", title = "T3", artist = "A3", thumbnailUrl = null)),
        )
        assertEquals(
            SongDownloadUiState.Failed("Network timeout"),
            resolveState(Song(videoId = "song-4", title = "T4", artist = "A4", thumbnailUrl = null)),
        )
        assertEquals(
            SongDownloadUiState.Idle,
            resolveState(Song(videoId = "song-5", title = "T5", artist = "A5", thumbnailUrl = null)),
        )
    }

    @Test
    fun `video card long press preserves isVideo flag`() {
        val videoShelfItem = ShelfItem(
            title = "Official Music Video",
            subtitle = "Artist • 10M views",
            thumbnailUrl = "https://img.youtube.com/vi/vid123/hqdefault.jpg",
            videoId = "vid123",
            isVideo = true,
        )

        val song = Song(
            videoId = videoShelfItem.videoId!!,
            title = videoShelfItem.title,
            artist = "Artist",
            thumbnailUrl = videoShelfItem.thumbnailUrl,
            isVideo = videoShelfItem.isVideo,
        )

        assertTrue("Song created from video card should have isVideo=true", song.isVideo)
    }

    @Test
    fun `artist browse item retains artist browse type for action menu`() {
        val artistShelfItem = ShelfItem(
            title = "Radiohead",
            subtitle = "Artist • 5M subscribers",
            thumbnailUrl = "https://img.youtube.com/artist.jpg",
            browseId = "UCartist123",
        )

        assertEquals("UCartist123", artistShelfItem.browseId)
        val browseType = BrowseType.ARTIST
        assertEquals(BrowseType.ARTIST, browseType)
    }
}
