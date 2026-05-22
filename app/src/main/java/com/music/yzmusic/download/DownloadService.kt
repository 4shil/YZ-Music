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

