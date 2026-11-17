package io.github.xororz.localdream.cloud

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * GitHub OAuth Device Flow（RFC 8628）。
 *
 * 不需要 client_secret，也不需要把密码或 token 上传到任何仓库：
 * 1) 应用申请 device_code + 四位用户码；
 * 2) 用户在浏览器打开 github.com/login/device 输入码并授权；
 * 3) 应用按 interval 轮询，拿到 access_token 后直接保存到本机 Keystore。
 *
 * client_id 需要用户在 GitHub 上自行创建 OAuth App 获得（无需 secret）。
 */
object GithubDeviceAuth {
    data class DeviceCode(
        val deviceCode: String,
        val userCode: String,
        val verificationUri: String,
        val intervalSec: Int,
        val expiresInSec: Int,
    )

    sealed class Poll {
        data object Pending : Poll()
        data object SlowDown : Poll()
        data object Expired : Poll()
        data class Success(val token: String) : Poll()
        data class Failure(val error: String) : Poll()
    }

    /**
     * Web OAuth 回跳换 token。注意：client_secret 会随 APK 分发，属于公开信息，
     * 任何拿到 APK 的人都能反编译取出；仅适用于自有客户端的场景。
     */
    fun exchangeCode(clientId: String, clientSecret: String, code: String, redirectUri: String): String {
        val json = org.json.JSONObject(
            post(
                "https://github.com/login/oauth/access_token",
                mapOf(
                    "client_id" to clientId,
                    "client_secret" to clientSecret,
                    "code" to code,
                    "redirect_uri" to redirectUri,
                ),
            ),
        )
        return json.optString("access_token").takeIf { it.isNotBlank() }
            ?: throw IllegalException(json.optString("error_description", "no access_token"))
    }

    /**
     * et.243：OAuth App 只能登记一个回调地址，而历史版本登记过
     * etc.tw.kg 和 GitHub Pages 两种。这里按候选地址依次尝试换 token，
     * 只要有一个和 App 设置里的一致就能登录成功，避免“授权完回不来”。
     */
    fun exchangeCodeAnyRedirect(
        clientId: String,
        clientSecret: String,
        code: String,
        redirects: List<String>,
    ): String {
        var last: Exception? = null
        for (r in redirects) {
            try {
                val t = exchangeCode(clientId, clientSecret, code, r)
                if (t.isNotBlank()) return t
            } catch (e: Exception) {
                last = e
            }
        }
        throw (last ?: Exception("授权码无效或已过期，请重新授权"))
    }

    /** 从回跳链接（https://…/oauth.html?code=xx 或 localdreamet://oauth?code=xx）里取 code。 */
    fun parseCallbackCode(text: String): String? {
        val t = text.trim()
        if (t.isEmpty()) return null
        Regex("[?&]code=([0-9a-zA-Z]+)").find(t)?.let { return it.groupValues[1] }
        if (Regex("^[0-9a-f]{20,64}$").matches(t)) return t
        return null
    }

    private class IllegalException(msg: String) : Exception(msg)

    private fun post(url: String, form: Map<String, String>): String {
        val body = form.entries.joinToString("&") { (k, v) ->
            URLEncoder.encode(k, "UTF-8") + "=" + URLEncoder.encode(v, "UTF-8")
        }
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 8000
            readTimeout = 12000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            setRequestProperty("User-Agent", "NightmareRoad-ET")
        }
        c.outputStream.use { it.write(body.toByteArray()) }
        val text = (if (c.responseCode in 200..299) c.inputStream else c.errorStream)
            ?.bufferedReader()?.readText().orEmpty()
        c.disconnect()
        return text
    }

    fun requestDeviceCode(clientId: String, scope: String = "repo read:user"): DeviceCode {
        val json = org.json.JSONObject(
            post(
                "https://github.com/login/device/code",
                mapOf("client_id" to clientId, "scope" to scope),
            ),
        )
        val err = json.optString("error")
        require(err.isBlank()) { json.optString("error_description", err) }
        return DeviceCode(
            deviceCode = json.getString("device_code"),
            userCode = json.getString("user_code"),
            verificationUri = json.optString("verification_uri", "https://github.com/login/device"),
            intervalSec = json.optInt("interval", 5),
            expiresInSec = json.optInt("expires_in", 900),
        )
    }

    fun pollToken(clientId: String, deviceCode: String): Poll {
        val json = runCatching {
            org.json.JSONObject(
                post(
                    "https://github.com/login/oauth/access_token",
                    mapOf(
                        "client_id" to clientId,
                        "device_code" to deviceCode,
                        "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
                    ),
                ),
            )
        }.getOrElse { return Poll.Failure(it.message ?: "network") }
        json.optString("access_token").takeIf { it.isNotBlank() }?.let { return Poll.Success(it) }
        return when (val e = json.optString("error")) {
            "authorization_pending" -> Poll.Pending
            "slow_down" -> Poll.SlowDown
            "expired_token" -> Poll.Expired
            "access_denied" -> Poll.Failure("用户拒绝了授权")
            "unsupported_grant_type", "incorrect_client_credentials", "device_flow_disabled" ->
                Poll.Failure(json.optString("error_description", e))
            "" -> Poll.Failure("未知响应")
            else -> Poll.Failure(json.optString("error_description", e))
        }
    }
}
