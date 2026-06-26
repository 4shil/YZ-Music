package com.music.yzmusic.data.canvas

import com.music.yzmusic.data.DebugLog as Log
import com.music.yzmusic.data.Http
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.util.Base64
import java.util.Locale

/**
 * Apple Music's motion artwork — the animated sleeves the web player shows on
 * an album page, exposed on the catalog API as `editorialVideo`.
 *
 * The richest of the three sources and the fussiest to reach. Two problems to
 * solve: the endpoint needs a bearer token, and a free-text search will return
 * a plausible-looking wrong album for almost any query.
 *
 * The token is the same read-only one the web player mints for anonymous
 * visitors, so it is scraped rather than owned — see [token]. The search is
 * scored rather than trusted: results are ranked on how well the artist, track
 * and album line up, compilations and radio-style playlists are dropped
 * outright, and anything that doesn't clear the bar is skipped even if it is
 * the only hit. A wrong canvas is worse than none.
 *
 * The clips are HLS, which is why the app carries the Media3 HLS extension.
 */
object AppleMusicCanvas {

    private const val TAG = "AppleMusicCanvas"
    private const val AMP = "https://amp-api.music.apple.com/v1/catalog"
    private const val WEB_PLAYER = "https://music.apple.com/us/browse"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Motion artwork is per storefront; use the device's if it looks sane. */
    val storefront: String by lazy {
        Locale.getDefault().country.takeIf { it.length == 2 }?.lowercase(Locale.ROOT) ?: "us"
    }

    fun search(title: String, artist: String, album: String?): CanvasArtwork? {
        val bearer = token() ?: return null

        // The catalog search is term-based, so fold everything we know into
        // the term — an artist name alone pulls in their whole discography and
        // the scoring below then has to reject all of it.
        val term = buildString {
            if (!title.contains(artist, ignoreCase = true)) append(artist).append(' ')
            append(title)
            if (!album.isNullOrBlank() && !title.contains(album, ignoreCase = true)) {
                append(' ').append(album)
            }
        }

        val url = "$AMP/$storefront/search".toHttpUrl().newBuilder()
            .addQueryParameter("term", term)
            .addQueryParameter("types", "songs")
            .addQueryParameter("limit", "10")
            .addQueryParameter("extend", "editorialVideo")
            .addQueryParameter("include", "albums")
            .build()
            .toString()

        val body = get(url, bearer) ?: return null
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        val hits = root["results"]?.jsonObject
            ?.get("songs")?.jsonObject
            ?.get("data")?.jsonArray
            ?: return null

        val ranked = hits.mapNotNull { hit ->
