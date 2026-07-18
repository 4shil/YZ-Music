package com.music.yzmusic

import com.music.yzmusic.data.model.Song
import com.music.yzmusic.download.DownloadProgress
import com.music.yzmusic.download.DownloadSession
import com.music.yzmusic.download.DownloadTarget
import com.music.yzmusic.download.Downloads
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The two things about a download that are remembered rather than observed: what
 * release a batch was, and whether the user has been told how it went.
 *
 * Both are worth pinning because both fail silently. A release that isn't
 * recorded doesn't throw — it just arrives in the Downloads folder as forty
 * unrelated rows, which is exactly what it looked like before any of this
 * existed. And a visibility rule that is off by one visit is either an indicator
 * that can't be dismissed or one that was never seen, and neither is visible
 * from the code: the whole point of the rule is that it holds while nobody is
 * looking.
 */
class DownloadSessionTest {

    private fun song(id: String, title: String = id, artist: String = "Artist") =
        Song(videoId = id, title = title, artist = artist, thumbnailUrl = null)

    /** As a downloaded track comes back off the Downloads page: with a file. */
    private fun onDisk(id: String, album: String? = null) = song(id).copy(
        albumName = album,
        localUri = "content://media/external/audio/media/$id",
    )

    @Before
    fun reset() = clearState()

    @After
