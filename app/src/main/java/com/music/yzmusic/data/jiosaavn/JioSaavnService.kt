package com.music.yzmusic.data.jiosaavn

import android.util.Base64
import com.music.yzmusic.data.TrackLog
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

@Serializable
data class RawArtistMapItem(
    val id: String = "",
    val name: String = "",
)

@Serializable
data class RawArtistMap(
    @SerialName("primary_artists") val primaryArtists: List<RawArtistMapItem> = emptyList(),
)

@Serializable
data class RawMoreInfo(
    val album_id: String = "",
    val album: String = "",
    @SerialName("encrypted_media_url") val encryptedMediaUrl: String = "",
    val duration: String = "",
    /**
     * Whether a 320kbps rendition exists, as `"true"`/`"false"`.
     *
     * The catalogue states this per track and it is frequently false. Asking
     * the CDN for `_320` anyway does not produce one.
     */
    @SerialName("320kbps") val has320: String = "",
    val artistMap: RawArtistMap = RawArtistMap(),
) {
    val supports320: Boolean get() = has320.equals("true", ignoreCase = true)
}

/** A decoded CDN URL and the bitrate it will really deliver. */
data class SaavnStream(val url: String, val kbps: Int?)

@Serializable
data class RawSongItem(
    val id: String = "",
    val title: String = "",
    val image: String = "",
    @SerialName("more_info") val moreInfo: RawMoreInfo = RawMoreInfo()
)

@Serializable
data class RawSearchResponse(
    val results: List<RawSongItem> = emptyList()
)

@Serializable
data class RawSongsResponse(
    val songs: List<RawSongItem> = emptyList()
)

object JioSaavnService {
    private const val TAG = "YZ Music"
    
    // https://www.jiosaavn.com/api.php
    private val BASE_URL = String(Base64.decode("aHR0cHM6Ly93d3cuamlvc2Fhdm4uY29tL2FwaS5waHA=", Base64.DEFAULT), Charsets.UTF_8)

    private val json = Json {
        isLenient = true
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    // Note: YZ Music uses OkHttp engine for ktor
    private val client by lazy {
        HttpClient(OkHttp) {
            install(ContentNegotiation) { json(json) }
            install(HttpTimeout) {
                requestTimeoutMillis = 6_000
                connectTimeoutMillis = 4_000
                socketTimeoutMillis = 6_000
            }
            defaultRequest {
                url(BASE_URL)
                headers.append(HttpHeaders.Accept, "application/json")
                headers.append(HttpHeaders.UserAgent, "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36")
                headers.append("X-Forwarded-For", "49.36.0.1")
                headers.append("X-Real-IP", "49.36.0.1")
                headers.append("Accept-Language", "en-IN,en;q=0.9")
                headers.append(HttpHeaders.Cookie, "explicit_content=1")
            }
            expectSuccess = false
        }
    }

    private fun decryptUrl(encryptedUrl: String): String {
        if (encryptedUrl.isBlank()) return ""
        return try {
            val key = "38346591" // DES 8-byte key
