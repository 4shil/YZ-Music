package com.music.yzmusic

import com.music.yzmusic.download.FlacTagger
import com.music.yzmusic.download.Mp4Tagger
import com.music.yzmusic.download.WebmTagger
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * All three taggers touch raw bytes of a file a user will actually try to play,
 * so these check the two things that matter most: a container the taggers
 * don't recognise comes back byte-for-byte unchanged, and one they do comes
 * back with every existing byte preserved (just shifted, for MP4) plus the
 * new metadata recoverable at the position it should be.
 */
class MediaTaggerTest {

    private fun box(type: String, payload: ByteArray): ByteArray {
        val out = ByteArray(8 + payload.size)
        val size = out.size
        out[0] = (size ushr 24).toByte()
        out[1] = (size ushr 16).toByte()
        out[2] = (size ushr 8).toByte()
        out[3] = size.toByte()
        type.toByteArray(Charsets.ISO_8859_1).copyInto(out, 4)
        payload.copyInto(out, 8)
        return out
    }

    private fun u32(value: Int): ByteArray = byteArrayOf(
        (value ushr 24).toByte(),
        (value ushr 16).toByte(),
        (value ushr 8).toByte(),
        value.toByte(),
    )

    private fun readU32(bytes: ByteArray, offset: Int): Long =
        ((bytes[offset].toLong() and 0xFF) shl 24) or ((bytes[offset + 1].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 8) or (bytes[offset + 3].toLong() and 0xFF)

    private fun ByteArray.indexOfBytes(needle: ByteArray, from: Int = 0): Int {
        outer@ for (i in from..size - needle.size) {
            for (j in needle.indices) if (this[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }

    /** `ftyp` + `moov(trak/mdia/minf/stbl/stco)` + `mdat`, with `stco`'s one entry pointing at `mdat`'s payload. */
    private fun buildFakeMp4(mdatPayload: ByteArray): Triple<ByteArray, Int, Int> {
        val ftyp = box("ftyp", ByteArray(8))

        fun moovWithStcoOffset(offset: Int): ByteArray {
            val stcoPayload = ByteArray(12)
            stcoPayload[7] = 1 // entry_count = 1
            u32(offset).copyInto(stcoPayload, 8)
            val stco = box("stco", stcoPayload)
            val stbl = box("stbl", stco)
