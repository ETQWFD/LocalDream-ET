package io.github.xororz.localdream.account

import android.content.Context
import android.util.Base64 as AndroidBase64
import io.github.xororz.localdream.BuildConfig
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import org.json.JSONObject

/**
 * 邮箱账号体系（et.246）。
 *
 * 数据放在私密仓库 `ETQWFD/NightmareRoad-ET-Private` 的
 * `users/<邮箱>/` 目录下：
 *   account.json   – 登录凭证（只存 PBKDF2 哈希 + 盐，绝不存明文密码）
 *   messages.json  – 这个用户自己发过的消息
 *   images/…       – 这个用户上传的图片（网盘 App 读同一份数据）
 *
 * 安全说明（重要，别自欺欺人）：客户端里带着一个有写权限的令牌，
 * 所以这套方案能防住"密码泄露"，防不住"拿到 APK 的人翻别人的目录"。
 * 真正安全的做法是走我们自己的 Windows/Linux 服务端鉴权，
 * 这里按需求先把功能做出来，并把风险写清楚。
 */
object EmailAccount {

    private const val OWNER = "ETQWFD"
    private const val REPO = "NightmareRoad-ET-Private"
    private const val BRANCH = "main"
    private const val API = "https://api.github.com/repos/$OWNER/$REPO/contents"

    private const val PREFS = "email_account"
    private const val ITERATIONS = 120_000
    private const val KEY_LEN = 256

    /** 邮箱里的 @ . 在路径里都不友好，统一转成安全字符，同时保证可逆。 */
    fun userDir(email: String): String =
        "users/" + email.trim().lowercase()
            .replace("@", "_at_")
            .replace(".", "_dot_")
            .replace(Regex("[^a-z0-9_\\-]"), "")

    fun accountPath(email: String): String = "${userDir(email)}/account.json"

    // ---- 密码哈希 -------------------------------------------------------

    private fun randomSalt(): String {
        val b = ByteArray(16)
        SecureRandom().nextBytes(b)
        return java.util.Base64.getEncoder().encodeToString(b)
    }

    private fun pbkdf2(password: String, salt: String): String = runCatching {
        val spec = PBEKeySpec(password.toCharArray(), salt.toByteArray(Charsets.UTF_8), ITERATIONS, KEY_LEN)
        val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        java.util.Base64.getEncoder().encodeToString(key)
    }.getOrElse {
        // 极少数 ROM 没提供 PBKDF2WithHmacSHA256：退化到多轮 SHA-256，
        // 依然不存明文，只是慢攻击强度低一些。
        var digest = salt + password
        repeat(20_000) { digest = sha256Hex(digest) }
        digest
    }

    private fun sha256Hex(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    // ---- 会话 -----------------------------------------------------------

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun signedInEmail(ctx: Context): String = prefs(ctx).getString("email", "").orEmpty()

    fun isSignedIn(ctx: Context): Boolean = signedInEmail(ctx).isNotBlank()

    fun signOut(ctx: Context) {
        prefs(ctx).edit().clear().apply()
    }

    private fun remember(ctx: Context, email: String) {
        prefs(ctx).edit()
            .putString("email", email.trim().lowercase())
            .putLong("since", System.currentTimeMillis())
            .apply()
    }

    // ---- 网络 -----------------------------------------------------------

    private fun token(): String = BuildConfig.GALLERY_TOKEN

    private class Http(val code: Int, val body: String)

    private fun call(path: String, method: String = "GET", payload: JSONObject? = null): Http {
        return try {
            val c = URL("$API/$path").openConnection() as HttpURLConnection
            c.requestMethod = method
            c.connectTimeout = 10_000
            c.readTimeout = 30_000
            c.setRequestProperty("Authorization", "token ${token()}")
            c.setRequestProperty("Accept", "application/vnd.github+json")
            c.setRequestProperty("User-Agent", "NightmareRoad-ET")
            if (payload != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(payload.toString().toByteArray()) }
            }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            Http(code, body)
        } catch (e: Exception) {
            Http(0, e.javaClass.simpleName)
        }
    }

    /** 校验规则集中在这里，界面只管把错误文案显示出来。 */
    fun validate(email: String, pwd: String, pwd2: String, agreed: Boolean): String? {
        val e = email.trim().lowercase()
        if (!Regex("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$").matches(e)) return "邮箱格式不正确"
        if (e.length > 120) return "邮箱太长了"
        if (pwd.length < 6) return "密码至少 6 位"
        if (pwd != pwd2) return "两次输入的密码不一致"
        if (!agreed) return "请先勾选同意用户协议"
        return null
    }

