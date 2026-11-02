package io.github.xororz.localdream.utils

import android.content.Context
import android.os.Environment
import java.io.File

/**
 * Central, user-visible storage for all downloaded/generated files.
 *
 * Primary location:  /storage/emulated/0/LocalDreamET
 *   - models/          downloaded and converted models
 *   - temp_downloads/  partial downloads / extraction scratch
 *
 * On Android 11+ writing here requires the "All files access" grant
 * (MANAGE_EXTERNAL_STORAGE); on 28–29 it uses WRITE_EXTERNAL_STORAGE with
 * requestLegacyExternalStorage. Before the grant exists we transparently fall
 * back to an app-private directory so nothing crashes.
 */
object Storage {
    const val DIR_NAME = "LocalDreamET"
    const val MODELS = "models"
    const val TEMP = "temp_downloads"
    const val OUTPUTS = "outputs"
    const val LOGS = "logs"

    /** The preferred public root on shared storage. */
    fun publicRoot(): File =
        File(Environment.getExternalStorageDirectory(), DIR_NAME)

    /** Whether the public root is currently writable by this app. */
    fun isPublicWritable(): Boolean =
        runCatching {
            val root = publicRoot()
            (root.exists() || root.mkdirs()) && root.canWrite()
        }.getOrDefault(false)

    /**
     * Effective root: public storage when permitted, otherwise an app-private
     * fallback carrying the same folder name. If the user picked an SD card
     * location (et.133/209) that override wins when readable.
     */
    fun root(context: Context): File {
        // et.209: user-chosen SD-card / alternate storage override.
        val ov = context.getSharedPreferences("storage_prefs", Context.MODE_PRIVATE)
            .getString("override_root", null)
        if (!ov.isNullOrBlank()) {
            val f = File(ov)
            if (runCatching { (f.exists() || f.mkdirs()) && f.canWrite() }.getOrDefault(false)) {
                return f
            }
        }
        val pub = publicRoot()
        return if (runCatching { (pub.exists() || pub.mkdirs()) && pub.canWrite() }.getOrDefault(false)) {
            pub
        } else {
            File(context.filesDir, DIR_NAME)
        }
    }

    /** List removable SD-card roots (exclude the primary internal volume). */
    fun sdCardRoots(context: Context): List<File> = runCatching {
        val sm = context.getSystemService(Context.STORAGE_SERVICE) as android.os.storage.StorageManager
        sm.storageVolumes.filter { !it.isPrimary && it.isRemovable && it.state == Environment.MEDIA_MOUNTED }
            .mapNotNull { runCatching { it.directory }.getOrNull() }
            .filter { it != null && it.canWrite() }
            .map { File(it, DIR_NAME) }
    }.getOrDefault(emptyList())

    fun setOverrideRoot(context: Context, path: String?) {
        context.getSharedPreferences("storage_prefs", Context.MODE_PRIVATE)
            .edit().apply { if (path.isNullOrBlank()) remove("override_root") else putString("override_root", path) }.apply()
    }

    /**
     * Losslessly move models/ into target root: copy each file, verify size,
     * then delete source. Existing files in target are kept (resume).
     * Returns moved file count.
     */
    fun moveModelsTo(context: Context, targetRoot: File): Int {
        val src = File(root(context), MODELS)
        if (!src.exists()) return 0
        targetRoot.mkdirs()
        var moved = 0
        src.walkTopDown().filter { it.isFile }.forEach { f ->
            val rel = f.relativeTo(src)
            val dst = File(targetRoot, "${MODELS}/$rel")
            dst.parentFile?.mkdirs()
            if (dst.exists() && dst.length() == f.length()) { f.delete(); moved++; return@forEach }
            runCatching {
                f.inputStream().use { input -> dst.outputStream().use { input.copyTo(it) } }
                if (dst.length() == f.length()) { f.delete(); moved++ }
            }
        }
        return moved
    }

    fun modelsDir(context: Context): File =
        File(root(context), MODELS).apply { if (!exists()) runCatching { mkdirs() } }

    fun tempDir(context: Context): File =
        File(root(context), TEMP).apply { if (!exists()) runCatching { mkdirs() } }

    /** Public outputs folder holding generated PNG files. */
    fun outputsDir(context: Context): File =
        File(root(context), OUTPUTS).apply { if (!exists()) runCatching { mkdirs() } }

    /** Public logs folder holding app.log / engine_*.log / convert_*.log. */
    fun logsDir(context: Context): File =
        File(root(context), LOGS).apply { if (!exists()) runCatching { mkdirs() } }
}
