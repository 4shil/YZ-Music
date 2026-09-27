package com.music.yzmusic.data.listentogether

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.music.yzmusic.BuildConfig
import com.music.yzmusic.YZMusicApplication
import com.music.yzmusic.data.settings.AppSettings
import com.music.yzmusic.data.DebugLog as Log
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.timeout
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.URI
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit

sealed interface ServerUrlError {
    data object Whitespace : ServerUrlError
    data object InvalidScheme : ServerUrlError
    data object InvalidHost : ServerUrlError
    data object InvalidPort : ServerUrlError
    data object InvalidPath : ServerUrlError
    data object HasPath : ServerUrlError
    data object HasQuery : ServerUrlError
    data object HasFragment : ServerUrlError
    data object Malformed : ServerUrlError
}

sealed interface ServerUrlValidationResult {
    data class Valid(val normalizedUrl: String) : ServerUrlValidationResult
    data class Invalid(val error: ServerUrlError) : ServerUrlValidationResult
}

/**
 * Turns whatever was typed into a party server address into a canonical one.
 *
 * Blank is **valid** and means "the one this build ships with", not a
 * malformed URL: clearing the box is how a person gets off a custom server,
 * and making that an error would mean the only way back to the default is
 * through a separate button that does something subtly different.
 *
 * Everything else is rejected with a specific reason rather than a generic
 * one. This is a text box a person is typing a long string into, and "that
 * URL is not valid" tells them nothing about which of the nine ways they got
 * it wrong.
 */
fun parseAndNormalizeServerUrl(raw: String): ServerUrlValidationResult {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return ServerUrlValidationResult.Valid("")
    if (trimmed.any { it.isWhitespace() }) return ServerUrlValidationResult.Invalid(ServerUrlError.Whitespace)

    val candidate = if (trimmed.contains("://")) trimmed else "https://$trimmed"

    val uri = try {
        URI(candidate)
    } catch (_: Exception) {
        // URI rejected it outright, but the port is the part worth rescuing:
        // an out-of-range port is a specific thing to say, and without this
        // branch "localhost:99999" would be reported as a bad host, which
        // sends the person looking in the wrong place entirely.
        val authority = candidate.substringAfter("://").substringBefore("/").substringBefore("?").substringBefore("#")
        if (authority.isEmpty()) {
            return ServerUrlValidationResult.Invalid(ServerUrlError.InvalidHost)
        }
        val portStr = authority.substringAfterLast(':', "")
        if (portStr.isNotEmpty() && portStr.all { it.isDigit() }) {
            val portNum = portStr.toLongOrNull()
            if (portNum != null && portNum !in 1..65535) {
                return ServerUrlValidationResult.Invalid(ServerUrlError.InvalidPort)
            }
        }
        return ServerUrlValidationResult.Invalid(ServerUrlError.InvalidHost)
    }

    val scheme = uri.scheme?.lowercase() ?: return ServerUrlValidationResult.Invalid(ServerUrlError.InvalidScheme)
    if (scheme != "http" && scheme != "https") {
        return ServerUrlValidationResult.Invalid(ServerUrlError.InvalidScheme)
    }

    if (!uri.rawQuery.isNullOrEmpty() || candidate.contains("?")) {
        return ServerUrlValidationResult.Invalid(ServerUrlError.HasQuery)
    }

    if (!uri.rawFragment.isNullOrEmpty() || candidate.contains("#")) {
        return ServerUrlValidationResult.Invalid(ServerUrlError.HasFragment)
    }

    val pathSegments = uri.rawPath.orEmpty().split('/').filter { it.isNotEmpty() }
    for (segment in pathSegments) {
        if (segment == "." || segment == "..") {
            return ServerUrlValidationResult.Invalid(ServerUrlError.InvalidPath)
        }
    }
    val canonicalPath = if (pathSegments.isEmpty()) "" else "/" + pathSegments.joinToString("/")

    val port = uri.port
    if (port != -1 && port !in 1..65535) {
        return ServerUrlValidationResult.Invalid(ServerUrlError.InvalidPort)
    }
    val authority = candidate.substringAfter("://").substringBefore("/").substringBefore("?").substringBefore("#")
    val portStr = authority.substringAfterLast(':', "")
    if (portStr.isNotEmpty() && portStr.all { it.isDigit() } && authority.contains(":")) {
        val portNum = portStr.toLongOrNull()
        if (portNum != null && portNum !in 1..65535) {
            return ServerUrlValidationResult.Invalid(ServerUrlError.InvalidPort)
        }
    }

    val rawHost = uri.host ?: return ServerUrlValidationResult.Invalid(ServerUrlError.InvalidHost)
    if (rawHost.isEmpty()) return ServerUrlValidationResult.Invalid(ServerUrlError.InvalidHost)

    val canonicalHost: String = when {
        rawHost.startsWith("[") && rawHost.endsWith("]") -> {
            val unbracketed = rawHost.substring(1, rawHost.length - 1)
            if (!unbracketed.contains(":")) return ServerUrlValidationResult.Invalid(ServerUrlError.InvalidHost)
            "[${unbracketed.lowercase()}]"
        }
        rawHost.contains(":") -> {
            "[${rawHost.lowercase()}]"
        }
        rawHost.equals("localhost", ignoreCase = true) -> {
            "localhost"
        }
        else -> {
            if (rawHost.length > 253) return ServerUrlValidationResult.Invalid(ServerUrlError.InvalidHost)
            if (rawHost.startsWith(".") || rawHost.endsWith(".")) {
                return ServerUrlValidationResult.Invalid(ServerUrlError.InvalidHost)
            }
            val labels = rawHost.split('.')
            if (labels.size < 2) return ServerUrlValidationResult.Invalid(ServerUrlError.InvalidHost)
            val labelRegex = Regex("^[a-zA-Z0-9]([a-zA-Z0-9-]*[a-zA-Z0-9])?$")
            for (label in labels) {
                if (label.isEmpty() || label.length > 63 || !label.matches(labelRegex)) {
                    return ServerUrlValidationResult.Invalid(ServerUrlError.InvalidHost)
                }
            }
            rawHost.lowercase()
        }
    }

    val portSuffix = if (port != -1) ":$port" else ""
    return ServerUrlValidationResult.Valid("${scheme.lowercase()}://$canonicalHost$portSuffix$canonicalPath")
}

/**
 * Where this install's party traffic is actually going, and how well it got
 * there.
 *
 * [CustomFallback] is the one that is not obvious: a custom address that is
 * configured but unreachable does not strand the feature, it quietly sends
 * traffic to the default server instead. The UI has to be able to say so,
 * because a person testing their own server and getting "connected" from the
 * default one would otherwise have no way to know the address was ignored.
 */
sealed interface ServerConnectionState {
    data object Checking : ServerConnectionState
    data class DefaultOnline(val latencyMs: Long) : ServerConnectionState
    data class CustomOnline(val latencyMs: Long) : ServerConnectionState
    data class CustomFallback(val latencyMs: Long) : ServerConnectionState
    data object Offline : ServerConnectionState
}

/** The answer to one probe: reachable or not, and how long it took to find out. */
data class ProbeResult(val isOnline: Boolean, val latencyMs: Long = 0L)

