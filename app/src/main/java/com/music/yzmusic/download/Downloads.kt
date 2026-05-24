package com.music.yzmusic.download

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import com.music.yzmusic.data.DebugLog as Log
import androidx.core.content.ContextCompat
import com.music.yzmusic.data.YtMusicRepository
import com.music.yzmusic.data.innertube.StreamResolver
import com.music.yzmusic.data.model.Song
import com.music.yzmusic.data.settings.AppSettings
import com.music.yzmusic.data.settings.DownloadQuality
import com.music.yzmusic.data.sources.SourceResolver
import com.music.yzmusic.data.sources.SourceStream
import com.music.yzmusic.data.sources.TrackMatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File
import java.io.OutputStream
import java.util.Locale

/** Where a track is between "not on this device" and "on it". */
sealed interface DownloadState {

    /** Accepted, waiting for the one in front of it. */
    data object Queued : DownloadState

    /** [fraction] is 0f until the length is known, which is the first thing asked for. */
    data class Running(val fraction: Float) : DownloadState

    data class Failed(val reason: String) : DownloadState
}

/**
 * The download queue, and the record of what came out of it.
 *
 * Split deliberately into two pieces of state that look similar and behave
 * nothing alike:
 *
 *  - [active] is what is happening now — queued, running, just failed. It lives
 *    in memory, is driven by [DownloadService], and is empty on a cold start
 *    because a download interrupted by the process dying did not happen.
 *  - [saved] is what exists on disk, keyed by videoId and remembered across
 *    launches. It is the only way the app can answer "do I already have this?"
 *    without a media-store query per row, and the only way it knows *which*
 *    file a track corresponds to when asked to delete it.
 *
 * [saved] is a claim about a folder this app does not own. The user is expected
 * to manage Downloads with a file manager, so an entry here can outlive the
 * file it names — which is why every read of it goes through [savedUri], and
 * why that verifies before it answers.
 */
object Downloads {

    private const val TAG = "YZ Music"
    private const val KEY_SAVED_METADATA = "downloaded_tracks_metadata"
    private const val KEY_SAVED_COLLECTIONS = "downloaded_collections"

    private lateinit var prefs: SharedPreferences
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = MapSerializer(String.serializer(), String.serializer())
    private val metadataSerializer = MapSerializer(String.serializer(), SavedSongMetadata.serializer())
    private val collectionSerializer = MapSerializer(String.serializer(), SavedCollection.serializer())

    private val _active = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    val active: StateFlow<Map<String, DownloadState>> = _active.asStateFlow()

    /**
     * Ids asked for as part of a release's own download tap, by the browseId
     * that was tapped.
     *
     * [active] is one flat map for the whole app — a track queued from one
     * release is still the same row if it happens to sit in another release
     * too, and correctly so. But that means a release page can't tell "one of
     * my tracks is queued" apart from "one of my tracks is queued *because I
     * was asked for*" just by scanning [active] for its own ids: two releases
     * that happen to share a track would both read as downloading the moment
     * either one is. This is what lets a release's header ask the narrower
     * question instead — never pruned explicitly, since a stale id here is
     * harmless once it drops out of [active].
     */
    private val _requested = MutableStateFlow<Map<String, Set<String>>>(emptyMap())
    val requested: StateFlow<Map<String, Set<String>>> = _requested.asStateFlow()

    /** Record that [videoIds] were asked for as [browseId]'s own release. */
    fun markRequested(browseId: String, videoIds: Collection<String>) {
        if (videoIds.isEmpty()) return
        _requested.update { it + (browseId to (it[browseId].orEmpty() + videoIds)) }
    }

    private val _saved = MutableStateFlow<Map<String, String>>(emptyMap())

    /** videoId to the uri of the file saved for it. */
    val saved: StateFlow<Map<String, String>> = _saved.asStateFlow()

    private val _savedMetadata = MutableStateFlow<Map<String, SavedSongMetadata>>(emptyMap())

