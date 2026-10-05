package io.github.xororz.localdream.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import io.github.xororz.localdream.R
import io.github.xororz.localdream.ui.screens.LoRAFile
import io.github.xororz.localdream.ui.screens.convertCustomModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * et.28: the conversion itself runs in this service's own service scope (not
 * the UI's rememberCoroutineScope), so leaving the model list or Activity
 * reclaim does not cancel it. Holds a PARTIAL_WAKE_LOCK and high-performance
 * WifiLock for the whole run (no short timeout), streams progress to
 * [ConvertManager] and LogHub(CONVERT).
 */
class ConvertService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null

    companion object {
        const val CHANNEL_ID = "ldet_convert"
        const val NOTIFICATION_ID = 4301
        const val EXTRA_MODEL_NAME = "model_name"
        const val EXTRA_FILE_URI = "file_uri"
        const val EXTRA_CLIP_SKIP = "clip_skip"

        fun start(
            context: Context,
            modelName: String,
            fileUri: Uri,
            clipSkip: Int,
        ) {
            val i = Intent(context, ConvertService::class.java).apply {
                putExtra(EXTRA_MODEL_NAME, modelName)
                putExtra(EXTRA_FILE_URI, fileUri.toString())
                putExtra(EXTRA_CLIP_SKIP, clipSkip)
            }
            context.startForegroundService(i)
        }

        fun stop(context: Context) {
            val i = Intent(context, ConvertService::class.java)
            context.stopService(i)
        }
    }

    private fun acquireLocks() {
        runCatching {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ldet:convert").apply {
                setReferenceCounted(false); acquire() // no short timeout
            }
            val wm = getSystemService(WIFI_SERVICE) as android.net.wifi.WifiManager
            wifiLock = wm.createWifiLock(
                android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "ldet:convert",
            ).apply { setReferenceCounted(false); acquire() }
        }
    }

    private fun releaseLocks() {
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        runCatching { if (wifiLock?.isHeld == true) wifiLock?.release() }
    }

    private fun buildNotification(text: String, progress: Int): Notification {
        val mgr = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val ch = NotificationChannel(CHANNEL_ID, "Model conversion", NotificationManager.IMPORTANCE_LOW)
        mgr.createNotificationChannel(ch)
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
        runCatching { startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.preparing_model), -1)) }
        acquireLocks()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val name = intent?.getStringExtra(EXTRA_MODEL_NAME) ?: return START_NOT_STICKY
        val uriStr = intent?.getStringExtra(EXTRA_FILE_URI) ?: return START_NOT_STICKY
        val clipSkip = intent?.getIntExtra(EXTRA_CLIP_SKIP, 1) ?: 1
        ConvertManager.preparing()
        scope.launch {
            runCatching {
                convertCustomModel(
                    context = applicationContext,
                    modelName = name,
                    fileUri = Uri.parse(uriStr),
                    clipSkip = clipSkip,
                    loraFiles = emptyList<LoRAFile>(),
                    onStart = { ConvertManager.preparing() },
                    onProgress = { msg ->
                        val pct = Regex("(\\d+)%").find(msg)?.value?.removeSuffix("%")?.toIntOrNull() ?: -1
                        ConvertManager.running(msg, pct)
                        runCatching {
                            getSystemService(NOTIFICATION_SERVICE).let { it as NotificationManager }
                                .notify(NOTIFICATION_ID, buildNotification(msg, pct))
                        }
                    },
                    onSuccess = {
                        ConvertManager.success()
                        runCatching { stopSelf() }
                    },
                    onError = { reason ->
                        ConvertManager.failed(reason)
                        runCatching { stopSelf() }
                    },
                )
            }.onFailure { e ->
                ConvertManager.failed(e.message ?: "convert error")
                runCatching { stopSelf() }
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        releaseLocks()
        scope.cancel()
        super.onDestroy()
    }
}
