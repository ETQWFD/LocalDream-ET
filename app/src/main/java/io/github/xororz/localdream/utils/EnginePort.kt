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
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt("active_engine_port", resolved).apply()
        return resolved
    }

    fun activeEnginePort(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt("active_engine_port", configuredEnginePort(context))
}
