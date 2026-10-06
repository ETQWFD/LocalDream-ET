package io.github.xororz.localdream.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import android.util.Base64
import android.util.Log
import androidx.core.app.NotificationCompat
import io.github.xororz.localdream.R
import io.github.xororz.localdream.cloud.ApiKeyStore
import io.github.xororz.localdream.remote.LanImageServer
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.net.BindException
import java.net.ServerSocket
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * et.37 (Bug9): foreground service that exposes an A1111/OpenAI-compatible LAN HTTP
 * image API on 0.0.0.0:<port>, driving the already-running local engine on
 * 127.0.0.1:8081. It never starts a second engine — it asks the existing
 * [BackendService] to load the chosen model and then proxies /generate calls.
 *
 * Holds a PARTIAL_WAKE_LOCK + WifiLock while alive; released on stop.
 */
class LanImageService : Service() {
    private var server: LanImageServer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    @Volatile private var actualPort = DEFAULT_PORT
    @Volatile private var modelId: String? = null
    private val genLock = Any() // serialize engine calls: single-instance serial inference

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, buildNotification())

        val chosenModel = intent?.getStringExtra(EXTRA_MODEL_ID)
        if (chosenModel != null && server == null) {
            modelId = chosenModel
            StateHolder._model.value = chosenModel
            // Bring the model up in the existing backend (single engine instance).
            startBackend(chosenModel)
            // Pick a free port starting at 8082.
            actualPort = findFreePort(DEFAULT_PORT, DEFAULT_PORT + 20)
            StateHolder._port.value = actualPort
            val key = ApiKeyStore(this).getOrCreate()
            val srv = LanImageServer(port = actualPort, authKey = key, driver = EngineDriver())
            try {
                srv.start()
            } catch (e: Exception) {
                Log.e(TAG, "failed to start LAN image server", e)
                updateRunning(false)
                stopSelf()
                return START_NOT_STICKY
            }
            server = srv
            acquireLocks()
            updateRunning(true)
        }
        return START_NOT_STICKY
    }

    private fun startBackend(mid: String) {
        try {
            val repo = io.github.xororz.localdream.data.ModelRepository.getInstance(this)
            kotlinx.coroutines.runBlocking { repo.ensureLoaded() }
            val model = repo.models.find { it.id == mid }
                ?: throw IllegalStateException("model $mid not found")
            val intent = Intent(this, BackendService::class.java).apply {
                putExtra("modelId", mid)
                putExtra("backendType", model.backendType)
                putExtra("width", 512)
                putExtra("height", 512)
            }
            startService(intent)
        } catch (e: Exception) {
            Log.e(TAG, "failed to bring up backend for $mid", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        server?.shutdown()
        server = null
        releaseLocks()
        updateRunning(false)
        // Leave the backend up only while we serve; stop it with us.
        try {
            startService(Intent(this, BackendService::class.java).setAction(BackendService.ACTION_STOP))
        } catch (_: Exception) {}
    }

    private fun acquireLocks() {
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ldet:lanimage").apply { acquire(3 * 60 * 60 * 1000L) }
            val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "ldet:lanimage").apply { acquire() }
        } catch (e: Exception) {
            Log.w(TAG, "lock acquire failed: ${e.message}")
        }
    }

    private fun releaseLocks() {
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        runCatching { if (wifiLock?.isHeld == true) wifiLock?.release() }
        wakeLock = null
        wifiLock = null
    }

    private fun createChannel() {
        val ch = NotificationChannel(CHANNEL_ID, "LAN Image API", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Local image generation HTTP API"
        }
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
    }

    private fun buildNotification(): Notification {
        val openApp = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val contentPi = PendingIntent.getActivity(this, 0, openApp, PendingIntent.FLAG_IMMUTABLE)
        val stopPi = PendingIntent.getService(
            this, 1,
            Intent(this, LanImageService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.api_server_notify_title))
            .setContentText(getString(R.string.api_server_notify, actualPort))
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentIntent(contentPi)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, getString(R.string.api_server_stop), stopPi)
            .build()
    }

    private inner class EngineDriver : LanImageServer.Driver {
        override fun isReady(): Boolean =
            BackendService.backendState.value is BackendService.BackendState.Running

        override fun currentModelId(): String? = modelId ?: BackendService.servingModelId.value

        override fun generate(
            prompt: String,
            negativePrompt: String,
            steps: Int,
            cfg: Float,
            width: Int,
            height: Int,
            seed: Long,
            sampler: String?,
            initImageBase64: String?,
            denoiseStrength: Float,
            n: Int,
        ): List<String> = synchronized(genLock) {
            val out = ArrayList<String>(n)
            repeat(n) {
                out.add(generateOne(prompt, negativePrompt, steps, cfg, width, height, seed, sampler, initImageBase64, denoiseStrength))
            }
            out
        }
    }

    /** One /generate SSE call against the local engine; returns base64 image. */
    private fun generateOne(
        prompt: String,
        negativePrompt: String,
        steps: Int,
        cfg: Float,
        width: Int,
        height: Int,
        seed: Long,
        sampler: String?,
        initImageBase64: String?,
        denoiseStrength: Float,
    ): String {
        val body = JSONObject().apply {
            put("prompt", prompt)
            put("negative_prompt", negativePrompt)
            put("steps", steps)
            put("cfg", cfg)
            put("width", width)
            put("height", height)
            put("denoise_strength", denoiseStrength)
            put("use_opencl", false)
            put("scheduler", sampler ?: "dpm")
            put("show_diffusion_process", false)
            if (seed >= 0) put("seed", seed)
            initImageBase64?.let { put("image", it) }
        }
        var conn: HttpURLConnection? = null
        try {
            conn = (URL("http://$ENGINE_HOST:$ENGINE_PORT/generate").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 15_000
                readTimeout = 300_000
                setRequestProperty("Content-Type", "application/json")
            }
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            if (conn.responseCode != 200) throw IOException("engine HTTP ${conn.responseCode}")
            val reader = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8))
            while (true) {
                val line = reader.readLine() ?: break
                if (!line.startsWith("data: ")) continue
                val data = line.substring(6).trim()
                if (data == "[DONE]") break
                val msg = runCatching { JSONObject(data) }.getOrNull() ?: continue
                when (msg.optString("type")) {
                    "progress" -> continue // ignore streamed previews
                    "complete" -> return decodeComplete(msg)
                }
            }
            throw IOException("no complete frame from engine")
        } finally {
            conn?.disconnect()
        }
    }

    private fun decodeComplete(msg: JSONObject): String {
        val b64 = msg.optString("image", "")
        if (b64.isEmpty()) throw IOException("engine returned no image")
        val bytes = Base64.decode(b64, Base64.DEFAULT)
        return when (msg.optString("format", "raw")) {
            "raw" -> {
                // Raw packed RGB/RGBA -> PNG.
                val w = msg.optInt("width", 512)
                val h = msg.optInt("height", 512)
                val channels = msg.optInt("channels", 3)
                val pixels = IntArray(w * h)
                if (channels == 4) rgbaBytesToPixels(bytes, pixels) else rgbBytesToPixels(bytes, pixels)
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                bmp.setPixels(pixels, 0, w, 0, 0, w, h)
                val baos = ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.PNG, 95, baos)
                Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
            }
            else -> {
                // Already-compressed jpeg/png frame: return as-is.
                Base64.encodeToString(bytes, Base64.NO_WRAP)
            }
        }
    }

    private fun rgbBytesToPixels(rgb: ByteArray, out: IntArray) {
        val n = minOf(out.size, rgb.size / 3)
        for (i in 0 until n) {
            val r = rgb[i * 3].toInt() and 0xFF
            val g = rgb[i * 3 + 1].toInt() and 0xFF
            val b = rgb[i * 3 + 2].toInt() and 0xFF
            out[i] = 0xFF000000.toInt() or (r shl 16) or (g shl 8) or b
        }
    }

    private fun rgbaBytesToPixels(rgba: ByteArray, out: IntArray) {
        val n = minOf(out.size, rgba.size / 4)
        for (i in 0 until n) {
            val r = rgba[i * 4].toInt() and 0xFF
            val g = rgba[i * 4 + 1].toInt() and 0xFF
            val b = rgba[i * 4 + 2].toInt() and 0xFF
            val a = rgba[i * 4 + 3].toInt() and 0xFF
            out[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }
    }

    private fun findFreePort(start: Int, end: Int): Int {
        for (p in start..end) {
            try {
                ServerSocket(p).use { return p }
            } catch (_: BindException) {
                // try next
            }
        }
        return start
    }

    private fun updateRunning(v: Boolean) {
        StateHolder._isRunning.value = v
    }

    companion object {
        private const val TAG = "LanImageService"
        private const val CHANNEL_ID = "lan_image_api_channel"
        private const val NOTIFICATION_ID = 7
        private const val DEFAULT_PORT = 8082
        private const val ENGINE_HOST = "127.0.0.1"
        private const val ENGINE_PORT = 8081

        const val ACTION_STOP = "io.github.xororz.localdream.STOP_LAN_IMAGE"
        const val EXTRA_MODEL_ID = "model_id"

        private object StateHolder {
            val _isRunning = MutableStateFlow(false)
            val _port = MutableStateFlow(DEFAULT_PORT)
            val _model = MutableStateFlow<String?>(null)
        }

        val isRunning: StateFlow<Boolean> = StateHolder._isRunning
        val port: StateFlow<Int> = StateHolder._port
        val model: StateFlow<String?> = StateHolder._model

        fun start(context: Context, modelId: String) {
            context.startForegroundService(
                Intent(context, LanImageService::class.java).putExtra(EXTRA_MODEL_ID, modelId),
            )
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, LanImageService::class.java).setAction(ACTION_STOP),
            )
        }

        /** Non-loopback, non-virtual IPv4 addresses (prefer 192.168/10.x). */
        fun localLanIp(): String? {
            return try {
                val ifaces = java.net.NetworkInterface.getNetworkInterfaces().toList()
                val candidates = ifaces
                    .filter { it.isUp && !it.isLoopback }
                    .flatMap { it.inetAddresses.toList() }
                    .filterIsInstance<java.net.Inet4Address>()
                    .mapNotNull { it.hostAddress }
                    .filter { !it.startsWith("172.16.") && !it.startsWith("172.17.") &&
                        !it.startsWith("172.18.") && !it.startsWith("172.19.") &&
                        !it.startsWith("172.2") && !it.startsWith("172.3") &&
                        !it.startsWith("169.254.") }
                candidates.firstOrNull { it.startsWith("192.168.") || it.startsWith("10.") }
                    ?: candidates.firstOrNull()
            } catch (_: Exception) { null }
        }
    }
}