val ServerConnectionState.latencyMs: Long?
    get() = when (this) {
        is ServerConnectionState.DefaultOnline -> latencyMs
        is ServerConnectionState.CustomOnline -> latencyMs
        is ServerConnectionState.CustomFallback -> latencyMs
        ServerConnectionState.Checking, ServerConnectionState.Offline -> null
    }

val ServerConnectionState.isFallback: Boolean
    get() = this is ServerConnectionState.CustomFallback

val ServerConnectionState.health: ListenTogether.Health
    get() = when (this) {
        ServerConnectionState.Checking -> ListenTogether.Health.CHECKING
        is ServerConnectionState.DefaultOnline,
        is ServerConnectionState.CustomOnline,
        is ServerConnectionState.CustomFallback -> ListenTogether.Health.ONLINE
        ServerConnectionState.Offline -> ListenTogether.Health.OFFLINE
    }

/**
 * Listen together: one party, shared by up to five signed-in devices.
 *
 * This object is the whole client half of the feature — membership, the socket,
 * the clock, and the controls any member may send. It does **not** touch the
 * player. What it publishes instead is [partyPositionMs]: where this device
 * ought to be, right now, on its own clock.
 * [PartySync][com.music.yzmusic.playback.PartySync] is what binds that to
 * Media3, and it lives in the playback service rather than here.
 */
object ListenTogether {

    /**
     * The address this build ships pointed at, before any resolution.
     *
     * Declared first because [defaultServer] and the health resolver are
     * `val`s initialised during construction and read it: a `val` further
     * down the object would still be null at that point, and Kotlin's
     * initialiser order is not something to discover at runtime.
     */
    private val DEFAULT_SERVER: String = BuildConfig.LISTEN_TOGETHER_SERVER.ifBlank { "https://yz-music-party.onrender.com" }

    enum class Connection { OFFLINE, CONNECTING, LIVE }

    data class State(
        val code: String? = null,
        val you: PartyMember? = null,
        val members: List<PartyMember> = emptyList(),
        val maxMembers: Int = 5,
        val hostOnlyControl: Boolean = false,
        val playback: PartyPlayback = PartyPlayback(),
        /**
         * Held separately from [playback] because it arrives separately: the
         * state frame carries only a sequence number for it, and this is
         * replaced when the server says the list has actually changed.
         */
        val queue: PartyQueue = PartyQueue(),
        val connection: Connection = Connection.OFFLINE,
        /** False until the first round trip; the playhead is a guess until then. */
        val clockSynced: Boolean = false,
        val roundTripMs: Long = 0,
        /** The last thing that went wrong, for the screen to show. */
        val error: String? = null,
    ) {
        val inParty: Boolean get() = code != null
        val isFull: Boolean get() = members.size >= maxMembers
        val controlsLocked: Boolean
            get() = inParty && hostOnlyControl && you?.isHost != true
    }

    /** A refusal from the server, carrying the machine-readable half. */
    class PartyException(val code: String, message: String, val statusCode: Int? = null) : Exception(message)

    /**
     * The outcome of a deep-link switch, which is allowed to fail without
     * taking the current party down with it.
     *
     * The two failure cases are distinguished because the caller's next move
     * differs: [TargetFailedRecovered] means there is still a party playing
     * and the person only has to be told why the link did not take, while
     * [TargetFailedNoParty] means nothing is playing and the message is the
     * whole of what they get.
     */
    sealed interface SwitchPartyResult {
        data class Success(val partyCode: String) : SwitchPartyResult
        data class TargetFailedRecovered(val partyCode: String, val targetError: String) : SwitchPartyResult
        data class TargetFailedNoParty(val targetError: String) : SwitchPartyResult
    }

