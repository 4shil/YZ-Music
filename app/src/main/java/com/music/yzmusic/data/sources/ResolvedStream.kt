package com.music.yzmusic.data.sources

import androidx.media3.common.MimeTypes
import java.util.Locale

/**
 * The packaging format of the stream URL.
 */
enum class StreamContainerType {
    PROGRESSIVE,
    HLS,
    DASH,
}

/**
 * Unified stream model representing a resolved playable or downloadable stream.
 *
 * Encapsulates container type, MIME type, codec, audio resolution, and source origin.
 */
data class ResolvedStream(
    val url: String,
    val mimeType: String? = null,
    val container: StreamContainerType = StreamContainerType.PROGRESSIVE,
    val codec: String? = null,
    val kbps: Int? = null,
    val sampleRateHz: Int? = null,
    val bitDepth: Int? = null,
    val channelCount: Int? = null,
    val sourceKind: SourceKind = SourceKind.YOUTUBE,
    val isLossless: Boolean = false,
    val isHiRes: Boolean = false,
    val isDolbyAtmos: Boolean = false,
    val durationMs: Long? = null,
    val canDownload: Boolean = true,
    val headers: Map<String, String> = emptyMap(),
) {
    companion object {
        fun inferContainer(url: String): StreamContainerType {
            val clean = url.substringBefore('?').lowercase(Locale.ROOT)
            return when {
                clean.endsWith(".m3u8") -> StreamContainerType.HLS
                clean.endsWith(".mpd") -> StreamContainerType.DASH
                else -> StreamContainerType.PROGRESSIVE
            }
        }

        fun inferMimeType(url: String, codec: String? = null): String? {
            val clean = url.substringBefore('?').lowercase(Locale.ROOT)
            return when {
                clean.endsWith(".m3u8") -> MimeTypes.APPLICATION_M3U8
                clean.endsWith(".mpd") -> MimeTypes.APPLICATION_MPD
                codec?.equals("eac3", ignoreCase = true) == true ||
                codec?.equals("joc", ignoreCase = true) == true -> "audio/eac3-joc"
                codec?.equals("flac", ignoreCase = true) == true -> MimeTypes.AUDIO_FLAC
                codec?.equals("alac", ignoreCase = true) == true -> MimeTypes.AUDIO_ALAC
                codec?.equals("opus", ignoreCase = true) == true -> MimeTypes.AUDIO_OPUS
                codec?.equals("mp4a", ignoreCase = true) == true ||
                codec?.equals("aac", ignoreCase = true) == true -> MimeTypes.AUDIO_AAC
                codec?.equals("mp3", ignoreCase = true) == true -> MimeTypes.AUDIO_MPEG
                else -> null
            }
        }
    }
}