    private val _collections = MutableStateFlow<Map<String, SavedCollection>>(emptyMap())

    /**
     * The releases that were downloaded *as* releases, by the id they were asked
     * for under.
     *
     * Read by the Downloads page so a batch download reads back as the thing
     * that was tapped. Exposed rather than kept private because the page is a
     * snapshot and has to be retaken when this changes — see
     * [collectionsAmong], which is what turns it into something drawable.
     */
    val collections: StateFlow<Map<String, SavedCollection>> = _collections.asStateFlow()

    /** Waiting, in the order asked for. Guarded by [lock]. */
    private val pending = LinkedHashMap<String, Song>()

    private val lock = Any()

    /**
     * The tracks taken off the queue and not yet finished, to the job fetching
     * each — null in the gap between a worker claiming a track and its job
     * existing.
     *
     * A map rather than the single slot this used to be, because several
     * downloads run at once now. That plurality is the only reason it is here:
     * [cancel] has to find *this* track's job among several, and a worker
     * claiming the next track must not be able to step on another worker's.
     * Guarded by [lock].
     */
    private val running = LinkedHashMap<String, Job?>()

    fun init(context: Context) {
        prefs = context.getSharedPreferences("yzmusic_settings", Context.MODE_PRIVATE)
        _saved.value = runCatching {
            json.decodeFromString(serializer, prefs.getString(KEY_SAVED, null) ?: "{}")
        }.getOrDefault(emptyMap())
        _savedMetadata.value = runCatching {
            json.decodeFromString(metadataSerializer, prefs.getString(KEY_SAVED_METADATA, null) ?: "{}")
        }.getOrDefault(emptyMap())
        _collections.value = runCatching {
            json.decodeFromString(collectionSerializer, prefs.getString(KEY_SAVED_COLLECTIONS, null) ?: "{}")
        }.getOrDefault(emptyMap())
    }

    // ---- Asking -------------------------------------------------------------

    /**
     * Queue [song], and make sure something is draining the queue.
     *
     * A track already saved, queued or running is left alone rather than
     * doubled — the menu row shows which of those it is, but a second tap
     * before the sheet updates should still be a no-op.
     *
     * The Wi-Fi-only check is here rather than only at the tap because this is
     * the one door into the queue, and a setting that can be bypassed by a
     * caller that forgot about it is not a setting. Callers that can say
     * something better than a failed row — a single toast for a whole album, say
     * — check [AppSettings.downloadsAllowedNow] themselves first; this is what
     * catches the rest.
     *
     * @param from what release this track was asked for as part of, when it was
     *   one of many. Carried no further than [DownloadSession], which is the
     *   only thing that has to say *why* forty tracks are in the queue.
     */
    fun enqueue(context: Context, song: Song, from: String? = null) {
        val id = song.videoId
        if (!AppSettings.downloadsAllowedNow) {
            // Distinct from the duplicate-tap no-op below: nothing is in flight
            // here to leave alone, and a refusal nobody is told about reads as a
            // dead button. A download already queued or running started on a
            // connection that allowed it and is none of this check's business.
            val inFlight = _active.value[id]
            if (inFlight !is DownloadState.Queued && inFlight !is DownloadState.Running) {
                DownloadSession.queued(song, from)
                fail(id, WIFI_ONLY_REFUSAL)
            }
            return
        }
        synchronized(lock) {
            if (id in pending || id in running) return
            pending[id] = song
        }
        _active.update { it + (id to DownloadState.Queued) }
        DownloadSession.queued(song, from)

        val app = context.applicationContext
        runCatching {
            ContextCompat.startForegroundService(app, Intent(app, DownloadService::class.java))
        }.onFailure {
            // Refused only when the app has no window and no exemption, which
            // means the queue has nothing to drain it and would sit there
            // looking accepted forever.
            Log.w(TAG, "could not start the download service: ${it.message}")
            synchronized(lock) { pending.remove(id) }
            fail(id, "Downloads can't start right now")
        }
    }

