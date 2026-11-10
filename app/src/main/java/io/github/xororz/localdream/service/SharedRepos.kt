package io.github.xororz.localdream.service

import android.content.Context
import io.github.xororz.localdream.BuildConfig
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
 * Bootstraps the two shared community repositories with the owner token.
 *
 * The app must never show "仓库不存在" to a normal user: if the chat repo,
 * gallery repo or their seed files were deleted, this recreates them (public,
 * auto-init) on the first visit. Errors are reported once and never block the
 * screen.
 */
object SharedRepos {
    const val CHAT = "LocalDreamET-Chat"
    const val GALLERY = "LocalDreamET-Gallery"
    private const val OWNER = "ETQWFD"

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var bootstrapped = false

    private fun token(): String = BuildConfig.GALLERY_TOKEN

    private fun req(path: String): Request.Builder =
        Request.Builder().url("https://api.github.com$path")
            .addHeader("Authorization", "Bearer ${token()}")
            .addHeader("Accept", "application/vnd.github+json")
            .addHeader("User-Agent", "LocalDream-ET")

    private fun createRepo(name: String): Boolean {
        val body = JSONObject()
            .put("name", name)
            .put("description", "LocalDream ET shared community data")
            .put("private", false)
            .put("auto_init", true)
            .toString()
            .toRequestBody("application/json".toMediaType())
        return runCatching {
            client.newCall(
                Request.Builder().url("https://api.github.com/user/repos")
                    .post(body)
                    .addHeader("Authorization", "Bearer ${token()}")
                    .addHeader("Accept", "application/vnd.github+json")
                    .addHeader("User-Agent", "LocalDream-ET")
                    .build(),
            ).execute().use { it.isSuccessful || it.code == 422 }
        }.getOrDefault(false)
    }

    private fun ensureRepo(name: String) {
        runCatching {
            client.newCall(req("/repos/$OWNER/$name").get().build()).execute().use {
                if (it.code != 404) return@use
            }
            createRepo(name)
        }
    }

    private fun ensureFile(repo: String, path: String, content: String, message: String) {
        runCatching {
            client.newCall(req("/repos/$OWNER/$repo/contents/$path").get().build()).execute().use {
                if (it.isSuccessful) return@use
                if (it.code != 404) return@use
            }
            val body = JSONObject()
                .put("message", message)
                .put("content", Base64.getEncoder().encodeToString(content.toByteArray()))
                .toString()
                .toRequestBody("application/json".toMediaType())
            client.newCall(req("/repos/$OWNER/$repo/contents/$path").put(body).build())
                .execute().use { }
        }
    }

    /** Idempotent; safe to call from any screen. */
    suspend fun ensure(context: Context): String? = withContext(Dispatchers.IO) {
        if (bootstrapped) return@withContext null
        if (token().isBlank()) return@withContext "构建缺少内置令牌，无法建立共享仓库"
        ensureRepo(CHAT)
        ensureRepo(GALLERY)
        ensureFile(CHAT, "messages.json", "[]", "init: shared chat")
        ensureFile(CHAT, "admin.json", "{\"admins\":[\"ETQWFD\"],\"mutes\":{}}", "init: admins")
        ensureFile(
            GALLERY,
            "README.md",
            "# LocalDreamET-Gallery\n\nLocalDream ET 图片社区共享仓库，所有作品由 App 上传。\n",
            "init: gallery readme",
        )
        bootstrapped = true
        null
    }
}
