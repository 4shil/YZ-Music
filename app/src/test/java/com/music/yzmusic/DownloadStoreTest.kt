package com.music.yzmusic

import com.music.yzmusic.data.model.Song
import com.music.yzmusic.data.settings.AppSettings
import com.music.yzmusic.data.settings.AudioQuality
import com.music.yzmusic.data.settings.DownloadQuality
import com.music.yzmusic.data.sources.SourceResolver
import com.music.yzmusic.data.sources.StreamRequest
import com.music.yzmusic.download.DownloadStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The decisions a download makes before a byte is fetched: what the file is
 * called, whether Android will keep one of it at all, what quality goes into it,
 * and whether the connection in hand is allowed to fetch it.
 *
 * The first two are worth pinning because both fail late and badly. A wrong
 * extension or MIME type is not a compile error and not a bad-sounding download
 * — it is `IllegalArgumentException: Unsupported MIME type` from inside a
 * `ContentResolver.insert`, several frames from anything naming the track, which
 * is precisely how every download in a build once failed while reporting itself
 * as a connection problem.
 *
 * The last two are worth pinning because they fail silently instead. A download
 * capped by the wrong setting is a file that plays perfectly and is not what was
 * asked for, and nothing about it looks wrong from outside.
 */
class DownloadStoreTest {

    private fun song(title: String, artist: String, videoId: String = "abc123") =
        Song(videoId = videoId, title = title, artist = artist, thumbnailUrl = null)

    // ---- What Android will store -------------------------------------------

    @Test
    fun `flac and wav map to the types the media store actually accepts`() {
        val flac = DownloadStore.storable("flac")
        assertEquals("flac", flac?.extension)
        assertEquals("audio/flac", flac?.mimeType)

        // Not audio/x-wav's mirror image: the x- prefix is on the MIME type
        // here and not on the extension.
