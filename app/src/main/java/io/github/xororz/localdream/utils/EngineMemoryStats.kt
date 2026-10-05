package io.github.xororz.localdream.utils

import java.io.File

/**
 * et.30: records the native engine child process's peak resident memory by
 * periodically reading /proc/<pid>/status. Read-only and best-effort; if the
 * entries are unreadable it simply reports null and never breaks generation.
 */
object EngineMemoryStats {
    private var pid: Int = 0
    @Volatile var lastVmRssKb: Long = 0
        private set
    @Volatile var lastVmHwmKb: Long = 0
        private set
    @Volatile var lastThreads: Int = 0
        private set

    fun attach(pid: Int) {
        this.pid = pid
        lastVmRssKb = 0; lastVmHwmKb = 0; lastThreads = 0
    }

    fun sample() {
        if (pid <= 0) return
        runCatching {
            File("/proc/$pid/status").forEachLine { line ->
                when {
                    line.startsWith("VmRSS:") -> lastVmRssKb = parseKb(line)
                    line.startsWith("VmHWM:") -> lastVmHwmKb = parseKb(line)
                    line.startsWith("Threads:") -> lastThreads = line.split(Regex("\\s+")).last().toIntOrNull() ?: 0
                }
            }
        }
    }

    fun clear() {
        pid = 0; lastVmRssKb = 0; lastVmHwmKb = 0; lastThreads = 0
    }

    private fun parseKb(line: String): Long =
        line.split(Regex("\\s+")).getOrNull(1)?.replace("kB", "")?.trim()?.toLongOrNull() ?: 0L
}
