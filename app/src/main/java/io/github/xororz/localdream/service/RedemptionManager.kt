package io.github.xororz.localdream.service

import android.content.Context
import android.util.Log
import io.github.xororz.localdream.cloud.CloudClient
import io.github.xororz.localdream.cloud.CloudConfig
import io.github.xororz.localdream.cloud.SecureTokenStore
import org.json.JSONObject

/**
 * et.119/build197 prize redemption.
 *
 * Flow: read redeem/codes/redeem_<code>.json in the public quota repo. If it is
 * `status:"unused"`, apply its `effect` locally (quota credit), then best-effort
 * mark it `used` and write a used_<code>_<aid>.json record. The local quota credit
 * always wins; remote bookkeeping failures are logged, not surfaced as errors.
 *
 * NOTE on auth: code lookup uses the user's logged-in GitHub token (unauthenticated
 * GitHub API is too rate-limited for a redeemed flow). No repo PAT is embedded in
 * the APK or committed to git — if the account lacks write to the quota repo the
 * remote mark-used step silently skips, but the prize is still credited locally.
 */
object RedemptionManager {
    private const val TAG = "Redemption"
    private const val REPO = "ETQWFD/LocalDreamET-Quota"

    sealed class Result {
        data class Ok(val credit: Long) : Result()
        object Invalid : Result()
        object NeedLogin : Result()
    }

    fun redeem(context: Context, rawCode: String): Result {
        val code = rawCode.trim()
        if (code.isEmpty()) return Result.Invalid
        val store = SecureTokenStore(context)
        val provider = CloudConfig.Provider.GITHUB
        val tok = store.token(provider) ?: return Result.NeedLogin

        var conn: java.net.HttpURLConnection? = null
        var sha: String? = null
        var currentStatus: String? = null
        var credit = 0L
        try {
            val url = "https://api.github.com/repos/$REPO/contents/redeem/codes/redeem_$code.json"
            conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                connectTimeout = 8000; readTimeout = 8000
                setRequestProperty("Authorization", "token $tok")
                setRequestProperty("Accept", "application/vnd.github+json")
            }
            when (conn.responseCode) {
                404 -> return Result.Invalid
                200 -> Unit
                else -> return Result.Invalid
            }
            val j = JSONObject(conn.inputStream.bufferedReader().readText())
            sha = j.optString("sha", "").ifBlank { null }
            val raw = String(android.util.Base64.decode(j.getString("content"), android.util.Base64.DEFAULT))
            val o = JSONObject(raw)
            currentStatus = o.optString("status", "unused")
            if (currentStatus.equals("used", ignoreCase = true)) return Result.Invalid

            val effect = o.optJSONObject("effect")
            if (effect != null && effect.optString("type", "quota").equals("quota", ignoreCase = true)) {
                credit = effect.optLong("value", 0L)
                if (credit > 0L) QuotaManager.bonus(context, credit)
            }
        } catch (e: Exception) {
            Log.w(TAG, "redeem lookup failed: ${e.message}")
            return Result.Invalid
        } finally {
            conn?.disconnect()
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
            // Update the code file (PUT with sha). Best-effort; ignore failures.
            if (sha != null) {
                CloudClient.uploadContent(
                    provider, tok, "ETQWFD", REPO,
                    "redeem/codes/redeem_$code.json", marked.toByteArray(), "code",
                )
            }
            val rec = """{"code":"$code","androidId":"$aid","ts":$ts}"""
            CloudClient.uploadContent(
                provider, tok, "ETQWFD", REPO,
                "redeem/used/used_${code}_$aid.json", rec.toByteArray(), "used",
            )
        }.onFailure { Log.w(TAG, "mark-used best-effort failed: ${it.message}") }

        return Result.Ok(credit)
    }
}