    /**
     * One membership change at a time.
     *
     * Held across create, join, switch and leave, because each of those ends
     * in "this device is now in party X with token T" and they all write the
     * same three things. Two of them interleaving writes a token from one to
     * the other, and the socket then belongs to a membership that does not
     * exist.
     */
    private val switchMutex = Mutex()

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    private val http = HttpClient(OkHttp) {
        engine {
            config {
                readTimeout(0, TimeUnit.MILLISECONDS)
                connectTimeout(15, TimeUnit.SECONDS)
                pingInterval(20, TimeUnit.SECONDS)
                retryOnConnectionFailure(true)
            }
        }
        install(ContentNegotiation) { json(json) }
        install(HttpTimeout)
        install(WebSockets)
        expectSuccess = false
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val clock = ServerClock()

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val _awaitingStart = MutableStateFlow(false)
    val awaitingStart: StateFlow<Boolean> = _awaitingStart.asStateFlow()

    fun setAwaitingStart(value: Boolean) {
        _awaitingStart.value = value
    }

    private val _activities = MutableStateFlow<List<PartyActivity>>(emptyList())
    val activities: StateFlow<List<PartyActivity>> = _activities.asStateFlow()

    private fun recordActivity(activity: PartyActivity) {
        _activities.update { current ->
            (listOf(activity) + current).take(MAX_ACTIVITIES)
        }
    }

    /**
     * A server the user has pointed this install at instead of the default one.
     */
    private val _customServer = MutableStateFlow("")
    val customServerUrl: StateFlow<String> = _customServer.asStateFlow()

    /**
     * The default party server this build ships pointed at, from
     * `LISTEN_TOGETHER_SERVER` in `local.properties` or the build environment.
     */
    val defaultServer: String = when (val res = parseAndNormalizeServerUrl(DEFAULT_SERVER)) {
        is ServerUrlValidationResult.Valid -> res.normalizedUrl
        is ServerUrlValidationResult.Invalid -> error("Invalid BuildConfig.LISTEN_TOGETHER_SERVER: $DEFAULT_SERVER")
    }

    /**
     * The server idle operations are currently pointed at.
     *
     * Not simply "the custom one if there is one": a custom address that is
     * down resolves to [defaultServer] here, which is what lets a party still
     * work while somebody's own server is being restarted. The screen says so
     * through [ServerStatus.isFallback] rather than failing outright.
     */
    private val _effectiveIdleServer = MutableStateFlow(defaultServer)
    fun effectiveIdleServerBase(): String = _effectiveIdleServer.value

    /**
     * The server a party switch should target when the invite itself does not
     * name one.
     *
     * [customServer] is `""` when nobody has configured one — not the default
     * server's address — so a switch with no explicit target (a typed code,
     * entered while already live in a party) has to fall back to whichever
     * server idle joins already resolve to. Handed the raw empty string
     * instead, [switchPartyWithRecovery] treats it as a malformed target and
     * refuses the switch outright.
     */
    fun resolveSwitchTarget(
        inviteServer: String?,
        customServer: String,
        idleServer: String = effectiveIdleServerBase(),
    ): String = inviteServer ?: customServer.ifBlank { idleServer }

    /**
     * The server actually hosting the live party.
     *
     * Non-null while [State.inParty]. Fixed for the life of the membership:
     * a background health check that flipped this mid-party would move the
     * socket's host out from under a membership that was issued by the old
     * one.
     */
    @Volatile
    private var activePartyServerBase: String? = null
    fun activePartyServerBase(): String? = activePartyServerBase

    /** Whether idle operations are currently falling back to the default server. */
    val isUsingDefaultFallback: Boolean
        get() {
            val configured = _customServer.value
            return configured.isNotBlank() &&
                effectiveIdleServerBase() == defaultServer &&
                configured != defaultServer
        }

    /** Whether a party can be reached at all — a built-in or a custom address. */
    val hasServer: Boolean get() = effectiveIdleServerBase().isNotBlank()

    enum class Health { UNKNOWN, CHECKING, ONLINE, OFFLINE }

    data class ServerStatus(
        val health: Health = Health.UNKNOWN,
        val latencyMs: Long = 0,
        val isFallback: Boolean = false,
    )

    private val _serverConnectionState = MutableStateFlow<ServerConnectionState>(ServerConnectionState.Checking)
    val serverConnectionState: StateFlow<ServerConnectionState> = _serverConnectionState.asStateFlow()

    private val _serverStatus = MutableStateFlow(ServerStatus())
    val serverStatus: StateFlow<ServerStatus> = _serverStatus.asStateFlow()

    /**
     * Whether a failure is the kind that says "that address is not there"
     * rather than "this request went wrong".
     *
     * The distinction decides whether a create retries on the default server:
     * a 404 or a refusal is an answer and retrying it elsewhere would be wrong,
     * while an unresolvable host or a 5xx says nothing about the request and
     * is worth one more try somewhere else.
     */
    fun isEligibleForFallback(error: Throwable): Boolean = when (error) {
        is java.net.UnknownHostException,
        is java.net.ConnectException,
        is java.net.NoRouteToHostException,
        is java.net.PortUnreachableException,
        is java.net.SocketTimeoutException,
        is io.ktor.client.plugins.HttpRequestTimeoutException,
        is io.ktor.client.network.sockets.SocketTimeoutException,
        is io.ktor.client.network.sockets.ConnectTimeoutException -> true
        is PartyException -> (error.statusCode ?: 0) in 500..599
        else -> {
            val cause = error.cause
            cause != null && cause !== error && isEligibleForFallback(cause)
        }
    }

    const val CUSTOM_SERVER_TIMEOUT_MS = 6_000L
    const val DEFAULT_SERVER_TIMEOUT_MS = 30_000L

    private var isScreenActive: Boolean = false
    private var healthMonitorJob: Job? = null
    private val resolutionMutex = Mutex()
    private var activeResolutionJob: Job? = null
    private var resolutionGeneration = 0L

    /**
     * Whether the Listen Together screen is on display.
     *
     * Only used to decide how often to check: on screen, ten seconds is
     * unobtrusive, because somebody reading a latency is watching it anyway;
     * off screen it is thirty, because that check runs on a phone's radio and
     * nothing is being read.
     */
    fun setScreenActive(active: Boolean) {
        isScreenActive = active
        if (active) {
            refreshServerHealth(showChecking = false)
        }
    }

    private fun startHealthMonitor() {
        healthMonitorJob?.cancel()
        healthMonitorJob = scope.launch {
            while (isActive) {
                val delayMs = if (isScreenActive) 10_000L else 30_000L
                delay(delayMs)
                refreshServerHealth(showChecking = false)
            }
        }
    }

    /**
     * Picks the server idle operations should use, given what a custom
     * address does.
     *
     * Split out from the state it writes to, because the answer is worth
     * having without touching any flow — a test can call this directly with
     * two probes and assert the choice, rather than standing up a client and
     * watching a StateFlow settle.
     */
    suspend fun resolveServerConnection(
        customServer: String,
        defaultServer: String,
        probeCustom: suspend () -> ProbeResult,
        probeDefault: suspend () -> ProbeResult,
    ): Pair<String, ServerConnectionState> {
        val normCustom = when (val res = parseAndNormalizeServerUrl(customServer)) {
            is ServerUrlValidationResult.Valid -> res.normalizedUrl
            is ServerUrlValidationResult.Invalid -> ""
        }
        val hasCustom = normCustom.isNotBlank() && normCustom != defaultServer

        return if (hasCustom) {
            val customProbe = probeCustom()
            if (customProbe.isOnline) {
                normCustom to ServerConnectionState.CustomOnline(customProbe.latencyMs)
            } else {
                val defaultProbe = probeDefault()
                if (defaultProbe.isOnline) {
                    // Invariant: CustomFallback latency is strictly the default
                    // server's latency, because that is the server being used.
                    defaultServer to ServerConnectionState.CustomFallback(defaultProbe.latencyMs)
                } else {
                    defaultServer to ServerConnectionState.Offline
                }
            }
        } else {
            val defaultProbe = probeDefault()
            if (defaultProbe.isOnline) {
                defaultServer to ServerConnectionState.DefaultOnline(defaultProbe.latencyMs)
            } else {
                defaultServer to ServerConnectionState.Offline
            }
        }
    }

    /**
     * Runs a resolution and publishes it, but only if nothing newer started
     * while it was in flight.
     *
     * The generation counter is what makes that check possible: a probe of an
     * address the user has since changed would otherwise land afterwards and
     * overwrite the newer answer with an older one, leaving the screen
     * reporting the health of a server this install stopped using.
     */
    private suspend fun resolveServerConnectionSerialized(
        showChecking: Boolean = false,
        targetGeneration: Long? = null,
    ): ServerConnectionState {
        if (showChecking) {
            resolutionMutex.withLock {
                if (targetGeneration == null || targetGeneration == resolutionGeneration) {
                    _serverConnectionState.value = ServerConnectionState.Checking
                    _serverStatus.value = ServerStatus(Health.CHECKING)
                }
            }
        }

        val custom = _customServer.value
        val (effectiveServer, state) = resolveServerConnection(
            customServer = custom,
            defaultServer = defaultServer,
            probeCustom = { probeHealthWithLatency(custom, CUSTOM_SERVER_TIMEOUT_MS) },
            probeDefault = { probeHealthWithLatency(defaultServer, DEFAULT_SERVER_TIMEOUT_MS) },
        )

        resolutionMutex.withLock {
            if (targetGeneration == null || targetGeneration == resolutionGeneration) {
                _effectiveIdleServer.value = effectiveServer
                _serverConnectionState.value = state
                _serverStatus.value = ServerStatus(
                    health = state.health,
                    latencyMs = state.latencyMs ?: 0L,
                    isFallback = state.isFallback,
                )
            }
        }
        return state
    }

    fun refreshServerHealth(showChecking: Boolean = false) {
        scope.launch {
            val job = resolutionMutex.withLock {
                activeResolutionJob?.cancel()
                val nextGen = ++resolutionGeneration
                val newJob = scope.async(start = CoroutineStart.LAZY) {
                    resolveServerConnectionSerialized(showChecking = showChecking, targetGeneration = nextGen)
                }
                activeResolutionJob = newJob
                newJob
            }
            job.start()
        }
    }

    /**
     * Asks one address whether it is there, and how far away it is.
     *
     * Latency is only reported when the answer was yes. A timed-out request
     * took however long the timeout took, which is a property of this device's
     * patience rather than of the server, and showing it as "38 ms" because
     * the request happened to fail quickly would be a lie in a number.
     */
    suspend fun probeHealthWithLatency(serverUrl: String, timeoutMs: Long): ProbeResult {
        val raw = resolveHttpBase(serverUrl)
        if (raw.isBlank()) return ProbeResult(isOnline = false, latencyMs = 0L)
        val start = ServerClock.localNowMs()
        val isOnline = runCatching {
            val response = http.get("$raw/healthz") {
                timeout { requestTimeoutMillis = timeoutMs }
            }
            if (!response.status.isSuccess()) return@runCatching false
            val body = runCatching { response.bodyAsText() }.getOrDefault("")
            body.contains("\"ok\":true")
        }.getOrElse {
            Log.w(TAG, "health check failed for ${redact(raw)}: ${redact(it.message)}")
            false
        }
        val elapsed = ServerClock.localNowMs() - start
        return ProbeResult(isOnline = isOnline, latencyMs = if (isOnline) elapsed.coerceAtLeast(0L) else 0L)
    }

    private suspend fun probeHealth(baseUrl: String): Boolean =
        probeHealthWithLatency(baseUrl, HEALTH_TIMEOUT_MS).isOnline

    data class HealthResolution(
        val resolvedServer: String,
        val health: Health,
        val isFallback: Boolean,
    )

    suspend fun computeHealthResolution(
        customServer: String,
        probeCustom: suspend () -> Boolean,
        probeDefault: suspend () -> Boolean,
    ): HealthResolution {
        val custom = normalizeServerBase(customServer)
        val hasCustom = custom.isNotBlank() && custom != defaultServer
        return if (hasCustom) {
            if (probeCustom()) {
                HealthResolution(resolvedServer = custom, health = Health.ONLINE, isFallback = false)
            } else if (probeDefault()) {
                HealthResolution(resolvedServer = defaultServer, health = Health.ONLINE, isFallback = true)
            } else {
                HealthResolution(resolvedServer = defaultServer, health = Health.OFFLINE, isFallback = false)
            }
        } else {
            if (probeDefault()) {
                HealthResolution(resolvedServer = defaultServer, health = Health.ONLINE, isFallback = false)
            } else {
                HealthResolution(resolvedServer = defaultServer, health = Health.OFFLINE, isFallback = false)
            }
        }
    }

    private lateinit var prefs: SharedPreferences
    private var token: String? = null

    @Volatile
    private var session: DefaultClientWebSocketSession? = null
    private var socketJob: Job? = null

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val rawSaved = prefs.getString(KEY_SERVER, null)?.trim().orEmpty()
        val normalizedSaved = when (val res = parseAndNormalizeServerUrl(rawSaved)) {
            is ServerUrlValidationResult.Valid -> res.normalizedUrl
            is ServerUrlValidationResult.Invalid -> ""
        }
        _customServer.value = normalizedSaved
        // Seed the idle target to the custom address so the first join before
        // any probe lands does not silently use the default server; the
        // resolution below may move it back if that address turns out to be
        // down, which is the correct answer but not a safe first guess.
        _effectiveIdleServer.value = if (normalizedSaved.isNotBlank()) {
            normalizedSaved
        } else {
            defaultServer
        }

        val code = prefs.getString(KEY_CODE, null)
        val saved = prefs.getString(KEY_TOKEN, null)
        prefs.edit().remove(KEY_CODE).remove(KEY_TOKEN).apply()
        if (!code.isNullOrBlank() && !saved.isNullOrBlank()) {
            releaseStaleSlot(code, saved)
        }

        // A phone changing network is the single most common reason a party
        // server goes from "connected" to "unreachable" and back, and waiting
        // out the next periodic check to notice is up to thirty seconds of a
        // screen reading the wrong thing. Re-probing on the edge of the change
        // is cheap; the alternative is not.
        val manager = context.getSystemService(ConnectivityManager::class.java)
        if (manager != null) {
            runCatching {
                manager.registerDefaultNetworkCallback(
                    object : ConnectivityManager.NetworkCallback() {
                        override fun onAvailable(network: Network) {
                            refreshServerHealth(showChecking = false)
                        }

                        override fun onLost(network: Network) {
                            _serverConnectionState.value = ServerConnectionState.Offline
                            _serverStatus.value = ServerStatus(Health.OFFLINE)
                        }

                        override fun onCapabilitiesChanged(
                            network: Network,
                            capabilities: NetworkCapabilities,
                        ) {
                            if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                                refreshServerHealth(showChecking = false)
                            }
                        }
                    }
                )
            }
        }

