package io.github.xororz.localdream.data

import android.content.Context
import android.net.Uri
import io.github.xororz.localdream.data.db.AppDatabase
import io.github.xororz.localdream.data.db.HistoryEntity
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class BackupFormatException(message: String) : IOException(message)

data class BackupStats(
    val totalCount: Int,
    val totalBytes: Long,
    val favoriteCount: Int,
    val favoriteBytes: Long,
)

data class BackupExportResult(
    val exported: Int,
    val skippedMissingImage: Int,
)

data class BackupImportResult(
    val imported: Int,
    val duplicates: Int,
    val failed: Int,
    val orphanModelIds: Set<String>,
)

// Exports generation history (DB rows plus image files) into a single zip
// and merges such zips back in. The zip carries a JSON manifest instead of
// a raw DB copy so backups stay readable across schema versions and import
// merges into the existing history rather than replacing it.
object HistoryBackup {
    private const val MANIFEST_NAME = "manifest.json"
    private const val FORMAT = "localdream-history-backup"
    private const val VERSION = 1

    // Exactly the layout saveGeneratedImage produces. Anything else in a
    // manifest (absolute paths, traversal, foreign files) is rejected.
    private val imagePathRegex = Regex("""history/[^/\\]+/\d+\.(png|jpg)""")

    suspend fun stats(context: Context): BackupStats = withContext(Dispatchers.IO) {
        val dao = AppDatabase.get(context).historyDao()
        val rows = dao.queryOnce(HistoryFilter().toSqlQuery())
        var totalCount = 0
        var totalBytes = 0L
        var favoriteCount = 0
        var favoriteBytes = 0L
        for (row in rows) {
            val file = File(context.filesDir, row.imagePath)
            if (!file.isFile) continue
            val size = file.length()
            totalCount++
            totalBytes += size
            if (row.favorite) {
                favoriteCount++
                favoriteBytes += size
            }
        }
        BackupStats(totalCount, totalBytes, favoriteCount, favoriteBytes)
    }

    suspend fun export(
        context: Context,
        uri: Uri,
        favoritesOnly: Boolean,
        onProgress: (done: Int, total: Int) -> Unit,
    ): BackupExportResult = withContext(Dispatchers.IO) {
        val dao = AppDatabase.get(context).historyDao()
        val filter = HistoryFilter(
            favorites = if (favoritesOnly) setOf(FavoriteFilter.FAVORITE) else null,
        )
        val rows = dao.queryOnce(filter.toSqlQuery())
        val items = rows.filter { File(context.filesDir, it.imagePath).isFile }

        val output = context.contentResolver.openOutputStream(uri)
            ?: throw IOException("Cannot open backup destination")
        ZipOutputStream(BufferedOutputStream(output)).use { zip ->
            zip.setLevel(Deflater.BEST_SPEED)
            zip.putNextEntry(ZipEntry(MANIFEST_NAME))
            zip.write(buildManifest(items).toString().toByteArray())
            zip.closeEntry()

            // The payloads are PNG/JPEG; deflating them again only burns CPU.
            zip.setLevel(Deflater.NO_COMPRESSION)
            items.forEachIndexed { index, row ->
                ensureActive()
                zip.putNextEntry(ZipEntry(row.imagePath))
                File(context.filesDir, row.imagePath).inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
                onProgress(index + 1, items.size)
            }
        }
        BackupExportResult(items.size, rows.size - items.size)
    }