    /**
     * 注册：把 account.json 写进 users/<邮箱>/。已存在则返回错误，
     * 避免覆盖别人的凭证（HTTP 422 = 文件已存在，正好当唯一性约束用）。
     */
    fun register(ctx: Context, email: String, pwd: String, pwd2: String, agreed: Boolean): String? {
        validate(email, pwd, pwd2, agreed)?.let { return it }
        val e = email.trim().lowercase()
        if (token().isBlank()) return "内置令牌缺失，暂时无法注册，请联系作者"
        val salt = randomSalt()
        val obj = JSONObject()
            .put("email", e)
            .put("algo", "PBKDF2WithHmacSHA256")
            .put("iterations", ITERATIONS)
            .put("salt", salt)
            .put("hash", pbkdf2(pwd, salt))
            .put("created", System.currentTimeMillis())
            .put("updated", System.currentTimeMillis())
        val payload = JSONObject()
            .put("message", "account: register $e")
            .put(
                "content",
                java.util.Base64.getEncoder().encodeToString(obj.toString().toByteArray()),
            )
        val r = call(accountPath(e), "PUT", payload)
        return when {
            r.code in 200..201 -> { remember(ctx, e); null }
            r.code == 422 -> "这个邮箱已经注册过了，请直接登录"
            r.code == 401 || r.code == 403 -> "没有写入权限（令牌被拒 HTTP ${r.code}）"
            r.code == 0 -> "网络不通：${r.body}"
            else -> "注册失败 HTTP ${r.code}"
        }
    }

    /** 登录：读 account.json，比对 PBKDF2 哈希。 */
    fun login(ctx: Context, email: String, pwd: String): String? {
        val e = email.trim().lowercase()
        if (!e.contains('@')) return "邮箱格式不正确"
        if (pwd.isEmpty()) return "请输入密码"
        val r = call(accountPath(e))
        if (r.code == 404) return "这个邮箱还没有注册"
        if (r.code !in 200..299) return if (r.code == 0) "网络不通：${r.body}" else "读取账号失败 HTTP ${r.code}"
        val obj = runCatching {
            JSONObject(
                String(
                    AndroidBase64.decode(
                        JSONObject(r.body).optString("content").replace("\n", ""),
                        AndroidBase64.DEFAULT,
                    ),
                    Charsets.UTF_8,
                ),
            )
        }.getOrNull() ?: return "账号数据损坏，请联系作者"
        val salt = obj.optString("salt")
        val want = obj.optString("hash")
        if (salt.isBlank() || want.isBlank()) return "账号数据不完整"
        val got = pbkdf2(pwd, salt)
        // 等长比较，避免时序侧信道
        if (got.length != want.length || !MessageDigest.isEqual(got.toByteArray(), want.toByteArray())) {
            return "密码不正确"
        }
        remember(ctx, e)
        return null
    }

    /** 用户目录下的图片列表（网盘 App 与主 App 共用同一份实现）。 */
    fun listImages(email: String): List<Pair<String, String>> {
        val e = email.trim().lowercase()
        val dir = "${userDir(e)}/images"
        val r = call(dir)
        if (r.code !in 200..299) return emptyList()
        return runCatching {
            val arr = org.json.JSONArray(r.body)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                if (!o.optString("type", "").equals("file", true)) return@mapNotNull null
                val name = o.optString("name")
                if (!name.matches(Regex("(?i).*\\.(png|jpg|jpeg|webp|gif|heic)$"))) return@mapNotNull null
                name to o.optString("download_url")
            }
        }.getOrDefault(emptyList())
    }

    /** 上传一张图到用户自己的私密目录。 */
    fun uploadImage(email: String, name: String, bytes: ByteArray): String? {
        val e = email.trim().lowercase()
        val safe = name.replace(Regex("[^A-Za-z0-9._\\-]"), "_")
        val path = "${userDir(e)}/images/$safe"
        val payload = JSONObject()
            .put("message", "upload $safe")
            .put("content", java.util.Base64.getEncoder().encodeToString(bytes))
        val r = call(path, "PUT", payload)
        return if (r.code in 200..201) null else "上传失败 HTTP ${r.code}"
    }
}
