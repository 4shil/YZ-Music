package com.music.yzmusic.data.sources.module

import android.util.Log
import com.music.yzmusic.data.TrackLog
import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.AsyncFunctionBinding
import com.dokar.quickjs.binding.FunctionBinding
import com.dokar.quickjs.binding.define
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.Locale

/**
 * Sandboxed QuickJS engine pool for executing module JS.
 *
 * Modules commonly stash session/auth state in top-level variables set up
 * once at load time (e.g. an eager token pre-fetch). The old one-shot
 * executor re-evaluated the whole script on every function call, so that
 * state never survived from `searchTracks()` to `getStreamUrl()` — each
 * ran in its own throwaway VM. Keeping one engine alive per loaded module,
 * reused across calls, is what a normal module host does and what these
 * modules assume. LRU-capped at [MAX_MODULES]; least-recently-used is
 * evicted to make room rather than refusing to load past the cap.
 *
 * ### Why a module gets several engines and not one
 *
 * A QuickJS VM is a single-threaded interpreter and [callExport] drives it
 * across two `evaluate` calls with the answer parked in a global in between.
 * Both of those make one engine strictly one call at a time: two callers
 * sharing it would interleave their `evaluate`s inside the interpreter, and
 * even if they didn't, the second would overwrite `__spine_resolved_json`
 * before the first had read it — so one track's download would quietly receive
 * another track's stream URL. That was survivable while downloads drained one
 * at a time and playback asked for one track at a time; it is not survivable
 * with several downloads in flight.
 *
 * Serialising on a single engine would be correct and would also give all the
 * concurrency back, since a module's own HTTP fetches happen *inside* the VM
 * and hold it for their whole duration — measured at 7.7s for one module on
 * one query. So each module gets a small pool instead: [ENGINES_PER_MODULE]
 * independent VMs, each handed to one caller at a time, growing lazily so a
 * module nobody is hammering still costs exactly one.
 *
 * Ported from Convx's `QuickJsExecutor`, with the pool added.
 */
internal object QuickJsExecutor {

    private const val TAG = "YZ Music"

    /**
     * How many modules stay resident.
     *
     * Has to cover a whole index, not a working set. A search fans out to
     * *every* module at once, so a cap below the index size means each search
     * evicts engines the same search is still using — and the eviction is
     * invisible to [ModuleManager], which caches the load separately and goes
     * on reporting a hit for an engine that is gone. What that produced was
     * `Module … is not loaded` on the stream call for whichever modules lost
     * the race, one wasted attempt per track, on every track. Four was the
     * ported default and is below the size of a real index.
     */
    private const val MAX_MODULES = 12

    /**
     * How many callers one module can serve at once.
     *
     * Each is a whole interpreter with the module's script evaluated into it,
     * so this is not free — and each costs whatever the module does at load
     * time, which for several of them is an eager token fetch. Three is sized
     * against the download queue's own width: enough that the workers are not
     * queueing behind each other on the one slow module in the index, few
     * enough that a three-module index is nine VMs and not thirty.
     */
    private const val ENGINES_PER_MODULE = 3

    /**
     * One module's interpreters, and the source to make more from.
     *
     * [free] is the ones nobody is inside right now. It is unbounded and only
     * ever holds engines this pool made, so a send to it cannot fail or block;
     * a receive is a caller waiting its turn.
     */
    private class Pool(val jsCode: String, val fetchBase: String) {
        val free = Channel<QuickJs>(Channel.UNLIMITED)
        val lock = Mutex()
        /** Engines made or being made — the claim that stops two callers both growing the pool. */
        var started = 0
        /** Engines actually made, for closing them on unload. Guarded by [lock]. */
        val made = mutableListOf<QuickJs>()
    }

    /** Whether [moduleId] still has a live pool, i.e. hasn't been evicted. */
    fun isLoaded(moduleId: String): Boolean =
        synchronized(engineLock) { pools.containsKey(moduleId) }

    private val syncHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    private val engineLock = Any()

    /** LRU map: access-ordered so the oldest-used entry is first. */
    private val pools = LinkedHashMap<String, Pool>(16, 0.75f, true)

    // ── Load ─────────────────────────────────────────────────────────────

    suspend fun loadModule(moduleId: String, jsCode: String, fetchBase: String = ""): Result<Unit> {
        val evicted = synchronized(engineLock) {
            if (pools.containsKey(moduleId)) {
                TrackLog.d(TAG, "QuickJsExecutor.loadModule($moduleId) — ENGINE CACHE HIT")
                return Result.success(Unit)
            }
            buildList {
                while (pools.size >= MAX_MODULES) {
                    val lruId = pools.keys.firstOrNull() ?: break
                    TrackLog.d(TAG, "  Evicting LRU module engine: $lruId")
                    pools.remove(lruId)?.let { addAll(it.made) }
                }
            }
        }
        evicted.forEach { runCatching { it.close() } }

        TrackLog.d(TAG, "QuickJsExecutor.loadModule($moduleId) fetchBase=$fetchBase jsCodeLength=${jsCode.length}")
        return withContext(Dispatchers.Default) {
            runCatching { newEngine(jsCode, fetchBase) }
                .onSuccess { qjs ->
                    // The pool starts at one and grows only if two callers ever
                    // want this module at the same moment. A module that is
                    // never contended never pays for a second interpreter.
                    val pool = Pool(jsCode, fetchBase)
                    pool.started = 1
                    pool.made += qjs
                    pool.free.trySend(qjs)
                    synchronized(engineLock) { pools[moduleId] = pool }
                }
                .onFailure { TrackLog.e(TAG, "  ✗ loadModule FAILED for $moduleId: ${it.message}", it) }
                .map { }
        }
    }

