package io.github.xororz.localdream.utils

/** et.30: records the last native engine exit for OOM/crash mapping. */
object EngineExitInfo {
    @Volatile var lastExitCode: Int = 0
        private set
    @Volatile var lastModelId: String = ""
        private set
    @Volatile var timestampMs: Long = 0
        private set

    fun record(code: Int, modelId: String) {
        lastExitCode = code
        lastModelId = modelId
        timestampMs = System.currentTimeMillis()
    }

    fun clear() {
        lastExitCode = 0
        lastModelId = ""
        timestampMs = 0
    }

    /** Human-readable mapping for engine exit codes. */
    fun describe(code: Int): String = when (code) {
        137 -> "engine exited code=137 (SIGKILL/OOM, system killed the process)"
        134 -> "engine exited code=134 (SIGABRT, native abort)"
        139 -> "engine exited code=139 (SIGSEGV, native segfault)"
        else -> "engine exited code=$code"
    }
}
