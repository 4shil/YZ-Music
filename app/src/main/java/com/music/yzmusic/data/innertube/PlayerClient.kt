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
