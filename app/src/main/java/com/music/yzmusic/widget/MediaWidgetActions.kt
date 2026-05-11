package com.music.yzmusic.widget

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.music.yzmusic.playback.PlaybackService
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The widget's transport buttons.
 *
 * Playback is reached by **binding** the session, never by starting it. That is
 * the whole reason this is possible at all: `startService` and
 * `startForegroundService` are refused from the background on API 26+ and 31+,
 * and a tap on a home-screen widget is the background — but `bindService` is not
 * restricted, and a `MediaController` binds. Same handshake the app itself uses,
 * from [rememberMediaController][com.music.yzmusic.playback.rememberMediaController].
 *
 * It also means **play works with the app dead**, with nothing extra plumbed in:
 * the bind creates [PlaybackService], whose `onCreate` already restores the last
 * queue, so by the time the controller connects there is something to play. The
 * one thing that restore deliberately leaves undone is `prepare()` — it exists so
 * a cold app can *show* where you left off without pulling a stream for a track
 * nobody has asked for yet — so that is done here, at the point somebody has.
 */
class MediaWidgetActions : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action?.takeIf { it in ACTIONS } ?: return
        val app = context.applicationContext

        // Held open across the connect: a controller that arrives after the
        // broadcast has returned arrives in a process that may already be gone.
        val pending = goAsync()
        val handler = Handler(Looper.getMainLooper())
