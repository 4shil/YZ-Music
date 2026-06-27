package com.music.yzmusic.data.canvas

import com.music.yzmusic.data.DebugLog as Log
import com.music.yzmusic.data.Http
import com.google.protobuf.CodedInputStream
import com.google.protobuf.CodedOutputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream

/**
 * Spotify's own Canvas — the feature these other providers are named after.
 *
 * There is no public API for it. The web player fetches it from an internal
 * endpoint ([CANVAS_URL]) using protobuf over HTTP, authenticated with a
 * bearer token minted from the listener's own session — see [SpotifyToken].
 * That means this source is the one of the four that needs the listener to
 * hand something over first (Settings > their `sp_dc` session cookie); with
 * no cookie set [SpotifyToken.accessToken] returns null before any request is
 * made, so this is a free no-op until then.
 *
 * The wire format is hand-rolled rather than generated: the message shapes
 * are tiny — one string in, a handful of strings out — and pulling in protoc
 * codegen for two fields is not worth the build-time dependency. Field
 * numbers below are Spotify's own, not chosen by us; get them wrong and the
 * response silently parses to nothing rather than failing loudly, so they
 * are commented with what each one actually is.
 */
object SpotifyCanvas {

    private const val TAG = "SpotifyCanvas"
    private const val SEARCH_URL = "https://api.spotify.com/v1/search"
    private const val ALBUM_TRACKS_URL = "https://api.spotify.com/v1/albums"
    private const val CANVAS_URL = "https://spclient.wg.spotify.com/canvaz-cache/v0/canvases"
    private const val PATHFINDER_URL = "https://api-partner.spotify.com/pathfinder/v1/query"

    /** The persisted query id for the web player's own track search. */
    private const val PATHFINDER_SEARCH_HASH =
        "bc1ca2fcd0ba1013a0fc88e6cc4f190af501851e3dafd3e1ef85840297694428"

    /**
     * spclient gates this path to Spotify's own apps by user agent; the web
     * player's own UA is turned away, so this wears a mobile client's instead.
     */
    private const val SPOTIFY_APP_UA = "Spotify/9.0.34.593 iOS/18.4 (iPhone15,3)"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val CANVAS_URL_REGEX = Regex("""https://[^"'\s\x00-\x1F]+\.cnvs\.mp4""")

    private data class TrackHit(val uri: String, val title: String, val artist: String, val album: String?)

    suspend fun search(title: String, artist: String, album: String?): CanvasArtwork? {
        val token = SpotifyToken.accessToken()
        if (token == null) {
            Log.d(TAG, "no access token (cookie unset or mint failed); skipping")
            return null
        }

        // Pathfinder first: it's the search the web player's own search box
        // calls, and in practice the one this kind of token is reliably let
        // near — the plain REST endpoint below answers some requests and
        // 429s others in a pattern that hasn't been possible to pin down from
        // outside Spotify. Kept as the fallback rather than dropped, since a
        // client token failing to mint takes Pathfinder down with it but
        // leaves REST still reachable.
        val hit = searchViaPathfinder(title, artist, album, token)
            ?: searchViaRest(title, artist, album, token)
        if (hit == null) {
            Log.d(TAG, "no canvas for '$title' by '$artist' (no matching track found)")
            return null
        }

        val canvasUrl = fetchCanvasUrl(hit.uri, token)
        if (canvasUrl == null) {
            Log.d(TAG, "no canvas for '$title' by '$artist' (matched '${hit.title}', no clip)")
            return null
        }
        Log.d(TAG, "canvas for '${hit.title}' by '${hit.artist}'")
        return CanvasArtwork(
            url = canvasUrl,
            title = hit.title,
            artist = hit.artist,
            album = hit.album,
            source = CanvasSource.SPOTIFY,
        )
    }

    /**
     * Doesn't re-check the hit's title and artist the way [searchViaRest]
     * does: the fields a GraphQL response like this carries aren't published
     * anywhere, so pulling `name`/`artists` back out the way the REST
     * response's documented shape allows would be a guess. Spotify's own
     * relevance ranking on a "title artist album" query is trusted instead,
     * the same way [firstTrackUri] trusts an album's own track listing rather
     * than re-checking it.
     */
    private fun searchViaPathfinder(title: String, artist: String, album: String?, token: String): TrackHit? {
        val clientToken = SpotifyToken.clientToken()
        if (clientToken == null) {
            Log.d(TAG, "no client token; skipping pathfinder search")
            return null
        }
        val searchTerm = listOfNotNull(title, artist, album).joinToString(" ")
        val variables = buildJsonObject {
            put("searchTerm", searchTerm)
            put("offset", 0)
            put("limit", 10)
            put("numberOfTopResults", 5)
            put("includeAudiobooks", false)
            put("includePreReleases", false)
        }.toString()
        val extensions = buildJsonObject {
            putJsonObject("persistedQuery") {
                put("version", 1)
                put("sha256Hash", PATHFINDER_SEARCH_HASH)
            }
        }.toString()

