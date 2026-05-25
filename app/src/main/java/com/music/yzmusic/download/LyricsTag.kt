package com.music.yzmusic.download

import com.music.yzmusic.data.DebugLog as Log
import com.music.yzmusic.data.lyrics.LyricsRepository
import com.music.yzmusic.data.lyrics.toEnhancedLrc
import com.music.yzmusic.data.lyrics.toLrc
import com.music.yzmusic.data.model.Song
import com.music.yzmusic.data.model.durationMillis
import com.music.yzmusic.data.settings.AppSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The lyrics to write into a track [Downloads] is about to save, as LRC text.
 *
 * The same lookup the player does — [LyricsRepository], the same four
 * databases, the same user's pick of which of them may be asked — turned into
 * the one string [MediaTagger] can hand a container. A download is the moment
 * to do it: the lyrics are fetched over the connection that was already good
 * enough to pull the audio down, and they land in the file rather than in a
 * cache, so they survive the app being cleared and travel with the track if it
 * is copied off the device.
 *
 * Gated on [AppSettings.syncedLyrics] and [AppSettings.lyricsSources] rather
 * than on a switch of its own. Those settings are not about the player screen,
 * they are about whether this app may contact third-party lyric services at
 * all and which ones — and a download quietly asking a source the user
 * unticked would be the same request they said no to, made somewhere they
 * weren't looking.
 *
 * Never throws for anything but cancellation. A download that has otherwise
 * succeeded must not be undone by a lyrics server being down, which is the same
 * bargain [MediaTagger] makes for cover art.
 */
internal object LyricsTag {

    private const val TAG = "YZ Music"

    /**
     * An LRC document for [track], or null when there is nothing worth writing.
     *
     * Null covers four different things on purpose, because the caller does the
     * same thing for all of them: the feature is off, the track has no lyrics
     * published, the lookup failed, or the answer was unusable.
     */
    /**
     * The two forms of one track's lyrics.
     *
     * [plain] is what goes in the container's own lyrics field, where every
     * other player looks. [enhanced] is the same lines with their word timings
     * kept, in a field only this app reads — null when the source was
     * line-synced and there was nothing extra to say. See
     * [toEnhancedLrc][com.music.yzmusic.data.lyrics.toEnhancedLrc] for why
     * they are two fields rather than one.
     */
    internal class Embeddable(val plain: String, val enhanced: String?)

    suspend fun forTrack(track: Song): Embeddable? {
        val sources = if (AppSettings.syncedLyrics.value) {
            AppSettings.lyricsSources.value
        } else {
            emptySet()