    suspend fun import(
        context: Context,
        uri: Uri,
        installedModelIds: Set<String>,
        onProgress: (done: Int, total: Int) -> Unit,
    ): BackupImportResult = withContext(Dispatchers.IO) {
        val dao = AppDatabase.get(context).historyDao()

        // Pass 1: find and parse the manifest. Our own zips put it first, but
        // scan the whole archive so re-zipped backups still import.
        val manifest = openZip(context, uri).use { zip ->
            generateSequence { zip.nextEntry }
                .firstOrNull { it.name == MANIFEST_NAME }
                ?.let { JSONObject(zip.readBytes().toString(Charsets.UTF_8)) }
        } ?: throw BackupFormatException("manifest.json not found")

        if (manifest.optString("format") != FORMAT) {
            throw BackupFormatException("unrecognized backup format")
        }
        if (manifest.optInt("version", Int.MAX_VALUE) > VERSION) {
            throw BackupFormatException("backup was created by a newer app version")
        }

        val itemsJson = manifest.optJSONArray("items") ?: JSONArray()
        val pending = LinkedHashMap<String, HistoryEntity>()
        var duplicates = 0
        var invalid = 0
        for (i in 0 until itemsJson.length()) {
            val entity = itemsJson.optJSONObject(i)?.let { jsonToEntity(it) }
            if (entity == null || !imagePathRegex.matches(entity.imagePath)) {
                invalid++
                continue
            }
            if (dao.countByKey(entity.modelId, entity.timestamp) > 0) {
                duplicates++
                continue
            }
            pending[entity.imagePath] = entity
        }

        // Pass 2: stream images to their final location, then insert the row.
        // Each item commits on its own, so a cancelled import keeps what it
        // finished and a re-run resumes via the duplicate check above.
        val total = pending.size
        var imported = 0
        val orphanModelIds = mutableSetOf<String>()
        if (total > 0) {
            openZip(context, uri).use { zip ->
                generateSequence { zip.nextEntry }.forEach { entry ->
                    ensureActive()
                    val entity = pending.remove(entry.name) ?: return@forEach
                    writeImage(zip, File(context.filesDir, entity.imagePath))
                    dao.insert(entity)
                    imported++
                    if (entity.modelId !in installedModelIds) {
                        orphanModelIds += entity.modelId
                    }
                    onProgress(imported, total)
                }
            }
        }
        // Whatever is still pending had no matching image entry in the zip.
        BackupImportResult(imported, duplicates, invalid + pending.size, orphanModelIds)
    }

    private fun openZip(context: Context, uri: Uri): ZipInputStream {
        val input: InputStream = context.contentResolver.openInputStream(uri)
            ?: throw IOException("Cannot open backup file")
        return ZipInputStream(input.buffered())
    }

    private fun writeImage(source: InputStream, dest: File) {
        dest.parentFile?.mkdirs()
        val tmp = File(dest.parentFile, "${dest.name}.part")
        try {
            tmp.outputStream().use { source.copyTo(it) }
            if (!tmp.renameTo(dest)) {
                throw IOException("Failed to move ${tmp.name} into place")
            }
        } finally {
            tmp.delete()
        }
    }

    private fun buildManifest(items: List<HistoryEntity>): JSONObject {
        val array = JSONArray()
        items.forEach { array.put(entityToJson(it)) }
        return JSONObject().apply {
            put("format", FORMAT)
            put("version", VERSION)
            put("exportedAt", System.currentTimeMillis())
            put("items", array)
        }
    }

    private fun entityToJson(e: HistoryEntity): JSONObject = JSONObject().apply {
        put("modelId", e.modelId)
        put("timestamp", e.timestamp)
        put("imagePath", e.imagePath)
        put("width", e.width)
        put("height", e.height)
        put("mode", e.mode)
        e.denoiseStrength?.let { put("denoiseStrength", it.toDouble()) }
        e.upscalerId?.let { put("upscalerId", it) }
        put("steps", e.steps)
        put("cfg", e.cfg.toDouble())
        e.seed?.let { put("seed", it) }
        put("prompt", e.prompt)
        put("negativePrompt", e.negativePrompt)
        e.generationTime?.let { put("generationTime", it) }
        put("scheduler", e.scheduler)
        put("runOnCpu", e.runOnCpu)
        put("useOpenCL", e.useOpenCL)
        put("favorite", e.favorite)
    }

    private fun jsonToEntity(json: JSONObject): HistoryEntity? {
        val modelId = json.optString("modelId")
        val timestamp = json.optLong("timestamp", -1L)
        val imagePath = json.optString("imagePath")
        if (modelId.isEmpty() || timestamp <= 0 || imagePath.isEmpty()) return null
        return HistoryEntity(
            modelId = modelId,
            timestamp = timestamp,
            imagePath = imagePath,
            width = json.optInt("width", 512),
            height = json.optInt("height", 512),
            mode = json.optString("mode", GenerationMode.UNKNOWN.name),
            denoiseStrength = if (json.has("denoiseStrength")) {
                json.optDouble("denoiseStrength").toFloat()
            } else {
                null
            },
            upscalerId = json.optString("upscalerId").ifEmpty { null },
            steps = json.optInt("steps", 20),
            cfg = json.optDouble("cfg", 7.0).toFloat(),
            seed = if (json.has("seed")) json.optLong("seed") else null,
            prompt = json.optString("prompt", ""),
            negativePrompt = json.optString("negativePrompt", ""),
            generationTime = json.optString("generationTime").ifEmpty { null },
            scheduler = json.optString("scheduler", "dpm"),
            runOnCpu = json.optBoolean("runOnCpu", false),
            useOpenCL = json.optBoolean("useOpenCL", false),
            favorite = json.optBoolean("favorite", false),
        )
    }
}
