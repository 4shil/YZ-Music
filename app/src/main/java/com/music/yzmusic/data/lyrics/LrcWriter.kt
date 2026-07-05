package com.music.yzmusic.data.lyrics

/**
 * Turns parsed lines back into the text of an LRC file.
 *
 * The counterpart to [LrcLib.parseLrc], and the only writer in this package —
 * everything else here reads. It exists for the download path, which embeds
 * what it gets into the saved file's own metadata (see
 * `com.music.yzmusic.download.LyricsTag`), and LRC is what that field is read
 * as: whatever a track's lyrics arrived as, they leave here as `[mm:ss.xx]`
 * stamps, which is the one synced-lyric syntax every other player understands.
 *
 * **Word timings are deliberately dropped.** Three of the four providers carry
 * them and the player uses them, but the way to keep them in a file is the
 * "enhanced" A2 extension — `<mm:ss.xx>` runs inside the line — and a reader
 * that doesn't implement it does not ignore those stamps, it *shows* them, so
 * every line reads as angle-bracket noise with words scattered through it.
 * Line stamps degrade to plain text in the same reader; word stamps corrupt it.
 * The file is for other players, so it gets the syntax they all read.
 *
 * Nothing writes the `[ti:]`/`[ar:]`/`[al:]` header tags either. The container's
 * own title, artist and album atoms sit beside this field and already say all
 * three; repeating them here would only be visible in the readers that *don't*
 * parse LRC, which are exactly the ones that can least afford three more lines
 * of markup at the top.
 */
internal fun List<LyricLine>.toLrc(): String {
    if (isEmpty()) return ""
    // Sorted here rather than assumed: the providers each sort their own output,
    // but this is one line of insurance against a file whose stamps run
    // backwards — which every reader renders as lyrics that jump about.
    return sortedBy { it.timeMs }.joinToString("\n") { line -> stamp(line.timeMs) + line.flattened() }
}

/**
 * The line as one row of text, answering vocal and all.
 *
 * [LyricLine.background] is a display split — the backing voice drawn under the
 * lead rather than run into it, see [withBackgroundVocals] — and LRC has no way
 * to say that. Giving it a stamp of its own would put a second line into the
 * file where the song has one, so it goes back where every provider that
 * doesn't mark it structurally had it: on the end of the lead.
 */
