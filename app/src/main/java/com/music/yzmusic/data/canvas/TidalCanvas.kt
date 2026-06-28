package com.music.yzmusic.data.canvas

import com.music.yzmusic.data.DebugLog as Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.util.Locale

/**
 * Tidal's "video cover" — a square looping clip some albums ship instead of a
 * still sleeve.
 *
 * Read through the same public search endpoint Tidal's own embeddable player
 * uses, which needs no account: a track search returns the album object, the
 * album object carries a `videoCover` id, and that id expands into a fixed URL
 * on Tidal's CDN. Square and 1280px, so it drops into the sleeve without
 * cropping — the best-shaped source of the three.
 */
object TidalCanvas {

    private const val TAG = "TidalCanvas"
    private const val SEARCH = "https://api.tidal.com/v1/search"

    /** The token Tidal's public embed player ships; read-only, no account. */
    private const val EMBED_TOKEN = "vNVdglQOjFJJGG2U"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Tidal's catalogue is regional, and so is which albums have a cover. */
    private val countryCode: String by lazy {
        Locale.getDefault().country.takeIf { it.length == 2 }?.uppercase(Locale.ROOT) ?: "US"
    }

    fun search(title: String, artist: String, album: String?): CanvasArtwork? {
