package io.github.xororz.localdream.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import io.github.xororz.localdream.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.util.concurrent.TimeUnit

/**
 * In-app self updater.
 *
 * Update manifest (authoritative, hosted on GitHub Pages):
 *   https://etqwfd.github.io/LocalDream-ET/update.json
 * Fallback: the latest GitHub release of the ET fork.
 *
 * The whole flow lives inside the app: check -> download with a progress
 * callback -> request the install permission once -> hand the APK to the
 * system package installer. No file manager or third-party store is involved.
 */
object AppUpdater {

    data class UpdateInfo(
        val versionName: String,
        val versionCode: Long,
        val releaseNotes: String,
        val apkUrl: String,
        val sizeBytes: Long,
    )

    // Edit these two constants to retarget the update channel.
    const val DEVELOPER = "ET"
    private const val MANIFEST_URL = "https://etqwfd.github.io/LocalDream-ET/update.json"
    private const val RELEASES_API =
        "https://api.github.com/repos/ETQWFD/LocalDream-ET/releases/latest"

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    /** Whether the app is allowed to install APKs (Android O+). */
    fun canInstall(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
        return context.packageManager.canRequestPackageInstalls()
    }

    /** Intent that opens the system "install unknown apps" page for this app. */
    fun installPermissionSettingsIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * Checks the update channel. Returns an [UpdateInfo] only when the remote
     * versionCode is strictly newer than the installed build, otherwise null.
     */
    suspend fun check(): UpdateInfo? = withContext(Dispatchers.IO) {
        runCatching { fetchManifest() }.getOrNull()
            ?: runCatching { fetchLatestRelease() }.getOrNull()
    }

