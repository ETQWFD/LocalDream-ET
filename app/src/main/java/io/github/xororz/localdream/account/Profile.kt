package io.github.xororz.localdream.account

import io.github.xororz.localdream.BuildConfig
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

/**
 * et.253：用户资料。
 * - 昵称：邮箱注册默认取 @ 前面的部分，GitHub 登录默认取 login，都可手动改；
 * - 头像：从相册选一张，压缩后存到应用私有目录，仅本机使用，不上传仓库。
 * 发布作品与聊天时用这里的昵称，不再固定显示 GitHub login / 匿名。
 */
object Profile {
    private const val PREF = "profile"
    private const val KEY_NAME = "display_name"
    private const val KEY_AVATAR = "avatar_file"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun name(ctx: Context): String = prefs(ctx).getString(KEY_NAME, "").orEmpty()

    fun setName(ctx: Context, v: String) {
        prefs(ctx).edit().putString(KEY_NAME, v.trim().take(24)).apply()
    }

    fun avatarPath(ctx: Context): String = prefs(ctx).getString(KEY_AVATAR, "").orEmpty()

    /** 展示用昵称：手填 > 邮箱前缀 > 空。 */
    /** et.327：昵称里出现"官方"两个字，一律显示为 ***（含全角/空格变体）。 */
    private fun maskOfficial(v: String): String {
        val norm = v.replace('　', ' ').replace("\u200b", "").replace(" ", "")
        if (!norm.contains("官方")) return v
        val masked = v.replace(Regex("官\\s*方"), "***")
        return if (masked.contains("***")) masked else "***"
    }

    /** et.327：查这个昵称是否已经被用过（社区作者 + 聊天作者）。 */
    fun taken(ctx: Context, name: String): Boolean {
        val n = maskOfficial(name).trim()
        if (n.isBlank()) return false
        val tok = BuildConfig.GALLERY_TOKEN
        fun authors(url: String): List<String> = runCatching {
            val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            c.connectTimeout = 8000; c.readTimeout = 20000
            c.setRequestProperty("Authorization", "token " + tok)
            c.setRequestProperty("User-Agent", "NightmareRoad")
            c.setRequestProperty("Accept", "application/vnd.github.raw")
            val body = c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            val o = org.json.JSONObject(body)
            if (o.has("items")) o.getJSONArray("items") else o.getJSONArray("messages")
        }.getOrNull()?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.optString("author")?.takeIf { it.isNotBlank() }
            }
        } ?: emptyList()
        val g = authors("https://api.github.com/repos/ETQWFD/NightmareRoad-ET-Gallery/contents/png/index.json")
        val c = authors("https://api.github.com/repos/ETQWFD/NightmareRoad-ET-Chat/contents/messages.json")
        return (g + c).any { it == n }
    }

    fun displayName(ctx: Context): String {
        val v = run {
        val manual = name(ctx)
        if (manual.isNotBlank()) return manual
        val email = EmailAccount.signedInEmail(ctx)
        if (email.isNotBlank()) email.substringBefore('@').take(24)
        return ""
    }
        return if (v.trim().isBlank()) "" else maskOfficial(v)
    }

    fun avatarBitmap(ctx: Context): Bitmap? = runCatching {
        val p = avatarPath(ctx)
        if (p.isBlank()) return@runCatching null
        val f = File(p)
        if (!f.exists()) return@runCatching null
        BitmapFactory.decodeFile(p)
    }.getOrNull()

    /** 保存头像：等比缩到 256px 存 PNG，避免占空间。 */
    fun saveAvatar(ctx: Context, bytes: ByteArray): Boolean = runCatching {
        val src = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return false
        val scale = 256f / maxOf(src.width, src.height)
        val w = (src.width * scale).toInt().coerceAtLeast(1)
        val h = (src.height * scale).toInt().coerceAtLeast(1)
        val bmp = Bitmap.createScaledBitmap(src, w, h, true)
        val f = File(ctx.filesDir, "avatar.png")
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 92, it) }
        prefs(ctx).edit().putString(KEY_AVATAR, f.absolutePath).apply()
        true
    }.getOrDefault(false)
}
