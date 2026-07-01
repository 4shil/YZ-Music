package com.music.yzmusic.data.innertube

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.Locale

/**
 * One client identity for the `player` endpoint.
 *
 * YouTube hands out stream URLs per client, and which clients are answered
 * changes without notice: an identity that returns `OK` today answers
 * `LOGIN_REQUIRED` next month, and one that is merely *old* is refused with a
 * bare HTTP 400 before playability is even considered. So this is a list to
 * walk rather than a constant — see [StreamResolver].
 *
 * Three things travel together and must not be separated:
 *
 *  - **[userAgent]**, which the media fetch has to repeat. googlevideo bakes
 *    the client into the URL as `c=`/`cver=` and compares it against the
 *    headers of the request that comes back for the bytes.
 *  - **[origin]**, sent only by the browser-shaped clients, and pointing at
 *    the host that client actually runs on. Native app clients send none, and
 *    sending one anyway is as wrong as omitting it from a web client.
 *  - **[needsSignatureTimestamp]**, which decides whether the player request
 *    has to carry a timestamp lifted from YouTube's player JavaScript. The
 *    clients that need it are the ones that answer with ciphered formats.
 *
 * The versions here are load-bearing and were checked against the live
 * endpoint rather than copied from anywhere; see each one's note.
 */
data class PlayerClient(
    val clientName: String,
    val clientVersion: String,
    val clientId: String,
    val userAgent: String,
    val osName: String? = null,
    val osVersion: String? = null,
    val deviceMake: String? = null,
    val deviceModel: String? = null,
    val androidSdkVersion: String? = null,
    /** The host this client runs on, for browser-shaped clients only. */
    val origin: String? = null,
    /** Ciphered formats can't be unlocked without one. */
    val needsSignatureTimestamp: Boolean = false,
) {
    val referer: String? get() = origin?.let { "$it/" }

    /** Browser-shaped clients are served from their own host; app clients from YouTube proper. */
    val usesMusicHost: Boolean get() = origin == MUSIC_ORIGIN

    /**
     * Headers the *media* request must carry for a URL this client minted.
     *
     * The stream fetch is a separate request from the one that produced the
     * URL, and googlevideo treats a mismatch between the two as reason enough
     * to throttle the response to a crawl or refuse it with 403.
     */
    fun mediaHeaders(): Map<String, String> = buildMap {
        put("User-Agent", userAgent)
        origin?.let { put("Origin", it) }
        referer?.let { put("Referer", it) }
    }

    companion object {
        private const val MUSIC_ORIGIN = "https://music.youtube.com"
        private const val YOUTUBE_ORIGIN = "https://www.youtube.com"

        private const val WEB_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36"

        /**
         * iPhone YouTube, and the one that carries the session in practice: it
         * is answered without a login, without a proof of origin token and
         * without a signature timestamp, and it returns plain `url` fields —
         * so a stream is one POST away with no player JavaScript in the path.
         *
         * The version is the whole ballgame. Anything Google considers stale is
         * refused with an HTTP 400 before playability is looked at, which is
         * not a "try the next format" failure but a "this identity is dead"
         * one. These are current as of July 2026.
         */
        val IOS = PlayerClient(
            clientName = "IOS",
            clientVersion = "21.26.4",
            clientId = "5",
            userAgent = "com.google.ios.youtube/21.26.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)",
            osName = "iPhone",
            osVersion = "18.3.2.22D82",
            deviceMake = "Apple",
            deviceModel = "iPhone16,2",
        )

        /** A newer build of the same app: refused on a different schedule. */
