package io.github.xororz.localdream.service

import android.content.Context
import android.util.Log
import io.github.xororz.localdream.chat.ChatRepository
import io.github.xororz.localdream.cloud.CloudConfig
import io.github.xororz.localdream.cloud.SecureTokenStore
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Picture-market revenue split.
 *
 * A buyer pays [price] units locally (QuotaManager.consume). 85% of that price
 * is written to `earnings/<author>.json` in ETQWFD/LocalDreamET-Quota and 15%
 * to `earnings/__platform.json`. The author's device calls [sync] and moves the
 * delta into its own bonus pool, so the money is actually spendable — not just
 * a number on a web page.
 */
object GalleryEarnings {
    private const val TAG = "GalleryEarnings"
    private const val REPO = "ETQWFD/LocalDreamET-Quota"
    private const val DIR = "earnings"
    private const val AUTHOR_PERCENT = 85

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    private fun safeName(raw: String): String {
        val cleaned = raw.trim().lowercase().replace(Regex("[^a-z0-9._-]"), "_")
        return cleaned.ifBlank { "unknown" }
    }

    private fun readUrl(name: String) =
        "https://raw.githubusercontent.com/$REPO/main/$DIR/$name.json"

    private fun apiUrl(name: String) =
        "https://api.github.com/repos/$REPO/contents/$DIR/$name.json"

    /** Reads a totals file; returns (total, sha). total = 0 when the file is absent. */
    private fun read(name: String, token: String): Pair<Long, String> {
        val req = Request.Builder().url(apiUrl(name))
            .addHeader("Authorization", "Bearer $token")
            .addHeader("User-Agent", "LocalDream-ET")
            .build()
        runCatching {
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val obj = JSONObject(resp.body?.string().orEmpty())
                    val sha = obj.optString("sha", "")
                    val body = obj.optString("content", "").replace("\n", "")
                    val json = runCatching {
                        JSONObject(String(Base64.getDecoder().decode(body)))
                    }.getOrNull()
                    return json?.optLong("total", 0L)!! to sha
                }
            }
        }
        // Fall back to the public mirror when the token cannot read it.
        runCatching {
            val conn = java.net.URL(readUrl(name)).openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 12000
            if (conn.responseCode in 200..299) {
                val json = JSONObject(conn.inputStream.use { it.readBytes().decodeToString() })
                return json.optLong("total", 0L) to ""
            }
        }
        return 0L to ""
    }

    private fun write(name: String, total: Long, sales: Int, token: String): Boolean {
        repeat(3) { attempt ->
            val (existing, sha) = read(name, token)
            val base = if (existing > 0L) existing else 0L
            val payload = JSONObject()
                .put("total", base + total)
                .put("sales", sales)
                .put("updated", System.currentTimeMillis() / 1000L)
                .put("unit", "quota")
                .toString()
            val body = JSONObject().put("message", "earnings: +$total")
                .put("content", Base64.getEncoder().encodeToString(payload.toByteArray()))
                .apply { if (sha.isNotBlank()) put("sha", sha) }
                .toString()
            val req = Request.Builder().url(apiUrl(name))
                .put(body.toRequestBody("application/json".toMediaType()))
                .addHeader("Authorization", "Bearer $token")
                .addHeader("User-Agent", "LocalDream-ET")
                .build()
            runCatching {
                client.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) return true
                    if (resp.code == 409 || resp.code == 422) return@use
                    Log.w(TAG, "write $name failed HTTP ${resp.code}")
                    return false
                }
            }.onFailure { Log.w(TAG, "write $name: ${it.message}") }
            Thread.sleep(250L)
        }
        return false
    }

    /** Splits a completed purchase between the author and the platform. */
    suspend fun record(ctx: Context, author: String, price: Int): Boolean =
        withContext(Dispatchers.IO) {
            if (price <= 0) return@withContext false
            val token = runCatching { ChatRepository.writeToken(ctx) }.getOrDefault("")
            if (token.isBlank()) return@withContext false
            val authorPart = price.toLong() * AUTHOR_PERCENT / 100
            val platformPart = price.toLong() - authorPart
            val authorOk = write(safeName(author), authorPart, 1, token)
            val platformOk = write("__platform", platformPart, 1, token)
            authorOk && platformOk
        }

    /**
     * Applies newly earned quota to this device. Idempotent: remembers the
     * remote total it already credited, so a re-check never double-pays.
     */
    suspend fun sync(ctx: Context): Long = withContext(Dispatchers.IO) {
        val login = runCatching {
            SecureTokenStore(ctx).state(CloudConfig.Provider.GITHUB)?.login
        }.getOrNull() ?: return@withContext 0L
        val token = runCatching { ChatRepository.writeToken(ctx) }.getOrDefault("")
        if (token.isBlank()) return@withContext 0L
        val remote = runCatching { read(safeName(login), token).first }.getOrDefault(0L)
        if (remote <= 0L) return@withContext 0L
        val sp = ctx.getSharedPreferences("quota_prefs", Context.MODE_PRIVATE)
        val applied = sp.getLong("earnings_applied", 0L)
        if (remote <= applied) return@withContext 0L
        val delta = remote - applied
        sp.edit()
            .putLong("earnings_applied", remote)
            .putLong("bonus_pool", sp.getLong("bonus_pool", 0L) + delta)
            .apply()
        Log.i(TAG, "credited $delta quota from gallery earnings")
        delta
    }
}
