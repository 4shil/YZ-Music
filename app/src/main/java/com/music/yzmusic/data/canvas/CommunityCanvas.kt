package com.music.yzmusic.data.canvas

import com.music.yzmusic.data.DebugLog as Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * A community-curated `song + artist -> looping video` index, published as one
 * JSON file and mirrored by whoever maintains it.
 *
 * The catalogue services only have motion artwork where a label paid to make
 * some, which is a thin slice of anything outside current chart releases. This
 * fills the gaps by hand: small, hit-or-miss, and the only source here that
 * ever covers back catalogue.
 *
 * One file for the whole index means one request and then local lookups, so
 * the manifest is held for [TTL_MS] rather than re-fetched per track.
 */
object CommunityCanvas {

    private const val TAG = "CommunityCanvas"
    private const val MANIFEST = "https://vivimusicanvas.mkmdevilmi.workers.dev/canvas.json"
    private const val TTL_MS = 30L * 60 * 1000

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private data class Entry(
        val song: String,
        val artist: String,
        val album: String,
        val url: String,
    )

    @Volatile private var entries: List<Entry> = emptyList()
    @Volatile private var fetchedAtMs = 0L

    fun search(title: String, artist: String, album: String?): CanvasArtwork? {
        val index = manifest().ifEmpty { return null }

        val wantTitle = title.normalizeForMatch()
        val wantArtist = artist.normalizeForMatch()
        val wantAlbum = album?.normalizeForMatch()

        // Contributors write titles as they please — "Song" against "Song
        // (Official Video)" — so this side matches on containment rather than
        // equality. That is looser than the catalogue providers get, and the
        // album check is what keeps it honest when we know one.
        val hit = index.firstOrNull { entry ->
            val song = entry.song.normalizeForMatch()
            val credited = entry.artist.normalizeForMatch()
            val listed = entry.album.normalizeForMatch()
