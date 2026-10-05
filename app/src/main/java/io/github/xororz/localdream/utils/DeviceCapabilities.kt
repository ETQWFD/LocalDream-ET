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

    // ---- Robust SoC / GPU identification ---------------------------------
    // Build.SOC_MODEL alone is unreliable: some ROMs (incl. certain Huawei /
    // custom Android 16 builds) leave it blank, and users then see a generic
    // "unsupported" message. We combine Build fields, hidden system properties
    // and /proc/cpuinfo, and read the GL renderer through a tiny EGL context.
    enum class SocVendor { QUALCOMM, KIRIN, MEDIATEK, SAMSUNG, GOOGLE, UNKNOWN }

    data class SocInfo(
        val vendor: SocVendor,
        /** Snapdragon SM part number, e.g. 8750 for SM8750, or null. */
        val snapdragonPart: Int?,
        val raw: String,
    )

    @Volatile
    private var socCache: SocInfo? = null
    val socInfo: SocInfo
        get() = socCache ?: detectSoc().also { socCache = it }

    private fun sysprop(name: String): String = try {
        val clz = Class.forName("android.os.SystemProperties")
        val m = clz.getMethod("get", String::class.java)
        (m.invoke(null, name) as? String).orEmpty()
    } catch (_: Throwable) {
        ""
    }

    private fun cpuInfoField(key: String): String = try {
        java.io.File("/proc/cpuinfo").bufferedReader().useLines { seq ->
            seq.mapNotNull { line ->
                val p = line.split(":", limit = 2)
                if (p.size == 2 && p[0].trim().equals(key, ignoreCase = true)) {
                    p[1].trim().takeIf { it.isNotBlank() }
                } else null
            }.firstOrNull()
        }.orEmpty()
    } catch (_: Throwable) {
        ""
    }

    private fun detectSoc(): SocInfo {
        val hay = listOf(
            Build.SOC_MODEL,
            Build.SOC_MANUFACTURER,
            Build.HARDWARE,
            Build.BOARD,
            sysprop("ro.soc.model"),
            sysprop("ro.soc.manufacturer"),
            sysprop("ro.hardware"),
            cpuInfoField("Hardware"),
        ).joinToString(" ") { it.orEmpty() }.lowercase()

        // SM8750 / SM8750-AB / qcom sm8650 ...
        val part = Regex("sm\\D{0,2}(\\d{4})").find(hay)?.groupValues?.get(1)?.toIntOrNull()

        val vendor = when {
            hay.contains("kirin") || hay.contains("hisilicon") ||
                hay.contains("hisi") || hay.contains("麒麟") -> SocVendor.KIRIN
            part != null || hay.contains("qualcomm") || hay.contains("qcom") ||
                hay.contains("snapdragon") || hay.contains("adreno") -> SocVendor.QUALCOMM
            hay.contains("mediatek") || hay.contains("dimensity") ||
                hay.contains("helio") || hay.contains("mt6") || hay.contains("mt8") ->
                SocVendor.MEDIATEK
            hay.contains("exynos") || hay.contains("s5e") -> SocVendor.SAMSUNG
            hay.contains("tensor") || hay.contains("zuma") ||
                hay.contains("gs101") || hay.contains("gs201") -> SocVendor.GOOGLE
            else -> SocVendor.UNKNOWN
        }
        return SocInfo(vendor, part, hay.ifBlank { "unknown" })
    }

    fun snapdragonPart(): Int? = socInfo.snapdragonPart

    /** Localized short label of the detected chip vendor. */
    fun vendorLabel(): String = when (socInfo.vendor) {
        SocVendor.QUALCOMM -> "高通骁龙"
        SocVendor.KIRIN -> "华为麒麟 (Hisilicon)"
        SocVendor.MEDIATEK -> "联发科 (MediaTek)"
        SocVendor.SAMSUNG -> "三星 Exynos"
        SocVendor.GOOGLE -> "Google Tensor"
        SocVendor.UNKNOWN -> "本机"
    }

    /**
     * Whether the device very likely exposes a usable OpenCL implementation for
     * the MNN GPU backend. Read from the GL renderer; the major mobile GPUs
     * (Adreno / Mali / Xclipse / PowerVR) all ship vendor OpenCL. MNN safely
     * falls back to CPU if dlopen(libOpenCL) fails at runtime.
     */
    @Volatile
    private var rendererCache: String? = null
    @Volatile
    private var rendererProbed = false

    @Suppress("DEPRECATION")
    fun glRenderer(): String? {
        if (rendererProbed) return rendererCache
        rendererProbed = true
        rendererCache = try {
            val dpy = (android.opengl.EGL14.eglGetDisplay(android.opengl.EGL14.EGL_DEFAULT_DISPLAY))
                ?: return null
            val versions = IntArray(2)
            if (!android.opengl.EGL14.eglInitialize(dpy, versions, 0, versions, 1)) return null
            val attr = intArrayOf(
                android.opengl.EGL14.EGL_SURFACE_TYPE, android.opengl.EGL14.EGL_PBUFFER_BIT,
                android.opengl.EGL14.EGL_RENDERABLE_TYPE, 0x0004, // EGL_OPENGL_ES2_BIT
                android.opengl.EGL14.EGL_NONE,
            )
            val configs: Array<android.opengl.EGLConfig?> = arrayOfNulls(1)
            val num = IntArray(1)
            if (!android.opengl.EGL14.eglChooseConfig(dpy, attr, 0, configs, 0, 1, num, 0) ||
                num[0] == 0
            ) {
                android.opengl.EGL14.eglTerminate(dpy); return null
            }
            val pbuffer = android.opengl.EGL14.eglCreatePbufferSurface(
                dpy, configs[0], intArrayOf(
                    android.opengl.EGL14.EGL_WIDTH, 1,
                    android.opengl.EGL14.EGL_HEIGHT, 1,
                    android.opengl.EGL14.EGL_NONE,
                ), 0,
            )
            val cattr = intArrayOf(0x3098, 2, android.opengl.EGL14.EGL_NONE) // EGL_CONTEXT_CLIENT_VERSION
            val ctx = android.opengl.EGL14.eglCreateContext(
                dpy, configs[0], android.opengl.EGL14.EGL_NO_CONTEXT, cattr, 0,
            )
            if (ctx == android.opengl.EGL14.EGL_NO_CONTEXT) {
                android.opengl.EGL14.eglDestroySurface(dpy, pbuffer)
                android.opengl.EGL14.eglTerminate(dpy); return null
            }
            android.opengl.EGL14.eglMakeCurrent(dpy, pbuffer, pbuffer, ctx)
            val r = android.opengl.GLES20.glGetString(android.opengl.GLES20.GL_RENDERER)
            android.opengl.EGL14.eglMakeCurrent(
                dpy, android.opengl.EGL14.EGL_NO_SURFACE,
                android.opengl.EGL14.EGL_NO_SURFACE, android.opengl.EGL14.EGL_NO_CONTEXT,
            )
            android.opengl.EGL14.eglDestroyContext(dpy, ctx)
            android.opengl.EGL14.eglDestroySurface(dpy, pbuffer)
            android.opengl.EGL14.eglTerminate(dpy)
            r
        } catch (_: Throwable) {
            null
        }
        return rendererCache
    }

    /** Default the SD1.5 GPU (OpenCL) toggle on for phones with a known mobile GPU. */
    fun openclRecommended(): Boolean {
        val r = glRenderer()?.lowercase().orEmpty()
        return r.contains("adreno") || r.contains("mali") ||
            r.contains("xclipse") || r.contains("powervr") ||
            // Fallback when the renderer can't be read: trust known ARM vendors.
            (r.isBlank() && socInfo.vendor in setOf(
                SocVendor.QUALCOMM, SocVendor.KIRIN,
                SocVendor.MEDIATEK, SocVendor.SAMSUNG,
            ))
    }

    /** Big DiT (Z-Image / FLUX.2 Klein / Qwen Image 2.1): arm64 + elite NPU. */
    fun canRunDit(): Boolean {
        if (!is64Bit()) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        if (socInfo.vendor != SocVendor.QUALCOMM) return false
        return (snapdragonPart() ?: 0) >= 8750
    }

    /** SDXL over QNN NPU: arm64 and an allowlisted recent Snapdragon. */
    fun canRunSdxlNpu(): Boolean {
        if (!is64Bit()) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        if (socInfo.vendor != SocVendor.QUALCOMM) return false
        // SDXL QNN path validated on 8 Gen 3 (SM8650) and newer.
        return (snapdragonPart() ?: 0) >= 8650
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
     * SD1.5 long-edge ceiling adapts to total RAM (64-aligned):
     * ≤6 GB → 512; ≤10 GB → 640; >10 GB → 768. Kept conservative so the
     * MNN fp16 UNet does not OOM on mid-range phones.
     */
    fun sd15LongEdgeForRam(context: Context): Int {
        val total = totalRamBytes(context)
        val gb = total / (1024.0 * 1024.0 * 1024.0)
        return when {
            gb <= 6.0 -> 512
            gb <= 10.0 -> 640
            else -> 768
        }
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
        if (isDit || isSdxl) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return REASON_ANDROID
            // The flagship NPU/DiT/SDXL stack is built only on Qualcomm Adreno
            // (QNN). Give Kirin/MediaTek/Exynos users a precise vendor message
            // instead of a generic "model too new" one.
            if (socInfo.vendor != SocVendor.QUALCOMM) return REASON_NPU_VENDOR
            if (isDit && !canRunDit()) return REASON_NPU_DIT
            if (isSdxl && !canRunSdxlNpu()) return REASON_NPU_SDXL
        }
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
    const val REASON_NPU_VENDOR = "npu_vendor"
    const val REASON_RAM = "ram"
    const val REASON_STORAGE = "storage"
}
