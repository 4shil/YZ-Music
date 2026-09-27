package com.music.yzmusic.data.lyrics

/**
 * One line of the lyrics panel: the words as the singer sang them, and
 * optionally the second voice drawn beneath them.
 *
 * The original is always the hero. A translation or a romanization is set
 * under it at a smaller size rather than swapped in for it, which is what
 * Apple Music does and what BitChord copies: the singer's own words stay on
 * screen and the reader's eye keeps following the song, while the thing they
 * actually came for sits right underneath in a voice that is clearly not part
 * of the performance.
 */
data class LyricDisplayRow(
    val primary: LyricLine,
    val sub: LyricLine? = null,
)

/**
 * Stacks a second line list underneath [primary], one row per original line.
 *
 * Pairing is strictly by position, never by searching. Every alternate in
 * this app is built on the original's own timing — the provider's translation
 * arrives row-aligned, and a generated one goes through the same retiming — so
 * index *n* of the alternate belongs to index *n* of the song. Anything that
 * guessed would put one line's translation under the next line's words, and a
 * lyric that reads "I don't know you" under "hello, how are you" is worse than
 * no translation at all. A layer of the wrong length is therefore truncated
 * where it runs out and ignored where it overruns, rather than being nudged
 * into place.
 *
 * A sub-line is dropped — and the row falls back to showing its primary alone
 * — when the layer has nothing to say there: blank text, or the very same
 * words again. Rendering "そら" above "sora" spends a second line of the
 * panel to tell the reader nothing.
 */
fun pairLyricLayers(
    primary: List<LyricLine>,
    sub: List<LyricLine>?,
): List<LyricDisplayRow> {
    if (sub == null) return primary.map { LyricDisplayRow(it) }
    val width = minOf(primary.size, sub.size)
    return List(primary.size) { index ->
        if (index >= width) {
            LyricDisplayRow(primary[index])
        } else {
            val candidate = sub[index]
            LyricDisplayRow(
                primary = primary[index],
                sub = candidate.takeIf { it.addsSomething(primary[index]) },
            )
        }
    }
}

/**
 * Whether [this] is worth a line of the panel under [above] — it must carry
 * actual characters, and it must not just repeat what is already there.
 */
private fun LyricLine.addsSomething(above: LyricLine): Boolean {
    val mine = text.trim()
    if (mine.isEmpty()) return false
    return !mine.equals(above.text.trim(), ignoreCase = true)
}
