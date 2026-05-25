package com.music.yzmusic.download

import com.music.yzmusic.data.lyrics.WORD_LYRICS_FIELD

/**
 * Appends Matroska `Tags` and `Attachments` elements — title, artist, album,
 * lyrics, cover — to an already-downloaded WebM file, in place.
 *
 * The insertion is always a plain append at the end of the file, never a
 * splice in the middle, which is what makes this simpler than [Mp4Tagger]:
 * a downloaded track is one `EBML` header followed by one `Segment`, and
 * `Tags`/`Attachments` are ordinary children of that `Segment` with nothing
 * else addressing them by absolute offset (unlike MP4's `stco`/`co64`, an
 * optional `SeekHead` records where things are, but it is advisory — a
 * player without an entry for `Tags` in it still finds the element by
 * reading on, which is exactly what appending at the end relies on).
 *
 * The one field that can need touching is `Segment`'s own size, if it
 * declared one — a WebM served as a live remux typically declares it
 * "unknown" (an all-ones size, meaning "read to the end"), in which case
 * appending needs no further change at all. A declared size is only ever
 * widened in place, keeping its original byte width, because growing that
 * width would shift the size field itself and everything after it — the
 * same offset cascade [Mp4Tagger] exists to handle, which nothing here
 * reaches for. If the value doesn't fit the existing width, or the file
 * doesn't match the single-header/single-segment shape this assumes, the
 * input comes back unchanged rather than guessed at.
 */
object WebmTagger {

    private val EBML_HEADER_ID = byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte())
    private val SEGMENT_ID = byteArrayOf(0x18, 0x53.toByte(), 0x80.toByte(), 0x67)

    private val ID_TAGS = byteArrayOf(0x12, 0x54, 0xC3.toByte(), 0x67)
    private val ID_TAG = byteArrayOf(0x73, 0x73)
    private val ID_TARGETS = byteArrayOf(0x63, 0xC0.toByte())
    private val ID_SIMPLETAG = byteArrayOf(0x67, 0xC8.toByte())
    private val ID_TAGNAME = byteArrayOf(0x45, 0xA3.toByte())
    private val ID_TAGSTRING = byteArrayOf(0x44, 0x87.toByte())
    private val ID_ATTACHMENTS = byteArrayOf(0x19, 0x41, 0xA4.toByte(), 0x69)
    private val ID_ATTACHEDFILE = byteArrayOf(0x61, 0xA7.toByte())
    private val ID_FILENAME = byteArrayOf(0x46, 0x6E)
    private val ID_FILEMIMETYPE = byteArrayOf(0x46, 0x60)
    private val ID_FILEDATA = byteArrayOf(0x46, 0x5C)
    private val ID_FILEUID = byteArrayOf(0x46, 0xAE.toByte())

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
        insert(bytes, buildTail(title, artist, album, lyrics, cover, coverMime, wordLyrics))
    }.getOrDefault(bytes)

    private fun insert(bytes: ByteArray, tail: ByteArray): ByteArray {
        if (tail.isEmpty()) return bytes
        if (bytes.size < 16 || !bytes.regionMatches(0, EBML_HEADER_ID)) return bytes

        val headerSize = readSize(bytes, EBML_HEADER_ID.size) ?: return bytes
        val segmentIdOffset = EBML_HEADER_ID.size + headerSize.width + headerSize.value.toInt()
        if (segmentIdOffset + 4 > bytes.size || !bytes.regionMatches(segmentIdOffset, SEGMENT_ID)) return bytes

        val segmentSize = readSize(bytes, segmentIdOffset + SEGMENT_ID.size) ?: return bytes
        val segmentContentStart = segmentIdOffset + SEGMENT_ID.size + segmentSize.width

        if (segmentSize.isUnknown) {
            val out = bytes.copyOf(bytes.size + tail.size)
            tail.copyInto(out, bytes.size)
            return out
        }

        // A declared size only matches this shape when it accounts for every
        // byte already in the file — anything else (trailing padding, more
        // top-level elements after Segment) isn't a layout worth guessing at.
        val declaredEnd = segmentContentStart + segmentSize.value
        if (declaredEnd != bytes.size.toLong()) return bytes

        val newSize = segmentSize.value + tail.size
        val maxForWidth = (1L shl (7 * segmentSize.width)) - 2
        if (newSize > maxForWidth) return bytes

        val out = bytes.copyOf(bytes.size + tail.size)
        tail.copyInto(out, bytes.size)
        writeVint(out, segmentIdOffset + SEGMENT_ID.size, newSize, segmentSize.width)
        return out
    }

