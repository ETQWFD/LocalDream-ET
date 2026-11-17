package io.github.xororz.localdream.utils

import android.content.Context
import android.content.SharedPreferences

/**
 * 三档引擎配置（底层仍是同一套 MNN/QNN 原生库，按机型切换运行参数）：
 *  - COMPAT     低配：强制 CPU，长边 ≤384，关 OpenCL，起步步数少；
 *  - BALANCED   中配：CPU/OpenCL 可选，长边 ≤512；
 *  - PERFORMANCE 高配：GPU 优先，长边 ≤768。
 * AUTO 由设备自行选择，用户也可以在设置里强制指定。
 */
object EngineProfile {

    const val PREF = "engine_profile"

    enum class Mode(val key: String, val label: String) {
        AUTO("auto", "自动"),
        COMPAT("compat", "低配引擎"),
        BALANCED("balanced", "中配引擎"),
        PERFORMANCE("performance", "高配引擎"),
    }

    fun selected(context: Context): Mode {
        val raw = prefs(context).getString(PREF, Mode.AUTO.key)
        return Mode.entries.firstOrNull { it.key == raw } ?: Mode.AUTO
    }

    fun set(context: Context, mode: Mode) {
        prefs(context).edit().putString(PREF, mode.key).apply()
    }

    /** Auto: 32-bit / ≤4GB → COMPAT；≥12GB 且 64 位 → PERFORMANCE；其余 BALANCED。 */
    fun effective(context: Context): Mode {
        val chosen = selected(context)
        if (chosen != Mode.AUTO) return chosen
        if (!DeviceCapabilities.is64Bit()) return Mode.COMPAT
        val totalGb = DeviceCapabilities.totalRamBytes(context) / (1024.0 * 1024.0 * 1024.0)
        return when {
            totalGb <= 4.0 -> Mode.COMPAT
            totalGb >= 12.0 -> Mode.PERFORMANCE
            else -> Mode.BALANCED
        }
    }

    /** Long-edge ceiling for SD1.5 CPU/GPU generation. */
    fun longEdgeCap(context: Context): Int = when (effective(context)) {
        Mode.COMPAT -> 384
        Mode.BALANCED -> 512
        Mode.PERFORMANCE -> 768
        Mode.AUTO -> 512
    }

    /** Suggested UI speed tier (fast / balanced / quality). */
    fun speedTier(context: Context): String = when (effective(context)) {
        Mode.COMPAT -> "fast"
        Mode.BALANCED -> "balanced"
        Mode.PERFORMANCE -> "quality"
        Mode.AUTO -> "balanced"
    }

    fun allowOpenCl(context: Context): Boolean =
        effective(context) != Mode.COMPAT && DeviceCapabilities.openclRecommended()

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
}
