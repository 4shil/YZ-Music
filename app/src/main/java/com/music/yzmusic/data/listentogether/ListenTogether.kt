package com.music.yzmusic.data.listentogether

import android.content.Context
import android.content.SharedPreferences
import com.music.yzmusic.BuildConfig
import com.music.yzmusic.YZMusicApplication
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
    data object Blank : ServerUrlError
    data object UnsupportedScheme : ServerUrlError
    data object InvalidHost : ServerUrlError
    data object InvalidPort : ServerUrlError
}

sealed interface ServerUrlValidationResult {
    data class Valid(val normalizedUrl: String) : ServerUrlValidationResult
    data class Invalid(val error: ServerUrlError) : ServerUrlValidationResult
}

fun parseAndNormalizeServerUrl(rawUrl: String): ServerUrlValidationResult {
    val trimmed = rawUrl.trim()
    if (trimmed.isEmpty()) return ServerUrlValidationResult.Invalid(ServerUrlError.Blank)

    val withScheme = if (!trimmed.startsWith("http://", ignoreCase = true) &&
        !trimmed.startsWith("https://", ignoreCase = true)
    ) {
        "https://$trimmed"
    } else {
        trimmed
    }

    val uri = runCatching { URI(withScheme) }.getOrNull()
        ?: return ServerUrlValidationResult.Invalid(ServerUrlError.InvalidHost)

    val scheme = uri.scheme?.lowercase()
    if (scheme != "http" && scheme != "https") {
        return ServerUrlValidationResult.Invalid(ServerUrlError.UnsupportedScheme)
    }

    val port = uri.port
    if (port != -1 && port !in 1..65535) {
        return ServerUrlValidationResult.Invalid(ServerUrlError.InvalidPort)
    }

    val rawHost = uri.host ?: return ServerUrlValidationResult.Invalid(ServerUrlError.InvalidHost)
    if (rawHost.isEmpty()) return ServerUrlValidationResult.Invalid(ServerUrlError.InvalidHost)

    val portSuffix = if (port != -1) ":$port" else ""
    val canonicalPath = uri.rawPath?.trimEnd('/') ?: ""
    return ServerUrlValidationResult.Valid("$scheme://${rawHost.lowercase()}$portSuffix$canonicalPath")
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

    /** Whether a party can be reached at all — a default or a custom address. */
    val hasServer: Boolean get() = httpBase().isNotBlank()

    enum class Health { UNKNOWN, CHECKING, ONLINE, OFFLINE }

    data class ServerStatus(val health: Health = Health.UNKNOWN, val latencyMs: Long = 0)

    private val _serverStatus = MutableStateFlow(ServerStatus())
    val serverStatus: StateFlow<ServerStatus> = _serverStatus.asStateFlow()

    private var healthJob: Job? = null

    fun refreshServerHealth() {
        if (healthJob?.isActive == true) return
        healthJob = scope.launch {
            val base = httpBase()
            if (base.isBlank()) {
                _serverStatus.value = ServerStatus(Health.OFFLINE)
                return@launch
            }
            _serverStatus.value = ServerStatus(Health.CHECKING)
            val startedAt = ServerClock.localNowMs()
            val ok = runCatching {
                http.get("$base/healthz") {
                    timeout { requestTimeoutMillis = HEALTH_TIMEOUT_MS }
                }.status.isSuccess()
            }.getOrElse {
                Log.w(TAG, "health check failed: ${redact(it.message)}")
                false
            }
            _serverStatus.value = ServerStatus(
                health = if (ok) Health.ONLINE else Health.OFFLINE,
                latencyMs = ServerClock.localNowMs() - startedAt,
            )
        }
    }

    private lateinit var prefs: SharedPreferences
    private var token: String? = null

    @Volatile
    private var session: DefaultClientWebSocketSession? = null
    private var socketJob: Job? = null

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val savedServer = prefs.getString(KEY_SERVER, null)?.trim().orEmpty()
        when (val res = parseAndNormalizeServerUrl(savedServer)) {
            is ServerUrlValidationResult.Valid -> _customServer.value = res.normalizedUrl
            is ServerUrlValidationResult.Invalid -> _customServer.value = ""
        }

        val code = prefs.getString(KEY_CODE, null)
        val saved = prefs.getString(KEY_TOKEN, null)
        prefs.edit().remove(KEY_CODE).remove(KEY_TOKEN).apply()
        if (!code.isNullOrBlank() && !saved.isNullOrBlank()) {
            releaseStaleSlot(code, saved)
        }
    }

    private fun releaseStaleSlot(code: String, held: String) {
        scope.launch {
            runCatching {
                http.post("${httpBase()}/api/parties/$code/leave") {
                    header("Authorization", "Bearer $held")
                }
            }.onFailure { failure ->
                Log.i(TAG, "stale party slot left to the server's grace: ${redact(failure.message)}")
            }
        }
    }

    /** Points this install at another server, or back at the default one if blank. */
    fun setCustomServerUrl(value: String) {
        val trimmed = value.trim()
        if (trimmed.isBlank()) {
            _customServer.value = ""
            prefs.edit().remove(KEY_SERVER).apply()
            return
        }
        when (val res = parseAndNormalizeServerUrl(trimmed)) {
            is ServerUrlValidationResult.Valid -> {
                _customServer.value = res.normalizedUrl
                prefs.edit().putString(KEY_SERVER, res.normalizedUrl).apply()
            }
            is ServerUrlValidationResult.Invalid -> {
                Log.w(TAG, "ignoring invalid custom server URL: $trimmed")
            }
        }
    }

    /** Whether this device has an account it can jam as. */
    fun canJoin(): Boolean = identity() != null

    fun ensureConnected() {
        if (_state.value.code == null || token == null) return
        if (socketJob?.isActive == true) return
        connect()
    }

    // ------------------------------------------------------------ joining --

    suspend fun createParty(maxMembers: Int = 5): Result<String> = enter { who ->
        post(
            "${httpBase()}/api/parties",
            JoinRequest(who.userId, who.deviceId, who.name, who.avatar, maxMembers = maxMembers),
        )
    }

    suspend fun joinParty(code: String, server: String? = null): Result<String> {
        val previousCode = _state.value.code
        val previousToken = token
        val result = enter { who ->
            val cleaned = code.filter { it.isLetterOrDigit() }.uppercase()
            if (cleaned.length != CODE_LENGTH) {
                throw PartyException("bad_code", "A party code is six letters or digits.")
            }
            refuseIfRecentlyKicked(cleaned)
            val base = server?.let {
                when (val res = parseAndNormalizeServerUrl(it)) {
                    is ServerUrlValidationResult.Valid -> res.normalizedUrl
                    is ServerUrlValidationResult.Invalid -> null
                }
            } ?: httpBase()
            post(
                "$base/api/parties/$cleaned/join",
                JoinRequest(who.userId, who.deviceId, who.name, who.avatar),
            )
        }

        val joinedCode = result.getOrNull()
        if (
            joinedCode != null &&
            previousCode != null &&
            previousToken != null &&
            !previousCode.equals(joinedCode, ignoreCase = true)
        ) {
            releaseStaleSlot(previousCode, previousToken)
        }
        return result
    }

    suspend fun previewParty(code: String, server: String? = null): Result<PartyPreview> = withContext(Dispatchers.IO) {
        val cleaned = code.filter { it.isLetterOrDigit() }.uppercase()
        if (cleaned.length != CODE_LENGTH) {
            return@withContext Result.failure(PartyException("bad_code", "A party code is six letters or digits."))
        }
        runCatching {
            refuseIfRecentlyKicked(cleaned)
            val base = (server?.let {
                when (val res = parseAndNormalizeServerUrl(it)) {
                    is ServerUrlValidationResult.Valid -> res.normalizedUrl
                    is ServerUrlValidationResult.Invalid -> null
                }
            } ?: httpBase()).ifBlank { throw PartyException("no_server", "Set the party server address first.") }

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

    private suspend fun enter(request: suspend (Identity) -> PartyMembership): Result<String> =
        withContext(Dispatchers.IO) {
            val who = identity()
                ?: return@withContext Result.failure(
                    PartyException("not_signed_in", "Sign in to listen together."),
                )
            if (httpBase().isBlank()) {
                return@withContext Result.failure(
                    PartyException("no_server", "Set the party server address first."),
                )
            }
            runCatching { request(who) }
                .onSuccess { membership ->
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
                    connect()
                }
                .onFailure { failure ->
                    Log.w(TAG, "could not enter a party: ${redact(failure.message)}")
                    _state.update { it.copy(error = failure.displayMessage()) }
                }
                .map { it.code }
        }

    /** Give up this device's slot. The party carries on without it. */
    suspend fun leaveParty() = withContext(Dispatchers.IO) {
        val code = _state.value.code
        val held = token
        socketJob?.cancel()
        socketJob = null
        session = null
        clock.reset()
        token = null
        _awaitingStart.value = false
        _activities.value = emptyList()
        prefs.edit().remove(KEY_CODE).remove(KEY_TOKEN).apply()
        _state.value = State()
        if (code != null && held != null) {
            runCatching {
                http.post("${httpBase()}/api/parties/$code/leave") {
                    header("Authorization", "Bearer $held")
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

    private fun identity(): Identity? {
        val store = YZMusicApplication.authStore
        if (!store.isSignedIn) return null
        val account = store.activeSession ?: return null
        val profile = account.profiles.firstOrNull { it.profileId == account.activeProfileId }
            ?: account.profiles.firstOrNull()
        val name = profile?.name?.takeIf { it.isNotBlank() }
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

    private fun httpBase(): String {
        val raw = _customServer.value.trim().trimEnd('/').ifBlank { DEFAULT_SERVER }
        if (raw.isBlank()) return ""
        return if (raw.startsWith("http://") || raw.startsWith("https://")) raw else "https://$raw"
    }

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

    private val DEFAULT_SERVER: String = BuildConfig.LISTEN_TOGETHER_SERVER.ifBlank { "https://yz-music-party.onrender.com" }
    private const val PING_INTERVAL_MS = 15_000L
    private const val REPORT_INTERVAL_MS = 10_000L
    private const val HEALTH_TIMEOUT_MS = 45_000L
}
