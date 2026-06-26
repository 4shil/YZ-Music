package com.music.yzmusic.data.canvas

import com.music.yzmusic.data.Http
import okhttp3.Request
import java.text.Normalizer
import java.util.Locale

/**
 * Which provider a clip came from — read in one place, by
 * [CanvasArtworkPlayer][com.music.yzmusic.ui.player.CanvasArtworkPlayer]'s
 * caller, to decide whether the backdrop should keep re-tinting itself off
 * the clip as it loops rather than settling on its first frame. See that
 * call site for why Spotify's Canvas gets the periodic re-tint and the other
 * three don't.
 */
enum class CanvasSource { SPOTIFY, OTHER }

/**
 * A looping video that stands in for a track's cover art — what Spotify calls
 * a Canvas and Apple calls motion artwork.
 *
 * [url] is what the player mounts; [fallbackUrl] is tried once if that errors,
 * which is how the Apple provider hands over a second rendition of the same
 * clip when the preferred one won't decode. The three metadata fields are not
 * decoration: providers search by free text and will happily return the wrong
 * album's clip, so [matches] re-checks the answer against what's playing.
 */
data class CanvasArtwork(
    val url: String,
