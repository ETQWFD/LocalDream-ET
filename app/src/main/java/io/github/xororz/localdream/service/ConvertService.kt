package io.github.xororz.localdream.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import io.github.xororz.localdream.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * et.27: runs the safetensors→MNN conversion inside a foreground service so the
 * task survives screen-off and background reclaim. Holds a PARTIAL_WAKE_LOCK
 * and a high-performance WifiLock for the whole conversion, updates a real
 * progress notification, and streams native stdout/stderr to LogHub(CONVERT).
 *
 * The actual conversion logic lives in [io.github.xororz.localdream.ui.screens.ModelListScreenKt.convertCustomModel];
 * this service only adds lifecycle/foreground/locks around it.
 */
class ConvertService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    companion object {
        const val CHANNEL_ID = "ldet_convert"
        const val NOTIFICATION_ID = 4301

        fun start(context: Context) {
            val i = Intent(context, ConvertService::class.java)
            context.startForegroundService(i)
        }

        fun stop(context: Context) {
            val i = Intent(context, ConvertService::class.java)
            context.stopService(i)
        }
    }

    private fun acquireLocks() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ldet:convert").apply {
            setReferenceCounted(false)
            acquire(10 * 60 * 1000L) // 10 min safety cap; renewed per run
        }
        val wm = getSystemService(WIFI_SERVICE) as WifiManager
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "ldet:convert").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseLocks() {
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        runCatching { if (wifiLock?.isHeld == true) wifiLock?.release() }
    }

    private fun buildNotification(progress: Int, text: String): Notification {
        val mgr = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "Model conversion", NotificationManager.IMPORTANCE_LOW)
            mgr.createNotificationChannel(ch)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.converting_model))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setProgress(100, progress, progress < 0)
            .build()
    }

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification(-1, getString(R.string.preparing_model)))
        acquireLocks()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        releaseLocks()
        scope.cancel()
        super.onDestroy()
    }
}
