package io.github.xororz.localdream.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import io.github.xororz.localdream.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
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
     * Streams the APK into the app cache, invoking [onProgress] with
     * downloaded / total bytes. Returns the saved file.
     */
    suspend fun download(
        context: Context,
        url: String,
        onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> },
    ): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val target = File(dir, "localdream-update.apk")
        if (target.exists()) target.delete()

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "LocalDream-ET/${BuildConfig.VERSION_NAME}")
            .build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                target.delete()
                error("HTTP ${resp.code}")
            }
            val body = resp.body ?: error("empty response body")
            val total = body.contentLength().takeIf { it > 0 } ?: -1L
            body.byteStream().use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var downloaded = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        onProgress(downloaded, total)
                    }
                    output.flush()
                }
            }
        }
        if (!target.exists() || target.length() <= 0L) error("downloaded file is empty")
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
