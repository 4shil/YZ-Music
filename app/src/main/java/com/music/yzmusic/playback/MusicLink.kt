package com.music.yzmusic.playback

import android.app.SearchManager
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What an intent from outside the app turned out to be asking for. */
sealed interface LinkRequest {
    /** A song, by video id — `watch?v=`, a `youtu.be` short link, a Short. */
    data class Track(val videoId: String) : LinkRequest

    /** An album, playlist or artist page, by browse id. */
    data class Page(val browseId: String) : LinkRequest

    /**
     * Words rather than an id: "play Blinding Lights", or a shared search URL.
     *
     * [play] separates the two. A spoken request is an instruction — it should
     * start the best match, not leave a list on screen for someone whose phone
     * is in their pocket — while a search *link* is a page somebody meant to
     * show you.
     */
    data class Search(val query: String, val play: Boolean) : LinkRequest

    /** "Play music", with nothing said about what. */
    data object Resume : LinkRequest
}

/**
 * Links and voice requests handed to YZ Music from elsewhere on the device.
 *
 * The same relay [PlayerDeepLink] is, and for the same reason: what has to
 * happen — start a queue, push a page, run a search — is all inside
 * `YZMusicApp`'s composition, which [MainActivity][com.music.yzmusic.MainActivity]
 * has no handle on. The activity reads the intent, this holds the answer, and
 * the composition serves it once its controller and view model exist.
 *
 * Kept apart from [PlayerDeepLink] rather than folded into it because the two
 * are answered by different things at different times: opening the player is a
 * boolean the sheet reads, while this is a request that may take a network
 * round trip before anything happens on screen.
 */
object MusicLink {

    /**
     * Marks an intent as already read.
     *
     * MainActivity is `singleTask` and declares no `configChanges`, so a theme
     * or font-size change destroys and recreates it — with `getIntent()` still
     * returning the link that launched it. Without this, rotating the phone an
     * hour later would replay the shared song over whatever is playing.
     */
    private const val EXTRA_CONSUMED = "bitchord.linkConsumed"

    private val _pending = MutableStateFlow<LinkRequest?>(null)

    /** The outstanding request, or null. Cleared by [handled]. */
