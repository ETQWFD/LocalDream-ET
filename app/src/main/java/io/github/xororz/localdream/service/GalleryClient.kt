package io.github.xororz.localdream.service

import io.github.xororz.localdream.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64

/**
 * et.132: public image gallery backed by the public repo ETQWFD/LocalDreamET-Gallery.
 * Images live under png/; sidecar <name>.meta.json holds author/price/ts.
 * Upload token comes from BuildConfig.GALLERY_TOKEN (git-ignored local.properties).
 */
object GalleryClient {
    private const val API = "https://api.github.com/repos/ETQWFD/LocalDreamET-Gallery/contents"
    private const val RAW = "https://raw.githubusercontent.com/ETQWFD/LocalDreamET-Gallery/main"

    data class GalleryItem(
        val name: String,
        val url: String,
        val author: String,
        val price: Int,
        val ts: Long,
    )

    private fun http(url: String, method: String, body: ByteArray? = null): Pair<Int, String> {
        var c: HttpURLConnection? = null
        return try {
            c = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 15000
                readTimeout = 20000
                setRequestProperty("Accept", "application/vnd.github+json")
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
            0 to (e.message ?: "err")
        } finally {
            c?.disconnect()
        }
    }

    fun list(): List<GalleryItem> = try {
        val (code, text) = http("$API/png", "GET")
        if (code != 200) return emptyList()
        val arr = JSONArray(text)
        val out = mutableListOf<GalleryItem>()
        for (i in 0 until arr.length()) {
            val n = arr.getJSONObject(i).getString("name")
            if (!n.endsWith(".png")) continue
            val meta = readMeta(n.substringBeforeLast(".png"))
            out.add(
                GalleryItem(n, "$RAW/png/$n", meta?.optString("author", "?") ?: "?",
                    meta?.optInt("price", 0) ?: 0, meta?.optLong("ts", 0L) ?: 0L)
            )
        }
        out.sortedByDescending { it.ts }
    } catch (_: Exception) { emptyList() }

    private fun readMeta(stem: String): JSONObject? = try {
        val (code, text) = http("$API/png/$stem.meta.json", "GET")
        if (code != 200) null
        else String(Base64.getDecoder().decode(
            JSONObject(text).getString("content").replace("\n", "")))
            .let { JSONObject(it) }
    } catch (_: Exception) { null }

    fun upload(pngBytes: ByteArray, author: String, price: Int): Boolean = try {
        val id = "${System.currentTimeMillis()}_${(1000..9999).random()}"
        val imgBody = JSONObject()
            .put("message", "upload $id")
            .put("content", Base64.getEncoder().encodeToString(pngBytes))
            .toString().toByteArray()
        val (c1) = http("$API/png/pic_$id.png", "PUT", imgBody)
        if (c1 !in 200..201) false
        else {
            val meta = JSONObject().put("author", author).put("price", price)
                .put("ts", System.currentTimeMillis())
            val metaBody = JSONObject().put("message", "meta $id")
                .put("content", Base64.getEncoder().encodeToString(meta.toString().toByteArray()))
                .toString().toByteArray()
            val (c2) = http("$API/png/pic_$id.meta.json", "PUT", metaBody)
            c2 in 200..201
        }
    } catch (_: Exception) { false }

    /** Idempotent: androidId+imageName is the path key; existing => not charged again. */
    fun recordPurchase(androidId: String, imageName: String): Boolean = try {
        val path = "users/$androidId/purchased/$imageName.json"
        val quotaApi = "https://api.github.com/repos/ETQWFD/LocalDreamET-Quota/contents"
        val (existsCode) = http("$quotaApi/$path", "GET")
        if (existsCode == 200) return false
        val body = JSONObject().put("image", imageName).put("ts", System.currentTimeMillis())
        val b64 = Base64.getEncoder().encodeToString(body.toString().toByteArray())
        val put = JSONObject().put("message", "buy $imageName").put("content", b64)
            .toString().toByteArray()
        val (c) = http("$quotaApi/$path", "PUT", put)
        c in 200..201
    } catch (_: Exception) { false }
}
