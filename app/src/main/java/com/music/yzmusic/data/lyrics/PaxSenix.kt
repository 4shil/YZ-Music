package com.music.yzmusic.data.lyrics

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/**
 * Word-timed lyrics via [lyrics.paxsenix.org](https://lyrics.paxsenix.org), a
 * public proxy in front of Apple Music's own catalogue and lyrics — a second,
 * independent route to the same Apple TTML [BetterLyrics] carries, useful
 * exactly when that one's host is the one having a bad day.
 *
 * Apple's own search wants a bearer token, which its web player mints from a
 * token embedded in its own JS bundle — there is no key to request, only that
 * bundle to read, so this scrapes it the same way the player itself does at
 * load time, and keeps it until Apple says no.
 */
object PaxSenix {

    private const val PROXY = "https://lyrics.paxsenix.org"
    private const val APPLE_SEARCH = "https://amp-api.music.apple.com/v1/catalog/us/search"
    private const val DURATION_TOLERANCE_SECONDS = 10

    private val tokenMutex = Mutex()
    private val cachedToken = AtomicReference<String?>(null)

    suspend fun lyrics(
        title: String,
        artist: String,
        durationMs: Long,
        album: String? = null,
    ): List<LyricLine>? = withContext(Dispatchers.IO) {
        val seconds = (durationMs / 1000).toInt()
