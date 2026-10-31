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
