package com.music.yzmusic.data.lyrics

/**
 * What a provider brought back: the original words, and — where the source
 * happens to ship them — a translation and a romanization of those same words.
 *
 * The alternates are whole line lists built on the *original's* timing rather
 * than extra fields hung off each line. That shape is what lets a display swap
 * change nothing about the music: the player swaps one list for another of the
 * same length, with the same stamps and the same word timings, so the sweep
 * carries across and the scroll does not move. The original is never replaced
 * in place, only set aside, and a null alternate means the layer is not on
 * offer at all rather than on offer and empty.
 */
data class LyricsPayload(
    val lines: List<LyricLine>,
    val translation: List<LyricLine>? = null,
    val romanization: List<LyricLine>? = null,
) {
    val hasAlternates: Boolean get() = translation != null || romanization != null
}
