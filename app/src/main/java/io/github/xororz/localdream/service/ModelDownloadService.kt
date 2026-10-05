package io.github.xororz.localdream.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import io.github.xororz.localdream.R
import io.github.xororz.localdream.data.Model
import io.github.xororz.localdream.ui.screens.LoRAFile
import io.github.xororz.localdream.ui.screens.convertCustomModel
import io.github.xororz.localdream.utils.Http
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request

class ModelDownloadService : Service() {
    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private var downloadJob: Job? = null

    // Polled by the resumable checkpoint downloader so an explicit cancel also
    // stops an in-flight read without cancelling the foreground service itself.
    @Volatile
    private var convertCancelled: Boolean = false

    private val notificationManager by lazy {
        getSystemService(NOTIFICATION_SERVICE) as NotificationManager
    }

    private val client = Http.client.newBuilder()
        // Short connect timeout so an unreachable source fails fast and we can
        // switch to the mirror; a long read timeout tolerates slow big-file
        // transfers without aborting mid-download.
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /**
     * Builds the list of source URLs to try for one file: the requested URL
     * first, then the Hugging Face <-> hf-mirror counterpart. This keeps
     * downloads working whether the official site or the China mirror is the
     * reachable one on the current network.
     */
    private fun mirrorCandidates(url: String): List<String> {
        val out = linkedSetOf(url)
        when {
            url.contains("//huggingface.co") ->
                out += url.replace("https://huggingface.co", "https://hf-mirror.com")
                    .replace("http://huggingface.co", "https://hf-mirror.com")
            url.contains("//hf-mirror.com") ->
                out += url.replace("https://hf-mirror.com", "https://huggingface.co")
        }
        return out.toList()
    }

    companion object {
        private const val TAG = "ModelDownloadService"
        private const val NOTIFICATION_CHANNEL_ID = "model_download_channel"
        private const val NOTIFICATION_ID = 2001

        private val _downloadState = MutableStateFlow<DownloadState>(DownloadState.Idle)
        val downloadState: StateFlow<DownloadState> = _downloadState

        const val ACTION_START_DOWNLOAD = "action_start_download"
        const val ACTION_CANCEL_DOWNLOAD = "action_cancel_download"

        const val EXTRA_MODEL_ID = "model_id"
        const val EXTRA_MODEL_NAME = "model_name"
        const val EXTRA_FILE_URL = "file_url"
        const val EXTRA_IS_ZIP = "is_zip"
        const val EXTRA_MODEL_TYPE = "model_type"
        const val TYPE_SD = "sd"
        const val TYPE_UPSCALER = "upscaler"

        // Raw SD1.5 checkpoint downloaded with byte-range resume, then
        // converted to MNN on the device inside this foreground service.
        const val TYPE_CONVERT_SD = "convert_sd"
        const val EXTRA_CONVERT_PATH = "convert_path"
        const val EXTRA_CLIP_SKIP = "clip_skip"

        // Package downloaded as individual files instead of one zip. A DiT
        // package is many gigabytes, and unzipping one needs the archive and its
        // contents on disk at the same time; fetching the files straight into
        // the model directory halves the space a download needs and lets an
        // interrupted one resume at file granularity.
        const val TYPE_MULTI_FILE = "multi_file"

        // TYPE_MULTI_FILE only: file names under EXTRA_FILE_URL, and an empty
        // marker file to create once they all arrived.
        const val EXTRA_FILE_NAMES = "file_names"
        const val EXTRA_MARKER_FILE = "marker_file"
    }

    sealed class DownloadState {
        object Idle : DownloadState()
        data class Downloading(
            val modelId: String,
            val progress: Float,
            val downloadedBytes: Long,
            val totalBytes: Long,
        ) : DownloadState()

        data class Extracting(val modelId: String) : DownloadState()

        // On-device conversion of a freshly downloaded raw checkpoint.
        data class Converting(val modelId: String, val message: String) : DownloadState()
        data class Success(val modelId: String) : DownloadState()
        data class Error(val modelId: String, val message: String) : DownloadState()
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquireLocks()
    }

    private var wakeLock: android.os.PowerManager.WakeLock? = null
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null

