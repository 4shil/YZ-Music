package com.music.yzmusic.data.scrobbling

import com.music.yzmusic.data.DebugLog as Log
import com.music.yzmusic.data.model.Song
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.min
import kotlin.math.roundToLong

class ScrobbleManager(
    private val scope: CoroutineScope,
    var minSongDuration: Int = 30,
    var scrobbleDelayPercent: Float = 0.5f,
    var scrobbleDelaySeconds: Int = 180,
) {
    private var scrobbleJob: Job? = null
    private var scrobbleRemainingMillis: Long = 0L
    private var scrobbleTimerStartedAt: Long = 0L
    private var songStartedAt: Long = 0L
    private var songStarted = false
    var useNowPlaying = true

    fun destroy() {
        scrobbleJob?.cancel()
        scrobbleRemainingMillis = 0L
        scrobbleTimerStartedAt = 0L
        songStartedAt = 0L
        songStarted = false
    }

    fun onSongStart(
        song: Song?,
        durationMs: Long? = null,
    ) {
        if (song == null) return
        songStartedAt = System.currentTimeMillis() / 1000
        songStarted = true
        startScrobbleTimer(song, durationMs)
        if (useNowPlaying) {
            updateNowPlaying(song)
        }
    }

    fun onSongResume(song: Song) {
        resumeScrobbleTimer(song)
    }

