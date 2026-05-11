package com.my.kizzy.gateway

import android.os.SystemClock
import com.my.kizzy.gateway.entities.Heartbeat
import com.my.kizzy.gateway.entities.Identify.Companion.toIdentifyPayload
import com.my.kizzy.gateway.entities.Payload
import com.my.kizzy.gateway.entities.Ready
import com.my.kizzy.gateway.entities.Resume
import com.my.kizzy.gateway.entities.op.OpCode
import com.my.kizzy.gateway.entities.op.OpCode.DISPATCH
import com.my.kizzy.gateway.entities.op.OpCode.HEARTBEAT
import com.my.kizzy.gateway.entities.op.OpCode.HEARTBEAT_ACK
import com.my.kizzy.gateway.entities.op.OpCode.HELLO
import com.my.kizzy.gateway.entities.op.OpCode.IDENTIFY
import com.my.kizzy.gateway.entities.op.OpCode.INVALID_SESSION
import com.my.kizzy.gateway.entities.op.OpCode.PRESENCE_UPDATE
import com.my.kizzy.gateway.entities.op.OpCode.RECONNECT
import com.my.kizzy.gateway.entities.op.OpCode.RESUME
import com.my.kizzy.gateway.entities.presence.Presence
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.header
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import java.util.logging.Logger
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration.Companion.seconds

/**
 * Modified by Zion Huang
 *
 * The socket's whole lifecycle lives in one coroutine — [runConnectionLoop] —
 * which connects, pumps frames until the connection goes away for any reason,
 * waits out a backoff and goes round again. That shape matters on Android: this
 * socket spends most of its life behind a backgrounded app, where the radio
 * sleeping, a wifi-to-cellular handover or Discord's own idle timeout will take
 * it down repeatedly over a listening session, and every one of those has to
 * heal without the user reopening the app.
 *
 * Two failures the loop alone does not cover, and which are handled here:
 *
 *  - **Zombie sockets.** A TCP connection that dies while the radio is asleep
 *    is not reported as closed — reads simply never complete and writes are
 *    accepted into a void. Nothing in the WebSocket API distinguishes that from
 *    a quiet connection, so [startHeartbeatJob] insists on Discord's
 *    `HEARTBEAT_ACK` for every heartbeat it sends and drops the socket when one
 *    goes unanswered. Without that check a backgrounded app publishes presences
 *    into a dead socket indefinitely and only recovers on a process restart.
 *
 *  - **A presence lost with its session.** Discord holds a presence for the
 *    lifetime of the gateway session and discards it when that session ends, so
 *    a reconnect leaves the profile blank until the next track change — which
 *    may be minutes away. [lastPresence] is therefore replayed as soon as a new
 *    session is ready.
 */
