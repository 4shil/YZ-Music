package com.music.yzmusic.data.sources.module

import android.util.Log
import com.music.yzmusic.data.TrackLog
import com.music.yzmusic.data.Http
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.Request

/**
 * Fetches a module index, downloads and loads module JS, and calls the
 * module's exported search/stream functions.
 *
 * Ported from Convx's `ModuleManager`, adapted to use YZ Music's shared
 * [Http.client] OkHttp instance rather than a separate Ktor client.
 *
 * One instance should be held per [ModuleSource] config so that loaded
 * engines survive across successive search calls.
 */
class ModuleManager {

    private val json = Json {
        isLenient = true
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    /**
     * JS engines, keyed by module id. Delegates to [QuickJsExecutor]'s LRU pool.
     *
     * Concurrent because [ModuleSource.search][com.music.yzmusic.data.sources.ModuleSource.search]
     * loads every module in an index at the same time, and a plain HashMap
     * resizing under two of those at once corrupts quietly rather than loudly.
     */
    private val loadedModules = java.util.concurrent.ConcurrentHashMap<String, LoadedModule>()

    data class LoadedModule(
        val module: SpineModule,
        val jsCode: String,
        val baseUrl: String,
    )

    // ── Index ─────────────────────────────────────────────────────────────

    private class CachedIndex(val modules: List<SpineModule>, val fetchedAtMs: Long)

    /**
     * Parsed indexes, keyed by source URL.
     *
     * Substituting one track asks for the index twice — once to search, once
     * to turn the match into a stream URL — and every track after it asks
     * again. That is two network round trips per play for a document that
     * changes when someone publishes a module, which is to say hardly ever:
     * measured at ~460ms of a ~2.1s substitution, or roughly a fifth of the
     * wait before audio starts, spent re-fetching bytes already in hand.
     *
     * Held behind [indexLock] rather than a plain map because the search and
     * the stream call can overlap across tracks, and two coroutines missing
     * the cache together would each start their own fetch.
     */
    private val indexCache = mutableMapOf<String, CachedIndex>()
    private val indexLock = Mutex()

    /**
     * GETs [sourceUrl], parses every `"category:*"` key, returns all modules.
     *
     * Served from [indexCache] while an earlier answer is still inside
     * [INDEX_TTL_MS]. A failed fetch is never cached — a source that was
     * briefly unreachable should be retried on the next track, not written
     * off for the rest of the window.
     */
    suspend fun fetchIndex(sourceUrl: String): Result<List<SpineModule>> =
        withContext(Dispatchers.IO) {
            indexLock.withLock {
                val cached = indexCache[sourceUrl]
                if (cached != null &&
                    System.currentTimeMillis() - cached.fetchedAtMs < INDEX_TTL_MS
                ) {
                    TrackLog.d(TAG, "▶ fetchIndex($sourceUrl) — CACHE HIT (${cached.modules.size} modules)")
                    return@withContext Result.success(cached.modules)
                }

                TrackLog.d(TAG, "▶ fetchIndex($sourceUrl)")
                runCatching {
                    val request = Request.Builder().url(sourceUrl).build()
                    Http.client.newCall(request).execute().use { resp ->
                        if (!resp.isSuccessful) {
                            throw Exception("HTTP ${resp.code} from $sourceUrl")
                        }
                        val body = resp.body?.string()
                            ?: throw Exception("Empty body from $sourceUrl")
                        val modules = ModuleIndex.parseModules(json, body)
                        TrackLog.d(TAG, "  Parsed ${modules.size} modules")
                        modules
                    }
                }.onSuccess {
                    indexCache[sourceUrl] = CachedIndex(it, System.currentTimeMillis())
                }.onFailure {
                    TrackLog.e(TAG, "  ✗ fetchIndex FAILED for $sourceUrl: ${it.message}", it)
                }
            }
        }

    // ── Load ──────────────────────────────────────────────────────────────

    /**
     * Downloads a module's JS and initialises a QuickJS engine for it.
     *
     * [resolveBaseUrl] turns a relative `module.download` filename into an
     * absolute base — callers pass `{ sourceUrl.substringBeforeLast("/") }`.
     *
     * Results are cached; a second call for the same id returns immediately.
     *
     * The cache is checked against the executor rather than on its own. Engines
     * are LRU-capped over there and this map is not told when one is evicted,
     * so a hit here could name a module whose engine had already been closed —
     * and the caller then went straight to a `callExport` that could only fail
     * with "not loaded". Re-initialising costs a JS evaluation, but not the
     * download: the source is what this map is really holding.
     */
    suspend fun loadModule(
        module: SpineModule,
        resolveBaseUrl: suspend (String) -> String = { it },
    ): Result<LoadedModule> = withContext(Dispatchers.IO) {
        val cached = loadedModules[module.id]
        if (cached != null) {
            if (QuickJsExecutor.isLoaded(module.id)) {
                TrackLog.d(TAG, "▶ loadModule(${module.id}) — CACHE HIT")
                return@withContext Result.success(cached)
            }
            val revived = QuickJsExecutor
                .loadModule(module.id, cached.jsCode, cached.baseUrl)
                .map { cached }
            if (revived.isSuccess) return@withContext revived
            loadedModules.remove(module.id)
        }

        TrackLog.d(TAG, "▶ loadModule(${module.id}) download=${module.download}")
        runCatching {
            val downloadUrl = if (module.download.startsWith("http")) {
                module.download
            } else {
                val base = resolveBaseUrl(module.download)
                "$base/${module.download}"
            }

            TrackLog.d(TAG, "  Resolved download URL: $downloadUrl")
