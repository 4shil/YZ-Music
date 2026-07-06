package com.music.yzmusic.data.lyrics

/**
 * One word of a line, with the stretch of the song it is sung over.
 *
 * Apple's TTML splits long words into syllables; those are merged back into
 * whole words on the way in, so [startMs] is the first syllable's start and
 * [endMs] the last one's end. Whole words are what the sweep needs — a
 * highlight that ran across "e" and "nough" separately reads as a stutter.
 */
data class LyricWord(val startMs: Long, val endMs: Long, val text: String)

/**
 * One synced line. [timeMs] is when it starts; a blank [text] is an
 * instrumental stretch — LRC files mark those with a bare timestamp.
 *
 * [words] is populated only by the providers that carry word-level timing
 * (BetterLyrics, LyricsPlus, SimpMusic's rich sync). LRCLIB has none, so a
 * line from there highlights whole; see [isWordSynced].
 *
 * [sungUntilMs] is the line's own end where a line-synced provider states one,
 * which is what lets an interlude be told apart from a slowly sung line.
 *
 * [background] is the answering vocal — the "(ooh)" or the echoed half-phrase
 * a second voice sings over the lead. It is a line in its own right, with its
 * own stamp and its own words, because that is what it is: it starts partway
 * through the line it answers and routinely runs past the *next* line's stamp.
 * Run into [text] it dragged the sweep along with it, and the cursor — which
 * takes the last line whose stamp has passed — moved on before the bracket had
 * been sung, so the tail of the line was skipped. Kept apart it draws
 * underneath the lead on its own clock. Never nested: a background line's own
 * [background] is always null.
 */
data class LyricLine(
    val timeMs: Long,
    val text: String,
    val words: List<LyricWord> = emptyList(),
    val sungUntilMs: Long? = null,
