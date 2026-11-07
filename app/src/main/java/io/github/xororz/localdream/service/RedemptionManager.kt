package io.github.xororz.localdream.service

import android.content.Context
import android.util.Log
import io.github.xororz.localdream.cloud.CloudClient
import io.github.xororz.localdream.cloud.CloudConfig
import io.github.xororz.localdream.cloud.SecureTokenStore
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

/**
 * et.130 hardened prize-code redemption.
 *
 * Read path: Contents API (api.github.com) first; on timeout / 5xx / network
 * failure fall back to read-only mirrors raw.githubusercontent.com then
 * cdn.jsdelivr.net. connect=10s, read=15s, retry up to 2 extra attempts.
 * Write path (mark used) goes through the Contents API only and reports a
 * distinct Chinese error instead of a generic "network" message.
 *
 * Results:
 *  - Ok(credit)
 *  - Used            -> "该兑换码已使用"
 *  - Invalid         -> "兑换码无效" (404 / malformed)
 *  - NeedLogin       -> "请先登录"
 *  - Timeout         -> "请求超时，请重试"
 *  - NoNetwork       -> "无网络连接，请检查网络"
 *  - Forbidden       -> "令牌无效或权限不足" (403)
 *  - WriteFailed     -> "写入失败：…"
 */
object RedemptionManager {
    private const val TAG = "Redemption"
    private const val REPO = "ETQWFD/LocalDreamET-Quota"
    private const val CONNECT_MS = 10000
    private const val READ_MS = 15000
    private const val MAX_NET_TRIES = 3 // initial + 2 retries

    @Volatile private var lastNoNetwork = false
    @Volatile private var lastForbidden = false

    sealed class Result {
        data class Ok(val credit: Long) : Result()
        object Used : Result()
        object Invalid : Result()
        object NeedLogin : Result()
        object Timeout : Result()
        object NoNetwork : Result()
        object Forbidden : Result()
        data class WriteFailed(val reason: String) : Result()
    }

    private sealed class Lookup {
        data class Looked(val sha: String?, val effectValue: Long, val reusable: Boolean = false) : Lookup()
        object Used : Lookup()
        object Invalid : Lookup()
        object Timeout : Lookup()
        object NoNetwork : Lookup()
        object Forbidden : Lookup()
    }

    /** Mirrors that return the raw file body for a read-only verification. */
    private fun rawMirrors(code: String): List<String> {
        // et.147: 国内 api/raw/jsdelivr 常被拦截，加入 gitmirror/statically/ghproxy。
        val paths = listOf("redeem/codes/$code.json", "redeem/codes/redeem_$code.json")
        val hosts = listOf<(String) -> String>(
            { "https://raw.gitmirror.com/$REPO/main/$it" },
            { "https://cdn.statically.io/gh/$REPO@main/$it" },
            { "https://raw.githubusercontent.com/$REPO/main/$it" },
            { "https://cdn.jsdelivr.net/gh/$REPO@main/$it" },
            { "https://gh-proxy.com/https://raw.githubusercontent.com/$REPO/main/$it" },
        )
        return paths.flatMap { p -> hosts.map { h -> h(p) } }
    }

    private fun decodeBody(conn: HttpURLConnection): String =
        conn.inputStream.bufferedReader().readText()

    private fun readRawCodeBody(body: String): Lookup {
        return runCatching {
            val o = JSONObject(body)
            val status = o.optString("status", "unused")
            val reusable = o.optBoolean("reusable", false)
            if (status.equals("used", ignoreCase = true) && !reusable) Lookup.Used
            else {
                val eff = o.optJSONObject("effect")
                val value = if (eff != null &&
                    eff.optString("type", "quota").equals("quota", ignoreCase = true)
                ) eff.optLong("value", 0L) else 0L
                // read-only mirrors have no sha; mark-used then falls back to upload.
                Lookup.Looked(null, value, reusable)
            }
        }.getOrDefault(Lookup.Invalid)
    }

