package io.github.xororz.localdream.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import androidx.core.content.FileProvider
import io.github.xororz.localdream.data.Model
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Bundles a human-readable diagnostic report for offline bug reports.
 *
 * Every section is wrapped in runCatching: a failing collector writes
 * "unavailable: <reason>" instead of crashing the whole export. The report is
 * written to the public LocalDreamET folder and shared via FileProvider.
 */
object DiagnosticsExporter {

    /** URLs probed for reachability in the report (final host / code / size). */
    private val PROBE_URLS = listOf(
        "https://huggingface.co/xororz/sd-mnn/resolve/main/AnythingV5.zip",
        "https://hf-mirror.com/xororz/sd-mnn/resolve/main/AnythingV5.zip",
    )

    data class Result(val file: File, val error: String? = null)

    fun build(context: Context): Result {
        val sb = StringBuilder()
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

        sb.appendLine("==== LocalDream ET Diagnostic Report ====")
        sb.appendLine("generated: ${Date()}")
        appendAppInfo(context, sb)
        appendDeviceInfo(context, sb)
        appendModelsListing(context, sb)
        appendEngineLogs(context, sb)
        appendConvertLogs(context, sb)
        appendLogcat(sb)
        appendSourceProbe(sb)

        return runCatching {
            val out = File(Storage.publicRoot().apply { mkdirs() }, "diagnostics_$ts.txt")
            out.writeText(sb.toString())
            Result(out)
        }.getOrElse {
            // fallback to app-private
            runCatching {
                val out = File(File(context.filesDir, Storage.DIR_NAME).apply { mkdirs() }, "diagnostics_$ts.txt")
                out.writeText(sb.toString())
                Result(out)
            }.getOrElse { Result(File(""), it.message ?: "unknown") }
        }
    }

    fun shareIntent(context: Context, file: File): Intent {
        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    private fun appendAppInfo(context: Context, sb: StringBuilder) = runCatching {
        sb.appendLine("\n---- App ----")
        val pm = context.packageManager
        val info = pm.getPackageInfo(context.packageName, 0)
        sb.appendLine("package: ${context.packageName}")
        sb.appendLine("versionName: ${info.versionName}")
        val vc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION") info.versionCode.toLong()
        }
        sb.appendLine("versionCode: $vc")
    }.getOrElse { sb.appendLine("App: unavailable: ${it.message}") }

    private fun appendDeviceInfo(context: Context, sb: StringBuilder) = runCatching {
        sb.appendLine("\n---- Device ----")
        sb.appendLine("manufacturer: ${Build.MANUFACTURER}")
        sb.appendLine("model: ${Build.MODEL}")
        sb.appendLine("device: ${Build.DEVICE}")
        sb.appendLine("supportedAbis: ${Build.SUPPORTED_ABIS.joinToString(",")}")
        sb.appendLine("androidRelease: ${Build.VERSION.RELEASE} (sdk ${Build.VERSION.SDK_INT})")
        val rt = Runtime.getRuntime()
        sb.appendLine("memTotalMb: ${rt.totalMemory() / (1024 * 1024)}  memFreeMb: ${rt.freeMemory() / (1024 * 1024)}  memMaxMb: ${rt.maxMemory() / (1024 * 1024)}")
        // et.30: system memory + PSS + engine child peak.
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val mi = android.app.ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        sb.appendLine("sysAvailMb: ${mi.availMem / (1024 * 1024)}  sysTotalMb: ${mi.totalMem / (1024 * 1024)}  thresholdMb: ${mi.threshold / (1024 * 1024)}  lowMemory: ${mi.lowMemory}")
        val pss = android.os.Debug.MemoryInfo()
        android.os.Debug.getMemoryInfo(pss)
        sb.appendLine("pssTotalKb: ${pss.totalPss}  nativePssKb: ${pss.nativePss}  dalvikPssKb: ${pss.dalvikPss}")
        sb.appendLine("cpuCores: ${Runtime.getRuntime().availableProcessors()}  engineThreadsPerSession: 4")
        sb.appendLine("engineVmRssKb: ${EngineMemoryStats.lastVmRssKb}  engineVmHwmKb: ${EngineMemoryStats.lastVmHwmKb}  engineThreads: ${EngineMemoryStats.lastThreads}")
        // et.30: OpenCL/GPU probe for diagnostics.
        sb.appendLine("openclProbe: ${DeviceCapabilities.openclProbeReport()}")
    }.getOrElse { sb.appendLine("Device: unavailable: ${it.message}") }

