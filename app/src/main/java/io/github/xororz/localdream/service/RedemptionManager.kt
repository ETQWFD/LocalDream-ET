package io.github.xororz.localdream.service

import android.content.Context
import android.util.Log
import io.github.xororz.localdream.cloud.CloudClient
import io.github.xororz.localdream.cloud.CloudConfig
import io.github.xororz.localdream.cloud.SecureTokenStore
import org.json.JSONObject

/**
 * et.119/build197 prize redemption; hardened in et.120/build198.
 *
 * Flow: read redeem/codes/redeem_<code>.json via the GitHub Contents API (same
 * quota repo), 30s timeouts, retry up to 2 extra times on a network failure.
 * If `status:"unused"`, apply its `effect` locally (quota credit), then best-effort
 * mark it `used` and write a used_<code>_<aid>.json record.
 *
 * Results are split so the UI can show the right Chinese message:
 *  - Ok(credit): redeemed
 *  - Used: code exists but already redeemed -> "该兑换码已使用"
 *  - Invalid: code not found / malformed -> "兑换码无效"
 *  - NeedLogin: no GitHub token -> prompt login first
 *  - Network: timed out / unreachable -> retry message
 */
object RedemptionManager {
    private const val TAG = "Redemption"
    private const val REPO = "ETQWFD/LocalDreamET-Quota"
    private const val TIMEOUT_MS = 30000
    private const val MAX_NET_TRIES = 3 // initial + 2 retries

    sealed class Result {
        data class Ok(val credit: Long) : Result()
        object Used : Result()
        object Invalid : Result()
        object NeedLogin : Result()
        object Network : Result()
    }

    private sealed class Lookup {
        data class Looked(val sha: String?, val effectValue: Long) : Lookup()
        object Used : Lookup()
        object Invalid : Lookup()
        object Network : Lookup()
    }

    private fun lookup(code: String, tok: String): Lookup {
        var lastErr: Exception? = null
        repeat(MAX_NET_TRIES) { attempt ->
            var conn: java.net.HttpURLConnection? = null
            try {
                val url = "https://api.github.com/repos/$REPO/contents/redeem/codes/redeem_$code.json"
                conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                    connectTimeout = TIMEOUT_MS; readTimeout = TIMEOUT_MS
                    setRequestProperty("Authorization", "token $tok")
                    setRequestProperty("Accept", "application/vnd.github+json")
                }
                when (conn.responseCode) {
                    404 -> return Lookup.Invalid
                    200 -> {
                        val j = JSONObject(conn.inputStream.bufferedReader().readText())
                        val sha = j.optString("sha", "").ifBlank { null }
                        val raw = String(
                            android.util.Base64.decode(j.getString("content"), android.util.Base64.DEFAULT),
                        )
                        val o = JSONObject(raw)
                        val status = o.optString("status", "unused")
                        if (status.equals("used", ignoreCase = true)) return Lookup.Used
                        val eff = o.optJSONObject("effect")
                        val value = if (eff != null &&
                            eff.optString("type", "quota").equals("quota", ignoreCase = true)
                        ) eff.optLong("value", 0L) else 0L
                        return Lookup.Looked(sha, value)
                    }
                    else -> return Lookup.Invalid
                }
            } catch (e: Exception) {
                lastErr = e
                Log.w(TAG, "lookup try ${attempt + 1} failed: ${e.message}")
            } finally {
                conn?.disconnect()
            }
        }
        Log.w(TAG, "lookup gave up after $MAX_NET_TRIES tries: ${lastErr?.message}")
        return Lookup.Network
    }

    fun redeem(context: Context, rawCode: String): Result {
        val code = rawCode.trim()
        if (code.isEmpty()) return Result.Invalid
        val store = SecureTokenStore(context)
        val provider = CloudConfig.Provider.GITHUB
        val tok = store.token(provider) ?: return Result.NeedLogin

        return when (val l = lookup(code, tok)) {
            is Lookup.Looked -> {
                var credit = 0L
                if (l.effectValue > 0L) {
                    QuotaManager.bonus(context, l.effectValue)
                    credit = l.effectValue
                }
                // Best-effort: flip status to used + write a used record. Never fails redeem.
                runCatching {
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
                        // et.121/build199: update the existing code file in place (with sha)
                        // so a one-time code actually flips to used instead of duplicating.
                        CloudClient.updateContent(
                            provider, tok, "ETQWFD", REPO,
                            "redeem/codes/redeem_$code.json", marked.toByteArray(), l.sha, "redeem",
                        )
                    }
                    val rec = """{"code":"$code","androidId":"$aid","ts":$ts}"""
                    CloudClient.uploadContent(
                        provider, tok, "ETQWFD", REPO,
                        "redeem/used/used_${code}_$aid.json", rec.toByteArray(), "used",
                    )
                }.onFailure { Log.w(TAG, "mark-used best-effort failed: ${it.message}") }
                Result.Ok(credit)
            }
            Lookup.Used -> Result.Used
            Lookup.Invalid -> Result.Invalid
            Lookup.Network -> Result.Network
        }
    }
}
