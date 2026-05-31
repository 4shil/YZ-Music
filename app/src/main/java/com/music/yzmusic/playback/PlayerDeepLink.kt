package com.music.yzmusic.playback

import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A request from outside the app to open the full player.
 *
 * Tapping a widget's artwork should land on the now-playing sheet, and there is
 * nothing to hand that intent to: whether the sheet is up is `showNowPlaying`, a
 * `remember`ed boolean inside `YZMusicApp`, which only exists once the
 * composition is running and which
 * [MainActivity][com.music.yzmusic.MainActivity] cannot reach. Because the
 * activity is `singleTask`, the intent also arrives down two different paths
 * depending on whether the app happened to be alive — `onCreate` for a cold
 * launch, `onNewIntent` for every other time — so both relay through here and
 * the composition watches this.
 */
object PlayerDeepLink {

    /** Set on the widget's artwork intent. Nothing else sets it. */
    const val EXTRA_OPEN_PLAYER = "yzmusic.openPlayer"

    private val _pending = MutableStateFlow(false)

    /** Whether a request is outstanding. Cleared by [handled]. */
    val pending: StateFlow<Boolean> = _pending.asStateFlow()

    /** Reads an incoming intent, and reports whether it asked for the player. */
    fun consume(intent: Intent?): Boolean {
        if (intent == null) return false
        val wantsPlayer = intent.getBooleanExtra(EXTRA_OPEN_PLAYER, false) ||
            intent.getBooleanExtra("bitchord.openPlayer", false)
        if (!wantsPlayer) return false
        // Cleared off the intent as well. A singleTask activity keeps the intent
        // that launched it, and getIntent() returns the same one after every
        // configuration change — left in place, a widget tap would reopen the
        // sheet each time the device changed theme or font size.