    private fun appendModelsListing(context: Context, sb: StringBuilder) = runCatching {
        sb.appendLine("\n---- Models (files, sizes, markers) ----")
        val root = Model.getModelsDir(context)
        val dirs = root.listFiles()?.filter { it.isDirectory } ?: emptyList()
        if (dirs.isEmpty()) sb.appendLine("(no model dirs under $root)")
        for (d in dirs) {
            sb.appendLine("== ${d.name} ==")
            sb.appendLine("  finished: ${File(d, "finished").exists()}  ready: ${File(d, "ready").exists()}")
            sb.appendLine("  sd15OutputsOk: ${Model.hasConvertedSd15Outputs(d)}")
            d.listFiles()?.forEach { f ->
                sb.appendLine("    ${f.name}  ${f.length()} bytes")
            }
        }
    }.getOrElse { sb.appendLine("Models: unavailable: ${it.message}") }

    private fun appendEngineLogs(context: Context, sb: StringBuilder) = runCatching {
        sb.appendLine("\n---- Engine logs (filesDir engine_*.log, last 60 lines each) ----")
        val logs = File(context.filesDir, ".").listFiles { f -> f.name.startsWith("engine_") && f.name.endsWith(".log") }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()
        if (logs.isEmpty()) sb.appendLine("(none yet)")
        for (f in logs.take(5)) {
            sb.appendLine("== ${f.name} (${f.length()} bytes) ==")
            val lines = f.readLines()
            lines.takeLast(60).forEach { sb.appendLine("  $it") }
        }
    }.getOrElse { sb.appendLine("Engine logs: unavailable: ${it.message}") }

    private fun appendConvertLogs(context: Context, sb: StringBuilder) = runCatching {
        sb.appendLine("\n---- Convert logs (temp_dir convert_*.log, last 40 lines each) ----")
        val dir = Storage.tempDir(context)
        val logs = dir.listFiles { f -> f.name.startsWith("convert_") && f.name.endsWith(".log") }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()
        if (logs.isEmpty()) sb.appendLine("(none)")
        for (f in logs.take(5)) {
            sb.appendLine("== ${f.name} ==")
            f.readLines().takeLast(40).forEach { sb.appendLine("  $it") }
        }
    }.getOrElse { sb.appendLine("Convert logs: unavailable: ${it.message}") }

    private fun appendLogcat(sb: StringBuilder) = runCatching {
        sb.appendLine("\n---- App logcat (this pid, buffered) ----")
        val snap = LogCapture.snapshot()
        if (snap.isBlank()) sb.appendLine("(buffer empty)")
        else snap.lineSequence().toList().takeLast(200).forEach { sb.appendLine("  $it") }
    }.getOrElse { sb.appendLine("Logcat: unavailable: ${it.message}") }

    private fun appendSourceProbe(sb: StringBuilder) = runCatching {
        sb.appendLine("\n---- Download source probe (HEAD / small Range, short timeout) ----")
        for (u in PROBE_URLS) {
            runCatching {
                val conn = (URL(u).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 6000
                    readTimeout = 6000
                    setRequestProperty("Range", "bytes=0-0")
                    instanceFollowRedirects = true
                    connect()
                }
                val code = conn.responseCode
                val ct = conn.contentType
                val clen = conn.getHeaderField("Content-Length")
                val host = conn.url.host
                sb.appendLine("  $u")
                sb.appendLine("    -> $code  host=$host  type=$ct  contentLength=$clen")
                conn.disconnect()
            }.getOrElse { sb.appendLine("  $u\n    -> error: ${it.message}") }
        }
    }.getOrElse { sb.appendLine("Source probe: unavailable: ${it.message}") }
}
