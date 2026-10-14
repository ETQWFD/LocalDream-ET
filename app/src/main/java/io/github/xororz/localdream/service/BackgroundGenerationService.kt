package io.github.xororz.localdream.service

import android.app.*
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.graphics.createBitmap
import io.github.xororz.localdream.R
import io.github.xororz.localdream.utils.Http
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

class BackgroundGenerationService : Service() {
    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private val notificationManager by lazy { getSystemService(NOTIFICATION_SERVICE) as NotificationManager }
    private var lastProgressNotifyAt = 0L

    // Held for the duration of one generation so the CPU keeps running the
    // native diffusion loop even with the screen off; released on destroy.
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null

    @Synchronized
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "LocalDreamET:generation",
        ).also {
            runCatching { it.acquire() }
        }
        runCatching {
            val wm = getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
            wifiLock = wm.createWifiLock(
                android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                "LocalDreamET:generation",
            ).apply { setReferenceCounted(false); acquire() }
        }
    }

    @Synchronized
    private fun releaseWakeLock() {
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        wakeLock = null
        runCatching { if (wifiLock?.isHeld == true) wifiLock?.release() }
        wifiLock = null
    }

    // In-flight /generate call; cancelled by ACTION_STOP. Cancelling closes the
    // socket, which the backend detects at the next progress event and aborts
    // the generation instead of computing a result nobody will read.
    @Volatile
    private var activeCall: okhttp3.Call? = null
    // et.30: mutable init-image copy for OOM safe-step rescale.
    private var retryImage: String? = null
    // et.30: engine context for OOM auto-restart.
    private var engineModelId: String? = null
    private var engineBackendType: String = "sd15cpu"

    @Volatile
    private var cancelRequested = false

    companion object {
        private const val CHANNEL_ID = "image_generation_channel"
        private const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "stop_generation"
        const val LOCAL_BACKEND_HOST = "localhost:8081"

        // Shared across generations; the long timeouts cover a single SDXL
        // request that can stream for many minutes.
        private val generationClient: OkHttpClient by lazy {
            Http.client.newBuilder()
                .connectTimeout(3600, TimeUnit.SECONDS)
                .readTimeout(3600, TimeUnit.SECONDS)
                .writeTimeout(3600, TimeUnit.SECONDS)
                .callTimeout(3600, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                // et.44: the engine (cpp-httplib, one generation per connection) does not
                // reliably support keep-alive. Reusing a pooled connection for the 2nd
                // /generate made the engine print "Req Rcvd" then never start UNET steps
                // (half-open pooled socket). Force a brand-new TCP connection per request.
                .connectionPool(okhttp3.ConnectionPool(0, 5, TimeUnit.MINUTES))
                .build()
        }

        private val _generationState = MutableStateFlow<GenerationState>(GenerationState.Idle)
        val generationState: StateFlow<GenerationState> = _generationState

        private val _bitmapConsumed = MutableStateFlow(false)

        private val _isServiceRunning = MutableStateFlow(false)
        val isServiceRunning: StateFlow<Boolean> = _isServiceRunning

        fun resetState() {
            _generationState.value = GenerationState.Idle
            _bitmapConsumed.value = false
        }

        fun clearCompleteState() {
            if (_generationState.value is GenerationState.Complete) {
                _generationState.value = GenerationState.Idle
            }
        }

        fun markBitmapConsumed() {
            _bitmapConsumed.value = true
        }

        /** Interrupts the in-flight generation (if any) and stops the service. */
        fun stop(context: Context) {
            context.startService(
                Intent(context, BackgroundGenerationService::class.java)
                    .setAction(ACTION_STOP),
            )
        }
    }

    sealed class GenerationState {
        object Idle : GenerationState()
        data class Progress(val progress: Float, val intermediateImage: Bitmap? = null) : GenerationState()

        data class Complete(val bitmap: Bitmap, val seed: Long?) : GenerationState()
        data class Error(val message: String) : GenerationState()
    }

    private fun updateState(newState: GenerationState) {
        _generationState.value = newState
    }

    override fun onCreate() {
        super.onCreate()
        Log.d("GenerationService", "service created")
        _isServiceRunning.value = true
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("GenerationService", "service execute: ${intent?.extras}")

        startForeground(NOTIFICATION_ID, createNotification(0f))
        acquireWakeLock()
        // et.50: optional overlay keep-alive; no-op without SYSTEM_ALERT_WINDOW.
        io.github.xororz.localdream.utils.OverlayKeepAlive.show(this) {
            cancelRequested = true
            activeCall?.cancel()
        }

        when (intent?.action) {
            ACTION_STOP -> {
                Log.d("GenerationService", "generation interrupted by user")
                cancelRequested = true
                activeCall?.cancel()
                updateState(GenerationState.Idle)
                stopSelf()
                return START_NOT_STICKY
            }
        }

        var prompt = intent?.getStringExtra("prompt")
        Log.d("GenerationService", "prompt: $prompt")

        if (prompt == null) {
            Log.e("GenerationService", "empty prompt")
            stopSelf()
            return START_NOT_STICKY
        }
        val data: android.content.Intent = intent!!

        var negativePrompt = data.getStringExtra("negative_prompt") ?: ""

        // SD1.5's CLIP text encoder only understands English. Chinese characters
        // map to garbage tokens (blank/ignored results), so the prompt must be
        // turned into English tags. The UI normally does full-sentence online
        // translation on an IO dispatcher and passes the result marked as
        // pretranslated; only fall back to the offline dictionary here if an
        // older caller sent raw Chinese without pretranslating. DiT models (e.g.
        // Qwen) natively accept Chinese and are passed through untouched.
        val englishOnly = data.getBooleanExtra("prompt_english_only", false)
        val pretranslated = data.getBooleanExtra("prompt_pretranslated", false)
        if (englishOnly && !pretranslated) {
            if (io.github.xororz.localdream.util.ChinesePrompt.hasChinese(prompt)) {
                prompt = io.github.xororz.localdream.util.ChinesePrompt.translatePrompt(prompt)
                Log.d("GenerationService", "offline-translated prompt: $prompt")
            }
            if (io.github.xororz.localdream.util.ChinesePrompt.hasChinese(negativePrompt)) {
                negativePrompt =
                    io.github.xororz.localdream.util.ChinesePrompt.translatePrompt(negativePrompt)
            }
        }
        val steps = data.getIntExtra("steps", 20)
        val cfg = data.getFloatExtra("cfg", 7f)
        val seed = if (data.hasExtra("seed")) data.getLongExtra("seed", 0) else null
        var width = data.getIntExtra("width", 512)
        var height = data.getIntExtra("height", 512)
        // Effective dimensions = target crop size for SDXL aspect-pad mode,
        // or equal to width/height otherwise. Used for decoding progress
        // previews which the backend already crops to the visible region.
        val effectiveWidth = data.getIntExtra("effective_width", width)
        val effectiveHeight = data.getIntExtra("effective_height", height)
        // et.25: img2img strength is clamped to 0.3–0.7 and defaults to 0.45.
        // strength=1 degrades to pure-noise txt2img; out-of-range values are
        // rejected here before they reach the native sampler.
        val denoiseStrength = data.getFloatExtra("denoise_strength", 0.45f)
            .coerceIn(0.3f, 0.7f)
        val useOpenCL = data.getBooleanExtra("use_opencl", false)
        val hasInitImage = data.getBooleanExtra("has_image", false)
        val scheduler = data.getStringExtra("scheduler") ?: "dpm"
        val aspectRatio = data.getStringExtra("aspect_ratio") ?: "1:1"
        // Ultrafix: tiled img2img repair over an upscaled image. Uses its own
        // base-image file so a pending img2img selection in tmp.txt survives.
        val ultrafix = data.getBooleanExtra("ultrafix", false)
        val ultrafixTileSize = data.getIntExtra("ultrafix_tile_size", 512)
        // Backend to talk to: the local backend by default, or a remote host's
        // generation port when running in connected-device mode.
        val backendHost = data.getStringExtra("backend_host") ?: LOCAL_BACKEND_HOST
        // et.30: save model context for OOM auto-restart of the engine.
        engineModelId = data.getStringExtra("modelId")
        engineBackendType = data.getStringExtra("backendType") ?: "sd15cpu"

        val image = if (ultrafix) {
            try {
                val ultrafixFile = File(applicationContext.filesDir, "ultrafix.txt")
                if (ultrafixFile.exists()) {
                    ultrafixFile.readText()
                } else {
                    null
                }
            } catch (e: Exception) {
                Log.e("GenerationService", "Failed to read ultrafix image data", e)
                null
            }
        } else if (data.getBooleanExtra("has_image", false)) {
            try {
                val tmpFile = File(applicationContext.filesDir, "tmp.txt")
                if (tmpFile.exists() && tmpFile.length() > 0) {
                    tmpFile.readText()
                } else {
                    // et.63: don't silently fall through to txt2img when the caller
                    // intended img2img but the base image is missing/empty. Surface a
                    // clear error instead of "出成了文生图、没传底图".
                    throw java.io.IOException("未读取到上传底图(tmp.txt 为空或不存在)，请在出图页重新选择图片")
                }
            } catch (e: Exception) {
                Log.e("GenerationService", "Failed to read image data", e)
                null
            }
        } else {
            null
        }
        // et.63: intended img2img but no base image was available -> hard error,
        // never silently run txt2img without the init image.
        if (data.getBooleanExtra("has_image", false) && image.isNullOrBlank()) {
            updateState(
                GenerationState.Error("未读取到底图，请重新上传图片后再修图"),
            )
            stopSelf()
            return START_NOT_STICKY
        }
        retryImage = image
        val mask = if (data.getBooleanExtra("has_mask", false)) {
            try {
                val maskFile = File(applicationContext.filesDir, "mask.txt")
                if (maskFile.exists()) {
                    maskFile.readText()
                } else {
                    Log.w(
                        "GenerationService",
                        "has_mask is true but mask.txt not found",
                    )
                    null
                }
            } catch (e: Exception) {
                Log.e("GenerationService", "Failed to read mask data", e)
                null
            }
        } else {
            null
        }
        val referenceImages = if (data.getBooleanExtra("has_reference_images", false)) {
            try {
                val refsFile = File(applicationContext.filesDir, "dit_references.json")
                if (refsFile.exists()) JSONArray(refsFile.readText()) else null
            } catch (e: Exception) {
                Log.e("GenerationService", "Failed to read DiT reference images", e)
                null
            }
        } else {
            null
        }

        Log.d("GenerationService", "params: steps=$steps, cfg=$cfg, seed=$seed")

        if (_generationState.value is GenerationState.Complete) {
            updateState(GenerationState.Idle)
        }
        _bitmapConsumed.value = false
        cancelRequested = false

        serviceScope.launch {
            Log.d("GenerationService", "start generation")
            runGeneration(
                prompt,
                negativePrompt,
                steps,
                cfg,
                seed,
                width,
                height,
                effectiveWidth,
                effectiveHeight,
                image,
                mask,
                referenceImages,
                denoiseStrength,
                useOpenCL,
                scheduler,
                aspectRatio,
                ultrafix,
                ultrafixTileSize,
                backendHost,
            )
        }

        return START_NOT_STICKY
    }

    @Suppress("LongParameterList")
    private suspend fun runGeneration(
        prompt: String,
        negativePrompt: String,
        steps: Int,
        cfg: Float,
        seed: Long?,
        width: Int,
        height: Int,
        effectiveWidth: Int,
        effectiveHeight: Int,
        image: String?,
        mask: String?,
        referenceImages: JSONArray?,
        denoiseStrength: Float,
        useOpenCL: Boolean,
        scheduler: String,
        aspectRatio: String,
        ultrafix: Boolean,
        ultrafixTileSize: Int,
        backendHost: String,
    ) = withContext(Dispatchers.IO) {
        // Set once the complete event is fully handled; a socket teardown
        // racing the service shutdown after that point is not an error.
        var completed = false
        try {
            updateState(GenerationState.Progress(0f))

            val preferences =
                applicationContext.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            val showProcess = preferences.getBoolean("show_diffusion_process", false)
            val showStride = preferences.getInt("show_diffusion_stride", 1)

            // et.25: GPU -> CPU auto-recovery. If this device's GPU fp16 has
            // already produced a bad image once, we default to CPU. Each
            // generation attempt may be retried exactly once on CPU with safe
            // params when the GPU output fails the structural health check.
            var attemptOpenCL = useOpenCL && !preferences.getBoolean("gpu_unstable", false)
            // et.52: any reasonable CFG (1..15) must submit; clamp + round to 1 decimal
            // instead of rejecting. Upstream passes cfg raw; we only guard against
            // out-of-range / fractional edge values that could trip the engine.
            var attemptCfg = Math.round(cfg.coerceIn(1f, 15f) * 10f) / 10f
            var attemptSampler = scheduler
            var attemptSteps = steps
            var attemptWidth = width
            var attemptHeight = height
            // et.45: HARD request-build-layer tier for extreme-low-end 32-bit devices
            // (armeabi-v7a, ~2-3GB, no fp16/dotprod, e.g. PowerVR GE8320). Regardless of
            // what the UI / saved speed_tier / custom fields say, the params actually sent
            // to the engine are pinned to the only proven-stable profile: 256 long edge,
            // <=8 steps, euler_a, batch 1. This is where S:25 in the log is overridden.
            if (io.github.xororz.localdream.utils.DeviceCapabilities.extremeLowRam(this@BackgroundGenerationService)) {
                // et.46: user can opt out of the auto pin via "force_generate" (Settings).
                val forceDraw = applicationContext
                    .getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                    .getBoolean("force_generate", false)
                if (!forceDraw) {
                val le = 256
                val (cw, ch) = run {
                    val lo = minOf(attemptWidth, attemptHeight)
                    val hi = maxOf(attemptWidth, attemptHeight)
                    val scale = le.toDouble() / hi
                    var w = Math.round(attemptWidth * scale).toInt()
                    var h = Math.round(attemptHeight * scale).toInt()
                    w = (w / 8) * 8; h = (h / 8) * 8
                    if (w < 8) w = 8; if (h < 8) h = 8
                    w to h
                }
                attemptWidth = cw
                attemptHeight = ch
                attemptSteps = 8
                attemptSampler = "euler_a"
                io.github.xororz.localdream.cloud.LogHub.log(
                    io.github.xororz.localdream.cloud.LogHub.Category.GENERATE,
                    "Extreme-low-end device: pinned to 256/8/euler_a/batch1 (was ${width}x${height}/$steps/$scheduler)",
                )
                }
            }
            var cpuRetryDone = false
            // et.30: independent OOM safe-step retry (384 long edge). Each flag
            // fires at most once so GPU->CPU and OOM->384 cannot loop.
            var safeOomRetryDone = false
            var badAllocRetryDone = false
            // et.44-patch: a transport hiccup on an otherwise-live engine gets exactly
            // one in-place reconnect (fresh connection, same params) — no process restart.
            var connRetryDone = false

            attemptLoop@ while (true) {
            try {
            val defaultNeg = "lowres, bad anatomy, bad hands, missing fingers, extra fingers, extra limbs, deformed, disfigured, mutated hands, extra arms, extra legs, extra eyes, ugly, worst quality"
            val effNegative = if (negativePrompt.contains("bad anatomy")) negativePrompt
                else if (negativePrompt.isBlank()) defaultNeg
                else "$negativePrompt, $defaultNeg"
            Log.d(
                "GenerationService",
                "InitImg:${if (retryImage.isNullOrBlank()) "none" else "${attemptWidth}x${attemptHeight} bytes=${retryImage?.length}"} denoise=$denoiseStrength",
            )
            val jsonObject = JSONObject().apply {
                put("prompt", prompt)
                put("negative_prompt", effNegative)
                put("steps", attemptSteps)
                put("cfg", attemptCfg)
                // Per-step previews come back as base64 JPEG (tiny) instead of
                // raw RGB; the final image stays raw (lossless, loopback-cheap).
                put("preview_format", "jpeg")
                put("width", attemptWidth)
                put("height", attemptHeight)
                put("denoise_strength", denoiseStrength)
                put("use_opencl", attemptOpenCL)
                put("scheduler", attemptSampler)
                // Ultrafix never streams previews: each one would tile-decode
                // the full image (the backend rejects it as well).
                put("show_diffusion_process", if (ultrafix) false else showProcess)
                put("show_diffusion_stride", showStride)
                if (ultrafix) {
                    put("ultrafix", true)
                    put("tile_size", ultrafixTileSize)
                    // The result is 4x-class resolution; raw RGB would be a
                    // ~67 MB base64 payload at 4096x4096. It is persisted as
                    // JPEG by the history manager anyway.
                    put("output_format", "jpeg")
                } else {
                    put("aspect_ratio", aspectRatio)
                }
                seed?.let { put("seed", it) }
                retryImage?.let { put("image", it) }
                mask?.let { put("mask", it) }
                referenceImages?.let { put("reference_images", it) }
            }

            val request = Request.Builder()
                .url("http://$backendHost/generate")
                .post(jsonObject.toString().toRequestBody("application/json".toMediaTypeOrNull()))
                // et.44: tell the single-shot engine to close the socket after this
                // response, so the next generation gets a clean accept.
                .header("Connection", "close")
                .build()

            // et.53: mark a generation request as in flight. While this is true the
            // BackendService must NOT close the engine pipe or destroy the process —
            // fixes "Req Rcvd ... -> read interrupted by close() -> engine exited 143".
            io.github.xororz.localdream.service.BackendService.notifyGenerationStarted()
            // et.73: engine startup can take 10-30s; poll 8081 every 1s up
            // to 30s before surfacing "引擎未就绪". EditScreen now also
            // starts BackendService first, so this poll waits for it.
            run {
                var ok = false
                repeat(30) { _ ->
                    runCatching {
                        java.net.Socket().use {
                            it.connect(java.net.InetSocketAddress("127.0.0.1", 8081), 1500); ok = true
                        }
                    }
                    if (ok) return@repeat
                    runCatching { Thread.sleep(1000) }
                }
                if (!ok) throw IOException("引擎启动失败：8081 30秒内未就绪，模型文件可能损坏，请重新下载")
            }
            // et.57: optional background music while generating.
            io.github.xororz.localdream.utils.BgmManager.start(this@BackgroundGenerationService)
            val call = generationClient.newCall(request)
            activeCall = call
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException(
                        this@BackgroundGenerationService.getString(
                            R.string.error_request_failed,
                            response.code.toString(),
                        ),
                    )
                }

                response.body?.let { responseBody ->
                    Log.d("BgGenService", "Reading streaming response")

                    val reader = BufferedReader(InputStreamReader(responseBody.byteStream()))
                    var messageCount = 0
                    // Reused across progress previews: with the diffusion
                    // process shown every step would otherwise allocate a
                    // fresh width*height IntArray (4 MB at 1024x1024).
                    var previewPixels: IntArray? = null
                    // Throttle for the *preview image* (not the percent): when
                    // the user enables the step-by-step preview, a full bitmap
                    // arrived every diffusion step and was pushed straight into
                    // Compose, churning bitmaps and recomposing the overlay each
                    // step ("generation laggy"). The percent bar still updates
                    // every step; we only rate-limit attaching a new preview
                    // bitmap to ~7 fps. The final result image is unaffected.
                    var lastPreviewEmitMs = 0L

                    // Read line by line for efficiency
                    readLoop@ while (isActive) {
                        val readLineStart = System.currentTimeMillis()
                        val line = reader.readLine() ?: break
                        val readLineTime = System.currentTimeMillis() - readLineStart

                        if (line.startsWith("data: ")) {
                            val data = line.substring(6).trim()
                            if (data == "[DONE]") break

                            val jsonParseStart = System.currentTimeMillis()
                            val message = JSONObject(data)
                            val jsonParseTime = System.currentTimeMillis() - jsonParseStart
                            messageCount++

                            when (message.optString("type")) {
                                "progress" -> {
                                    val step = message.optInt("step")
                                    val totalSteps = message.optInt("total_steps")
                                    val progress = step.toFloat() / totalSteps

                                    val b64Img = message.optString("image")
                                    var bitmap: Bitmap? = null
                                    if (b64Img.isNotEmpty()) {
                                        try {
                                            val imageBytes = Base64.getDecoder().decode(b64Img)
                                            bitmap = if (message.optString("format", "raw") == "raw") {
                                                // Progress previews are cropped to (effectiveWidth,
                                                // effectiveHeight) by the backend so the SDXL aspect-pad
                                                // path doesn't ship the 1024 canvas every step.
                                                val pw = effectiveWidth
                                                val ph = effectiveHeight
                                                val pixels = previewPixels
                                                    ?.takeIf { it.size == pw * ph }
                                                    ?: IntArray(pw * ph).also {
                                                        previewPixels = it
                                                    }
                                                rgbBytesToPixels(imageBytes, pixels)
                                                createBitmap(pw, ph).also {
                                                    it.setPixels(pixels, 0, pw, 0, 0, pw, ph)
                                                }
                                            } else {
                                                // jpeg/png preview: decode downsampled. These are
                                                // throwaway previews (the final image comes back raw),
                                                // so decode at most ~512px on the long edge instead of
                                                // allocating a full-size bitmap every step.
                                                val bounds = BitmapFactory.Options().apply {
                                                    inJustDecodeBounds = true
                                                }
                                                BitmapFactory.decodeByteArray(
                                                    imageBytes, 0, imageBytes.size, bounds,
                                                )
                                                var sample = 1
                                                val maxEdge = 512
                                                while (bounds.outWidth / sample > maxEdge ||
                                                    bounds.outHeight / sample > maxEdge
                                                ) {
                                                    sample *= 2
                                                }
                                                val opts = BitmapFactory.Options().apply {
                                                    inSampleSize = sample
                                                }
                                                BitmapFactory.decodeByteArray(
                                                    imageBytes, 0, imageBytes.size, opts,
                                                )
                                            }
                                        } catch (e: Exception) {
                                            Log.e(
                                                "BgGenService",
                                                "Failed to decode intermediate image",
                                                e,
                                            )
                                        }
                                    }

                                    // Rate-limit the preview *bitmap* only. The progress
                                    // percent always flows through; a new preview image is
                                    // attached at most ~7x per second to avoid per-step
                                    // Compose churn. The very first preview is always shown.
                                    if (bitmap != null) {
                                        val nowMs = System.currentTimeMillis()
                                        if (lastPreviewEmitMs != 0L &&
                                            nowMs - lastPreviewEmitMs < 150L
                                        ) {
                                            bitmap = null
                                        } else {
                                            lastPreviewEmitMs = nowMs
                                        }
                                    }

                                    updateState(GenerationState.Progress(progress, bitmap))
                                    updateNotification(progress)
                                }

                                "complete" -> {
                                    Log.d(
                                        "BgGenService",
                                        "=== Received complete message, parsing... ===",
                                    )
                                    Log.d(
                                        "BgGenService",
                                        "readLine took: ${readLineTime}ms, line length: ${line.length}",
                                    )
                                    Log.d(
                                        "BgGenService",
                                        "JSONObject parsing took: ${jsonParseTime}ms, data length: ${data.length}",
                                    )
                                    val completeStartTime = System.currentTimeMillis()

                                    // 1. Extract fields from JSON
                                    val extractStart = System.currentTimeMillis()
                                    val base64Image = message.optString("image")
                                    val returnedSeed =
                                        message.optLong("seed", -1).takeIf { it != -1L }
                                    val resultWidth = message.optInt("width", 512)
                                    val resultHeight = message.optInt("height", 512)
                                    val resultChannels = message.optInt("channels", 3)
                                    Log.d(
                                        "BgGenService",
                                        "JSON extraction took: ${System.currentTimeMillis() - extractStart}ms, Base64 length: ${base64Image.length}",
                                    )

                                    if (base64Image.isNullOrEmpty()) {
                                        throw IOException("no image data")
                                    }

                                    // 2. Base64 decode
                                    val decodeStartTime = System.currentTimeMillis()
                                    val imageBytes = Base64.getDecoder().decode(base64Image)
                                    Log.d(
                                        "BgGenService",
                                        "Base64 decoding took: ${System.currentTimeMillis() - decodeStartTime}ms, decoded size: ${imageBytes.size} bytes",
                                    )

                                    // 3. Packed RGB/RGBA conversion + Bitmap creation.
                                    // Qwen Image 2.1 returns its native alpha channel;
                                    // the other backends continue to return RGB.
                                    val bitmapStartTime = System.currentTimeMillis()
                                    val bitmap = if (message.optString("format", "raw") == "raw") {
                                        if (resultChannels != 3 && resultChannels != 4) {
                                            throw IOException(
                                                "Unsupported result channel count: $resultChannels",
                                            )
                                        }
                                        val expectedBytes =
                                            resultWidth.toLong() * resultHeight * resultChannels
                                        if (expectedBytes > Int.MAX_VALUE || imageBytes.size != expectedBytes.toInt()) {
                                            throw IOException(
                                                "Invalid raw image size: expected $expectedBytes bytes, got ${imageBytes.size}",
                                            )
                                        }
                                        val pixels = IntArray(resultWidth * resultHeight)
                                        if (resultChannels == 4) {
                                            rgbaBytesToPixels(imageBytes, pixels)
                                        } else {
                                            rgbBytesToPixels(imageBytes, pixels)
                                        }
                                        createBitmap(resultWidth, resultHeight).also {
                                            it.setPixels(
                                                pixels,
                                                0,
                                                resultWidth,
                                                0,
                                                0,
                                                resultWidth,
                                                resultHeight,
                                            )
                                            it.setHasAlpha(resultChannels == 4)
                                        }
                                    } else {
                                        BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                                            ?: throw IOException("Failed to decode result image")
                                    }
                                    Log.d(
                                        "BgGenService",
                                        "RGB conversion + Bitmap creation took: ${System.currentTimeMillis() - bitmapStartTime}ms",
                                    )

                                    // et.25: structural health gate + GPU->CPU auto
                                    // recovery. A divergent latent / VAE-NaN
                                    // "shattered glass" result is never saved as a
                                    // success; on the GPU we retry exactly once on
                                    // CPU with safe params before declaring failure.
                                    val health = io.github.xororz.localdream.cloud.BitmapHealth.assess(bitmap)
                                    if (health != null) {
                                        io.github.xororz.localdream.cloud.LogHub.log(
                                            io.github.xororz.localdream.cloud.LogHub.Category.GENERATE,
                                            "$health use_opencl=$attemptOpenCL has_image=${!image.isNullOrBlank()} size=${resultWidth}x$resultHeight strength=$denoiseStrength",
                                        )
                                        if (attemptOpenCL && !cpuRetryDone) {
                                            // Mark this device's GPU as unstable and
                                            // retry once on CPU with safe defaults.
                                            preferences.edit().putBoolean("gpu_unstable", true).apply()
                                            cpuRetryDone = true
                                            attemptOpenCL = false
                                            attemptCfg = 7f
                                            attemptSampler = "dpm++2m"
                                            attemptSteps = 20
                                            io.github.xororz.localdream.cloud.LogHub.log(
                                                io.github.xororz.localdream.cloud.LogHub.Category.GENERATE,
                                                "GPU output abnormal, auto switching to CPU retry",
                                            )
                                            updateState(GenerationState.Progress(0f))
                                            break@readLoop
                                        }
                                        // CPU also bad: likely corrupted model weights.
                                        throw java.io.IOException(
                                            getString(R.string.error_result_abnormal_model),
                                        )
                                    }

                                    // Surface a one-time, non-intrusive notice that
                                    // this device's GPU was found unstable.
                                    if (cpuRetryDone) {
                                        io.github.xororz.localdream.cloud.LogHub.log(
                                            io.github.xororz.localdream.cloud.LogHub.Category.GENERATE,
                                            "CPU retry produced a valid image; GPU marked unstable for this device",
                                        )
                                    }

                                    Log.d(
                                        "BgGenService",
                                        "=== Total processing time for complete message: ${System.currentTimeMillis() - completeStartTime}ms, size: ${resultWidth}x$resultHeight ===",
                                    )

                                    updateState(
                                        GenerationState.Complete(
                                            bitmap,
                                            returnedSeed,
                                        ),
                                    )

                                    Log.d(
                                        "BgGenService",
                                        "Generation completed, waiting for UI to consume bitmap",
                                    )

                                    // Wait for UI to consume the bitmap with timeout
                                    val waitStartTime = System.currentTimeMillis()
                                    val consumed = withTimeoutOrNull(5000L) {
                                        _bitmapConsumed.first { it }
                                    }
                                    if (consumed == null) {
                                        Log.w(
                                            "BgGenService",
                                            "Timeout waiting for bitmap consumption",
                                        )
                                    }

                                    Log.d(
                                        "BgGenService",
                                        "Bitmap consumed, stopping service. Wait time: ${System.currentTimeMillis() - waitStartTime}ms",
                                    )
                                    completed = true
                                    // et.39: the UI already holds its own reference to the
                                    // bitmap (on-screen display + async JPEG save). Drop the
                                    // process-wide StateFlow reference so the singleton does
                                    // not pin the full-size result bitmap until the next run,
                                    // and release the big retry base64 string. (We do NOT
                                    // recycle the native bitmap here — the UI still uses it.)
                                    _generationState.value = GenerationState.Idle
                                    retryImage = null
                                    // et.65: optional "auto exit after current image" power mode.
                                    // Only honored when the API/LAN server is NOT running (it
                                    // may need the engine resident). Stops the engine process,
                                    // removes the foreground notification and any overlay ball.
                                    val autoExit = applicationContext
                                        .getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                                        .getBoolean("auto_exit_after_gen", false)
                                    val apiRunning = try {
                                        applicationContext
                                            .getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                                            .getBoolean("api_server_running", false)
                                    } catch (e: Exception) { false }
                                    if (autoExit && !apiRunning) {
                                        try {
                                            startService(
                                                android.content.Intent(
                                                    this@BackgroundGenerationService,
                                                    io.github.xororz.localdream.service.BackendService::class.java,
                                                ).apply {
                                                    action = io.github.xororz.localdream.service.BackendService.ACTION_STOP
                                                },
                                            )
                                        } catch (e: Exception) {
                                            Log.w("BgGenService", "auto_exit stop engine failed", e)
                                        }
                                    }
                                    stopSelf()
                                    // The stream carries nothing after complete; leaving
                                    // the loop here avoids a blocked readLine() racing the
                                    // service shutdown (stopSelf -> onDestroy cancels the
                                    // call, which would surface as "Socket closed").
                                    break@readLoop
                                }

                                "error" -> {
                                    val errorMsg =
                                        message.optString("message", "unknown error")
                                    Log.e(
                                        "BgGenService",
                                        "Received error message: $errorMsg",
                                    )
                                    throw IOException(errorMsg)
                                }
                            }
                        }
                    }
                }
            }
            // et.25: if the GPU output was bad and we flipped to CPU, re-post
            // once with CPU safe params. Otherwise the attempt loop is finished.
            if (cpuRetryDone) continue@attemptLoop
            break@attemptLoop
            } catch (e: Exception) {
                val exitCode = io.github.xororz.localdream.utils.EngineExitInfo.lastExitCode
                // et.56: engine threw std::bad_alloc mid-generation (e.g. QNN package
                // built for 8gen2 running on 8 Elite / Android 17, HTP pool mismatch).
                // This is NOT a 143/OOM-killer — the process is alive, just failed a big
                // allocation. Auto-downgrade resolution 512->384->256 once and resend.
                val msgLc = (e.message ?: "").lowercase()
                val isBadAlloc = msgLc.contains("bad_alloc") || msgLc.contains("image generation error")
                if (isBadAlloc && !badAllocRetryDone) {
                    badAllocRetryDone = true
                    val longEdge = maxOf(attemptWidth, attemptHeight)
                    val target = when {
                        longEdge > 448 -> 384
                        else -> 256
                    }
                    val scale = target.toDouble() / longEdge
                    attemptWidth = (Math.round(attemptWidth * scale).toInt() / 8) * 8
                    attemptHeight = (Math.round(attemptHeight * scale).toInt() / 8) * 8
                    io.github.xororz.localdream.cloud.LogHub.log(
                        io.github.xororz.localdream.cloud.LogHub.Category.GENERATE,
                        "engine bad_alloc; auto-downgrade to ${attemptWidth}x${attemptHeight}",
                    )
                    if (engineModelId != null) {
                        runCatching {
                            val ri = android.content.Intent(
                                this@BackgroundGenerationService,
                                io.github.xororz.localdream.service.BackendService::class.java,
                            ).apply {
                                setAction(io.github.xororz.localdream.service.BackendService.ACTION_RESTART)
                                putExtra("modelId", engineModelId)
                                putExtra("backendType", engineBackendType)
                                putExtra("width", attemptWidth)
                                putExtra("height", attemptHeight)
                            }
                            startService(ri)
                            var waited = 0L
                            while (waited < 45_000L) {
                                try { java.net.Socket("127.0.0.1", 8081).use { break } }
                                catch (_: Exception) { Thread.sleep(400); waited += 400 }
                            }
                        }
                    }
                    updateState(GenerationState.Progress(0f))
                    continue@attemptLoop
                }
                // et.44-patch: only a CONFIRMED hard process exit may count as the
                // engine dying. read-interrupted / connection-reset / broken-pipe /
                // unexpected-end are transport/pipeline errors — with et.44's per-request
                // fresh TCP connection the engine process is actually still alive, and
                // killing+restarting it on these is exactly what caused the dense
                // start/143 churn and mid-generation interruptions.
                val engineDied = exitCode in setOf(137, 134, 139)
                // et.51: the CPU "降到384安全参数重试" must ONLY run on the CPU engine
                // (sd15cpu) where 137 is plausibly memory pressure. On NPU/DiT backends a
                // crash is NOT OOM (a 32GB NPU phone reported "内存不足" running Anything V5
                // NPU) — applying the CPU 384 downgrade there is meaningless and misleading.
                val isCpuBackend = engineBackendType == "sd15cpu"
                if (engineDied && !isCpuBackend) {
                    // NPU / DiT / other accelerator: report the REAL reason, never "内存不足".
                    val reason = when (exitCode) {
                        137 -> "NPU 引擎被系统结束(code=137)，可能是 NPU 驱动/模型不匹配"
                        134 -> "NPU/加速引擎初始化失败(code=134 SIGABRT)，模型与加速方式可能不兼容"
                        139 -> "NPU/加速引擎崩溃(code=139 段错误)，模型与加速方式可能不兼容"
                        else -> "加速引擎退出(code=$exitCode)"
                    }
                    io.github.xororz.localdream.cloud.LogHub.log(
                        io.github.xororz.localdream.cloud.LogHub.Category.GENERATE,
                        "Accelerator backend ($engineBackendType) died exit=$exitCode; not a CPU OOM retry.",
                    )
                    updateState(
                        GenerationState.Error(
                            "$reason。请改用 CPU Models 标签下的对应模型；详见 engine_*.log 末行。",
                        ),
                    )
                    stopSelf()
                    break@attemptLoop
                }
                if (engineDied && !safeOomRetryDone) {
                    safeOomRetryDone = true
                    // et.45-patch: tier-aware OOM retry. On extreme-low-end 32-bit devices we
                    // never escalate to 384/20 (that just OOMs again). If we are ALREADY at
                    // the minimum 256/8 tier and still got 137, give up cleanly with a clear
                    // message — do NOT restart the engine in a loop.
                    val extreme = io.github.xororz.localdream.utils.DeviceCapabilities
                        .extremeLowRam(this@BackgroundGenerationService)
                    val longEdge = maxOf(attemptWidth, attemptHeight)
                    val alreadyLowest = longEdge <= 256 && attemptSteps <= 8
                    if (extreme && alreadyLowest) {
                        io.github.xororz.localdream.cloud.LogHub.log(
                            io.github.xororz.localdream.cloud.LogHub.Category.GENERATE,
                            "OOM at minimum 256/8 tier on extreme-low device; stopping.",
                        )
                        updateState(GenerationState.Error(getString(R.string.err_extreme_low_oom)))
                        stopSelf()
                        break@attemptLoop
                    }
                    io.github.xororz.localdream.cloud.LogHub.log(
                        io.github.xororz.localdream.cloud.LogHub.Category.GENERATE,
                        "Engine died mid-run (exit=$exitCode, err=${e.message}); auto retry safe step",
                    )
                    val ratio = io.github.xororz.localdream.ui.screens.inferAspectRatioString(attemptWidth, attemptHeight)
                    if (extreme) {
                        // et.45-patch: keep the extreme-low device pinned to the minimum tier
                        // rather than jumping to 384/20.
                        val scale = 256.0 / maxOf(attemptWidth, attemptHeight)
                        attemptWidth = (Math.round(attemptWidth * scale).toInt() / 8) * 8
                        attemptHeight = (Math.round(attemptHeight * scale).toInt() / 8) * 8
                        attemptCfg = 7f
                        attemptSampler = "euler_a"
                        attemptSteps = 8
                    } else {
                        val (nw, nh) = io.github.xororz.localdream.ui.screens.sd15SizeForRatio(ratio, 384)
                        attemptWidth = nw
                        attemptHeight = nh
                        attemptCfg = 7f
                        attemptSampler = "dpm++2m"
                        attemptSteps = 20
                    }
                    // Restart the engine at the new safe resolution, then wait
                    // for 8081 to be ready before resending.
                    if (engineModelId != null) {
                        try {
                            val restartIntent = android.content.Intent(
                                this@BackgroundGenerationService,
                                io.github.xororz.localdream.service.BackendService::class.java,
                            ).apply {
                                setAction(io.github.xororz.localdream.service.BackendService.ACTION_RESTART)
                                putExtra("modelId", engineModelId)
                                putExtra("backendType", engineBackendType)
                                putExtra("use_opencl", false)
                                putExtra("width", attemptWidth)
                                putExtra("height", attemptHeight)
                            }
                            startService(restartIntent)
                            // Wait for the new engine to listen on 8081.
                            var waited = 0L
                            var ready = false
                            while (waited < 45_000L) {
                                try {
                                    java.net.Socket("127.0.0.1", 8081).use { ready = true; break }
                                } catch (_: Exception) {
                                    Thread.sleep(400)
                                    waited += 400
                                }
                            }
                            io.github.xororz.localdream.cloud.LogHub.log(
                                io.github.xororz.localdream.cloud.LogHub.Category.GENERATE,
                                if (ready) "Engine restarted, 8081 ready at ${attemptWidth}x${attemptHeight}"
                                else "Engine restart timed out waiting for 8081",
                            )
                            if (!ready) throw IOException("Engine restart timeout")
                        } catch (re: Exception) {
                            io.github.xororz.localdream.cloud.LogHub.log(
                                io.github.xororz.localdream.cloud.LogHub.Category.GENERATE,
                                "Engine restart failed: ${re.message}",
                            )
                        }
                    }
                    // Rescale the init image to the new safe resolution.
                    if (!image.isNullOrBlank()) {
                        try {
                            val bytes = android.util.Base64.decode(image, android.util.Base64.DEFAULT)
                            val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            if (bmp != null) {
                                val scaled = android.graphics.Bitmap.createScaledBitmap(bmp, attemptWidth, attemptHeight, true)
                                if (scaled != bmp) bmp.recycle()
                                val baos = java.io.ByteArrayOutputStream()
                                scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, baos)
                                // Replace the image used in the JSON.
                                retryImage = android.util.Base64.encodeToString(baos.toByteArray(), android.util.Base64.NO_WRAP)
                            }
                        } catch (_: Exception) {}
                    }
                    updateState(GenerationState.Progress(0f))
                    continue@attemptLoop
                }
                // et.44-patch: not a confirmed engine death. Treat as a transient
                // transport/pipeline hiccup on the STILL-LIVE engine: reconnect once
                // with a fresh connection (et.44 already uses ConnectionPool(0)+close).
                // Never restart the process for this; after one retry, surface the error.
                val msg = (e.message ?: "")
                val transportHiccup = msg.contains(
                    "read interrupted", true,
                ) || msg.contains("connection reset", true) ||
                    msg.contains("broken pipe", true) ||
                    msg.contains("unexpected end", true) ||
                    msg.contains("timeout", true)
                if (transportHiccup && !connRetryDone && !cancelRequested) {
                    connRetryDone = true
                    io.github.xororz.localdream.cloud.LogHub.log(
                        io.github.xororz.localdream.cloud.LogHub.Category.GENERATE,
                        "Transport hiccup on live engine, reconnecting once: ${e.message}",
                    )
                    // Brief settle so the engine's accept loop is ready again.
                    Thread.sleep(600)
                    continue@attemptLoop
                }
                throw e
            }
            } // attemptLoop
        } catch (e: Exception) {
            if (completed) {
                // Result already delivered; a teardown exception from the
                // closing socket must not overwrite the Complete state.
                Log.d("GenerationService", "post-completion teardown: ${e.message}")
            } else if (cancelRequested) {
                // User interrupted: the cancelled call throws on its blocked
                // read; this is the expected exit, not an error.
                Log.d("GenerationService", "generation cancelled")
                updateState(GenerationState.Idle)
            } else {
                Log.e("GenerationService", "generation error", e)
                // et.51: do NOT blanket "内存不足". Use the real exit code / backend.
                val code = io.github.xororz.localdream.utils.EngineExitInfo.lastExitCode
                val msg = when {
                    code == 137 && engineBackendType == "sd15cpu" ->
                        getString(R.string.err_engine_oom_killed)
                    code == 137 && engineBackendType != "sd15cpu" ->
                        // et.54: 137 on an accelerator after backgrounding is usually the
                        // system pausing/killing the app, not a NPU-phone OOM.
                        "引擎被系统结束(code=137)：切后台会暂停 NPU 生成，请保持 App 在前台、关闭电池优化/加锁后重试；详见 engine_*.log 末行。"
                    code in setOf(134, 139) -> {
                        val why = when (code) {
                            134 -> "NPU/加速引擎初始化失败(SIGABRT)"
                            else -> "NPU/加速引擎崩溃(段错误 code=139)"
                        }
                        "$why，模型与加速方式可能不兼容；请改用 CPU Models 标签下的对应模型，详见 engine_*.log。"
                    }
                    code == 0 && (e.message ?: "").contains("bad_alloc", true) ->
                        "引擎内存分配失败(bad_alloc，常见于 NPU 包与本机芯片/驱动不匹配)，已自动降档仍失败；请改用 CPU Models 标签下的对应模型，详见 engine_*.log。"
                    else ->
                        "生成失败(exit=$code)：${e.message ?: "原生退出"}，详见 engine_*.log 末行。"
                }
                updateState(GenerationState.Error(msg))
            }
            stopSelf()
        } finally {
            activeCall = null
            io.github.xororz.localdream.utils.BgmManager.stop(this@BackgroundGenerationService)
            io.github.xororz.localdream.service.BackendService.notifyGenerationEnded()
            // et.57: release the idle engine when the LAN "API 连接" service is NOT running,
            // so the engine does not keep holding memory after a normal local generation.
            // (If the user regenerates within the idle grace window, requestStart cancels
            // the stop and reuses the resident engine.)
            if (!io.github.xororz.localdream.service.LanImageService.isRunning.value) {
                runCatching {
                    startService(
                        android.content.Intent(
                            this@BackgroundGenerationService,
                            io.github.xororz.localdream.service.BackendService::class.java,
                        ).apply { action = io.github.xororz.localdream.service.BackendService.ACTION_STOP }
                    )
                }
            }
        }
    }

    // Expands packed RGB bytes into ARGB ints; stops at whichever buffer ends
    // first so a short payload can never index out of bounds.
    private fun rgbBytesToPixels(rgb: ByteArray, pixels: IntArray) {
        val count = minOf(pixels.size, rgb.size / 3)
        for (i in 0 until count) {
            val index = i * 3
            val r = rgb[index].toInt() and 0xFF
            val g = rgb[index + 1].toInt() and 0xFF
            val b = rgb[index + 2].toInt() and 0xFF
            pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
    }

    private fun rgbaBytesToPixels(rgba: ByteArray, pixels: IntArray) {
        val count = minOf(pixels.size, rgba.size / 4)
        for (i in 0 until count) {
            val index = i * 4
            val r = rgba[index].toInt() and 0xFF
            val g = rgba[index + 1].toInt() and 0xFF
            val b = rgba[index + 2].toInt() and 0xFF
            val a = rgba[index + 3].toInt() and 0xFF
            pixels[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }
    }

    private fun createNotificationChannel() {
        val name = getString(R.string.gen_channel_name)
        val descriptionText = getString(R.string.gen_channel_desc)
        val importance = NotificationManager.IMPORTANCE_LOW
        val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
            description = descriptionText
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun createNotification(progress: Float): Notification {
        val openAppIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(this.getString(R.string.generating_notify))
            .setContentText(getString(R.string.notify_progress, (progress * 100).toInt()))
            .setProgress(100, (progress * 100).toInt(), false)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(progress: Float) {
        // The system rate-limits notification updates; posting one per
        // diffusion step just gets dropped, so throttle to ~2 per second.
        val now = SystemClock.elapsedRealtime()
        if (now - lastProgressNotifyAt < 500) return
        lastProgressNotifyAt = now
        notificationManager.notify(NOTIFICATION_ID, createNotification(progress))
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTimeout(startId: Int) {
        super.onTimeout(startId)
        handleTimeout(0)
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        super.onTimeout(startId, fgsType)
        handleTimeout(fgsType)
    }

    private fun handleTimeout(fgsType: Int) {
        Log.e("GenerationService", "Foreground service timeout (fgsType=$fgsType)")
        updateState(GenerationState.Error("Service timeout"))
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        activeCall?.cancel()
        serviceScope.cancel()
        releaseWakeLock()
        io.github.xororz.localdream.utils.OverlayKeepAlive.hide(this)

        if (_generationState.value is GenerationState.Error) {
            resetState()
        }

        _isServiceRunning.value = false
        Log.d("GenerationService", "service destroyed, isServiceRunning set to false")
    }
}
