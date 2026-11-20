package com.novaplay.app.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.novaplay.app.Nav
import com.novaplay.app.R
import com.novaplay.app.data.DownloadItem
import com.novaplay.app.data.Library

/**
 * Keeps the process alive and shows one progress notification while [DownloadCenter] has running transfers.
 * It owns no download logic; it only mirrors the summary and stops itself when nothing runs any more.
 */
class DownloadService : Service() {
    private val refresh = Runnable { update(false) }
    private var lastNotify = 0L
    private var inForeground = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannels(this)
        Library.addListener(refresh)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_PAUSE_ALL) DownloadCenter.pauseAll()
        update(true)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        Library.removeListener(refresh)
        super.onDestroy()
    }

    private fun update(force: Boolean) {
        val summary = DownloadCenter.summary()
        val notification = build(summary)
        if (!inForeground) {
            // startForegroundService() obliges us to call startForeground(), even when there is nothing left to show.
            try {
                if (Build.VERSION.SDK_INT >= 29) {
                    startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                } else {
                    startForeground(NOTIF_ID, notification)
                }
                inForeground = true
            } catch (e: Exception) {
                stopSelf()
                return
            }
        }
        if (summary.running == 0) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            inForeground = false
            stopSelf()
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastNotify < 1000L) return
        lastNotify = now
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID, notification)
    }

    private fun build(s: DownloadCenter.Summary): Notification {
        val title = when {
            s.running > 1 -> "Downloading ${s.running} videos"
            s.title.isNotEmpty() -> s.title
            else -> "Downloading"
        }
        val pct = if (s.fraction >= 0f) (s.fraction * 100f).toInt().coerceIn(0, 100) else 0
        val text = StringBuilder()
        if (s.fraction >= 0f) text.append(pct).append('%') else text.append("Starting…")
        if (s.queued > 0) text.append(" · ").append(s.queued).append(" waiting")
        val pause = PendingIntent.getService(
            this, 0,
            Intent(this, DownloadService::class.java).setAction(ACTION_PAUSE_ALL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(title)
            .setContentText(text.toString())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, pct, s.fraction < 0f)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openDownloads(this))
            .addAction(0, "Pause all", pause)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        const val ACTION_PAUSE_ALL = "com.novaplay.app.action.PAUSE_ALL"
        const val CHANNEL_PROGRESS = "downloads_progress"
        const val CHANNEL_DONE = "downloads_done"
        private const val NOTIF_ID = 3001
        private const val DONE_BASE = 4000

        /** Call once at start-up (API 26+ needs the channels to exist before a notification is posted). */
        fun ensureChannels(context: Context) {
            if (Build.VERSION.SDK_INT < 26) return
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_PROGRESS, "Downloads in progress", NotificationManager.IMPORTANCE_LOW)
            )
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_DONE, "Finished downloads", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }

        fun notifyDone(context: Context, item: DownloadItem) {
            try {
                val n = NotificationCompat.Builder(context, CHANNEL_DONE)
                    .setSmallIcon(R.drawable.ic_stat_download)
                    .setContentTitle("Download complete")
                    .setContentText(item.video.title)
                    .setAutoCancel(true)
                    .setContentIntent(openDownloads(context))
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .build()
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.notify(DONE_BASE + (item.id.hashCode() and 0xFFFF), n)
            } catch (e: Exception) {
                // Notifications are a courtesy; never let them break a finished download.
            }
        }

        private fun openDownloads(context: Context): PendingIntent {
            val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: Intent()
            launch.putExtra(Nav.EXTRA_OPEN, Nav.TAB_DOWNLOADS)
            return PendingIntent.getActivity(
                context, 1, launch,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
    }
}
