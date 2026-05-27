package com.music.yzmusic.playback

import android.net.Uri
import android.util.Log
import com.music.yzmusic.data.TrackLog
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import com.music.yzmusic.data.innertube.StreamResolver
import java.io.InterruptedIOException

/**
 * Fetches a stream as a run of bounded ranges instead of one open-ended read.
 *
 * googlevideo paces a continuous response down to roughly playback speed:
 * ask for a whole track in one request and it arrives at about 15kB/s, barely
 * ahead of the rate it is being consumed at. Ask for the same bytes as bounded
 * ranges and each one is served at line rate — measured on the same track,
 * over the same connection, seconds apart:
 *
 * ```
 *   GET (unbounded)               15.5 kB/s
 *   Range: bytes=0-2097151         5.7 MB/s
 * ```
 *
 * That is the difference between a player that can build a buffer and one that
 * can only just keep up, and it is why a stall never recovers into a cushion:
 * there is no spare bandwidth to catch up with.
 *
 * [AudioCache] already fetches this way — it is why read-ahead can put a whole
 * track on disk in the time between songs. This puts the player's own reads on
 * the same footing, which matters for the track being listened to right now:
 * read-ahead deliberately never touches it (Media3 locks a cache entry to one
 * writer), so without this it is the one track that always streams throttled.
 *
 * Transparent to everything above it. [CacheDataSource][androidx.media3.datasource.cache.CacheDataSource]
 * sees one continuous stream of the length it asked for, and a request that is
 * already bounded — every read-ahead fetch — is passed straight through
 * untouched rather than chunked twice.
 */
@UnstableApi
class ChunkedDataSource(
    private val upstream: DataSource,
    private val chunkBytes: Long,
) : DataSource {

    private var baseSpec: DataSpec? = null
    private var position = 0L
    private var bytesRemaining = 0L
    private var chunkRemaining = 0L
    private var chunkOpen = false

    /** Set when the request can't be improved on, and is simply forwarded. */
    private var passthrough = false

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    /**
     * The total size has to be known up front, or there is no way to say where
     * the last range ends. Every progressive googlevideo URL carries it as
     * `clen`, which costs nothing to read; anything else is forwarded as-is.
     */
    override fun open(dataSpec: DataSpec): Long {
        baseSpec = dataSpec
        position = dataSpec.position

        val total = dataSpec.uri.getQueryParameter("clen")?.toLongOrNull()
        if (dataSpec.length != C.LENGTH_UNSET.toLong() || total == null) {
            passthrough = true
            chunkOpen = true
            // Reported, not just thrown. Nothing but googlevideo carries `clen`,
            // so every module stream comes through this branch — and this was
            // the one open in the app whose refusal was neither logged nor
            // handed anywhere. A dead Tidal URL surfaced as a bare
            // ExoPlaybackException with not one line naming the server that
            // produced it or the status it produced.
            try {
                return upstream.open(dataSpec)
            } catch (e: Exception) {
                if (e !is InterruptedIOException) {
                    // `host` is null exactly when the URL was too broken to
                    // parse, which is the case most in need of naming — so fall
                    // back to the string itself rather than logging "null".
                    val who = dataSpec.uri.host ?: dataSpec.uri.toString().take(120)
                    TrackLog.w(TAG, "$who refused the stream: ${e.message}")
                    report(dataSpec, e)
                }
                throw e
            }
        }

        passthrough = false
        bytesRemaining = (total - position).coerceAtLeast(0L)
        if (bytesRemaining > 0) openChunk()
        return bytesRemaining
    }

