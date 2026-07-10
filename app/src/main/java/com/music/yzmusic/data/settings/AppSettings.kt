package com.music.yzmusic.data.settings

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.music.yzmusic.BuildConfig
import com.music.yzmusic.auth.AuthStore
import com.music.yzmusic.data.lyrics.LyricsSource
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Stream bitrate ceiling. HIGH means "whatever the best available format is".
 *
 * [hourly] is what the ceiling costs in data over an hour of listening, which
 * is the only part of this a user actually cares about on a metered plan.
 */
enum class AudioQuality(
    val maxKbps: Int,
    val label: String,
    val detail: String,
    val hourly: String,
) {
    LOW(64, "Low", "~64 kbps · smallest download", "29 MB/hr"),
    MEDIUM(128, "Medium", "~128 kbps · balanced", "58 MB/hr"),
    HIGH(Int.MAX_VALUE, "High", "Best available · ~171 kbps Opus", "77 MB/hr"),
}

/**
 * What to keep when a track is saved to the device.
 *
 * Deliberately not [AudioQuality]. That enum budgets a *stream*, and is priced
 * per hour because the same bytes are spent again on every replay. A download is
 * the opposite trade — paid for once, kept, played from disk forever after — so
 * the figure that decides it is what one track costs, and the rung worth
 * defaulting to is the top one rather than the cheap one.
 *
 * The rungs themselves differ too. On the YouTube path a download is
 * AAC-in-MP4 or nothing (see
 * [StreamResolver.resolveForDownload][com.music.yzmusic.data.innertube.StreamResolver.resolveForDownload]),
 * so there is no Opus here to describe the way [AudioQuality.HIGH] does. And
 * [LOSSLESS] has no streaming counterpart at all: it is the only rung that lets
 * a configured source's bit-exact file end up as a file on disk.
 */
enum class DownloadQuality(
    /** Ceiling for the AAC ladder. [Int.MAX_VALUE] means "whichever rung is best". */
    val maxKbps: Int,
    val label: String,
    val detail: String,
    /** Roughly what one four-minute track costs at this rung, sans unit context. */
