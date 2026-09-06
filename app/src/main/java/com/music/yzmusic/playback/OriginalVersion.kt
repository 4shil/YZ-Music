package com.music.yzmusic.playback

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Tracks and persists songs that the listener has reverted to YouTube's original
 * upload by hand (up to 500 pinned IDs across app restarts).
 */
object OriginalVersion {

    private lateinit var prefs: SharedPreferences
    private val _pinned = MutableStateFlow<Set<String>>(emptySet())
    val pinned: StateFlow<Set<String>> = _pinned.asStateFlow()

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _pinned.value = prefs.getString(KEY_PINNED, null)
            ?.split('\n')
            ?.filter { it.isNotBlank() }
            ?.toSet()
            ?: emptySet()
    }

    fun isPinned(videoId: String?): Boolean = videoId != null && videoId in _pinned.value

    fun pin(videoId: String) {
        if (videoId.isBlank() || isPinned(videoId)) return
        var next = _pinned.value + videoId
        while (next.size > MAX_PINNED) next = next - next.first()
        write(next)
    }

    fun unpin(videoId: String) {
        if (!isPinned(videoId)) return
        write(_pinned.value - videoId)
    }

    private fun write(ids: Set<String>) {
        _pinned.value = ids
        if (!::prefs.isInitialized) return
        prefs.edit().putString(KEY_PINNED, ids.joinToString("\n")).apply()
    }

    private const val MAX_PINNED = 500
    private const val PREFS_NAME = "yzmusic_original_versions"
    private const val KEY_PINNED = "pinned"
}
