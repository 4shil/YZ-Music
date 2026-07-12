package com.music.yzmusic.data.stats

import android.content.Context
import android.util.Log
import com.music.yzmusic.data.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.Locale

/**
 * What this device has listened to, kept on this device.
 *
 * ## Aggregates, not an event log
 *
 * The obvious shape for listening history is a row per play, summed up when
 * something asks. It is also the shape that grows without bound on a phone
 * nobody is going to garbage-collect: a year of ordinary listening is tens of
 * thousands of rows, and every one of them is carried around so that a page
 * almost nobody opens can add them up once.
 *
 * So the addition happens on the way in. A track's total is a counter that goes
 * up while it plays, and reading the Replay is a merge of a handful of already
 * finished sums rather than a pass over history. The cost of that is what any
 * aggregate costs: the raw plays are gone, so a question nobody thought of in
 * advance cannot be answered later. Every question Replay asks is here.
 *
 * ## One file per month
 *
 * Buckets are calendar months in the device's own time zone, one JSON file
 * each. That is what makes "this year" and "all time" the same operation on
 * different numbers of files, and it means the only bucket held in memory is
 * the one being written to — the rest are read, merged and released.
 *
 * A month is also the granularity below which nothing on the Replay page asks a
 * question. Days are still counted, as a total per day inside the bucket, which
 * is enough for "your biggest day" without a file per day to get there.
 *
 * ## Nothing leaves the device
 *
 * There is no upload, no account and no id. [Backup] can write the whole thing
 * out as JSON because the user asked it to, and that is the only way any of this
 * goes anywhere.
 */
object ListeningStats {

    private lateinit var directory: File

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * The month being written to, held open between samples.
     *
     * Guarded by its own lock rather than made thread-safe piecewise: a sample
     * touches four maps and two arrays and has to leave them agreeing with each
     * other, and the writer is one coroutine ticking every few seconds, so
     * there is nothing here for finer locking to win.
     */
    private var open: OpenBucket? = null

    private val lock = Any()

    /** Set when [open] has changes not yet on disk. */
    private var dirty = false

    /**
     * Where every write to this folder happens.
     *
     * Its own scope rather than the caller's, because the caller is the
     * playback service and its scope is the main thread. [writeLock] serialises
     * them so a flush and a month rollover cannot be halfway into the same file
     * at once.
     */
    private val writer = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeLock = Mutex()

    /**
     * Bumped by every completed write and every import, so [summary] can tell
     * whether the answer it already has is still the answer.
     */
    @Volatile
    private var version = 0L

    /** The last summary handed out, and what it was computed from. */
    private var cached: Cached? = null

    private class Cached(
        val period: ReplayPeriod,
        val version: Long,
        val facts: Int,
        val day: LocalDate,
        val summary: ReplaySummary,
    )

    fun init(context: Context) {
        directory = File(context.filesDir, DIRECTORY)
        // Opening a month means reading and parsing it, and the first thing to
        // ask for one is the playback sampler — on the main thread, a few
        // seconds into the first track. Done here instead, it has happened long
        // before anything is playing.
        writer.launch { synchronized(lock) { bucketFor(YearMonth.now()) } }
    }

    private val ready: Boolean get() = this::directory.isInitialized

    // ── Writing ─────────────────────────────────────────────────────────────

    /**
     * Adds [playedMs] of [song] to the current month.
     *
     * [countsAsPlay] separates the two things a listener means by "played". The
     * minutes are what actually came out of the speaker and are added on every
     * sample; a *play* is a whole listen and is counted once, by whoever is
     * watching the track rather than here — see [ListeningRecorder], which holds
     * the same threshold the scrobbler uses.
     */
    fun record(song: Song, playedMs: Long, countsAsPlay: Boolean) {
        if (!ready) return
        if (playedMs <= 0 && !countsAsPlay) return
        if (song.videoId.isBlank()) return
