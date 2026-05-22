package com.music.yzmusic.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.music.yzmusic.R
import com.music.yzmusic.data.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Keeps the process alive while the download queue drains, and says so.
 *
 * A download is the one thing this app does that a user starts and then leaves:
 * they tap it and put the phone in a pocket. A coroutine on a ViewModel scope
 * would be killed the moment the activity goes, and a plain background service
 * on a modern Android is killed almost as fast — so this is a foreground
 * service, which is also the only honest arrangement, since a notification is
 * exactly what the user should get for work happening out of sight.
 *
 * It owns no state. The queue and everything known about it live in
 * [Downloads]; this drives that queue and reflects it into a notification, and
 * stops itself the moment there is nothing left to do.
 */
class DownloadService : Service() {

    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())

    private var drain: Job? = null
    private var notifier: Job? = null

    /** What the notification is currently about. */
    @Volatile
    private var current: Song? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Must happen within a few seconds of the start request whatever the
        // intent turns out to be, the cancel below included — a service started
        // with startForegroundService and never promoted takes the app down
        // with it.
        promote()

        if (intent?.action == ACTION_CANCEL_ALL) {
            Downloads.active.value.keys.toList().forEach(Downloads::cancel)
            shutdown(stopWork = true)
            return START_NOT_STICKY
        }

        if (drain == null) {
            drain = scope.launch {
                drainQueue()
                // Not shutdown(stopWork = true): this is the drain coroutine,
                // and cancelling its own job here would be cancelling itself.
                shutdown(stopWork = false)
            }
            notifier = scope.launch { reflectProgress() }
        }
        // Not sticky: a queue is a list of things asked for in a session, and
        // reviving the service without one would put up a notification about
        // nothing.
        return START_NOT_STICKY
    }

    /**
     * [WORKERS] tracks at a time, each pulling from the same queue.
     *
     * This used to be one, on the reasoning that these are ranged fetches
     * already served at line rate and two at once would finish neither sooner.
     * That reasoning was about the *transfer*, and the transfer turned out to
     * be the small half. Working out where a lossless track's bytes come from
     * — a search across every module in the index, then a stream endpoint
     * opened against the winner, then another when that one answers with a
     * lossy copy — is tens of seconds a track, and none of it is bandwidth. On
     * a 300-track queue drained one at a time, that is a connection sitting
     * idle for the great majority of the run: measured at ~19s a track against
     * a few seconds of actual transfer.
     *
     * It is latency, so the answer is overlap. Four in flight means four
     * lookups outstanding at once, and the module engines they land on are
     * pooled to match — see `QuickJsExecutor.ENGINES_PER_MODULE`, without which
     * this would be four workers taking turns on one interpreter and no faster
     * than one.
     *
     * A worker that finds the queue empty waits [IDLE_GRACE_MS] before giving
     * up rather than exiting on the spot. The queue is filled by a loop of
     * [Downloads.enqueue] calls and the first of them is what starts this
     * service, so at the moment the workers spin up there may be exactly one
     * track in it — and workers that took "empty" for "finished" would leave a
     * 300-track download being drained by however many happened to win that
     * race.
     */
    private suspend fun drainQueue() = coroutineScope {
        repeat(WORKERS) { launch { work() } }
    }

    private suspend fun work() {
        var idleFor = 0L
        while (true) {
            val song = Downloads.takeNext()
            if (song == null) {
                // Nothing to take, but something may still be arriving — or
                // another worker may fail a track back into view. Only a queue
                // that stays empty, with nothing else in flight, is finished.
                if (idleFor >= IDLE_GRACE_MS && !Downloads.busy()) return
                delay(IDLE_POLL_MS)
                idleFor += IDLE_POLL_MS
                continue
            }
            idleFor = 0L
            current = song
            postNotification()

            // Its own job, so one track can be cancelled out from under the
            // loop without taking the rest of the queue with it.
            val job = scope.launch { Downloads.run(this@DownloadService, song) }
            Downloads.onRunning(song.videoId, job)
            job.join()
            Downloads.onIdle(song.videoId)
        }
    }

    /** Repost as the running track advances, slowly enough not to thrash the shade. */
    private suspend fun reflectProgress() {
        Downloads.active.collect {
            postNotification()
            delay(PROGRESS_REFRESH_MS)
        }
    }

    private fun shutdown(stopWork: Boolean) {
        if (stopWork) drain?.cancel()
        notifier?.cancel()
        drain = null
        notifier = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        Downloads.onStopped()
        super.onDestroy()
    }

    // ---- Notification -------------------------------------------------------

    private fun promote() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun postNotification() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        runCatching { manager.notify(NOTIFICATION_ID, buildNotification()) }
    }

    private fun buildNotification(): Notification {
        val active = Downloads.active.value
        val runningStates = active.values.filterIsInstance<DownloadState.Running>()
