package com.music.yzmusic.data

import android.os.Build
import android.util.Log
import com.music.yzmusic.BuildConfig
import com.music.yzmusic.data.model.Song
import com.music.yzmusic.data.sources.SourceResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.CoroutineContext

/**
 * The app's own record of how each track came to be playing, as text you can
 * paste somewhere.
 *
 * Diagnosing why a track played from the wrong source, at the wrong bitrate,
 * or not at all has meant plugging the phone in and reading `adb logcat` — and
 * the answer is usually in a stretch lasting a few seconds that has already
 * scrolled past by the time anyone notices something sounded wrong. This keeps
 * that stretch.
 *
 * ### Why not read logcat
 *
 * The obvious implementation shells out to `logcat`, and it works. But from
 * Android 13 an app that does so trips a system consent dialog — *"Allow
 * YZ Music to access all device logs?"* — which appears whenever the process
 * happens to spawn, asks for far more than this needs, and puts every other
 * app's output within reach of a paste made from a music player. None of that
 * is a reasonable price for a debug button.
 *
 * So the lines are kept here on the way past instead. Nothing is read back
 * from the system, no permission is involved, no dialog can appear, and what
 * ends up on the clipboard is only ever what this app itself wrote.
 *
 * ### What gets kept
 *
 * Only the paths that decide how a track plays: the resolver, the module
 * sandbox, the source ladder, the cache and the player. Deliberately not the
 * feeds, the artwork, the lyrics or the library — a paste that includes
 * everything is one nobody reads to the end of, and none of it has ever been
 * the answer to "why did this song sound wrong".
 *
 * ### Which lines are whose
 *
 * Every line is filed against the track it is about, and reading the log back
 * is a question about a track rather than about a stretch of time — see
 * [about] and [forTrack]. This app does most of a track's work nowhere near
 * the moment that track is playing: it is resolved while the one before it
 * plays, and the first seconds of every track are spent resolving the *next*
 * one. So "the last thirty seconds of log" is never the same thing as "this
 * song's story", and asking for one by way of the other pastes the wrong
 * song's log almost every time.
 *
 * Call [d], [w] and [e] exactly where `Log.d`/`w`/`e` would go; they forward
 * to logcat as well, so `adb logcat -s YZ Music` is unchanged.
 */
object TrackLog {

    // ── Writing ─────────────────────────────────────────────────────────────

    // logcat is only worth writing to in a debug build — nothing in prod ever
    // reads it (see the class doc), so a release build skips straight to
    // record(), which is what Copy Log actually depends on.

    fun d(tag: String, message: String, about: String? = working.get()) {
        if (BuildConfig.DEBUG) Log.d(tag, message)
        record('D', message, about)
    }

    fun i(tag: String, message: String, about: String? = working.get()) {
        if (BuildConfig.DEBUG) Log.i(tag, message)
        record('I', message, about)
    }

    fun w(tag: String, message: String, about: String? = working.get()) {
        if (BuildConfig.DEBUG) Log.w(tag, message)
        record('W', message, about)
    }

    fun w(tag: String, message: String, error: Throwable, about: String? = working.get()) {
        if (BuildConfig.DEBUG) Log.w(tag, message, error)
        record('W', "$message\n${error.stackTraceToString()}", about)
    }

    fun e(tag: String, message: String, about: String? = working.get()) {
        if (BuildConfig.DEBUG) Log.e(tag, message)
        record('E', message, about)
    }

    fun e(tag: String, message: String, error: Throwable, about: String? = working.get()) {
        if (BuildConfig.DEBUG) Log.e(tag, message, error)
        record('E', "$message\n${error.stackTraceToString()}", about)
    }

    // ── Whose line is it ────────────────────────────────────────────────────

    /** The track whose work this thread is doing, if it is doing any. */
    private val working = ThreadLocal<String?>()

    /**
     * A coroutine context that files everything logged inside it against [id].
     *
     *     scope.async(Dispatchers.IO + TrackLog.about(videoId)) { … }
     *
     * The alternative is passing an id down to every call that logs, and the
     * lines worth having are exactly the ones furthest from anyone who knows
     * which track they are for: a fetch inside a QuickJS export inside a module
     * search inside a source ladder. None of those layers has any other use for
     * a track id, and threading one through all of them to serve a debug button
     * would be a worse trade than the button is worth.
     *
     * Carried as a [kotlinx.coroutines.ThreadContextElement] rather than a bare
     * thread local because that work hops threads constantly —
     * `withContext(IO)` for a fetch, `Dispatchers.Default` for the JS engine —
     * and this follows it, including into every child coroutine.
     */
    fun about(id: String?): CoroutineContext = working.asContextElement(id)

    private class Line(val at: Long, val level: Char, val text: String, val track: String?)

    private val lines = ArrayDeque<Line>()

    /** Total characters held, so the buffer is bounded by size rather than by count. */
    private var held = 0

    /**
     * Bounded by bytes rather than by line count: one `callExport result` line
     * carrying a search response is worth several hundred ordinary lines, and a
     * limit that counts them the same either wastes memory or throws away the
     * history that matters.
     */
    private fun record(level: Char, message: String, about: String?) {
        val text = if (message.length > MAX_LINE_CHARS) {
            message.take(MAX_LINE_CHARS) + "…(${message.length - MAX_LINE_CHARS} more)"
        } else {
            message
        }
        synchronized(lines) {
            lines.addLast(Line(System.currentTimeMillis(), level, text, about))
            held += text.length
            while (held > MAX_HELD_CHARS && lines.isNotEmpty()) {
                held -= lines.removeFirst().text.length
            }
        }
    }

    // ── Reading ─────────────────────────────────────────────────────────────

    /**
     * Wall-clock times at which each track became the current one.
     *
     * Only a floor for tracks with no lines of their own, now that lines say
     * which track they are about: a track served whole from the disk cache is
     * resolved by nobody and would otherwise have no start at all.
     */
    private val startedAt = ConcurrentHashMap<String, Long>()

