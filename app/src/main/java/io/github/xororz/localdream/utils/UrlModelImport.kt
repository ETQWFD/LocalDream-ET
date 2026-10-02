package io.github.xororz.localdream.utils

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.StatFs
import io.github.xororz.localdream.service.ModelConvertEngine
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray
import org.json.JSONObject

/**
 * "Import from link" support.
 *
 * The user pastes either a direct `.safetensors` link or a model page
 * (Hugging Face / hf-mirror / ModelScope). We resolve a concrete single-file
 * SD1.5 checkpoint, run a compatibility precheck BEFORE downloading
 * (file shape, real size vs the 32-bit 2.14 GB ceiling, free storage and a
 * lightweight safetensors header probe), and only then register it. Once
 * registered it behaves exactly like a built-in convert model: the existing
 * foreground download service pulls it (resumable, multi-source), converts it
 * on device, and after the `finished` marker is written it stays in the list
 * forever and runs on CPU/GPU including 32-bit devices.
 *
 * DiT checkpoints (Qwen / Flux / SDXL / .gguf / .ckpt / diffusers folders)
 * cannot run through the sd15cpu engine, so the precheck reports them as
 * unsupported instead of silently failing after a multi-GB download.
 */
object UrlModelImport {

    const val LIMIT_32BIT = 2_140_000_000L // signed 32-bit seek ceiling + margin
    private const val EXTRA_MARGIN = 400L * 1024L * 1024L // headroom beyond download+convert
    private const val HEADER_CAP = 20L * 1024L * 1024L
    private const val UA =
        "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/124.0 Mobile Safari/537.36"

    data class Entry(val id: String, val name: String, val url: String)

    sealed class Norm {
        data class Direct(val convertPath: String, val file: String) : Norm()
        data class Page(val repo: String) : Norm()
        data class Bad(val reason: String) : Norm()
    }

    data class Precheck(
        val supported: Boolean,
        val convertPath: String,
        val fileName: String,
        val sizeBytes: Long,
        val freeBytes: Long,
        val shapeOk: Boolean,
        val sizeOk: Boolean,
        val storageOk: Boolean,
        // true = verified SD1.5 tensors; false = header proves incompatible;
        // null = header could not be fetched within the cap (size still governs).
        val headerSd15: Boolean?,
    )

    // ---- registry persistence -------------------------------------------------

    private fun registryFile(context: Context): File =
        File(Storage.root(context), "url_models.json")

