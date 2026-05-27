package com.music.yzmusic.playback

import android.content.Context
import android.content.SharedPreferences
import com.music.yzmusic.data.model.Song
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The queue as it stood when the app was last used, so a cold start opens on
 * the track you left off on instead of an empty player.
 *
 * Persisted as its own small record rather than by serialising [Song]: the
 * fields below are all a MediaItem carries, so they are all that survives a
 * round trip through the player anyway, and a stored format is better off not
 * moving every time the domain model does.
 */
object LastPlayed {

    /** A restored queue: the tracks, which one was current, and how far in. */
    class Snapshot(val songs: List<Song>, val index: Int, val positionMs: Long)

    private lateinit var prefs: SharedPreferences
    private val json = Json { ignoreUnknownKeys = true }

