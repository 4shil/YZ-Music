package com.music.yzmusic.data.lyrics

/**
 * Enhanced ("A2") LRC — a normal LRC line with a stamp in front of each word:
 *
 * ```
 * [00:27.39]<00:27.39>I <00:27.54>been <00:27.74>tryna <00:28.07>call
 * ```
 *
 * This is what Musixmatch's rich sync becomes, and it is what SimpMusic
 * serves. Only starts are written down, so each word runs until the next one
 * begins, and the last word of a line until the next line does.
 */
object EnhancedLrc {

    private val LINE = Regex("""^\[(\d{1,3}):(\d{2})[.:](\d{2,3})](.*)$""")
    private val WORD = Regex("""<(\d{1,3}):(\d{2})[.:](\d{2,3})>([^<]*)""")

    /** Empty when [lrc] carries no word stamps — the caller can then fall back. */
    fun parse(lrc: String): List<LyricLine> {
