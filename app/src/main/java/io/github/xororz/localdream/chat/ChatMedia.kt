package io.github.xororz.localdream.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaRecorder
import android.net.Uri
import androidx.core.content.edit
import io.github.xororz.localdream.BuildConfig
import io.github.xororz.localdream.cloud.CloudConfig
import io.github.xororz.localdream.cloud.SecureTokenStore
import java.io.File
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Public chat transport.
 *
 * Messages and uploaded media both live in the public repo
 * ETQWFD/LocalDreamET-Chat (branch main):
 *   messages.json          – the whole (small) message log, read-modify-write
 *   media/img_<ts>.jpg     – images posted from the chat
 *   media/vid_<ts>.mp4     – short videos
 *   media/aud_<ts>.m4a     – voice messages
 *
 * Reads are tried against several mirrors because raw.githubusercontent is
 * frequently unreachable from mainland China; writes go to api.github.com with
 * the signed-in user's PAT (falling back to the baked-in owner token).
 */
object ChatRepository {
    private const val OWNER = "ETQWFD"
    private const val REPO = "LocalDreamET-Chat"
    private const val FILE = "messages.json"
    private const val MEDIA_DIR = "media"

    /** GitHub's contents API rejects files above 100 MB; keep a margin. */
    const val MAX_VIDEO_BYTES = 15L * 1024 * 1024
    const val MAX_AUDIO_BYTES = 8L * 1024 * 1024

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(45, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val READ_URLS = listOf(
        "https://raw.githubusercontent.com/$OWNER/$REPO/main/$FILE",
        "https://cdn.jsdelivr.net/gh/$OWNER/$REPO@main/$FILE",
        "https://raw.gitmirror.com/$OWNER/$REPO/main/$FILE",
        "https://raw.kkgithub.com/$OWNER/$REPO/main/$FILE",
    )

    fun rawUrl(path: String): String = "https://raw.githubusercontent.com/$OWNER/$REPO/main/$path"

    fun cdnUrl(path: String): String = "https://cdn.jsdelivr.net/gh/$OWNER/$REPO@main/$path"

    /** PAT used for writes: the user's own token first, owner token as fallback. */
    fun writeToken(context: Context): String {
        val user = SecureTokenStore(context).token(CloudConfig.Provider.GITHUB).orEmpty()
        return user.ifBlank { BuildConfig.GALLERY_TOKEN }
    }

    private fun readWithToken(url: String, token: String?): Pair<Int, String> {
        val req = Request.Builder().url(url).get().apply {
            addHeader("User-Agent", "LocalDream-ET")
            addHeader("Accept", "application/vnd.github+json")
            if (!token.isNullOrBlank()) addHeader("Authorization", "Bearer $token")
        }.build()
        return client.newCall(req).execute().use { resp ->
            resp.code to (resp.body?.string().orEmpty())
        }
    }

    /** Loads the log; never throws, returns an empty list on total failure. */
    suspend fun load(context: Context): List<ChatMessage> = withContext(Dispatchers.IO) {
        // Cache-buster defeats the CDN's few-minutes staleness after a write.
        val bust = System.currentTimeMillis()
        for ((i, url) in READ_URLS.withIndex()) {
            val u = if (i == 0) "$url?t=$bust" else url
            try {
                val (code, body) = readWithToken(u, null)
                if (code == 200) return@withContext ChatMerge.latest(ChatMessage.listFromJson(body))
            } catch (_: Exception) {
                // next mirror
            }
        }
        emptyList()
    }

    /**
     * Read-modify-write append. GitHub returns 409 when two clients race on the
     * same blob, so retry a few times, re-reading the sha each attempt.
     */
    suspend fun send(context: Context, message: ChatMessage): Result<Unit> =
        withContext(Dispatchers.IO) {
            val token = writeToken(context)
            if (token.isBlank()) {
                return@withContext Result.failure(IllegalStateException("未登录 GitHub，且内置令牌缺失，无法发言"))
            }
            var lastError = "发送失败"
            repeat(4) { attempt ->
                try {
                    val api = "https://api.github.com/repos/$OWNER/$REPO/contents/$FILE"
                    var sha = ""
                    var base: List<ChatMessage> = emptyList()
                    val (code, text) = readWithToken(api, token)
                    if (code == 200) {
                        val obj = JSONObject(text)
                        sha = obj.optString("sha", "")
                        val content = obj.optString("content", "").replace("\n", "")
                        if (content.isNotBlank()) {
                            base = ChatMessage.listFromJson(
                                String(Base64.getDecoder().decode(content), Charsets.UTF_8),
                            )
                        } else {
                            // File larger than 1 MB: contents API omits body; use the raw mirror.
                            base = load(context)
                        }
                    }
                    val merged = ChatMerge.append(base, message)
                    val payload = JSONObject()
                        .put("message", "chat: ${message.kind.name.lowercase()} by ${message.author}")
                        .put(
                            "content",
                            Base64.getEncoder().encodeToString(
                                ChatMessage.listToJson(merged).toByteArray(Charsets.UTF_8),
                            ),
                        )
                        .apply { if (sha.isNotBlank()) put("sha", sha) }
                    val req = Request.Builder()
                        .url(api)
                        .put(payload.toString().toRequestBody("application/json".toMediaType()))
                        .addHeader("Authorization", "Bearer $token")
                        .addHeader("Accept", "application/vnd.github+json")
                        .addHeader("User-Agent", "LocalDream-ET")
                        .build()
                    client.newCall(req).execute().use { resp ->
                        if (resp.isSuccessful) return@withContext Result.success(Unit)
                        val body = resp.body?.string().orEmpty()
                        lastError = when (resp.code) {
                            401 -> "GitHub 令牌无效或已过期"
                            403 -> "GitHub 拒绝写入（令牌缺少 repo 权限或被限流）"
                            409 -> "有人同时发言，正在重试…"
                            422 -> "消息格式被 GitHub 拒绝"
                            else -> "发送失败 HTTP ${resp.code}"
                        }
                        if (resp.code == 403) return@withContext Result.failure(IllegalStateException(lastError))
                    }
                } catch (e: Exception) {
                    lastError = e.message ?: e.javaClass.simpleName
                }
                // small backoff before the next read-modify-write round
                Thread.sleep(400L * (attempt + 1))
            }
            Result.failure(IllegalStateException(lastError))
        }

    /**
     * Uploads one media file and returns (canonical, cdn) URLs, or null with a
     * human readable reason in [lastError].
     */
    suspend fun uploadMedia(
        context: Context,
        bytes: ByteArray,
        extension: String,
        kindTag: String,
    ): MediaUpload = withContext(Dispatchers.IO) {
        val token = writeToken(context)
        if (token.isBlank()) {
            return@withContext MediaUpload(null, null, "未登录 GitHub，无法上传")
        }
        if (bytes.isEmpty()) return@withContext MediaUpload(null, null, "文件为空")
        val safeExt = extension.lowercase().filter { it.isLetterOrDigit() }.ifBlank { "bin" }
        val path = "$MEDIA_DIR/${kindTag}_${System.currentTimeMillis()}_${(1000..9999).random()}.$safeExt"
        try {
            val body = JSONObject()
                .put("message", "media: $path")
                .put("content", Base64.getEncoder().encodeToString(bytes))
                .toString()
                .toRequestBody("application/json".toMediaType())
            val req = Request.Builder()
                .url("https://api.github.com/repos/$OWNER/$REPO/contents/$path")
                .put(body)
                .addHeader("Authorization", "Bearer $token")
                .addHeader("Accept", "application/vnd.github+json")
                .addHeader("User-Agent", "LocalDream-ET")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val detail = resp.body?.string().orEmpty()
                    val reason = when (resp.code) {
                        401 -> "上传令牌无效"
                        403 -> "令牌无仓库写权限"
                        413 -> "文件太大，GitHub 拒绝接收"
                        else -> "上传失败 HTTP ${resp.code} ${detail.take(80)}"
                    }
                    return@withContext MediaUpload(null, null, reason)
                }
            }
            MediaUpload(rawUrl(path), cdnUrl(path), "")
        } catch (e: Exception) {
            MediaUpload(null, null, e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * Re-encodes a picked image to JPEG so a 12 MP photo does not become a
     * 40 MB base64 request. Long edge capped at 1280, quality 82.
     */
    suspend fun compressImage(context: Context, uri: Uri): ByteArray? =
        withContext(Dispatchers.IO) {
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, bounds)
                }
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
                var sample = 1
                while (bounds.outWidth / sample > 2048 || bounds.outHeight / sample > 2048) {
                    sample *= 2
                }
                val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                val decoded = context.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, opts)
                } ?: return@runCatching null
                val maxEdge = 1280
                val scale = minOf(
                    1f,
                    maxEdge.toFloat() / maxOf(decoded.width, decoded.height),
                )
                val target = if (scale < 1f) {
                    Bitmap.createScaledBitmap(
                        decoded,
                        (decoded.width * scale).toInt().coerceAtLeast(1),
                        (decoded.height * scale).toInt().coerceAtLeast(1),
                        true,
                    )
                } else {
                    decoded
                }
                val out = java.io.ByteArrayOutputStream()
                target.compress(Bitmap.CompressFormat.JPEG, 82, out)
                if (target !== decoded) target.recycle()
                decoded.recycle()
                out.toByteArray()
            }.getOrNull()
        }
}

