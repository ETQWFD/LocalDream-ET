package io.github.xororz.localdream.service

import io.github.xororz.localdream.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64

/** 2026-11：个人公告（转账到账、悬赏驳回/支付），只写给自己用户名下的目录。 */
object NoticesClient {
    private const val API = "https://api.github.com/repos/ETQWFD/NightmareRoad-ET-Gallery/contents"

    private fun safe(login: String) = login.filter { it.isLetterOrDigit() || it in "._-" }.ifBlank { "anonymous" }

    private fun conn(url: String, method: String = "GET") =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 8000; readTimeout = 15000
            setRequestProperty("Authorization", "token ${BuildConfig.GALLERY_TOKEN}")
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "NightmareRoad-ET")
        }

    suspend fun post(login: String, title: String, body: String = ""): Boolean = withContext(Dispatchers.IO) {
        if (login.isBlank()) return@withContext false
        runCatching {
            val ts = System.currentTimeMillis()
            val json = JSONObject().put("title", title).put("body", body).put("ts", ts).put("read", false)
            val c = conn("$API/notices/${safe(login)}/${ts}.json", "PUT").apply {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
            val payload = JSONObject().put("message", "notice $login $ts")
                .put("content", Base64.getEncoder().encodeToString(json.toString().toByteArray()))
            c.outputStream.use { it.write(payload.toString().toByteArray()) }
            c.responseCode in 200..201
        }.getOrDefault(false)
    }

    private fun readDir(dir: String): MutableList<JSONObject> {
        val out = mutableListOf<JSONObject>()
        runCatching {
            val c = conn("$API/notices/$dir")
            if (c.responseCode !in 200..299) return@runCatching
            val arr = JSONArray(c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) })
            for (i in 0 until arr.length()) {
                val f = arr.getJSONObject(i)
                if (!f.optString("name").endsWith(".json")) continue
                val r = conn(f.optString("url")).inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
                val root = JSONObject(r)
                out += JSONObject(
                    String(Base64.getDecoder().decode(root.optString("content").replace("\n", "")), Charsets.UTF_8),
                )
            }
        }
        return out
    }

    suspend fun list(login: String): List<JSONObject> = withContext(Dispatchers.IO) {
        // 全局公告 + 本人公告；未登录也能看到作者公告。
        val out = readDir("_global")
        if (login.isBlank()) return@withContext out.sortedByDescending { it.optLong("ts") }
        runCatching {
            val c = conn("$API/notices/${safe(login)}")
            if (c.responseCode !in 200..299) return@runCatching
            val arr = JSONArray(c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) })
            for (i in 0 until arr.length()) {
                val f = arr.getJSONObject(i)
                if (!f.optString("name").endsWith(".json")) continue
                val r = conn(f.optString("url")).inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
                val root = JSONObject(r)
                out += JSONObject(
                    String(Base64.getDecoder().decode(root.optString("content").replace("\n", "")), Charsets.UTF_8),
                )
            }
        }
        out.sortedByDescending { it.optLong("ts") }
    }
}