        startHealthMonitor()
        refreshServerHealth(showChecking = true)
    }

    /**
     * Hands a previous process's slot back, so the others see them leave now
     * rather than when the server's disconnect grace sweeps it.
     *
     * Takes the server explicitly because the slot has to be given back to
     * the one that issued it. Sending this to whatever the install happens to
     * be pointed at now would either fail or, worse, tell an unrelated server
     * to drop a code it never issued.
     */
    private fun releaseStaleSlotOnServer(serverBase: String, code: String, held: String) {
        if (serverBase.isBlank() || code.isBlank() || held.isBlank()) return
        scope.launch {
            runCatching {
                http.post("$serverBase/api/parties/$code/leave") {
                    header("Authorization", "Bearer $held")
                }
            }.onFailure { failure ->
                Log.i(TAG, "stale party slot left to the server's grace: ${redact(failure.message)}")
            }
        }
    }

    private fun releaseStaleSlot(code: String, held: String) {
        val server = activePartyServerBase ?: resolveHttpBase(effectiveIdleServerBase())
        releaseStaleSlotOnServer(server, code, held)
    }

    /**
     * Points this install at another server and resolves to what it can reach.
     *
     * Suspends, and returns the resulting [ServerConnectionState], because the
     * only useful answer to "I changed the address" is what happened when the
     * change was tried: a caller that cannot see the result has to guess
     * between "saved" and "saved, and silently talking to a different server".
     *
     * Blank means back to the default, so the remove path in the editor is
     * this same function with an empty string rather than a second one.
     */
    suspend fun setCustomServerUrl(normalizedUrl: String): ServerConnectionState {
        val canonical = when (val res = parseAndNormalizeServerUrl(normalizedUrl)) {
            is ServerUrlValidationResult.Valid -> res.normalizedUrl
            // Blank is the documented way back to the default, so it is not a
            // malformed URL — it is the remove path arriving here.
            is ServerUrlValidationResult.Invalid ->
                if (normalizedUrl.isBlank()) "" else {
                    Log.w(TAG, "ignoring invalid custom server URL: $normalizedUrl")
                    return ServerConnectionState.Offline
                }
        }

        if (canonical.isBlank()) {
            prefs.edit().remove(KEY_SERVER).apply()
        } else {
            prefs.edit().putString(KEY_SERVER, canonical).apply()
        }
        _customServer.value = canonical

        val job = resolutionMutex.withLock {
            activeResolutionJob?.cancel()
            val nextGen = ++resolutionGeneration
            val newJob = scope.async(start = CoroutineStart.LAZY) {
                resolveServerConnectionSerialized(showChecking = true, targetGeneration = nextGen)
            }
            activeResolutionJob = newJob
            newJob
        }
        job.start()
        return job.await()
    }

    /** Whether this device has an account it can jam as. */
    fun canJoin(): Boolean = identity() != null

    /**
     * The name this device goes into parties under, as last set by the user.
     *
     * Blank means "use the account's name", which is the common case and the
     * one [identity] falls back to.
     */
    fun nickname(): String = prefs.getString(KEY_NICKNAME, null).orEmpty()

    fun setNickname(value: String) {
        prefs.edit().putString(KEY_NICKNAME, value.trim().take(80)).apply()
    }


    fun ensureConnected() {
        if (_state.value.code == null || token == null) return
        if (socketJob?.isActive == true) return
        connect()
    }

    // ------------------------------------------------------------ joining --

    /**
     * Starts a party, retrying once on the default server if the configured
     * one turns out to be unreachable.
     *
     * The retry is what makes a custom address safe to leave configured: a
     * person running their own server who shuts it down for an evening can
     * still start a party, instead of being told to go and clear a setting
     * first. Only a failure that means "not there" is retried — a refusal is
     * an answer, and asking again somewhere else would only be a slower no.
     */
    suspend fun createParty(nickname: String = nickname(), maxMembers: Int = 5): Result<String> = switchMutex.withLock {
        val primary = effectiveIdleServerBase()
        val primaryNormalized = resolveHttpBase(primary)
        val attempt = runCatching {
            doCreateOnServer(primaryNormalized, nickname, maxMembers)
        }

        if (attempt.isSuccess) {
            return@withLock Result.success(attempt.getOrThrow())
        }

        val failure = attempt.exceptionOrNull()
            ?: return@withLock Result.failure(IllegalStateException("Unknown create failure"))

        if (normalizeServerBase(primary) != defaultServer && isEligibleForFallback(failure)) {
            Log.w(TAG, "createParty failed on custom server, falling back to default: ${redact(failure.message)}")
            val fallbackNormalized = resolveHttpBase(defaultServer)
            val fallbackAttempt = runCatching {
                doCreateOnServer(fallbackNormalized, nickname, maxMembers)
            }
            if (fallbackAttempt.isSuccess) {
                return@withLock Result.success(fallbackAttempt.getOrThrow())
            }
            val fallbackFailure = fallbackAttempt.exceptionOrNull() ?: failure
            _state.update { it.copy(error = fallbackFailure.displayMessage()) }
            return@withLock Result.failure(fallbackFailure)
        }

        _state.update { it.copy(error = failure.displayMessage()) }
        Result.failure(failure)
    }

    private suspend fun doCreateOnServer(serverBase: String, nickname: String, maxMembers: Int): String {
        val who = identity(nickname) ?: throw PartyException("not_signed_in", "Sign in to listen together.")
        if (serverBase.isBlank()) throw PartyException("no_server", "Set the party server address first.")
        val membership = post(
            "$serverBase/api/parties",
            JoinRequest(
                who.userId, who.deviceId, who.name, who.avatar, maxMembers,
                autoplayEnabled = AppSettings.autoplay.value,
            ),
        )

        withContext(NonCancellable) {
            activePartyServerBase = serverBase
            token = membership.token
            prefs.edit()
                .putString(KEY_CODE, membership.code)
                .putString(KEY_TOKEN, membership.token)
                .apply()
            clock.reset()
            _state.value = State(
                code = membership.code,
                you = membership.you,
                members = membership.party.members,
                maxMembers = membership.party.maxMembers,
                hostOnlyControl = membership.party.hostOnlyControl,
                playback = membership.party.playback,
                queue = membership.party.queue,
                connection = Connection.CONNECTING,
            )
        }
        connect()
        return membership.code
    }

    /**
     * Joins a party from whichever server idle operations currently resolve
     * to.
     *
     * Architectural contract:
     * - Precondition: IDLE only.
     * - Used by manual party-code entry.
     * - Performs a direct Idle -> Live transition.
     *
     * Do not merge this with [switchPartyWithRecovery]. Deep-link invites
     * require target-first transactional semantics and recovery guarantees
     * that are intentionally not part of this API.
     */
    suspend fun joinParty(code: String, nickname: String = nickname()): Result<String> = switchMutex.withLock {
        val targetServer = resolveHttpBase(effectiveIdleServerBase())
        runCatching {
            doJoinOnServer(targetServer, code, nickname)
        }.onFailure { failure ->
            Log.w(TAG, "could not enter a party: ${redact(failure.message)}")
            _state.update { it.copy(error = failure.displayMessage()) }
        }
    }

    private suspend fun doJoinOnServer(serverBase: String, code: String, nickname: String): String {
        val who = identity(nickname) ?: throw PartyException("not_signed_in", "Sign in to listen together.")
        if (serverBase.isBlank()) throw PartyException("no_server", "Set the party server address first.")
        val cleaned = code.filter { it.isLetterOrDigit() }.uppercase()
        if (cleaned.length != CODE_LENGTH) {
            throw PartyException("bad_code", "A party code is six letters or digits.")
        }
        refuseIfRecentlyKicked(cleaned)
        val membership = post(
            "$serverBase/api/parties/$cleaned/join",
            JoinRequest(who.userId, who.deviceId, who.name, who.avatar),
        )

        withContext(NonCancellable) {
            activePartyServerBase = serverBase
            token = membership.token
            prefs.edit()
                .putString(KEY_CODE, membership.code)
                .putString(KEY_TOKEN, membership.token)
                .apply()
            clock.reset()
            _state.value = State(
                code = membership.code,
                you = membership.you,
                members = membership.party.members,
                maxMembers = membership.party.maxMembers,
                hostOnlyControl = membership.party.hostOnlyControl,
                playback = membership.party.playback,
                queue = membership.party.queue,
                connection = Connection.CONNECTING,
            )
        }
        connect()
        return membership.code
    }

    suspend fun previewParty(code: String, server: String? = null): Result<PartyPreview> = withContext(Dispatchers.IO) {
        val cleaned = code.filter { it.isLetterOrDigit() }.uppercase()
        if (cleaned.length != CODE_LENGTH) {
            return@withContext Result.failure(PartyException("bad_code", "A party code is six letters or digits."))
        }
        runCatching {
            refuseIfRecentlyKicked(cleaned)
            val base = resolveHttpBase(server ?: effectiveIdleServerBase())
                .ifBlank { throw PartyException("no_server", "Set the party server address first.") }

            val response = http.get("$base/api/parties/$cleaned/preview") {
                timeout { requestTimeoutMillis = 15_000L }
            }
            if (!response.status.isSuccess()) {
                val problem = response.toPartyException()
                if (problem.code == "http_404") {
                    return@runCatching PartyPreview(code = cleaned)
                }
                throw problem
            }
            response.body<PartyPreview>()
        }.onFailure {
            Log.w(TAG, "could not look up a party: ${redact(it.message)}")
        }
    }

    /**
     * Transactionally transitions to an invite target.
     *
     * Architectural contract:
     *
     * Before successful target `/join`, this method must not mutate the current
     * party membership, current token, or persisted server preference.
     *
     * Phase 1 — Target join (cancellable):
     * - Target /join is attempted before mutating the current party or
     *   persisted server preference.
     * - If the target fails, the current party remains untouched.
     *
     * Phase 2 — Local commit (NonCancellable):
     * - Begins only after the target join succeeds.
     * - Commits the target server/token and local CONNECTING state.
     * - Dispatches old-party cleanup asynchronously.
     * - Target WebSocket establishment occurs outside the commit boundary.
     *
     * This API is intentionally separate from [joinParty]. It is the
     * deep-link transition state machine and may begin from either IDLE
     * or LIVE. It strictly contacts [targetCustomServer] and NEVER queries
     * or triggers idle fallback.
     *
     * The ordering is the whole point. A link tapped while a party is playing
     * has to either land the new party or leave the old one exactly as it was —
     * never half-way, which would mean a device holding a token for a server
     * it is no longer talking to and not being in the party it thinks it is in.
     */
    suspend fun switchPartyWithRecovery(
        targetCustomServer: String,
        targetCode: String,
        nickname: String = nickname(),
    ): SwitchPartyResult = switchMutex.withLock {
        withContext(Dispatchers.IO) {
            val who = identity(nickname) ?: return@withContext failed("Sign in to listen together.")

            val normTarget = when (val res = parseAndNormalizeServerUrl(targetCustomServer)) {
                is ServerUrlValidationResult.Valid -> res.normalizedUrl
                is ServerUrlValidationResult.Invalid -> return@withContext failed("Target server address is invalid.")
            }

            val targetBase = resolveHttpBase(normTarget)
            if (targetBase.isBlank()) {
                return@withContext failed("Target server address is invalid or missing.")
            }

            val cleanedTargetCode = targetCode.filter { it.isLetterOrDigit() }.uppercase()
            if (cleanedTargetCode.length != CODE_LENGTH) {
                return@withContext failed("A party code is six letters or digits.")
            }

            if (isRecentlyKicked(cleanedTargetCode)) {
                return@withContext failed(
                    "Couldn’t let you in — you’ve recently been kicked out of this party.",
                )
            }

            val oldServerBase = activePartyServerBase ?: resolveHttpBase(_customServer.value)
            val oldCode = _state.value.code
            val oldToken = token

            // Step 1: Join Target First while old party stays connected & playing
            val targetJoinResult = runCatching {
                post(
                    "$targetBase/api/parties/$cleanedTargetCode/join",
                    JoinRequest(who.userId, who.deviceId, who.name, who.avatar),
                )
            }

            val membership = targetJoinResult.getOrElse { failure ->
                Log.w(TAG, "failed to join target party: ${redact(failure.message)}")
                return@withContext failed(failure.displayMessage())
            }

            // Step 2: Target join succeeded. Commit the switch and release the
            // old slot, both inside NonCancellable so a cancelled scope cannot
            // leave the token written without the state that matches it.
            withContext(NonCancellable) {
                if (!oldCode.isNullOrBlank() && !oldToken.isNullOrBlank() &&
                    (oldServerBase != targetBase || !oldCode.equals(membership.code, ignoreCase = true))
                ) {
                    releaseStaleSlotOnServer(oldServerBase, oldCode, oldToken)
                }

                socketJob?.cancel()
                socketJob = null
                session = null

                activePartyServerBase = targetBase

                // The invite's server becomes the configured one, so the party
                // that is now live is the one the install is pointed at. Left
                // un-committed, the next idle operation would go to the old
                // address and this device would leave through the wrong door.
                setCustomServerUrl(normTarget)

                token = membership.token
                prefs.edit()
                    .putString(KEY_CODE, membership.code)
                    .putString(KEY_TOKEN, membership.token)
                    .apply()
                clock.reset()
                _state.value = State(
                    code = membership.code,
                    you = membership.you,
                    members = membership.party.members,
                    maxMembers = membership.party.maxMembers,
                    hostOnlyControl = membership.party.hostOnlyControl,
                    playback = membership.party.playback,
                    queue = membership.party.queue,
                    connection = Connection.CONNECTING,
                )
            }

            // Outside NonCancellable: establish socket connection
            connect()

            SwitchPartyResult.Success(membership.code)
        }
    }

    /**
     * The failure shape that matches where this device currently stands.
     *
     * Split out because the same message means two different things depending
     * on whether a party is still playing behind it, and getting that wrong
     * is how a failed invite silently stops the music.
     */
    private fun failed(message: String): SwitchPartyResult =
        if (_state.value.inParty) {
            SwitchPartyResult.TargetFailedRecovered(_state.value.code.orEmpty(), message)
        } else {
            SwitchPartyResult.TargetFailedNoParty(message)
        }

    /** Give up this device's slot. The party carries on without it. */
    suspend fun leaveParty() = switchMutex.withLock {
        withContext(Dispatchers.IO) {
            val code = _state.value.code
            val held = token
            // The leaving request has to go to the server that issued the
            // token. Taken before the state is cleared, because clearing the
            // state is what loses the address.
            val currentServer = activePartyServerBase ?: resolveHttpBase(effectiveIdleServerBase())
            socketJob?.cancel()
            socketJob = null
            session = null
            clock.reset()
            token = null
            activePartyServerBase = null
            _awaitingStart.value = false
            _activities.value = emptyList()
            prefs.edit().remove(KEY_CODE).remove(KEY_TOKEN).apply()
            _state.value = State()
            if (code != null && held != null) {
                runCatching {
                    http.post("$currentServer/api/parties/$code/leave") {
                        header("Authorization", "Bearer $held")
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------ controls --

    fun play(positionMs: Long? = null) = control("play") { positionMs?.let { put("positionMs", it) } }

    fun pause(positionMs: Long? = null) = control("pause") { positionMs?.let { put("positionMs", it) } }

    fun seek(positionMs: Long) = control("seek") { put("positionMs", positionMs) }

    fun next() = control("next") {}

    fun previous() = control("previous") {}

    fun setTrack(track: PartyTrack, positionMs: Long = 0, isPlaying: Boolean = true) =
        control("setTrack") {
            put("track", json.encodeToJsonElement(PartyTrack.serializer(), track))
            put("positionMs", positionMs)
            put("isPlaying", isPlaying)
        }

    fun setQueue(queue: List<PartyTrack>, index: Int) = control("setQueue") {
        put("queue", json.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(PartyTrack.serializer()), queue))
        put("queueIndex", index)
    }

    fun queueAdd(tracks: List<PartyTrack>, playNext: Boolean = false) = control("queueAdd") {
        put("tracks", json.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(PartyTrack.serializer()), tracks))
        put("playNext", playNext)
    }

    fun queueRemove(videoId: String) = control("queueRemove") {
        put("videoId", videoId)
    }

    fun queueClear() = control("queueClear") {}

    fun queueMove(fromIndex: Int, toIndex: Int, videoId: String? = null) = control("queueMove") {
        put("fromIndex", fromIndex)
        put("toIndex", toIndex)
        if (videoId != null) put("videoId", videoId)
    }

    fun setMaxMembers(value: Int) = control("setMaxMembers") { put("maxMembers", value) }

    fun kick(memberId: String) = control("kick") { put("memberId", memberId) }

    fun setAutoplay(enabled: Boolean) = control("setAutoplay") { put("enabled", enabled) }

    fun setHostOnlyControl(enabled: Boolean) = control("setHostOnlyControl") { put("enabled", enabled) }

    private fun control(action: String, body: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) {
        val frame = buildJsonObject {
            put("type", "control")
            put("action", action)
            body()
        }
        send(frame)
    }

    private fun send(frame: JsonObject) {
        val live = session ?: return
        scope.launch {
            runCatching { live.send(Frame.Text(frame.toString())) }
                .onFailure { Log.w(TAG, "control not sent: ${redact(it.message)}") }
        }
    }

    // --------------------------------------------------------- the playhead --

    fun partyPositionMs(): Long? {
        val playback = _state.value.playback
        playback.track ?: return null
        if (!playback.isPlaying) return playback.positionMs
        val serverNow = clock.serverNowMs() ?: return playback.effectivePositionMs
        val elapsed = (serverNow - playback.anchorMs).coerceAtLeast(0)
        val position = playback.positionMs + elapsed
        val duration = playback.track.durationMs
        return if (duration != null) minOf(position, duration) else position
    }

    fun msUntilStart(): Long {
        val playback = _state.value.playback
        if (!playback.isPlaying) return 0
        val serverNow = clock.serverNowMs() ?: return 0
        return (playback.anchorMs - serverNow).coerceAtLeast(0)
    }

    // ------------------------------------------------------------- socket --

    private fun connect() {
        socketJob?.cancel()
        socketJob = scope.launch { runSocketLoop() }
    }

    private suspend fun CoroutineScope.runSocketLoop() {
        var backoffMs = 1_000L
        while (isActive) {
            val code = _state.value.code
            val held = token
            if (code == null || held == null) {
                _state.update { it.copy(connection = Connection.OFFLINE) }
                return
            }
            try {
                _state.update { it.copy(connection = Connection.CONNECTING) }
                http.webSocket("${wsBase()}/ws/parties/$code?token=$held") {
                    session = this
                    backoffMs = 1_000L
                    _state.update { it.copy(connection = Connection.LIVE, error = null) }
                    launch { runPings() }
                    launch { runProgressReports() }
                    for (frame in incoming) {
                        if (frame is Frame.Text) onFrame(frame.readText())
                    }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                Log.i(TAG, "party socket died: ${redact(t.message)}; reconnecting in ${backoffMs}ms")
            } finally {
                session = null
                _state.update { it.copy(connection = Connection.OFFLINE, clockSynced = false) }
            }

            delay(backoffMs)
            backoffMs = (backoffMs * 2).coerceAtMost(20_000L)
        }
    }

    private suspend fun DefaultClientWebSocketSession.runPings() {
        while (isActive) {
            ping()
            delay(PING_INTERVAL_MS)
        }
    }

    private suspend fun DefaultClientWebSocketSession.ping() {
        val sentAt = ServerClock.localNowMs()
        val frame = buildJsonObject {
            put("type", "ping")
            put("clientMs", sentAt)
        }
        runCatching { send(Frame.Text(frame.toString())) }
    }

    private suspend fun DefaultClientWebSocketSession.runProgressReports() {
        while (isActive) {
            delay(REPORT_INTERVAL_MS)
            val pos = partyPositionMs() ?: continue
            val frame = buildJsonObject {
                put("type", "report")
                put("positionMs", pos)
                put("clientMs", ServerClock.localNowMs())
            }
            runCatching { send(Frame.Text(frame.toString())) }
        }
    }

    private fun onFrame(text: String) {
        val received = ServerClock.localNowMs()
        val frame = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
        when (frame["type"]?.jsonPrimitive?.content) {
            "welcome" -> {
                val party = frame["party"]?.let {
                    runCatching { json.decodeFromJsonElement(PartySnapshot.serializer(), it) }.getOrNull()
                } ?: return
                val you = frame["you"]?.let {
                    runCatching { json.decodeFromJsonElement(PartyMember.serializer(), it) }.getOrNull()
                }
                _state.update { it.copy(
                    code = party.code,
                    you = you ?: it.you,
                    members = party.members,
                    maxMembers = party.maxMembers,
                    hostOnlyControl = party.hostOnlyControl,
                    playback = party.playback,
                    queue = party.queue,
                    connection = Connection.LIVE,
                    error = null,
                ) }
            }

            "pong" -> {
                val sentAt = frame["clientMs"]?.jsonPrimitive?.content?.toLongOrNull() ?: return
                val serverMs = frame["serverMs"]?.jsonPrimitive?.content?.toLongOrNull() ?: return
                clock.record(sentAt, serverMs, received)
                _state.update { it.copy(
                    clockSynced = clock.synced,
                    roundTripMs = clock.roundTripMs,
                ) }
            }

            "state" -> {
                val playback = frame["playback"]?.let {
                    runCatching { json.decodeFromJsonElement(PartyPlayback.serializer(), it) }.getOrNull()
                } ?: return
                if (playback.seq < _state.value.playback.seq) return
                _state.update { it.copy(playback = playback) }
                if (playback.queueSeq != _state.value.queue.seq) {
                    send(buildJsonObject { put("type", "syncQueue") })
                }
            }

            "queue" -> {
                val queue = frame["queue"]?.let {
                    runCatching { json.decodeFromJsonElement(PartyQueue.serializer(), it) }.getOrNull()
                } ?: return
                if (queue.seq < _state.value.queue.seq) return
                _state.update { it.copy(queue = queue) }
            }

            "members" -> {
                val members = frame["members"]?.let {
                    runCatching {
                        json.decodeFromJsonElement(
                            kotlinx.serialization.builtins.ListSerializer(PartyMember.serializer()),
                            it,
                        )
                    }.getOrNull()
                } ?: return
                _state.update { it.copy(members = members) }
            }

            "activity" -> {
                val action = frame["action"]?.jsonPrimitive?.content ?: return
                val by = frame["by"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: return
                val atMs = frame["atMs"]?.jsonPrimitive?.content?.toLongOrNull() ?: received
                val detail = frame["detail"]?.jsonPrimitive?.content.orEmpty()
                recordActivity(PartyActivity(action, by, atMs, detail))
            }

            "error" -> {
                val reason = frame["error"]?.jsonPrimitive?.content
                val message = frame["message"]?.jsonPrimitive?.content
                Log.w(TAG, "party server refused a frame: $reason ${redact(message)}")
                _state.update { it.copy(error = message) }
                if (reason == "bad_token" || reason == "no_such_party") {
                    scope.launch { leaveParty() }
                }
            }

            "bye" -> {
                if (frame["reason"]?.jsonPrimitive?.content == "kicked") {
                    _state.value.code?.let(::recordKick)
                }
                scope.launch { leaveParty() }
            }
        }
    }

    // ------------------------------------------------------------- kick cooldown --

    private const val KICK_COOLDOWN_MS = 5 * 60 * 1000L

    private fun recordKick(code: String) {
        val now = ServerClock.localNowMs()
        val current = prefs.getString(KEY_KICKED, null).orEmpty()
        val updated = current.split(";")
            .filter { it.isNotBlank() }
            .mapNotNull {
                val parts = it.split(":")
                if (parts.size == 2) parts[0] to parts[1].toLongOrNull() else null
            }
            .filter { (_, time) -> time != null && now - time < KICK_COOLDOWN_MS }
            .toMap()
            .toMutableMap()
        updated[code.uppercase()] = now
        val serialized = updated.entries.joinToString(";") { "${it.key}:${it.value}" }
        prefs.edit().putString(KEY_KICKED, serialized).apply()
    }

    private fun refuseIfRecentlyKicked(code: String) {
        val now = ServerClock.localNowMs()
        val current = prefs.getString(KEY_KICKED, null).orEmpty()
        val kicked = current.split(";")
            .filter { it.isNotBlank() }
            .mapNotNull {
                val parts = it.split(":")
                if (parts.size == 2) parts[0] to parts[1].toLongOrNull() else null
            }
            .filter { (_, time) -> time != null && now - time < KICK_COOLDOWN_MS }
            .toMap()
        if (kicked.containsKey(code.uppercase())) {
            throw PartyException(
                "recently_kicked",
                "Couldn’t let you in — you’ve recently been kicked out of this party.",
            )
        }
    }

    // ------------------------------------------------------------- plumbing --

    private data class Identity(
        val userId: String,
        val deviceId: String,
        val name: String,
        val avatar: String?,
    )

    private fun identity(nickname: String = nickname()): Identity? {
        val store = YZMusicApplication.authStore
        if (!store.isSignedIn) return null
        val account = store.activeSession ?: return null
        val profile = account.profiles.firstOrNull { it.profileId == account.activeProfileId }
            ?: account.profiles.firstOrNull()
        // A nickname the user set for parties wins over the account's name,
        // and blank falls through to the account — so an empty field is the
        // "just use my name" answer rather than an anonymous one.
        val name = nickname.trim().takeIf { it.isNotBlank() }
            ?: profile?.name?.takeIf { it.isNotBlank() }
            ?: account.name.takeIf { it.isNotBlank() }
            ?: account.email.substringBefore('@').takeIf { it.isNotBlank() }
            ?: return null
        return Identity(
            userId = sha256("${account.accountId}:${profile?.profileId.orEmpty()}").take(32),
            deviceId = deviceId(),
            name = name,
            avatar = profile?.avatar?.takeIf { it.startsWith("http") },
        )
    }

    /**
     * The picture the rest of the party will see against this device's name.
     *
     * Read off [identity] rather than the account directly, so the sheets show
     * what will actually be sent rather than a second guess at it — including
     * answering null in the cases a party cannot be joined at all.
     */
    fun myAvatarUrl(): String? = identity()?.avatar

    /** Whether this code is one this device was recently removed from. */
    private fun isRecentlyKicked(code: String): Boolean {
        val now = ServerClock.localNowMs()
        val current = prefs.getString(KEY_KICKED, null).orEmpty()
        return current.split(";")
            .filter { it.isNotBlank() }
            .mapNotNull {
                val parts = it.split(":")
                if (parts.size == 2) parts[0] to parts[1].toLongOrNull() else null
            }
            .any { (kicked, time) -> kicked.equals(code.uppercase(), ignoreCase = true) && time != null && now - time < KICK_COOLDOWN_MS }
    }

    private fun deviceId(): String {
        prefs.getString(KEY_DEVICE, null)?.let { return it }
        return UUID.randomUUID().toString().also {
            prefs.edit().putString(KEY_DEVICE, it).apply()
        }
    }

    private suspend fun post(url: String, body: JoinRequest): PartyMembership {
        val response = http.post(url) {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        if (!response.status.isSuccess()) throw response.toPartyException()
        return response.body()
    }

    private suspend fun HttpResponse.toPartyException(): PartyException {
        val body = runCatching { bodyAsText() }.getOrDefault("")
        val parsed = runCatching { json.decodeFromString(ApiError.serializer(), body) }.getOrNull()
        return PartyException(
            code = parsed?.code.orEmpty().ifBlank { "http_${status.value}" },
            message = parsed?.message?.takeIf { it.isNotBlank() }
                ?: if (status.value == 422) "This account can't be used to jam."
                else "The party server said ${status.value}.",
            statusCode = status.value,
        )
    }

    fun normalizeServerBase(value: String): String =
        value.trim().trimEnd('/')

    /**
     * Resolves a server address to a fully qualified HTTP base URL.
     *
     * Blank means the default server rather than nothing, because the one
     * place a blank genuinely means "unreachable" is a build configured with
     * no address at all, and that is caught at construction.
     */
    private fun resolveHttpBase(server: String): String {
        val raw = normalizeServerBase(server).ifBlank { defaultServer }
        if (raw.isBlank()) return ""
        return if (raw.startsWith("http://") || raw.startsWith("https://")) raw else "https://$raw"
    }

    /**
     * The base this device's own socket and any remaining idle calls use.
     *
     * Read from the resolved idle server rather than the configured one, so a
     * custom address that failed its probe does not take the live socket down
     * with it: resolution already moved idle work to the default server, and
     * the socket has to follow the same answer or it would be pointed at a
     * host that was just reported unreachable.
     */
    private fun httpBase(): String = resolveHttpBase(effectiveIdleServerBase())

    private fun redact(text: String?): String {
        var out = text.orEmpty()
        if (out.isEmpty()) return out
        listOf(DEFAULT_SERVER, _customServer.value)
            .filter { it.isNotBlank() }
            .flatMap { listOf(it, it.substringAfter("://")) }
            .sortedByDescending(String::length)
            .forEach { out = out.replace(it, SERVER_PLACEHOLDER, ignoreCase = true) }
        return out.replace(ABSOLUTE_URL, SERVER_PLACEHOLDER)
    }

    private fun Throwable.displayMessage(): String = when (this) {
        is PartyException -> message ?: UNREACHABLE
        else -> UNREACHABLE
    }

    private fun wsBase(): String = httpBase()
        .replaceFirst("https://", "wss://")
        .replaceFirst("http://", "ws://")

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }

    const val CODE_LENGTH = 6
    private const val MAX_ACTIVITIES = 30

    private const val TAG = "ListenTogether"
    private const val PREFS = "yzmusic_listen_together"
    private const val KEY_SERVER = "server_url"
    private const val KEY_KICKED = "recent_kicks"

    private const val SERVER_PLACEHOLDER = "<party server>"
    private const val UNREACHABLE = "Couldn’t reach the party server."

    private val ABSOLUTE_URL = Regex(""" (?:https?|wss?)://[^\s,;)\]}'\"]+""", RegexOption.IGNORE_CASE)
    private const val KEY_CODE = "party_code"
    private const val KEY_TOKEN = "party_token"
    private const val KEY_DEVICE = "device_id"
    private const val KEY_NICKNAME = "party_nickname"

    private const val PING_INTERVAL_MS = 15_000L
    private const val REPORT_INTERVAL_MS = 10_000L
    private const val HEALTH_TIMEOUT_MS = 45_000L
}
