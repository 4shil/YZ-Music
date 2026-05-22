package com.music.yzmusic.download

import com.music.yzmusic.data.DebugLog as Log
import com.music.yzmusic.data.Http
import com.music.yzmusic.data.innertube.PlayerClient
import com.music.yzmusic.data.innertube.StreamResolver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import java.io.OutputStream
import kotlin.coroutines.coroutineContext

/**
 * Pulls a resolved stream onto disk.
 *
 * Two things here are not obvious and both are load-bearing:
 *
 *  - **Bounded ranges, not one long GET.** googlevideo paces a continuous
 *    response down to roughly playback speed — about 15kB/s, against 5.7MB/s
 *    for the same bytes asked for as ranges. That is the difference between a
 *    four-minute track saving in a second and saving in four minutes, and it is
 *    the same finding [ChunkedDataSource][com.music.yzmusic.playback.ChunkedDataSource]
 *    exists for.
 *  - **The client's own headers.** googlevideo bakes the identity that minted a
 *    URL into it as `c=`/`cver=` and compares that against the headers of the
 *    request that comes back for the bytes. Fetching with anything else is a
 *    403 — so the fetch is dressed as whatever the URL says made it, which
 *    [PlayerClient.forStreamUrl] can recover from the URL alone.
 *
 * Sequential rather than parallel. Ranges are served at line rate, and a
 * typical AAC track is four megabytes: splitting that across connections buys
 * nothing a user could perceive and costs the temp files and reassembly that a
 * cancelled download would then have to clean up.
 *
 * None of the above is true of a stream from a configured source rather than
 * from YouTube, which is what [fetchDirect] is for.
 */
object Downloader {

    private const val TAG = "YZ Music"

    /** Matches the range size read-ahead settled on; large enough to amortise, small enough to cancel promptly. */
    private const val CHUNK_BYTES = 2L * 1024 * 1024

    private const val BUFFER_BYTES = 64 * 1024

    /**
     * Fetch all of [stream] into [sink].
     *
     * @param maxKbps the ceiling [stream] was resolved under, needed again for
     *   the re-resolve below. Resolving at a different one would pick a
     *   different rung of the AAC ladder, and the length check that guards the
     *   resume would then fail a retry that had nothing wrong with it.
     * @param onProgress called as bytes land, with the running total and the
     *   full size. Never called with a total of zero.
     * @return how many bytes were written.
     */
    suspend fun fetch(
        videoId: String,
        stream: StreamResolver.Stream,
        maxKbps: Int,
        sink: OutputStream,
        onProgress: (written: Long, total: Long) -> Unit,
    ): Long = withContext(Dispatchers.IO) {
        var url = stream.url
        val total = contentLength(url) ?: error("Track unavailable: no length to fetch")

        var position = 0L
