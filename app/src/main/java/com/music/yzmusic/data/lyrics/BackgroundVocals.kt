package com.music.yzmusic.data.lyrics

/**
 * Pulls the answering vocal out of a line and hangs it underneath, as
 * [LyricLine.background].
 *
 * Only Apple's TTML says outright which spans are the backing voice
 * (`ttm:role="x-bg"`, read in [TtmlLyrics]). Every other provider — LyricsPlus,
 * SimpMusic's rich sync, LRCLIB — writes it into the line as a bracket:
 *
 * ```
 * I'm foolishly patient (Foolishly patient)
 * ```
 *
 * Which is a bracket doing the job of a second line, and it showed. The words
 * inside it are sung *over* the line that follows, so the cursor moved on with
 * the bracket half-swept and the strip cut it off mid-phrase. Split out, the
 * bracket keeps its own timings and draws below the lead instead of being
 * dragged through it.
 *
 * The parentheses are kept on the text rather than stripped. They are what the
 * provider published, they are what [toLrc] has to write back out for the
 * downloaded file to stay readable in other players, and a smaller line under
 * the lead already says "this is the answer" without the punctuation being
 * taken away.
 */
internal fun List<LyricLine>.withBackgroundVocals(): List<LyricLine> =
    map { it.splitTrailingBracket() }

private fun LyricLine.splitTrailingBracket(): LyricLine {
    // A source that marked its own backing vocal has already said everything
    // guessing from punctuation could, and better.
    if (background != null || isGap) return this
