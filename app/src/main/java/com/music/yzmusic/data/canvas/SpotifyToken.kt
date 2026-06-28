package com.music.yzmusic.data.canvas

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import com.music.yzmusic.data.DebugLog as Log
import com.music.yzmusic.data.Http
import com.music.yzmusic.data.settings.AppSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Base64

/**
 * The bearer token behind Spotify's own web player, minted from the
 * listener's session cookie ([AppSettings.spotifySpdcToken]) rather than an
 * app credential — there is no public API for a track's Canvas, so this walks
 * the same door the web player itself uses to fetch one.
 *
 * Call [init] once at process start, same as [CanvasCache] and the other
 * app-scoped singletons — the WebView harvest below needs a [Context] and
 * none of the suspend call chain that reaches [accessToken] has one to hand.
 */
internal object SpotifyToken {

    private const val TAG = "SpotifyToken"
    private const val BRIDGE_NAME = "YZMusicSpotifyTokenBridge"
    private const val HARVEST_TIMEOUT_MS = 20_000L
    private const val DEFAULT_TOKEN_LIFETIME_MS = 3_600_000L

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val harvestMutex = Mutex()

    @Volatile private var appContext: Context? = null

    @Volatile private var cachedAccessToken: String? = null
    @Volatile private var accessTokenExpiresAtMs = 0L
    @Volatile private var cachedClientId: String? = null

    @Volatile private var cachedSession: SessionInfo? = null
    @Volatile private var cachedClientToken: String? = null
    @Volatile private var clientTokenExpiresAtMs = 0L

    private data class SessionInfo(val clientVersion: String, val deviceId: String)
    private data class HarvestedToken(val token: String, val expiresAt: Long, val clientId: String?)

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /**
     * The current bearer token, or null when there is no cookie to mint one
     * from, [init] was never called, or the harvest failed. Cached until
     * shortly before it expires so a skip through a queue doesn't pay for
     * this per track.
     *
     * Minted by loading the real web player in an offscreen WebView with the
     * listener's cookie applied and reading the token it mints for itself —
     * see [harvestViaWebView] for why signing the request ourselves isn't
     * enough.
     */
    suspend fun accessToken(): String? {
        val cookie = AppSettings.spotifySpdcToken.value
        if (cookie.isBlank()) return null

        val now = System.currentTimeMillis()
        cachedAccessToken?.let { if (now < accessTokenExpiresAtMs - 30_000) return it }

        return harvestMutex.withLock {
            val stillNow = System.currentTimeMillis()
            cachedAccessToken?.let { if (stillNow < accessTokenExpiresAtMs - 30_000) return@withLock it }

            val context = appContext
            if (context == null) {
                Log.w(TAG, "SpotifyToken.init was never called; no context for the harvest")
                return@withLock null
            }

            val harvested = withContext(Dispatchers.Main) { harvestViaWebView(context, cookie) }
            if (harvested == null) {
                Log.w(TAG, "token harvest failed or timed out")
                return@withLock null
            }

            cachedAccessToken = harvested.token
            accessTokenExpiresAtMs = harvested.expiresAt
            harvested.clientId?.let { cachedClientId = it }
            Log.d(TAG, "harvested access token, good until ${java.util.Date(harvested.expiresAt)}")
            harvested.token
        }
    }

    /**
     * Spotify retired the endpoint this used to hit directly with a signed
     * request; its replacement (`/api/token`) checks a TOTP the web player
     * computes from a secret buried in its own JS bundle. That part is
     * reproducible — the secret is short-lived but published — and doing so
     * does get a 200 back with a token. What it doesn't get back is a token
     * anything downstream honours: api.spotify.com and spclient both turn a
     * forged one away with a 429 that reads exactly like rate limiting, right
     * up until it fires on the very first request of a session. So rather
     * than sign the request ourselves, this loads open.spotify.com for real
     * in an offscreen WebView with the cookie applied, and reads the token
     * the page mints for itself by hooking fetch/XHR before its own bundle
     * runs.
     */
    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun harvestViaWebView(context: Context, cookie: String): HarvestedToken? {
