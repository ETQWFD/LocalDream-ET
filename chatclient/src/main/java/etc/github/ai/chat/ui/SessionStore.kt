package etc.github.ai.chat.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Simple file-based session persistence:
 *  - messages list -> filesDir/session.json (roles, text, image file refs)
 *  - AI / reference bitmaps -> filesDir/images/ 目录下的 png/jpg 文件
 *    (referenced by relative path, so the giant base64 payloads never live
 *    in preferences).
 * All IO here is blocking; call it from Dispatchers.IO.
 */
class SessionStore(context: Context) {
    private val filesDir = context.filesDir
    private val sessionFile = File(filesDir, "session.json")
    private val imagesDir = File(filesDir, "images").apply { mkdirs() }

    fun writePng(name: String, bmp: Bitmap): String {
        val f = File(imagesDir, "$name.png")
        f.outputStream().use { out -> bmp.compress(Bitmap.CompressFormat.PNG, 90, out) }
        return "images/${f.name}"
    }

    fun writeJpeg(name: String, bmp: Bitmap): String {
        val f = File(imagesDir, "$name.jpg")
        f.outputStream().use { out -> bmp.compress(Bitmap.CompressFormat.JPEG, 85, out) }
        return "images/${f.name}"
    }

    fun decode(relPath: String): Bitmap? =
        runCatching {
            BitmapFactory.decodeFile(File(filesDir, relPath).absolutePath)
        }.getOrNull()

    data class Stored(
        val role: String,
        val id: Long,
        val text: String,
        val userImage: String?,
        val aiImages: List<String>,
        val error: String?,
    )

    fun persist(messages: List<ChatMsg>) {
        val arr = JSONArray()
        messages.forEach { m ->
            val o = JSONObject().put("role", if (m is ChatMsg.User) "user" else "ai")
                .put("id", m.id)
            when (m) {
                is ChatMsg.User -> o.put("text", m.text)
                    .put("image", m.imagePath ?: JSONObject.NULL)
                is ChatMsg.Ai -> o.put("images", JSONArray(m.imagePaths))
                    .put("error", m.error ?: JSONObject.NULL)
            }
            arr.put(o)
        }
        runCatching { sessionFile.writeText(arr.toString()) }
    }

    fun load(): List<Stored> {
        if (!sessionFile.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(sessionFile.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val imgs = o.optJSONArray("images")
                Stored(
                    role = o.getString("role"),
                    id = o.getLong("id"),
                    text = o.optString("text"),
                    userImage = if (o.isNull("image")) null else o.optString("image"),
                    aiImages = if (imgs == null) emptyList()
                    else (0 until imgs.length()).map { imgs.getString(it) },
                    error = if (o.isNull("error")) null else o.optString("error"),
                )
            }
        }.getOrDefault(emptyList())
    }

    fun clear() {
        runCatching { sessionFile.delete(); imagesDir.listFiles()?.forEach { it.delete() } }
    }
}