    private fun lookup(code: String, tok: String): Lookup {
        var lastTimeout = false
        var lastForbidden = false
        repeat(MAX_NET_TRIES) { attempt ->
            var conn: HttpURLConnection? = null
            try {
                // 1) authoritative Contents API (provides sha + auth). Try bare
                //    "<code>.json" first, legacy "redeem_<code>.json" second.
                val apiUrl = "https://api.github.com/repos/$REPO/contents/redeem/codes/$code.json"
                conn = (URL(apiUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = CONNECT_MS; readTimeout = READ_MS
                    setRequestProperty("Authorization", "token $tok")
                    setRequestProperty("Accept", "application/vnd.github+json")
                }
                when (conn.responseCode) {
                    404 -> {
                        // try legacy name, then raw mirrors below.
                        conn.disconnect()
                        val legacy = "https://api.github.com/repos/$REPO/contents/redeem/codes/redeem_$code.json"
                        conn = (URL(legacy).openConnection() as HttpURLConnection).apply {
                            connectTimeout = CONNECT_MS; readTimeout = READ_MS
                            setRequestProperty("Authorization", "token $tok")
                            setRequestProperty("Accept", "application/vnd.github+json")
                        }
                        if (conn.responseCode == 404) return Lookup.Invalid
                        if (conn.responseCode == 403) return Lookup.Forbidden
                    }
                    403 -> return Lookup.Forbidden
                    200 -> {
                        val j = JSONObject(decodeBody(conn))
                        val sha = j.optString("sha", "").ifBlank { null }
                        val raw = String(
                            android.util.Base64.decode(j.getString("content"), android.util.Base64.DEFAULT),
                        )
                        val o = JSONObject(raw)
                        val status = o.optString("status", "unused")
                        val reusable = o.optBoolean("reusable", false)
                    if (status.equals("used", ignoreCase = true) && !reusable) return Lookup.Used
                        val eff = o.optJSONObject("effect")
                        val value = if (eff != null &&
                            eff.optString("type", "quota").equals("quota", ignoreCase = true)
                        ) eff.optLong("value", 0L) else 0L
                        return Lookup.Looked(sha, value, reusable)
                    }
                    in 500..599 -> { /* fall through to mirrors this attempt */ }
                }
                // 2/3) read-only mirrors (used when api 5xx or unreachable).
                for (m in rawMirrors(code)) {
                    conn?.disconnect()
                    var mconn: HttpURLConnection? = null
                    try {
                        mconn = (URL(m).openConnection() as HttpURLConnection).apply {
                            connectTimeout = CONNECT_MS; readTimeout = READ_MS
                        }
                        when (mconn.responseCode) {
                            200 -> return readRawCodeBody(decodeBody(mconn))
                            404 -> return Lookup.Invalid
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "mirror $m failed: ${e.message}")
                    } finally {
                        mconn?.disconnect()
                    }
                }
            } catch (e: SocketTimeoutException) {
                lastTimeout = true
                Log.w(TAG, "lookup try ${attempt + 1} timeout: ${e.message}")
            } catch (e: java.net.ConnectException) {
                lastNoNetwork = true
                Log.w(TAG, "lookup try ${attempt + 1} no network: ${e.message}")
            } catch (e: Exception) {
                Log.w(TAG, "lookup try ${attempt + 1} error: ${e.message}")
            } finally {
                conn?.disconnect()
            }
        }
        return when {
            lastForbidden -> Lookup.Forbidden
            lastTimeout -> Lookup.Timeout
            else -> Lookup.NoNetwork
        }
    }

    fun redeem(context: Context, rawCode: String): Result {
        val code = rawCode.trim()
        if (code.isEmpty()) return Result.Invalid
        val store = SecureTokenStore(context)
        val provider = CloudConfig.Provider.GITHUB
        // et.146: 未做 GitHub 设备登录时，回退到内置 gallery 令牌；该令牌对
        // LocalDreamET-Quota 仓有读写权限，兑换码不需要用户先登录。
        var tok = store.token(provider)
        if (tok.isNullOrBlank()) tok = io.github.xororz.localdream.BuildConfig.GALLERY_TOKEN
        if (tok.isNullOrBlank()) return Result.NeedLogin

        lastNoNetwork = false
        lastForbidden = false
        return when (val l = lookup(code, tok)) {
            is Lookup.Looked -> {
                var credit = 0L
                if (l.effectValue > 0L) {
                    QuotaManager.bonus(context, l.effectValue)
                    credit = l.effectValue
                }
                // Flip status to used + write a used record. Report a distinct
                // failure if the write fails (never silently "succeeds").
                // et.147: reusable 卡密不翻转 used 状态（群成员奖励可重复使用）。
                val writeErr = if (l.reusable) null else runCatching {
                    val aid = android.provider.Settings.Secure.getString(
                        context.contentResolver, android.provider.Settings.Secure.ANDROID_ID,
                    ) ?: "anon"
                    val ts = System.currentTimeMillis()
                    val marked = JSONObject().apply {
                        put("status", "used")
                        put("used_by", aid)
                        put("used_ts", ts)
                    }.toString()
                    if (l.sha != null) {
                        // et.144: update the ACTUAL file that was read (bare <code>.json),
                        // not the legacy redeem_ prefixed name, so status flips correctly.
                        CloudClient.updateContent(
                            provider, tok, "ETQWFD", REPO,
                            "redeem/codes/$code.json", marked.toByteArray(), l.sha, "redeem",
                        )
                    } else {
                        // read-only mirror gave no sha; just append the used record.
                        val rec = """{"code":"$code","androidId":"$aid","ts":$ts,"auto":true}"""
                        CloudClient.uploadContent(
                            provider, tok, "ETQWFD", REPO,
                            "redeem/used/used_${code}_$aid.json", rec.toByteArray(), "used",
                        )
                    }
                }.exceptionOrNull()
                if (writeErr != null) {
                    Log.w(TAG, "mark-used failed: ${writeErr.message}")
                    return Result.WriteFailed(writeErr.message ?: "未知错误")
                }
                Result.Ok(credit)
            }
            Lookup.Used -> Result.Used
            Lookup.Invalid -> Result.Invalid
            Lookup.Timeout -> Result.Timeout
            Lookup.NoNetwork -> Result.NoNetwork
            Lookup.Forbidden -> Result.Forbidden
        }
    }
}
