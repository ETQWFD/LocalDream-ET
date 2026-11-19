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
 *   https://etc.tw.kg/update.json
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
        // et.47: optional server-driven dialog title (e.g. an edition name such as
        // 「最后一舞」). Blank -> client falls back to the default "new version" title.
        val title: String = "",
    )

    // Edit these two constants to retarget the update channel.
    const val DEVELOPER = "ET"
    // et.25: dual channel. GitHub (Pages + raw + releases) is authoritative;
    // Gitee mirror is only a fallback and a 404/unreachable Gitee must NOT fail
    // the check — any one channel being available is enough.
    private const val RELEASES_API =
        "https://api.github.com/repos/ETQWFD/NightmareRoad-ET/releases/latest"

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            // 2026-11：更新检查原来 20s/60s，镜像不通时用户要干等一分多钟；
            // 取版本号只需要几十 KB，6s/12s 足够，失败立刻换下一通道。
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    // et.118/build195b: a short read timeout acts as a stall watchdog — if a mirror
    // pushes no bytes for 30s the read throws SocketTimeoutException and we switch
    // to the next candidate instead of hanging at 0%.
    private val stallClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
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
            io.github.xororz.localdream.cloud.CloudConfig.jsdelivrUpdateJson,
            io.github.xororz.localdream.cloud.CloudConfig.ghProxyUpdateJson,
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
            .header("User-Agent", "NightmareRoad-ET/${BuildConfig.VERSION_NAME}")
            .build()
        return client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code}")
            resp.body?.string().orEmpty()
        }
    }

    private fun fetchManifest(url: String): UpdateInfo? {
        val json = JSONObject(httpGet(url))
        var url2 = json.optString("apkUrl").ifBlank { json.optString("apk_url") }
        var size = json.optLong("size", 0L)
        var sha = json.optString("sha256", "").trim().lowercase()
        // et.244：官网/更新通道可以按 ABI 提供两个包（高配 arm64 版 / 基础 32 位版）。
        // 有匹配本机 ABI 的条目就用它，没有就退回通用 apkUrl，老版本清单照常工作。
        val abi = android.os.Build.SUPPORTED_ABIS?.firstOrNull().orEmpty()
        if (url2.isNotBlank() && abi.isNotBlank()) {
            // et.249：清单里的 variants 从 et.244 起是"以 ABI 为键的对象"，
            // 而这里原来只用 optJSONArray 读数组形式 —— 对象形式解析恒为 null，
            // 结果按架构分包从来没生效，32 位旧机也在下 160MB+ 的通用包，
            // 表现为"更新下载特别慢"。两种写法现在都认。
            var pick: JSONObject? = null
            json.optJSONObject("variants")?.optJSONObject(abi)?.let { pick = it }
            if (pick == null) {
                val arr = json.optJSONArray("variants")
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val v = arr.optJSONObject(i) ?: continue
                        if (v.optString("abi") == abi) { pick = v; break }
                    }
                }
            }
            pick?.let { v ->
                val u = v.optString("apkUrl").ifBlank { v.optString("url") }
                if (u.isNotBlank()) {
                    url2 = u
                    size = v.optLong("size", size)
                    sha = v.optString("sha256", sha).trim().lowercase()
                }
            }
        }
        if (url2.isBlank()) return null
        return UpdateInfo(
            versionName = json.optString("versionName").ifBlank { json.optString("tag") },
            versionCode = json.optLong("versionCode", json.optLong("version_code", 0L)),
            releaseNotes = json.optString("releaseNotes").ifBlank { json.optString("notes") },
            apkUrl = url2,
            sizeBytes = size,
            sha256 = sha,
            title = json.optString("title").ifBlank { json.optString("name") },
        ).takeIf { it.versionCode > BuildConfig.VERSION_CODE }
    }

    private fun fetchLatestRelease(): UpdateInfo? {
        val json = JSONObject(httpGet(RELEASES_API))
        if (json.optBoolean("draft") || json.optBoolean("prerelease")) {
            // Still honour an explicit prerelease channel if pointed here; keep simple.
        }
        // et.249：GitHub 是按名称排序返回资产的，"第一个 .apk"在本仓库里其实是
        // …-arm64.apk（"NightmareRoad-ET-arm64…" 字典序在 "NightmareRoad-ET.apk" 之前）。
        // 回退通道若真被触发，32 位机会拿到 arm64 包，安装直接 NO_MATCHING_ABIS。
        // 所以这里按本机 ABI 选资产，选不到再退回通用包（名字里不带架构后缀的那个）。
        val myAbi = android.os.Build.SUPPORTED_ABIS?.firstOrNull().orEmpty()
        val abiTag = when {
            myAbi.contains("arm64") || myAbi.contains("aarch64") -> "arm64"
            myAbi.contains("armeabi") || myAbi.contains("armv7") -> "armv7a"
            else -> ""
        }
        val apks = json.optJSONArray("assets")
            ?.let { arr -> (0 until arr.length()).map { arr.getJSONObject(it) } }
            ?.filter {
                val nm = it.optString("name")
                // 只认主程序包：Release 里现在还有 NightmareDrive.apk（网盘），
                // 它既不带架构后缀、又排在字母序最前，必须显式排除，
                // 否则非 ARM 设备走回退通道会把网盘当主程序装上。
                nm.endsWith(".apk", ignoreCase = true) &&
                    nm.startsWith("NightmareRoad-ET", ignoreCase = true)
            }
            .orEmpty()
        if (apks.isEmpty()) return null
        val asset = apks.firstOrNull {
            abiTag.isNotEmpty() && it.optString("name").contains(abiTag, ignoreCase = true)
        } ?: apks.firstOrNull { name ->
            // 通用包：不含任何其它架构标记
            !name.optString("name").contains("arm64", true) &&
                !name.optString("name").contains("v7a", true)
        } ?: apks.first()
        val tag = json.optString("tag_name").ifBlank { json.optString("name") }
        // 原来 find() 取第一段数字：tag「et.248」会得到 248，而真实 versionCode 是 322，
        // 248 > 322 永假 —— GitHub Releases 回退通道因此一直是死的（所有镜像都挂时
        // 用户就再也收不到更新）。取 tag 里最大的数字串，并保证新 tag 形如
        // v<versionCode>-et.<NNN>，回退通道才可靠。
        val code = Regex("\\d+").findAll(tag)
            .mapNotNull { it.value.toLongOrNull() }
            .maxOrNull() ?: 0L
        return UpdateInfo(
            versionName = json.optString("tag_name").trimStart('v'),
            versionCode = code,
            releaseNotes = json.optString("body"),
            apkUrl = asset.optString("browser_download_url"),
            sizeBytes = asset.optLong("size", 0L),
            title = json.optString("name"),
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
            // et.148: release 二进制不能走 jsdelivr（404），改用 gh-proxy 系列。
            "https://gh-proxy.com/",
            "https://ghfast.top/",
            "https://ghproxy.net/",
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
                        setRequestProperty("User-Agent", "NightmareRoad-ET/${BuildConfig.VERSION_NAME}")
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
        // et.79: dedup — if a previously downloaded APK for this version already
        // exists and passes size+sha256, reuse it instead of re-downloading every launch.
        runCatching {
            val existing = File(io.github.xororz.localdream.utils.Storage.tempDir(context), "localdream-update.apk")
            if (existing.exists() && verifyApk(existing, expectedSize, expectedSha256) == null) {
                return@withContext existing
            }
        }
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
        // /storage/emulated/0/NightmareRoad-ET/temp_downloads (survives cache
        // clears and is directly readable by the system package installer),
        // transparently falling back to filesDir before storage permission.
        val dir = io.github.xororz.localdream.utils.Storage.tempDir(context)
        val target = File(dir, "localdream-update.apk")
        val part = File(dir, "localdream-update.apk.part")

        // Ordered mirror list: the fastest probed candidate first, then the rest
        // in the requested mirror order. Each attempt resumes the same .part, so
        // switching mirrors mid-download never re-downloads completed bytes.
        val probes = fastestCandidate(mirrorCandidates(url))
        val ordered = buildList {
            probes?.url?.let { add(it) }
            mirrorCandidates(url).forEach { if (it !in this) add(it) }
        }

        var from = if (part.exists()) part.length() else 0L
        var total = probes?.total?.takeIf { it > 0 } ?: -1L
        if (total > 0 && from >= total) from = 0L
        if (from == 0L && part.exists()) part.delete()
        if (from == 0L && target.exists()) target.delete()

        var lastError: Exception? = null
        for (mirror in ordered) {
            if (total > 0 && from >= total) break
            val startedFrom = from
            try {
                from = streamRange(mirror, from, part) { got, tot ->
                    if (total <= 0 && tot > 0) total = tot
                    onProgress(got, if (tot > 0) tot else total)
                }
                lastError = null
            } catch (e: Exception) {
                // 30s read timeout (stall) or connection error -> try the next mirror.
                lastError = e
                io.github.xororz.localdream.cloud.LogHub.log(
                    io.github.xororz.localdream.cloud.LogHub.Category.UPDATE,
                    "mirror failed/stalled ($mirror, from=$startedFrom, now=$from): ${e.message}; switching",
                )
            }
        }

        if (total > 0 && from < total) {
            throw lastError ?: IOException("download stalled before completing ($from/$total)")
        }
        if (lastError != null && total <= 0) throw lastError ?: IOException("download failed")

        if (!part.exists() || part.length() <= 0L) error("downloaded file is empty")
        if (target.exists()) target.delete()
        if (!part.renameTo(target)) {
            part.copyTo(target, overwrite = true); part.delete()
        }
        target
    }

    /**
     * Opens a ranged GET on [mirror], appends to [part] and returns the new .part
     * length. Uses the short-read-timeout [stallClient] so a mirror that stops
     * pushing bytes throws instead of hanging.
     */
    private fun streamRange(
        mirror: String,
        from: Long,
        part: File,
        onProgress: (got: Long, total: Long) -> Unit,
    ): Long {
        val builder = Request.Builder()
            .url(mirror)
            .header("User-Agent", "NightmareRoad-ET/${BuildConfig.VERSION_NAME}")
        if (from > 0) builder.header("Range", "bytes=$from-")
        stallClient.newCall(builder.build()).execute().use { resp ->
            if (resp.code != 200 && resp.code != 206) error("HTTP ${resp.code} on $mirror")
            var base = from
            if (resp.code == 200) { part.delete(); base = 0L }
            val body = resp.body ?: error("empty body on $mirror")
            val bodyLen = body.contentLength().takeIf { it > 0 } ?: -1L
            val tot = if (bodyLen > 0) bodyLen + base else -1L
            body.byteStream().use { input ->
                java.io.FileOutputStream(part, resp.code == 206 && base > 0).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var downloaded = base
                    while (true) {
                        val r = input.read(buffer)
                        if (r == -1) break
                        output.write(buffer, 0, r)
                        downloaded += r
                        onProgress(downloaded, tot)
                    }
                    output.flush()
                }
            }
            return part.length()
        }
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
    /** versionCode of a downloaded APK, or -1 when it cannot be parsed. */
    fun apkVersionCode(context: Context, apk: File): Long = runCatching {
        val info = context.packageManager.getPackageArchiveInfo(
            apk.absolutePath,
            android.content.pm.PackageManager.GET_ACTIVITIES,
        ) ?: return@runCatching -1L
        if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode
        else @Suppress("DEPRECATION") info.versionCode.toLong()
    }.getOrDefault(-1L)

    /** True only when the downloaded APK is a genuinely newer build. */
    fun isNewerApk(context: Context, apk: File): Boolean =
        apkVersionCode(context, apk) > BuildConfig.VERSION_CODE.toLong()

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
