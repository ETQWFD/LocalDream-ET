package etc.github.ai.chat.api

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/** Chinese-readable error; thrown for every non-success path. */
class ApiException(message: String) : Exception(message)

/**
 * Strict client for server-dist/SERVER-API.md v1.1.2:
 *  - GET  /healthz                     (no key) 200 ready / 503 starting
 *  - POST /sdapi/v1/txt2img            -> {"images":[base64,...]}
 *  - POST /sdapi/v1/img2img            -> {"images":[base64,...]}
 *  - POST /v1/images/generations       -> {"data":[{"b64_json":...}]}
 *  - GitHub Contents API upload (optional cloud button)
 *
 * The engine runs a single-threaded, synchronous diffusion pass; local models
 * can take tens of seconds, hence the 10-minute read timeout.
 */
object ServerApi {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .readTimeout(600, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    sealed class Health {
        data class Ready(val model: String) : Health()
        data object Starting : Health()
        data class Fail(val message: String) : Health()
    }

    fun normalizeBase(input: String): String {
        var s = input.trim().trimEnd('/')
        if (s.isEmpty()) throw ApiException("服务端地址为空")
        if (!s.startsWith("http://") && !s.startsWith("https://")) s = "http://$s"
        return s
    }

    private fun Request.Builder.auth(apiKey: String): Request.Builder {
        if (apiKey.isNotBlank()) {
            header("Authorization", "Bearer $apiKey")
            header("X-API-Key", apiKey)
        }
        return this
    }

    // ---- GET /healthz (no key) --------------------------------------------
    fun healthz(baseRaw: String): Health {
        val base = try { normalizeBase(baseRaw) } catch (e: Exception) {
            return Health.Fail(e.message ?: "服务端地址错误")
        }
        val req = try {
            Request.Builder().url("$base/healthz").get().build()
        } catch (e: Exception) {
            return Health.Fail("地址格式错误：${e.message}")
        }
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            return when (resp.code) {
                200 -> {
                    val model = runCatching { JSONObject(body).optString("model") }.getOrDefault("")
                    Health.Ready(model.ifBlank { "(未知模型)" })
                }
                503 -> Health.Starting
                else -> Health.Fail("HTTP ${resp.code}：${body.take(120)}")
            }
        }
    }

    // ---- generation --------------------------------------------------------
    private fun postJson(url: String, apiKey: String, body: JSONObject): String {
        val req = Request.Builder()
            .url(url)
            .auth(apiKey)
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            when (resp.code) {
                200 -> return text
                401 -> throw ApiException("API Key 无效或缺失（401），请到设置核对 46 位 Key")
                503 -> throw ApiException("模型加载中，暂不能出图（503），请稍后重试")
                502, 504 -> throw ApiException("网关错误 HTTP ${resp.code}：${text.take(100)}")
                else -> throw ApiException("请求失败 HTTP ${resp.code}：${text.take(150)}")
            }
        }
    }

    /** txt2img: steps/cfg left out on purpose — the server injects defaults. */
    fun txt2img(
        baseRaw: String,
        apiKey: String,
        prompt: String,
        negativePrompt: String,
        batchSize: Int?,
    ): List<Bitmap> {
        if (prompt.isBlank()) throw ApiException("提示词为空")
        val base = normalizeBase(baseRaw)
        val body = JSONObject()
            .put("prompt", prompt)
            .put("negative_prompt", negativePrompt)
        if (batchSize != null) body.put("batch_size", batchSize)
        val resp = postJson("$base/sdapi/v1/txt2img", apiKey, body)
        return parseImages(resp)
    }

    /** img2img: init_images are base64 data URIs, denoising_strength default 0.6. */
    fun img2img(
        baseRaw: String,
        apiKey: String,
        prompt: String,
        negativePrompt: String,
        initImageDataUri: String,
        denoising: Double,
        batchSize: Int?,
    ): List<Bitmap> {
        val base = normalizeBase(baseRaw)
        val body = JSONObject()
            .put("prompt", prompt)
            .put("negative_prompt", negativePrompt)
            .put("init_images", JSONArray().put(initImageDataUri))
            .put("denoising_strength", denoising)
        if (batchSize != null) body.put("batch_size", batchSize)
        val resp = postJson("$base/sdapi/v1/img2img", apiKey, body)
        return parseImages(resp)
    }

    /** Accepts both sdapi {"images":[...]} and OpenAI {"data":[{"b64_json":...}]}. */
    private fun parseImages(json: String): List<Bitmap> {
        val root = JSONObject(json)
        val out = ArrayList<Bitmap>()
        val images = root.optJSONArray("images")
        if (images != null) {
            for (i in 0 until images.length()) out.add(decodeBase64Image(images.optString(i)))
        } else {
            val data = root.optJSONArray("data")
                ?: throw ApiException("响应里没有 images/data 字段：${json.take(120)}")
            for (i in 0 until data.length()) {
                val obj = data.optJSONObject(i) ?: continue
                out.add(decodeBase64Image(obj.optString("b64_json")))
            }
        }
        if (out.isEmpty()) throw ApiException("服务端返回了 0 张图片")
        return out
    }

    private fun decodeBase64Image(raw: String): Bitmap {
        if (raw.isBlank()) throw ApiException("图片数据为空")
        // sd.cpp may or may not wrap the payload as a data URI.
        val b64 = if (raw.startsWith("data:")) {
            val comma = raw.indexOf(',')
            if (comma < 0) throw ApiException("data URI 格式错误")
            raw.substring(comma + 1)
        } else raw
        val bytes = Base64.decode(b64, Base64.DEFAULT)
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: throw ApiException("图片解码失败（不是有效的 PNG/JPEG）")
    }

    // ---- optional GitHub cloud upload --------------------------------------
    /**
     * PUT /repos/{owner}/{repo}/contents/png/{yyyyMMddHHmm}.png
     * Body carries base64 content. Token is never logged.
     */
    fun uploadToGithub(
        token: String,
        repo: String,
        bitmap: Bitmap,
    ): String {
        if (token.isBlank()) throw ApiException("未配置 GitHub Token")
        if (!repo.contains("/")) throw ApiException("仓库格式应为 owner/repo")
        val baos = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 90, baos)
        val content = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
        val name = "png/" + java.time.format.DateTimeFormatter
            .ofPattern("yyyyMMddHHmm").format(java.time.LocalDateTime.now()) + ".png"
        val body = JSONObject()
            .put("message", "ET chatclient upload")
            .put("content", content)
            .toString()
        val req = Request.Builder()
            .url("https://api.github.com/repos/$repo/contents/$name")
            .header("Authorization", "token $token")
            .header("Accept", "application/vnd.github+json")
            .put(body.toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(req).execute().use { resp ->
            if (resp.code == 200 || resp.code == 201) {
                return "https://github.com/$repo/blob/main/$name"
            }
            val text = resp.body?.string().orEmpty()
            throw ApiException("GitHub 上传失败 HTTP ${resp.code}：${text.take(120)}")
        }
    }
}
