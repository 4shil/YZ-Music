package com.music.yzmusic.data.sources

import android.util.Log
import com.music.yzmusic.data.TrackLog
import com.music.yzmusic.data.model.Song
import com.music.yzmusic.data.sources.module.ModuleManager
import com.music.yzmusic.data.sources.module.ModuleSearchResult
import com.music.yzmusic.data.sources.module.SpineModule
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.Locale

/**
 * A [MusicSource] backed by one or more compatible JS module plugins.
 *
 * The [config]'s [SourceConfig.baseUrl] points at a module-index JSON
 * (e.g. `https://example.com/index.json`). That index lists `SpineModule`
 * descriptors; each one ships a JS file that exports `searchTracks()` and
 * `getTrackStreamUrl()`. This class fetches the index, loads the JS into a
 * QuickJS sandbox, and routes [search] / [stream] through those exports.
 *
 * Track IDs are encoded as `<moduleId>::<upstreamId>` so [stream] can
 * identify which loaded module to call.
 */
class ModuleSource(
    override val config: SourceConfig,
) : MusicSource, SourceRegistry.ConfigBacked {

    override val configId: String get() = config.id
    override val kind: SourceKind get() = SourceKind.MODULE
    override val displayName: String get() = config.displayName

    /**
     * One manager per source instance. The manager's in-memory module cache
     * survives across successive search calls on the same instance, which is
     * what keeps the QuickJS engines alive between a search and the stream
     * call for a result it returned.
     */
    private val manager = ModuleManager()

    // ── Health ────────────────────────────────────────────────────────────

    override suspend fun health(): SourceHealth = withContext(Dispatchers.IO) {
        if (config.baseUrl.isBlank()) {
            return@withContext SourceHealth.Rejected("A module index URL is required")
        }
        try {
            val modules = manager.fetchIndex(config.baseUrl).getOrThrow()
            when {
                modules.isEmpty() -> SourceHealth.Rejected(
                    "The index answered but listed no modules — check the URL"
                )
                else -> SourceHealth.Ok("${modules.size} module${if (modules.size == 1) "" else "s"}")
            }
        } catch (e: Exception) {
            TrackLog.w(TAG, "module index fetch failed for ${config.displayName}: ${e.message}")
            SourceHealth.Unreachable(e.message ?: "Could not reach the module index")
        }
    }

    // ── Search ────────────────────────────────────────────────────────────

    /**
     * Every module in the index, asked at once, answers interleaved, and
     * nobody waited on past the point of usefulness.
     *
     * Three things, all of which the obvious implementation gets wrong:
     *
     *  - **At once.** A module is a network fetch, a JS load and a search;
     *    done one after another, a three-module index spent three times as
     *    long answering as it had to, against a lookup that has to finish
     *    before audio can start.
     *  - **Interleaved.** Filling the result list module by module and
     *    stopping at [limit] means the first module's tail crowds out every
     *    other module's best hit — with a limit of eight and a chatty first
     *    module, the rest of the index was never asked at all. Round-robin
     *    puts each module's top result ahead of any module's second, so a
     *    track only one of them holds survives the cut.
     *  - **Not to the last straggler.** Asked at once but awaited together,
     *    the slowest module becomes the price of every track — 7.5s against
     *    1.5s for the fastest, measured on the same query. So the fan-out
     *    closes a short grace period after the first useful answer, and
     *    whoever hasn't spoken by then sits this track out.
     *
     * What comes back is a candidate list, not a ranking:
     * [TrackMatcher][com.music.yzmusic.data.sources.TrackMatcher] decides
     * which rows are the recording and [SourceResolver] decides which of those
     * to open. This only has to be complete enough to contain the right one.
     */
    override suspend fun search(query: String, limit: Int, waitForAll: Boolean): List<Song> =
        withContext(Dispatchers.IO) {
            if (query.isBlank()) return@withContext emptyList()
            val indexUrl = config.baseUrl
            val baseUrl = indexUrl.substringBeforeLast("/")

            val modules = manager.fetchIndex(indexUrl).getOrElse { e ->
                TrackLog.w(TAG, "${config.displayName}: index fetch failed — ${e.message}")
                return@withContext emptyList()
            }

            val perModule = coroutineScope {
                val answers = arrayOfNulls<List<Song>>(modules.size)
                val first = CompletableDeferred<Unit>()
                val jobs = modules.mapIndexed { at, module ->
                    launch {
                        val songs = searchOne(module, query, limit, baseUrl)
                        answers[at] = songs
                        if (songs.isNotEmpty()) first.complete(Unit)
                    }
                }
                // Everyone gets until someone useful answers, and a short
                // grace period after that. Waiting for all of them made the
                // slowest module the cost of every track — measured at 7.5s
                // against 1.5s for the fastest, on a lookup that has to finish
                // before audio can start. Waiting for only the first is the
                // opposite mistake: the fast module is not reliably the one
                // holding the best copy, and the grace period is what buys the
                // chance to compare them.
                if (waitForAll) {
                    // Patient, not indefinite. A flat join on everyone made the
                    // *slowest* module the price of every single track — and on
                    // a batch download, where this path runs once per track back
                    // to back, a module that simply never answers was 20s of
                    // dead time per song and nothing to show for it. Every
                    // module still gets a real hearing, several times what the
                    // live path allows; what it no longer gets is unlimited
                    // time while the queue stands still.
                    withTimeoutOrNull(SEARCH_PATIENT_MS) {
                        withTimeoutOrNull(SEARCH_BUDGET_MS) { first.await() }
                        withTimeoutOrNull(SEARCH_PATIENT_GRACE_MS) { jobs.joinAll() }
                    }
                } else {
                    withTimeoutOrNull(SEARCH_BUDGET_MS) { first.await() }
                    withTimeoutOrNull(SEARCH_GRACE_MS) { jobs.joinAll() }
                }
                jobs.forEach { it.cancel() }
                answers.filterNotNull()
            }
            interleave(perModule).take(limit)
        }

    /** One module's answers, or an empty list if it couldn't give any. */
    private suspend fun searchOne(
        module: SpineModule,
        query: String,
        limit: Int,
        baseUrl: String,
    ): List<Song> {
        val loaded = manager.loadModule(module) { baseUrl }.getOrElse { e ->
            TrackLog.w(TAG, "${config.displayName}: load failed for ${module.id} — ${e.message}")
            return emptyList()
        }
        val searchResponse = manager.searchTracks(loaded, query, limit).getOrElse { e ->
            TrackLog.w(TAG, "${config.displayName}: search failed for ${module.id} — ${e.message}")
            return emptyList()
        }
        return searchResponse.tracks.map { track ->
            Song(
                // Encode module id + upstream id so stream() can route back.
                videoId = SourceRegistry.trackKey(config.id, "${module.id}$MOD_SEPARATOR${track.id}"),
                title = track.title,
                artist = track.artist,
                albumName = track.album.ifBlank { null },
                thumbnailUrl = track.albumCover,
                durationText = track.duration.takeIf { it > 0 }
                    ?.let { "${it / 60}:${"%02d".format(Locale.ROOT, it % 60)}" },
                sourceQuality = rowTier(track),
            )
        }
    }

    /**
     * What this row can be had at, from what the module said about *it*.
     *
     * Precedence is the whole point. A row stating `format: flac` or
     * `FLAC 16-bit / 44.1kHz` is describing the copy it holds, and is worth
     * believing. A row listing `availableQualities: [LOSSLESS, HIGH, LOW]` is
     * describing what its backend might in principle be asked for, and is
     * worth much less: the aggregator module publishes that list on rows whose
     * lossless backend then declines and whose stream arrives from SoundCloud
     * at 128kbps. So the stated quality is read first and the menu of
     * possibilities only when nothing was stated — otherwise every row from
     * that module claims the top tier and the ordering it feeds is noise.
     */
