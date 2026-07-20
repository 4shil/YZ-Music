package com.music.yzmusic

import com.music.yzmusic.data.model.Song
import com.music.yzmusic.data.model.durationMillis
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A row's duration arrives as a display string, and the download path's lyrics
 * lookup needs it back as a quantity.
 *
 * Worth its own tests because every way of getting this wrong is silent. Three
 * of the four lyric databases match on the track's length and LRCLIB *ranks* on
 * it, so a duration that parses to zero doesn't fail the lookup — it matches the
 * shortest edit of the song in the database and embeds timings for a different
 * recording. There is nothing to notice until someone plays the file.
 */
class SongDurationTest {

