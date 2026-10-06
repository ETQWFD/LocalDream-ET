package etc.github.ai.chat.ui

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.util.Base64
import androidx.compose.runtime.getValue
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
        /** Small preview of the picked reference image, or null. */
        val imagePreview: Bitmap?,
    ) : ChatMsg

    data class Ai(
        override val id: Long,
        val images: List<Bitmap>,
        /** Non-null when the request failed — shown red with retry hint. */
        val error: String?,
        /** True while the synchronous server request is in flight. */
        val pending: Boolean = false,
    ) : ChatMsg
}

class ChatViewModel(app: Application) : AndroidViewModel(app) {
    private val store = SettingsStore(app)
    var settings by mutableStateOf(store.load())
        private set

    val messages = mutableListOf<ChatMsg>()
    var messagesVersion by mutableStateOf(0)
        private set

    var input by mutableStateOf("")
        private set
    var pendingImageUri: Uri? by mutableStateOf(null)
        private set
    /** Decoded small preview of the picked image, for the chat bubble. */
    var pendingImagePreview: Bitmap? by mutableStateOf(null)
        private set
    var denoising by mutableStateOf(0.6f)
        private set
    var generating by mutableStateOf(false)
        private set
    var showSettings by mutableStateOf(false)
        private set
    /** The user message that owns the latest "pending" AI bubble, for retry. */
    var lastErrorUserText by mutableStateOf<String?>(null)
        private set
    /** Shown when user hits send without server address / API key configured. */
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
                        onProgress = { done, total ->
                            scanProgress = "正在扫描 $done/$total"
                        },
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

    /** User tapped a discovered server row: write it into settings. */
    fun fillAddress(base: String) {
        updateSettings(settings.copy(baseUrl = base))
    }

    fun updateSettings(s: Settings) {
        settings = s
        store.save(s)
    }

    fun setPickedImage(uri: Uri?, preview: Bitmap?) {
        pendingImageUri = uri
        pendingImagePreview = preview
    }

    private fun bump() { messagesVersion++ }

    private fun batchSizeOrNull(): Int? =
        settings.batchSize.trim().toIntOrNull()?.takeIf { it > 0 }

    fun send() {
        if (generating) return
        val prompt = input.trim()
        val picked = pendingImageUri
        if (prompt.isEmpty() && picked == null) return
        // no silent dead button: guide to settings instead.
        if (settings.baseUrl.isBlank() || settings.apiKey.isBlank()) {
            needSetup = true
            return
        }

        val imgPreview = pendingImagePreview
        val userMsg = ChatMsg.User(System.currentTimeMillis(), prompt, imgPreview)
        messages.add(userMsg); bump()

        val aiPending = ChatMsg.Ai(System.currentTimeMillis() + 1, emptyList(), null, pending = true)
        messages.add(aiPending); bump()

        val imgUri = pendingImageUri
        val s = settings
        val den = denoising.toDouble()
        val batch = batchSizeOrNull()

        // clear composer
        input = ""
        pendingImageUri = null
        pendingImagePreview = null

        generating = true
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
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
            // replace the pending AI bubble
            val idx = messages.indexOf(aiPending)
            val replacement = result.fold(
                onSuccess = { imgs ->
                    lastErrorUserText = null
                    ChatMsg.Ai(aiPending.id, imgs, null)
                },
                onFailure = { e ->
                    lastErrorUserText = prompt
                    ChatMsg.Ai(aiPending.id, emptyList(), e.message ?: "未知网络错误")
                },
            )
            if (idx >= 0) messages[idx] = replacement else messages.add(replacement)
            generating = false
            bump()
        }
    }

    /** Re-run the last failed prompt (text only). */
    fun retryLast() {
        val text = lastErrorUserText ?: return
        // remove the error bubble
        val errIdx = messages.indexOfLast { it is ChatMsg.Ai && it.error != null }
        if (errIdx >= 0) messages.removeAt(errIdx)
        input = text
        send()
    }

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
        val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = resolver.openInputStream(uri).use {
            android.graphics.BitmapFactory.decodeStream(it, null, opts)
        } ?: throw IllegalStateException("无法读取所选图片")
        val baos = ByteArrayOutputStream()
        bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, baos)
        return "data:image/jpeg;base64," +
            Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
    }
}
