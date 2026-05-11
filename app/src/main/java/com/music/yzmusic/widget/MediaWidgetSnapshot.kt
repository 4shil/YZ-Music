package com.music.yzmusic.widget

import android.content.Context
import com.music.yzmusic.playback.LastPlayed

/**
 * Everything a home-screen widget needs to know about playback.
 *
 * Persisted rather than read live, because a widget outlives the app. It is on
 * screen while the process is dead, after a reboot, and in the seconds before a
 * launcher's first update reaches us — none of which a
 * [MediaController][androidx.media3.session.MediaController] can serve, since
 * connecting one means starting [PlaybackService][com.music.yzmusic.playback.PlaybackService]
 * just to find out what to draw. So the service writes here whenever the answer
 * changes ([publishWidgetState][com.music.yzmusic.playback.PlaybackService]) and
 * the widget only ever reads a file.
 */
internal data class MediaWidgetSnapshot(
    val mediaId: String?,
    val title: String,
    val artist: String,
    val artworkUrl: String?,
    /**
     * Whether the transport should show a pause glyph.
     *
     * This tracks `player.playWhenReady`, **not** `player.isPlaying`. The
     * difference matters more here than almost anywhere else in the app: a
     * YouTube track has to be resolved through NewPipe before it can buffer, and
     * that can run for seconds, all of which `isPlaying` spends false. Keyed on
     * it, a widget would answer a tap by leaving the play glyph exactly where it
     * was — the one thing that makes a control feel broken. `playWhenReady`
     * flips the instant the command lands, which is also what the media
     * notification shows.
     */
    val isPlaying: Boolean,
    val hasPrevious: Boolean,
    val hasNext: Boolean,
) {
    /** Whether there is a track to draw at all. */
    val hasTrack: Boolean get() = mediaId != null

    companion object {

