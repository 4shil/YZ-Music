package com.music.yzmusic.data.lyrics

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.util.Base64
import kotlin.math.abs
import kotlin.math.min

/**
 * Line-synced lyrics from KuGou's public mobile/lyrics endpoints — a Chinese
 * catalogue, but one that also carries a great many English and Hindi tracks
 * that the other four sources simply don't have.
 *
 * Three unauthenticated calls, chained: search the song to get its audio
 * fingerprint (`hash`), search lyrics candidates against that hash, then
 * download the winning candidate. A search by keyword alone (skipping the
 * hash) is kept as the fallback that catches everything the first two miss.
 */
object KuGou {

    private const val DURATION_TOLERANCE_SECONDS = 8

    suspend fun lyrics(
        title: String,
        artist: String,
        durationMs: Long,
        album: String? = null,
    ): List<LyricLine>? = withContext(Dispatchers.IO) {
        val keyword = keyword(title, artist, album)
        val seconds = (durationMs / 1000).toInt()

        val candidate = searchSongs(keyword, seconds)?.firstNotNullOfOrNull { hash ->
            searchLyrics(hash = hash)?.firstOrNull()
        } ?: searchLyrics(keyword = keyword, seconds = seconds)?.firstOrNull()
            ?: return@withContext null

        val lrc = download(candidate.id, candidate.accesskey) ?: return@withContext null
        LrcLib.parseLrc(lrc).takeIf { it.isNotEmpty() }
    }

    /**
     * Song hashes worth trying, restricted to cuts within
     * [DURATION_TOLERANCE_SECONDS] of the track being played — otherwise the
     * first result for a common title is as likely to be a cover or a remix
     * as the right recording — and ordered closest match first.
     */
    private fun searchSongs(keyword: Keyword, seconds: Int): List<String>? {
        val url = "https://mobileservice.kugou.com/api/v3/search/song".toHttpUrl().newBuilder()
            .addQueryParameter("version", "9108")
            .addQueryParameter("plat", "0")
            .addQueryParameter("pagesize", "8")
            .addQueryParameter("showtype", "0")
            .addQueryParameter("keyword", keyword.query)
            .build()
        val body = lyricsGet(url.toString()) ?: return null
        val response = runCatching { lyricsJson.decodeFromString<SearchSongResponse>(body) }.getOrNull()
        return response?.data?.info.orEmpty()
            .filter { seconds <= 0 || abs(it.duration - seconds) <= DURATION_TOLERANCE_SECONDS }
            .sortedBy { abs(it.duration - seconds) }
            .map { it.hash }
    }

    private fun searchLyrics(hash: String? = null, keyword: Keyword? = null, seconds: Int = -1): List<Candidate>? {
        val builder = "https://lyrics.kugou.com/search".toHttpUrl().newBuilder()
            .addQueryParameter("ver", "1")
            .addQueryParameter("man", "yes")
            .addQueryParameter("client", "pc")
        when {
            hash != null -> builder.addQueryParameter("hash", hash)
            keyword != null -> {
                builder.addQueryParameter("keyword", keyword.query)
                if (seconds > 0) builder.addQueryParameter("duration", (seconds * 1000).toString())
            }
            else -> return null
        }
        val body = lyricsGet(builder.build().toString()) ?: return null
        val response = runCatching { lyricsJson.decodeFromString<SearchLyricsResponse>(body) }.getOrNull()
        return response?.candidates
    }

    private fun download(id: String, accessKey: String): String? {
        val url = "https://lyrics.kugou.com/download".toHttpUrl().newBuilder()
            .addQueryParameter("fmt", "lrc")
            .addQueryParameter("charset", "utf8")
            .addQueryParameter("client", "pc")
            .addQueryParameter("ver", "1")
            .addQueryParameter("id", id)
            .addQueryParameter("accesskey", accessKey)
            .build()
