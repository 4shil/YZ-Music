package com.music.yzmusic

import com.music.yzmusic.data.lyrics.LrcLib
import com.music.yzmusic.data.lyrics.LyricLine
import com.music.yzmusic.data.lyrics.LyricWord
import com.music.yzmusic.data.lyrics.toLrc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The LRC writer is what the download path embeds into a saved file's metadata,
 * so what it emits is read back by third-party players this project will never
 * see. That makes the stamp format the whole test surface: a reader that
 * doesn't recognise a stamp doesn't skip the line, it shows the markup, and a
 * stamp that parses but is *wrong* shows the right words at the wrong moment.
 *
 * Everything below uses placeholder text rather than any real lyric.
 */
class LrcWriterTest {

    @Test
    fun `each line is stamped as mm colon ss dot centiseconds`() {
        val lines = listOf(
            LyricLine(timeMs = 0L, text = "first line"),
            LyricLine(timeMs = 61_230L, text = "second line"),
        )
        assertEquals("[00:00.00]first line\n[01:01.23]second line", lines.toLrc())
    }

    @Test
    fun `milliseconds are truncated to centiseconds rather than rounded up past a second`() {
        // 59.999s must not become [01:00.00]: rounding here would push a line
        // past a minute boundary and reorder it against the one that follows.
        assertEquals("[00:59.99]x", listOf(LyricLine(59_999L, "x")).toLrc())
    }

    @Test
