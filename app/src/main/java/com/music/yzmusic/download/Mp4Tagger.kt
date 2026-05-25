package com.music.yzmusic.download

import com.music.yzmusic.data.lyrics.WORD_LYRICS_FIELD

/**
 * Writes iTunes-style metadata atoms — title, artist, album, lyrics, cover —
 * into an already-downloaded M4A/MP4 file, in place.
 *
 * There is no public Android API for this: [android.media.MediaMuxer] can
 * copy tracks into a fresh MP4 but has no way to declare a title or embed
 * artwork, and every general-purpose Java tagging library drags in either
 * native code or `javax.imageio` (absent on Android, and the exact crash
 * several other music apps hit shipping the unmodified desktop jaudiotagger).
 * So this reads and rewrites the handful of boxes involved directly.
 *
 * The whole thing is a single insertion: a fresh `udta/meta/ilst` atom is
 * appended as the last child of `moov`. Growing `moov` shifts every byte
 * after it, which is only a problem because `stco`/`co64` (the sample tables
 * under `moov/trak/mdia/minf/stbl`) record *absolute* file offsets into
 * `mdat` — so every entry at or past the insertion point is bumped by the
 * inserted length. Nothing else in the file addresses itself by absolute
 * offset, so that one adjustment is sufficient regardless of whether `mdat`
 * sits before or after `moov`.
 *
 * Any layout this doesn't recognise — no `moov`, a box that doesn't fit its
 * parent — falls through to returning the input unchanged rather than
 * guessing: a download that plays untagged is a smaller loss than one a
 * bad rewrite has corrupted.
 */
object Mp4Tagger {

    private data class BoxRef(
        val offset: Int,
        val headerLen: Int,
        val size: Int,
        /** The raw 32-bit size field, before size==0/1 are resolved — 0 means "to end of parent", which must be left alone rather than replaced with a real number. */
        val rawSize32: Long,
        val type: String,
    ) {
