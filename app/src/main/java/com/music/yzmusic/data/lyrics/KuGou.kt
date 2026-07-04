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
