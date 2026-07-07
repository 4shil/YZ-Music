package com.music.yzmusic.data.lyrics

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs

/**
 * Line-synced lyrics from Musixmatch's own web client API.
 *
 * There is no public key for this: the web player signs every request with an
 * HMAC over the URL and the day's date, using a secret that has been baked
 * into that same web player's JavaScript — and, by extension, into every
 * independent Musixmatch client that has reimplemented the scheme from
 * reading it, which is where this one comes from too. A session token from
 * `token.get` rides alongside it and is cached until the service itself
 * rejects it.
 */
object Musixmatch {

    private const val BASE = "https://apic.musixmatch.com/ws/1.1"

    // The signing secret Musixmatch's web client bakes into its own bundle —
    // see the file note above for where this comes from.
    private const val SIGNING_SECRET = "RJDefUswhwjkZDeM"

    private val tokenMutex = Mutex()
    private val cachedToken = AtomicReference<String?>(null)

    suspend fun lyrics(
        title: String,
        artist: String,
        durationMs: Long,
    ): List<LyricLine>? = withContext(Dispatchers.IO) {
        val seconds = (durationMs / 1000).toInt()
