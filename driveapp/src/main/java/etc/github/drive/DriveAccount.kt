package etc.github.drive

import android.content.Context
import android.util.Base64
import etc.github.drive.BuildConfig
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import org.json.JSONArray
import org.json.JSONObject

/** 与主 App（Nightmare Road-ET）同一套邮箱账号：私密仓库 users/&lt;邮箱&gt;/。 */
object DriveAccount {
    private const val OWNER = "ETQWFD"
    private const val REPO = "NightmareRoad-ET-Private"
    private const val API = "https://api.github.com/repos/$OWNER/$REPO/contents"
    private const val PREFS = "drive_account"

    fun userDir(email: String): String =
        "users/" + email.trim().lowercase()
            .replace("@", "_at_")
            .replace(".", "_dot_")
            .replace(Regex("[^a-z0-9_\\-]"), "")

    fun accountPath(email: String): String = "${userDir(email)}/account.json"

    /** 上传一张图片到 users/&lt;邮箱&gt;/images/（名字带时间戳，不会覆盖旧图）。 */
    fun uploadImage(email: String, name: String, bytes: ByteArray): Http {
        val payload = JSONObject().apply {
            put("message", "drive upload $name")
            put("content", Base64.encodeToString(bytes, Base64.NO_WRAP))
            put("branch", "main")
        }
        return call("${imagesDir(email)}/$name", "PUT", payload)
    }
    fun imagesDir(email: String): String = "${userDir(email)}/images"

    private fun token(): String = BuildConfig.GALLERY_TOKEN

    class Http(val code: Int, val body: String)

    fun call(path: String, method: String = "GET", payload: JSONObject? = null): Http {
        return try {
            val c = URL("$API/$path").openConnection() as HttpURLConnection
            c.requestMethod = method
            c.connectTimeout = 10_000
            c.readTimeout = 30_000
            c.setRequestProperty("Authorization", "token ${token()}")
            c.setRequestProperty("Accept", "application/vnd.github+json")
            c.setRequestProperty("User-Agent", "NightmareDrive")
            if (payload != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(payload.toString().toByteArray()) }
            }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            Http(code, stream?.bufferedReader()?.use { it.readText() }.orEmpty())
        } catch (e: Exception) {
            Http(0, e.javaClass.simpleName + ": " + (e.message ?: ""))
        }
    }

    private fun pbkdf2(password: String, salt: String): String = runCatching {
        val spec = PBEKeySpec(password.toCharArray(), salt.toByteArray(Charsets.UTF_8), 120_000, 256)
        Base64.encodeToString(
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded,
            Base64.NO_WRAP,
        )
    }.getOrElse {
        // 与主 App 的降级路径保持一致：多轮 SHA-256。
        var d = salt + password
        repeat(20_000) { d = MessageDigest.getInstance("SHA-256").digest(d.toByteArray()).joinToString("") { b -> "%02x".format(b) } }
        d
    }

    /** 校验规则与主 App 完全一致，避免同一个邮箱在两边得到不同结论。 */
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
     * 注册：把 account.json 写进 users/<邮箱>/，格式与主 App 一致
     * （PBKDF2WithHmacSHA256 / 120000 轮 / 标准 base64 无换行）。
     * HTTP 422 = 文件已存在，正好当"邮箱唯一"约束用。
     */
    fun register(ctx: Context, email: String, pwd: String, pwd2: String, agreed: Boolean): String? {
        validate(email, pwd, pwd2, agreed)?.let { return it }
        val e = email.trim().lowercase()
        if (token().isBlank()) return "内置令牌缺失，暂时无法注册，请联系作者"
        val salt = randomSalt()
        val obj = JSONObject()
            .put("email", e)
            .put("algo", "PBKDF2WithHmacSHA256")
            .put("iterations", 120_000)
            .put("salt", salt)
            .put("hash", pbkdf2(pwd, salt))
            .put("created", System.currentTimeMillis())
            .put("updated", System.currentTimeMillis())
        val payload = JSONObject()
            .put("message", "account: register $e (drive)")
            .put("content", java.util.Base64.getEncoder().encodeToString(obj.toString().toByteArray()))
        val r = call(accountPath(e), "PUT", payload)
        return when {
            r.code in 200..201 -> { remember(ctx, e); null }
            r.code == 422 -> "这个邮箱已经注册过了，请直接登录"
            r.code == 401 || r.code == 403 -> "没有写入权限（令牌被拒 HTTP ${r.code}）"
            r.code == 0 -> "网络不通：${r.body}"
            else -> "注册失败 HTTP ${r.code}"
        }
    }

    private fun randomSalt(): String = runCatching {
        val b = ByteArray(32)
        java.security.SecureRandom().nextBytes(b)
        Base64.encodeToString(b, Base64.NO_WRAP)
    }.getOrElse { System.currentTimeMillis().toString() }

    /** 登录校验：读 account.json 比对哈希。返回 null 表示成功。 */
    fun login(email: String, pwd: String): String? {
        if (token().isBlank()) return "内置令牌缺失，无法访问云盘，请联系作者"
        val e = email.trim().lowercase()
        if (!e.contains('@')) return "邮箱格式不正确"
        val r = call(accountPath(e))
        if (r.code == 404) return "这个邮箱还没有注册，点下面的「注册」创建一个"
        if (r.code !in 200..299) return if (r.code == 0) "网络不通：${r.body}" else "读取账号失败 HTTP ${r.code}"
        val obj = runCatching {
            JSONObject(
                String(
                    Base64.decode(JSONObject(r.body).optString("content").replace("\n", ""), Base64.DEFAULT),
                    Charsets.UTF_8,
                ),
            )
        }.getOrNull() ?: return "账号数据损坏"
        val salt = obj.optString("salt")
        val want = obj.optString("hash")
        if (salt.isBlank() || want.isBlank()) return "账号数据不完整"
        val got = pbkdf2(pwd, salt)
        if (got.length != want.length || !MessageDigest.isEqual(got.toByteArray(), want.toByteArray())) {
            return "密码不正确"
        }
        return null
    }

    fun remember(ctx: Context, email: String) {
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("email", email.trim().lowercase()).apply()
    }

    fun email(ctx: Context): String =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("email", "").orEmpty()

    fun signOut(ctx: Context) {
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    data class RemoteImage(val name: String, val url: String, val size: Long, val sha: String)

    /** 列出用户云盘里的图片（只读，不提供删除）。 */
    fun listImages(email: String): List<RemoteImage> {
        val r = call(imagesDir(email))
        if (r.code !in 200..299) return emptyList()
        return runCatching {
            val arr = JSONArray(r.body)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                if (!o.optString("type", "").equals("file", true)) return@mapNotNull null
                val name = o.optString("name")
                if (!name.matches(Regex("(?i).*\\.(png|jpg|jpeg|webp|gif|heic|avif)$"))) return@mapNotNull null
                RemoteImage(name, o.optString("download_url"), o.optLong("size"), o.optString("sha"))
            }.sortedByDescending { it.name }
        }.getOrDefault(emptyList())
    }
}