    /** A fresh interpreter with [jsCode] evaluated into it, or a throw saying why not. */
    private suspend fun newEngine(jsCode: String, fetchBase: String): QuickJs {
        val qjs = QuickJs.create(Dispatchers.Default)
        qjs.maxStackSize = 512 * 1024L
        try {
            bindConsole(qjs)
            bindAsyncFetch(qjs, fetchBase)

            qjs.evaluate<String>(POLYFILLS)
            val cleanCode = preprocessModuleCode(jsCode)

            qjs.evaluate<String>(
                """
                var __spine_iife_error = null;
                var __spine_mod = (function() {
                    try {
                        var module = { exports: {} };
                        var exports = module.exports;
                        var self = {};
                        $cleanCode
                        if (module.exports && (module.exports.searchTracks || module.exports.getTrackStreamUrl)) {
                            return module.exports;
                        }
                        return {};
                    } catch(e) {
                        __spine_iife_error = e && e.message ? e.message : String(e);
                        return {};
                    }
                })();
                'ok'
                """.trimIndent()
            )

            val iifeError = qjs.evaluate<String>("__spine_iife_error || 'none'")
            if (iifeError != "none") throw IllegalStateException("Module init error: $iifeError")

            val keys = qjs.evaluate<String>("Object.keys(__spine_mod).join(', ')")
            TrackLog.d(TAG, "  Module exports: [$keys]")
            return qjs
        } catch (e: Throwable) {
            qjs.close()
            throw e
        }
    }

    // ── Pool ─────────────────────────────────────────────────────────────

    /**
     * An interpreter of [moduleId]'s, exclusively, until it is given back.
     *
     * Takes a free one if there is one, makes another if the pool has room, and
     * otherwise waits for whoever is using one to finish. The claim on a new
     * engine is taken *before* it is built and released if building throws, so
     * a module whose script fails to evaluate cannot permanently consume a slot
     * in the pool it never joined.
     */
    private suspend fun acquire(moduleId: String, pool: Pool): QuickJs {
        pool.free.tryReceive().getOrNull()?.let { return it }

        val mine = pool.lock.withLock {
            if (pool.started >= ENGINES_PER_MODULE) return@withLock false
            pool.started++
            true
        }
        if (!mine) return pool.free.receive()

        return try {
            newEngine(pool.jsCode, pool.fetchBase).also { fresh ->
                pool.lock.withLock { pool.made += fresh }
            }
        } catch (e: Throwable) {
            pool.lock.withLock { pool.started-- }
            throw e
        }
    }

    // ── Call ─────────────────────────────────────────────────────────────

    suspend fun callExport(moduleId: String, functionName: String, args: List<String>): Result<String> {
        val pool = synchronized(engineLock) { pools[moduleId] }
            ?: return Result.failure(IllegalStateException("Module $moduleId is not loaded"))

        TrackLog.d(TAG, "QuickJsExecutor.callExport($moduleId, $functionName) args=$args")
        return withContext(Dispatchers.Default) {
            val qjs = try {
                acquire(moduleId, pool)
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                return@withContext Result.failure(e)
            }
            try {
                callOn(qjs, moduleId, functionName, args)
            } finally {
                // Back into circulation on every path, including a throw: an
                // engine that is never returned is a slot the pool can never
                // hand out again, and three of those would deadlock the module.
                pool.free.trySend(qjs)
            }
        }
    }

    private suspend fun callOn(
        qjs: QuickJs,
        moduleId: String,
        functionName: String,
        args: List<String>,
    ): Result<String> =
        run {
            runCatching {
                val hasFn = qjs.evaluate<String>("typeof __spine_mod['$functionName']")
                if (hasFn != "function") {
                    throw IllegalStateException("$functionName is not a function on module $moduleId")
                }

                val argsStr = args.joinToString(",")

                // evaluate() converts a Promise via toString() instead of awaiting it.
                // Workaround: store the resolved JSON in a global var inside the async
                // IIFE, then read it in a second evaluate() call.
                qjs.evaluate<String>(
                    """
                    var __spine_resolved_json = undefined;
                    (async function() {
                        var __fn = __spine_mod['$functionName'];
                        if (!__fn) {
                            __spine_resolved_json = JSON.stringify({ error: '$functionName not found' });
                            return;
                        }
                        try {
                            var r = await __fn($argsStr);
                            __spine_resolved_json = typeof r === 'string' ? r : JSON.stringify(r);
                        } catch(e) {
                            __spine_resolved_json = JSON.stringify({ error: e && e.message ? e.message : String(e) });
                        }
                    })();
                    """.trimIndent()
                )

                val rawResult = qjs.evaluate<String>("__spine_resolved_json")
                TrackLog.d(TAG, "  callExport result (${rawResult.length} chars): ${rawResult.take(500)}")
                rawResult
            }.onFailure {
                TrackLog.e(TAG, "  ✗ callExport FAILED for $moduleId.$functionName: ${it.message}", it)
            }
        }

    // ── Unload ───────────────────────────────────────────────────────────

    fun unload(moduleId: String) {
        val closing = synchronized(engineLock) { pools.remove(moduleId) }?.made.orEmpty()
        closing.forEach { runCatching { it.close() } }
        TrackLog.d(TAG, "QuickJsExecutor.unload($moduleId)")
    }

    fun unloadAll() {
