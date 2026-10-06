package etc.github.ai.chat.ui

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import etc.github.ai.chat.Settings
import etc.github.ai.chat.SettingsStore
import etc.github.ai.chat.api.ServerApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

sealed interface ChatMsg {
    val id: Long

    data class User(
        override val id: Long,
        val text: String,
        /** Relative file path of the picked reference image, or null. */
        val imagePath: String?,
        /** In-memory preview bitmap for immediate rendering. */
        val preview: Bitmap?,
    ) : ChatMsg

    data class Ai(
        override val id: Long,
        /** Relative file paths of returned images. */
        val imagePaths: List<String>,
        /** In-memory decoded bitmaps. */
        val images: List<Bitmap>,
        /** Non-null when the request failed. */
        val error: String?,
        /** True while the synchronous server request is in flight. */
        val pending: Boolean = false,
    ) : ChatMsg
}

class ChatViewModel(app: Application) : AndroidViewModel(app) {
    private val store = SettingsStore(app)
    private val session = SessionStore(app)

    var settings by mutableStateOf(store.load())
        private set

    // Snapshot-state list: mutations recompose LazyColumn. (v1.0.1 used a plain
    // mutableList, so added messages never rendered — that was Bug12.)
    val messages = mutableStateListOf<ChatMsg>()
    var loaded by mutableStateOf(false)
        private set

    var input by mutableStateOf("")
        private set
    var pendingImageUri: Uri? by mutableStateOf(null)
        private set
    var pendingImagePreview: Bitmap? by mutableStateOf(null)
        private set
    var denoising by mutableStateOf(0.6f)
        private set
    var generating by mutableStateOf(false)
        private set
    var showSettings by mutableStateOf(false)
        private set
    var needSetup by mutableStateOf(false)
        private set

    // ---- LAN scan state (used by the settings screen) ---------------------
    var scanning by mutableStateOf(false)
        private set
    var scanProgress by mutableStateOf("")
        private set
    var scanFound by mutableStateOf<List<etc.github.ai.chat.api.LanScanner.Found>>(emptyList())
        private set
    private var scanCancelled = false

    init {
        // restore history on IO: JSON + decoded image files
        viewModelScope.launch {
            val restored = withContext(Dispatchers.IO) {
                session.load().mapNotNull { s ->
                    when (s.role) {
                        "user" -> ChatMsg.User(
                            s.id, s.text, s.userImage,
                            s.userImage?.let { session.decode(it) },
                        )
                        "ai" -> ChatMsg.Ai(
                            s.id, s.aiImages,
                            s.aiImages.mapNotNull { session.decode(it) },
                            s.error, pending = false,
                        )
                        else -> null
                    }
                }
            }
            messages.clear()
            messages.addAll(restored)
            loaded = true
        }
    }

    fun onInputChange(v: String) { input = v }
    fun onDenoisingChange(v: Float) { denoising = v }
    fun openSettings() { showSettings = true }
    fun closeSettings() { showSettings = false }
    fun dismissNeedSetup() { needSetup = false }
    fun goSetupFromNeedSetup() { needSetup = false; showSettings = true }
    fun clearPickedImage() {
        pendingImageUri = null
        pendingImagePreview = null
    }

    fun updateSettings(s: Settings) {
        settings = s
        store.save(s)
    }

    fun setPickedImage(uri: Uri?, preview: Bitmap?) {
        pendingImageUri = uri
        pendingImagePreview = preview
    }

    private fun persistAsync() {
        val snapshot = messages.toList()
        viewModelScope.launch(Dispatchers.IO) { session.persist(snapshot) }
    }

    private fun batchSizeOrNull(): Int? =
        settings.batchSize.trim().toIntOrNull()?.takeIf { it > 0 }

