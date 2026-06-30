package com.music.yzmusic.data.innertube

import com.music.yzmusic.data.DebugLog as Log
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import com.music.yzmusic.data.model.LikeStatus
import com.music.yzmusic.data.model.PlaylistPrivacy
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonArrayBuilder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale

/**
 * Minimal Innertube (youtubei) client.
 *
 * Two kinds of client identity, for different reasons:
 *
 *  - **WEB_REMIX** against music.youtube.com for browse/search/library. It
 *    returns the full YT Music shelf layout and honours the signed-in session.
 *
 *  - **A device client** for the `player` endpoint, chosen per call. Which
 *    ones Google answers changes without notice, so [player] takes the
 *    identity as an argument and [StreamResolver] walks a list of them rather
 *    than betting the app on any single one. See [PlayerClient].
 *
 * Authenticated requests are signed with Google's SAPISIDHASH scheme derived
 * from the stored cookie; no long-lived token is ever minted or stored.
 */
object Innertube {

    private const val MUSIC_BASE = "https://music.youtube.com/youtubei/v1"
    private const val YT_BASE = "https://www.youtube.com/youtubei/v1"
    private const val MUSIC_ORIGIN = "https://music.youtube.com"
    private const val YOUTUBE_ORIGIN = "https://www.youtube.com"

    /**
     * Fallback WEB_REMIX version, used until [SessionScope] reads the live one
     * out of the music.youtube.com shell. Only a starting point: the real
     * version moves every few days, and the one that matters is the one
     * [webRemixVersion] reports.
     */
    private const val WEB_REMIX_VERSION = "1.20250101.01.00"
    private const val WEB_REMIX_CLIENT_ID = "67"

    private const val TAG = "YZ Music"

    /** Session cookie captured by the login WebView; null = browse as guest. */
    var cookie: String? = null
        set(value) {
            if (field != value) {
                // Both belong to the session that just left. A scope kept across
                // a sign-in would credit the new account's plays to the old one,
                // and a visitor id minted under the old session is not bound to
                // the new one — see [SessionScope] and [visitorData].
                scope = null
                visitorData = null
                visitorDataIsSessionBound = false
            }
            field = value
        }

    /**
     * Google's per-session visitor id.
     *
     * Far more load-bearing than "an id for stats". A `player` request that
     * carries no visitor id is treated as a client with no session at all, and
     * Google answers it in one of two ways: the honest one, `LOGIN_REQUIRED` /
     * "Sign in to confirm you're not a bot", or the quiet one — a perfectly
     * ordinary-looking response whose stream URLs serve a byte to anything that
     * asks and then refuse every real read with 403. The second is what
     * "it loads and then doesn't play" is made of.
     *
     * So it is fetched deliberately by [ensureVisitorData] rather than being
     * hoped for: browse responses carry one only sometimes, and a session that
     * never happened to see one would silently never play anything.
     */
    @Volatile
    private var visitorData: String? = null

    /**
     * Whether [visitorData] came from the signed-in shell rather than being
     * minted anonymously.
     *
     * A session-bound id outranks an anonymous one and must not be replaced by
     * it. Both are fetched near startup and nothing orders them, so without this
     * the better id was lost to whichever request happened to finish second.
     */
    @Volatile
    private var visitorDataIsSessionBound = false

    /**
     * A visitor id for this session, minting one if there isn't one yet.
     *
     * @param refresh discard the current id and take a fresh one — worth doing
     *   exactly once when a request comes back accusing us of being a bot,
     *   since an id can be burned while the session around it is fine.
     */
    suspend fun ensureVisitorData(refresh: Boolean = false): String? {
        if (!refresh && visitorData != null) return visitorData
        runCatching { fetchVisitorData() }
            .onFailure { Log.w(TAG, "could not mint a visitor id: ${it.message}") }
            .getOrNull()
            ?.let {
                if (refresh || !visitorDataIsSessionBound) {
                    visitorData = it
                    visitorDataIsSessionBound = false
                }
            }
        return visitorData
    }

    /**
     * The service worker bootstrap the web player loads before anything else,
     * which is where a fresh visitor id comes from without needing a page.
     * It answers with an anti-hijacking prefix and then plain nested arrays,
     * so the id is found by shape rather than by a path that would rot.
     */
    private suspend fun fetchVisitorData(): String? {
        val body = client.get("https://www.youtube.com/sw.js_data") {
            header("User-Agent", WEB_USER_AGENT)
        }.bodyAsText()
        val payload = Json.parseToJsonElement(body.substringAfter("\n", body.drop(5)))
        return findVisitorData(payload)
    }

    private fun findVisitorData(element: JsonElement): String? = when (element) {
        is JsonArray -> element.firstNotNullOfOrNull { findVisitorData(it) }
        is JsonPrimitive -> element.contentOrNull?.takeIf { VISITOR_DATA.matches(it) }
        else -> null
    }

