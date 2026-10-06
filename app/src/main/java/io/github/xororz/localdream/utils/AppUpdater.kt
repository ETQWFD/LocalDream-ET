package io.github.xororz.localdream.utils

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
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
import java.io.IOException
import java.net.HttpURLConnection
import java.util.concurrent.TimeUnit

private const val ACTION_INSTALL_COMMIT =
    "io.github.xororz.localdream.action.INSTALL_COMMIT"

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
        val sha256: String = "",
    )

    // Edit these two constants to retarget the update channel.
    const val DEVELOPER = "ET"
    // et.25: dual channel. GitHub (Pages + raw + releases) is authoritative;
    // Gitee mirror is only a fallback and a 404/unreachable Gitee must NOT fail
    // the check — any one channel being available is enough.
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
        // et.25 dual channel: try GitHub Pages, GitHub raw, then the optional
        // Gitee mirror. A failing Gitee channel is ignored.
        val channels = listOf(
            io.github.xororz.localdream.cloud.CloudConfig.githubUpdateJson,
            io.github.xororz.localdream.cloud.CloudConfig.githubUpdateJsonRaw,
            io.github.xororz.localdream.cloud.CloudConfig.giteeUpdateJson,
        )
        for (url in channels) {
            runCatching { fetchManifest(url) }.getOrNull()?.let { return@withContext it }
        }
        runCatching { fetchLatestRelease() }.getOrNull()
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

    private fun fetchManifest(url: String): UpdateInfo? {
        val json = JSONObject(httpGet(url))
        val url = json.optString("apkUrl").ifBlank { json.optString("apk_url") }
        if (url.isBlank()) return null
        return UpdateInfo(
            versionName = json.optString("versionName").ifBlank { json.optString("tag") },
            versionCode = json.optLong("versionCode", json.optLong("version_code", 0L)),
            releaseNotes = json.optString("releaseNotes").ifBlank { json.optString("notes") },
            apkUrl = url,
            sizeBytes = json.optLong("size", 0L),
            sha256 = json.optString("sha256", "").trim().lowercase(),
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
     * Public download entry. Downloads the APK then verifies it against the
     * manifest's expected size and sha256. If verification fails (truncated /
     * stitched resume / corrupt proxy), the corrupt file and its .part are deleted
     * and the whole download restarts — at most 3 attempts. A failing verification
     * must NEVER reach the installer (et.38: "package not signed / parse error").
     */
    suspend fun download(
        context: Context,
        url: String,
        expectedSize: Long = 0L,
        expectedSha256: String = "",
        onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> },
    ): File = withContext(Dispatchers.IO) {
        var lastError: Exception? = null
        repeat(3) { attempt ->
            val target = downloadOnce(context, url, onProgress)
            val problem = verifyApk(target, expectedSize, expectedSha256)
            if (problem == null) {
                io.github.xororz.localdream.cloud.LogHub.log(
                    io.github.xororz.localdream.cloud.LogHub.Category.UPDATE,
                    "integrity OK: ${target.length()} bytes" +
                        (if (expectedSha256.isNotBlank()) ", sha256 matched" else ""),
                )
                return@withContext target
            }
            io.github.xororz.localdream.cloud.LogHub.log(
                io.github.xororz.localdream.cloud.LogHub.Category.UPDATE,
                "integrity check failed: $problem (attempt ${attempt + 1}/3); re-downloading clean",
            )
            runCatching { target.delete() }
            runCatching { File(io.github.xororz.localdream.utils.Storage.tempDir(context), "localdream-update.apk.part").delete() }
            lastError = IOException("integrity check failed: $problem")
        }
        throw lastError ?: IOException("download failed integrity check")
    }

    /** Returns null when the APK passes size + sha256, or a human reason when not. */
    private fun verifyApk(apk: File, expectedSize: Long, expectedSha256: String): String? {
        if (!apk.exists() || apk.length() <= 0L) return "file missing or empty"
        if (expectedSize > 0L && apk.length() != expectedSize) {
            return "size mismatch: got ${apk.length()}, expected $expectedSize"
        }
        if (expectedSha256.isNotBlank()) {
            val actual = sha256Of(apk)
            if (!actual.equals(expectedSha256, ignoreCase = true)) {
                return "sha256 mismatch"
            }
        }
        return null
    }

    private fun sha256Of(file: File): String = runCatching {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val r = input.read(buf)
                if (r < 0) break
                md.update(buf, 0, r)
            }
        }
        md.digest().joinToString("") { "%02x".format(it) }
    }.getOrDefault("")

    /**
     * Streams the APK into the app cache, invoking [onProgress] with
     * downloaded / total bytes. Returns the saved file. Uses the fastest
     * reachable GitHub mirror and HTTP Range resume.
     */
    private suspend fun downloadOnce(
        context: Context,
        url: String,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        // et.21: write the update APK into the user-visible shared folder
        // /storage/emulated/0/LocalDreamET/temp_downloads (survives cache
        // clears and is directly readable by the system package installer),
        // transparently falling back to filesDir before storage permission.
        val dir = io.github.xororz.localdream.utils.Storage.tempDir(context)
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

        // et.34: a fresh download starts from 0 — drop any previously completed
        // APK left at [target]. Otherwise the dialog polling (which reads file
        // sizes) would see the OLD finished APK at ~99% of the new size the
        // instant the download starts, i.e. "jumping to 99% before bytes move".
        if (from == 0L && target.exists()) target.delete()

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

    /**
     * Hands the downloaded APK to the system package installer.
     *
     * Uses the PackageInstaller session API first: it always routes to the
     * built-in installer confirmation screen and can never be hijacked by a
     * third-party file manager the user set as a default "open with" target
     * (e.g. MT Manager). Only if that path is unavailable do we fall back to
     * ACTION_VIEW, explicitly pinned to the on-device system installer package
     * rather than any user-installed app.
     */
    fun install(context: Context, apk: File) {
        if (!apk.exists() || apk.length() <= 0L) error("downloaded APK is missing or empty")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            context.startActivity(installPermissionSettingsIntent(context))
            return
        }
        if (runCatching { installViaPackageInstaller(context, apk) }.getOrDefault(false)) return
        installViaViewIntent(context, apk)
    }

    private fun installViaPackageInstaller(context: Context, apk: File): Boolean {
        val app = context.applicationContext
        val installer = app.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(
            PackageInstaller.SessionParams.MODE_FULL_INSTALL,
        ).apply { setSize(apk.length()) }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            apk.inputStream().use { input ->
                session.openWrite("localdream-update", 0, apk.length()).use { out ->
                    input.copyTo(out, 1 shl 16)
                    out.flush()
                    session.fsync(out)
                }
            }

            // Local, non-exported callback so only our own app receives the
            // commit result; the system still shows its installer UI itself.
            var receiver: BroadcastReceiver? = null
            val cbIntent = Intent(ACTION_INSTALL_COMMIT).setPackage(app.packageName)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            val statusReceiver = PendingIntent
                .getBroadcast(app, sessionId, cbIntent, flags)
                .intentSender
            receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, data: Intent) {
                    runCatching { ctx.unregisterReceiver(this) }
                    val status = data.getIntExtra(
                        PackageInstaller.EXTRA_STATUS,
                        PackageInstaller.STATUS_FAILURE,
                    )
                    if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                        // Some ROMs hand back a confirmation intent to launch.
                        @Suppress("DEPRECATION")
                        val confirm = data
                            .getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                        confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        runCatching { confirm?.let { ctx.startActivity(it) } }
                    }
                }
            }
            ContextCompat.registerReceiver(
                app,
                receiver,
                IntentFilter(ACTION_INSTALL_COMMIT),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            session.commit(statusReceiver)
        }
        return true
    }

    private fun installViaViewIntent(context: Context, apk: File) {
        val authority = "${context.packageName}.fileprovider"
        val uri = FileProvider.getUriForFile(context, authority, apk)
        val base = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_ACTIVITY_NEW_TASK,
            )
        // Pin to the system installer package so a default file manager never
        // opens the APK. Prefer an installer-named system app; on other ROMs
        // accept any resolved handler that ships with the system image.
        val systemInstaller = run {
            val pm = context.packageManager
            val handlers = pm.queryIntentActivities(base, 0)
            val byName = handlers.firstOrNull {
                val pkg = it.activityInfo?.packageName.orEmpty()
                pkg.contains("packageinstaller")
            }
            val sysApp = handlers.firstOrNull {
                runCatching {
                    val flags = pm.getApplicationInfo(it.activityInfo.packageName, 0).flags
                    flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0
                }.getOrDefault(false)
            }
            (byName ?: sysApp)?.activityInfo?.packageName
        }
        systemInstaller?.let { base.setPackage(it) }
        context.startActivity(base)
    }
}
