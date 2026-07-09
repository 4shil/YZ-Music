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
