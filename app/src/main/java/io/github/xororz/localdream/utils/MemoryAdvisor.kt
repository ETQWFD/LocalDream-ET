package io.github.xororz.localdream.utils

import android.app.ActivityManager
import android.content.Context
import android.os.Build

/**
 * et.30: pre-generation memory advisory. Non-blocking: it only decides whether to
 * show a Chinese warning dialog; it never silently changes parameters or blocks.
 *
 * Threshold rationale: a 512/640/768 SD1.5 CPU session plus the app's own heap
 * needs roughly 1.2-1.5GB of headroom on low-RAM devices. If availMem is below
 * the tier threshold we warn the user they may get jank or an OOM failure.
 */
object MemoryAdvisor {

    data class Advice(
        val shouldWarn: Boolean,
        val availMb: Long,
        val totalMb: Long,
        val longEdge: Int,
    )

    fun assess(context: Context, longEdge: Int, backend: String): Advice {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        val availMb = info.availMem / (1024 * 1024)
        val totalMb = info.totalMem / (1024 * 1024)
        // CPU high-resolution needs more headroom; NPU keeps weights off CPU.
        val thresholdMb = when {
            backend.equals("sd15npu", true) -> 600
            longEdge >= 768 -> 1500
            longEdge >= 640 -> 1200
            else -> 1000
        }
        // et.30: 32-bit address space is ~2-3GB; long edge >384 risks SIGKILL.
        val is32Bit = !DeviceCapabilities.is64Bit()
        val shouldWarn = availMb < thresholdMb || (is32Bit && longEdge > 384)
        return Advice(
            shouldWarn = shouldWarn,
            availMb = availMb,
            totalMb = totalMb,
            longEdge = longEdge,
        )
    }
}
