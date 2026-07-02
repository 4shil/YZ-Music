package com.music.yzmusic.data.innertube

import android.os.SystemClock
import android.util.Log
import com.music.yzmusic.data.TrackLog
import com.music.yzmusic.data.Http
import com.music.yzmusic.data.NerdStats
import com.music.yzmusic.data.settings.AppSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.ConnectionPool
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.AccountTerminatedException
import org.schabi.newpipe.extractor.exceptions.AgeRestrictedContentException
import org.schabi.newpipe.extractor.exceptions.GeographicRestrictionException
import org.schabi.newpipe.extractor.exceptions.PaidContentException
import org.schabi.newpipe.extractor.exceptions.PrivateContentException
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.exceptions.SoundCloudGoPlusContentException
import org.schabi.newpipe.extractor.exceptions.UnsupportedContentInCountryException
import org.schabi.newpipe.extractor.exceptions.YoutubeMusicPremiumContentException
import org.schabi.newpipe.extractor.services.youtube.YoutubeJavaScriptPlayerManager
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import java.io.IOException
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.Locale

/**
 * Turns a videoId into a URL ExoPlayer can actually stream.
 *
 * Three things make or break this, and the order they are attempted in matters
 * as much as the mechanics of each:
 *
 *  1. **Which endpoint asks.** The `youtubei/v1/player` POST is one small JSON
 *     round trip. The watch page — what a full extractor scrape fetches — is
 *     several hundred kilobytes of HTML and is rate-shaped: under load Google
 *     answers its headers immediately and then feeds the body out over tens of
 *     seconds, or simply stops sending and never closes. That shaping is
 *     invisible as an error and reads to a listener as endless buffering, so
 *     the scrape is kept off the hot path entirely — see [newPipeUrl], the
 *     failsafe of last resort.
 *
 *  2. **Which client asks.** Google turns identities away without notice and
 *     without pattern: the client that works today answers `LOGIN_REQUIRED`
 *     next month. So [CLIENTS] is walked rather than trusted, the one that last
 *     worked is tried first, and one that is refused for a track is stood down
 *     for that track for a while.
 *
 *  3. **Whether the URL is real.** Every googlevideo URL carries an `n`
 *     parameter which, sent as-is, gets the response throttled to a crawl or
 *     refused with 403; it has to be transformed by running YouTube's own
 *     player JavaScript, which is what NewPipe's [YoutubeJavaScriptPlayerManager]
 *     does. That can fail quietly, and a URL can be dead on arrival for reasons
 *     no amount of care predicts — so nothing is handed to the player, or
 *     cached, until a single byte has been fetched from it. See [probe].
 */
object StreamResolver {

    private const val TAG = "YZ Music"

    /** Past this, an extractor fetch is worth flagging rather than just noting. */
    private const val SLOW_FETCH_MS = 2000L

    /** See [OkHttpDownloader.execute] — the one request the extractor is not allowed to make. */
    private const val NEXT_ENDPOINT = "/youtubei/v1/next"

    /** Well-formed, empty, and over the library's fifty-character floor. */
    private const val EMPTY_NEXT_RESPONSE =
        """{"responseContext":{},"contents":{},"currentVideoEndpoint":{},"trackingParams":""}"""

    /**
     * Player clients in the order they are worth asking, cheapest and most
     * reliable first — an order taken from what the live endpoint actually
     * answers, not from what ought to work.
     *
     * The four at the top return plain `url` fields, so a stream is one POST
     * away with no player JavaScript involved at all. [PlayerClient.ANDROID]
     * below them hands back ciphered formats, costing a download of that
     * JavaScript and a signature to solve. See each entry in [PlayerClient].
     *
     * No web client appears here. `WEB_REMIX` was the tail of this list and
     * paid for itself in neither reliability nor speed — always ciphered,
     * usually refused, and reached only on tracks that were already failing,
     * where the one thing left worth spending is time. [newPipeUrl] is the
     * last resort instead.
     *
     * The gating that decides which of these answers is applied per network,
     * not globally — an identity refused on one connection is served on
     * another — which is the whole reason this is a list and why the order is
     * only a starting guess that [clientOrder] corrects from experience.
     *
     * TVHTML5 (Cobalt v7) is first because it works on flagged IPs without
     * PO Token — the most reliable client as of July 2026.
     */
    private val CLIENTS = listOf(
        PlayerClient.ANDROID_MUSIC,
        PlayerClient.TVHTML5,
        PlayerClient.ANDROID_VR,
        PlayerClient.ANDROID_VR_LEGACY,
        PlayerClient.IOS,
        PlayerClient.IOS_RECENT,
        PlayerClient.ANDROID,
    )

