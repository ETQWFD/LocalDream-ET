package etc.github.ai.chat.community

import android.content.Context
import android.net.Uri
import android.util.Base64
import etc.github.ai.chat.BuildConfig
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * One message in the standalone ET 聊天社区.
 *
 * Wire format is intentionally close to the main app's chat (text/media), so
 * the same private repo can be read by either client; `file` is new here and is
 * simply ignored by older readers.
 */
data class ChatMsg(
    val author: String = "",
    val text: String = "",
    val ts: Long = 0L,
    /** text | image | file */
    val kind: String = "text",
    val fileName: String = "",
    val fileSize: Long = 0L,
    val sha256: String = "",
    /** GitHub Release asset ids of the parts (1 for a small file). */
    val parts: List<Long> = emptyList(),
    val quoteAuthor: String = "",
    val quoteText: String = "",
) {
    fun toJson(): JSONObject = JSONObject()
        .put("author", author)
        .put("text", text)
        .put("ts", ts)
        .put("kind", kind)
        .apply {
            if (fileName.isNotBlank()) {
                put(
                    "file",
                    JSONObject()
                        .put("name", fileName)
                        .put("size", fileSize)
                        .put("sha256", sha256)
                        .put("parts", JSONArray(parts)),
                )
            }
            if (quoteAuthor.isNotBlank()) put("qauthor", quoteAuthor)
            if (quoteText.isNotBlank()) put("qtext", quoteText)
        }

    companion object {
        fun fromJson(o: JSONObject): ChatMsg {
            val f = o.optJSONObject("file")
            val arr = f?.optJSONArray("parts")
            val ids = buildList {
                if (arr != null) for (i in 0 until arr.length()) add(arr.optLong(i, 0L))
            }
            return ChatMsg(
                author = o.optString("author", "游客"),
                text = o.optString("text", ""),
                ts = o.optLong("ts", 0L),
                kind = o.optString("kind", if (f != null) "file" else "text"),
                fileName = f?.optString("name", "").orEmpty(),
                fileSize = f?.optLong("size", 0L) ?: 0L,
                sha256 = f?.optString("sha256", "").orEmpty(),
                parts = ids,
                quoteAuthor = o.optString("qauthor", ""),
                quoteText = o.optString("qtext", ""),
            )
        }
    }
}

/**
 * Backend: the private GitHub repo `ETQWFD/ET-Chat`.
 *
 * - messages: chat/messages.json via the Contents API (atomic sha-guarded puts)
 * - big files: GitHub Release assets of a single rolling release named "files",
 *   which allows 100 MiB chunks and up to 2 GB per asset, no base64 blow-up.
 *
 * There is no login: the owner token is compiled in (local.properties only),
 * so any install of this private build can read and post.
 */
object ChatApi {
    const val OWNER = "ETQWFD"
    const val REPO = "ET-Chat"
    private const val MSG_PATH = "chat/messages.json"
    private const val RELEASE_TAG = "files"
    private const val MAX_FILE = 1024L * 1024 * 1024
    const val CHUNK = 100L * 1024 * 1024

    private val token: String get() = BuildConfig.GALLERY_TOKEN

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(900, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val jsonType = "application/json; charset=utf-8".toMediaType()

    private fun api(path: String): String = "https://api.github.com/repos/$OWNER/$REPO$path"

    private fun req(url: String): Request.Builder = Request.Builder()
        .url(url)
        .addHeader("Authorization", "Bearer $token")
        .addHeader("Accept", "application/vnd.github+json")
        .addHeader("User-Agent", "ET-Chat")

    suspend fun load(): List<ChatMsg> = withContext(Dispatchers.IO) {
        runCatching {
            val r = req(api("/contents/$MSG_PATH")).get().build()
            client.newCall(r).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext emptyList()
                val body = resp.body?.string().orEmpty()
                val o = JSONObject(body)
                val b64 = o.optString("content", "").replace("\n", "")
                val raw = if (b64.isNotBlank()) {
                    String(Base64.decode(b64, Base64.DEFAULT))
                } else {
                    return@withContext emptyList()
                }
                val arr = JSONObject(raw).optJSONArray("messages") ?: JSONArray()
                (0 until arr.length()).map { ChatMsg.fromJson(arr.getJSONObject(it)) }
                    .sortedBy { it.ts }
            }
        }.getOrDefault(emptyList())
    }

