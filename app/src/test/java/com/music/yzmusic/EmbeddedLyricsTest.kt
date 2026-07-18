package com.music.yzmusic

import com.music.yzmusic.data.lyrics.EmbeddedLyrics
import com.music.yzmusic.data.lyrics.LrcLib
import com.music.yzmusic.data.lyrics.LyricLine
import com.music.yzmusic.data.lyrics.LyricWord
import com.music.yzmusic.data.lyrics.toEnhancedLrc
import com.music.yzmusic.data.lyrics.toLrc
import com.music.yzmusic.download.FlacTagger
import com.music.yzmusic.download.Mp4Tagger
import com.music.yzmusic.download.WebmTagger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The reader and the three taggers have to agree, and nothing else checks that
 * they do: a download writes the file on a device and the player reads it back
 * on another run, so a disagreement between the two shows up as a downloaded
 * song that silently has no lyrics — which is exactly the bug this pair was
 * written for.
 *
 * Each test writes with the real tagger and reads with the real reader, so a
 * change to either side that breaks the pairing fails here rather than on a
 * phone. Placeholder text throughout; no real lyric appears in this file.
 */
class EmbeddedLyricsTest {

    private val lrc = "[00:01.00]first line\n[00:05.00]second line"

    @Test
    fun `an m4a written by the tagger reads back`() {
        val tagged = Mp4Tagger.tag(
            bytes = minimalMp4(),
            title = "t",
            artist = "a",
            album = null,
            lyrics = lrc,
            cover = null,
            coverIsPng = false,
        )
        assertEquals(lrc, EmbeddedLyrics.fromBytes(tagged))
    }

    @Test
    fun `a flac written by the tagger reads back`() {
        val tagged = FlacTagger.tag(
            bytes = minimalFlac(),
            title = "t",
            artist = "a",
            album = null,
            lyrics = lrc,
            cover = null,
            coverMime = "image/jpeg",
        )
        assertEquals(lrc, EmbeddedLyrics.fromBytes(tagged))
    }

    @Test
