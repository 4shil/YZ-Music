package com.music.yzmusic.data.stats

import android.content.Context
import android.net.Uri
import android.util.Log
import com.music.yzmusic.BuildConfig
import com.music.yzmusic.data.Http
import com.music.yzmusic.data.YtMusicRepository
import com.music.yzmusic.data.model.SearchFilter
import com.music.yzmusic.data.model.SearchResult
import com.music.yzmusic.data.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * What the Replay knows about an artist beyond their name: their picture, their
 * page, and what kind of music they make.
 *
 * ## Why any of this needs looking up
 *
 * Listening is counted per *name*, because a name is the only thing every track
 * carries — see [ListeningStats]. That is enough to rank artists and nothing
 * else. Three things the artist chart wants are simply not in the listening:
 *
 *  - **A picture of the artist.** Every track carries its own sleeve, and using
 *    that gave an artist chart illustrated with album covers — the same cover as
 *    the song chart above it, which reads as the page having drawn the wrong
 *    list rather than as a deliberate choice.
 *  - **A page to open.** A browse id only rides along when the row that queued
 *    the track happened to have one, which for a home-feed card or an AutoPlay
 *    suggestion it does not.
 *  - **A genre.** Nothing this app already talks to states one. YouTube Music's
 *    browse responses carry none — an album page bills itself "Album • 2023" —
 *    and the source modules hand back audio, not taxonomy.
 *
 * ## One store, two sources, two gates
 *
 * The picture and the page come from a YouTube Music artist search, which this
 * app is already talking to constantly and which is therefore not worth a
 * setting. The genre comes from Last.fm's `artist.getTopTags`, which is a
 * different service and *is* a setting ([AppSettings.replayGenres]): it sends an
 * artist's name and nothing else — no track, no time, no id, no indication that
 * anything was played — and turned off, the genre chart simply isn't drawn while
 * every other chart is unaffected.
 *
 * Both are asked once per artist and kept on this device for good, so the cost
 * is a couple of requests the first time somebody new turns up.
 *
 * ## Why the tags are filtered rather than used
 *
 * Last.fm tags are folksonomy, not taxonomy: alongside "shoegaze" sit "seen
 * live", "favourites", "albums i own" and several thousand more that describe
 * the tagger rather than the music. Taking the top tag verbatim produces a chart
 * whose leading genre is "awesome". So a tag only counts if it matches
 * [VOCABULARY] — a fixed list of things that are actually genres — and an artist
 * with no matching tag contributes nothing rather than a wrong answer.
 */
object ArtistFacts {

    private lateinit var file: File

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** artist (lowercased) → what is known about them. */
    private val known = ConcurrentHashMap<String, StoredArtist>()

    /** Names already queued this session, so a track on repeat asks once. */
    private val queued = ConcurrentHashMap.newKeySet<String>()

    private val requests = Channel<String>(Channel.UNLIMITED)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var dirty = false

    /**
     * Bumped whenever a lookup lands.
     *
     * The Replay reads these facts once, while it is building its charts, so a
     * picture that arrives a second after the page opened would otherwise not
     * appear until the page was opened again — which for a page opened a few
     * times a year means never. Watching this lets it rebuild.
     */
    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision.asStateFlow()

    fun init(context: Context) {
        file = File(context.filesDir, FILE_NAME)
        scope.launch {
            load()
            worker()
        }
        // Writes are on a timer rather than one per answer. The cache holds
        // every artist ever played, so rewriting it after each lookup meant a
        // few hundred kilobytes per artist during a backfill — for a file that
        // is only read once, at launch.
        scope.launch {
            while (true) {
                delay(SAVE_INTERVAL_MS)
                save()
            }
        }
    }

    private val ready: Boolean get() = this::file.isInitialized

    /** Whether a genre chart can be drawn at all on this build and these settings. */
    val genresAvailable: Boolean
        get() = AppSettings.replayGenres.value && BuildConfig.LASTFM_API_KEY.isNotBlank()

    // ── Reading ─────────────────────────────────────────────────────────────
    //
    // None of these reach the network. The Replay is drawn from what is already
    // known, and an artist that isn't yet simply keeps the track's sleeve and no
    // genre this time round. Asking here would put a round trip per artist
    // behind a page that opens with fifty of them on it.

    fun genresFor(artist: String): List<String> {
        if (!genresAvailable) return emptyList()
        return known[key(artist)]?.genres.orEmpty()
    }

