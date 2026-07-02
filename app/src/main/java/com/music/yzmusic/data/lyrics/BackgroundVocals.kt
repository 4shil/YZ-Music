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

    val open = bracketStart(text) ?: return this
    val lead = text.substring(0, open).trimEnd()
    val backing = text.substring(open).trim()
    if (lead.isEmpty() || !backing.any { it.isLetterOrDigit() }) return this

    if (words.isEmpty()) {
        // Line-synced: there is no timing to divide, so the two halves share
        // the line's stamp and simply stack. Both keep the stated end — it is
        // the line's end, and the line is both of them.
        return copy(
            text = lead,
            background = LyricLine(timeMs, backing, sungUntilMs = sungUntilMs),
        )
    }

    // [text] is the words joined by single spaces in every word-synced parser
    // here, so the bracket's character offset is a word boundary — unless the
    // bracket opens mid-word ("wait(ing)"), in which case it isn't one and
    // there is nothing to hand the backing line for timing. Leave those be.
    val split = words.indexOfFirstStartingAt(open) ?: return this
