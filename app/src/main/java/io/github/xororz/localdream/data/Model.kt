package io.github.xororz.localdream.data

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.xororz.localdream.R
import io.github.xororz.localdream.service.ModelDownloadService
import java.io.File
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@Immutable
data class Resolution(val width: Int, val height: Int) {
    val isSquare: Boolean get() = width == height

    override fun toString(): String = if (isSquare) {
        "$width×$width"
    } else {
        "$width×$height"
    }
}

object PatchScanner {
    private val squarePatchPattern = Regex("""^(\d+)\.patch$""")
    private val rectangularPatchPattern = Regex("""^(\d+)x(\d+)\.patch$""")

    fun scanAvailableResolutions(context: Context, modelId: String): List<Resolution> {
        val modelDir = File(Model.getModelsDir(context), modelId)
        if (!modelDir.exists() || !modelDir.isDirectory) {
            return emptyList()
        }

        val resolutions = mutableListOf<Resolution>()

        modelDir.listFiles()?.forEach { file ->
            if (!file.isFile) return@forEach

            squarePatchPattern.matchEntire(file.name)?.let { match ->
                val size = match.groupValues[1].toIntOrNull()
                if (size != null && size > 0) {
                    resolutions.add(Resolution(size, size))
                }
            }

            rectangularPatchPattern.matchEntire(file.name)?.let { match ->
                val width = match.groupValues[1].toIntOrNull()
                val height = match.groupValues[2].toIntOrNull()
                if (width != null && height != null && width > 0 && height > 0) {
                    resolutions.add(Resolution(width, height))
                }
            }
        }

        return resolutions.distinct().sortedBy { it.width * it.height }
    }
}

private fun getDeviceSoc(): String = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
    Build.SOC_MODEL
} else {
    "CPU"
}

@Immutable
data class DownloadProgress(val progress: Float, val downloadedBytes: Long, val totalBytes: Long)

val chipsetModelSuffixes = mapOf(
    "SM8475" to "8gen1",
    "SM8450" to "8gen1",
    "SM8550" to "8gen2",
    "SM8550P" to "8gen2",
    "QCS8550" to "8gen2",
    "QCM8550" to "8gen2",
    "SM8650" to "8gen2",
    "SM8650P" to "8gen2",
    "SM8750" to "8gen2",
    "SM8750P" to "8gen2",
    "SM8850" to "8gen2",
    "SM8850P" to "8gen2",
    "SM8735" to "8gen2",
    "SM8845" to "8gen2",
)

sealed class DownloadResult {
    data object Success : DownloadResult()
    data class Error(val message: String) : DownloadResult()
    data class Progress(val progress: DownloadProgress) : DownloadResult()
}

sealed class RenameResult {
    data object Success : RenameResult()

    // Caller decides messaging; BlankName/Reserved/Exists are recoverable input
    // errors, Io means the on-disk move failed.
    enum class Reason { BlankName, Reserved, Exists, Io }
    data class Error(val reason: Reason) : RenameResult()
}