    /** A picture of the artist, or null to fall back to whatever the row has. */
    fun imageFor(artist: String): String? = known[key(artist)]?.image

    /** The artist's page, so a chart row opens it without searching first. */
    fun browseIdFor(artist: String): String? = known[key(artist)]?.browseId

    /**
     * An artist was played. Looks them up if anything about them is missing.
     *
     * Called from the recording path rather than from the Replay page, so the
     * answers accumulate quietly while music plays and the page has them in hand
     * when it opens.
     */
    fun noticed(artist: String) {
        if (!ready) return
        val key = key(artist)
        if (key.isEmpty() || key.length > MAX_NAME_LENGTH) return
        val entry = known[key]
        if (entry != null && !entry.wants()) return
        if (!queued.add(key)) return
        requests.trySend(artist.trim())
    }

    /** What is still worth asking about for this artist. */
    private fun StoredArtist.wants(): Boolean {
        // A miss is remembered too, or an artist neither service has heard of is
        // asked about on every play forever. It expires, because the reason for
        // a miss is as often a dropped connection as an unknown artist.
        val wantsCard = browseId == null &&
            System.currentTimeMillis() - cardAt > TimeUnit.DAYS.toMillis(RETRY_DAYS)
        val wantsGenres = genresAvailable && genres.isEmpty() &&
            System.currentTimeMillis() - genresAt > TimeUnit.DAYS.toMillis(RETRY_DAYS)
        return wantsCard || wantsGenres
    }

    // ── The lookups ─────────────────────────────────────────────────────────

    /**
     * One artist at a time, spaced out.
     *
     * Serial and slow on purpose: this is background enrichment for a page that
     * may not be opened for months, and it shares [Http.client] and the Innertube
     * session with playback. Nothing here is worth a millisecond of a stream's
     * latency.
     */
    private suspend fun worker() {
        for (name in requests) {
            val existing = known[key(name)]
            if (existing?.browseId == null) {
                runCatching { fetchCard(name) }
                    .onFailure { Log.w(TAG, "Artist lookup failed for $name", it) }
            }
            if (genresAvailable && existing?.genres.isNullOrEmpty()) {
                runCatching { fetchGenres(name) }
                    .onFailure { Log.w(TAG, "Genre lookup failed for $name", it) }
            }
            // Published straight away so an open Replay picks the answer up;
            // the disk copy follows on its own timer, above.
            _revision.value++
            delay(REQUEST_SPACING_MS)
        }
    }

    /**
     * The artist's picture and page, off a YouTube Music artist search.
     *
     * A search rather than a browse, because a browse needs the id and the id is
     * half of what this is for. The top artist hit for a name is the artist:
     * that is the same lookup the app already makes to find a video's catalogue
     * release, and the same one a person would make.
     */
    private suspend fun fetchCard(name: String) {
        val hit = YtMusicRepository.search(name, SearchFilter.ARTISTS).getOrNull()
            ?.filterIsInstance<SearchResult.Browse>()
            ?.firstOrNull()
            ?.item
        val entry = known[key(name)] ?: StoredArtist(key = key(name))
        known[key(name)] = entry.copy(
            // Only when the hit is actually this artist. A search for a name
            // nobody has heard of still returns *something*, and filing a
            // stranger's photograph under someone else's name is worse than
            // keeping the sleeve.
            image = hit?.thumbnailUrl?.takeIf { hit.title.equals(name, ignoreCase = true) }
                ?: entry.image,
            browseId = hit?.browseId?.takeIf { hit.title.equals(name, ignoreCase = true) }
                ?: entry.browseId,
            cardAt = System.currentTimeMillis(),
        )
        dirty = true
    }

    private fun fetchGenres(name: String) {
        val url = "https://ws.audioscrobbler.com/2.0/?method=artist.gettoptags" +
            "&artist=${Uri.encode(name)}" +
            "&api_key=${Uri.encode(BuildConfig.LASTFM_API_KEY)}" +
            "&autocorrect=1&format=json"
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        val body = Http.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return
            response.body?.string()
        } ?: return

        val tags = runCatching {
            Json.parseToJsonElement(body).jsonObject["toptags"]
                ?.jsonObject?.get("tag")?.jsonArray
                ?.mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.content }
        }.getOrNull().orEmpty()