    /** NewPipe needs a Downloader; reuse the app's single OkHttp client. */
    private class OkHttpDownloader : Downloader() {
        override fun execute(request: Request): Response {
            // The `next` endpoint answers "what plays after this" — related
            // videos and the autoplay queue. `fetchPage()` asks for it
            // unconditionally while building a StreamExtractor, and this app
            // never reads the answer: it is here for [audioStreams] and gets
            // its own up-next from [Innertube.next] on a different client.
            //
            // Declining it is worth a special case because of what it costs.
            // Measured across extractions, every other request in the chain
            // lands in 110-670ms, while this one takes seven seconds or simply
            // hangs — it was the single request behind
            // `extractor fetch FAILED after 12004ms`, and since one hung call
            // fails the whole attempt, an endpoint nothing here reads was
            // deciding whether a track played at all.
            //
            // Answered with an empty-but-well-formed envelope rather than an
            // error, so nothing downstream treats it as a failed fetch; NewPipe
            // finds no related items, which is exactly as many as are wanted.
            // It has to be this padded: the library rejects any JSON body under
            // fifty characters outright with "JSON response is too short", so a
            // bare `{}` fails the whole extraction rather than quietly
            // returning nothing.
            if (NEXT_ENDPOINT in request.url()) {
                return Response(200, "OK", emptyMap(), EMPTY_NEXT_RESPONSE, request.url())
            }
            val builder = okhttp3.Request.Builder()
                .method(request.httpMethod(), request.dataToSend()?.toRequestBody())
                .url(request.url())

            var hasUserAgent = false
            request.headers().forEach { (name, values) ->
                if (name.equals("User-Agent", ignoreCase = true) && values.isNotEmpty()) {
                    hasUserAgent = true
                }
                when {
                    values.size > 1 -> {
                        builder.removeHeader(name)
                        values.forEach { builder.addHeader(name, it) }
                    }
                    values.size == 1 -> builder.header(name, values[0])
                }
            }
            if (!hasUserAgent) {
                // Chrome, not Firefox: NewPipe's own internal fetches — the
                // player JS included — go out under whatever this default is.
                // PixelMusic-ref's equivalent downloader defaults to this same
                // Chrome UA and does not hit the "Could not parse
                // deobfuscation function" failure this app was getting on the
                // identical video and NewPipeExtractor version; a Firefox UA
                // here is the one input that differed.
                builder.header(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36",
                )
            }

            // Every byte NewPipe fetches passes through here, and until this
            // line none of it was visible: an extraction that took thirty
            // seconds reported thirty seconds, with nothing to say whether that
            // was one shaped watch page, a player POST, or the player
            // JavaScript. Naming the request and its size is what makes the
            // difference between measuring the step and guessing at it.
            val requestStart = SystemClock.elapsedRealtime()
            val response = try {
                extractorClient.newCall(builder.build()).execute()
            } catch (e: Exception) {
                // The one that matters most, and the one a log written only on
                // the way out never sees: a request that times out or is torn
                // down produces no line at all, so an extraction killed by a
                // single hung call looks like an extraction that was slow for
                // no reason. Named here, then rethrown unchanged.
                TrackLog.w(
                    TAG,
                    "extractor fetch FAILED after ${SystemClock.elapsedRealtime() - requestStart}ms " +
                        "${request.httpMethod()} ${request.url()}: ${e.javaClass.simpleName}: ${e.message}",
                )
                throw e
            }
            val took = SystemClock.elapsedRealtime() - requestStart
            if (took > SLOW_FETCH_MS) {
                TrackLog.w(TAG, "extractor fetch ${took}ms ${request.httpMethod()} ${request.url()}")
            } else {
                TrackLog.d(TAG, "extractor fetch ${took}ms ${request.httpMethod()} ${request.url()}")
            }
            if (response.code == 429) {
                response.close()
                throw ReCaptchaException("reCaptcha Challenge requested", request.url())
            }
            return Response(
                response.code,
                response.message,
                response.headers.toMultimap(),
                response.body?.string(),
                response.request.url.toString(),
            )
        }
    }

