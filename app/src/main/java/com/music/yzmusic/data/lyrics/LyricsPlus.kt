package com.music.yzmusic.data.lyrics

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.util.concurrent.atomic.AtomicReference

/**
 * Syllable-timed lyrics from LyricsPlus, the open backend behind the YouLy+
 * extension. It aggregates Apple Music, QQ Music and Musixmatch, and its v2
 * response is the finest-grained of the providers here — Apple's own syllable
 * splits, not just word boundaries.
 *
 * The catch is hosting: it runs on volunteer mirrors, and at any given moment
 * most of them are rate-limited, out of Vercel credit or simply gone. The
 * extension's answer, copied here, is to ask all of them at once and take the
 * first real answer. The winner is remembered so the next track goes straight
 * to a host that was up a minute ago instead of paying for the race again.
 */
object LyricsPlus {

    private val MIRRORS = listOf(
        "https://lyricsplus.prjktla.my.id",
        "https://lyricsplus.atomix.one",
        "https://lyricsplus.binimum.org",
        "https://lyricsplus.prjktla.workers.dev",
        "https://lyricsplus-seven.vercel.app",
        "https://lyrics-plus-backend.vercel.app",
    )

    private val lastGood = AtomicReference<String?>(null)

    suspend fun lyrics(
        title: String,
        artist: String,
        durationMs: Long,
        album: String? = null,
    ): List<LyricLine>? = coroutineScope {
        val hosts = lastGood.get()
            ?.let { listOf(it) + MIRRORS.filterNot { mirror -> mirror == it } }
            ?: MIRRORS

        val pending = hosts.map { host ->
            host to async(Dispatchers.IO) { fetch(host, title, artist, durationMs, album) }
        }.toMutableList()

        // Take the first mirror to answer with something usable rather than
        // the first to answer at all — a mirror that 404s this track shouldn't
        // beat one that has it.
        try {
            while (pending.isNotEmpty()) {
                val (host, lines) = select {
                    pending.forEach { (host, job) -> job.onAwait { host to it } }
                }
                pending.removeAll { it.first == host }
                if (!lines.isNullOrEmpty()) {
                    lastGood.set(host)
                    return@coroutineScope lines
                }
            }
            null
        } finally {
            pending.forEach { it.second.cancel() }
        }
    }

    private suspend fun fetch(
        host: String,
        title: String,
        artist: String,
        durationMs: Long,
        album: String?,
    ): List<LyricLine>? = withContext(Dispatchers.IO) {
