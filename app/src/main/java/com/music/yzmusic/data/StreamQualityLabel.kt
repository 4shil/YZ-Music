package com.music.yzmusic.data

/**
 * The measured detail of a stream, rendered as one line for the player.
 *
 * ## Only what was measured
 *
 * Every part of the result is omitted unless it is known. That is the whole
 * design: a gap in the middle reads as "unknown", while a gap at the end reads
 * as nothing to say. Filling an unknown with a plausible value instead — a
 * device's advertised rates for a stream's, a claimed bit depth for a measured
 * one — would make the line confident and wrong, and a quality claim is the
 * one thing on this screen a listener will take at face value. So an unknown
 * never becomes a number here; it removes that word.
 *
 * ## Which side of the chain this describes
 *
 * The *source*: what the decoder was handed, read off [NerdStats]. Not the
 * output — that is `AudioOutputStatus`, a separate question asked after the
 * mixer has had the audio. Conflating the two is how a 24-bit stream ends up
 * described by the 16-bit speaker it is playing through.
 */
object StreamQualityLabel {

    /**
     * Builds the detail line, or null when nothing at all is known.
     *
     * Null rather than an empty string so a caller can leave the row out
     * entirely rather than reserve space for a blank.
     */
    fun detail(
        mimeType: String?,
        bitDepth: Int?,
        sampleRateHz: Int?,
        bitrateKbps: Int?,
    ): String? {
        val parts = ArrayList<String>(4)
        codec(mimeType)?.let(parts::add)
        bitDepth?.takeIf { it > 0 }?.let { parts.add("$it-bit") }
        sampleRateHz?.takeIf { it > 0 }?.let { parts.add(sampleRate(it)) }
        // Bitrate is deliberately last: it is the one part that competes for
        // space with a codec name, and on a lossless stream it is not what the
        // listener is asking about anyway.
        bitrateKbps?.takeIf { it > 0 }?.let { parts.add("$it kbps") }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }

    /** 44100 reads as "44.1kHz"; the round rates read as "48kHz", not "48.0kHz". */
    private fun sampleRate(hz: Int): String =
        if (hz % 1000 == 0) "${hz / 1000}kHz" else "${hz / 1000.0}kHz"

    /**
     * The codec's own name, or null when the mime type names none this app
     * prints. A mime type it does not recognise is dropped rather than echoed
     * back — `audio/3gpp` tells a listener nothing, and the rest of the line
     * stands on its own without it.
     *
     * Media3's mime types carry the codec in a parameter
     * (`audio/mp4; codecs="mp4a.40.2"`); that names what is actually being
     * decoded, so it wins over the container in the subtype.
     */
    private fun codec(mimeType: String?): String? {
        if (mimeType.isNullOrBlank()) return null
        val raw = CODEC_PARAMETER.find(mimeType)?.groupValues?.get(1)
            ?: mimeType.substringAfterLast('/')
        return NAMED_CODECS[raw.trim().lowercase()]
    }

    /**
     * Codec names this app is willing to print. AAC is keyed by its full
     * object-type identifier because `mp4a` alone does not say which of the
     * three AAC profiles arrived, and calling an HE-AAC stream plain "AAC"
     * would overstate it.
     */
    private val NAMED_CODECS = mapOf(
        "flac" to "FLAC",
        "alac" to "ALAC",
        "wav" to "WAV",
        "wave" to "WAV",
        "raw" to "PCM",
        "mp3" to "MP3",
        "mp4a.40.2" to "AAC",
        "mp4a.40.5" to "HE-AAC",
        "mp4a.67" to "AAC",
        "aac" to "AAC",
        "opus" to "Opus",
        "vorbis" to "Vorbis",
        "ac3" to "AC-3",
        "ec3" to "E-AC-3",
        "dts" to "DTS",
        "dsd" to "DSD",
    )

    private val CODEC_PARAMETER = Regex("""codecs\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
}
