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
 * et.117 robustness rewrite (fixes the "99% -> HTTP 404" failure):
 *  - before any GET we issue a HEAD to the FINAL url and pin the authoritative
 *    Content-Length (total). A mirror that does not answer HEAD / no length is
 *    skipped with the HTTP code;
 *  - an existing `.part` is validated against the pinned total (and against the
 *    total persisted last time). A stale/over-long/changed scratch is deleted
 *    and restarted from 0 instead of blindly appending to it;
 *  - the Range header is EXACT: `bytes=<have>-<total-1>`, never open-ended;
 *  - a 404 / 416 on the range request, or a 200 that ignores our Range, drops
 *    the `.part` and re-fetches the whole file fresh (up to 2 restarts, each
 *    preceded by a fresh HEAD) - this is what unsticks a download that died at
 *    99% because a CDN/LFS redirect refused the byte-range;
 *  - after the stream ends we hard-gate on `file.length() == total`; only then
 *    is the `.part` renamed into place. Anything short is deleted and retried;
 *  - progress (got/total) is persisted to SharedPreferences so a process death
 *    or reboot resumes from the pinned numbers;
 *  - the caller only reaches the unzip/convert stage once a file of exactly
 *    `total` bytes exists (size gate + safetensors header check).
 */
object ModelConvertEngine {
    private const val TAG = "ModelConvertEngine"
    private const val UA =
        "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/124.0 Mobile Safari/537.36"

    private const val PROGRESS_PREFS = "model_download_progress"

    // How many times we delete the `.part` and re-fetch the whole file after a
    // 404/416 range rejection or a post-stream size mismatch, PER mirror.
    private const val FULL_RESTART_BUDGET = 2

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

    /** Last pinned total bytes for [modelId], or -1 when unknown. */
    fun persistedTotal(context: Context, modelId: String): Long =
        prefs(context).getLong("total_$modelId", -1L)

    private fun prefs(context: Context): android.content.SharedPreferences =
        context.getSharedPreferences(PROGRESS_PREFS, Context.MODE_PRIVATE)

    private fun persistProgress(context: Context, modelId: String, got: Long, total: Long) {
        prefs(context).edit()
            .putLong("got_$modelId", got)
            .putLong("total_$modelId", total)
            .apply()
    }

    private fun clearProgress(context: Context, modelId: String) {
        prefs(context).edit()
            .remove("got_$modelId")
            .remove("total_$modelId")
            .apply()
    }

    private data class HeadInfo(val code: Int, val total: Long, val message: String)

