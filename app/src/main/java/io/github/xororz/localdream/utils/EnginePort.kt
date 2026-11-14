package io.github.xororz.localdream.utils

import android.content.Context
import java.net.ServerSocket

/**
 * et.130追加12: configurable local engine/HTTP ports.
 * Engine port (default 8081) and HTTP port (default 8080) are persisted in
 * SharedPreferences. If the chosen engine port is occupied, the engine start
 * picks the next free port (8081->8082->8083...) and the generation request
 * URL uses the actually-resolved port.
 */
object EnginePort {
    private const val PREFS = "app_prefs"

    /** et.189: the port the engine ACTUALLY listens on (may be bumped past 8081).
        Every health check / generate / tokenize URL must read this instead of a
        hard-coded 8081, otherwise a stale listener on 8081 makes the UI report
        "后端启动失败" although the engine is happily serving on 8082. */
    @Volatile var activePort: Int = 8081
        private set
    val host: String get() = "localhost:$activePort"
    const val DEFAULT_ENGINE_PORT = 8081
    const val DEFAULT_HTTP_PORT = 8080
    const val MAX_BUMP = 16

    fun configuredEnginePort(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt("server_port", DEFAULT_ENGINE_PORT)

    fun configuredHttpPort(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt("http_port", DEFAULT_HTTP_PORT)

    /** First free port >= [start]; if none within range, returns start anyway. */
    fun resolveFreePort(start: Int): Int {
        var p = start
        repeat(MAX_BUMP) {
            var s: ServerSocket? = null
            try {
                s = ServerSocket(p)
                s.reuseAddress = true
                return p
            } catch (_: Exception) {
                p++
            } finally {
                try { s?.close() } catch (_: Exception) {}
            }
        }
        return start
    }

    /** Free port for the engine, persisted so the request URL can reuse it. */
    fun enginePort(context: Context): Int {
        val resolved = resolveFreePort(configuredEnginePort(context))
        activePort = resolved
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt("active_engine_port", resolved).apply()
        return resolved
    }

    fun activeEnginePort(context: Context): Int {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt("active_engine_port", configuredEnginePort(context))
        activePort = p
        return p
    }

    /** et.190: actively probe 8081..8081+MAX_BUMP and bind to the port a live
        engine is really listening on. Covers the case where a stored/preferred
        port is stale (a listener died after the pref was written) so the UI
        keeps polling a dead port and reports 后端启动失败 forever. */
    fun probeLivePort(start: Int = DEFAULT_ENGINE_PORT, span: Int = MAX_BUMP): Int {
        for (p in start until start + span) {
            try {
                java.net.Socket().use { s ->
                    s.connect(java.net.InetSocketAddress("127.0.0.1", p), 200)
                }
                activePort = p
                return p
            } catch (_: Exception) {
            }
        }
        return activePort
    }
}
