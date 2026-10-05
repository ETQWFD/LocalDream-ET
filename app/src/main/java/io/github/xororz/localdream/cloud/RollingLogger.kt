package io.github.xororz.localdream.cloud

import android.content.Context
import android.util.Log
import io.github.xororz.localdream.utils.Storage
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Lightweight size-rotating app log written to the public folder
 * /storage/emulated/0/LocalDreamET/logs/app.log. Keeps app.log + app.1.log +
 * app.2.log (~2 MB each); when app.log fills, rename rotates (app.1 -> app.2,
 * app.log -> app.1) and starts a fresh app.log. If public storage is not
 * writable it falls back to filesDir. There is intentionally NO delete API.
 */
object RollingLogger {
    private const val MAX_BYTES = 2L * 1024L * 1024L // 2 MB
    private const val KEEP = 3
    private val ts = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)
    private var baseDir: File? = null

    fun init(context: Context) {
        var dir: File? = null
        runCatching {
            val pub = File(Storage.publicRoot(), "logs")
            if ((pub.exists() || pub.mkdirs()) && pub.canWrite()) dir = pub
        }
        if (dir == null) {
            runCatching { dir = File(context.filesDir, "logs").apply { mkdirs() } }
        }
        baseDir = dir
    }

    /** Effective log directory (for the UI to display the real path). */
    fun currentDir(): File? = baseDir

    @Synchronized
    fun log(category: String, message: String) {
        Log.i("RollingLogger/$category", message)
        val dir = baseDir ?: return
        try {
            val main = File(dir, "app.log")
            if (main.exists() && main.length() >= MAX_BYTES) rotate(dir)
            FileOutputStream(main, true).bufferedWriter().use { w ->
                w.write(ts.format(Date()))
                w.write(" [")
                w.write(category)
                w.write("] ")
                w.write(message)
                w.newLine()
            }
        } catch (_: Throwable) {
            // logging must never crash the app
        }
    }

    private fun rotate(dir: File) {
        File(dir, "app.$((KEEP - 1)).log").delete()
        for (i in (KEEP - 2) downTo 0) {
            val src = File(dir, if (i == 0) "app.log" else "app.$i.log")
            val dst = File(dir, "app.${i + 1}.log")
            if (src.exists()) src.renameTo(dst)
        }
    }

    /** Read the current + rotated logs for the log viewer (newest last). */
    fun readAll(): String {
        val dir = baseDir ?: return "(log dir unavailable)"
        val sb = StringBuilder()
        for (i in (KEEP - 1) downTo 0) {
            val f = File(dir, if (i == 0) "app.log" else "app.$i.log")
            if (f.exists()) {
                sb.appendLine("== ${f.name} ==")
                runCatching { f.readLines().takeLast(400).forEach { sb.appendLine(it) } }
            }
        }
        return sb.toString()
    }
}
