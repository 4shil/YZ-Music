package com.music.yzmusic.download

import com.music.yzmusic.data.lyrics.WORD_LYRICS_FIELD

import java.io.ByteArrayOutputStream

/**
 * Rewrites a downloaded FLAC's metadata blocks to carry title, artist, album,
 * lyrics and cover art.
 *
 * The simplest of the three taggers, and the reason is worth stating because it
 * is the opposite of what the other two are shaped by. A FLAC is `fLaC`, then a
 * chain of length-prefixed metadata blocks, then the audio frames — and nothing
 * in the format addresses anything by an absolute file offset. `SEEKTABLE`
 * *looks* like the exception, but its offsets are measured from the first byte
 * of the first frame header rather than from the start of the file, so growing
 * the metadata region moves the frames without invalidating a single number in
 * it. No `stco`/`co64` cascade to patch ([Mp4Tagger]), no `Segment` size to
 * widen in place without changing its byte width ([WebmTagger]): the whole job
 * here is to emit a new block chain and copy the frames through untouched.
 *
 * What comes out is the magic, `STREAMINFO`, every other block the file already
 * had, then a fresh `VORBIS_COMMENT` and `PICTURE`. The old copies of those two
 * are dropped rather than added to — a second `VORBIS_COMMENT` is illegal, and
 * two front covers is a coin toss over which one a player shows. `PADDING` is
 * dropped as well, which is what it is there for: it exists to be spent on
 * exactly this.
 *
 * Anything that doesn't fit the shape above — a file that doesn't open with
 * `fLaC`, a block that claims more bytes than the file has, a first block that
 * isn't `STREAMINFO` — comes back as the input, unchanged and by reference, so
 * [MediaTagger] leaves the downloaded file alone.
 */
object FlacTagger {

    fun tag(
        bytes: ByteArray,
        title: String,
        artist: String,
        album: String?,
        lyrics: String?,
        cover: ByteArray?,
        coverMime: String,
        /** The A2 form, under a name of this app's own — see [WORD_LYRICS_FIELD]. */
        wordLyrics: String? = null,
    ): ByteArray = runCatching {
        rewrite(bytes, title, artist, album, lyrics, cover, coverMime, wordLyrics)
    }.getOrDefault(bytes)

    private fun rewrite(
        bytes: ByteArray,
        title: String,
        artist: String,
        album: String?,
        lyrics: String?,
        cover: ByteArray?,
        coverMime: String,
        wordLyrics: String?,
    ): ByteArray {
        if (!bytes.regionMatches(0, MAGIC)) return bytes

        // The block chain. Each header is one byte of flags — bit 7 marks the
        // last block, bits 0-6 are the type — and three big-endian bytes of
        // payload length.
        val blocks = mutableListOf<Block>()
        var offset = MAGIC.size
        while (true) {
            if (offset + BLOCK_HEADER > bytes.size) return bytes
            val flags = bytes[offset].toInt() and 0xFF
            val length = ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
                (bytes[offset + 3].toInt() and 0xFF)
