package io.github.xororz.localdream.chat

import android.content.Context
import io.github.xororz.localdream.BuildConfig
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/** A user's public homepage, stored in the shared chat repo. */
data class ChatProfile(
    val login: String,
    val nickname: String = "",
    val bio: String = "",
    val avatar: String = "",
    val updated: Long = 0L,
) {
    val displayName: String get() = nickname.ifBlank { login }
}

object ChatProfileRepo {
    private const val OWNER = "ETQWFD"
    private const val REPO = "LocalDreamET-Chat"
    private const val DIR = "profiles"
    private val cache = ConcurrentHashMap<String, ChatProfile>()
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    private fun path(login: String) = "$DIR/${safe(login)}.json"

    /** GitHub paths must be URL-safe and stable across devices. */
    fun safe(login: String): String =
        login.trim().lowercase().replace(Regex("[^a-z0-9._-]"), "_").ifBlank { "unknown" }

    suspend fun load(login: String): ChatProfile = withContext(Dispatchers.IO) {
        if (login.isBlank()) return@withContext ChatProfile("")
        cache[login]?.let { return@withContext it }
        val url = "https://api.github.com/repos/$OWNER/$REPO/contents/${path(login)}"
        val req = Request.Builder().url(url)
            .addHeader("Authorization", "Bearer ${BuildConfig.GALLERY_TOKEN}")
            .addHeader("Accept", "application/vnd.github+json")
            .addHeader("User-Agent", "LocalDream-ET")
            .build()
        runCatching {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use
                val obj = JSONObject(resp.body?.string().orEmpty())
                val b64 = obj.optString("content", "").replace("\n", "")
                if (b64.isBlank()) return@use
                val p = parse(String(Base64.getDecoder().decode(b64), Charsets.UTF_8), login)
                cache[login] = p
            }
        }
        cache[login] ?: ChatProfile(login)
    }

    private fun parse(raw: String, login: String): ChatProfile = runCatching {
        val o = JSONObject(raw)
        ChatProfile(
            login = login,
            nickname = o.optString("nickname", ""),
            bio = o.optString("bio", ""),
            avatar = o.optString("avatar", ""),
            updated = o.optLong("updated", 0L),
        )
    }.getOrDefault(ChatProfile(login))

    suspend fun save(ctx: Context, profile: ChatProfile): Result<Unit> = withContext(Dispatchers.IO) {
        val token = BuildConfig.GALLERY_TOKEN.ifBlank { ChatRepository.writeToken(ctx) }
        if (token.isBlank()) return@withContext Result.failure(IllegalStateException("缺少上传令牌"))
        val url = "https://api.github.com/repos/$OWNER/$REPO/contents/${path(profile.login)}"
        repeat(3) {
            val sha = runCatching {
                val req = Request.Builder().url(url)
                    .addHeader("Authorization", "Bearer $token")
                    .addHeader("User-Agent", "LocalDream-ET")
                    .build()
                client.newCall(req).execute().use { r ->
                    if (r.isSuccessful) JSONObject(r.body?.string().orEmpty()).optString("sha", "") else ""
                }
            }.getOrDefault("")
            val payload = JSONObject()
                .put("login", profile.login)
                .put("nickname", profile.nickname)
                .put("bio", profile.bio)
                .put("avatar", profile.avatar)
                .put("updated", System.currentTimeMillis() / 1000L)
                .toString()
            val body = JSONObject()
                .put("message", "profile: ${profile.login}")
                .put("content", Base64.getEncoder().encodeToString(payload.toByteArray()))
                .apply { if (sha.isNotBlank()) put("sha", sha) }
                .toString()
            val req = Request.Builder().url(url)
                .put(body.toRequestBody("application/json".toMediaType()))
                .addHeader("Authorization", "Bearer $token")
                .addHeader("User-Agent", "LocalDream-ET")
                .build()
            val ok = runCatching {
                client.newCall(req).execute().use { r -> r.isSuccessful }
            }.getOrDefault(false)
            if (ok) {
                cache[profile.login] = profile
                return@withContext Result.success(Unit)
            }
            Thread.sleep(250L)
        }
        Result.failure(IllegalStateException("主页保存失败"))
    }

    fun cached(login: String): ChatProfile? = cache[login]
}
