package io.github.xororz.localdream.service

import io.github.xororz.localdream.BuildConfig
import io.github.xororz.localdream.net.MirrorPurge
import io.github.xororz.localdream.net.ProgressBody
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
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request

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
    /** Upload in flight, exposed so the UI can cancel it with a real cross. */
    @Volatile
    private var uploadCall: okhttp3.Call? = null

    fun cancelUpload() {
        runCatching { uploadCall?.cancel() }
        uploadCall = null
    }

    private val uploadHttp = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(90, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** PUT with byte-level progress; cancellation goes through uploadCall. */
    private fun putWithProgress(
        url: String,
        bytes: ByteArray,
        token: String,
        onProgress: ((Float) -> Unit)?,
    ): Pair<Int, String> {
        return try {
            val body = ProgressBody(bytes, "application/json".toMediaType(), onProgress)
            val call = uploadHttp.newCall(
                Request.Builder().url(url)
                    .put(body)
                    .addHeader("Authorization", "token $token")
                    .addHeader("Accept", "application/vnd.github+json")
                    .addHeader("User-Agent", "LocalDream-ET")
                    .build(),
            )
            uploadCall = call
            call.execute().use { resp ->
                resp.code to resp.body?.string().orEmpty()
            }
        } catch (e: Exception) {
            val cancelled = e.message?.contains("Canceled") == true
            -1 to (if (cancelled) "已取消上传" else (e.message ?: e.javaClass.simpleName))
        } finally {
            uploadCall = null
        }
    }

    // et.153: 内存缓存，第二次进入社区秒开；下拉刷新时清空。
    @Volatile
    private var cached: List<GalleryItem>? = null
    fun invalidate() { cached = null }
    private const val API = "https://api.github.com/repos/ETQWFD/LocalDreamET-Gallery/contents"
    // et.149: 国内优先 jsdelivr / gitmirror，raw 作为兜底，图片和元数据都走镜像。
    private val IMG_BASES = listOf(
        "https://cdn.jsdelivr.net/gh/ETQWFD/LocalDreamET-Gallery@main",
        "https://raw.gitmirror.com/ETQWFD/LocalDreamET-Gallery/main",
        "https://raw.githubusercontent.com/ETQWFD/LocalDreamET-Gallery/main",
    )
    private val META_BASES = listOf(
        "https://cdn.jsdelivr.net/gh/ETQWFD/LocalDreamET-Gallery@main",
        "https://raw.gitmirror.com/ETQWFD/LocalDreamET-Gallery/main",
        "https://raw.githubusercontent.com/ETQWFD/LocalDreamET-Gallery/main",
    )

    data class GalleryItem(
        val name: String,
        val url: String,
        val author: String,
        val title: String,
        val price: Int,
        val ts: Long,
        /** Mirror chain: jsDelivr -> gitmirror -> raw. [url] is the first entry. */
        val urls: List<String> = listOf(url),
    )

    /** 上传结果：ok=false 时 reason 可直接展示给用户。 */
    data class UploadResult(val ok: Boolean, val reason: String)

    /** 阻塞 HTTP；必须在 Dispatchers.IO 调用。 */
    private fun http(url: String, method: String, body: ByteArray? = null): Pair<Int, String> {
        var c: HttpURLConnection? = null
        return try {
            c = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 6000
                readTimeout = 10000
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

    /** Same as [http] but with an explicit bearer token (see upload). */
    private fun httpWithToken(
        url: String,
        method: String,
        body: ByteArray,
        token: String,
    ): Pair<Int, String> {
        var c: HttpURLConnection? = null
        return try {
            c = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 8000
                readTimeout = 20000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "LocalDream-ET")
                setRequestProperty("Authorization", "token $token")
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                outputStream.use { it.write(body) }
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
            404 -> "仓库/目录不存在或令牌无写权限（HTTP 404）"
            409 -> "文件冲突，请重试"
            422 -> "GitHub 校验失败（内容或文件名不合法）"
            else -> "HTTP $code"
        }
        return if (msg.isBlank()) reason else "$reason：$msg"
    }

    /** et.149: 多镜像取字节，返回 200 时的 body。 */
    private fun httpAny(urls: List<String>): Pair<Int, String> {
        for (u in urls) {
            val (c, t) = http(u, "GET")
            if (c in 200..299) return c to t
        }
        return -1 to "all mirrors failed"
    }

    private fun enumerateViaJsdelivr(): List<String> {
        return try {
            val (code, text) = http(
                "https://data.jsdelivr.com/v1/packages/gh/ETQWFD/LocalDreamET-Gallery@main?structure=flat",
                "GET"
            )
            if (code != 200) return emptyList()
            val arr = JSONObject(text).optJSONArray("files") ?: return emptyList()
            (0 until arr.length()).mapNotNull {
                val name = arr.getJSONObject(it).optString("name", "")
                if (name.startsWith("/png/") && name.endsWith(".png")) name.substringAfterLast("/") else null
            }
        } catch (_: Exception) { emptyList() }
    }

    /**
     * One authenticated request lists every path in the repo. jsDelivr's
     * package index is cached and can miss files uploaded minutes ago, which
     * made the community look empty even though the upload had succeeded.
     */
    private fun enumerateViaApi(): List<String> {
        return try {
            val (code, text) = http(
                "https://api.github.com/repos/ETQWFD/LocalDreamET-Gallery/git/trees/main?recursive=1",
                "GET",
            )
            if (code != 200) return emptyList()
            val tree = JSONObject(text).optJSONArray("tree") ?: return emptyList()
            (0 until tree.length()).mapNotNull { i ->
                val path = tree.getJSONObject(i).optString("path", "")
                if (path.startsWith("png/") && path.endsWith(".png")) path.substringAfterLast('/') else null
            }
        } catch (_: Exception) { emptyList() }
    }

    /** 社区列表；失败返回空列表由 UI 提示。 */
    suspend fun list(force: Boolean = false): List<GalleryItem> = withContext(Dispatchers.IO) {
        if (!force) cached?.let { return@withContext it }
        try {
            // et.152: 国内 jsdelivr 包目录枚举最快（<1s），优先；api.github.com 作为兜底。
            var names: List<String> = enumerateViaApi()
            if (names.isEmpty()) names = enumerateViaJsdelivr()
            if (names.isEmpty()) {
                names = try {
                    val (code, text) = http("$API/png", "GET")
                    if (code != 200) emptyList()
                    else (0 until JSONArray(text).length()).mapNotNull { i ->
                        JSONArray(text).getJSONObject(i).optString("name", "").takeIf { it.endsWith(".png") }
                    }
                } catch (_: Exception) { emptyList() }
            }
            val metas = fetchMetas(names)
            names.map { n ->
                val stem = n.substringBeforeLast(".png")
                val m = metas[stem]
                // Mirror chain, jsDelivr first because it is reachable in China.
                val mirrors = listOf(
                    "https://cdn.jsdelivr.net/gh/ETQWFD/LocalDreamET-Gallery@main/png/$n",
                    "https://raw.gitmirror.com/ETQWFD/LocalDreamET-Gallery/main/png/$n",
                    "https://raw.githubusercontent.com/ETQWFD/LocalDreamET-Gallery/main/png/$n",
                )
                GalleryItem(
                    n, mirrors.first(),
                    m?.optString("author", "?") ?: "?",
                    m?.optString("name", "") ?: "",
                    m?.optInt("price", 0) ?: 0,
                    m?.optLong("ts", 0L) ?: 0L,
                    mirrors,
                )
            }.sortedByDescending { it.ts }.also { cached = it }
        } catch (_: Exception) {
            cached ?: emptyList()
        }
    }

    /** et.149: 元数据走多镜像并发读，单张失败不影响整页。 */
    private suspend fun fetchMetas(names: List<String>): Map<String, JSONObject> = coroutineScope {
        val subset = names.take(200)
        subset.map { n ->
            async(Dispatchers.IO) {
                val stem = n.substringBeforeLast(".png")
                val api = "$API/png/$stem.meta.json"
                val (apiCode, apiText) = http(api, "GET")
                if (apiCode == 200) {
                    return@async stem to (try { JSONObject(apiText) } catch (_: Exception) { null })
                }
                val urls = META_BASES.map { "$it/png/$stem.meta.json" }
                val (code, text) = httpAny(urls)
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
        userToken: String? = null,
        onProgress: ((Float) -> Unit)? = null,
    ): UploadResult = withContext(Dispatchers.IO) {
        if (pngBytes.isEmpty()) return@withContext UploadResult(false, "图片文件为空")
        // A signed-in account that is not a collaborator gets 404 on write
        // (seen in the field with login ET972). Try its token first so the work
        // is attributed to the author, then fall back to the built-in token.
        // Owner token first: every signed-in user publishes through the same
        // account that owns the gallery, so a normal account never hits 404.
        // The author field still keeps the uploader's own login.
        val candidates = listOfNotNull(
            BuildConfig.GALLERY_TOKEN.takeIf { it.isNotBlank() },
            userToken?.takeIf { it.isNotBlank() },
        ).distinct()
        if (candidates.isEmpty()) {
            return@withContext UploadResult(false, "请先登录 GitHub 再发布作品")
        }
        var last = UploadResult(false, "上传失败")
        for (token in candidates) {
            val result = uploadWithToken(pngBytes, author, price, name, token, onProgress)
            if (result.ok) return@withContext result
            last = result
            val permissionIssue = result.reason.contains("404") ||
                result.reason.contains("403") ||
                result.reason.contains("令牌") ||
                result.reason.contains("权限")
            if (!permissionIssue) return@withContext result
        }
        last
    }

    private suspend fun uploadWithToken(
        pngBytes: ByteArray,
        author: String,
        price: Int,
        name: String,
        token: String,
        onProgress: ((Float) -> Unit)?,
    ): UploadResult = withContext(Dispatchers.IO) {
        try {
            val id = "${System.currentTimeMillis()}_${(1000..9999).random()}"
            val imgBody = JSONObject()
                .put("message", "upload $id")
                .put("content", Base64.getEncoder().encodeToString(pngBytes))
                .toString().toByteArray()
            val (c1, t1) = putWithProgress("$API/png/pic_$id.png", imgBody, token, onProgress)
            if (c1 == -1 && t1 == "已取消上传") return@withContext UploadResult(false, "已取消上传")
            if (c1 !in 200..201) {
                io.github.xororz.localdream.cloud.LogHub.log(
                    io.github.xororz.localdream.cloud.LogHub.Category.UPLOAD,
                    "gallery upload failed HTTP $c1 ${t1.take(120)}",
                )
                return@withContext UploadResult(
                    false,
                    brief(c1, t1) + (if (c1 == 404) "（令牌无写权限）" else ""),
                )
            }
            onProgress?.invoke(1f)

            val meta = JSONObject().put("author", author).put("price", price)
                .put("ts", System.currentTimeMillis())
                .put("name", name.ifBlank { "作品" })
            val metaBody = JSONObject().put("message", "meta $id")
                .put("content", Base64.getEncoder().encodeToString(meta.toString().toByteArray()))
                .toString().toByteArray()
            val (c2, t2) = putWithProgress("$API/png/pic_$id.meta.json", metaBody, token, null)
            MirrorPurge.jsdelivr("LocalDreamET-Gallery", "png/pic_$id.png")
            MirrorPurge.jsdelivr("LocalDreamET-Gallery", "png/pic_$id.meta.json")
            // Read the file back so "发布成功" can never be a lie: if the commit
            // did not land (409 race, revoked token) we report it immediately.
            val verified = runCatching {
                var ok = false
                repeat(3) { attempt ->
                    val (vc, vt) = http("$API/png/pic_$id.png", "GET")
                    if (vc == 200 && vt.contains("\"size\"")) { ok = true; return@repeat }
                    if (attempt < 2) Thread.sleep(1200)
                }
                ok
            }.getOrDefault(false)
            if (c2 !in 200..201) {
                // Image landed but meta failed: still a successful publish.
                return@withContext UploadResult(true, "作品已上传，但作品信息写入失败：${brief(c2, t2)}")
            }
            io.github.xororz.localdream.cloud.LogHub.log(
                io.github.xororz.localdream.cloud.LogHub.Category.UPLOAD,
                "gallery upload ok pic_$id.png (${pngBytes.size} bytes, verified=$verified)",
            )
            UploadResult(true, if (verified) "" else "已上传，仓库索引稍有延迟，请下拉刷新")
        } catch (e: Exception) {
            io.github.xororz.localdream.cloud.LogHub.log(
                io.github.xororz.localdream.cloud.LogHub.Category.UPLOAD,
                "gallery upload exception ${e.message}",
            )
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
