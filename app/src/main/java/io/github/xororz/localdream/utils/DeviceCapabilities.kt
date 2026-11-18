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

    /**
     * SD1.5 QNN NPU (Hexagon) runtime is only useful on arm64-v8a Qualcomm
     * Snapdragon SoCs. On 32-bit ROMs, Kirin/MediaTek/Exynos/Tensor, or when
     * the SoC part is below the QNN lib set we ship, we must never attempt to
     * prepare or launch qnnlibs — instead fall back to CPU/GPU.
     */
    fun hasQnnNpu(): Boolean {
        if (!is64Bit()) return false
        val info = socInfo
        if (info.vendor != SocVendor.QUALCOMM) return false
        val part = info.snapdragonPart ?: return false
        // 官方兼容表：Hexagon V68 或更新（含骁龙 7 Gen 1 / 8s Gen 3 这类
        // 非旗舰）。V68 起点约 SM7450，旧 6 系 v66 会直接崩，不放行。
        return part >= 7350
    }

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

    /**
     * et.245：Build.SOC_MODEL / SOC_MANUFACTURER 是 API 31（Android 12）才有的
     * 静态字段。在 Android 8~11 上哪怕只是"引用"这个字段，ART 解析时会直接抛
     * java.lang.NoSuchFieldError，把界面渲染整个带崩（et.244 真实崩溃栈：
     * detectSoc → vendorLabel → ModelListScreen）。所以一律走反射，
     * 拿不到就返回空串，交给下面的 ro.soc.model 系统属性兜底。
     */
    private fun buildStringField(name: String): String = try {
        if (Build.VERSION.SDK_INT < 31) "" else {
            (Build::class.java.getField(name).get(null) as? String).orEmpty()
        }
    } catch (_: Throwable) {
        ""
    }

    /** 安全的芯片型号读取（任何 API 级别都不会抛）。 */
    fun socModel(): String =
        buildStringField("SOC_MODEL").ifBlank { sysprop("ro.soc.model") }

    fun socManufacturer(): String =
        buildStringField("SOC_MANUFACTURER").ifBlank { sysprop("ro.soc.manufacturer") }

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

    private fun detectSoc(): SocInfo = try {
        detectSocInner()
    } catch (_: Throwable) {
        // 探测芯片属于"锦上添花"，任何 ROM 上的异常都不该让 App 闪退。
        SocInfo(SocVendor.UNKNOWN, null, "")
    }

    private fun detectSocInner(): SocInfo {
        val hay = listOf(
            socModel(),
            socManufacturer(),
            Build.HARDWARE,
            Build.BOARD,
            sysprop("ro.soc.model"),
            sysprop("ro.soc.manufacturer"),
            sysprop("ro.hardware"),
            cpuInfoField("Hardware"),
        ).joinToString(" ") { it.orEmpty() }.lowercase()

        // SM8750 / SM8750-AB / qcom sm8650 ...
        var part = Regex("sm\\D{0,2}(\\d{4})").find(hay)?.groupValues?.get(1)?.toIntOrNull()

        // Honor / OPPO / realme 等 ROM 经常把 Build.SOC_MODEL 留空，只在
        // ro.board.platform 里暴露高通内部代号（pineapple/sun/kalama/taro…）。
        // 之前只认 "sm8650" 这种字符串，这些机器就被误判成“不支持 NPU”。
        if (part == null) {
            val codename = listOf(
                sysprop("ro.board.platform"), Build.BOARD, Build.HARDWARE,
                sysprop("ro.hardware"),
            ).joinToString(" ").lowercase()
            val map = mapOf(
                "taro" to 8450, "kalama" to 8550, "pineapple" to 8650,
                "sun" to 8750, "volcano" to 8850, "cape" to 8550,
                "parrot" to 7450, "shima" to 7325, "yupik" to 7325,
                "holi" to 6375, "blair" to 4450, "khaje" to 6225,
                "bengal" to 6115,
            )
            part = map.entries.firstOrNull { codename.contains(it.key) }?.value
        }

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

    /**
     * et.242: 天玑高端机判定。天玑 9000 起步的 Mali-G710/G715/G720/G925 带
     * 可用的 vendor OpenCL，MNN 的 GPU 后端能在这些机器上真实出图；低端
     * 天玑（Helio/PowerVR）则必须留在 CPU，避免驱动段错误。
     * 平台代号来自 ro.board.platform：mt6983=D9000、mt6985=D9200、
     * mt6989=D9300、mt6991=D9400，mt6895/mt6893 等也按高端处理。
     */
    fun isHighEndMediaTek(context: Context): Boolean {
        if (socInfo.vendor != SocVendor.MEDIATEK) return false
        if (!is64Bit()) return false
        val plat = listOf(
            sysprop("ro.board.platform"), sysprop("ro.soc.model"),
            Build.BOARD, Build.HARDWARE,
        ).joinToString(" ").lowercase()
        val digits = Regex("mt(\\d{4})").find(plat)?.groupValues?.get(1)?.toIntOrNull()
        val highEnd = digits != null && digits >= 6890
        if (!highEnd) return false
        val totalGb = totalRamBytes(context) / (1024.0 * 1024.0 * 1024.0)
        return totalGb >= 7.0
    }

    /**
     * et.244：本机是否可用 Vulkan（MNN 官方开源后端，不需要任何厂商 NDA）。
     * 返回主版本号（1 表示 1.x），0 表示不可用。天玑 9000+ / 骁龙 8+ 的 Mali、
     * Adreno 都暴露 Vulkan 1.1+，个别 ROM 阉割了 OpenCL 但保留 Vulkan。
     */
    fun vulkanMajorVersion(context: Context): Int = runCatching {
        val pm = context.packageManager
        // 用字符串常量而不是 PackageManager.FEATURE_VULKAN，避免不同 compileSdk
        // 的 stub 缺该字段时编译不过。
        if (!pm.hasSystemFeature("android.hardware.vulkan")) return 0
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            // 反射调用 getSystemFeatureVersionInt(name, default)，返回 major*100+minor。
            val m = pm.javaClass.getMethod(
                "getSystemFeatureVersionInt", String::class.java, Int::class.javaPrimitiveType,
            )
            val v = (m.invoke(pm, "android.hardware.vulkan", 0) as? Int) ?: 0
            return if (v > 0) v / 100 else 1
        }
        1
    }.getOrDefault(0)

    fun supportsVulkan(context: Context): Boolean = vulkanMajorVersion(context) >= 1

    /** Localized short label of the detected chip vendor. */
    fun vendorLabel(): String = runCatching { when (socInfo.vendor) {
        SocVendor.QUALCOMM -> "高通骁龙"
        SocVendor.KIRIN -> "华为麒麟 (Hisilicon)"
        SocVendor.MEDIATEK -> "联发科 (MediaTek)"
        SocVendor.SAMSUNG -> "三星 Exynos"
        SocVendor.GOOGLE -> "Google Tensor"
        SocVendor.UNKNOWN -> "本机"
    } }.getOrDefault("本机")
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

    /**
     * 2026-11：PowerVR（vivo/OPPO 天玑低端机常见）的 OpenCL 驱动在 MNN
     * 里初始化就崩，SD1.5 选 GPU 会直接闪退。只对 Adreno/Mali/Xclipse
     * 默认开 GPU；PowerVR 一律先走 CPU，用户仍可在设置里手动开。
     */
    fun openclRecommended(): Boolean {
        val r = glRenderer()?.lowercase().orEmpty()
        if (r.contains("powervr")) return false
        return r.contains("adreno") || r.contains("mali") ||
            r.contains("xclipse") ||
            // Fallback when the renderer can't be read: trust known ARM vendors.
            (r.isBlank() && socInfo.vendor in setOf(
                SocVendor.QUALCOMM, SocVendor.KIRIN,
                SocVendor.MEDIATEK, SocVendor.SAMSUNG,
            ))
    }

    // et.30: detailed OpenCL/GPU probe for diagnostics (not gating). Logs once.
    fun openclProbeReport(): String {
        val r = glRenderer().orEmpty()
        val vendor = when {
            r.contains("adreno", true) -> "Adreno"
            r.contains("mali", true) -> "Mali"
            r.contains("xclipse", true) -> "Xclipse"
            r.contains("powervr", true) -> "PowerVR"
            else -> "unknown"
        }
        val paths = listOf(
            "/system/lib/libOpenCL.so", "/system/lib64/libOpenCL.so",
            "/vendor/lib/libOpenCL.so", "/vendor/lib64/libOpenCL.so",
            "/system/vendor/lib/libOpenCL.so", "/system/vendor/lib64/libOpenCL.so",
            "/system/lib/egl/libGLES_mali.so", "/vendor/lib/egl/libGLES_mali.so",
        )
        val present = paths.filter { java.io.File(it).exists() }
        return "GL_RENDERER=$r vendor=$vendor openclPathsPresent=${present.joinToString(",")}"
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
     * et.42: count "big" CPU cores by reading each online cpu's max frequency from
     * sysfs, and taking those at >=95% of the highest max freq found. We do NOT
     * hardcode cpu0-3 — topology varies; on the test device cpu0-3@2.3GHz are the
     * big cores and cpu4-7@1.8GHz the LITTLE ones. Falls back to a safe 4 when the
     * sysfs values are unreadable. Used to set RAYON_NUM_THREADS (the engine is a
     * rayon binary and ignores OMP_NUM_THREADS), avoiding 8-thread big.LITTLE thrash.
     */
    fun bigCoreCount(): Int {
        return runCatching {
            val freqs = mutableListOf<Int>()
            var cpu = 0
            while (cpu < 64) {
                // et.243：标准节点是 cpuinfo_max_freq，联发科/部分厂商只暴露
                // 旧的 cpu_max_freq。之前只读后者，高通/三星/麒麟机型全部读空，
                // bigCoreCount 退回默认 4 线程，8 核旗舰也只跑 4 核 —— 这正是
                // “高配手机生成慢/卡顿”的一个真实原因。
                val f = runCatching {
                    java.io.File("/sys/devices/system/cpu/cpu$cpu/cpufreq/cpuinfo_max_freq")
                        .readText().trim().toIntOrNull()
                }.getOrNull() ?: runCatching {
                    java.io.File("/sys/devices/system/cpu/cpu$cpu/cpufreq/cpu_max_freq")
                        .readText().trim().toIntOrNull()
                }.getOrNull()
                if (f != null && f in 100_000..8_000_000) freqs.add(f)
                cpu++
            }
            if (freqs.size < 2) return@runCatching 4
            val maxF = freqs.maxOrNull() ?: return@runCatching 4
            val big = freqs.count { it >= (maxF * 0.90).toInt() }
            // Sanity: at least 2, never more than half+2 of cores, leave headroom for UI.
            big.coerceAtLeast(2).coerceAtMost((Runtime.getRuntime().availableProcessors() / 2 + 2).coerceAtLeast(4))
        }.getOrDefault(4)
    }

    /**
     * SD1.5 long-edge ceiling adapts to total RAM (64-aligned):
     * ≤6 GB → 512; ≤10 GB → 640; >10 GB → 768. Kept conservative so the
     * MNN fp16 UNet does not OOM on mid-range phones.
     */
    /**
     * et.41: extremely constrained 32-bit device (armeabi-v7a, ~2-3GB RAM, no
     * fp16/dotprod like the PowerVR Rogue GE8320). Even 384 long edge gets SIGKILL'd
     * mid-diffusion (code 137), so we fall to the smallest stable SD1.5 canvas.
     */
    fun extremeLowRam(context: Context): Boolean {
        if (is64Bit()) return false
        val total = totalRamBytes(context)
        return total <= 3500L * 1024 * 1024 // ~3.4GB
    }

    fun sd15LongEdgeForRam(context: Context): Int {
        // et.41: worst tier first — 256 long edge (a multiple of 8, engine-accepted).
        if (extremeLowRam(context)) return 256
        // et.30: 32-bit (armeabi-v7a) address space is ~2-3GB; 512 on a 32-bit
        // device triggers SIGKILL (exit 137) mid-diffusion. Cap 32-bit to 384.
        if (!is64Bit()) return 384
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        // et.39: low-RAM / isLowRamDevice devices default to the safe 384 long edge
        // (and batch is forced to 1 elsewhere) to avoid code=137 under memory pressure.
        if (am.isLowRamDevice) return 384
        val total = totalRamBytes(context)
        val gb = total / (1024.0 * 1024.0 * 1024.0)
        val ramBased = when {
            gb <= 4.0 -> 384
            gb <= 6.0 -> 512
            gb <= 10.0 -> 640
            else -> 768
        }
        // 用户在设置里选的引擎档位优先；AUTO 时仍按内存自适应。
        val mode = EngineProfile.selected(context)
        return if (mode == EngineProfile.Mode.AUTO) {
            ramBased
        } else {
            minOf(ramBased, EngineProfile.longEdgeCap(context))
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
        // DiT + 8B 文本编码器实际要吃 12GB 以上；只看 total 会放过 12GB 机器，
        // 一生成就被 LMK/lowmemorykiller 杀掉（表现为“每次生成都闪退”）。
        if (isDit) {
            val total = totalRamBytes(context)
            val avail = availRamBytes(context)
            if (total < ramRequiredBytes) return REASON_RAM
            if (avail > 0 && avail < ramRequiredBytes * 0.70) return REASON_RAM
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
