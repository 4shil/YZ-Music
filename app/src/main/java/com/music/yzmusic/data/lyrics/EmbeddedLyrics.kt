package com.music.yzmusic.data.lyrics

import android.content.Context
import android.net.Uri
import com.music.yzmusic.data.DebugLog as Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream

/**
 * The lyrics already sitting inside a downloaded file.
 *
 * The read side of what the download path wrote — see `MediaTagger`, and the
 * three taggers under it. A track that was downloaded had its lyrics fetched
 * once, at download time, and written into the file; asking four servers for
 * them again every time it is played is a network round trip to arrive at a
 * string that is already on disk, and it is the reason a downloaded song showed
 * nothing at all with the connection off.
 *
 * Two fields are read, in this order:
 *
 *  - `YZMUSIC_LYRICS`, this app's own, holding the "enhanced" A2 form with the
 *    word timings intact — see [toEnhancedLrc].
 *  - the container's standard lyrics field, holding plain `[mm:ss.xx]` LRC.
 *
 * The second is what every other player reads and what older downloads have,
 * so it is the fallback rather than the exception. Preferring the first is what
 * keeps a downloaded song lighting up word by word instead of a line at a time.
 *
 * Never throws. A file that isn't one of the three containers, or is one and
 * has no lyrics in it, is a null — the caller falls back to the network, which
 * is exactly what it did before this existed.
 */
object EmbeddedLyrics {

    private const val TAG = "YZ Music"

    /**
     * Most bytes worth pulling to find a tag.
     *
     * A cap rather than a size: this reads whatever region of the file holds
     * the metadata, and that region is small in all three containers — but its
     * length is stated *by the file*, so a corrupt or hostile one could claim
     * any number at all. `LyricsTag` caps what it writes at 64k, so anything
     * past this is not a tag this app produced.
     */
    private const val MAX_TAG_BYTES = 8 * 1024 * 1024

    /**
     * The lyrics inside [uriString], or null when it has none worth showing.
     *
     * Touches the filesystem, so it runs on [Dispatchers.IO] regardless of
     * where it is called from.
     */
    suspend fun forUri(context: Context, uriString: String): List<LyricLine>? =
        withContext(Dispatchers.IO) {
            val raw = runCatching { read(context, Uri.parse(uriString)) }
                .onFailure { Log.d(TAG, "no embedded lyrics in $uriString: ${it.message}") }
                .getOrNull()
                ?: return@withContext null
            // The same last pass the network sources get, so a downloaded track
            // and a streamed one draw their backing vocals the same way.
            LrcLib.parseLrc(raw).takeIf { lines -> lines.any { it.text.isNotBlank() } }
                ?.withBackgroundVocals()
        }

    /** The raw LRC text in the file, preferring this app's word-timed field. */
    private fun read(context: Context, uri: Uri): String? =
        open(context, uri)?.use { fromBytes(it.readAtMost(MAX_TAG_BYTES)) }

    /**
     * The raw LRC text in [head], whichever of the three containers it is.
     *
     * Split from [read] so the parsing can be tested against bytes a tagger
     * just produced, without a device or a `Context` in the way — the round
     * trip is the only thing that proves a reader and a writer agree.
     */
    internal fun fromBytes(head: ByteArray): String? {
