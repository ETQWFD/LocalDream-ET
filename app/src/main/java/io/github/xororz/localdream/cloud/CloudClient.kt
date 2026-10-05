package io.github.xororz.localdream.cloud

import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Real OAuth + REST calls for GitHub and Gitee private backup.
 *
 * No credential is baked in: [CloudConfig] supplies empty strings by default, in
 * which case callers must not invoke these methods. All network calls run on
 * IO dispatchers with short timeouts and are wrapped in runCatching by callers.
 */
object CloudClient {

    data class CloudUser(val login: String, val displayName: String)

    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    private val FORM_MEDIA = "application/x-www-form-urlencoded".toMediaType()

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    /**
     * Validate a user-pasted Personal Access Token by calling the user API.
     * Returns the CloudUser on success; throws (with the HTTP code) on failure
     * (e.g. 401 bad token). No OAuth client secret or redirect is involved.
     */
    fun validatePAT(provider: CloudConfig.Provider, token: String): CloudUser =
        fetchUser(provider, token)

    /** Fetch the authenticated user profile. */
    fun fetchUser(provider: CloudConfig.Provider, token: String): CloudUser {
        val req = when (provider) {
            CloudConfig.Provider.GITHUB -> Request.Builder()
                .url("https://api.github.com/user")
                .addHeader("Authorization", "Bearer $token")
                .addHeader("Accept", "application/vnd.github+json")
                .get()
            CloudConfig.Provider.GITEE -> Request.Builder()
                .url("https://gitee.com/api/v5/user?access_token=$token")
                .get()
        }
        client.newCall(req.build()).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) error("user HTTP ${resp.code}: $text")
            val json = JSONObject(text)
            val login = json.optString("login").ifBlank { json.optString("username") }
            val name = json.optString("name").ifBlank { login }
            if (login.isBlank()) error("no login in response: $text")
            return CloudUser(login, name)
        }
    }

    /** Ensure the private backup repo exists (create if 404), return owner/repo. */
    fun ensurePrivateRepo(provider: CloudConfig.Provider, token: String, owner: String): Pair<String, String> {
        val repo = CloudConfig.BACKUP_REPO
        // Try GET first; 200 => reuse.
        val getReq = when (provider) {
            CloudConfig.Provider.GITHUB -> Request.Builder()
                .url("https://api.github.com/repos/$owner/$repo")
                .addHeader("Authorization", "Bearer $token").get()
            CloudConfig.Provider.GITEE -> Request.Builder()
                .url("https://gitee.com/api/v5/repos/$owner/$repo?access_token=$token").get()
        }
        client.newCall(getReq.build()).execute().use { if (it.isSuccessful) return owner to repo }

        // Create private repo.
        val createBody = JSONObject()
            .put("name", repo)
            .put("private", true)
            .put("description", "LocalDream ET auto backup")
            .toString()
        val req = when (provider) {
            CloudConfig.Provider.GITHUB -> Request.Builder()
                .url("https://api.github.com/user/repos")
                .addHeader("Authorization", "Bearer $token")
                .addHeader("Accept", "application/vnd.github+json")
                .post(createBody.toRequestBody(JSON_MEDIA))
            CloudConfig.Provider.GITEE -> Request.Builder()
                .url("https://gitee.com/api/v5/user/repos?access_token=$token")
                .addHeader("Content-Type", "application/json")
                .post(createBody.toRequestBody(JSON_MEDIA))
        }
        client.newCall(req.build()).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) error("create repo HTTP ${resp.code}: $text")
        }
        return owner to repo
    }

    /**
     * Upload bytes to <owner>/<repo>/<path> via the contents API. If the path
     * already exists (422 / non-200 due to conflict), append a numeric suffix and
     * retry a few times rather than overwriting. Returns the final path.
     */
    fun uploadContent(
        provider: CloudConfig.Provider,
        token: String,
        owner: String,
        repo: String,
        path: String,
        bytes: ByteArray,
        message: String,
    ): String {
        val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        var candidate = path
        repeat(6) { attempt ->
            val body = JSONObject()
                .put("message", message)
                .put("content", b64)
                .toString()
            val req = when (provider) {
                CloudConfig.Provider.GITHUB -> Request.Builder()
                    .url("https://api.github.com/repos/$owner/$repo/contents/$candidate")
                    .addHeader("Authorization", "Bearer $token")
                    .addHeader("Accept", "application/vnd.github+json")
                    .put(body.toRequestBody(JSON_MEDIA))
                CloudConfig.Provider.GITEE -> Request.Builder()
                    .url("https://gitee.com/api/v5/repos/$owner/$repo/contents/$candidate?access_token=$token")
                    .put(body.toRequestBody(JSON_MEDIA))
            }
            client.newCall(req.build()).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (resp.isSuccessful) return "$owner/$repo/$candidate"
                // Conflict: try a suffixed filename.
                candidate = withExt(path, attempt + 1)
            }
        }
        error("upload failed after retries")
    }

    private fun withExt(path: String, idx: Int): String {
        val dot = path.lastIndexOf('.')
        return if (dot > 0) path.substring(0, dot) + "_$idx" + path.substring(dot)
        else "${path}_$idx"
    }

    private fun enc(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")
}
