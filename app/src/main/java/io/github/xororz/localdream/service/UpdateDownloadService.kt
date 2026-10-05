package io.github.xororz.localdream.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import io.github.xororz.localdream.R
import io.github.xororz.localdream.utils.AppUpdater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File

/**
 * Foreground service carrying the in-app APK update download.
 *
 * The previous download ran inside the UpdateDialog composable scope, so
 * backgrounding the app / Doze cancelled it mid-way. This service promotes to a
 * foreground notification (progress % + speed), holds a partial wake lock, and
 * resumes the existing Range-based downloader. On completion it posts a
 * "tap to install" notification; on failure it posts the real reason and bytes.
 */
class UpdateDownloadService : Service() {

    companion object {
        private const val TAG = "UpdateDownload"
        private const val CHANNEL_ID = "update_download_channel"
        private const val NOTIF_ID = 4711
        private const val EXTRA_URL = "apk_url"
        private const val ACTION_RETRY = "io.github.xororz.localdream.UPDATE_RETRY"

        fun start(context: Context, apkUrl: String) {
            val i = Intent(context, UpdateDownloadService::class.java).apply {
                putExtra(EXTRA_URL, apkUrl)
            }
            context.startForegroundService(i)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ldet:update").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val url = intent?.getStringExtra(EXTRA_URL)
        if (url.isNullOrBlank()) {
            stopSelf(); return START_NOT_STICKY
        }
        startForeground(NOTIF_ID, buildNotification(0, -1L, -1L, false))
        io.github.xororz.localdream.cloud.LogHub.log(
            io.github.xororz.localdream.cloud.LogHub.Category.UPDATE,
            "Update download start: $url",
        )
        scope.launch {
            var lastTs = System.currentTimeMillis()
            var lastBytes = 0L
            try {
                val apk: File = AppUpdater.download(this@UpdateDownloadService, url) { done, total ->
                    val now = System.currentTimeMillis()
                    val dt = (now - lastTs).coerceAtLeast(1L)
                    val speed = (done - lastBytes) * 1000L / dt
                    if (now - lastTs >= 400) {
                        lastTs = now; lastBytes = done
                        updateNotification(buildNotification(done, total, speed, false))
                    }
                }
                // Success: notification that opens the installer.
                io.github.xororz.localdream.cloud.LogHub.log(
                    io.github.xororz.localdream.cloud.LogHub.Category.UPDATE,
                    "Update download OK",
                )
                installOnTap(apk)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            } catch (e: Exception) {
                Log.e(TAG, "update download failed", e)
                io.github.xororz.localdream.cloud.LogHub.log(
                    io.github.xororz.localdream.cloud.LogHub.Category.UPDATE,
                    "Update download FAILED: ${e.message}",
                )
                failNotification(e.message ?: "unknown", url)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun buildNotification(
        done: Long,
        total: Long,
        speedBps: Long,
        doneFlag: Boolean,
    ): android.app.Notification {
        val pm = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val pi = PendingIntent.getActivity(
            this, 0, pm,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val pct = if (total > 0) (done * 100 / total).toInt() else 0
        val speedMb = speedBps / (1024.0 * 1024.0)
        val text = if (total > 0) {
            String.format("%.1f / %.1f MB  (%.1f MB/s)", done / 1048576.0, total / 1048576.0, speedMb)
        } else {
            String.format("%.1f MB", done / 1048576.0)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(getString(R.string.update_downloading_title))
            .setContentText(text)
            .setProgress(if (total > 0) 100 else 0, pct, total <= 0)
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
    }

    private fun updateNotification(n: android.app.Notification) {
        getSystemService(NotificationManager::class.java)?.notify(NOTIF_ID, n)
    }

    private fun installOnTap(apk: File) {
        val pm = packageManager.getLaunchIntentForPackage(packageName)
        val n = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(getString(R.string.update_ready_title))
            .setContentText(getString(R.string.update_ready_hint))
            .setAutoCancel(true)
            .build()
        // Tapping launches the installer via AppUpdater.install (handles unknown-sources).
        installPending(apk)?.let {
            val m = NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(getString(R.string.update_ready_title))
                .setContentText(getString(R.string.update_ready_hint))
                .setContentIntent(it)
                .setAutoCancel(true)
                .build()
            getSystemService(NotificationManager::class.java)?.notify(NOTIF_ID, m)
            return
        }
        getSystemService(NotificationManager::class.java)?.notify(NOTIF_ID, n)
    }

    private fun installPending(apk: File): PendingIntent? = runCatching {
        // A broadcast that triggers AppUpdater.install.
        val i = Intent(this, UpdateInstallReceiver::class.java).apply {
            putExtra("apk_path", apk.absolutePath)
        }
        PendingIntent.getBroadcast(
            this, apk.absolutePath.hashCode(), i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }.getOrNull()

    private fun failNotification(reason: String, url: String) {
        val retry = Intent(this, UpdateDownloadService::class.java).apply { putExtra(EXTRA_URL, url) }
        val rpi = PendingIntent.getService(
            this, url.hashCode(), retry,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(getString(R.string.update_failed_title))
            .setContentText(reason)
            .setContentIntent(rpi)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java)?.notify(NOTIF_ID, n)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.update_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            )
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(ch)
        }
    }

    override fun onDestroy() {
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        scope.cancel()
        super.onDestroy()
    }
}
