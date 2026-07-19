package com.music.yzmusic

import com.music.yzmusic.data.lyrics.LrcLib
import com.music.yzmusic.data.lyrics.LyricLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LrcLibTest {

    /** Sung lines only — a long intro gets a synthesised gap in front. */
    private fun List<LyricLine>.words() = filterNot { it.isGap }.map { it.text }

    @Test
    fun `parses centisecond stamps`() {