@Immutable
data class Model(
    val id: String,
    val name: String,
    val description: String,
    val baseUrl: String,
    val fileUri: String = "",
    // When non-empty, the app downloads this raw SD1.5 safetensors (path after
    // the HF host; mirror/official host chosen at download time) and runs the
    // on-device converter once. Lets extra SD1.5 models ship without a
    // prebuilt MNN zip. Empty for normal prebuilt/single-file models.
    val convertSourceUrl: String = "",
    val generationSize: Int = 512,
    val approximateSize: String = "1GB",
    val isDownloaded: Boolean = false,
    // Defaults written in code for this model; only the fields it cares about.
    val codeDefaults: ModelConfig = ModelConfig(),
    // Defaults read from config.json in the model directory, if present.
    val configDefaults: ModelConfig = ModelConfig(),
    val runOnCpu: Boolean = false,
    val isCustom: Boolean = false,
    val isSdxl: Boolean = false,
    val isAnima: Boolean = false,
    // DiT packages run by libdit_engine.so: "zimage", "klein" or "qwen21".
    val ditKind: String = "",
    // Files that make up a package downloaded file-by-file rather than as one
    // zip, as "<path under baseUrl>|<name on disk>" pairs. Used by the DiT
    // packages: they are too large to unpack from an archive on device, and
    // their parts are pulled straight from the repositories that publish them
    // instead of being rehosted.
    val packageFiles: List<String> = emptyList(),
    // Minimum RAM (bytes) the device should reasonably have free before we let
    // the user download a huge package. 0 = no RAM gate (SD1.5 models).
    val ramRequiredBytes: Long = 0L,
    // Free storage (bytes) that must remain on the model volume before starting
    // a (huge) download, so it cannot run out of disk half-way. 0 = no gate.
    val storageRequiredBytes: Long = 0L,
) {
    val isDit: Boolean get() = ditKind.isNotEmpty()

    // Per-field priority: code defaults > config.json > global defaults.
    val defaults: GenerationDefaults
        get() = codeDefaults.withFallback(configDefaults).resolve()

    // SDXL and Anima both render on a fixed 1024 canvas and reach non-1:1
    // outputs via aspect-ratio inpaint padding; the run screen treats them
    // alike for default size and aspect-ratio handling (ultrafix stays
    // SDXL-only). SD1.5 NPU/CPU use their own sizes / resolution patches.
    val usesFixedCanvas: Boolean
        get() = isSdxl || isAnima

    // These DiT models use RoPE, so the run screen uses
    // independent width/height controls instead of a fixed canvas plus padding.
    val supportsFreeResolution: Boolean
        get() = isDit

    // Backend --type value; each type implies the full model file layout.
    val backendType: String
        get() = when {
            isDit -> ditKind
            isAnima -> "anima"
            isSdxl -> if (runOnCpu) "sdxlmnn" else "sdxl"
            runOnCpu -> "sd15cpu"
            else -> "sd15npu"
        }

    fun startDownload(context: Context) {
        // Raw checkpoint that is converted on device: handled by the foreground
        // convert-download path (resumable download + native conversion).
        if (convertSourceUrl.isNotEmpty()) {
            val intent = Intent(context, ModelDownloadService::class.java).apply {
                action = ModelDownloadService.ACTION_START_DOWNLOAD
                putExtra(ModelDownloadService.EXTRA_MODEL_ID, id)
                putExtra(ModelDownloadService.EXTRA_MODEL_NAME, name)
                putExtra(
                    ModelDownloadService.EXTRA_MODEL_TYPE,
                    ModelDownloadService.TYPE_CONVERT_SD,
                )
                putExtra(ModelDownloadService.EXTRA_CONVERT_PATH, convertSourceUrl)
                putExtra(ModelDownloadService.EXTRA_CLIP_SKIP, 1)
            }
            context.startForegroundService(intent)
            return
        }

        // A multi-file package carries its sources in packageFiles instead.
        if (isCustom || (fileUri.isEmpty() && packageFiles.isEmpty())) return

        val intent = Intent(context, ModelDownloadService::class.java).apply {
            action = ModelDownloadService.ACTION_START_DOWNLOAD
            putExtra(ModelDownloadService.EXTRA_MODEL_ID, id)
            putExtra(ModelDownloadService.EXTRA_MODEL_NAME, name)
            if (packageFiles.isNotEmpty()) {
                putExtra(ModelDownloadService.EXTRA_FILE_URL, baseUrl.removeSuffix("/"))
                putExtra(
                    ModelDownloadService.EXTRA_MODEL_TYPE,
                    ModelDownloadService.TYPE_MULTI_FILE,
                )
                putStringArrayListExtra(
                    ModelDownloadService.EXTRA_FILE_NAMES,
                    ArrayList(packageFiles),
                )
                // Written only after every file lands, so a partial download
                // is never picked up as an installed model.
                putExtra(ModelDownloadService.EXTRA_MARKER_FILE, markerFileName(ditKind))
            } else {
                putExtra(
                    ModelDownloadService.EXTRA_FILE_URL,
                    "${baseUrl.removeSuffix("/")}/$fileUri",
                )
                putExtra(ModelDownloadService.EXTRA_IS_ZIP, fileUri.endsWith(".zip"))
                putExtra(ModelDownloadService.EXTRA_MODEL_TYPE, ModelDownloadService.TYPE_SD)
            }
        }

        context.startForegroundService(intent)
    }

    suspend fun deleteModel(context: Context, keepHistory: Boolean = true): Boolean = withContext(Dispatchers.IO) {
        try {
            val modelDir = File(getModelsDir(context), id)
            val generationPreferences = GenerationPreferences(context)

            if (!keepHistory) {
                HistoryManager(context).clearHistoryForModel(id)
            }
            generationPreferences.clearPreferencesForModel(id)
            PinnedModels.unpin(context, listOf(id))

            if (modelDir.exists() && modelDir.isDirectory) {
                val deleted = modelDir.deleteRecursively()
                Log.d("Model", "Delete model $id: $deleted")
                deleted
            } else {
                Log.d("Model", "Model does not exist: $id")
                false
            }
        } catch (e: Exception) {
            Log.e("Model", "error: ${e.message}")
            false
        }
    }

    // Rename a custom model, migrating every artifact keyed by its id: the
    // model directory, history (files + DB rows), per-model preferences and the
    // pinned list. The model directory is moved first because scanCustomModels()
    // keys off it; if that move fails nothing else is touched.
    suspend fun rename(context: Context, newName: String): RenameResult = withContext(Dispatchers.IO) {
        val newId = newName.replace(" ", "")
        when {
            newId.isEmpty() -> return@withContext RenameResult.Error(RenameResult.Reason.BlankName)

            newId == id -> return@withContext RenameResult.Success

            ModelRepository.isReservedModelId(newId) ->
                return@withContext RenameResult.Error(RenameResult.Reason.Reserved)
        }

        val modelsDir = getModelsDir(context)
        val oldDir = File(modelsDir, id)
        val newDir = File(modelsDir, newId)
        if (!oldDir.exists()) return@withContext RenameResult.Error(RenameResult.Reason.Io)
        if (newDir.exists()) return@withContext RenameResult.Error(RenameResult.Reason.Exists)

        if (!oldDir.renameTo(newDir)) {
            return@withContext RenameResult.Error(RenameResult.Reason.Io)
        }

        // The directory move is the commit point: the model is now usable under
        // its new id. Migrate the remaining artifacts best-effort. A rare failure
        // here degrades gracefully (saved params/history may not carry over) but
        // must not be reported as a failed rename, since the model HAS been
        // renamed. Cancellation must still propagate.
        try {
            HistoryManager(context).renameModel(id, newId)
            GenerationPreferences(context).migratePreferencesForModel(id, newId)
            PinnedModels.rename(context, id, newId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("Model", "rename post-move migration partly failed: ${e.message}")
        }
        RenameResult.Success
    }

    companion object {
        private const val MODELS_DIR = "models"

        // Where each part of a DiT package comes from, and the name the
        // native side expects on disk (see PipelineDit). The weights are
        // pulled from the repositories that publish them rather than rehosted:
        // the FP8 DiTs are Apache-2.0 releases from their authors, and the
        // Qwen3-4B text encoder and VAE come from the upstream Adreno packages
        // already converted to the formats stable-diffusion.cpp reads.
        val ZIMAGE_PACKAGE_FILES = listOf(
            "Kijai/Z-Image_comfy_fp8_scaled/resolve/main/" +
                "z-image-turbo_fp8_scaled_e4m3fn_KJ.safetensors|dit.safetensors",
            "zhiyuanasad/z_image_turbo_adreno/resolve/main/llm.gguf|llm.gguf",
            "zhiyuanasad/z_image_turbo_adreno/resolve/main/vae.safetensors|vae.safetensors",
            "Tongyi-MAI/Z-Image-Turbo/resolve/main/tokenizer/tokenizer.json|tokenizer.json",
        )

        val KLEIN_PACKAGE_FILES = listOf(
            "black-forest-labs/FLUX.2-klein-4b-fp8/resolve/main/" +
                "flux-2-klein-4b-fp8.safetensors|dit.safetensors",
            "zhiyuanasad/flux2_klein_adreno/resolve/main/llm.gguf|llm.gguf",
            "zhiyuanasad/flux2_klein_adreno/resolve/main/vae.safetensors|vae.safetensors",
            "Qwen/Qwen3-4B/resolve/main/tokenizer.json|tokenizer.json",
        )

        val QWEN_IMAGE_2_1_PACKAGE_FILES = listOf(
            "leejet/Qwen-Image-2.1-GGUF/resolve/main/" +
                "qwen_image_2.1-Q4_0.gguf|dit.gguf",
            "bartowski/Qwen_Qwen3-VL-8B-Instruct-GGUF/resolve/main/" +
                "Qwen_Qwen3-VL-8B-Instruct-Q4_0.gguf|llm.gguf",
            "bartowski/Qwen_Qwen3-VL-8B-Instruct-GGUF/resolve/main/" +
                "mmproj-Qwen_Qwen3-VL-8B-Instruct-f16.gguf|llm_vision.gguf",
            "Qwen/Qwen3-VL-8B-Instruct/resolve/main/tokenizer.json|tokenizer.json",
            "Comfy-Org/Qwen-Image-2.1/resolve/main/vae/" +
                "qwen_image_2.1_vae_bf16.safetensors|vae.safetensors",
        )

        // Uncensored (UC) Q4_0 build of the same DiT; the text encoder, vision
        // projector, tokenizer and VAE are identical to the stock package, only
        // dit.gguf changes. Same native "qwen21" pipeline handles it.
        val QWEN_IMAGE_2_1_UC_PACKAGE_FILES = listOf(
            "abenzerps/Qwen-Image-2.1-Uncensored-GGUF/resolve/main/" +
                "qwen-image-2.1-UC-Q4_0.gguf|dit.gguf",
            "bartowski/Qwen_Qwen3-VL-8B-Instruct-GGUF/resolve/main/" +
                "Qwen_Qwen3-VL-8B-Instruct-Q4_0.gguf|llm.gguf",
            "bartowski/Qwen_Qwen3-VL-8B-Instruct-GGUF/resolve/main/" +
                "mmproj-Qwen_Qwen3-VL-8B-Instruct-f16.gguf|llm_vision.gguf",
            "Qwen/Qwen3-VL-8B-Instruct/resolve/main/tokenizer.json|tokenizer.json",
            "Comfy-Org/Qwen-Image-2.1/resolve/main/vae/" +
                "qwen_image_2.1_vae_bf16.safetensors|vae.safetensors",
        )

        fun isDeviceSupported(): Boolean {
            val soc = getDeviceSoc()
            return getChipsetSuffix(soc) != null
        }

        fun isQualcommDevice(): Boolean {
            val soc = getDeviceSoc().uppercase()
            val prefixes = listOf(
                "SM", "QCS", "QCM", "CQ", "IPQ", "SXR", "AIC", "SSG",
                "SC", "SA", "SDM", "MSM", "QRB", "X1E", "X1P",
            )
            return prefixes.any { soc.startsWith(it) }
        }

        fun getChipsetSuffix(soc: String): String? {
            if (soc in chipsetModelSuffixes) {
                return chipsetModelSuffixes[soc]
            }
            if (soc.startsWith("SM")) {
                return "min"
            }
            return null
        }

        fun getModelsDir(context: Context): File =
            io.github.xororz.localdream.utils.Storage.modelsDir(context)

        fun isModelDownloaded(
            context: Context,
            modelId: String,
            isCustom: Boolean = false,
            requireFinished: Boolean = false,
        ): Boolean {
            if (isCustom) {
                return true
            }

            val modelDir = File(getModelsDir(context), modelId)
            if (!modelDir.exists() || !modelDir.isDirectory) {
                return false
            }

            // On-device-converted checkpoints only become usable when the native
            // converter writes "finished". Without this, a kill/reboot mid-download
            // or mid-conversion leaves a non-empty half model that used to be
            // reported as installed and then failed on first use.
            if (requireFinished) {
                return File(modelDir, "finished").isFile
            }

            val files = modelDir.listFiles()
            return files != null && files.isNotEmpty()
        }

        fun isDitPackageDownloaded(
            context: Context,
            modelId: String,
            ditKind: String,
            packageFiles: List<String>,
        ): Boolean {
            val modelDir = File(getModelsDir(context), modelId)
            val marker = markerFileName(ditKind)
            if (marker.isEmpty() || !File(modelDir, marker).isFile) return false
            return packageFiles.all { entry ->
                val remote = entry.substringBefore('|')
                val local = entry.substringAfter('|', remote.substringAfterLast('/'))
                File(modelDir, local).let { it.isFile && it.length() > 0L }
            }
        }

        private fun markerFileName(ditKind: String): String = when (ditKind) {
            "zimage" -> "ZIMAGE"
            "klein" -> "KLEIN"
            "qwen21" -> "QWEN_IMAGE_2_1"
            else -> ""
        }

        // Upscalers store a single raw weight file; existence must match what
        // performUpscale() actually loads, not just a non-empty directory.
        const val UPSCALER_FILE_NAME = "upscaler.bin"

        fun isUpscalerDownloaded(context: Context, upscalerId: String): Boolean {
            val file = File(File(getModelsDir(context), upscalerId), UPSCALER_FILE_NAME)
            return file.exists() && file.length() > 0
        }
    }
}

@Immutable
data class UpscalerModel(
    val id: String,
    val name: String,
    val description: String,
    val baseUrl: String,
    val fileUri: String,
    val isDownloaded: Boolean = false,
) {
    fun startDownload(context: Context) {
        val intent = Intent(context, ModelDownloadService::class.java).apply {
            action = ModelDownloadService.ACTION_START_DOWNLOAD
            putExtra(ModelDownloadService.EXTRA_MODEL_ID, id)
            putExtra(ModelDownloadService.EXTRA_MODEL_NAME, name)
            putExtra(ModelDownloadService.EXTRA_FILE_URL, "${baseUrl.removeSuffix("/")}/$fileUri")
            putExtra(ModelDownloadService.EXTRA_IS_ZIP, false)
            putExtra(ModelDownloadService.EXTRA_MODEL_TYPE, "upscaler")
        }

        context.startForegroundService(intent)
    }
}

class UpscalerRepository private constructor(private val context: Context) {
    private val generationPreferences = GenerationPreferences(context)
    private val refreshMutex = Mutex()

    var upscalers by mutableStateOf<List<UpscalerModel>>(emptyList())
        private set

    private var isLoaded = false

    suspend fun ensureLoaded() {
        if (isLoaded) return
        refreshMutex.withLock {
            if (isLoaded) return
            val baseUrl = generationPreferences.getBaseUrl()
            upscalers = withContext(Dispatchers.IO) { initializeUpscalers(baseUrl) }
            isLoaded = true
        }
    }

    private fun initializeUpscalers(baseUrl: String): List<UpscalerModel> {
        val soc = getDeviceSoc()
        val suffix = Model.getChipsetSuffix(soc) ?: "min"

        return listOf(
            createAnimeUpscaler(baseUrl, suffix),
            createRealisticUpscaler(baseUrl, suffix),
        )
    }

    private fun createAnimeUpscaler(baseUrl: String, suffix: String): UpscalerModel {
        val id = "upscaler_anime"
        val fileUri =
            "xororz/upscaler/resolve/main/realesrgan_x4plus_anime_6b/upscaler_$suffix.bin"

        val isDownloaded = Model.isUpscalerDownloaded(context, id)

        return UpscalerModel(
            id = id,
            name = context.getString(R.string.upscaler_anime),
            description = context.getString(R.string.upscaler_anime_desc),
            baseUrl = baseUrl,
            fileUri = fileUri,
            isDownloaded = isDownloaded,
        )
    }

    private fun createRealisticUpscaler(baseUrl: String, suffix: String): UpscalerModel {
        val id = "upscaler_realistic"
        val fileUri = "xororz/upscaler/resolve/main/4x_UltraSharpV2_Lite/upscaler_$suffix.bin"

        val isDownloaded = Model.isUpscalerDownloaded(context, id)

        return UpscalerModel(
            id = id,
            name = context.getString(R.string.upscaler_realistic),
            description = context.getString(R.string.upscaler_realistic_desc),
            baseUrl = baseUrl,
            fileUri = fileUri,
            isDownloaded = isDownloaded,
        )
    }

    // Re-read the base URL and rebuild the upscaler list so a base-URL change
    // in settings takes effect without an app restart. Mirrors
    // ModelRepository.refreshAllModels(); the singleton otherwise caches the
    // URL captured at first ensureLoaded().
    suspend fun refreshBaseUrl() {
        refreshMutex.withLock {
            if (!isLoaded) return
            val baseUrl = generationPreferences.getBaseUrl()
            upscalers = withContext(Dispatchers.IO) { initializeUpscalers(baseUrl) }
        }
    }

    suspend fun refreshUpscalerState(upscalerId: String) {
        refreshMutex.withLock {
            val current = upscalers
            upscalers = withContext(Dispatchers.IO) {
                current.map { upscaler ->
                    if (upscaler.id == upscalerId) {
                        val isDownloaded = Model.isUpscalerDownloaded(context, upscaler.id)
                        upscaler.copy(isDownloaded = isDownloaded)
                    } else {
                        upscaler
                    }
                }
            }
        }
    }

    companion object {
        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var instance: UpscalerRepository? = null

        fun getInstance(context: Context): UpscalerRepository = instance ?: synchronized(this) {
            instance ?: UpscalerRepository(context.applicationContext).also { instance = it }
        }
    }
}

class ModelRepository private constructor(private val context: Context) {
    private val generationPreferences = GenerationPreferences(context)
    private val refreshMutex = Mutex()

    // Read by the create*Model() builders during a scan; refreshed from
    // preferences at the start of every refresh, always under refreshMutex.
    private var baseUrl = "https://huggingface.co/"

    var models by mutableStateOf<List<Model>>(emptyList())
        private set

    // False until the first disk scan completes; lets the UI tell "still
    // loading" apart from "genuinely no models".
    var isLoaded by mutableStateOf(false)
        private set

    suspend fun ensureLoaded() {
        if (isLoaded) return
        refreshAllModels()
    }

    private fun scanCustomModels(): List<Model> {
        val modelsDir = Model.getModelsDir(context)
        val customModels = mutableListOf<Model>()
        // Link-imported models are provided by initializeModels() (with their
        // proper name/URL), so don't also surface the finished folder here.
        val urlImportIds = io.github.xororz.localdream.utils.UrlModelImport.idSet(context)

        if (modelsDir.exists() && modelsDir.isDirectory) {
            modelsDir.listFiles()?.forEach { dir ->
                if (!dir.isDirectory) return@forEach

                val modelId = dir.name
                if (modelId in urlImportIds) return@forEach
                if (modelId in RESERVED_MODEL_IDS) {
                    Log.w(
                        "ModelRepository",
                        "skip custom model '$modelId': id conflicts with a built-in model",
                    )
                    return@forEach
                }

                val finishedFile = File(dir, "finished")
                val npuCustomFile = File(dir, "npucustom")
                val sdxlFile = File(dir, "SDXL")
                val animaFile = File(dir, "ANIMA")
                val zImageFile = File(dir, "ZIMAGE")
                val kleinFile = File(dir, "KLEIN")
                val qwenImage21File = File(dir, "QWEN_IMAGE_2_1")

                when {
                    zImageFile.exists() && DitEngine.isSupportedDevice() ->
                        customModels.add(createCustomModel(dir, isNpu = true, ditKind = "zimage"))

                    kleinFile.exists() && DitEngine.isSupportedDevice() ->
                        customModels.add(createCustomModel(dir, isNpu = true, ditKind = "klein"))

                    qwenImage21File.exists() && DitEngine.isSupportedDevice() ->
                        customModels.add(createCustomModel(dir, isNpu = true, ditKind = "qwen21"))

                    animaFile.exists() ->
                        customModels.add(createCustomModel(dir, isNpu = true, isAnima = true))

                    sdxlFile.exists() ->
                        customModels.add(createCustomModel(dir, isNpu = !File(dir, "unet.mnn").exists(), isSdxl = true))

                    finishedFile.exists() ->
                        customModels.add(createCustomModel(dir, isNpu = false))

                    npuCustomFile.exists() ->
                        customModels.add(createCustomModel(dir, isNpu = true))
                }
            }
        }

        return customModels
            .filter { m ->
                io.github.xororz.localdream.utils.DeviceCapabilities.isListable(
                    isDit = m.isDit,
                    runOnCpu = m.runOnCpu,
                    isSdxlNpu = m.isSdxl && !m.runOnCpu,
                )
            }
            .sortedBy { it.name.lowercase() }
    }

    private fun createCustomModel(
        modelDir: File,
        isNpu: Boolean = false,
        isSdxl: Boolean = false,
        isAnima: Boolean = false,
        ditKind: String = "",
    ): Model {
        val modelId = modelDir.name
        // Imported models have no code-level defaults: config.json (if
        // bundled in the zip) wins, the generic placeholder prompts below
        // only fill what it leaves unset.
        val placeholders = ModelConfig(
            prompt = "masterpiece, best quality, a cat sat on a mat,",
            negativePrompt = "lowres, bad anatomy, bad hands, missing fingers, extra fingers, bad arms, missing legs, missing arms, poorly drawn face, bad face, fused face, cloned face, three crus, fused feet, fused thigh, extra crus, ugly fingers, horn, huge eyes, worst face, 2girl, long fingers, disconnected limbs,",
        )
        val config = ModelConfig.read(modelDir) ?: ModelConfig()

        return Model(
            id = modelId,
            name = modelId,
            description = context.getString(R.string.custom_model),
            baseUrl = "",
            generationSize = if (isSdxl || isAnima) 1024 else 512,
            approximateSize = "Custom",
            isDownloaded = true,
            configDefaults = config.withFallback(placeholders),
            runOnCpu = !isNpu,
            isCustom = true,
            isSdxl = isSdxl,
            isAnima = isAnima,
            ditKind = ditKind,
        )
    }

    private fun initializeModels(): List<Model> {
        val customModels = scanCustomModels()

        val predefinedModels = mutableListOf<Model>().apply {
            // et.18: catalog entries are always constructed so every device can
            // see them; runnability is shown as a lock + blocked download
            // (DeviceCapabilities.gateReason) rather than hiding the model.
            add(createZImageTurboModel())
            add(createFlux2KleinModel())
            add(createQwenImage21Model())
            add(createQwenImage21UcModel())
            add(createIllustriousV16Model())
            add(createIllustriousV16Dmd2Model())
            add(createCyberRealisticV10Model())
            add(createCyberRealisticV10Dmd2Model())
            add(createAnythingV5Model())
            add(createAnythingV5ModelCPU())
            add(createQteaMixModel())
            add(createQteaMixModelCPU())
            add(createAbsoluteRealityModel())
            add(createAbsoluteRealityModelCPU())
            add(createCuteYukiMixModel())
            add(createCuteYukiMixModelCPU())
            add(createChilloutMixModelCPU())
            add(createChilloutMixModel())
            // Extra SD1.5 models (uncensored-friendly): raw checkpoint is
            // downloaded and converted to MNN on the device on first install,
            // so they run on CPU/GPU incl. 32-bit, just like the prebuilt ones.
            add(createRealisticVisionCpu())
            add(createCounterfeitCpu())
            add(createDreamShaperCpu())
            add(createMajicmixCpu())
            add(createAnalogMadnessCpu())
            // et.10 batch: six more unrestricted SD1.5 checkpoints.
            add(createJuggernautCpu())
            add(createCamelliaNsfwCpu())
            add(createDarkSushiCpu())
            add(createBreakDomainCpu())
            add(createHelloWorldCpu())
            // et.11 batch: six more unrestricted SD1.5 checkpoints.
            add(createAniRealityCpu())
            add(createChillyMixCpu())
            add(createTwoKwiCpu())
            add(createBeautifulArtCpu())
            // et.12 batch: nine 32-bit-safe (<2^31 B), header-validated
            // checkpoints; replaces FantasyTime/CoffeeMix/LemonPastel whose
            // files exceeded signed-32-bit offsets and crashed armeabi-v7a.
            add(createPhotonCpu())
            add(createLemonCreamiCpu())
            add(createMeinaPastelCpu())
            add(createRealisianV5Cpu())
            add(createAbyssOrangeCpu())
            add(createHassakuCpu())
            add(createColorfulCpu())
            add(createCosplayMixCpu())
            add(createNextPhotoCpu())
            // et.14 batch: nine more 32-bit-safe, header-validated checkpoints.
            add(createMajicV7Cpu())
            add(createEpiGoddessCpu())
            add(createRevAnimatedCpu())
            add(createMeinaV11Cpu())
            add(createAingV9Cpu())
            add(createLemonTeaCpu())
            add(createCounterMixV2Cpu())
            add(createBeautifulFantasyCpu())
            add(createEtherRealLuxCpu())
            // User "import from link" models (persisted SD1.5 convert entries).
            io.github.xororz.localdream.utils.UrlModelImport.entries(context).forEach { e ->
                add(createUrlImportModel(e))
            }
        }

        return customModels + predefinedModels
            .map { applyConfigDefaults(it) }
            .filter { m ->
                // Hide entries this device physically cannot run. SD1.5 CPU
                // models survive on every ABI (incl. 32-bit); arm64-only NPU /
                // DiT / SDXL entries are dropped on 32-bit or weak SoCs.
                io.github.xororz.localdream.utils.DeviceCapabilities.isListable(
                    isDit = m.isDit,
                    runOnCpu = m.runOnCpu,
                    isSdxlNpu = m.isSdxl && !m.runOnCpu,
                )
            }
    }

    // Load config.json shipped inside the model's downloaded files, keeping
    // any values already merged into configDefaults (e.g. the custom model
    // placeholders) as fallback.
    private fun applyConfigDefaults(model: Model): Model {
        val config = ModelConfig.read(File(Model.getModelsDir(context), model.id)) ?: return model
        return model.copy(configDefaults = config.withFallback(model.configDefaults))
    }

    // DiT packages are fetched file-by-file because they are too large to
    // unpack from an archive on device.
    private fun createZImageTurboModel(): Model {
        val id = "z_image_turbo"
        return Model(
            id = id,
            name = "Z-Image Turbo",
            description = context.getString(R.string.z_image_turbo_description),
            baseUrl = baseUrl,
            packageFiles = Model.ZIMAGE_PACKAGE_FILES,
            generationSize = 1024,
            approximateSize = "8.8GB",
            isDownloaded = Model.isDitPackageDownloaded(
                context,
                id,
                "zimage",
                Model.ZIMAGE_PACKAGE_FILES,
            ),
            codeDefaults = ModelConfig(
                prompt = "a lovely cat wearing black sunglasses, studio photo,",
                negativePrompt = "",
                steps = 8f,
                cfg = 1f,
                scheduler = "euler",
            ),
            runOnCpu = false,
            ditKind = "zimage",
            ramRequiredBytes = 10L * 1024 * 1024 * 1024,
            storageRequiredBytes = 10L * 1000 * 1000 * 1000,
        )
    }

    private fun createFlux2KleinModel(): Model {
        val id = "flux2_klein_4b"
        return Model(
            id = id,
            name = "FLUX.2 Klein 4B",
            description = context.getString(R.string.flux2_klein_description),
            baseUrl = baseUrl,
            packageFiles = Model.KLEIN_PACKAGE_FILES,
            generationSize = 1024,
            approximateSize = "6.7GB",
            isDownloaded = Model.isDitPackageDownloaded(
                context,
                id,
                "klein",
                Model.KLEIN_PACKAGE_FILES,
            ),
            codeDefaults = ModelConfig(
                prompt = "a lovely cat wearing black sunglasses, studio photo,",
                negativePrompt = "",
                steps = 4f,
                cfg = 1f,
                scheduler = "euler",
                // 1.0 keeps the base image as a pure reference (or a full
                // redraw inside a mask); lower values also start from it.
                denoiseStrength = 1f,
            ),
            runOnCpu = false,
            ditKind = "klein",
            ramRequiredBytes = 8L * 1024 * 1024 * 1024,
            storageRequiredBytes = 8L * 1000 * 1000 * 1000,
        )
    }

    private fun createQwenImage21Model(): Model {
        val id = "qwen_image_2_1"
        return Model(
            id = id,
            name = "Qwen Image 2.1",
            description = context.getString(R.string.qwen_image_2_1_description),
            baseUrl = baseUrl,
            packageFiles = Model.QWEN_IMAGE_2_1_PACKAGE_FILES,
            generationSize = 1024,
            approximateSize = "10.8GB",
            isDownloaded = Model.isDitPackageDownloaded(
                context,
                id,
                "qwen21",
                Model.QWEN_IMAGE_2_1_PACKAGE_FILES,
            ),
            codeDefaults = ModelConfig(
                prompt = "a lovely cat holding a sign that says 'Qwen Image 2.1',",
                negativePrompt = "",
                steps = 20f,
                cfg = 1f,
                scheduler = "euler",
                denoiseStrength = 1f,
            ),
            runOnCpu = false,
            ditKind = "qwen21",
            ramRequiredBytes = 12L * 1024 * 1024 * 1024,
            storageRequiredBytes = 13L * 1000 * 1000 * 1000,
        )
    }

    private fun createQwenImage21UcModel(): Model {
        val id = "qwen_image_2_1_uc"
        return Model(
            id = id,
            name = "Qwen Image 2.1 UC Q4_0",
            description = context.getString(R.string.qwen_image_2_1_uc_description),
            baseUrl = baseUrl,
            packageFiles = Model.QWEN_IMAGE_2_1_UC_PACKAGE_FILES,
            generationSize = 1024,
            approximateSize = "10.8GB",
            isDownloaded = Model.isDitPackageDownloaded(
                context,
                id,
                "qwen21",
                Model.QWEN_IMAGE_2_1_UC_PACKAGE_FILES,
            ),
            codeDefaults = ModelConfig(
                prompt = "a lovely cat holding a sign that says 'Qwen Image 2.1 UC',",
                negativePrompt = "",
                steps = 20f,
                cfg = 1f,
                scheduler = "euler",
                denoiseStrength = 1f,
            ),
            runOnCpu = false,
            ditKind = "qwen21",
            ramRequiredBytes = 12L * 1024 * 1024 * 1024,
            storageRequiredBytes = 13L * 1000 * 1000 * 1000,
        )
    }

    private fun isSdxlCapableSoc(soc: String): Boolean = soc in setOf("SM8750", "SM8750P", "SM8850", "SM8850P", "SM8845", "SM8650")

    private fun createCyberRealisticV10Model(): Model {
        val id = "cyber_realistic_v10"
        val fileUri = "xororz/sdxl-qnn/resolve/main/cyber_realistic_v10_qnn2.28_8gen3.zip"

        val isDownloaded = Model.isModelDownloaded(context, id, false)

        return Model(
            id = id,
            name = "CyberRealistic v10",
            description = context.getString(R.string.cyberrealistic_description),
            baseUrl = baseUrl,
            fileUri = fileUri,
            generationSize = 1024,
            approximateSize = "4.2GB",
            isDownloaded = isDownloaded,
            codeDefaults = ModelConfig(
                prompt = "masterpiece, best quality, a majestic cat sitting on a windowsill at sunset,",
                negativePrompt = "lowres, bad anatomy, bad hands, text, error, missing fingers, extra digit, fewer digits, cropped, worst quality, low quality, normal quality, jpeg artifacts, signature, watermark, username, blurry,",
            ),
            runOnCpu = false,
            isSdxl = true,
        )
    }

    private fun createCyberRealisticV10Dmd2Model(): Model {
        val id = "cyber_realistic_v10_dmd2"
        val fileUri = "xororz/sdxl-qnn/resolve/main/cyber_realistic_v10_dmd2_qnn2.28_8gen3.zip"

        val isDownloaded = Model.isModelDownloaded(context, id, false)

        return Model(
            id = id,
            name = "CyberRealistic v10 DMD2",
            description = context.getString(R.string.dmd2_description),
            baseUrl = baseUrl,
            fileUri = fileUri,
            generationSize = 1024,
            approximateSize = "4.2GB",
            isDownloaded = isDownloaded,
            codeDefaults = ModelConfig(
                prompt = "masterpiece, best quality, a majestic cat sitting on a windowsill at sunset,",
                negativePrompt = "lowres, bad anatomy, bad hands, text, error, missing fingers, extra digit, fewer digits, cropped, worst quality, low quality, normal quality, jpeg artifacts, signature, watermark, username, blurry,",
            ),
            // steps/cfg/scheduler intentionally unset: the distilled model
            // ships them in a config.json bundled inside the zip.
            runOnCpu = false,
            isSdxl = true,
        )
    }

    private fun createIllustriousV16Model(): Model {
        val id = "illustrious_v16"
        val fileUri = "xororz/sdxl-qnn/resolve/main/illustrious_v16_qnn2.28_8gen3.zip"

        val isDownloaded = Model.isModelDownloaded(context, id, false)

        return Model(
            id = id,
            name = "Illustrious v16",
            description = context.getString(R.string.illustriousv16_description),
            baseUrl = baseUrl,
            fileUri = fileUri,
            generationSize = 1024,
            approximateSize = "4.2GB",
            isDownloaded = isDownloaded,
            codeDefaults = ModelConfig(
                prompt = "1girl, solo, blue twintails, very long hair, bangs, blue eyes, jewelry, necklace, hair bow, off-shoulder white frilled dress, bare shoulders, collarbone, underwater, floating hair, reaching towards viewer, air bubbles, blue theme, blurry foreground, masterpiece",
                negativePrompt = "lowres, bad anatomy, bad hands, missing fingers, extra fingers, bad arms, missing legs, missing arms, poorly drawn face, bad face, fused face, cloned face, three crus, fused feet, fused thigh, extra crus, ugly fingers, horn, realistic photo, huge eyes, worst face, 2girl, long fingers, disconnected limbs,",
            ),
            runOnCpu = false,
            isSdxl = true,
        )
    }

    private fun createIllustriousV16Dmd2Model(): Model {
        val id = "illustrious_v16_dmd2"
        val fileUri = "xororz/sdxl-qnn/resolve/main/illustrious_v16_dmd2_qnn2.28_8gen3.zip"

        val isDownloaded = Model.isModelDownloaded(context, id, false)

        return Model(
            id = id,
            name = "Illustrious v16 DMD2",
            description = context.getString(R.string.dmd2_description),
            baseUrl = baseUrl,
            fileUri = fileUri,
            generationSize = 1024,
            approximateSize = "4.2GB",
            isDownloaded = isDownloaded,
            codeDefaults = ModelConfig(
                prompt = "1girl, solo, blue twintails, very long hair, bangs, blue eyes, jewelry, necklace, hair bow, off-shoulder white frilled dress, bare shoulders, collarbone, underwater, floating hair, reaching towards viewer, air bubbles, blue theme, blurry foreground, masterpiece",
                negativePrompt = "lowres, bad anatomy, bad hands, missing fingers, extra fingers, bad arms, missing legs, missing arms, poorly drawn face, bad face, fused face, cloned face, three crus, fused feet, fused thigh, extra crus, ugly fingers, horn, realistic photo, huge eyes, worst face, 2girl, long fingers, disconnected limbs,",
            ),
            runOnCpu = false,
            isSdxl = true,
        )
    }

    private fun createAnythingV5Model(): Model {
        val id = "anythingv5"
        val soc = getDeviceSoc()
        val suffix = Model.getChipsetSuffix(soc) ?: "min"
        val fileUri = "xororz/sd-qnn/resolve/main/AnythingV5_qnn2.28_$suffix.zip"

        val isDownloaded = Model.isModelDownloaded(context, id, false)
        return Model(
            id = id,
            name = "Anything V5.0",
            description = context.getString(R.string.anythingv5_description),
            baseUrl = baseUrl,
            fileUri = fileUri,
            approximateSize = "1.1GB",
            isDownloaded = isDownloaded,
            codeDefaults = ModelConfig(
                prompt = "masterpiece, best quality, 1girl, solo, cute, white hair,",
                negativePrompt = "lowres, bad anatomy, bad hands, missing fingers, extra fingers, bad arms, missing legs, missing arms, poorly drawn face, bad face, fused face, cloned face, three crus, fused feet, fused thigh, extra crus, ugly fingers, horn, realistic photo, huge eyes, worst face, 2girl, long fingers, disconnected limbs,",
            ),
            runOnCpu = false,
        )
    }

    private fun createAnythingV5ModelCPU(): Model {
        val id = "anythingv5cpu"
        val fileUri = "xororz/sd-mnn/resolve/main/AnythingV5.zip"

        val isDownloaded = Model.isModelDownloaded(context, id, false)

        return Model(
            id = id,
            name = "Anything V5.0",
            description = context.getString(R.string.anythingv5_description),
            baseUrl = baseUrl,
            fileUri = fileUri,
            approximateSize = "1.2GB",
            isDownloaded = isDownloaded,
            codeDefaults = ModelConfig(
                prompt = "masterpiece, best quality, 1girl, solo, cute, white hair,",
                negativePrompt = "lowres, bad anatomy, bad hands, missing fingers, extra fingers, bad arms, missing legs, missing arms, poorly drawn face, bad face, fused face, cloned face, three crus, fused feet, fused thigh, extra crus, ugly fingers, horn, realistic photo, huge eyes, worst face, 2girl, long fingers, disconnected limbs,",
            ),
            runOnCpu = true,
        )
    }

    private fun createQteaMixModel(): Model {
        val id = "qteamix"
        val soc = getDeviceSoc()
        val suffix = Model.getChipsetSuffix(soc) ?: "min"
        val fileUri = "xororz/sd-qnn/resolve/main/QteaMix_qnn2.28_$suffix.zip"
        val isDownloaded = Model.isModelDownloaded(context, id, false)
        return Model(
            id = id,
            name = "QteaMix",
            description = context.getString(R.string.qteamix_description),
            baseUrl = baseUrl,
            fileUri = fileUri,
            approximateSize = "1.1GB",
            isDownloaded = isDownloaded,
            codeDefaults = ModelConfig(
                prompt = "chibi, best quality, 1girl, solo, cute, pink hair,",
                negativePrompt = "lowres, bad anatomy, bad hands, missing fingers, extra fingers, bad arms, missing legs, missing arms, poorly drawn face, bad face, fused face, cloned face, three crus, fused feet, fused thigh, extra crus, ugly fingers, horn, realistic photo, huge eyes, worst face, 2girl, long fingers, disconnected limbs,",
            ),
        )
    }

    private fun createQteaMixModelCPU(): Model {
        val id = "qteamixcpu"
        val fileUri = "xororz/sd-mnn/resolve/main/QteaMix.zip"
        val isDownloaded = Model.isModelDownloaded(context, id, false)

        return Model(
            id = id,
            name = "QteaMix",
            description = context.getString(R.string.qteamix_description),
            baseUrl = baseUrl,
            fileUri = fileUri,
            approximateSize = "1.2GB",
            isDownloaded = isDownloaded,
            codeDefaults = ModelConfig(
                prompt = "chibi, best quality, 1girl, solo, cute, pink hair,",
                negativePrompt = "lowres, bad anatomy, bad hands, missing fingers, extra fingers, bad arms, missing legs, missing arms, poorly drawn face, bad face, fused face, cloned face, three crus, fused feet, fused thigh, extra crus, ugly fingers, horn, realistic photo, huge eyes, worst face, 2girl, long fingers, disconnected limbs,",
            ),
            runOnCpu = true,
        )
    }

    private fun createCuteYukiMixModel(): Model {
        val id = "cuteyukimix"
        val soc = getDeviceSoc()
        val suffix = Model.getChipsetSuffix(soc) ?: "min"
        val fileUri = "xororz/sd-qnn/resolve/main/CuteYukiMix_qnn2.28_$suffix.zip"
        val isDownloaded = Model.isModelDownloaded(context, id, false)
        return Model(
            id = id,
            name = "CuteYukiMix",
            description = context.getString(R.string.cuteyukimix_description),
            baseUrl = baseUrl,
            fileUri = fileUri,
            approximateSize = "1.1GB",
            isDownloaded = isDownloaded,
            codeDefaults = ModelConfig(
                prompt = "masterpiece, best quality, 1girl, solo, cute, white hair,",
                negativePrompt = "lowres, bad anatomy, bad hands, missing fingers, extra fingers, bad arms, missing legs, missing arms, poorly drawn face, bad face, fused face, cloned face, three crus, fused feet, fused thigh, extra crus, ugly fingers, horn, realistic photo, huge eyes, worst face, 2girl, long fingers, disconnected limbs,",
            ),
        )
    }

    private fun createCuteYukiMixModelCPU(): Model {
        val id = "cuteyukimixcpu"
        val fileUri = "xororz/sd-mnn/resolve/main/CuteYukiMix.zip"
        val isDownloaded = Model.isModelDownloaded(context, id, false)

        return Model(
            id = id,
            name = "CuteYukiMix",
            description = context.getString(R.string.cuteyukimix_description),
            baseUrl = baseUrl,
            fileUri = fileUri,
            approximateSize = "1.2GB",
            isDownloaded = isDownloaded,
            codeDefaults = ModelConfig(
                prompt = "masterpiece, best quality, 1girl, solo, cute, white hair,",
                negativePrompt = "lowres, bad anatomy, bad hands, missing fingers, extra fingers, bad arms, missing legs, missing arms, poorly drawn face, bad face, fused face, cloned face, three crus, fused feet, fused thigh, extra crus, ugly fingers, horn, realistic photo, huge eyes, worst face, 2girl, long fingers, disconnected limbs,",
            ),
            runOnCpu = true,
        )
    }

    private fun createAbsoluteRealityModel(): Model {
        val id = "absolutereality"
        val soc = getDeviceSoc()
        val suffix = Model.getChipsetSuffix(soc) ?: "min"
        val fileUri = "xororz/sd-qnn/resolve/main/AbsoluteReality_qnn2.28_$suffix.zip"
        val isDownloaded = Model.isModelDownloaded(context, id, false)
        return Model(
            id = id,
            name = "Absolute Reality",
            description = context.getString(R.string.absolutereality_description),
            baseUrl = baseUrl,
            fileUri = fileUri,
            approximateSize = "1.1GB",
            isDownloaded = isDownloaded,
            codeDefaults = ModelConfig(
                prompt = "masterpiece, best quality, ultra-detailed, realistic, 8k, a cat on grass,",
                negativePrompt = "worst quality, low quality, normal quality, poorly drawn, lowres, low resolution, signature, watermarks, ugly, out of focus, error, blurry, unclear photo, bad photo, unrealistic, semi realistic, pixelated, cartoon, anime, cgi, drawing, 2d, 3d, censored, duplicate,",
            ),
            runOnCpu = false,
        )
    }

    private fun createAbsoluteRealityModelCPU(): Model {
        val id = "absoluterealitycpu"
        val fileUri = "xororz/sd-mnn/resolve/main/AbsoluteReality.zip"
        val isDownloaded = Model.isModelDownloaded(context, id, false)

        return Model(
            id = id,
            name = "Absolute Reality",
            description = context.getString(R.string.absolutereality_description),
            baseUrl = baseUrl,
            fileUri = fileUri,
            approximateSize = "1.2GB",
            isDownloaded = isDownloaded,
            codeDefaults = ModelConfig(
                prompt = "masterpiece, best quality, ultra-detailed, realistic, 8k, a cat on grass,",
                negativePrompt = "worst quality, low quality, normal quality, poorly drawn, lowres, low resolution, signature, watermarks, ugly, out of focus, error, blurry, unclear photo, bad photo, unrealistic, semi realistic, pixelated, cartoon, anime, cgi, drawing, 2d, 3d, censored, duplicate,",
            ),
            runOnCpu = true,
        )
    }

    private fun createChilloutMixModel(): Model {
        val id = "chilloutmix"
        val soc = getDeviceSoc()
        val suffix = Model.getChipsetSuffix(soc) ?: "min"
        val fileUri = "xororz/sd-qnn/resolve/main/ChilloutMix_qnn2.28_$suffix.zip"
        val isDownloaded = Model.isModelDownloaded(context, id, false)
        return Model(
            id = id,
            name = "ChilloutMix",
            description = context.getString(R.string.chilloutmix_description),
            baseUrl = baseUrl,
            fileUri = fileUri,
            approximateSize = "1.1GB",
            isDownloaded = isDownloaded,
            codeDefaults = ModelConfig(
                prompt = "RAW photo, best quality, realistic, photo-realistic, masterpiece, 1girl, upper body, facing front, portrait, white shirt",
                negativePrompt = "paintings, cartoon, anime, lowres, bad anatomy, bad hands, text, error, missing fingers, extra digit, cropped, worst quality, low quality, normal quality, jpeg artifacts, signature, watermark, username, skin spots, acnes, skin blemishes",
            ),
            runOnCpu = false,
        )
    }

    private fun createChilloutMixModelCPU(): Model {
        val id = "chilloutmixcpu"
        val fileUri = "xororz/sd-mnn/resolve/main/ChilloutMix.zip"
        val isDownloaded = Model.isModelDownloaded(context, id, false)

        return Model(
            id = id,
            name = "ChilloutMix",
            description = context.getString(R.string.chilloutmix_description),
            baseUrl = baseUrl,
            fileUri = fileUri,
            approximateSize = "1.2GB",
            isDownloaded = isDownloaded,
            codeDefaults = ModelConfig(
                prompt = "RAW photo, best quality, realistic, photo-realistic, masterpiece, 1girl, upper body, facing front, portrait, white shirt",
                negativePrompt = "paintings, cartoon, anime, lowres, bad anatomy, bad hands, text, error, missing fingers, extra digit, cropped, worst quality, low quality, normal quality, jpeg artifacts, signature, watermark, username, skin spots, acnes, skin blemishes",
            ),
            runOnCpu = true,
        )
    }

    // Extra SD1.5 models shipped without a prebuilt MNN zip. The raw
    // checkpoint is downloaded (mirror host with official fallback) and the
    // on-device converter produces the MNN files once, so these run on
    // CPU/GPU including 32-bit devices.
    private fun createRealisticVisionCpu(): Model {
        val id = "realisticvision_cpu"
        val isDownloaded = Model.isModelDownloaded(context, id, false, requireFinished = true)
        return Model(
            id = id,
            name = "Realistic Vision V5.1",
            description = context.getString(R.string.realisticvision_description),
            baseUrl = "",
            approximateSize = context.getString(R.string.size_sd_convert),
            isDownloaded = isDownloaded,
            codeDefaults = ModelConfig(
                prompt = "RAW photo, best quality, realistic, photo-realistic, masterpiece, detailed skin, 8k uhd, dslr, soft lighting, high quality, film grain",
                negativePrompt = "cartoon, anime, drawing, painting, lowres, bad anatomy, bad hands, text, error, missing fingers, extra digit, cropped, worst quality, low quality, jpeg artifacts, signature, watermark, deformed, blurry",
            ),
            runOnCpu = true,
            convertSourceUrl = "SG161222/Realistic_Vision_V5.1_noVAE/resolve/main/Realistic_Vision_V5.1_fp16-no-ema.safetensors",
        )
    }

    private fun createCounterfeitCpu(): Model {
        val id = "counterfeit_cpu"
        val isDownloaded = Model.isModelDownloaded(context, id, false, requireFinished = true)
        return Model(
            id = id,
            name = "Counterfeit V2.5",
            description = context.getString(R.string.counterfeit_description),
            baseUrl = "",
            approximateSize = context.getString(R.string.size_sd_convert),
            isDownloaded = isDownloaded,
            codeDefaults = ModelConfig(
                prompt = "masterpiece, best quality, 1girl, solo, detailed eyes, anime style",
                negativePrompt = "lowres, bad anatomy, bad hands, missing fingers, extra fingers, poorly drawn face, fused face, worst face, realistic photo, long fingers, disconnected limbs",
            ),
            runOnCpu = true,
            convertSourceUrl = "gsdf/Counterfeit-V2.5/resolve/main/Counterfeit-V2.5_fp16.safetensors",
        )
    }

    // Versatile, very stable general-purpose SD1.5 checkpoint (good for
    // realistic, art and anime alike; known for low failure rate).
    private fun createDreamShaperCpu(): Model {
        val id = "dreamshaper_cpu"
        val isDownloaded = Model.isModelDownloaded(context, id, false, requireFinished = true)
        return Model(
            id = id,
            name = "DreamShaper 8",
            description = context.getString(R.string.dreamshaper_description),
            baseUrl = "",
            approximateSize = context.getString(R.string.size_sd_convert),
            isDownloaded = isDownloaded,
            codeDefaults = ModelConfig(
                prompt = "masterpiece, best quality, highly detailed, sharp focus, professional, 8k uhd",
                negativePrompt = "lowres, bad anatomy, bad hands, missing fingers, extra fingers, poorly drawn face, worst quality, low quality, jpeg artifacts, signature, watermark, blurry, deformed",
            ),
            runOnCpu = true,
            convertSourceUrl = "digiplay/DreamShaper_8/resolve/main/dreamshaper_8.safetensors",
        )
    }

    private fun createMajicmixCpu(): Model {
        val id = "majicmix_cpu"
        val isDownloaded = Model.isModelDownloaded(context, id, false, requireFinished = true)
        return Model(
            id = id,
            name = "majicMIX Realistic v7",
            description = context.getString(R.string.majicmix_description),
            baseUrl = "",
            approximateSize = context.getString(R.string.size_sd_convert),
            isDownloaded = isDownloaded,
            codeDefaults = ModelConfig(
                prompt = "RAW photo, best quality, masterpiece, photorealistic, 8k uhd, dslr, ultra detailed skin, soft natural lighting, sharp focus, film grain",
                negativePrompt = "cartoon, anime, drawing, painting, lowres, bad anatomy, bad hands, text, error, missing fingers, extra digit, cropped, worst quality, low quality, jpeg artifacts, signature, watermark, deformed, blurry",
            ),
            runOnCpu = true,
            convertSourceUrl = "digiplay/majicMIX_realistic_v7/resolve/main/majicmixRealistic_v7.safetensors",
        )
    }

    private fun createAnalogMadnessCpu(): Model {
        val id = "analogmadness_cpu"
        val isDownloaded = Model.isModelDownloaded(context, id, false, requireFinished = true)
        return Model(
            id = id,
            name = "Analog Madness v7",
            description = context.getString(R.string.analogmadness_description),
            baseUrl = "",
            approximateSize = context.getString(R.string.size_sd_convert),
            isDownloaded = isDownloaded,
            codeDefaults = ModelConfig(
                prompt = "analog photo, film photography, best quality, masterpiece, realistic, 35mm film, grain, natural color, soft light, detailed, dslr",
                negativePrompt = "cartoon, anime, 3d render, digital art, lowres, bad anatomy, bad hands, text, error, missing fingers, cropped, worst quality, low quality, jpeg artifacts, signature, watermark, deformed, blurry",
            ),
            runOnCpu = true,
            convertSourceUrl = "digiplay/AnalogMadness-realistic-model-v7/resolve/main/analogMadness_v70.safetensors",
        )
    }

    // ---- et.10 batch: six additional unrestricted SD1.5 checkpoints ----
    // Raw fp16 checkpoints (~2.1-2.4 GB) downloaded with resume and converted
    // on device, so they run on CPU/GPU including 32-bit devices.
    private fun convertCpuModel(
        id: String,
        name: String,
        descRes: Int,
        url: String,
        defaultPrompt: String,
        defaultNegative: String,
        size: String? = null,
    ): Model {
        val isDownloaded =
            Model.isModelDownloaded(context, id, false, requireFinished = true)
        return Model(
            id = id,
            name = name,
            description = context.getString(descRes),
            baseUrl = "",
            approximateSize = size ?: context.getString(R.string.size_sd_convert),
            isDownloaded = isDownloaded,
            codeDefaults = ModelConfig(
                prompt = defaultPrompt,
                negativePrompt = defaultNegative,
            ),
            runOnCpu = true,
            convertSourceUrl = url,
        )
    }

    // A model the user added by pasting a link. Runs through the exact same
    // resumable download + on-device SD1.5 conversion path, and persists in the
    // url_models.json registry so it stays in the list across restarts.
    private fun createUrlImportModel(
        e: io.github.xororz.localdream.utils.UrlModelImport.Entry,
    ): Model {
        val isDownloaded =
            Model.isModelDownloaded(context, e.id, false, requireFinished = true)
        return Model(
            id = e.id,
            name = e.name,
            description = context.getString(R.string.url_imported_description),
            baseUrl = "",
            approximateSize = context.getString(R.string.size_link_convert),
            isDownloaded = isDownloaded,
            codeDefaults = ModelConfig(
                prompt = "masterpiece, best quality, highly detailed, sharp focus, 8k",
                negativePrompt = "lowres, bad anatomy, bad hands, missing fingers, extra digit, worst quality, low quality, jpeg artifacts, signature, watermark, deformed, blurry",
            ),
            runOnCpu = true,
            convertSourceUrl = e.url,
        )
    }

    private fun createJuggernautCpu(): Model = convertCpuModel(
        id = "juggernaut_cpu",
        name = "Juggernaut Final",
        descRes = R.string.juggernaut_description,
        url = "digiplay/Juggernaut_final/resolve/main/juggernaut_final.safetensors",
        defaultPrompt = "RAW photo, best quality, masterpiece, photorealistic, ultra detailed, 8k uhd, dslr, sharp focus, natural skin texture, soft lighting",
        defaultNegative = "cartoon, anime, drawing, 3d render, lowres, bad anatomy, bad hands, missing fingers, extra digit, worst quality, low quality, jpeg artifacts, signature, watermark, deformed, blurry",
    )

    // ---- et.12 batch: 32-bit-safe (<2^31 B), header-validated SD1.5 ----
    private fun createPhotonCpu(): Model = convertCpuModel(
        id = "photon_cpu",
        name = "Photon v1",
        descRes = R.string.photon_description,
        url = "digiplay/Photon_v1/resolve/main/photon_v1.safetensors",
        defaultPrompt = "RAW photo, best quality, masterpiece, photorealistic, ultra detailed skin texture, natural light, 8k uhd, dslr, sharp focus",
        defaultNegative = "cartoon, anime, drawing, painting, 3d render, lowres, bad anatomy, bad hands, missing fingers, extra digit, worst quality, low quality, jpeg artifacts, signature, watermark, deformed, blurry",
    )

    private fun createLemonCreamiCpu(): Model = convertCpuModel(
        id = "lemoncreami_cpu",
        name = "LemonCreami v1",
        descRes = R.string.lemoncreami_description,
        url = "digiplay/LemonCreami/resolve/main/lemoncreami_v10.safetensors",
        defaultPrompt = "masterpiece, best quality, 1girl, solo, creamy soft anime, detailed eyes, smooth shading, warm pastel color, sharp focus, 8k",
        defaultNegative = "lowres, bad anatomy, bad hands, missing fingers, extra fingers, poorly drawn face, realistic photo, harsh contrast, worst quality, low quality, jpeg artifacts, signature, watermark, blurry, deformed",
    )

    private fun createMeinaPastelCpu(): Model = convertCpuModel(
        id = "meinapastel_cpu",
        name = "MeinaPastel v3",
        descRes = R.string.meinapastel_description,
        url = "digiplay/MeinaPastel_v3/resolve/main/meinapastel_v3.safetensors",
        defaultPrompt = "masterpiece, best quality, 1girl, solo, soft pastel illustration, delicate lineart, detailed eyes, dreamy light, sharp focus, 8k",
        defaultNegative = "lowres, bad anatomy, bad hands, missing fingers, extra fingers, poorly drawn face, realistic photo, worst quality, low quality, jpeg artifacts, signature, watermark, blurry, deformed",
    )

    private fun createRealisianV5Cpu(): Model = convertCpuModel(
        id = "realisianv5_cpu",
        name = "Realisian v5",
        descRes = R.string.realisianv5_description,
        url = "digiplay/Realisian_v5/resolve/main/realisian_v50.safetensors",
        defaultPrompt = "RAW photo, best quality, masterpiece, photorealistic portrait, highly detailed skin, beautiful detailed eyes, 8k uhd, dslr, soft cinematic light, sharp focus",
        defaultNegative = "cartoon, anime, drawing, painting, 3d render, lowres, bad anatomy, bad hands, missing fingers, extra digit, worst quality, low quality, jpeg artifacts, signature, watermark, deformed, blurry",
    )

    private fun createCamelliaNsfwCpu(): Model = convertCpuModel(
        id = "camelliansfw_cpu",
        name = "CamelliaMix NSFW v1.1",
        descRes = R.string.camelliansfw_description,
        url = "digiplay/CamelliaMix_NSFW_diffusers_v1.1/resolve/main/camelliamixNSFW_v11.safetensors",
        defaultPrompt = "masterpiece, best quality, highly detailed, 2.5d, semi-realistic, sharp focus, cinematic lighting, 8k",
        defaultNegative = "lowres, bad anatomy, bad hands, missing fingers, extra fingers, poorly drawn face, worst quality, low quality, jpeg artifacts, signature, watermark, blurry, deformed",
    )

    private fun createDarkSushiCpu(): Model = convertCpuModel(
        id = "darksushi_cpu",
        name = "Dark Sushi 2.5D",
        descRes = R.string.darksushi_description,
        url = "digiplay/DarkSushi2.5D_v1/resolve/main/darkSushi25D25D_v10.safetensors",
        defaultPrompt = "masterpiece, best quality, 1girl, solo, highly detailed, 2.5d anime, vivid color, detailed eyes, sharp focus",
        defaultNegative = "lowres, bad anatomy, bad hands, missing fingers, extra fingers, poorly drawn face, realistic photo, worst quality, low quality, jpeg artifacts, signature, watermark, blurry, deformed",
    )

    private fun createBreakDomainCpu(): Model = convertCpuModel(
        id = "breakdomain_cpu",
        name = "BreakDomain Realistic R2333",
        descRes = R.string.breakdomain_description,
        url = "digiplay/breakdomainrealistic_R2333/resolve/main/breakdomainrealistic_R2333.safetensors",
        defaultPrompt = "masterpiece, best quality, 1girl, solo, detailed anime style, detailed eyes, clean lineart, vibrant, sharp focus",
        defaultNegative = "lowres, bad anatomy, bad hands, missing fingers, extra fingers, poorly drawn face, realistic photo, 3d render, worst quality, low quality, jpeg artifacts, signature, watermark, blurry, deformed",
    )

    private fun createHelloWorldCpu(): Model = convertCpuModel(
        id = "helloworld_cpu",
        name = "HelloWorld v3",
        descRes = R.string.helloworld_description,
        url = "digiplay/helloworld_v3/resolve/main/helloWorld_v3.safetensors",
        defaultPrompt = "masterpiece, best quality, highly detailed illustration, beautiful detailed eyes, soft color, detailed background, sharp focus, 8k",
        defaultNegative = "lowres, bad anatomy, bad hands, missing fingers, extra fingers, poorly drawn face, realistic photo, worst quality, low quality, jpeg artifacts, signature, watermark, blurry, deformed",
    )

    // ---- et.11 batch: six more unrestricted SD1.5 checkpoints ----
    private fun createAniRealityCpu(): Model = convertCpuModel(
        id = "anireality_cpu",
        name = "AniReality Mix v1",
        descRes = R.string.anireality_description,
        url = "digiplay/AniRealityMix_v1/resolve/main/anirealityMix_v1.safetensors",
        defaultPrompt = "masterpiece, best quality, 1girl, solo, 2.5d semi-realistic anime, detailed eyes, smooth skin, cinematic light, sharp focus, 8k",
        defaultNegative = "lowres, bad anatomy, bad hands, missing fingers, extra fingers, poorly drawn face, worst quality, low quality, jpeg artifacts, signature, watermark, blurry, deformed",
    )

    private fun createChillyMixCpu(): Model = convertCpuModel(
        id = "chillymix_cpu",
        name = "ChillyMix v1",
        descRes = R.string.chillymix_description,
        url = "digiplay/ChillyMix_v1/resolve/main/chillymix_V1Fp16.safetensors",
        defaultPrompt = "RAW photo, best quality, masterpiece, photorealistic portrait, ultra detailed skin, beautiful detailed eyes, 8k uhd, dslr, soft cinematic light, sharp focus",
        defaultNegative = "cartoon, anime, drawing, painting, lowres, bad anatomy, bad hands, missing fingers, extra digit, worst quality, low quality, jpeg artifacts, signature, watermark, deformed, blurry",
    )

    private fun createTwoKwiCpu(): Model = convertCpuModel(
        id = "twokwi_cpu",
        name = "2-KWI v1.0",
        descRes = R.string.twokwi_description,
        url = "digiplay/2-KWI/resolve/main/2KWI_v10.safetensors",
        defaultPrompt = "RAW photo, best quality, masterpiece, photorealistic, natural portrait, natural skin texture, detailed eyes, 8k uhd, dslr, soft lighting, sharp focus, film grain",
        defaultNegative = "cartoon, anime, drawing, 3d render, lowres, bad anatomy, bad hands, missing fingers, extra digit, worst quality, low quality, jpeg artifacts, signature, watermark, deformed, blurry",
    )

    private fun createBeautifulArtCpu(): Model = convertCpuModel(
        id = "beautifulart_cpu",
        name = "BeautifulArt v1",
        descRes = R.string.beautifulart_description,
        url = "digiplay/BeautifulArt_v1/resolve/main/beautifulArt_v10.safetensors",
        defaultPrompt = "masterpiece, best quality, fine art, highly detailed, elegant, dramatic lighting, professional photography, painterly, sharp focus, 8k",
        defaultNegative = "lowres, bad anatomy, bad hands, missing fingers, extra fingers, poorly drawn face, worst quality, low quality, jpeg artifacts, signature, watermark, blurry, deformed",
    )

    private fun createAbyssOrangeCpu(): Model = convertCpuModel(
        id = "abyssomix_cpu",
        name = "AbyssOrangeMix2 Hard",
        descRes = R.string.abyssomix_description,
        url = "digiplay/abyssorangemix2_Hard/resolve/main/abyssorangemix2_Hard.safetensors",
        defaultPrompt = "masterpiece, best quality, 1girl, solo, anime style, highly detailed, vibrant color, detailed eyes, sharp focus, 8k",
        defaultNegative = "lowres, bad anatomy, bad hands, missing fingers, extra fingers, poorly drawn face, realistic photo, worst quality, low quality, jpeg artifacts, signature, watermark, blurry, deformed",
    )

    private fun createHassakuCpu(): Model = convertCpuModel(
        id = "hassaku_cpu",
        name = "Hassaku v1.3",
        descRes = R.string.hassaku_description,
        url = "digiplay/Hassaku_1.3/resolve/main/hassakuHentaiModel_v13.safetensors",
        defaultPrompt = "masterpiece, best quality, 1girl, solo, anime illustration, detailed eyes and face, soft shading, cinematic light, sharp focus, 8k",
        defaultNegative = "lowres, bad anatomy, bad hands, missing fingers, extra fingers, poorly drawn face, realistic photo, worst quality, low quality, jpeg artifacts, signature, watermark, blurry, deformed",
    )

    private fun createColorfulCpu(): Model = convertCpuModel(
        id = "colorful_cpu",
        name = "Colorful v3.1",
        descRes = R.string.colorful_description,
        url = "digiplay/Colorful_v3.1/resolve/main/colorful_v31.safetensors",
        defaultPrompt = "masterpiece, best quality, 1girl, solo, vivid colorful anime, rich color, detailed eyes, clean lineart, sharp focus, 8k",
        defaultNegative = "lowres, bad anatomy, bad hands, missing fingers, extra fingers, poorly drawn face, realistic photo, dull color, worst quality, low quality, jpeg artifacts, signature, watermark, blurry, deformed",
    )

    private fun createCosplayMixCpu(): Model = convertCpuModel(
        id = "cosplaymix_cpu",
        name = "CosplayMix v2",
        descRes = R.string.cosplaymix_description,
        url = "digiplay/CosplayMix_v2/resolve/main/cosplaymix_v20.safetensors",
        defaultPrompt = "RAW photo, best quality, masterpiece, photorealistic cosplay portrait, detailed fabric and skin, natural light, 8k uhd, dslr, sharp focus",
        defaultNegative = "cartoon, anime, drawing, painting, 3d render, lowres, bad anatomy, bad hands, missing fingers, extra digit, worst quality, low quality, jpeg artifacts, signature, watermark, deformed, blurry",
    )

    private fun createNextPhotoCpu(): Model = convertCpuModel(
        id = "nextphoto_cpu",
        name = "NextPhoto v3",
        descRes = R.string.nextphoto_description,
        url = "digiplay/NextPhoto_v3/resolve/main/nextphoto_v30.safetensors",
        defaultPrompt = "RAW photo, best quality, masterpiece, photorealistic, natural skin texture, detailed eyes, candid photography, 8k uhd, dslr, soft daylight, sharp focus, film grain",
        defaultNegative = "cartoon, anime, drawing, painting, 3d render, lowres, bad anatomy, bad hands, missing fingers, extra digit, worst quality, low quality, jpeg artifacts, signature, watermark, deformed, blurry",
    )

    // ---- et.14 batch: nine more 32-bit-safe (<2^31 B), header-validated SD1.5 ----
    private fun createMajicV7Cpu(): Model = convertCpuModel(
        id = "majicv7_cpu",
        name = "MajicMix Realistic v7",
        descRes = R.string.majicv7_description,
        url = "digiplay/majicMIX_realistic_v7/resolve/main/majicmixRealistic_v7.safetensors",
        defaultPrompt = "RAW photo, best quality, masterpiece, photorealistic, highly detailed skin, beautiful detailed eyes, 8k uhd, dslr, soft cinematic light, sharp focus",
        defaultNegative = "cartoon, anime, drawing, painting, 3d render, lowres, bad anatomy, bad hands, missing fingers, extra digit, worst quality, low quality, jpeg artifacts, signature, watermark, deformed, blurry",
    )

    private fun createEpiGoddessCpu(): Model = convertCpuModel(
        id = "epigoddess_cpu",
        name = "epiCPhotoGasm 2.5D Goddess",
        descRes = R.string.epigoddess_description,
        url = "digiplay/epi_2.5Dphotogodess_diffusers/resolve/main/epi25dphotogodess_v3.safetensors",
        defaultPrompt = "masterpiece, best quality, 1girl, solo, semi-realistic 2.5d, beautiful detailed face and eyes, soft cinematic lighting, sharp focus, 8k",
        defaultNegative = "lowres, bad anatomy, bad hands, missing fingers, extra fingers, poorly drawn face, realistic photo, harsh contrast, worst quality, low quality, jpeg artifacts, signature, watermark, blurry, deformed",
    )

    private fun createRevAnimatedCpu(): Model = convertCpuModel(
        id = "revanimated_cpu",
        name = "ReV Animated v1.1",
        descRes = R.string.revanimated_description,
        url = "digiplay/revAnimated_v11/resolve/main/revAnimated_v11_fp16.safetensors",
        defaultPrompt = "masterpiece, best quality, highly detailed illustration, vibrant color, cinematic lighting, sharp focus, 8k, fantasy concept art",
        defaultNegative = "lowres, bad anatomy, bad hands, missing fingers, extra fingers, poorly drawn face, photorealistic, 3d render, worst quality, low quality, jpeg artifacts, signature, watermark, blurry, deformed",
    )

    private fun createMeinaV11Cpu(): Model = convertCpuModel(
        id = "meinav11_cpu",
        name = "MeinaMix v11",
        descRes = R.string.meinav11_description,
        url = "digiplay/MeinaMix_v11/resolve/main/meinamix_meinaV11.safetensors",
        defaultPrompt = "masterpiece, best quality, 1girl, solo, beautiful anime illustration, detailed eyes, soft light, sharp focus, 8k",
        defaultNegative = "lowres, bad anatomy, bad hands, missing fingers, extra fingers, poorly drawn face, realistic photo, worst quality, low quality, jpeg artifacts, signature, watermark, blurry, deformed",
    )

    private fun createAingV9Cpu(): Model = convertCpuModel(
        id = "aingv9_cpu",
        name = "AingDiffusion v9",
        descRes = R.string.aingv9_description,
        url = "digiplay/AingDiffusion9/resolve/main/aingdiffusion_v90.safetensors",
        defaultPrompt = "masterpiece, best quality, 1girl, solo, detailed anime style, clean lineart, beautiful eyes, vivid color, sharp focus, 8k",
        defaultNegative = "lowres, bad anatomy, bad hands, missing fingers, extra fingers, poorly drawn face, realistic photo, worst quality, low quality, jpeg artifacts, signature, watermark, blurry, deformed",
    )

    private fun createLemonTeaCpu(): Model = convertCpuModel(
        id = "lemontea_cpu",
        name = "LemonTea Painterly 2.5D",
        descRes = R.string.lemontea_description,
        url = "digiplay/LemonTea2.5D/resolve/main/lemonteaMixPainterly2_v1.safetensors",
        defaultPrompt = "masterpiece, best quality, 1girl, solo, painterly 2.5d illustration, soft brush texture, detailed eyes, warm light, sharp focus, 8k",
        defaultNegative = "lowres, bad anatomy, bad hands, missing fingers, extra fingers, poorly drawn face, photorealistic, worst quality, low quality, jpeg artifacts, signature, watermark, blurry, deformed",
    )

    private fun createCounterMixV2Cpu(): Model = convertCpuModel(
        id = "countermixv2_cpu",
        name = "CounterMix v2",
        descRes = R.string.countermixv2_description,
        url = "digiplay/CounterMix_v2/resolve/main/countermix_v20.safetensors",
        defaultPrompt = "masterpiece, best quality, highly detailed, 2.5d semi-realistic anime, beautiful detailed eyes, cinematic lighting, sharp focus, 8k",
        defaultNegative = "lowres, bad anatomy, bad hands, missing fingers, extra fingers, poorly drawn face, realistic photo, worst quality, low quality, jpeg artifacts, signature, watermark, blurry, deformed",
    )

    private fun createBeautifulFantasyCpu(): Model = convertCpuModel(
        id = "beautifantasy_cpu",
        name = "Beautiful Fantasy Real Mix",
        descRes = R.string.beautifantasy_description,
        url = "digiplay/BeautifulFantasyRealMix_diffusers/resolve/main/beautifulfantasyreal_v10.safetensors",
        defaultPrompt = "masterpiece, best quality, semi-realistic fantasy portrait, beautiful detailed face and eyes, dramatic light, intricate details, sharp focus, 8k",
        defaultNegative = "lowres, bad anatomy, bad hands, missing fingers, extra fingers, poorly drawn face, worst quality, low quality, jpeg artifacts, signature, watermark, blurry, deformed",
    )

    private fun createEtherRealLuxCpu(): Model = convertCpuModel(
        id = "ethereallux_cpu",
        name = "EtherReal Mix LUX2",
        descRes = R.string.ethereallux_description,
        url = "digiplay/EtherRealMix_LUX2/resolve/main/etherRealMix_etherRealMixLUX2.safetensors",
        defaultPrompt = "masterpiece, best quality, highly detailed, elegant semi-realistic anime, luminous skin, beautiful eyes, soft cinematic light, sharp focus, 8k",
        defaultNegative = "lowres, bad anatomy, bad hands, missing fingers, extra fingers, poorly drawn face, photorealistic, worst quality, low quality, jpeg artifacts, signature, watermark, blurry, deformed",
    )

    suspend fun refreshModelState(modelId: String) {
        refreshMutex.withLock {
            val current = models
            models = withContext(Dispatchers.IO) {
                current.map { model ->
                    if (model.id == modelId) {
                        val isDownloaded = if (model.isDit) {
                            Model.isDitPackageDownloaded(
                                context,
                                modelId,
                                model.ditKind,
                                model.packageFiles,
                            )
                        } else {
                            Model.isModelDownloaded(
                                context,
                                modelId,
                                model.isCustom,
                                requireFinished = model.convertSourceUrl.isNotEmpty(),
                            )
                        }
                        applyConfigDefaults(
                            model.copy(isDownloaded = isDownloaded),
                        )
                    } else {
                        model
                    }
                }
            }
        }
    }

    suspend fun refreshAllModels() {
        refreshMutex.withLock {
            baseUrl = generationPreferences.getBaseUrl()
            models = withContext(Dispatchers.IO) { initializeModels() }
            isLoaded = true
        }
    }

    companion object {
        // IDs reserved by built-in models and upscalers. Custom model
        // directories that match one of these would collide with the built-in
        // entry on disk and in the UI list, so they are skipped during scan.
        // Keep in sync with the create*Model() functions and UpscalerRepository.
        private val RESERVED_MODEL_IDS = setOf(
            // SDXL (NPU)
            "illustrious_v16", "illustrious_v16_dmd2",
            "cyber_realistic_v10", "cyber_realistic_v10_dmd2",
            // SD 1.5 NPU
            "anythingv5", "qteamix", "cuteyukimix", "absolutereality", "chilloutmix",
            // SD 1.5 CPU
            "anythingv5cpu", "qteamixcpu", "cuteyukimixcpu",
            "absoluterealitycpu", "chilloutmixcpu",
            // SD 1.5 CPU, raw safetensors downloaded then converted on device
            "realisticvision_cpu", "counterfeit_cpu", "dreamshaper_cpu",
            "majicmix_cpu", "analogmadness_cpu",
            // et.10 batch
            "juggernaut_cpu", "camelliansfw_cpu",
            "darksushi_cpu", "breakdomain_cpu", "helloworld_cpu",
            // et.11 batch
            "anireality_cpu", "chillymix_cpu", "twokwi_cpu", "beautifulart_cpu",
            // et.12 batch (32-bit-safe); retired: fantasytime_cpu, coffeemix_cpu, lemonpastel_cpu
            "photon_cpu", "lemoncreami_cpu", "meinapastel_cpu", "realisianv5_cpu",
            "abyssomix_cpu", "hassaku_cpu", "colorful_cpu", "cosplaymix_cpu", "nextphoto_cpu",
            // et.14 batch (32-bit-safe, header-validated)
            "majicv7_cpu", "epigoddess_cpu", "revanimated_cpu", "meinav11_cpu",
            "aingv9_cpu", "lemontea_cpu", "countermixv2_cpu",
            "beautifantasy_cpu", "ethereallux_cpu",
            // DiT
            "z_image_turbo", "flux2_klein_4b", "qwen_image_2_1", "qwen_image_2_1_uc",
        )

        fun isReservedModelId(id: String): Boolean = id in RESERVED_MODEL_IDS

        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var instance: ModelRepository? = null

        fun getInstance(context: Context): ModelRepository = instance ?: synchronized(this) {
            instance ?: ModelRepository(context.applicationContext).also { instance = it }
        }
    }
}