data class MediaUpload(val raw: String?, val cdn: String?, val error: String)

/**
 * Minimal MediaRecorder wrapper for voice messages. WeChat-style chat needs
 * AAC in an MP4 container; every Android device can play that back natively.
 */
class VoiceRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var target: File? = null
    private var startedAt = 0L

    val isRecording: Boolean get() = recorder != null

    fun start(): Boolean {
        if (recorder != null) return true
        val file = File(context.cacheDir, "voice_${System.currentTimeMillis()}.m4a")
        return try {
            val r = if (android.os.Build.VERSION.SDK_INT >= 31) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioSamplingRate(44100)
            r.setAudioEncodingBitRate(96000)
            r.setAudioChannels(1)
            r.setOutputFile(file.absolutePath)
            r.prepare()
            r.start()
            recorder = r
            target = file
            startedAt = System.currentTimeMillis()
            true
        } catch (e: Exception) {
            runCatching { recorder?.release() }
            recorder = null
            target = null
            runCatching { file.delete() }
            false
        }
    }

    /** Stops and returns (file, durationSec); null when nothing usable. */
    fun stop(): Pair<File, Int>? {
        val r = recorder ?: return null
        val file = target
        val elapsed = ((System.currentTimeMillis() - startedAt) / 1000L).toInt()
        recorder = null
        target = null
        return try {
            r.stop()
            r.release()
            if (file != null && file.exists() && file.length() > 0) {
                file to elapsed.coerceAtLeast(1)
            } else {
                file?.delete()
                null
            }
        } catch (e: Exception) {
            runCatching { r.release() }
            file?.delete()
            null
        }
    }

    fun cancel() {
        val r = recorder ?: return
        recorder = null
        runCatching { r.stop() }
        runCatching { r.release() }
        target?.delete()
        target = null
    }
}