open class DiscordWebSocket(
    private val token: String,
    private val os: String = "Android",
    private val browser: String = "Discord Android",
    private val device: String = "Generic Android Device",
) : CoroutineScope {
    private val logger = Logger.getLogger(DiscordWebSocket::class.java.name)
    private val gatewayUrl = "wss://gateway.discord.gg/?v=9&encoding=json"
    private var websocket: DefaultClientWebSocketSession? = null
    private var sequence = 0
    private var sessionId: String? = null
    private var heartbeatInterval = 0L
    private var resumeGatewayUrl: String? = null
    private var heartbeatJob: Job? = null
    private var client: HttpClient = HttpClient {
        install(WebSockets)
    }
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * One job, not a job per attempt.
     *
     * A stored [SupervisorJob] rather than a `get()` that builds one, because
     * every `launch` on this scope reads [coroutineContext]: a getter handed each
     * coroutine a parent of its own, which left [close] with nothing to cancel
     * and every heartbeat and reconnect it was meant to stop still running.
     */
    private val supervisor = SupervisorJob()

    override val coroutineContext: CoroutineContext = supervisor + Dispatchers.Default

    /**
     * The connect/pump/backoff loop. Reconnection is this coroutine going round
     * again rather than a second coroutine scheduled from inside the first —
     * which is what it used to be, and could not work: the guard against
     * scheduling two reconnects at once tested the very job that was asking for
     * one, so every disconnect that wasn't an explicit gateway `RECONNECT` ended
     * the presence for the life of the process.
     */
    private var connectionLoop: Job? = null

    private val loopLock = Any()

    /** Set by [close]. Stops the loop retrying an intentionally closed socket. */
    @Volatile
    private var closed = false

    /**
     * Whether there is a session that can carry a presence — true between
     * `READY`/`RESUMED` and the socket going down. A [MutableStateFlow] so
     * [sendActivity] can wait on it instead of polling: the wait used to be a
     * 10ms sleep loop with no exit, which on a socket that never came back
     * spun for as long as the service lived.
     */
    private val sessionReady = MutableStateFlow(false)

    /** Collapses the reconnect backoff on request — see [retryNow]. */
    private val retrySignal = Channel<Unit>(Channel.CONFLATED)

    /**
     * `elapsedRealtime` of the last `HEARTBEAT_ACK`.
     *
     * That clock and not `nanoTime`, which stops during deep sleep: the whole
     * point of the reading is to notice that a lot of wall time has passed with
     * nothing heard from Discord, and the case where that happens is a device
     * asleep behind a backgrounded app. A monotonic clock that sleeps too would
     * report the socket as fresh however long the phone had been in a pocket.
     */
    @Volatile
    private var lastAckAt = 0L

    /** A heartbeat is out and unanswered. */
    @Volatile
    private var awaitingAck = false

    /** Skip the next backoff — set when Discord itself asked us to reconnect. */
    @Volatile
    private var immediateRetry = false

    /**
     * The last presence handed to [sendActivity], replayed after a reconnect.
     *
     * Its timestamps are absolute instants rather than offsets, so a presence
     * built one socket ago is still correct on the next one.
     */
    @Volatile
    private var lastPresence: Presence? = null

    /**
     * What has already been delivered on the *current* session. Cleared with the
     * socket, because a new session has been told nothing.
     */
    @Volatile
    private var deliveredPresence: Presence? = null

    /** Starts the connection loop if it isn't already running. */
    fun connect() {
        if (closed) return
        synchronized(loopLock) {
            if (connectionLoop?.isActive == true) {
                logger.info("Gateway connection loop already running.")
                return
            }
            connectionLoop = launch { runConnectionLoop() }
        }
    }

    /**
     * Asks the loop to stop waiting out its backoff and try now.
     *
     * The backoff climbs to a minute, which is the right thing while there is no
     * network and the wrong thing the moment there is something to publish: a
     * track change is evidence the user is listening, and shouldn't wait on a
     * timer that was set when the last attempt failed.
     */
    fun retryNow() {
        if (closed) return
        retrySignal.trySend(Unit)
    }

    private suspend fun runConnectionLoop() {
        var backoff = INITIAL_RECONNECT_DELAY.inWholeMilliseconds
        while (currentCoroutineContext().isActive && !closed) {
            // A resume URL is only worth using while its session might still be
            // alive; every path that invalidates the session clears it, so
            // reaching for it here can't strand us on a dead one.
            val url = resumeGatewayUrl ?: gatewayUrl
            var connected = false
            try {
                logger.info("Connecting to Discord Gateway at $url")
                val session = client.webSocketSession(url) {
                    header("User-Agent", "Discord-Android/314013;RNA")
                    header("Accept-Language", "en-US")
                    header("Cache-Control", "no-cache")
                    header("Pragma", "no-cache")
                }
                websocket = session
                connected = true
                backoff = INITIAL_RECONNECT_DELAY.inWholeMilliseconds
                logger.info("Successfully connected to Discord Gateway.")
                // Runs until the socket closes or errors, which is the only way
                // out of an attempt — including the heartbeat watchdog's, which
                // works by cancelling the session underneath this.
                session.incoming.receiveAsFlow().collect { frame ->
                    if (frame is Frame.Text) {
                        val text = frame.readText()
                        // Per frame, so one payload this build doesn't
                        // understand — an opcode or event Discord added since —
                        // can't take the connection down with it.
                        runCatching { onMessage(json.decodeFromString(text)) }
                            .onFailure { logger.warning("Gateway: bad payload: ${it.message}") }
                    }
                }
                val reason = session.closeReason.await()
                logger.warning(
                    "Gateway closed with code: ${reason?.code}, reason: ${reason?.message}",
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (e: Exception) {
                logger.warning("Gateway connection error: ${e.message}")
            } finally {
                teardownSocket()
            }

            if (closed) break

            // An attempt that died before it ever opened is evidence the resume
            // URL is stale as often as it is evidence the network is down, and
            // the base gateway will always take a fresh identify.
            if (!connected) forgetSession()

            if (immediateRetry) {
                immediateRetry = false
                continue
            }
            logger.info("Gateway: reconnecting in ${backoff}ms")
            // A wait, not a sleep: retryNow() cuts it short.
            withTimeoutOrNull(backoff) { retrySignal.receive() }
            backoff = (backoff * 2).coerceAtMost(MAX_RECONNECT_DELAY.inWholeMilliseconds)
        }
    }

    /**
     * Puts the socket and everything keyed to it back to a known-down state.
     *
     * Unconditional, and on every exit path, because the flag saying whether
     * there was a connection is what [connect] and [sendActivity] read: leaving
     * it set after a failure — which the old error path did, having returned
     * early before clearing it — made every later attempt to connect a no-op
     * and every push a wait for a socket that no longer existed.
     */
    private fun teardownSocket() {
        sessionReady.value = false
        awaitingAck = false
        deliveredPresence = null
        heartbeatJob?.cancel()
        heartbeatJob = null
        websocket?.cancel()
        websocket = null
    }

    /** Drops what identifies a session, so the next HELLO identifies afresh. */
