package com.music.yzmusic.data.lyrics

import com.music.yzmusic.data.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import kotlin.math.abs

/**
 * Lyrics from LRCLIB — a free, key-less, community lyrics database.
 *
 * Two calls: an exact `get` keyed on artist + title + duration, and a fuzzy
 * `search` when that misses (YouTube Music titles carry "(From ...)" and
 * "| Official Video" noise that the exact endpoint won't match). Results are
 * line-synced `[mm:ss.xx]` LRC — LRCLIB has no word-level timing, so
 * highlighting is per line.
 */
object LrcLib {

    private const val BASE = "https://lrclib.net/api"
    private const val AGENT = "YZ Music (https://github.com/bitchord)"

    private val json = Json { ignoreUnknownKeys = true }

    /** Synced lyrics for a track, or null when nothing usable is published. */
    suspend fun lyrics(title: String, artist: String, durationMs: Long): List<LyricLine>? =
        withContext(Dispatchers.IO) {
            val cleanTitle = title.clean()
            val cleanArtist = artist.clean()
            val seconds = (durationMs / 1000).toInt()

            val exact = runCatching { exactMatch(cleanTitle, cleanArtist, seconds) }.getOrNull()
            val synced = exact ?: runCatching { bestSearchHit(cleanTitle, cleanArtist, seconds) }
                .getOrNull()
            synced?.let(::parseLrc)?.takeIf { it.isNotEmpty() }
        }

    private fun exactMatch(title: String, artist: String, seconds: Int): String? {
        val url = "$BASE/get".toHttpUrl().newBuilder()
            .addQueryParameter("track_name", title)
            .addQueryParameter("artist_name", artist)
            .addQueryParameter("duration", seconds.toString())
            .build()
        val body = get(url.toString()) ?: return null
        return (json.parseToJsonElement(body) as? JsonObject)
            ?.get("syncedLyrics")?.jsonPrimitive?.contentOrNull
    }

    /**
     * Fuzzy fallback. Prefers whichever hit is closest in length to what we're
     * actually playing — same song, different edit, would drift otherwise.
     */
    private fun bestSearchHit(title: String, artist: String, seconds: Int): String? {
        val url = "$BASE/search".toHttpUrl().newBuilder()
            .addQueryParameter("track_name", title)
            .addQueryParameter("artist_name", artist)
            .build()
