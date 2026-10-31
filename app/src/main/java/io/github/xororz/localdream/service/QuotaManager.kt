package io.github.xororz.localdream.service

import android.content.Context
import android.util.Log
import io.github.xororz.localdream.cloud.CloudClient
import io.github.xororz.localdream.cloud.CloudConfig
import io.github.xororz.localdream.cloud.SecureTokenStore

/**
 * et.118/build195 quota system.
 *
 * A single local budget of [TOTAL] units per device, auto-reset every [RESET_DAYS]
 * days. Consumption scales with steps and resolution. The whole thing is a local
 * cache: reports to the public repo ETQWFD/LocalDreamET-Quota are best-effort and
 * MUST never block or fail the generation flow.
 */
object QuotaManager {
    private const val TAG = "QuotaManager"
    const val TOTAL = 1_280_000L
    private const val RESET_DAYS = 7L
    private const val SP = "quota_prefs"
    private const val QUOTA_REPO = "ETQWFD/LocalDreamET-Quota"

    private fun sp(c: Context) =
        c.getSharedPreferences(SP, Context.MODE_PRIVATE)

    private fun rollover(c: Context) {
        val now = System.currentTimeMillis()
        val epoch = sp(c).getLong("epoch_ms", 0L)
        if (epoch == 0L) {
            sp(c).edit().putLong("epoch_ms", now).apply()
            return
        }
        if ((now - epoch) / (24L * 60 * 60 * 1000) >= RESET_DAYS) {
            sp(c).edit().putLong("used", 0L).putLong("epoch_ms", now).apply()
            Log.i(TAG, "quota rolled over after $RESET_DAYS days")
        }
    }

    /** Used units (clamped), reset applied. */
    fun used(c: Context): Long {
        rollover(c)
        return sp(c).getLong("used", 0L).coerceIn(0L, TOTAL)
    }

    fun remaining(c: Context): Long = (TOTAL - used(c)).coerceAtLeast(0L)

    fun available(c: Context): Boolean = used(c) < TOTAL

    /** Cost scales with steps and megapixels; ~1 unit per 512px-step. */
    fun cost(steps: Int, width: Int, height: Int): Long {
        val mp = (width.toLong() * height.toLong()) / (512L * 512L).coerceAtLeast(1L)
        return (steps.toLong() * mp.coerceAtLeast(1L) * 200L).coerceAtLeast(200L)
    }

    fun consume(c: Context, amount: Long) {
        sp(c).edit().putLong("used", (used(c) + amount).coerceAtMost(TOTAL)).apply()
    }

    fun refund(c: Context, amount: Long) {
        sp(c).edit().putLong("used", (used(c) - amount).coerceAtLeast(0L)).apply()
    }

    /** et.119/build197: credit quota from a redemption code (lowers used, floor 0). */
    fun bonus(c: Context, value: Long) {
        if (value <= 0L) return
        sp(c).edit().putLong("used", (used(c) - value).coerceAtLeast(0L)).apply()
    }

    /** Next reset instant (epoch_ms + 7 days from current epoch); 0 if unset. */
    fun resetEpochMs(c: Context): Long {
        rollover(c)
        val epoch = sp(c).getLong("epoch_ms", 0L)
        return if (epoch == 0L) 0L else epoch + RESET_DAYS * 24L * 60 * 60 * 1000
    }

    /**
     * et.118/build196: on startup, read the latest usage record this device pushed
     * to the quota repo and adopt it if the remote used >= local used. This keeps
     * the budget consistent after a reinstall / app-data wipe. Non-blocking; any
     * failure is logged and the local value stays the source.
     */
    fun mergeFromRemote(c: Context) {
        val aid = runCatching {
            android.provider.Settings.Secure.getString(
                c.contentResolver, android.provider.Settings.Secure.ANDROID_ID,
            )
        }.getOrNull() ?: return
        Thread {
            runCatching {
                val store = SecureTokenStore(c)
                val tok = store.token(CloudConfig.Provider.GITHUB) ?: return@Thread
                val listConn = (java.net.URL("https://api.github.com/repos/$QUOTA_REPO/contents/usage")
                    .openConnection() as java.net.HttpURLConnection).apply {
                    connectTimeout = 8000; readTimeout = 8000
                    setRequestProperty("Authorization", "token $tok")
                    setRequestProperty("Accept", "application/vnd.github+json")
                }
                if (listConn.responseCode != 200) return@Thread
                val arr = org.json.JSONArray(listConn.inputStream.bufferedReader().readText())
                var bestName: String? = null
                var bestTs = 0L
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val name = o.optString("name", "")
                    if (!name.startsWith("usage_${aid}_")) continue
                    val ts = name.substringAfterLast('_').substringBefore('.').toLongOrNull() ?: continue
                    if (ts > bestTs) { bestTs = ts; bestName = name }
                }
                val n = bestName ?: return@Thread
                val c2 = (java.net.URL("https://api.github.com/repos/$QUOTA_REPO/contents/usage/$n")
                    .openConnection() as java.net.HttpURLConnection).apply {
                    connectTimeout = 8000; readTimeout = 8000
                    setRequestProperty("Authorization", "token $tok")
                    setRequestProperty("Accept", "application/vnd.github+json")
                }
                if (c2.responseCode != 200) return@Thread
                val j = org.json.JSONObject(c2.inputStream.bufferedReader().readText())
                val raw = String(android.util.Base64.decode(j.getString("content"), android.util.Base64.DEFAULT))
                val remoteUsed = org.json.JSONObject(raw).optLong("used", 0L)
                val localUsed = used(c)
                if (remoteUsed > localUsed) {
                    sp(c).edit().putLong("used", remoteUsed).apply()
                    Log.i(TAG, "merged remote used=$remoteUsed (local was $localUsed)")
                }
            }.onFailure { Log.w(TAG, "mergeFromRemote failed (non-blocking): ${it.message}") }
        }.start()
    }

    /**
     * Best-effort report: upload one timestamped JSON record to the quota repo.
     * Never throws, never blocks the caller. On failure the local cache is still
     * the source of truth.
     */
    fun report(c: Context) {
        val usedNow = used(c)
        val ts = System.currentTimeMillis()
        val aid = runCatching {
            android.provider.Settings.Secure.getString(
                c.contentResolver, android.provider.Settings.Secure.ANDROID_ID,
            )
        }.getOrNull() ?: "anon"
        Thread {
            runCatching {
                val store = SecureTokenStore(c)
                val provider = CloudConfig.Provider.GITHUB
                val tok = store.token(provider) ?: return@Thread
                val auth = store.state(provider)
                val ip = runCatching {
                    java.net.URL("https://api.ipify.org").openConnection().apply {
                        connectTimeout = 4000; readTimeout = 4000
                    }.getInputStream().bufferedReader().readText()
                }.getOrNull() ?: ""
                val json = """{"androidId":"$aid","model":"${android.os.Build.MODEL}","ip":"$ip","login":"${auth?.login ?: ""}","repo":"${auth?.repo ?: ""}","used":$usedNow,"total":$TOTAL,"ts":$ts}"""
                CloudClient.uploadContent(
                    provider, tok, auth?.owner ?: auth?.login ?: "anon",
                    QUOTA_REPO, "usage/usage_${aid}_$ts.json", json.toByteArray(), "usage",
                )
            }.onFailure { Log.w(TAG, "quota report failed (non-blocking): ${it.message}") }
        }.start()
    }
}