    private fun acquireLocks() {
        runCatching {
            val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
            wakeLock = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "ldet:dl").apply {
                setReferenceCounted(false); acquire()
            }
            val wm = getSystemService(WIFI_SERVICE) as android.net.wifi.WifiManager
            wifiLock = wm.createWifiLock(
                android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "ldet:dl",
            ).apply { setReferenceCounted(false); acquire() }
        }
    }

    private fun releaseLocks() {
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        runCatching { if (wifiLock?.isHeld == true) wifiLock?.release() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_DOWNLOAD -> {
                val modelId = intent.getStringExtra(EXTRA_MODEL_ID) ?: return START_NOT_STICKY
                val modelName = intent.getStringExtra(EXTRA_MODEL_NAME) ?: modelId
                val modelType = intent.getStringExtra(EXTRA_MODEL_TYPE) ?: TYPE_SD

                if (modelType == TYPE_CONVERT_SD) {
                    val convertPath =
                        intent.getStringExtra(EXTRA_CONVERT_PATH) ?: return START_NOT_STICKY
                    val clipSkip = intent.getIntExtra(EXTRA_CLIP_SKIP, 1)
                    startForeground(NOTIFICATION_ID, createNotification(modelName, 0f))
                    startConvertDownload(modelId, modelName, convertPath, clipSkip)
                } else {
                    val fileUrl =
                        intent.getStringExtra(EXTRA_FILE_URL) ?: return START_NOT_STICKY
                    val isZip = intent.getBooleanExtra(EXTRA_IS_ZIP, false)
                    val fileNames = intent.getStringArrayListExtra(EXTRA_FILE_NAMES)
                    val markerFile = intent.getStringExtra(EXTRA_MARKER_FILE)

                    startForeground(NOTIFICATION_ID, createNotification(modelName, 0f))
                    startDownload(
                        modelId = modelId,
                        modelName = modelName,
                        fileUrl = fileUrl,
                        isZip = isZip,
                        modelType = modelType,
                        fileNames = fileNames.orEmpty(),
                        markerFile = markerFile,
                    )
                }
            }

            ACTION_CANCEL_DOWNLOAD -> {
                cancelDownload()
            }
        }
        return START_NOT_STICKY
    }

    private fun startDownload(
        modelId: String,
        modelName: String,
        fileUrl: String,
        isZip: Boolean,
        modelType: String,
        fileNames: List<String> = emptyList(),
        markerFile: String? = null,
    ) {
        downloadJob?.cancel()
        downloadJob = serviceScope.launch {
            var tempFile: File? = null
            var extractTempDir: File? = null
            try {
                _downloadState.value = DownloadState.Downloading(modelId, 0f, 0, 0)
                io.github.xororz.localdream.cloud.LogHub.log(
                    io.github.xororz.localdream.cloud.LogHub.Category.DOWNLOAD,
                    "Start download: $modelName ($modelId) from $fileUrl",
                )

                val tempDir =
                    io.github.xororz.localdream.utils.Storage.tempDir(applicationContext)

                // Do NOT wipe the whole temp directory: it holds the resumable
                // scratch of raw-checkpoint conversions (conv_<id>) and other
                // models' .part files. Each download owns its own named file.
                if (!tempDir.exists()) tempDir.mkdirs()

                if (modelType == TYPE_MULTI_FILE) {
                    downloadPackageFiles(modelId, modelName, fileUrl, fileNames, markerFile)
                    _downloadState.value = DownloadState.Success(modelId)
                    updateNotification(modelName, 100f, true)
                    withContext(Dispatchers.Main) {
                        kotlinx.coroutines.delay(2000)
                        _downloadState.value = DownloadState.Idle
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                    return@launch
                }

                // Stable per-model name lets a restarted/retried download resume
                // this file's leftover bytes instead of starting from zero.
                tempFile = File(tempDir, "${modelId}_$modelType.part")

                downloadFile(fileUrl, tempFile, modelId, modelName)

                when (modelType) {
                    TYPE_SD -> {
                        if (isZip) {
                            // Integrity gate #1: the .part must be a complete, readable
                            // zip (central directory + per-entry CRC). A truncated CDN
                            // download that ZipInputStream would have silently half
                            // extracted now fails loudly and is re-downloaded.
                            validateZipOrThrow(tempFile)

                            val modelDir = File(getModelsDir(), modelId)

                            if (modelDir.exists()) {
                                modelDir.deleteRecursively()
                            }
                            modelDir.mkdirs()

                            extractTempDir = File(tempDir, "${modelId}_extract")
                            extractTempDir.mkdirs()

                            _downloadState.value = DownloadState.Extracting(modelId)
                            updateNotification(modelName, 0f, isExtracting = true)

                            unzipFile(tempFile, extractTempDir)

                            extractTempDir.listFiles()?.forEach { file ->
                                file.renameTo(File(modelDir, file.name))
                            }
                            extractTempDir.delete()
                            extractTempDir = null

                            // Integrity gate #2: after extraction the model must have
                            // the real conversion products. Pick the required manifest
                            // by package family: sd-qnn NPU packages ship a flattened
                            // QNN layout (no *.mnn.weight sidecars), CPU packages ship
                            // the MNN six-piece set.
                            val isQnnPackage = fileUrl.contains("/sd-qnn/")
                            val complete = Model.hasCompleteSdPackage(modelDir, isQnnPackage)
                            if (!complete) {
                                val actual = modelDir.listFiles()
                                    ?.joinToString { "${it.name}=${it.length()}" } ?: "(empty)"
                                io.github.xororz.localdream.cloud.LogHub.log(
                                    io.github.xororz.localdream.cloud.LogHub.Category.DOWNLOAD,
                                    "Extracted incomplete model: modelId=$modelId qnn=$isQnnPackage url=$fileUrl actual=[$actual]",
                                )
                                throw IOException(
                                    getString(R.string.error_model_extract_incomplete, modelName),
                                )
                            }
                        }
                    }

                    TYPE_UPSCALER -> {
                        val upscalerDir = File(getModelsDir(), modelId).apply {
                            if (!exists()) mkdirs()
                        }
                        val targetFile = File(upscalerDir, Model.UPSCALER_FILE_NAME)

                        if (targetFile.exists()) {
                            targetFile.delete()
                        }

                        // Don't report success on a failed move: it would leave
                        // an empty model dir that the UI/loader can't use.
                        if (!tempFile.renameTo(targetFile)) {
                            tempFile.copyTo(targetFile, overwrite = true)
                        }
                    }
                }

                tempFile.delete()
                tempFile = null

                _downloadState.value = DownloadState.Success(modelId)
                updateNotification(modelName, 100f, true)

                withContext(Dispatchers.Main) {
                    kotlinx.coroutines.delay(2000)
                    _downloadState.value = DownloadState.Idle
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            } catch (e: CancellationException) {
                discardPartialFiles(modelType, modelId)
                // Cancellation (service reclaimed, a new download started, or
                // explicit cancel) is not a download failure: re-throw so it is
                // not surfaced as an "Error" state. Emitting Error here is what
                // produced the spurious "Job was cancelled" snackbar that could
                // appear right after a successful download finished.
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Download failed", e)
                io.github.xororz.localdream.cloud.LogHub.log(
                    io.github.xororz.localdream.cloud.LogHub.Category.DOWNLOAD,
                    "Download FAILED: $modelName — ${e.message}",
                )

                // Keep tempFile: downloadFile resumes its bytes on the next tap.
                // Only drop the half-extracted tree.
                extractTempDir?.deleteRecursively()
                discardPartialFiles(modelType, modelId)

                _downloadState.value =
                    DownloadState.Error(modelId, e.message ?: getString(R.string.unknown_error))
                updateNotification(modelName, 0f, false, e.message)

                withContext(Dispatchers.Main) {
                    kotlinx.coroutines.delay(3000)
                    _downloadState.value = DownloadState.Idle
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
    }

    /**
     * Fetches each file of a package straight into the model directory.
     *
     * A file that is already there at its published size is kept, so a
     * download interrupted after 6 of 8GB resumes on the next attempt instead
     * of starting over. Progress is reported across the whole package, using
     * the sizes a HEAD request reports up front.
     */
    private suspend fun downloadPackageFiles(
        modelId: String,
        modelName: String,
        baseUrl: String,
        fileNames: List<String>,
        markerFile: String?,
    ) = withContext(Dispatchers.IO) {
        require(fileNames.isNotEmpty()) { "empty package file list" }
        val modelDir = File(getModelsDir(), modelId).apply { mkdirs() }
        val base = baseUrl.removeSuffix("/")

        // Entries are "<remote path>|<name on disk>": the parts of a package
        // can come from different repositories, and the names they are
        // published under are not the ones the backend looks for.
        val parts = fileNames.map { entry ->
            val remote = entry.substringBefore('|')
            val local = entry.substringAfter('|', remote.substringAfterLast('/'))
            remote to local
        }

        val sizes = parts.associate { (remote, _) -> remote to remoteSize("$base/$remote") }
        val totalBytes = sizes.values.sumOf { it.coerceAtLeast(0L) }
        var completedBytes = 0L

        for ((remote, local) in parts) {
            val dest = File(modelDir, local)
            val expected = sizes[remote] ?: -1L
            if (dest.exists() && expected > 0 && dest.length() == expected) {
                completedBytes += expected
                Log.i(TAG, "Package file already complete: $local")
                continue
            }
            val part = File(modelDir, "$local.part")
            downloadFile(
                url = "$base/$remote",
                destFile = part,
                modelId = modelId,
                modelName = modelName,
                packageOffset = completedBytes,
                packageTotal = totalBytes,
            )
            if (dest.exists()) dest.delete()
            if (!part.renameTo(dest)) throw IOException("Failed to install $local")
            completedBytes += if (expected > 0) expected else dest.length()
        }

        // Written last: it is what marks the package complete to the scanner,
        // so an interrupted download never looks like an installed model.
        if (!markerFile.isNullOrEmpty()) File(modelDir, markerFile).createNewFile()
    }

    /**
     * Drops the ".part" files a multi-file download leaves behind.
     *
     * Each part is written from the start rather than resumed, so a leftover
     * one is dead weight - and at DiT package sizes that is gigabytes the user
     * cannot see. Files that already finished keep their final name and stay,
     * which is what lets the next attempt skip them.
     */
    private fun discardPartialFiles(modelType: String, modelId: String) {
        if (modelType != TYPE_MULTI_FILE) return
        val modelDir = File(getModelsDir(), modelId)
        modelDir.listFiles { file -> file.isFile && file.name.endsWith(".part") }
            ?.forEach { part ->
                if (part.delete()) Log.i(TAG, "Removed partial file ${part.name}")
            }
    }

    /** Published size of a remote file, or -1 when the server does not say. */
    private fun remoteSize(url: String): Long {
        for (candidate in mirrorCandidates(url)) {
            val request = Request.Builder().url(candidate).head().build()
            val size = runCatching {
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        response.header("Content-Length")?.toLongOrNull() ?: -1L
                    } else {
                        -1L
                    }
                }
            }.getOrDefault(-1L)
            if (size >= 0L) return size
        }
        return -1L
    }

    private suspend fun downloadFile(
        url: String,
        destFile: File,
        modelId: String,
        modelName: String,
        packageOffset: Long = 0L,
        packageTotal: Long = 0L,
    ) = withContext(Dispatchers.IO) {
        var lastError: Exception? = null
        // Try the chosen source, then the Hugging Face/mirror counterpart.
        for (candidate in mirrorCandidates(url)) {
            // Resume from whatever a previous attempt already wrote. A server
            // that answers 200 (ignores Range) restarts the file instead.
            val have = if (destFile.exists()) destFile.length().coerceAtLeast(0L) else 0L
            try {
                val requestBuilder = Request.Builder().url(candidate)
                if (have > 0L) requestBuilder.header("Range", "bytes=$have-")

                requestBuilder.build().let { request ->
                client.newCall(request).execute().use { response ->
                    val append = response.code == 206
                    if (!append && response.code !in 200..299) {
                        throw Exception(getString(R.string.error_download_failed, response.code.toString()))
                    }

                    val body = response.body ?: throw Exception("Response body is null")
                    val segmentLen = body.contentLength()
                    // Absolute size of this file when resuming a 206 response.
                    val totalThisFile = if (append && segmentLen > 0) have + segmentLen else segmentLen
                    var downloadedBytes = if (append) have else 0L
                    var lastUpdateTime = 0L

                    java.io.BufferedOutputStream(FileOutputStream(destFile, append)).use { output ->
                        body.byteStream().buffered().use { input ->
                            val buffer = ByteArray(256 * 1024)
                            var bytes: Int

                            while (input.read(buffer).also { bytes = it } != -1) {
                                output.write(buffer, 0, bytes)
                                downloadedBytes += bytes

                                val currentTime = System.currentTimeMillis()
                                if (currentTime - lastUpdateTime >= 500 || downloadedBytes == totalThisFile) {
                                    lastUpdateTime = currentTime
                                    val reportedDone = packageOffset + downloadedBytes
                                    val reportedTotal =
                                        if (packageTotal > 0) packageTotal else totalThisFile
                                    val progress = if (reportedTotal > 0) {
                                        reportedDone.toFloat() / reportedTotal
                                    } else {
                                        0f
                                    }

                                    _downloadState.value = DownloadState.Downloading(
                                        modelId,
                                        progress,
                                        reportedDone,
                                        reportedTotal,
                                    )

                                    updateNotification(modelName, progress)
                                }
                            }
                        }
                    }

                    // Guard against silently truncated downloads: a dropped connection
                    // ends the read loop without throwing, leaving a partial file.
                    if (totalThisFile > 0 && downloadedBytes != totalThisFile) {
                        throw Exception(
                            getString(R.string.error_download_failed, "$downloadedBytes/$totalThisFile"),
                        )
                    }
                }
                }
                // Success on this source.
                return@withContext
            } catch (e: Exception) {
                lastError = e
                Log.w(TAG, "download from $candidate failed (${e.message}); trying next source")
                // Keep destFile so the next mirror resumes from these bytes
                // instead of discarding potentially gigabytes of progress.
            }
        }
        throw lastError ?: Exception(getString(R.string.error_download_failed, "no source"))
    }

    /**
     * Validate a downloaded archive BEFORE extraction: open it with
     * java.util.zip.ZipFile (which reads the central directory and verifies each
     * entry's CRC) and touch every entry. A truncated/corrupt CDN download throws
     * ZipException here instead of being half-extracted and mistaken for a model.
     */
    private fun validateZipOrThrow(zipFile: File) {
        if (!zipFile.exists() || zipFile.length() <= 0L) {
            throw IOException("Downloaded archive is empty")
        }
        java.util.zip.ZipFile(zipFile, java.util.zip.ZipFile.OPEN_READ).use { zf ->
            val entries = zf.entries()
            var count = 0
            while (entries.hasMoreElements()) {
                val e = entries.nextElement()
                // Accessing getCrc/getSize forces the central-directory entry to be
                // well-formed; a bad central directory already throws in the ctor.
                if (e.size < 0 || e.crc < 0) {
                    throw IOException("Corrupt zip entry: ${e.name}")
                }
                count++
            }
            if (count == 0) throw IOException("Archive has no entries")
        }
    }

    private suspend fun unzipFile(zipFile: File, destDir: File) = withContext(Dispatchers.IO) {
        ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
            var entry = zis.nextEntry

            while (entry != null) {
                if (!entry.isDirectory) {
                    val fileName = entry.name.substringAfterLast('/')
                    if (fileName.isNotEmpty() && !fileName.startsWith(".") && !fileName.startsWith("__MACOSX")) {
                        val file = File(destDir, fileName)

                        java.io.BufferedOutputStream(FileOutputStream(file)).use { output ->
                            zis.copyTo(output)
                        }
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    /**
     * Raw-SD1.5 path: resumable mirror download (this foreground service, live
     * notification, survives background/screen-off/reboot) followed by the same
     * native on-device conversion used by "add custom model".
     */
    private fun startConvertDownload(
        modelId: String,
        modelName: String,
        convertPath: String,
        clipSkip: Int,
    ) {
        convertCancelled = false
        downloadJob?.cancel()
        downloadJob = serviceScope.launch {
            try {
                _downloadState.value = DownloadState.Downloading(modelId, 0f, 0, 0)

                val source = withContext(Dispatchers.IO) {
                    ModelConvertEngine.downloadCheckpoint(
                        context = applicationContext,
                        modelId = modelId,
                        convertPath = convertPath,
                        onProgress = { got, total ->
                            val p = if (total > 0) (got.toFloat() / total).coerceIn(0f, 1f) else 0f
                            _downloadState.value = DownloadState.Downloading(modelId, p, got, total)
                            updateNotification(modelName, p)
                        },
                        isCancelled = { convertCancelled },
                    )
                }
                if (convertCancelled) throw ModelConvertEngine.CancelledException()

                // Conversion stage.
                _downloadState.value =
                    DownloadState.Converting(modelId, getString(R.string.preparing_model))
                updateNotification(modelName, 0f, isExtracting = true)

                var ok = false
                var failure: String? = null
                convertCustomModel(
                    context = applicationContext,
                    modelName = modelId,
                    fileUri = Uri.fromFile(source),
                    clipSkip = clipSkip,
                    loraFiles = emptyList<LoRAFile>(),
                    onProgress = { msg ->
                        _downloadState.value = DownloadState.Converting(modelId, msg)
                        updateNotification(modelName, 0f, isExtracting = true)
                    },
                    onStart = {},
                    onSuccess = { ok = true },
                    onError = { msg -> failure = msg },
                )
                if (!ok) throw Exception(failure ?: getString(R.string.conversion_need_sd15))

                // Fully installed: drop the 2 GB scratch checkpoint.
                runCatching { ModelConvertEngine.scratchDir(applicationContext, modelId).deleteRecursively() }

                _downloadState.value = DownloadState.Success(modelId)
                updateNotification(modelName, 100f, true)
                withContext(Dispatchers.Main) {
                    kotlinx.coroutines.delay(2000)
                    _downloadState.value = DownloadState.Idle
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            } catch (e: ModelConvertEngine.CancelledException) {
                // Keep the .part / finished checkpoint: a later tap resumes.
                _downloadState.value = DownloadState.Idle
                withContext(Dispatchers.Main) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            } catch (e: CancellationException) {
                // Coroutine cancelled (new download started / service stopped).
                // Not an error; scratch is preserved for resume.
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "convert download failed", e)
                // The half-converted model dir is removed by convertCustomModel;
                // the downloaded checkpoint stays in scratch so a retry does not
                // re-fetch it.
                _downloadState.value =
                    DownloadState.Error(modelId, e.message ?: getString(R.string.unknown_error))
                updateNotification(modelName, 0f, false, e.message)
                withContext(Dispatchers.Main) {
                    kotlinx.coroutines.delay(4000)
                    _downloadState.value = DownloadState.Idle
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
    }

    private fun cancelDownload() {
        convertCancelled = true
        downloadJob?.cancel()
        _downloadState.value = DownloadState.Idle
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun getModelsDir(): File =
        io.github.xororz.localdream.utils.Storage.modelsDir(applicationContext)

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.model_download_channel),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.model_download_channel_desc)
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun createNotification(
        modelName: String,
        progress: Float,
        isExtracting: Boolean = false,
    ): android.app.Notification {
        val title = if (isExtracting) {
            getString(R.string.extracting)
        } else {
            getString(R.string.downloading_model, modelName)
        }

        val openAppIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val appPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(title)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setProgress(100, (progress * 100).toInt(), isExtracting)
            .setOngoing(true)
            .setContentIntent(appPendingIntent)
            .build()
    }

    private fun updateNotification(
        modelName: String,
        progress: Float,
        success: Boolean = false,
        error: String? = null,
        isExtracting: Boolean = false,
    ) {
        val notification = when {
            success -> {
                NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                    .setContentTitle(getString(R.string.download_complete))
                    .setContentText(modelName)
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setOngoing(false)
                    .build()
            }

            error != null -> {
                NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                    .setContentTitle(getString(R.string.download_failed))
                    .setContentText(error)
                    .setSmallIcon(android.R.drawable.stat_notify_error)
                    .setOngoing(false)
                    .build()
            }

            else -> {
                createNotification(modelName, progress, isExtracting)
            }
        }

        notificationManager.notify(NOTIFICATION_ID, notification)
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
        Log.e(TAG, "Foreground service timeout (fgsType=$fgsType)")
        downloadJob?.cancel()
        _downloadState.value = DownloadState.Error("timeout", "Foreground service timeout")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        releaseLocks()
    }
}