    private fun httpGet(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", "LocalDream-ET/${BuildConfig.VERSION_NAME}")
            .build()
        return client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code}")
            resp.body?.string().orEmpty()
        }
    }

    private fun fetchManifest(): UpdateInfo? {
        val json = JSONObject(httpGet(MANIFEST_URL))
        val url = json.optString("apkUrl").ifBlank { json.optString("apk_url") }
        if (url.isBlank()) return null
        return UpdateInfo(
            versionName = json.optString("versionName").ifBlank { json.optString("tag") },
            versionCode = json.optLong("versionCode", json.optLong("version_code", 0L)),
            releaseNotes = json.optString("releaseNotes").ifBlank { json.optString("notes") },
            apkUrl = url,
            sizeBytes = json.optLong("size", 0L),
        ).takeIf { it.versionCode > BuildConfig.VERSION_CODE }
    }

    private fun fetchLatestRelease(): UpdateInfo? {
        val json = JSONObject(httpGet(RELEASES_API))
        if (json.optBoolean("draft") || json.optBoolean("prerelease")) {
            // Still honour an explicit prerelease channel if pointed here; keep simple.
        }
        val asset = json.optJSONArray("assets")
            ?.takeIf { it.length() > 0 }
            ?.let { arr ->
                (0 until arr.length())
                    .map { arr.getJSONObject(it) }
                    .firstOrNull { it.optString("name").endsWith(".apk", ignoreCase = true) }
            } ?: return null
        val code = Regex("\\d+").find(
            json.optString("tag_name").ifBlank { json.optString("name") },
        )?.value?.toLongOrNull() ?: 0L
        return UpdateInfo(
            versionName = json.optString("tag_name").trimStart('v'),
            versionCode = code,
            releaseNotes = json.optString("body"),
            apkUrl = asset.optString("browser_download_url"),
            sizeBytes = asset.optLong("size", 0L),
        ).takeIf { it.versionCode > BuildConfig.VERSION_CODE && it.apkUrl.isNotBlank() }
    }

    /**
     * GitHub release downloads are often slow/throttled in mainland China. We
     * build a list of candidate URLs (the origin plus several public
     * GitHub-acceleration proxies), probe each with a tiny ranged GET in
     * parallel, and pick the one with the lowest connect+time-to-first-byte
     * that actually returns 206 (resumable). The download then resumes a
     * `.part` file so an interrupted update continues instead of restarting.
     */
    private fun mirrorCandidates(origin: String): List<String> {
        // Only proxy real github release/blob URLs; Pages/other hosts direct.
        if (!origin.contains("github.com/") && !origin.contains("githubusercontent.com/")) {
            return listOf(origin)
        }
        val proxies = listOf(
            "https://gh-proxy.com/",
            "https://ghfast.top/",
            "https://ghproxy.net/",
            "https://gh.llkk.cc/",
            "https://mirror.ghproxy.com/",
        )
        // Direct origin first (it is fastest on good networks), then proxies.
        return buildList {
            add(origin)
            proxies.forEach { add(it + origin) }
        }
    }

    private data class Probe(val url: String, val ms: Long, val total: Long)

    private suspend fun fastestCandidate(urls: List<String>): Probe? = coroutineScope {
        urls.map { u ->
            async(Dispatchers.IO) {
                val start = System.currentTimeMillis()
                var conn: HttpURLConnection? = null
                try {
                    conn = (java.net.URL(u).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 6000
                        readTimeout = 6000
                        instanceFollowRedirects = true
                        setRequestProperty("User-Agent", "LocalDream-ET/${BuildConfig.VERSION_NAME}")
                        setRequestProperty("Range", "bytes=0-0")
                    }
                    conn.connect()
                    val code = conn.responseCode
                    if (code != 206 && code != 200) return@async null
                    val total = conn.getHeaderField("Content-Range")
                        ?.substringAfterLast('/')?.toLongOrNull()
                        ?: conn.contentLengthLong.takeIf { it > 0 } ?: -1L
                    // Read the single byte so TTFB is genuinely measured.
                    conn.inputStream?.use { it.read() }
                    Probe(u, System.currentTimeMillis() - start, total)
                } catch (_: Exception) {
                    null
                } finally {
                    conn?.disconnect()
                }
            }
        }.awaitAll().filterNotNull().minByOrNull { it.ms }
    }

    /**
     * Streams the APK into the app cache, invoking [onProgress] with
     * downloaded / total bytes. Returns the saved file. Uses the fastest
     * reachable GitHub mirror and HTTP Range resume.
     */
    suspend fun download(
        context: Context,
        url: String,
        onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> },
    ): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val target = File(dir, "localdream-update.apk")
        val part = File(dir, "localdream-update.apk.part")

        val best = fastestCandidate(mirrorCandidates(url))
            ?: run {
                // Last resort: try the origin without probing/mirroring.
                Probe(url, 0L, -1L)
            }

        var from = if (part.exists()) part.length() else 0L
        var total = best.total.takeIf { it > 0 } ?: -1L

        // A stale .part longer than the announced file (or from another
        // release) cannot be resumed — start clean.
        if (total > 0 && from >= total) from = 0L
        if (from == 0L && part.exists()) part.delete()

        val builder = Request.Builder()
            .url(best.url)
            .header("User-Agent", "LocalDream-ET/${BuildConfig.VERSION_NAME}")
        if (from > 0) builder.header("Range", "bytes=$from-")
        val resp = client.newCall(builder.build()).execute()
        try {
            // 206 = resumed; 200 = server ignored Range and restarts.
            if (resp.code != 200 && resp.code != 206) error("HTTP ${resp.code}")
            if (resp.code == 200) { from = 0L; part.delete() }
            val body = resp.body ?: error("empty response body")
            val bodyLen = body.contentLength().takeIf { it > 0 } ?: -1L
            if (total <= 0) total = if (bodyLen > 0) bodyLen + from else -1L

            body.byteStream().use { input ->
                java.io.FileOutputStream(part, resp.code == 206).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var downloaded = from
                    while (true) {
                        val r = input.read(buffer)
                        if (r == -1) break
                        output.write(buffer, 0, r)
                        downloaded += r
                        onProgress(downloaded, total)
                    }
                    output.flush()
                }
            }
        } finally {
            resp.close()
        }

        if (!part.exists() || part.length() <= 0L) error("downloaded file is empty")
        if (target.exists()) target.delete()
        if (!part.renameTo(target)) {
            part.copyTo(target, overwrite = true); part.delete()
        }
        target
    }

    /** Hands the downloaded APK to the system package installer. */
    fun install(context: Context, apk: File) {
        val authority = "${context.packageName}.fileprovider"
        val uri: Uri = FileProvider.getUriForFile(context, authority, apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_ACTIVITY_NEW_TASK,
            )
        }
        context.startActivity(intent)
    }
}
