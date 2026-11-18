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

    /** 登录校验：读 account.json 比对哈希。返回 null 表示成功。 */
    fun login(email: String, pwd: String): String? {
        if (token().isBlank()) return "内置令牌缺失，无法访问云盘，请联系作者"
        val e = email.trim().lowercase()
        if (!e.contains('@')) return "邮箱格式不正确"
        val r = call(accountPath(e))
        if (r.code == 404) return "这个邮箱还没有注册，请先在出图 App 里注册"
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
