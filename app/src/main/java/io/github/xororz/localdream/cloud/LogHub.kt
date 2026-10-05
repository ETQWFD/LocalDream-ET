package io.github.xororz.localdream.cloud

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * In-process ring buffer for the on-device log page. Key lifecycle events
 * (engine start/crash, download start/progress/fail, convert, generation,
 * update, network/upload result) are stamped here in addition to the existing
 * android.util.Log calls. This buffer is never cleared from the UI.
 */
object LogHub {
    enum class Category { ENGINE, DOWNLOAD, CONVERT, GENERATE, UPDATE, NETWORK, UPLOAD, APP }

    data class Entry(val time: Long, val category: Category, val message: String)

    private const val MAX = 400
    private val buf = ConcurrentLinkedDeque<Entry>()
    private val ts = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun log(category: Category, message: String) {
        val e = Entry(System.currentTimeMillis(), category, message)
        buf.addLast(e)
        while (buf.size > MAX) buf.pollFirst()
        Log.i("LogHub/${category.name}", message)
        RollingLogger.log(category.name, message)
    }

    fun all(): List<Entry> = buf.toList()

    fun formatted(filter: Category?): String {
        val sb = StringBuilder()
        for (e in buf) {
            if (filter != null && e.category != filter) continue
            sb.append(ts.format(Date(e.time)))
                .append(" [").append(e.category.name).append("] ")
                .append(e.message).append('\n')
        }
        return sb.toString()
    }
}
