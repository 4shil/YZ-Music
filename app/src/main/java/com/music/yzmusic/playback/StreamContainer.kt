package com.music.yzmusic.playback

import androidx.media3.common.MimeTypes
import java.util.concurrent.ConcurrentHashMap
import java.util.Locale

/**
 * Tracks container format of streaming URLs, ensuring Media3 can distinguish
 * progressive audio from MPEG-DASH (.mpd) and HLS (.m3u8) manifests.
 */
object StreamContainer {

    /**
     * The MIME type Media3 needs for [url], or null when it is ordinary progressive audio.
     */
    fun manifestMimeOf(url: String): String? =
        when (url.substringBefore('?').substringAfterLast('.').lowercase(Locale.ROOT)) {
            "m3u8" -> MimeTypes.APPLICATION_M3U8
            "mpd" -> MimeTypes.APPLICATION_MPD
            else -> null
        }

    /** Whether [url] is an index manifest of audio rather than a progressive file. */
    fun isManifest(url: String): Boolean = manifestMimeOf(url) != null

    /**
     * Tracks the URL last served for each media ID.
     */
    private val serving = ConcurrentHashMap<String, String>()

    fun served(mediaId: String, url: String) {
        if (serving.size >= MAX_REMEMBERED) serving.clear()
        serving[mediaId] = url
    }

    fun manifestServing(mediaId: String): String? = serving[mediaId]?.let(::manifestMimeOf)

    private const val MAX_REMEMBERED = 64
}
