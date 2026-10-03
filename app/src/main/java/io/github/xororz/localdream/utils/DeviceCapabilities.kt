package io.github.xororz.localdream.utils

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.StatFs
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

    /** Free bytes on the shared external volume where models are stored. */
    fun freeStorageBytes(context: Context): Long = try {
        val root = runCatching { Storage.root(context) }.getOrNull()
        val path = root?.takeIf { it.exists() }?.absolutePath
            ?: Environment.getExternalStorageDirectory().absolutePath
        StatFs(path).availableBytes
    } catch (_: Exception) {
        0L
    }

    /**
     * Whether a model entry should even be listed on this device.
     *
     * et.18: every catalog model is LISTED on every device, including on
     * 32-bit phones. Models the device cannot actually run are rendered with a
     * lock and their download is blocked with a precise reason
     * ([gateReason]), so users can see what exists and what a better device
     * would unlock, instead of the tab silently looking empty/broken.
     */
    fun isListable(
        isDit: Boolean,
        runOnCpu: Boolean,
        isSdxlNpu: Boolean,
    ): Boolean = true

    /**
     * Why a non-CPU model cannot run/download on this device, or null when it
     * is usable. Order: ABI first (32-bit cannot even load the arm64 engine),
     * then NPU/SoC generation, Android version, RAM, then free storage.
     *
     * Returns a machine reason string consumed by the UI's localized message.
     */
    fun gateReason(
        context: Context,
        isDit: Boolean,
        isSdxl: Boolean,
        ramRequiredBytes: Long,
        storageRequiredBytes: Long,
    ): String? {
        if (!is64Bit()) return REASON_ABI
        if (isDit) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return REASON_ANDROID
            if (!DitEngine.isSupportedDevice()) return REASON_NPU_DIT
        }
        if (isSdxl && !canRunSdxlNpu()) return REASON_NPU_SDXL
        if (ramRequiredBytes > 0 && totalRamBytes(context) < ramRequiredBytes) return REASON_RAM
        if (storageRequiredBytes > 0 &&
            freeStorageBytes(context) < storageRequiredBytes
        ) return REASON_STORAGE
        return null
    }

    const val REASON_ABI = "abi"
    const val REASON_ANDROID = "android"
    const val REASON_NPU_DIT = "npu_dit"
    const val REASON_NPU_SDXL = "npu_sdxl"
    const val REASON_RAM = "ram"
    const val REASON_STORAGE = "storage"
}