    /** Read-modify-write with sha checks; retries on 409 so two senders don't clobber. */
    suspend fun append(msg: ChatMsg): Result<Unit> = withContext(Dispatchers.IO) {
        repeat(8) {
            runCatching {
                var sha: String? = null
                var list = JSONArray()
                val get = req(api("/contents/$MSG_PATH")).get().build()
                client.newCall(get).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val o = JSONObject(resp.body?.string().orEmpty())
                        sha = o.optString("sha", "").takeIf { it.isNotBlank() }
                        val b64 = o.optString("content", "").replace("\n", "")
                        if (b64.isNotBlank()) {
                            val raw = String(Base64.decode(b64, Base64.DEFAULT))
                            list = JSONObject(raw).optJSONArray("messages") ?: JSONArray()
                        }
                    }
                }
                list.put(msg.toJson())
                val payload = JSONObject().put("messages", list).toString()
                val body = JSONObject()
                    .put("message", "chat: ${msg.author}")
                    .put("content", Base64.encodeToString(payload.toByteArray(), Base64.NO_WRAP))
                    .put("branch", "main")
                    .apply { sha?.let { put("sha", it) } }
                    .toString()
                    .toRequestBody(jsonType)
                val put = req(api("/contents/$MSG_PATH")).put(body).build()
                client.newCall(put).execute().use { resp ->
                    if (resp.code == 409 || resp.code == 422) return@use
                    if (!resp.isSuccessful) error("HTTP ${resp.code}: ${resp.body?.string().orEmpty().take(160)}")
                }
                return@withContext Result.success(Unit)
            }
            kotlinx.coroutines.delay(700)
        }
        Result.failure(IllegalStateException("消息发送失败（网络或并发冲突，请重试）"))
    }

    private suspend fun ensureRelease(): Long = withContext(Dispatchers.IO) {
        runCatching {
            val get = req(api("/releases/tags/$RELEASE_TAG")).get().build()
            client.newCall(get).execute().use { resp ->
                if (resp.isSuccessful) {
                    return@withContext JSONObject(resp.body?.string().orEmpty()).getLong("id")
                }
            }
        }
        val create = JSONObject()
            .put("tag_name", RELEASE_TAG)
            .put("name", "聊天社区文件仓库")
            .put("body", "大文件分片存放处（≤1GB，每片 100MB），由 App 自动合并。")
            .put("prerelease", false)
            .toString().toRequestBody(jsonType)
        val r = req("https://api.github.com/repos/$OWNER/$REPO/releases").post(create).build()
        client.newCall(r).execute().use { resp ->
            if (!resp.isSuccessful) error("创建文件仓库失败 HTTP ${resp.code}")
            JSONObject(resp.body?.string().orEmpty()).getLong("id")
        }
    }

    /** Upload one chunk as a release asset; returns its asset id. */
    private suspend fun uploadPart(releaseId: Long, name: String, part: File): Long =
        withContext(Dispatchers.IO) {
            val body: RequestBody = part.asRequestBody("application/octet-stream".toMediaType())
            val r = Request.Builder()
                .url("https://uploads.github.com/repos/$OWNER/$REPO/releases/$releaseId/assets?name=$name")
                .addHeader("Authorization", "Bearer $token")
                .addHeader("Accept", "application/vnd.github+json")
                .addHeader("User-Agent", "ET-Chat")
                .addHeader("Content-Type", "application/octet-stream")
                .post(body)
                .build()
            client.newCall(r).execute().use { resp ->
                if (!resp.isSuccessful) {
                    // Asset name already taken (a retry after a partial upload):
                    // delete the stale one and retry once.
                    if (resp.code == 422) {
                        val existing = findAsset(releaseId, name)
                        if (existing != null) deleteAsset(existing)
                        return@withContext uploadPart(releaseId, name, part)
                    }
                    error("分片上传失败 HTTP ${resp.code}")
                }
                JSONObject(resp.body?.string().orEmpty()).getLong("id")
            }
        }

    private suspend fun findAsset(releaseId: Long, name: String): Long? = withContext(Dispatchers.IO) {
        runCatching {
            val r = req("https://api.github.com/repos/$OWNER/$REPO/releases/$releaseId/assets?per_page=100")
                .get().build()
            client.newCall(r).execute().use { resp ->
                val arr = JSONArray(resp.body?.string().orEmpty())
                (0 until arr.length()).map { arr.getJSONObject(it) }
                    .firstOrNull { it.optString("name") == name }
                    ?.getLong("id")
            }
        }.getOrNull()
    }

    private fun deleteAsset(id: Long) {
        runCatching {
            val r = req("https://api.github.com/repos/$OWNER/$REPO/releases/assets/$id").delete().build()
            client.newCall(r).execute().close()
        }
    }

    data class UploadResult(val name: String, val size: Long, val sha256: String, val parts: List<Long>)

    /**
     * Copy [uri] to a temp file, hash it, and upload 100 MiB chunks.
     * Files up to 1 GB are accepted; anything bigger is refused up front.
     */
    suspend fun upload(
        context: Context,
        uri: Uri,
        displayName: String,
        onProgress: (done: Long, total: Long) -> Unit,
    ): Result<UploadResult> = withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(context.cacheDir, "upload").apply { mkdirs() }
            val src = File(dir, "src.bin")
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(src).use { out -> input.copyTo(out, 1 shl 16) }
            } ?: error("无法读取所选文件")
            val size = src.length()
            require(size in 1..MAX_FILE) { "文件需小于 1GB（当前 ${size / 1024 / 1024}MB）" }
            val sha = MessageDigest.getInstance("SHA-256").let { md ->
                src.inputStream().use { ins ->
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        val n = ins.read(buf)
                        if (n <= 0) break
                        md.update(buf, 0, n)
                    }
                }
                md.digest().joinToString("") { "%02x".format(it) }
            }
            val releaseId = ensureRelease()
            val parts = mutableListOf<Long>()
            // A unique run id in every part name: two people sending the same
            // file must not overwrite (or delete) each other's chunks.
            val suffix = sha.take(12) + "-" + java.util.UUID.randomUUID().toString().take(8)
            var offset = 0L
            var index = 1
            val total = ((size + CHUNK - 1) / CHUNK).toInt()
            while (offset < size) {
                val len = minOf(CHUNK, size - offset)
                val partFile = File(dir, "part.bin")
                src.inputStream().use { ins ->
                    var skipped = 0L
                    while (skipped < offset) {
                        val s = ins.skip(offset - skipped)
                        if (s <= 0) break
                        skipped += s
                    }
                    FileOutputStream(partFile).use { out ->
                        var left = len
                        val buf = ByteArray(1 shl 16)
                        while (left > 0) {
                            val n = ins.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                            if (n <= 0) break
                            out.write(buf, 0, n)
                            left -= n
                        }
                    }
                }
                val partName = "${safeName(displayName)}.$suffix.part${index}of$total"
                parts += uploadPart(releaseId, partName, partFile)
                offset += len
                index++
                onProgress(offset, size)
                partFile.delete()
            }
            src.delete()
            UploadResult(displayName, size, sha, parts)
        }
    }

    private fun safeName(n: String): String =
        n.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80).ifBlank { "file" }

    /** Download every part and concatenate into [target]. */
    suspend fun download(
        parts: List<Long>,
        target: File,
        totalBytes: Long,
        onProgress: (done: Long, total: Long) -> Unit,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            var done = 0L
            val total = if (totalBytes > 0) totalBytes else parts.size.toLong() * CHUNK
            FileOutputStream(target).use { out ->
                for ((i, id) in parts.withIndex()) {
                    val r = Request.Builder()
                        .url("https://api.github.com/repos/$OWNER/$REPO/releases/assets/$id")
                        .addHeader("Authorization", "Bearer $token")
                        .addHeader("Accept", "application/octet-stream")
                        .addHeader("User-Agent", "ET-Chat")
                        .get()
                        .build()
                    client.newCall(r).execute().use { resp ->
                        if (!resp.isSuccessful) error("分片下载失败 HTTP ${resp.code}")
                        val body = resp.body ?: error("空分片")
                        body.byteStream().use { ins ->
                            val buf = ByteArray(1 shl 16)
                            while (true) {
                                val n = ins.read(buf)
                                if (n <= 0) break
                                out.write(buf, 0, n)
                                done += n
                                onProgress(done, maxOf(total, done))
                            }
                        }
                    }
                }
            }
            Unit
        }
    }
}
