package io.github.xororz.localdream.utils

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import io.github.xororz.localdream.data.DitEngine

/**
 * Central place for "can this phone actually run this model" decisions.
 *
 * The APK ships two native stacks:
 *   - SD 1.5 engine (libstable_diffusion_core.so) in BOTH arm64-v8a and
 *     armeabi-v7a  -> runs on every device, including 32-bit-only phones.
 *   - large DiT / SDXL-QNN engine (libdit_engine.so, QNN .so) in arm64-v8a
 *     ONLY, and the DiT path additionally needs a Snapdragon 8 Elite-class
 *     SoC (SM8750+) and Android 12+.
 *
 * A 32-bit ROM on an otherwise capable SoC still cannot load the arm64-only
 * engine, so ABI is checked explicitly rather than trusting the SoC string.
 */
object DeviceCapabilities {

    /** True when this process can load arm64-v8a native libraries. */
    fun is64Bit(): Boolean =
        Build.SUPPORTED_ABIS?.any { it.equals("arm64-v8a", ignoreCase = true) } == true

    /** Big DiT (Z-Image / FLUX.2 Klein / Qwen Image 2.1): arm64 + elite NPU. */
    fun canRunDit(): Boolean = is64Bit() && DitEngine.isSupportedDevice()

    /** SDXL over QNN NPU: arm64 and an allowlisted recent Snapdragon. */
    fun canRunSdxlNpu(): Boolean {
        if (!is64Bit()) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        val soc = Build.SOC_MODEL.uppercase()
        if (!soc.startsWith("SM")) return false
        val part = soc.dropWhile { !it.isDigit() }.takeWhile { it.isDigit() }.toIntOrNull()
            ?: return false
        // SDXL QNN path validated on 8 Gen 3 (SM8650) and newer.
        return part >= 8650
    }

    fun totalRamBytes(context: Context): Long = try {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.totalMem
    } catch (_: Exception) {
        0L
    }

    fun availRamBytes(context: Context): Long = try {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.availMem
    } catch (_: Exception) {
        0L
    }

    /**
     * Whether a model entry should even be listed on this device.
     * 32-bit devices see only the cross-ABI SD1.5 CPU/GPU models; 64-bit
     * devices see everything their SoC can drive.
     */
    fun isListable(
        isDit: Boolean,
        runOnCpu: Boolean,
        isSdxlNpu: Boolean,
    ): Boolean {
        if (runOnCpu) return true
        if (isDit) return canRunDit()
        if (isSdxlNpu) return canRunSdxlNpu()
        // Non-CPU, non-DiT, non-SDXL = classic SD1.5 NPU (QNN, arm64 only).
        return is64Bit()
    }
}