    @Synchronized
    fun entries(context: Context): List<Entry> = try {
        val f = registryFile(context)
        if (!f.exists()) emptyList()
        else {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Entry(o.getString("id"), o.getString("name"), o.getString("url"))
            }
        }
    } catch (_: Exception) {
        emptyList()
    }

    @Synchronized
    fun idSet(context: Context): Set<String> = entries(context).map { it.id }.toSet()

    /** Register a new entry, guaranteeing a unique, filesystem-safe id. */
    @Synchronized
    fun addEntry(context: Context, name: String, convertPath: String): Entry {
        val list = entries(context).toMutableList()
        val base = name.lowercase()
            .filter { it.isLetterOrDigit() }
            .take(32)
            .ifBlank { "model" }
        var id = "urlx_$base"
        var n = 2
        val existing = list.map { it.id }.toMutableSet()
        while (id in existing) {
            id = "urlx_${base}_$n"; n++
        }
        val entry = Entry(id, name.trim().ifBlank { id }, convertPath)
        list.add(entry)
        save(context, list)
        return entry
    }

    private fun save(context: Context, list: List<Entry>) {
        val arr = JSONArray()
        list.forEach { e ->
            arr.put(JSONObject().put("id", e.id).put("name", e.name).put("url", e.url))
        }
        runCatching { registryFile(context).writeText(arr.toString()) }
    }

    // ---- link normalization ---------------------------------------------------

    fun normalize(raw: String): Norm {
        var s = raw.trim()
        if (s.isBlank()) return Norm.Bad("empty")
        if (!s.startsWith("http://") && !s.startsWith("https://")) s = "https://$s"

        val uri = runCatching { Uri.parse(s) }.getOrNull() ?: return Norm.Bad("bad_link")
        val host = uri.host?.lowercase().orEmpty()
        val path = uri.path?.trim('/').orEmpty()
        val segs = path.split('/').filter { it.isNotBlank() }

        // Hugging Face / hf-mirror: direct file (resolve or blob).
        if (host == "huggingface.co" || host == "hf-mirror.com" || host.endsWith(".hf-mirror.com")) {
            val marker = listOf("resolve", "blob").firstOrNull { path.contains("/$it/") }
            if (marker != null) {
                val idx = path.indexOf("/$marker/")
                val repo = path.substring(0, idx)
                val tail = path.substring(idx + marker.length + 2)
                val file = tail.substringAfter('/', tail).let { Uri.decode(it) }
                if (!file.lowercase().endsWith(".safetensors")) return Norm.Bad("not_safetensors")
                return Norm.Direct("$repo/resolve/main/${file}", file.substringAfterLast('/'))
            }
            // Model page: owner/repo, resolve the file list over the mirror API.
            if (segs.size >= 2) return Norm.Page("${segs[0]}/${segs[1]}")
            return Norm.Bad("bad_link")
        }

        // ModelScope: direct file URL carries the repo + FilePath in the query.
        if (host.contains("modelscope.cn")) {
            val filePath = uri.getQueryParameter("FilePath")
            if (!filePath.isNullOrBlank()) {
                val repo = path.substringAfter("/models/", "").trim('/')
                    .ifBlank { path.substringAfter("/api/v1/models/", "").substringBefore("/repo").trim('/') }
                val file = Uri.decode(filePath)
                if (repo.isBlank() || !file.lowercase().endsWith(".safetensors"))
                    return Norm.Bad("not_safetensors")
                return Norm.Direct("$repo/resolve/main/$file", file.substringAfterLast('/'))
            }
            // Model page: modelscope.cn/models/owner/repo
            if (path.startsWith("models/") && segs.size >= 2) {
                return Norm.Page("${segs[segs.lastIndex - 1]}/${segs.last()}")
            }
            return Norm.Bad("bad_link")
        }

        // Any other host: require a direct .safetensors link, used verbatim.
        val lastSeg = segs.lastOrNull().orEmpty()
        return if (lastSeg.lowercase().endsWith(".safetensors")) {
            Norm.Direct(s, Uri.decode(lastSeg))
        } else {
            Norm.Bad("not_safetensors")
        }
    }

    /** Resolve a HF-style model page to a single root .safetensors (prefer fp16). */
    private fun resolvePageFile(repo: String): String? = try {
        val u = "https://hf-mirror.com/api/models/$repo"
        val conn = (URL(u).openConnection() as HttpURLConnection).apply {
            connectTimeout = 12_000; readTimeout = 12_000
            setRequestProperty("User-Agent", UA)
        }
        if (conn.responseCode != 200) null
        else {
            val obj = JSONObject(conn.inputStream.bufferedReader().readText())
            val files = obj.optJSONArray("siblings") ?: return null
            val names = (0 until files.length()).mapNotNull { i ->
                files.optJSONObject(i)?.optString("rfilename").orEmpty()
            }.filter { it.endsWith(".safetensors") && !it.contains('/') }
            val pick = names.filter { it.lowercase().contains("fp16") }.minByOrNull { it.length }
                ?: names.minByOrNull { it.length }
            pick?.let { "$repo/resolve/main/$it" }
        }
    } catch (_: Exception) {
        null
    }

    // ---- precheck -------------------------------------------------------------

    fun is64Bit(): Boolean =
        Build.SUPPORTED_ABIS?.any { it == "arm64-v8a" } ?: false

    fun freeBytes(context: Context): Long =
        runCatching { StatFs(Storage.root(context).path).availableBytes }.getOrDefault(0L)

    /** Must be called off the main thread. Returns null only for an unusable link. */
    fun precheck(context: Context, rawInput: String): Precheck? {
        var convertPath: String
        var fileName: String
        when (val n = normalize(rawInput)) {
            is Norm.Direct -> { convertPath = n.convertPath; fileName = n.file }
            is Norm.Page -> {
                val resolved = resolvePageFile(n.repo) ?: return null
                convertPath = resolved; fileName = resolved.substringAfterLast('/')
            }
            is Norm.Bad -> return null
        }

        val shapeOk = fileName.lowercase().endsWith(".safetensors")

        // Resolve the real size through the exact same ordered source list the
        // downloader will use, so the verdict matches download reality.
        var size = -1L
        var sizeUrl: String? = null
        for (url in ModelConvertEngine.probeCandidateUrls(context, convertPath)) {
            val s = probeSize(url)
            if (s > 0L) { size = s; sizeUrl = url; break }
        }
        val sizeOk = size in 1..LIMIT_32BIT
        val free = freeBytes(context)
        // Need room for the download plus the converted artifact (~2x source).
        val need = if (size > 0) size * 2 + EXTRA_MARGIN else Long.MAX_VALUE
        val storageOk = size > 0 && free >= need

        val headerSd15: Boolean? = if (sizeOk && sizeUrl != null) probeHeader(sizeUrl) else null

        val supported = shapeOk && sizeOk && storageOk && headerSd15 != false
        return Precheck(
            supported = supported,
            convertPath = convertPath,
            fileName = fileName,
            sizeBytes = size,
            freeBytes = free,
            shapeOk = shapeOk,
            sizeOk = sizeOk,
            storageOk = storageOk,
            headerSd15 = headerSd15,
        )
    }

    private fun open(url: String, range: String?): HttpURLConnection? = try {
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 12_000
            readTimeout = 20_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Accept", "*/*")
            if (range != null) setRequestProperty("Range", range)
        }
    } catch (_: Exception) {
        null
    }

    private fun probeSize(url: String): Long = try {
        val c = open(url, "bytes=0-0") ?: return -1L
        c.connect()
        when (c.responseCode) {
            206 -> {
                val cr = c.getHeaderField("Content-Range").orEmpty()
                if ("/" in cr) cr.substringAfterLast('/').toLongOrNull() ?: -1L else -1L
            }
            in 200..299 -> c.contentLengthLong.coerceAtLeast(-1L)
            else -> -1L
        }
    } catch (_: Exception) {
        -1L
    }

    private fun probeHeader(url: String): Boolean? = try {
        val c0 = open(url, "bytes=0-7") ?: return null
        c0.connect()
        if (c0.responseCode !in 200..206) return null
        val head = c0.inputStream.use { it.readNBytesCompat(8) }
        if (head.size < 8) return null
        val n = java.nio.ByteBuffer.wrap(head).order(java.nio.ByteOrder.LITTLE_ENDIAN).long
        if (n <= 0L || n > HEADER_CAP) return null
        val c1 = open(url, "bytes=8-${7 + n}") ?: return null
        c1.connect()
        if (c1.responseCode !in 200..206) return null
        val raw = c1.inputStream.use { it.readNBytesCompat(n.toInt()) }
        val keys = JSONObject(String(raw, Charsets.UTF_8)).keys()
        val set = mutableSetOf<String>()
        while (keys.hasNext()) set.add(keys.next())
        val hasTimeEmbed = "model.diffusion_model.time_embed.0.weight" in set
        val hasClip =
            "cond_stage_model.transformer.text_model.embeddings.token_embedding.weight" in set
        val isSdxl = set.any {
            it == "model.diffusion_model.label_emb.0.0.weight" ||
                it.startsWith("conditioner.embedders")
        }
        when {
            isSdxl -> false
            hasTimeEmbed && hasClip -> true
            else -> false
        }
    } catch (_: Exception) {
        null
    }

    private fun java.io.InputStream.readNBytesCompat(len: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        var remaining = len
        while (remaining > 0) {
            val r = read(buf, 0, minOf(buf.size, remaining))
            if (r < 0) break
            out.write(buf, 0, r); remaining -= r
        }
        return out.toByteArray()
    }
}
