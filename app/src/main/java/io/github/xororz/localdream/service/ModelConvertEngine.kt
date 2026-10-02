package io.github.xororz.localdream.service

import android.content.Context
import android.util.Log
import io.github.xororz.localdream.data.GenerationPreferences
import io.github.xororz.localdream.utils.Storage
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Resumable, mirror-failover downloader for raw SD1.5 checkpoints that are
 * converted on device. Kept independent from the (UI-driven) conversion itself
 * so it can run inside [ModelDownloadService], a foreground service.
 *
 * Robustness:
 *  - partial bytes live on persistent shared storage
 *    (LocalDreamET/temp_downloads/conv_<id>/source.safetensors.part) and resume
 *    with HTTP Range after a connection drop, process death or reboot;
 *  - sources tried in order: user-preferred host, hf-mirror, ModelScope (CN),
 *    official Hugging Face;
 *  - truncated transfers are detected and retried from the next source;
 *  - the fully downloaded file is only renamed into place once its size matches
 *    the server-reported length.
 */
object ModelConvertEngine {
    private const val TAG = "ModelConvertEngine"
    private const val UA =
        "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/124.0 Mobile Safari/537.36"

    class CancelledException : Exception("cancelled")

    /** Ordered download URLs for a convert path, exposed for the import precheck. */
    fun probeCandidateUrls(context: Context, path: String): List<String> =
        candidateUrls(context, path)

    /** Directory holding a model's resumable download scratch. */
    fun scratchDir(context: Context, modelId: String): File =
        File(Storage.tempDir(context), "conv_${modelId.replace(" ", "")}")
            .apply { mkdirs() }

    /** Fully-downloaded checkpoint for [modelId], or null when not present. */
    fun completedSource(context: Context, modelId: String): File? {
        val f = File(scratchDir(context, modelId), "source.safetensors")
        return if (f.isFile && f.length() > 0L) f else null
    }

    /**
     * @param convertPath repository-relative path,
     *   e.g. "digiplay/Juggernaut_final/resolve/main/juggernaut_final.safetensors"
     */
    @Throws(Exception::class)
    fun downloadCheckpoint(
        context: Context,
        modelId: String,
        convertPath: String,
        onProgress: (got: Long, total: Long) -> Unit,
        isCancelled: () -> Boolean,
    ): File {
        completedSource(context, modelId)?.let {
            onProgress(it.length(), it.length())
            return it
        }

        val dir = scratchDir(context, modelId)
        val partFile = File(dir, "source.safetensors.part")
        val fullFile = File(dir, "source.safetensors")

        var lastError: String? = null
        for (url in candidateUrls(context, convertPath)) {
            if (isCancelled()) throw CancelledException()
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000
                    readTimeout = 120_000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", UA)
                    setRequestProperty("Accept", "*/*")
                }
                val have = if (partFile.exists()) partFile.length() else 0L
                var resumed = false
                if (have > 0L) conn.setRequestProperty("Range", "bytes=$have-")
                conn.connect()

                when (val code = conn.responseCode) {
                    in 200..299 -> if (have > 0L) partFile.delete()
                    HttpURLConnection.HTTP_PARTIAL -> resumed = true
                    else -> {
                        lastError = "HTTP $code"
                        conn.disconnect()
                        continue
                    }
                }

                val remaining = conn.contentLengthLong.coerceAtLeast(-1L)
                val total = if (resumed && remaining >= 0L) have + remaining else remaining
                var got = if (resumed) have else 0L
                var lastUi = 0L

                FileOutputStream(partFile, resumed).use { out ->
                    conn.inputStream.use { input ->
                        val buf = ByteArray(256 * 1024)
                        while (true) {
                            if (isCancelled()) throw CancelledException()
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            got += n
                            val now = System.currentTimeMillis()
                            if (now - lastUi >= 400) {
                                lastUi = now
                                onProgress(got, total)
                            }
                        }
                        out.flush()
                    }
                }
                conn.disconnect()

                if (total > 0L && partFile.length() != total) {
                    lastError = "truncated ${partFile.length()}/$total"
                    Log.w(TAG, "$url truncated: ${partFile.length()}/$total")
                    continue
                }
                if (partFile.length() <= 0L) {
                    lastError = "empty file"
                    continue
                }
                if (fullFile.exists()) fullFile.delete()
                if (!partFile.renameTo(fullFile)) {
                    partFile.copyTo(fullFile, overwrite = true)
                    partFile.delete()
                }
                onProgress(fullFile.length(), total)
                return fullFile
            } catch (e: CancelledException) {
                throw e
            } catch (e: Exception) {
                lastError = e.message
                Log.w(TAG, "download from $url failed: ${e.message}")
                // Keep partFile so the next mirror/resume continues from here.
            } finally {
                runCatching { conn?.disconnect() }
            }
        }
        throw IOException(
            context.getString(
                io.github.xororz.localdream.R.string.model_download_failed,
                lastError ?: "network",
            ),
        )
    }

    private fun candidateUrls(context: Context, path: String): List<String> {
        val p = path.removePrefix("/")
        // A user-supplied absolute direct link (any host) is used verbatim; it is
        // not something we can re-base onto the configured mirrors.
        if (p.startsWith("http://") || p.startsWith("https://")) return listOf(p)
        val prefs = GenerationPreferences(context)
        val source = kotlinx.coroutines.runBlocking { prefs.getSelectedSource() }
        val customBase = kotlinx.coroutines.runBlocking { prefs.getBaseUrl() }.trimEnd('/')
        val repo = p.substringBefore("/resolve/")
        val file = p.substringAfter("/resolve/main/", "")

        val hfMirror = "https://hf-mirror.com/$p"
        val official = "https://huggingface.co/$p"
        val modelScope = if (repo.isNotEmpty() && file.isNotEmpty()) {
            val enc = URLEncoder.encode(file, "UTF-8").replace("+", "%20")
            "https://modelscope.cn/api/v1/models/$repo/repo?Revision=master&FilePath=$enc"
        } else {
            ""
        }
        val custom = if (customBase.isNotEmpty() &&
            !customBase.contains("hf-mirror.com") &&
            !customBase.contains("huggingface.co") &&
            !customBase.contains("modelscope.cn")
        ) "$customBase/$p" else ""

        // Primary first per the user's setting; the rest stay as automatic
        // fallbacks so a source that is slow/down for one file never blocks it.
        val ordered = linkedSetOf<String>()
        when (source) {
            "modelscope" -> {
                if (modelScope.isNotEmpty()) ordered += modelScope
                ordered += hfMirror
                ordered += official
                if (custom.isNotEmpty()) ordered += custom
            }
            "huggingface" -> {
                ordered += official
                ordered += hfMirror
                if (modelScope.isNotEmpty()) ordered += modelScope
                if (custom.isNotEmpty()) ordered += custom
            }
            "custom" -> {
                if (custom.isNotEmpty()) ordered += custom
                if (modelScope.isNotEmpty()) ordered += modelScope
                ordered += hfMirror
                ordered += official
            }
            else -> { // hf-mirror
                ordered += hfMirror
                if (modelScope.isNotEmpty()) ordered += modelScope
                ordered += official
                if (custom.isNotEmpty()) ordered += custom
            }
        }
        return ordered.toList()
    }
}
