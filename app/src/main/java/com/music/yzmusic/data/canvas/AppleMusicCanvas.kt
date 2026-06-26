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
            val song = hit as? JsonObject ?: return@mapNotNull null
            val score = score(song, title, artist, album) ?: return@mapNotNull null
            score to song
        }.sortedByDescending { it.first }

        for ((score, song) in ranked) {
            if (score < MIN_SCORE) {
                Log.d(TAG, "no hit scored above $MIN_SCORE for '$title' (best was $score)")
                break
            }
            val attributes = song["attributes"]?.jsonObject ?: continue
            val songName = attributes["name"]?.jsonPrimitive?.contentOrNull
            val songArtist = attributes["artistName"]?.jsonPrimitive?.contentOrNull
            val albumName = attributes["albumName"]?.jsonPrimitive?.contentOrNull

            // Some searches already carry the motion artwork inline, which
            // saves the album round trip entirely.
            attributes["editorialVideo"]?.jsonObject?.let { video ->
                motionUrls(video)?.let { (primary, alternate) ->
                    Log.d(TAG, "inline motion artwork for '$songName'")
                    return CanvasArtwork(primary, alternate, songName, songArtist, albumName)
                }
            }

            val albumId = albumId(song) ?: continue
            fetchAlbum(albumId, bearer, songName, songArtist)?.let { return it }
        }
        return null
    }

    /**
     * Motion artwork for a release rather than a track, for the album page.
     *
     * Simpler than the song path: albums carry `editorialVideo` inline on the
     * search result, so there is no second lookup to resolve an id first.
     */
    fun searchAlbum(album: String, artist: String): CanvasArtwork? {
        val bearer = token() ?: return null
        val term = if (album.contains(artist, ignoreCase = true)) album else "$artist $album"

        val url = "$AMP/$storefront/search".toHttpUrl().newBuilder()
            .addQueryParameter("term", term)
            .addQueryParameter("types", "albums")
            .addQueryParameter("limit", "10")
            .addQueryParameter("extend", "editorialVideo")
            .build()
            .toString()

        val body = get(url, bearer) ?: return null
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        val hits = root["results"]?.jsonObject
            ?.get("albums")?.jsonObject
            ?.get("data")?.jsonArray
            ?: return null

        val ranked = hits.mapNotNull { hit ->
            val record = hit as? JsonObject ?: return@mapNotNull null
            // An album is its own "album" as far as the scoring goes, which is
            // what keeps a deluxe edition from outranking the plain one.
            val score = score(record, album, artist, album, albumIsSelf = true)
                ?: return@mapNotNull null
            score to record
        }.sortedByDescending { it.first }

        for ((score, record) in ranked) {
            if (score < MIN_SCORE) break
            val attributes = record["attributes"]?.jsonObject ?: continue
            val name = attributes["name"]?.jsonPrimitive?.contentOrNull
            if (name != null && isCompilation(name)) continue
            val video = attributes["editorialVideo"]?.jsonObject ?: continue
            val (primary, alternate) = motionUrls(video) ?: continue

            Log.d(TAG, "motion artwork for album '$name'")
            return CanvasArtwork(
                url = primary,
                fallbackUrl = alternate,
                title = name,
                artist = attributes["artistName"]?.jsonPrimitive?.contentOrNull,
                album = name,
            )
        }
        return null
    }

    // ---- Search scoring ------------------------------------------------

    /**
     * The floor a hit has to clear. An exact artist and an exact title alone
     * reach 25, so this only ever admits a result that matched on both, or one
     * that matched the artist plus a fuzzy title and the right album.
     */
    private const val MIN_SCORE = 12

    /**
     * How well a search hit lines up with what's playing, or null to reject it
     * outright. Artist is a gate rather than a score: a clip credited to
     * someone else is never the right one, however well the title reads.
     */
    private fun score(
        song: JsonObject,
        title: String,
        artist: String,
        album: String?,
        // An album result has no `albumName` of its own — it *is* the album.
        albumIsSelf: Boolean = false,
    ): Int? {
        val attributes = song["attributes"]?.jsonObject ?: return null
        val hitName = attributes["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val hitArtist = attributes["artistName"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val hitAlbum = if (albumIsSelf) {
            hitName
        } else {
            attributes["albumName"]?.jsonPrimitive?.contentOrNull.orEmpty()
        }

        if (isCompilation(hitName) || isCompilation(hitAlbum)) return null

        val wanted = splitArtists(artist)
        val credited = splitArtists(hitArtist)
        if (wanted.isEmpty() || credited.isEmpty()) return null
        if (!wanted.all { want -> credited.any { it == want } }) return null

        var score = 10

        val wantTitle = title.normalizeForMatch()
        val hitTitle = hitName.normalizeForMatch()
        score += when {
            hitTitle == wantTitle -> 15
            hitTitle.contains(wantTitle) || wantTitle.contains(hitTitle) -> 7
            // Same artist, different song. Apple returns these freely and
            // they are exactly the mismatch that has to be kept out.
            else -> -10
        }

        if (!album.isNullOrBlank() && hitAlbum.isNotBlank()) {
            val wantAlbum = album.normalizeForMatch()
            val gotAlbum = hitAlbum.normalizeForMatch()
            score += when {
                gotAlbum == wantAlbum -> 20
                gotAlbum.contains(wantAlbum) || wantAlbum.contains(gotAlbum) -> 10
                else -> 0
            }
        }

        // A "(Deluxe)" or "(Remastered)" on one side only is a different
        // master of the same track, and often a different clip.
        for (word in EDITION_WORDS) {
