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

    data class CloudUser(val login: String, val displayName: String, val avatarUrl: String? = null)

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
            val avatar = json.optString("avatar_url").ifBlank { json.optString("avatarUrl") }
            if (login.isBlank()) error("no login in response: $text")
            return CloudUser(login, name, avatar.ifBlank { null })
        }
    }

    /**
     * Verify an existing repo is reachable with this token (lightweight: GET
     * repo metadata + a contents probe; 404 on a sub-dir is acceptable).
     * Throws on 401/403/other failure.
     */
    fun verifyExistingRepo(provider: CloudConfig.Provider, token: String, owner: String, repo: String) {
        val getReq = when (provider) {
            CloudConfig.Provider.GITHUB -> Request.Builder()
                .url("https://api.github.com/repos/$owner/$repo")
                .addHeader("Authorization", "Bearer $token").get()
            CloudConfig.Provider.GITEE -> Request.Builder()
                .url("https://gitee.com/api/v5/repos/$owner/$repo?access_token=$token").get()
        }
        client.newCall(getReq.build()).execute().use { resp ->
            if (!resp.isSuccessful) error("repo HTTP ${resp.code}")
        }
        // Light contents probe (read png/ dir). 404 = dir not yet created is OK.
        val probe = when (provider) {
            CloudConfig.Provider.GITHUB -> Request.Builder()
                .url("https://api.github.com/repos/$owner/$repo/contents/png")
                .addHeader("Authorization", "Bearer $token").get().build()
            CloudConfig.Provider.GITEE -> Request.Builder()
                .url("https://gitee.com/api/v5/repos/$owner/$repo/contents/png?access_token=$token").get().build()
        }
        client.newCall(probe).execute().use { if (!it.isSuccessful && it.code != 404) error("contents probe HTTP ${it.code}") }
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

    // ---- et.26: GitHub OAuth Device Flow (gated on GITHUB_OAUTH_CLIENT_ID) ----

    data class DeviceCode(
        val deviceCode: String,
        val userCode: String,
        val verificationUri: String,
        val intervalSec: Int,
        val expiresInSec: Int,
    )

    /** Request a GitHub device-code. Caller must only invoke when clientId is set. */
    fun githubRequestDeviceCode(clientId: String): DeviceCode {
        val body = JSONObject()
            .put("client_id", clientId)
            .put("scope", "repo user:follow")
            .toString()
        val req = Request.Builder()
            .url("https://github.com/login/device/code")
            .addHeader("Accept", "application/json")
            .post(body.toRequestBody(JSON_MEDIA))
            .build()
        client.newCall(req).execute().use { resp ->
            val json = JSONObject(resp.body?.string().orEmpty())
            if (!resp.isSuccessful) error("device code HTTP ${resp.code}")
            return DeviceCode(
                json.optString("device_code"),
                json.optString("user_code"),
                json.optString("verification_uri"),
                json.optInt("interval", 5),
                json.optInt("expires_in", 900),
            )
        }
    }

    /** Poll the token endpoint. Returns access_token or null when still pending. */
    fun githubPollDeviceToken(clientId: String, deviceCode: String): String? {
        val body = JSONObject()
            .put("client_id", clientId)
            .put("device_code", deviceCode)
            .put("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
            .toString()
        val req = Request.Builder()
            .url("https://github.com/login/oauth/access_token")
            .addHeader("Accept", "application/json")
            .post(body.toRequestBody(JSON_MEDIA))
            .build()
        client.newCall(req).execute().use { resp ->
            val json = JSONObject(resp.body?.string().orEmpty())
            return json.optString("access_token").ifBlank { null }
        }
    }

    /** Best-effort star + follow; failures only logged, never break login. */
    fun githubStarAndFollow(token: String) {
        runCatching {
            val star = Request.Builder()
                .url("https://api.github.com/user/starred/ETQWFD/LocalDream-ET")
                .addHeader("Authorization", "Bearer $token").put("".toRequestBody(null)).build()
            client.newCall(star).execute().use { /* 204/403 both logged only */ }
        }
        runCatching {
            val follow = Request.Builder()
                .url("https://api.github.com/user/following/ETQWFD")
                .addHeader("Authorization", "Bearer $token").put("".toRequestBody(null)).build()
            client.newCall(follow).execute().use { }
        }
    }


    private fun enc(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")
}
