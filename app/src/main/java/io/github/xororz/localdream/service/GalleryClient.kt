package io.github.xororz.localdream.service

import io.github.xororz.localdream.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64

/**
 * et.132: public image gallery backed by the public repo ETQWFD/LocalDreamET-Gallery.
 * Images live under png/; sidecar <name>.meta.json holds author/price/ts.
 * Upload token comes from BuildConfig.GALLERY_TOKEN (git-ignored local.properties).
 *
 * et.145 修复：
 *  1) 所有方法改为 suspend + Dispatchers.IO。此前 list()/upload() 直接在 Compose
 *     主线程发 HttpURLConnection，Android 抛 NetworkOnMainThreadException，被 catch
 *     吞掉后界面只能显示“发布失败/空列表”。这是社区“发布失败，请重试”的根因。
 *  2) 失败时返回真实原因（HTTP 状态码 + 响应片段），不再只有 true/false。
 *  3) 列表元数据改走 raw.githubusercontent CDN 并发读取，避免逐图调用 GitHub
 *     contents API 触发 403 限流导致列表为空。
 */
object GalleryClient {
    private const val API = "https://api.github.com/repos/ETQWFD/LocalDreamET-Gallery/contents"
    private const val RAW = "https://raw.githubusercontent.com/ETQWFD/LocalDreamET-Gallery/main"

    data class GalleryItem(
        val name: String,
        val url: String,
        val author: String,
        val title: String,
        val price: Int,
        val ts: Long,
    )

    /** 上传结果：ok=false 时 reason 可直接展示给用户。 */
    data class UploadResult(val ok: Boolean, val reason: String)

    /** 阻塞 HTTP；必须在 Dispatchers.IO 调用。 */
    private fun http(url: String, method: String, body: ByteArray? = null): Pair<Int, String> {
        var c: HttpURLConnection? = null
        return try {
            c = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 15000
                readTimeout = 30000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "LocalDream-ET")
                val t = BuildConfig.GALLERY_TOKEN
                if (t.isNotEmpty()) setRequestProperty("Authorization", "token $t")
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    outputStream.use { it.write(body) }
                }
            }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)
                ?.bufferedReader()?.readText().orEmpty()
            code to text
        } catch (e: Exception) {
            -1 to (e.message ?: e.javaClass.simpleName)
        } finally {
            c?.disconnect()
        }
    }

    /** 把 GitHub 错误体压成一句可读原因。 */
    private fun brief(code: Int, text: String): String {
        val msg = try {
            JSONObject(text).optString("message", "")
        } catch (_: Exception) { "" }
        val reason = when (code) {
            0 -> "网络不可达"
            -1 -> "网络异常"
            401 -> "上传令牌无效或已过期"
            403 -> "GitHub 拒绝访问（限流或令牌无仓库写权限）"
            404 -> "仓库或目录不存在"
            409 -> "文件冲突，请重试"
            422 -> "GitHub 校验失败（内容或文件名不合法）"
            else -> "HTTP $code"
        }
        return if (msg.isBlank()) reason else "$reason：$msg"
    }

    /** 社区列表；失败返回空列表由 UI 提示。 */
    suspend fun list(): List<GalleryItem> = withContext(Dispatchers.IO) {
        try {
            val (code, text) = http("$API/png", "GET")
            if (code != 200) return@withContext emptyList()
            val arr = JSONArray(text)
            val names = (0 until arr.length()).mapNotNull { i ->
                arr.getJSONObject(i).optString("name", "").takeIf { it.endsWith(".png") }
            }
            val metas = fetchMetas(names)
            names.map { n ->
                val stem = n.substringBeforeLast(".png")
                val m = metas[stem]
                GalleryItem(
                    n, "$RAW/png/$n",
                    m?.optString("author", "?") ?: "?",
                    m?.optString("name", "") ?: "",
                    m?.optInt("price", 0) ?: 0,
                    m?.optLong("ts", 0L) ?: 0L,
                )
            }.sortedByDescending { it.ts }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** 元数据走 raw CDN 并发读取（无 API 限额），单张失败不影响整页。 */
    private suspend fun fetchMetas(names: List<String>): Map<String, JSONObject> = coroutineScope {
        val subset = names.take(200)
        subset.map { n ->
            async(Dispatchers.IO) {
                val stem = n.substringBeforeLast(".png")
                val (code, text) = http("$RAW/png/$stem.meta.json", "GET")
                if (code == 200) stem to (try { JSONObject(text) } catch (_: Exception) { null })
                else stem to null
            }
        }.awaitAll().mapNotNull { (k, v) -> v?.let { k to it } }.toMap()
    }

    /**
     * 上传一张图 + 同名 meta。成功返回 UploadResult(true, "")。
     * 任何一步失败都带回真实原因，便于界面直接展示。
     */
    suspend fun upload(
        pngBytes: ByteArray,
        author: String,
        price: Int,
        name: String,
    ): UploadResult = withContext(Dispatchers.IO) {
        try {
            if (BuildConfig.GALLERY_TOKEN.isBlank()) {
                return@withContext UploadResult(false, "未配置社区上传令牌（gallery.token）")
            }
            if (pngBytes.isEmpty()) return@withContext UploadResult(false, "图片文件为空")
            val id = "${System.currentTimeMillis()}_${(1000..9999).random()}"
            val imgBody = JSONObject()
                .put("message", "upload $id")
                .put("content", Base64.getEncoder().encodeToString(pngBytes))
                .toString().toByteArray()
            val (c1, t1) = http("$API/png/pic_$id.png", "PUT", imgBody)
            if (c1 !in 200..201) return@withContext UploadResult(false, brief(c1, t1))

            val meta = JSONObject().put("author", author).put("price", price)
                .put("ts", System.currentTimeMillis())
                .put("name", name.ifBlank { "作品" })
            val metaBody = JSONObject().put("message", "meta $id")
                .put("content", Base64.getEncoder().encodeToString(meta.toString().toByteArray()))
                .toString().toByteArray()
            val (c2, t2) = http("$API/png/pic_$id.meta.json", "PUT", metaBody)
            if (c2 !in 200..201) {
                // 图片已落库但 meta 失败：仍算发布成功，价格/作者稍后补
                return@withContext UploadResult(true, "作品已上传，但作品信息写入失败：${brief(c2, t2)}")
            }
            UploadResult(true, "")
        } catch (e: Exception) {
            UploadResult(false, e.message ?: e.javaClass.simpleName)
        }
    }

    /** Idempotent: androidId+imageName is the path key; existing => not charged again. */
    suspend fun recordPurchase(androidId: String, imageName: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val path = "users/$androidId/purchased/$imageName.json"
                val quotaApi = "https://api.github.com/repos/ETQWFD/LocalDreamET-Quota/contents"
                val (existsCode, _) = http("$quotaApi/$path", "GET")
                if (existsCode == 200) return@withContext false
                val body = JSONObject().put("image", imageName).put("ts", System.currentTimeMillis())
                val b64 = Base64.getEncoder().encodeToString(body.toString().toByteArray())
                val put = JSONObject().put("message", "buy $imageName").put("content", b64)
                    .toString().toByteArray()
                val (c) = http("$quotaApi/$path", "PUT", put)
                c in 200..201
            } catch (_: Exception) {
                false
            }
        }
}
