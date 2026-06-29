package com.music.yzmusic.data.discord

import android.os.Build
import android.util.Base64
import org.json.JSONObject
import java.util.Locale
import java.util.UUID

/**
 * The `X-Super-Properties` header Discord's own Android client sends on every
 * request, reproduced field for field.
 *
 * The gateway will accept a presence without it, but the REST endpoint that
 * proxies external artwork (see
 * [fetchExternalAsset][com.my.kizzy.rpc.fetchExternalAsset]) treats a request
 * with no client fingerprint as suspicious and starts returning 401s — so the
 * cover art silently stops appearing while the rest of the presence keeps
 * working. Sending this makes the call look like what it is impersonating.
 *
 * The UUIDs are generated once per process and cached: rotating them per
 * request is exactly what a real client never does.
 */
object SuperProperties {
    // Constants from research for Discord Android 314.13
    private const val CLIENT_VERSION = "314.13 - Stable"
    private const val CLIENT_BUILD_NUMBER = 314013
    private const val RELEASE_CHANNEL = "googleRelease"

    // Lazy loaded properties to avoid re-generating UUIDs
    val superProperties: JSONObject by lazy {
        JSONObject().apply {
            put("os", "Android")
            put("browser", "Discord Android")
            put("device", Build.DEVICE)
