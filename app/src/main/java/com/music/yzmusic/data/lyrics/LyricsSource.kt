package com.music.yzmusic.data.lyrics

/**
 * The databases [LyricsRepository] can ask, in the order it asks them.
 *
 * Exposed in Settings because the trade-offs are real and personal: one of
 * these is geoblocked in some countries, another runs on volunteer mirrors
 * that come and go, and all of them are third-party services being reached on
 * the user's connection. Anyone who would rather not talk to a given one
 * should be able to say so.
 */
enum class LyricsSource(
    val label: String,
    val detail: String,
    /** Whether it can return per-word timings, or only whole lines. */
    val wordSynced: Boolean,
) {
    // Declaration order is the default priority — [AppSettings.lyricsSourceOrder]
    // and [AppSettings.lyricsSources] both fall back to [LyricsSource.entries]
    // verbatim, so this list *is* the out-of-the-box experience.
    //
    // LyricsPlus sits third rather than first, which is a change: it has the
    // finest timing of any source here, and also the least reliable hosting -
    // volunteer mirrors that come and go. Asked first, a track whose LyricsPlus
    // mirror happens to be down waits on it before three hosts that carry the
    // same Apple catalogue are consulted at all. Behind them, its timing is
    // still what gets used whenever it answers.
    //
    // The three Apple hosts lead because they are asked for the same underlying
    // documents, so a track answered by any of them has word-level timing rather
    // than whole lines, which is the difference between the syllables lighting
    // up individually and the line lighting up as a whole. Among those three the
    // order is not load-bearing; they are independent hosts for one catalogue.
    PAXSENIX(
        label = "PaxSenix",
        detail = "Apple Music timings again, on a second host",
        wordSynced = true,
    ),
    BETTER_LYRICS(
        label = "BetterLyrics",
        detail = "Apple Music timings, word by word",
        wordSynced = true,
    ),
    LYRICS_PLUS(
        label = "LyricsPlus",
        detail = "Syllable by syllable, on community mirrors",
        wordSynced = true,
    ),
    SIMP_MUSIC(
        label = "SimpMusic",
        detail = "Matched on the video, so never the wrong edit",
        wordSynced = true,
    ),
    KUGOU(
        label = "KuGou",
        detail = "Whole lines, strong outside the English catalogue",
        wordSynced = false,
    ),
    LRCLIB(
        label = "LRCLIB",
        detail = "Whole lines only, and always up",
        wordSynced = false,
    ),
    MUSIXMATCH(
        label = "Musixmatch",
        detail = "Whole lines, from the biggest lyrics database there is",
        wordSynced = false,
    ),
    GENIUS(
        label = "Genius",
        detail = "Plain text fallback, massive web catalogue",
        wordSynced = false,
    ),
}
