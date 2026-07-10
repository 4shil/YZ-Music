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
    val perTrack: String,
    /** Whether a source's bit-exact file is worth keeping, or a transcode will do. */
    val keepsLossless: Boolean,
) {
    STANDARD(128, "Standard", "~128 kbps AAC · fits more on the device", "~4 MB", false),
    HIGH(Int.MAX_VALUE, "High", "Best AAC on offer, usually ~256 kbps", "~8 MB", false),
    LOSSLESS(
        Int.MAX_VALUE,
        "Lossless",
        "Bit-exact if a source has it, best AAC if not",
        "~35 MB",
        true,
    ),
}

enum class ThemeMode(val label: String) {
    SYSTEM("System"), LIGHT("Light"), DARK("Dark")
}

/**
 * App settings, backed by SharedPreferences and exposed as flows.
 *
 * PlaybackService runs in the same process as the UI, so it observes these
 * same flows and applies changes to the live ExoPlayer instance immediately —
 * no restart, no rebinding.
 */
object AppSettings {

    private lateinit var prefs: SharedPreferences

    /** Only for the Discord token — everything else on here is plain prefs. */
    private lateinit var authStore: AuthStore

    /**
     * Quality ceilings, one per kind of connection — the point of the split is
     * that Wi-Fi can stay on High while mobile data is capped. Both default to
     * High; the mobile plan is the user's to budget, not ours to assume.
     */
    val audioQualityWifi = MutableStateFlow(AudioQuality.HIGH)
    val audioQualityCellular = MutableStateFlow(AudioQuality.HIGH)

    /**
     * What a saved file should be, answered on its own terms.
     *
     * Kept apart from the two ceilings above on purpose. Those are about what
     * this minute's connection costs, and a download outlives the minute it was
     * started in — capping a permanent file at whichever network happened to be
     * in hand bakes a temporary decision into a lasting artefact, and the
     * reverse (a High ceiling on Wi-Fi implying 35MB FLACs of everything) is
     * just as wrong in the other direction.
     *
     * Data spend on a download is [wifiOnlyDownloads]' problem, not this
     * setting's, which is what lets this one be purely about the file.
     *
     * Defaults to [DownloadQuality.LOSSLESS] because that is what the download
     * path already did on an uncapped connection, and [migrateDownloadQuality]
     * keeps it that way for the people it didn't.
     */
    val downloadQuality = MutableStateFlow(DownloadQuality.LOSSLESS)

    /**
     * Refuse to start a download while the connection charges for data.
     *
     * Metered rather than literally-Wi-Fi, the same test [effectiveAudioQuality]
     * makes, because the thing worth protecting is the bill and not the radio: a
     * tethered hotspot is Wi-Fi that costs money, and an unmetered home
     * connection is worth using whether or not it arrives over Wi-Fi.
     *
     * On by default, and that is a deliberate change of behaviour for anyone
     * updating. [downloadQuality] defaulting to Lossless means a tap that used
     * to spend four megabytes of mobile data can now spend thirty-five, and of
     * the two ways to get that wrong — silently overspending a data plan, or
     * refusing with a sentence naming the switch that would allow it — only the
     * second is recoverable by the person it happens to.
     */
    val wifiOnlyDownloads = MutableStateFlow(true)

    /** Whether the active network charges for data. `null` while offline. */