    /**
     * HEAD the final url (following redirects) and read the real
     * Content-Length. Non-2xx / no-length surfaces as a bad [HeadInfo] so the
     * caller can label the failure with the exact HTTP code.
     */
    private fun headInfo(url: String): HeadInfo {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "HEAD"
                connectTimeout = 15_000
                readTimeout = 15_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", UA)
                setRequestProperty("Accept", "*/*")
            }
            conn.connect()
            val code = conn.responseCode
            val total = conn.getHeaderFieldLong("Content-Length", -1L)
            HeadInfo(code, total, conn.responseMessage ?: "HTTP $code")
        } catch (e: Exception) {
            Log.w(TAG, "HEAD $url failed: ${e.message}")
            HeadInfo(-1, -1L, e.message ?: "connect error")
        } finally {
            runCatching { conn?.disconnect() }
        }
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
            clearProgress(context, modelId)
            return it
        }

        val dir = scratchDir(context, modelId)
        val partFile = File(dir, "source.safetensors.part")
        val fullFile = File(dir, "source.safetensors")

        var lastError: String = "network"

        for (url in candidateUrls(context, convertPath)) {
            if (isCancelled()) throw CancelledException()

            // (a) HEAD the final url, pin the authoritative total.
            var head = headInfo(url)
            if (head.code !in 200..299 || head.total <= 0L) {
                lastError = "HTTP ${head.code} ${head.message}".trim()
                Log.w(TAG, "HEAD $url -> ${head.code} total=${head.total} ($lastError)")
                continue
            }
            var total = head.total

            // (b) validate existing .part against the pinned total.
            val prevTotal = persistedTotal(context, modelId)
            var have = if (partFile.exists()) partFile.length() else 0L
            if (have > total || (have > 0L && prevTotal > 0L && prevTotal != total)) {
                Log.w(
                    TAG,
                    "stale part have=$have prevTotal=$prevTotal total=$total, dropping scratch",
                )
                partFile.delete()
                have = 0L
            }
            persistProgress(context, modelId, have, total)
            if (have > 0L) onProgress(have, total)

            // ---- per-mirror download loop (full-restart budget) ----
            var restartBudget = FULL_RESTART_BUDGET
            var urlSucceeded = false
            var gaveUpOnMirror = false

            while (!urlSucceeded && !gaveUpOnMirror) {
                if (isCancelled()) throw CancelledException()

                var conn: HttpURLConnection? = null
                try {
                    conn = (URL(url).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 15_000
                        readTimeout = 120_000
                        instanceFollowRedirects = true
                        setRequestProperty("User-Agent", UA)
                        setRequestProperty("Accept", "*/*")
                        // (c) exact range, inclusive of the last byte.
                        if (have in 1L until total) {
                            setRequestProperty("Range", "bytes=$have-${total - 1}")
                        }
                    }
                    conn.connect()
                    val code = conn.responseCode
                    val reason = conn.responseMessage ?: ""

                    // (c) 404 / 416 on the range: the mirror refuses to hand us
                    //     the tail. Drop the part, re-HEAD, and re-fetch whole.
                    if (code == 404 || code == 416) {
                        lastError = "HTTP $code $reason".trim()
                        Log.w(TAG, "$url range rejected $lastError (have=$have total=$total)")
                        if (restartBudget <= 0) { gaveUpOnMirror = true; break }
                        restartBudget--
                        partFile.delete()
                        have = 0L
                        head = headInfo(url)
                        if (head.code !in 200..299 || head.total <= 0L) {
                            lastError = "HTTP ${head.code} ${head.message}".trim()
                            gaveUpOnMirror = true
                            break
                        }
                        total = head.total
                        persistProgress(context, modelId, 0L, total)
                        continue
                    }

                    if (code !in 200..299) {
                        lastError = "HTTP $code $reason".trim()
                        gaveUpOnMirror = true
                        break
                    }

                    val isPartial = code == HttpURLConnection.HTTP_PARTIAL
                    // A 200 while we asked for a range means the server ignored
                    // Range -> overwrite the part and stream the whole file once.
                    val append = isPartial
                    if (!append && partFile.exists() && have > 0L) {
                        Log.w(TAG, "$url answered 200 to a range request, restarting full")
                        partFile.delete()
                        have = 0L
                    }

                    // Cross-check the Content-Range total against our HEAD total.
                    if (isPartial) {
                        val serverTotal = conn.getHeaderField("Content-Range")
                            ?.substringAfter('/', missingDelimiterValue = "")
                            ?.toLongOrNull()
                        if (serverTotal != null && serverTotal != total) {
                            Log.w(TAG, "Content-Range total $serverTotal != head $total")
                            if (restartBudget <= 0) { gaveUpOnMirror = true; break }
                            restartBudget--
                            partFile.delete()
                            have = 0L
                            total = serverTotal
                            persistProgress(context, modelId, 0L, total)
                            continue
                        }
                    }

                    var got = if (append) have else 0L
                    var lastUi = 0L
                    FileOutputStream(partFile, append).use { out ->
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
                                    persistProgress(context, modelId, got, total)
                                }
                            }
                            out.flush()
                        }
                    }
                    conn.disconnect()
                    conn = null

                    // (d) hard size gate: only accept when we reached total bytes.
                    if (partFile.length() != total) {
                        lastError = "truncated ${partFile.length()}/$total"
                        Log.w(TAG, "$url $lastError")
                        if (restartBudget <= 0) { gaveUpOnMirror = true; break }
                        restartBudget--
                        partFile.delete()
                        have = 0L
                        continue
                    }
                    urlSucceeded = true
                } catch (e: CancelledException) {
                    throw e
                } catch (e: Exception) {
                    // A transient mid-read drop: keep the `.part` and rotate to
                    // the next mirror, which will re-HEAD and resume.
                    lastError = e.message ?: "network error"
                    Log.w(TAG, "GET $url failed: ${e.message}")
                    gaveUpOnMirror = true
                } finally {
                    runCatching { conn?.disconnect() }
                }
            }

            if (urlSucceeded) {
                // (d)+(f): size verified == total -> rename, then the caller is
                // allowed to unzip/convert only after this block returns.
                if (fullFile.exists()) fullFile.delete()
                if (!partFile.renameTo(fullFile)) {
                    partFile.copyTo(fullFile, overwrite = true)
                    partFile.delete()
                }
                if (fullFile.length() != total) {
                    Log.w(TAG, "renamed size ${fullFile.length()} != $total, retrying mirrors")
                    runCatching { fullFile.delete() }
                    continue
                }

                // et.110: validate safetensors header (first 8 bytes = uint64 LE
                // header length). A .ckpt/Pickle file or truncated download fails
                // this -> clear message, no hard convert.
                val hdrLen = runCatching {
                    java.io.DataInputStream(fullFile.inputStream().buffered(8)).use { it.readLong() }
                }.getOrNull() ?: -1L
                val sz = fullFile.length()
                if (hdrLen !in 16L..(sz - 8L)) {
                    runCatching { fullFile.delete() }
                    clearProgress(context, modelId)
                    throw IOException(
                        "文件损坏或是 .ckpt 格式（safetensors 头长度=$hdrLen，文件=$sz 字节），请重新下载 safetensors 版本",
                    )
                }
                clearProgress(context, modelId)
                onProgress(fullFile.length(), total)
                return fullFile
            }
            // else: keep `.part` so the next mirror resumes from its bytes.
        }

        throw IOException(
            context.getString(
                io.github.xororz.localdream.R.string.model_download_failed,
                lastError,
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
