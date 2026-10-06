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
     * fallback carrying the same folder name.
     */
    fun root(context: Context): File {
        val pub = publicRoot()
        return if (runCatching { (pub.exists() || pub.mkdirs()) && pub.canWrite() }.getOrDefault(false)) {
            pub
        } else {
            File(context.filesDir, DIR_NAME)
        }
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