    /**
     * Bug12 fix: the user bubble AND the loading AI bubble are written to the
     * observable list synchronously, BEFORE the network call, so the UI reacts
     * instantly. Success swaps in images; failure swaps in a Chinese error and
     * leaves a retry button. Nothing is ever swallowed silently.
     */
    fun send() {
        if (generating) return
        val prompt = input.trim()
        if (prompt.isEmpty() && pendingImageUri == null) return
        if (settings.baseUrl.isBlank() || settings.apiKey.isBlank()) {
            needSetup = true
            return
        }

        val imgPreview = pendingImagePreview
        val imgUri = pendingImageUri
        val s = settings
        val den = denoising.toDouble()
        val batch = batchSizeOrNull()

        val now = System.currentTimeMillis()
        // 1) optimistic UI: both bubbles appear immediately
        messages.add(ChatMsg.User(now, prompt, null, imgPreview))
        messages.add(ChatMsg.Ai(now + 1, emptyList(), emptyList(), null, pending = true))

        // clear composer (user bubble stays on screen)
        input = ""
        pendingImageUri = null
        pendingImagePreview = null
        generating = true
        persistAsync()

        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    // persist the picked preview image first (if any)
                    val userImgPath = imgPreview?.let { session.writeJpeg("u_$now", it) }
                    if (userImgPath != null) {
                        val idx = messages.indexOfLast { it is ChatMsg.User && it.id == now }
                        if (idx >= 0) {
                            messages[idx] = (messages[idx] as ChatMsg.User).copy(imagePath = userImgPath)
                        }
                    }
                    if (imgUri != null) {
                        val dataUri = readImageAsDataUri(imgUri)
                        ServerApi.img2img(
                            baseRaw = s.baseUrl,
                            apiKey = s.apiKey,
                            prompt = prompt.ifBlank { " " },
                            negativePrompt = s.negativePrompt,
                            initImageDataUri = dataUri,
                            denoising = den,
                            batchSize = batch,
                        )
                    } else {
                        ServerApi.txt2img(
                            baseRaw = s.baseUrl,
                            apiKey = s.apiKey,
                            prompt = prompt,
                            negativePrompt = s.negativePrompt,
                            batchSize = batch,
                        )
                    }
                }
            }

            val aiIdx = messages.indexOfLast { it is ChatMsg.Ai && it.id == now + 1 }
            result.fold(
                onSuccess = { imgs ->
                    val paths = imgs.mapIndexed { i, b ->
                        session.writePng("ai_${now + 1}_$i", b)
                    }
                    if (aiIdx >= 0) {
                        messages[aiIdx] = ChatMsg.Ai(now + 1, paths, imgs, null, pending = false)
                    }
                },
                onFailure = { e ->
                    if (aiIdx >= 0) {
                        messages[aiIdx] = ChatMsg.Ai(
                            now + 1, emptyList(), emptyList(),
                            e.message ?: "未知网络错误", pending = false,
                        )
                    }
                },
            )
            generating = false
            persistAsync()
        }
    }

    /** Retry a failed AI bubble: reuse the preceding user message (text + image). */
    fun retryError(aiMsg: ChatMsg.Ai) {
        if (generating) return
        val idx = messages.indexOfFirst { it.id == aiMsg.id }
        if (idx <= 0) return
        val userMsg = messages[idx - 1] as? ChatMsg.User ?: return
        val s = settings
        val den = denoising.toDouble()
        val batch = batchSizeOrNull()

        messages[idx] = aiMsg.copy(pending = true, error = null, images = emptyList(), imagePaths = emptyList())
        generating = true
        persistAsync()

        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    if (userMsg.imagePath != null) {
                        val bmp = session.decode(userMsg.imagePath)
                            ?: throw IllegalStateException("参考图读取失败")
                        val baos = ByteArrayOutputStream()
                        bmp.compress(Bitmap.CompressFormat.JPEG, 85, baos)
                        val dataUri = "data:image/jpeg;base64," +
                            Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
                        ServerApi.img2img(
                            baseRaw = s.baseUrl, apiKey = s.apiKey,
                            prompt = userMsg.text.ifBlank { " " },
                            negativePrompt = s.negativePrompt,
                            initImageDataUri = dataUri,
                            denoising = den, batchSize = batch,
                        )
                    } else {
                        ServerApi.txt2img(
                            baseRaw = s.baseUrl, apiKey = s.apiKey,
                            prompt = userMsg.text,
                            negativePrompt = s.negativePrompt,
                            batchSize = batch,
                        )
                    }
                }
            }
            result.fold(
                onSuccess = { imgs ->
                    val paths = imgs.mapIndexed { i, b ->
                        session.writePng("ai_${aiMsg.id}_$i", b)
                    }
                    messages[idx] = ChatMsg.Ai(aiMsg.id, paths, imgs, null, pending = false)
                },
                onFailure = { e ->
                    messages[idx] = ChatMsg.Ai(
                        aiMsg.id, emptyList(), emptyList(),
                        e.message ?: "未知网络错误", pending = false,
                    )
                },
            )
            generating = false
            persistAsync()
        }
    }

    fun clearConversation() {
        messages.clear()
        viewModelScope.launch(Dispatchers.IO) { session.clear() }
    }

    // ---- LAN scan ----------------------------------------------------------
    fun startLanScan() {
        if (scanning) return
        scanning = true
        scanCancelled = false
        scanFound = emptyList()
        scanProgress = "正在扫描 0/0"
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    etc.github.ai.chat.api.LanScanner.scan(
                        onProgress = { done, total -> scanProgress = "正在扫描 $done/$total" },
                        isCancelled = { scanCancelled },
                    )
                }
            }
            scanning = false
            r.onSuccess { scanFound = it }
            scanProgress = if (scanFound.isEmpty()) "未发现服务端" else "发现 ${scanFound.size} 台"
        }
    }

    fun cancelLanScan() { scanCancelled = true }

    fun fillAddress(base: String) { updateSettings(settings.copy(baseUrl = base)) }

    /** Recompress the picked image into a base64 data URI, max 1024px, JPEG. */
    private fun readImageAsDataUri(uri: Uri): String {
        val resolver = getApplication<Application>().contentResolver
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri).use { input ->
            android.graphics.BitmapFactory.decodeStream(input, null, bounds)
        }
        var sample = 1
        val maxDim = 1024
        while (bounds.outWidth / sample > maxDim || bounds.outHeight / sample > maxDim) sample *= 2
        val bmp = resolver.openInputStream(uri).use {
            android.graphics.BitmapFactory.decodeStream(
                it, null,
                android.graphics.BitmapFactory.Options().apply { inSampleSize = sample },
            )
        } ?: throw IllegalStateException("无法读取所选图片")
        val baos = ByteArrayOutputStream()
        bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, baos)
        return "data:image/jpeg;base64," +
            Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
    }
}