    /**
     * Drop [videoId] from the queue, or stop it if it is one of the ones
     * running.
     *
     * Dropping it from [running] is what makes this safe in the gap between a
     * track being dequeued and its job existing: a cancel landing in that
     * window finds no job to stop, but [onRunning] then finds the id it was
     * told to run is no longer wanted, and stops it on arrival.
     */
    fun cancel(videoId: String) {
        val job = synchronized(lock) {
            pending.remove(videoId)
            if (videoId !in running) return@synchronized null
            running.remove(videoId)
        }
        job?.cancel()
        clear(videoId)
        // A download the user called off is not something they need reminding to
        // check on, so it leaves the manager rather than sitting in it as a
        // permanent "cancelled" row.
        DownloadSession.forget(videoId)
    }

    // ---- The record ---------------------------------------------------------

    /**
     * The file saved for [videoId], or null — pruning the record if the file
     * has been deleted from under it.
     *
     * Touches the filesystem, so call it off the main thread.
     */
    suspend fun savedUri(context: Context, videoId: String): Uri? = withContext(Dispatchers.IO) {
        val recorded = _saved.value[videoId] ?: return@withContext null
        val uri = recorded.toUri()
        if (DownloadStore.exists(context, uri)) return@withContext uri
        Log.d(TAG, "$videoId was downloaded but the file is gone; forgetting it")
        forget(videoId)
        null
    }

    /**
     * True when [uriString] names a `file://` path that is not there.
     *
     * Deliberately answers only for `file://`, and deliberately cheaply: this is
     * called from [Song.toMediaItem], which runs on the main thread once per
     * item for a whole queue. A `stat` is a few microseconds and safe at that
     * rate; the `openFileDescriptor` a `content://` uri would need is a binder
     * round trip, and three hundred of those while building a queue is a frame
     * budget gone. Stale `content://` records are left to
     * [PlaybackService.recoverFrom], which catches every scheme at the moment a
     * read actually fails and costs nothing until then.
     *
     * False for anything unparseable, which keeps "I could not tell" out of the
     * "the file is missing" answer — the caller drops a uri on a true here.
     */
    fun isMissingLocalFile(uriString: String): Boolean {
        if (!uriString.startsWith("file://")) return false
        val path = runCatching { uriString.toUri().path }.getOrNull() ?: return false
        return !File(path).exists()
    }

    /**
     * As [savedUri], but synchronous and without a [Context] parameter — for
     * [Song.toMediaItem], which builds a [MediaItem] on whatever thread that
     * happens to run on and has neither a suspend context nor a [Context] in
     * hand to reach [DownloadStore.exists] with.
     *
     * Without this, a record surviving the file it names — deleted by a file
     * manager, or a folder wiped out from under the app — sent the player a
     * `file://` uri to a path that is simply not there. Nothing downstream
     * checks that either: [AudioCache.playbackFactory] hands `file://` and
     * `content://` uris straight to [androidx.media3.datasource.FileDataSource],
     * which fails with `ERROR_CODE_IO_FILE_NOT_FOUND` — retried a handful of
     * times and then given up on, so the track just refuses to play, with
     * nothing to say why.
     *
     * Prunes the record on the way past, the same as [savedUri]: a claim that
     * has just been shown to be false is not worth keeping to be shown false
     * again on the next play.
     */
    fun verifiedSavedUri(videoId: String): String? {
        val recorded = _saved.value[videoId] ?: return null
        if (!isMissingLocalFile(recorded)) return recorded
        Log.d(TAG, "$videoId was downloaded but the file is gone; forgetting it")
        forget(videoId)
        return null
    }

    /** Delete the file saved for [videoId] and forget it. */
    suspend fun delete(context: Context, videoId: String): Boolean = withContext(Dispatchers.IO) {