/**
 * Admin / mute policy, stored as admin.json in the same chat repo so the web
 * console (docs/liaoti.html) and the app always agree:
 *
 * { "admins": ["ETQWFD"], "mutes": { "somebody": {"until": 1790000000, "by": "ETQWFD"} } }
 */
object ChatPolicyRepo {
    private const val FILE = "admin.json"
    private const val OWNER_LOGIN = "ETQWFD"

    private fun readUrls(): List<String> = listOf(
        "https://raw.githubusercontent.com/ETQWFD/LocalDreamET-Chat/main/$FILE",
        "https://cdn.jsdelivr.net/gh/ETQWFD/LocalDreamET-Chat@main/$FILE",
        "https://raw.gitmirror.com/ETQWFD/LocalDreamET-Chat/main/$FILE",
    )

    suspend fun load(): ChatPolicy = withContext(Dispatchers.IO) {
        for (url in readUrls()) {
            try {
                val req = okhttp3.Request.Builder().url(url)
                    .addHeader("User-Agent", "LocalDream-ET")
                    .build()
                OkHttpClientHolder.client.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val body = resp.body?.string().orEmpty()
                        return@withContext parse(body)
                    }
                }
            } catch (_: Exception) {
                // next mirror
            }
        }
        ChatPolicy(admins = setOf(OWNER_LOGIN))
    }

    fun parse(raw: String): ChatPolicy = runCatching {
        val obj = JSONObject(raw)
        val admins = LinkedHashSet<String>()
        admins.add(OWNER_LOGIN)
        obj.optJSONArray("admins")?.let { arr ->
            for (i in 0 until arr.length()) {
                arr.optString(i).takeIf { it.isNotBlank() }?.let { admins.add(it) }
            }
        }
        val mutes = mutableMapOf<String, ChatMute>()
        obj.optJSONObject("mutes")?.let { m ->
            m.keys().forEach { key ->
                val v = m.optJSONObject(key)
                if (v != null) {
                    mutes[key] = ChatMute(
                        until = v.optLong("until", 0L),
                        by = v.optString("by", ""),
                        reason = v.optString("reason", ""),
                    )
                } else {
                    mutes[key] = ChatMute(until = m.optLong(key, 0L))
                }
            }
        }
        ChatPolicy(admins, mutes)
    }.getOrNull() ?: ChatPolicy(admins = setOf(OWNER_LOGIN))

    fun toJson(policy: ChatPolicy): String {
        val mutes = JSONObject()
        policy.mutes.forEach { (user, m) ->
            mutes.put(
                user,
                JSONObject().put("until", m.until).put("by", m.by).put("reason", m.reason),
            )
        }
        return JSONObject()
            .put("admins", org.json.JSONArray(policy.admins.toList()))
            .put("mutes", mutes)
            .toString()
    }

    /** Publishes the policy; caller must be an admin. */
    suspend fun save(context: Context, policy: ChatPolicy): Result<Unit> =
        withContext(Dispatchers.IO) {
            val token = ChatRepository.writeToken(context)
            if (token.isBlank()) return@withContext Result.failure(IllegalStateException("缺少 GitHub 令牌"))
            val api = "https://api.github.com/repos/ETQWFD/LocalDreamET-Chat/contents/$FILE"
            repeat(3) { attempt ->
                try {
                    var sha = ""
                    val getReq = okhttp3.Request.Builder().url(api)
                        .addHeader("Authorization", "Bearer $token")
                        .addHeader("User-Agent", "LocalDream-ET")
                        .build()
                    OkHttpClientHolder.client.newCall(getReq).execute().use { resp ->
                        if (resp.isSuccessful) {
                            sha = runCatching {
                                JSONObject(resp.body?.string().orEmpty()).optString("sha", "")
                            }.getOrDefault("")
                        }
                    }
                    val payload = JSONObject()
                        .put("message", "policy: admins/mutes")
                        .put(
                            "content",
                            Base64.getEncoder().encodeToString(toJson(policy).toByteArray()),
                        )
                        .apply { if (sha.isNotBlank()) put("sha", sha) }
                    val putReq = okhttp3.Request.Builder().url(api)
                        .put(payload.toString().toRequestBody("application/json".toMediaType()))
                        .addHeader("Authorization", "Bearer $token")
                        .addHeader("User-Agent", "LocalDream-ET")
                        .build()
                    OkHttpClientHolder.client.newCall(putReq).execute().use { resp ->
                        if (resp.isSuccessful) return@withContext Result.success(Unit)
                        if (resp.code == 409) {
                            // race: retry with the fresh sha
                        } else {
                            return@withContext Result.failure(
                                IllegalStateException("写入禁言配置失败 HTTP ${resp.code}"),
                            )
                        }
                    }
                } catch (e: Exception) {
                    if (attempt == 2) {
                        return@withContext Result.failure(
                            IllegalStateException(e.message ?: "写入禁言配置失败"),
                        )
                    }
                }
                Thread.sleep(300L)
            }
            Result.failure(IllegalStateException("写入禁言配置失败"))
        }
}

private object OkHttpClientHolder {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()
}

object ChatPrefs {
    private const val PREFS = "chat_prefs"

    fun draft(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("draft", "").orEmpty()

    fun saveDraft(context: Context, text: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString("draft", text) }
    }
}

object ChatTime {
    fun clock(tsSeconds: Long): String {
        if (tsSeconds <= 0) return ""
        val fmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        return fmt.format(java.util.Date(tsSeconds * 1000L))
    }

    fun dayLabel(tsSeconds: Long): String {
        val fmt = java.text.SimpleDateFormat("MM月dd日 HH:mm", java.util.Locale.getDefault())
        return fmt.format(java.util.Date(tsSeconds * 1000L))
    }

    fun duration(seconds: Int): String {
        val s = seconds.coerceAtLeast(0)
        return if (s < 60) "${s}\"" else "${s / 60}'${(s % 60).toString().padStart(2, '0')}\""
    }

    /** True when a new time separator should be drawn between the two entries. */
    fun needsSeparator(previous: Long, current: Long): Boolean =
        previous <= 0 || current - previous > 5 * 60
}