    /** Protobuf-in-base64; always this shape, and nothing else in there is. */
    private val VISITOR_DATA = Regex("""Cg[A-Za-z0-9_%-]{40,}""")

    // ---- Which account is this, exactly -------------------------------------

    /**
     * Who the session cookie actually acts as, and which client version it acts
     * with — read out of the signed-in music.youtube.com shell.
     *
     * A cookie is not an account. One Google login carries every account the
     * browser has ever signed into, plus every brand channel hanging off them,
     * and *nothing in the cookie says which one is meant*. The web client
     * resolves that from its page config and then says so on every request. An
     * app that skips this step is not making an ambiguous request — it is
     * making a request about the first account in the jar, whoever that is.
     *
     * That is the whole of "history works for me and not for them": for a
     * listener whose YouTube Music account *is* the first one, guessing is
     * indistinguishable from asking. For anyone with two Google accounts, or a
     * brand channel — the account YouTube Music itself pushes you onto when you
     * have one — every play was being credited to the wrong identity, so their
     * own history stayed empty no matter how many pings went out successfully.
     *
     * @param dataSyncId the account, as `context.user.onBehalfOfUser`. Only
     *   ever taken from a shell that reported itself signed in: Google answers
     *   an `onBehalfOfUser` it cannot tie to a session with 401, so a guessed
     *   value would break every request in the app rather than just history.
     * @param pageId the brand channel, as `X-Goog-PageId` — the header the
     *   stats endpoints ask for by name as `PLUS_PAGE_ID`. Absent for a plain
     *   personal account, which is why it is nullable rather than defaulted.
     * @param authUser which entry in the cookie jar, as `X-Goog-AuthUser`.
     *   Hardcoded `0` before this, which is the same guess by another name.
     */
    private class SessionScope(
        val dataSyncId: String?,
        val pageId: String?,
        val authUser: String,
        val clientVersion: String?,
    )

    @Volatile
    private var scope: SessionScope? = null

    private val scopeLock = Mutex()

    /**
     * The WEB_REMIX version to claim, live if the shell has been read.
     *
     * Worth taking from the shell rather than pinning: the stats pings carry it
     * as `cver`, and a version Google has never shipped is a standing invitation
     * to be treated as something other than a music client.
     */
    private val webRemixVersion: String
        get() = scope?.clientVersion ?: WEB_REMIX_VERSION

    /**
     * Reads the session scope, once per cookie, before anything that depends on
     * being the right account.
     *
     * Cheap to be wrong about and expensive to skip, so it fails open: a shell
     * that cannot be fetched or parsed leaves [scope] null and every request
     * behaves exactly as it did before. What it must never do is invent a
     * [SessionScope.dataSyncId] — see that field.
     */
    suspend fun ensureSessionScope() {
        val session = cookie ?: return
        if (scope != null) return
        scopeLock.withLock {
            if (scope != null || cookie != session) return
            runCatching { fetchSessionScope(session) }
                .onFailure { Log.w(TAG, "could not read the session scope: ${it.message}") }
                .getOrNull()
                ?.let { fresh ->
                    scope = fresh
                    Log.d(
                        TAG,
                        "session scope: authUser=${fresh.authUser} " +
                            "pageId=${fresh.pageId ?: "none"} " +
                            "dataSyncId=${if (fresh.dataSyncId != null) "present" else "none"} " +
                            "cver=${fresh.clientVersion ?: WEB_REMIX_VERSION}",
                    )
                }
        }
    }

    /**
     * The music.youtube.com shell, fetched with the session, for its `ytcfg`.
     *
     * Read by regex rather than by evaluating the config blob: it is one script
     * assignment among hundreds of kilobytes of app JavaScript, and the four
     * values wanted are flat strings in it. A key that moves reads as absent,
     * which is the same as not having asked.
     */
    private suspend fun fetchSessionScope(session: String): SessionScope? {
        val html = client.get("$MUSIC_ORIGIN/") {
            header("User-Agent", WEB_USER_AGENT)
            header("Accept-Language", "en-US,en;q=0.9")
            header("Cookie", session)
            sapisidFrom(session)?.let { header("Authorization", sapisidHash(it)) }
        }.bodyAsText()

        // The one value that must not be guessed. A shell that says it is
        // signed out either has a dead cookie or was served to nobody in
        // particular; either way its DATASYNC_ID belongs to no account, and
        // sending it would 401 every request in the app.
        val signedIn = CONFIG_LOGGED_IN.find(html)?.groupValues?.get(1) == "true"
        val clientVersion = CONFIG_CLIENT_VERSION.find(html)?.groupValues?.get(1)
        if (!signedIn) {
            Log.w(TAG, "music.youtube.com served a signed-out shell; not scoping requests")
            // Still worth the client version — that part is true either way.
            return clientVersion?.let { SessionScope(null, null, "0", it) }
        }

        // `<accountSyncId>||<sessionSyncId>`; only the first half identifies
        // the account, and the second changes on its own schedule.
