package com.music.yzmusic.download

import com.music.yzmusic.data.model.Song
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicLong

/** Where one track in the manager's list has got to. */
sealed interface DownloadProgress {

    data object Queued : DownloadProgress

    /** [fraction] is 0f until the length is known, which is one request in. */
    data class Running(val fraction: Float) : DownloadProgress

    data object Done : DownloadProgress

    data class Failed(val reason: String) : DownloadProgress

    /** Neither waiting nor coming back — nothing left for the user to watch. */
    val settled: Boolean get() = this is Done || this is Failed
}

/**
 * Everything downloaded since the app was opened, and whether the user has
 * looked at it yet.
 *
 * The gap this fills is between [Downloads.active] and [Downloads.saved].
 * `active` is only ever what is happening *right now* — a track leaves it the
 * instant it finishes, which is exactly the instant the user most wants to know
 * about it — and `saved` is a map of every file this app has ever written, with
 * nothing to distinguish the album from twenty minutes ago from one from last
 * March. Neither can answer "did the thing I asked for actually all arrive?",
 * and that is the only question a download manager exists to answer.
 *
 * Deliberately not persisted. This is a record of one sitting: the question it
 * answers is about a batch the user remembers starting, and after a restart
 * there is no such batch — what is on disk is then the whole truth and the
 * Downloads page is where to read it. Being a Kotlin object, the list dies with
 * the process, which is precisely the intended lifetime and needs no code.
 *
 * ### Why [State.visible] is not just "something is downloading"
 *
 * A download is the one thing in this app a user starts and walks away from, so
 * the moment it finishes is a moment nobody is watching. An indicator that
 * disappears the instant the last track lands is therefore an indicator that,
 * for the person who put their phone in their pocket, was never there at all —
 * they come back to a finished job with no trace of it and no way to tell a
 * completed album from one that failed on track nine.
 *
 * So it stays up until it has been *seen*, and "seen" is compared against when
 * the queue last went quiet rather than being a flag that is set once. That
 * comparison is what makes a visit during the download count for what it was:
 * checking in on it, not signing off on it. Look in halfway, close the sheet,
 * and the last track landing afterwards puts the indicator back — because the
 * thing being confirmed is the *outcome*, and the outcome hadn't happened yet.
 */
object DownloadSession {

    data class Item(
        /** The id the *tap* used, which is what everything else here is keyed by. */
        val videoId: String,
        val song: Song,