    private val init by lazy { NewPipe.init(OkHttpDownloader()) }

    /**
     * The extractor's own leash on [Http.client].
     *
     * Everything NewPipe fetches — the watch page above all — goes out through
     * here, and [Http.client] sets no `callTimeout` at all. Its 30-second read
     * timeout does not stand in for one: a read timeout is per read, so a
     * response that yields a few bytes at a time resets it forever and the call
     * never ends. That is not a hypothetical failure mode but the exact shaping
     * this file's header describes Google applying to the watch page, and it
     * was observed doing it — an extraction that simply never returned, twice
     * in a row, leaving a track buffering until ExoPlayer gave up and retried
     * into the same wall. Unbounded is the one thing this call must not be,
     * because it is the failsafe: nothing runs after it.
     *
     * It gets its **own connection pool**, and that is the point of it rather
     * than a detail. Sharing [Http.client]'s pool means the watch-page GET can
     * be handed a connection to `www.youtube.com` left over from an Innertube
     * `player` POST, and a pooled connection that the far end has quietly
     * stopped answering does not fail — it hangs, silently, until something
     * times it out. `retryOnConnectionFailure` cannot save it, because nothing
     * is failing. That is exactly the shape the measurements have: a first
     * attempt that burns the whole ceiling and a second, on a fresh connection,
     * that succeeds in 2.2 seconds. Its own pool means extraction never
     * inherits a socket some other part of the app finished with.
     *
     * Sharing the pool was never required, either. The address-family argument
     * in [Http] is about a googlevideo media fetch matching the `player`
     * request that minted its URL; this fetches HTML from `www.youtube.com`,
     * and the googlevideo URL it comes back with is fetched later through
     * [Http.client] regardless.
     *
     * The ceiling is sized against a healthy extraction — about two seconds —
     * rather than against patience. Twelve is generous enough that a merely
     * slow page still completes, and short enough that a hung one costs a few
     * seconds before [EXTRACTION_ATTEMPTS] tries again on a new connection,
     * instead of half a minute of silence.
     */
    private val extractorClient by lazy {
        Http.client.newBuilder()
            // Short, because a connection this app is *not* using is a
            // connection going stale. The observed failure is a request that
            // gets no response at all and dies on the ceiling exactly — twelve
            // thousand and one milliseconds, over and over, on three different
            // endpoints. That is not a slow server, it is a socket the far side
            // (or a NAT on the way) has silently dropped while it sat idle
            // between tracks, being handed to the next request as though it
            // were good. Half a minute of keepalive is short enough that most
            // are re-established rather than resurrected.
            .connectionPool(ConnectionPool(4, 30, TimeUnit.SECONDS))
            // And for the ones that go stale while held: HTTP/2 pings make the
            // client notice a dead peer itself, in seconds, instead of waiting
            // out a response that is never coming. These endpoints are all
            // HTTP/2, so this is the mechanism actually available for detecting
            // it rather than merely giving up on it.
            .pingInterval(PING_INTERVAL_SECONDS, TimeUnit.SECONDS)
            .callTimeout(EXTRACTOR_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }

    private const val PING_INTERVAL_SECONDS = 5L

    /**
     * Sized so that giving up and trying again is cheaper than waiting.
     *
     * A healthy extractor request lands in 100-700ms and the retry that follows
     * a hung one has, every time it has been watched, succeeded immediately —
     * so the ceiling is not protecting a slow-but-viable request, it is deciding
     * how long a dead connection costs. At twelve seconds one stall turned a
     * four-second start into twenty-six; at five, the same stall costs about
     * six seconds all in.
     */
    private const val EXTRACTOR_TIMEOUT_SECONDS = 5L

    /**
     * @return a directly streamable URL that has been proven to serve bytes,
     *   or throws with a reason worth showing.
     *
     * Results are held briefly — see [recent]. Resolving is the slow part of
     * starting a track, and ExoPlayer asks again for every re-open: each seek
     * outside the buffer, and each range the cache fills in.
     */
    suspend fun resolve(videoId: String): String {
        init

        recent[videoId]
            ?.takeIf { SystemClock.elapsedRealtime() - it.at < URL_TTL_MS }
            ?.let { return it.url }

        // A verdict, not a failure: asking again cannot change the answer, so
        // every caller after the first is told so without a request being sent.
        // See [rememberUnplayable] for why this is the fix for "stuck loading".
        unplayableReason(videoId)?.let { throw PermanentlyUnplayableException(it) }

        val stream = coalescedResolve(videoId)

        // The container carries no bitrate field, so this is the only place the
        // real figure is ever known.
        NerdStats.onStreamPicked(videoId, stream.kbps)
        remember(videoId, stream.url)
        return stream.url
    }

    /**
     * A track this app cannot play, for a reason that will read the same in ten
     * seconds — an age gate no session gets past, a takedown, a region block.
     *
     * Its own type because everything above the resolver has to be able to tell
     * it apart from a failure worth retrying, and the layers in between are
     * ExoPlayer's: a load error carries whatever exception it was given and
     * nothing else, so the distinction has to travel in the type. See
     * [PlaybackService][com.music.yzmusic.playback.PlaybackService]'s load-error
     * policy and `recoverFrom`.
     */
    class PermanentlyUnplayableException(reason: String) : IOException(reason)

    /**
     * Tracks that have already failed for a reason retrying cannot fix, and
     * until when.
     *
     * This is the single change that turns the observed failure — a track that
     * sits in BUFFERING for minutes on end, hammering youtubei — back into a
     * failure that happens once. Nothing above this object retries *less* than
     * three deep: ExoPlayer's own load-error policy retries the source, this
     * service's `recoverFrom` retries the player, and read-ahead resolves the
     * same track again on its own schedule. Against a permanent refusal every
     * one of those is a full client walk plus a triple extraction — measured in
     * the report at roughly twenty-seven walks and fifty youtubei requests in a
     * 2m41s window, for a track whose answer was settled by the first one.
     *
     * Entries expire rather than being permanent, because the reasons behind
     * them do: an age gate stops mattering the moment the listener signs in
     * (see [forgetUnplayable], called from the login flow), and Google's region
     * and bot verdicts are measured in hours, not sessions. Ten minutes is the
     * same budget [STAND_DOWN_MS] uses, for the same reason — long enough that
     * the storm cannot re-form, short enough that a listener who fixes the
     * cause does not have to restart the app.
     */
    private val unplayable = ConcurrentHashMap<String, Verdict>()

    private class Verdict(val reason: String, val at: Long)

    private fun unplayableReason(videoId: String): String? {
        val entry = unplayable[videoId] ?: return null
        if (SystemClock.elapsedRealtime() - entry.at < UNPLAYABLE_TTL_MS) return entry.reason
        unplayable.remove(videoId)
        return null
    }

    private fun rememberUnplayable(videoId: String, reason: String) {
        if (unplayable.size > MAX_REMEMBERED) unplayable.clear()
        unplayable[videoId] = Verdict(reason, SystemClock.elapsedRealtime())
    }

    /**
     * Forget every verdict recorded above.
     *
     * Signing in is the one event that can turn an age-gated track playable,
     * and signing out the one that can turn it back — so both have to clear
     * this, or the listener who signs in specifically to play a track is told
     * for the next ten minutes that it still cannot be played. The stand-downs
     * go with it: a client refused while anonymous is owed a fresh hearing now
     * that there is a session to send.
     */
