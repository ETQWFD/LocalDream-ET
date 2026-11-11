package io.github.xororz.localdream.ui.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import coil.compose.AsyncImage
import io.github.xororz.localdream.chat.ChatIdentity
import io.github.xororz.localdream.chat.ChatKind
import io.github.xororz.localdream.chat.ChatMessage
import io.github.xororz.localdream.chat.ChatMute
import io.github.xororz.localdream.chat.ChatPolicy
import io.github.xororz.localdream.chat.ChatPolicyRepo
import io.github.xororz.localdream.chat.ChatRepository
import io.github.xororz.localdream.chat.ChatTime
import io.github.xororz.localdream.chat.ChatWindow
import io.github.xororz.localdream.chat.VoiceRecorder
import io.github.xororz.localdream.service.SharedRepos
import io.github.xororz.localdream.cloud.CloudConfig
import io.github.xororz.localdream.cloud.SecureTokenStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Public chat, WeChat-style bubbles:
 *  - own messages on the right with the green WeChat bubble colour,
 *  - everyone else on the left on the surface colour,
 *  - text / image / short video / voice, all real uploads to the public repo.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { SecureTokenStore(ctx) }
    val meLogin = remember { store.state(CloudConfig.Provider.GITHUB)?.login }
    val meAvatar = remember { store.state(CloudConfig.Provider.GITHUB)?.avatarUrl }

    var messages by remember { mutableStateOf<List<ChatMessage>>(emptyList()) }
    val chatListState = rememberLazyListState()
    var loading by remember { mutableStateOf(true) }
    var input by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var atPicker by remember { mutableStateOf(false) }
    var mentionMessage by remember { mutableStateOf<ChatMessage?>(null) }
    var lastMentionTs by remember {
        mutableStateOf(ctx.getSharedPreferences("chat_prefs", Context.MODE_PRIVATE).getLong("last_mention_ts", 0L))
    }
    var profileFor by remember { mutableStateOf<String?>(null) }
    var profileData by remember { mutableStateOf<io.github.xororz.localdream.chat.ChatProfile?>(null) }
    var editingProfile by remember { mutableStateOf<io.github.xororz.localdream.chat.ChatProfile?>(null) }
    var viewerImages by remember { mutableStateOf<List<String>>(emptyList()) }
    var videoUrls by remember { mutableStateOf<List<String>>(emptyList()) }
    var localPreview by remember { mutableStateOf<Bitmap?>(null) }

    val recorder = remember { VoiceRecorder(ctx) }
    var recording by remember { mutableStateOf(false) }
    var recordSeconds by remember { mutableIntStateOf(0) }
    var uploadingVoice by remember { mutableStateOf(false) }
    var uploadProgress by remember { mutableFloatStateOf(-1f) }
    var pendingVideo by remember { mutableStateOf<String?>(null) }
    var policy by remember { mutableStateOf(ChatPolicy()) }
    var actionFor by remember { mutableStateOf<ChatMessage?>(null) }
    var webUrl by remember { mutableStateOf<String?>(null) }

    var playingKey by remember { mutableStateOf<String?>(null) }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }

    fun stopPlayback() {
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        playingKey = null
    }

    DisposableEffect(Unit) {
        onDispose {
            stopPlayback()
            recorder.cancel()
        }
    }

    fun refresh() {
        scope.launch {
            loading = true
            messages = ChatWindow.recent(ChatRepository.load(ctx))
            policy = ChatPolicyRepo.load()
            loading = false
        }
    }

    // Progress callbacks arrive on an IO thread and can be very frequent
    // (one per 32 KB); only push a new value when the whole percent changes.
    var lastPct = -1
    fun publishProgress(p: Float) {
        val pct = (p * 100).toInt().coerceIn(0, 100)
        if (pct != lastPct) {
            lastPct = pct
            uploadProgress = p
        }
    }

    /** Returns true when the signed-in user is currently muted. */
    fun blockedByMute(): Boolean {
        val remain = policy.muteRemaining(meLogin)
        if (remain > 0) {
            status = "你已被禁言，剩余 ${ChatTime.duration(remain.toInt())}"
            return true
        }
        return false
    }

    LaunchedEffect(Unit) {
        // Make sure the shared repos/files exist before we read or write.
        SharedRepos.ensure(ctx)
        refresh()
    }

    // Everyone should see new messages without pressing refresh: poll gently.
    LaunchedEffect(Unit) {
        var tick = 0
        while (true) {
            delay(3_000)
            if (!sending) {
                val loaded = withContext(Dispatchers.IO) { ChatRepository.load(ctx) }
                if (loaded.isNotEmpty()) {
                    messages = ChatWindow.recent(loaded)
                    val newest = loaded.maxOfOrNull { it.ts } ?: 0L
                    if (lastMentionTs == 0L) {
                        lastMentionTs = newest
                        ctx.getSharedPreferences("chat_prefs", Context.MODE_PRIVATE)
                            .edit().putLong("last_mention_ts", newest).apply()
                    } else {
                        val me = meLogin
                        val mention = loaded.lastOrNull {
                            it.ts > lastMentionTs && !ChatIdentity.isMine(it.author, me) &&
                                (it.text.contains("@全体人员") || it.text.contains("@全体") ||
                                    (me != null && it.text.contains("@$me")))
                        }
                        if (mention != null) mentionMessage = mention
                        lastMentionTs = maxOf(lastMentionTs, newest)
                        ctx.getSharedPreferences("chat_prefs", Context.MODE_PRIVATE)
                            .edit().putLong("last_mention_ts", lastMentionTs).apply()
                    }
                }
            }
            // Pick up admin/mute changes made in the backend without a restart.
            if (tick % 10 == 0) {
                val fresh = withContext(Dispatchers.IO) { ChatPolicyRepo.load() }
                if (fresh != null) policy = fresh
            }
            tick++
        }
    }

    LaunchedEffect(recording) {
        while (recording) {
            delay(1000)
            if (recording) recordSeconds++
        }
    }

    fun post(message: ChatMessage, clearInput: Boolean = true) {
        sending = true
        status = ""
        scope.launch {
            val result = ChatRepository.send(ctx, message)
            sending = false
            result.onSuccess {
                messages = messages + message
                // Media sends must not eat the text the user is still drafting.
                if (clearInput) input = ""
                refresh()
            }.onFailure {
                status = it.message ?: "发送失败"
            }
        }
    }

    fun sendText() {
        val text = input.trim()
        if (text.isEmpty()) return
        val author = meLogin ?: ""
        if (author.isBlank()) {
            status = "请先在设置里登录 GitHub，才能公开发言"
            return
        }
        if (blockedByMute()) return
        val wantsAll = text.contains("@全体人员") || text.contains("@全体") || text.contains("@all")
        if (wantsAll && !policy.isAdmin(meLogin)) {
            status = "只有管理员可以 @全体人员"
            return
        }
        post(
            ChatMessage(
                author = author,
                text = text,
                ts = nowSeconds(),
                kind = ChatKind.TEXT,
                avatar = meAvatar,
            ),
        )
    }

    fun sendImage(uri: Uri) {
        val author = meLogin ?: ""
        if (author.isBlank()) {
            status = "请先在设置里登录 GitHub，才能发图片"
            return
        }
        if (blockedByMute()) return
        sending = true
        status = "正在压缩并上传图片…"
        scope.launch {
            val bytes = ChatRepository.compressImage(ctx, uri)
            if (bytes == null) {
                sending = false
                localPreview = null
                status = "图片读取失败，换一张试试"
                return@launch
            }
            uploadProgress = 0f
            val up = ChatRepository.uploadMedia(ctx, bytes, "jpg", "img", onProgress = { publishProgress(it) })
            uploadProgress = -1f
            if (up.raw == null) {
                sending = false
                localPreview = null
                status = up.error.ifBlank { "图片上传失败" }
                return@launch
            }
            post(
                ChatMessage(
                    author = author,
                    text = "",
                    ts = nowSeconds(),
                    kind = ChatKind.IMAGE,
                    mediaUrl = up.raw,
                    cdnUrl = up.cdn,
                    avatar = meAvatar,
                ),
                clearInput = false,
            )
        }
    }

    fun sendVideo(uri: Uri) {
        val author = meLogin ?: ""
        if (author.isBlank()) {
            status = "请先在设置里登录 GitHub，才能发视频"
            return
        }
        if (blockedByMute()) return
        sending = true
        pendingVideo = runCatching { uri.lastPathSegment?.substringAfterLast('/') }.getOrNull() ?: "视频"
        status = "正在上传视频…"
        scope.launch {
            val length = withContext(Dispatchers.IO) {
                runCatching {
                    ctx.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize }.takeIf { it != null }
                        ?: ctx.contentResolver.openInputStream(uri)?.use { it.available().toLong() }
                }.getOrNull() ?: 0L
            }
            if (length > ChatRepository.MAX_VIDEO_BYTES) {
                sending = false
                pendingVideo = null
                status = "视频超过 ${ChatRepository.MAX_VIDEO_BYTES / 1024 / 1024}MB，请压缩后再发"
                return@launch
            }
            val bytes = withContext(Dispatchers.IO) {
                runCatching { ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
            }
            if (bytes == null || bytes.isEmpty()) {
                sending = false
                pendingVideo = null
                status = "视频读取失败"
                return@launch
            }
            val ext = runCatching { uri.lastPathSegment?.substringAfterLast('.') }.getOrNull()
                ?.lowercase()
                ?.takeIf { it in listOf("mp4", "mov", "webm", "m4v", "3gp") } ?: "mp4"
            uploadProgress = 0f
            val up = ChatRepository.uploadMedia(ctx, bytes, ext, "vid", onProgress = { publishProgress(it) })
            uploadProgress = -1f
            if (up.raw == null) {
                sending = false
                pendingVideo = null
                status = up.error.ifBlank { "视频上传失败" }
                return@launch
            }
            val seconds = withContext(Dispatchers.IO) {
                runCatching {
                    val retriever = android.media.MediaMetadataRetriever()
                    retriever.setDataSource(ctx, uri)
                    val ms = retriever.extractMetadata(
                        android.media.MediaMetadataRetriever.METADATA_KEY_DURATION,
                    )?.toLongOrNull() ?: 0L
                    retriever.release()
                    (ms / 1000L).toInt()
                }.getOrDefault(0)
            }
            post(
                ChatMessage(
                    author = author,
                    text = "",
                    ts = nowSeconds(),
                    kind = ChatKind.VIDEO,
                    mediaUrl = up.raw,
                    cdnUrl = up.cdn,
                    durationSec = seconds,
                    avatar = meAvatar,
                ),
                clearInput = false,
            )
        }
    }

    fun stopRecordingAndSend() {
        val result = recorder.stop()
        recording = false
        recordSeconds = 0
        if (result == null) {
            val reason = recorder.lastError
            status = if (reason.isNullOrBlank()) {
                "录音失败：请检查麦克风权限或是否有其他应用占用麦克风"
            } else {
                "录音失败：$reason"
            }
            return
        }
        val (file, seconds) = result
        if (seconds < 1) {
            file.delete()
            status = "说话时间太短"
            return
        }
        val author = meLogin ?: ""
        if (author.isBlank()) {
            file.delete()
            status = "请先在设置里登录 GitHub，才能发语音"
            return
        }
        if (blockedByMute()) {
            file.delete()
            return
        }
        uploadingVoice = true
        status = "正在上传语音…"
        scope.launch {
            val bytes = withContext(Dispatchers.IO) { runCatching { file.readBytes() }.getOrNull() }
            if (bytes == null || bytes.isEmpty() || bytes.size > ChatRepository.MAX_AUDIO_BYTES) {
                uploadingVoice = false
                status = "语音文件过大或读取失败"
                file.delete()
                return@launch
            }
            uploadProgress = 0f
            val up = ChatRepository.uploadMedia(ctx, bytes, "m4a", "aud", onProgress = { publishProgress(it) })
            uploadProgress = -1f
            file.delete()
            uploadingVoice = false
            if (up.raw == null) {
                status = up.error.ifBlank { "语音上传失败" }
                return@launch
            }
            post(
                ChatMessage(
                    author = author,
                    text = "",
                    ts = nowSeconds(),
                    kind = ChatKind.AUDIO,
                    mediaUrl = up.raw,
                    cdnUrl = up.cdn,
                    durationSec = seconds,
                    avatar = meAvatar,
                ),
                clearInput = false,
            )
        }
    }

    fun playAudio(message: ChatMessage) {
        if (playingKey == message.key) {
            stopPlayback()
            return
        }
        stopPlayback()
        playingKey = message.key
        val candidates = listOfNotNull(message.cdnUrl, message.mediaUrl)
        fun tryAt(index: Int) {
            val url = candidates.getOrNull(index) ?: run {
                // Mirrors can be blocked in mainland China: pull the file through
                // the authenticated API into cache and play it from disk.
                scope.launch {
                    val bytes = ChatRepository.fetchRepoFile(message.mediaUrl)
                    if (bytes == null || bytes.isEmpty()) {
                        playingKey = null
                        status = "语音加载失败"
                        return@launch
                    }
                    val f = java.io.File(ctx.cacheDir, "voice_play_${message.ts}.m4a")
                    withContext(Dispatchers.IO) { f.writeBytes(bytes) }
                    runCatching {
                        val mp = MediaPlayer()
                        mp.setAudioAttributes(
                            AudioAttributes.Builder()
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .build(),
                        )
                        mp.setDataSource(f.absolutePath)
                        mp.setOnPreparedListener { it.start() }
                        mp.setOnCompletionListener { stopPlayback() }
                        player = mp
                        mp.prepare()
                    }.onFailure {
                        playingKey = null
                        status = "语音播放失败"
                    }
                }
                return
            }
            runCatching {
                val mp = MediaPlayer()
                mp.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build(),
                )
                mp.setDataSource(url)
                mp.setOnPreparedListener { it.start() }
                mp.setOnCompletionListener {
                    stopPlayback()
                }
                mp.setOnErrorListener { _, _, _ ->
                    runCatching { mp.release() }
                    player = null
                    tryAt(index + 1)
                    true
                }
                player = mp
                mp.prepareAsync()
            }.onFailure {
                playingKey = null
                status = "语音播放失败"
            }
        }
        tryAt(0)
    }

    fun cancelPendingUpload() {
        ChatRepository.cancelUpload()
        sending = false
        uploadingVoice = false
        uploadProgress = -1f
        localPreview = null
        pendingVideo = null
        status = "已取消上传"
    }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            scope.launch {
                localPreview = withContext(Dispatchers.IO) {
                    io.github.xororz.localdream.utils.ImageDecode.decodeSampledUri(ctx, uri, 1024)
                }
            }
            sendImage(uri)
        }
    }
    val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) sendVideo(uri)
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            // The finger has already been lifted while the dialog was up, so do
            // not start a stranded recording; ask for another press instead.
            status = "麦克风权限已开启，请再按住说话"
        } else {
            status = "需要麦克风权限才能发语音"
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("公共聊天")
                        Text(
                            "所有人可见 · 请遵守社区公约",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { profileFor = meLogin }) {
                        Icon(Icons.Default.Person, contentDescription = "我的主页")
                    }
                    IconButton(onClick = { refresh() }, enabled = !loading) {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新")
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (meLogin != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Avatar(name = meLogin, avatarUrl = meAvatar, size = 20)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "已登录：$meLogin",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.weight(1f))
                    if (sending) {
                        Text("发送中…", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            LazyColumn(
                state = chatListState,
                reverseLayout = true,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val visible = messages.reversed()
                items(visible, key = { it.key }) { m ->
                    val index = visible.indexOf(m)
                    val older = visible.getOrNull(index + 1)
                    if (older == null || ChatTime.needsSeparator(older.ts, m.ts)) {
                        Text(
                            text = ChatTime.dayLabel(m.ts),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                    }
                    val mine = ChatIdentity.isMine(m.author, meLogin)
                    MessageRow(
                        message = m,
                        mine = mine,
                        admin = policy.isAdmin(m.author),
                        playing = playingKey == m.key,
                        onPlayAudio = { playAudio(m) },
                        onAvatar = { login -> profileFor = login },
                        onOpenMedia = { urls -> viewerImages = urls },
                        onOpenVideo = { urls -> videoUrls = urls },
                        onLink = { url -> webUrl = url },
                        onLongPress = { actionFor = m },
                    )
                }
            }

            if (loading && messages.isEmpty()) {
                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            if (messages.isEmpty() && !loading) {
                Text(
                    "还没有人发言，来抢第一条吧",
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }

            if (sending && uploadProgress >= 0f) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp)) {
                    LinearProgressIndicator(
                        progress = { uploadProgress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "上传中 ${(uploadProgress * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            if (status.isNotEmpty() || sending) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        status.ifEmpty { "正在发送…" },
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (status.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.error,
                    )
                    if (sending || uploadingVoice) {
                        TextButton(onClick = {
                            ChatRepository.cancelUpload()
                            uploadingVoice = false
                            sending = false
                            uploadProgress = -1f
                            status = "已取消上传"
                        }) {
                            Icon(Icons.Default.Close, contentDescription = "取消上传", modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(2.dp))
                            Text("取消")
                        }
                    }
                }
            }

            if (localPreview != null && sending) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    Image(
                        bitmap = localPreview!!.asImageBitmap(),
                        contentDescription = "正在发送",
                        modifier = Modifier.height(88.dp).fillMaxWidth(),
                        contentScale = ContentScale.Fit,
                    )
                    // Cancel before it reaches the shared chat (wrong pick / bad file).
                    IconButton(
                        onClick = { cancelPendingUpload() },
                        modifier = Modifier.align(Alignment.TopEnd).size(28.dp),
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "取消这张图片",
                            tint = Color.White,
                            modifier = Modifier
                                .size(18.dp)
                                .background(Color(0x99000000), CircleShape),
                        )
                    }
                }
            }

            if (pendingVideo != null && sending) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Videocam, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "视频上传中 ${pendingVideo}" +
                                if (uploadProgress >= 0f) " ${(uploadProgress * 100).toInt()}%" else "",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { cancelPendingUpload() }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "取消这个视频")
                        }
                    }
                }
            }

            if (recording) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Default.FiberManualRecord, null, tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.width(6.dp))
                        Text("正在录音 ${ChatTime.duration(recordSeconds)}")
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = {
                            recorder.cancel()
                            recording = false
                            recordSeconds = 0
                        }) { Text("取消") }
                        Button(onClick = { stopRecordingAndSend() }) { Text("发送") }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                // WeChat-style hold-to-talk: press starts the recorder, release
                // sends, sliding away cancels. A tap still toggles for users
                // who prefer the old two-step flow.
                val micStart: () -> Boolean = {
                    val granted = ContextCompat.checkSelfPermission(
                        ctx, Manifest.permission.RECORD_AUDIO,
                    ) == PackageManager.PERMISSION_GRANTED
                    if (!granted) {
                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        false
                    } else if (uploadingVoice || sending) {
                        false
                    } else if (recorder.start()) {
                        recording = true
                        recordSeconds = 0
                        true
                    } else {
                        status = "麦克风被占用，录音启动失败"
                        false
                    }
                }
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .pointerInput(recording, uploadingVoice, sending) {
                            detectTapGestures(
                                onPress = {
                                    if (recording) {
                                        // second tap while recording = send
                                        stopRecordingAndSend()
                                        return@detectTapGestures
                                    }
                                    if (!micStart()) return@detectTapGestures
                                    val released = tryAwaitRelease()
                                    if (released) {
                                        stopRecordingAndSend()
                                    } else {
                                        recorder.cancel()
                                        recording = false
                                        recordSeconds = 0
                                        status = "已取消录音"
                                    }
                                },
                            )
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (recording) Icons.Default.Stop else Icons.Default.Mic,
                        contentDescription = "按住说话，松开发送",
                        tint = if (recording) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("说点什么…") },
                    maxLines = 4,
                )
                IconButton(
                    enabled = !sending && !recording,
                    onClick = { atPicker = true },
                ) {
                    Icon(Icons.Default.AlternateEmail, contentDescription = "@某人")
                }
                IconButton(
                    enabled = !sending && !recording,
                    onClick = { imagePicker.launch("image/*") },
                ) {
                    Icon(Icons.Default.Image, contentDescription = "图片")
                }
                IconButton(
                    enabled = !sending && !recording,
                    onClick = { videoPicker.launch("video/*") },
                ) {
                    Icon(Icons.Default.Videocam, contentDescription = "视频")
                }
                FilledIconButton(
                    enabled = !sending && input.isNotBlank(),
                    onClick = { sendText() },
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "发送")
                }
            }
        }
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            runCatching { chatListState.animateScrollToItem(0) }
        }
    }

    // Long-press actions: copy / quote / @mention / mute (admins only).
    if (atPicker) {
        val people = messages.map { it.author }.filter { it.isNotBlank() }.distinct()
        AlertDialog(
            onDismissRequest = { atPicker = false },
            title = { Text("选择要 @ 的人") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    if (policy.isAdmin(meLogin)) {
                        TextButton(onClick = {
                            input = (input + " @全体人员 ").trimStart()
                            atPicker = false
                        }) { Text("@全体人员（管理员）") }
                    }
                    people.forEach { name ->
                        TextButton(onClick = {
                            input = (input + " @$name ").trimStart()
                            atPicker = false
                        }, modifier = Modifier.fillMaxWidth()) { Text("@$name") }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { atPicker = false }) { Text("关闭") } },
        )
    }

    mentionMessage?.let { m ->
        AlertDialog(
            onDismissRequest = { mentionMessage = null },
            title = { Text("有人 @ 你") },
            text = { Text("${m.author}：${m.text.ifBlank { "[${m.kind.name}]" }}") },
            confirmButton = { TextButton(onClick = { mentionMessage = null }) { Text("知道了") } },
        )
    }

    LaunchedEffect(profileFor) {
        val login = profileFor
        if (login.isNullOrBlank()) {
            profileData = null
        } else {
            profileData = io.github.xororz.localdream.chat.ChatProfileRepo.cached(login)
            val loaded = io.github.xororz.localdream.chat.ChatProfileRepo.load(login)
            if (profileFor == login) profileData = loaded
        }
    }

    profileFor?.let { login ->
        val p = profileData ?: io.github.xororz.localdream.chat.ChatProfile(login)
        ProfileViewDialog(
            profile = p,
            isSelf = ChatIdentity.isMine(login, meLogin),
            onEdit = { editingProfile = p },
            onDismiss = { profileFor = null },
        )
    }

    editingProfile?.let { initial ->
        ProfileEditDialog(
            initial = initial,
            onDismiss = { editingProfile = null },
            onSave = { updated ->
                scope.launch {
                    io.github.xororz.localdream.chat.ChatProfileRepo.save(ctx, updated).onSuccess {
                        Toast.makeText(ctx, "主页已保存", Toast.LENGTH_SHORT).show()
                        profileData = updated
                        editingProfile = null
                    }.onFailure {
                        Toast.makeText(ctx, it.message ?: "保存失败", Toast.LENGTH_SHORT).show()
                    }
                }
            },
        )
    }

    actionFor?.let { target ->
        AlertDialog(
            onDismissRequest = { actionFor = null },
            title = { Text(target.author.ifBlank { "匿名" }, maxLines = 1) },
            text = {
                Column {
                    val clipboard = LocalClipboardManager.current
                    if (target.kind == ChatKind.IMAGE || target.kind == ChatKind.VIDEO) {
                        TextButton(onClick = {
                            actionFor = null
                            scope.launch {
                                val bytes = withContext(Dispatchers.IO) {
                                    ChatRepository.fetchRepoFile(target.mediaUrl)
                                }
                                if (bytes == null || bytes.isEmpty()) {
                                    Toast.makeText(ctx, "文件下载失败", Toast.LENGTH_SHORT).show()
                                    return@launch
                                }
                                val ext = if (target.kind == ChatKind.VIDEO) "mp4" else "png"
                                val mime = if (target.kind == ChatKind.VIDEO) "video/mp4" else "image/png"
                                val ok = withContext(Dispatchers.IO) {
                                    saveBytesToGallery(ctx, bytes, "LocalDreamET_${target.ts}.$ext", mime)
                                }
                                Toast.makeText(
                                    ctx,
                                    if (ok) "已保存到相册" else "保存失败，请检查存储权限",
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        }, modifier = Modifier.fillMaxWidth()) { Text("保存到相册") }

                        TextButton(onClick = {
                            actionFor = null
                            val url = listOfNotNull(target.cdnUrl, target.mediaUrl).firstOrNull().orEmpty()
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = if (target.kind == ChatKind.VIDEO) "video/*" else "image/*"
                                putExtra(Intent.EXTRA_TEXT, url)
                            }
                            runCatching {
                                ctx.startActivity(Intent.createChooser(send, "分享"))
                            }.onFailure { Toast.makeText(ctx, "没有可分享的应用", Toast.LENGTH_SHORT).show() }
                        }, modifier = Modifier.fillMaxWidth()) { Text("分享") }

                        TextButton(onClick = {
                            actionFor = null
                            val me = meLogin ?: return@TextButton
                            post(
                                target.copy(
                                    author = me,
                                    ts = nowSeconds(),
                                    avatar = meAvatar,
                                    text = "",
                                ),
                                clearInput = false,
                            )
                        }, modifier = Modifier.fillMaxWidth()) { Text("转发到聊天") }
                        HorizontalDivider()
                    }

                    TextButton(onClick = {
                        clipboard.setText(AnnotatedString(target.text.ifBlank { "[${target.kind.name}]" }))
                        Toast.makeText(ctx, "已复制", Toast.LENGTH_SHORT).show()
                        actionFor = null
                    }, modifier = Modifier.fillMaxWidth()) { Text("复制文字") }

                    TextButton(onClick = {
                        actionFor = null
                        val me = meLogin ?: return@TextButton
                        post(
                            target.copy(author = me, ts = nowSeconds(), avatar = meAvatar),
                            clearInput = false,
                        )
                    }, modifier = Modifier.fillMaxWidth()) { Text("转发到聊天") }

                    TextButton(onClick = {
                        val quoted = "「${target.author}：${target.text.take(50).ifBlank { "[${target.kind.name}]" }}」\n"
                        input = quoted + input
                        actionFor = null
                    }, modifier = Modifier.fillMaxWidth()) { Text("引用这条消息") }

                    TextButton(onClick = {
                        input = (input + " @" + target.author).trimStart()
                        actionFor = null
                    }, modifier = Modifier.fillMaxWidth()) { Text("@TA") }

                    val iAmAdmin = policy.isAdmin(meLogin)
                    val targetIsAdmin = policy.isAdmin(target.author)
                    val isSelf = ChatIdentity.isMine(target.author, meLogin)
                    if (iAmAdmin && !isSelf && target.author.isNotBlank() && target.author != "ETQWFD") {
                        HorizontalDivider()
                        TextButton(onClick = {
                            actionFor = null
                            scope.launch {
                                val admins = policy.admins.toMutableSet()
                                if (targetIsAdmin) admins.remove(target.author) else admins.add(target.author)
                                val newPolicy = policy.copy(admins = admins)
                                ChatPolicyRepo.save(ctx, newPolicy).onSuccess {
                                    policy = newPolicy
                                    Toast.makeText(
                                        ctx,
                                        if (targetIsAdmin) "已取消 ${target.author} 的管理员" else "已设为管理员：${target.author}",
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                }.onFailure { status = it.message ?: "管理员设置失败" }
                            }
                        }, modifier = Modifier.fillMaxWidth()) {
                            Text(if (targetIsAdmin) "取消管理员" else "设为管理员")
                        }
                    }
                    if (iAmAdmin && !targetIsAdmin && !isSelf) {
                        HorizontalDivider()
                        Text(
                            "禁言 ${target.author}",
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(top = 8.dp, start = 8.dp),
                        )
                        listOf(600L to "10 分钟", 3600L to "1 小时", 21600L to "6 小时").forEach { (sec, label) ->
                            TextButton(onClick = {
                                actionFor = null
                                scope.launch {
                                    val newPolicy = policy.copy(
                                        mutes = policy.mutes + (
                                            target.author to ChatMute(
                                                until = System.currentTimeMillis() / 1000L + sec,
                                                by = meLogin ?: "admin",
                                            )
                                            ),
                                    )
                                    ChatPolicyRepo.save(ctx, newPolicy).onSuccess {
                                        policy = newPolicy
                                        Toast.makeText(ctx, "已禁言 $label", Toast.LENGTH_SHORT).show()
                                    }.onFailure {
                                        status = it.message ?: "禁言失败"
                                    }
                                }
                            }, modifier = Modifier.fillMaxWidth()) { Text("禁言 $label") }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { actionFor = null }) { Text("关闭") }
            },
        )
    }

    // In-app browser: chat links must not kick the user out to the system browser.
    webUrl?.let { url ->
        Dialog(onDismissRequest = { webUrl = null }) {
            Surface(shape = MaterialTheme.shapes.large) {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            url,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { webUrl = null }) {
                            Icon(Icons.Default.Close, contentDescription = "关闭")
                        }
                    }
                    AndroidView(
                        factory = { c ->
                            WebView(c).apply {
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                settings.useWideViewPort = true
                                settings.loadWithOverviewMode = true
                                settings.builtInZoomControls = true
                                settings.displayZoomControls = false
                                webViewClient = object : WebViewClient() {
                                    override fun onReceivedError(
                                        view: WebView?,
                                        request: android.webkit.WebResourceRequest?,
                                        error: android.webkit.WebResourceError?,
                                    ) {
                                        Toast.makeText(ctx, "网页加载失败：${error?.description ?: "网络错误"}", Toast.LENGTH_SHORT).show()
                                    }
                                }
                                android.webkit.WebChromeClient()
                            }
                        },
                        update = { it.loadUrl(url) },
                        modifier = Modifier.fillMaxWidth().height(460.dp),
                    )
                    TextButton(
                        onClick = {
                            runCatching {
                                ctx.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
                            }.onFailure {
                                Toast.makeText(ctx, "没有可用的浏览器", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("用系统浏览器打开") }
                }
            }
        }
    }

    if (viewerImages.isNotEmpty()) {
        Dialog(onDismissRequest = { viewerImages = emptyList() }) {
            Surface(shape = MaterialTheme.shapes.large) {
                Box(contentAlignment = Alignment.TopEnd) {
                    io.github.xororz.localdream.ui.components.FallbackImage(
                        urls = viewerImages,
                        contentDescription = "查看大图",
                        modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp),
                        contentScale = ContentScale.Fit,
                        zoomable = true,
                    )
                    IconButton(onClick = { viewerImages = emptyList() }) {
                        Icon(Icons.Default.Close, contentDescription = "关闭")
                    }
                }
            }
        }
    }

    if (videoUrls.isNotEmpty()) {
        InAppVideoDialog(videoUrls) { videoUrls = emptyList() }
    }
}

@Composable
private fun ProfileViewDialog(
    profile: io.github.xororz.localdream.chat.ChatProfile,
    isSelf: Boolean,
    onEdit: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.large) {
            Column(
                Modifier.padding(20.dp).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Avatar(name = profile.login, avatarUrl = profile.avatar.ifBlank { null }, size = 72)
                Spacer(Modifier.height(10.dp))
                Text(profile.displayName, style = MaterialTheme.typography.titleMedium)
                Text("@${profile.login}", style = MaterialTheme.typography.labelMedium)
                if (profile.bio.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(profile.bio, style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(16.dp))
                Row {
                    TextButton(onClick = onDismiss) { Text("关闭") }
                    if (isSelf) {
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = onEdit) { Text("编辑我的主页") }
                    } else {
                        Text("只能编辑自己的主页", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfileEditDialog(
    initial: io.github.xororz.localdream.chat.ChatProfile,
    onDismiss: () -> Unit,
    onSave: (io.github.xororz.localdream.chat.ChatProfile) -> Unit,
) {
    var nickname by remember { mutableStateOf(initial.nickname) }
    var bio by remember { mutableStateOf(initial.bio) }
    var avatar by remember { mutableStateOf(initial.avatar) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑我的主页") },
        text = {
            Column {
                OutlinedTextField(
                    value = nickname,
                    onValueChange = { nickname = it },
                    label = { Text("昵称（默认显示账号名）") },
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = bio,
                    onValueChange = { bio = it },
                    label = { Text("个人简介") },
                    maxLines = 3,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = avatar,
                    onValueChange = { avatar = it },
                    label = { Text("头像图片链接（可留空）") },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(initial.copy(nickname = nickname.trim(), bio = bio.trim(), avatar = avatar.trim()))
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/**
 * Built-in player: chat videos must not kick the user out to an external app
 * (many devices have no video player installed at all). Tries the mirror chain
 * in order if the first URL fails.
 */
@Composable
private fun InAppVideoDialog(urls: List<String>, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var index by remember(urls) { mutableIntStateOf(0) }
    var downloadTrigger by remember(urls) { mutableIntStateOf(0) }
    var localFile by remember(urls) { mutableStateOf<java.io.File?>(null) }
    var failed by remember(urls) { mutableStateOf(false) }

    // If every mirror refuses to stream, pull the clip through the
    // authenticated API and play the local file (MediaPlayer handles it).
    LaunchedEffect(downloadTrigger) {
        if (downloadTrigger == 0) return@LaunchedEffect
        val bytes = withContext(Dispatchers.IO) { ChatRepository.fetchRepoFile(urls.lastOrNull()) }
        if (bytes == null || bytes.isEmpty()) {
            failed = true
            return@LaunchedEffect
        }
        val f = withContext(Dispatchers.IO) {
            java.io.File(ctx.cacheDir, "video_play_${System.currentTimeMillis()}.mp4")
                .also { it.writeBytes(bytes) }
        }
        localFile = f
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.large) {
            Box(contentAlignment = Alignment.TopEnd) {
                androidx.compose.ui.viewinterop.AndroidView(
                    factory = { c ->
                        android.widget.VideoView(c).apply {
                            val controller = android.widget.MediaController(c)
                            controller.setAnchorView(this)
                            setMediaController(controller)
                            setOnPreparedListener { controller.show() }
                            setOnErrorListener { _, _, _ ->
                                if (index < urls.lastIndex) {
                                    index += 1
                                } else if (localFile == null) {
                                    downloadTrigger += 1
                                } else {
                                    failed = true
                                }
                                true
                            }
                        }
                    },
                    update = { view ->
                        runCatching {
                            val src = localFile?.absolutePath ?: urls.getOrNull(index)
                            if (src != null) {
                                view.setVideoURI(android.net.Uri.parse(src))
                                view.start()
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(320.dp),
                )
                if (failed) {
                    Text(
                        "视频加载失败，请检查网络后重试",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.align(Alignment.BottomCenter).padding(8.dp),
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "关闭")
                }
            }
        }
    }
}

/** Saves bytes into the public Pictures/LocalDreamET album. */
private fun saveBytesToGallery(ctx: android.content.Context, bytes: ByteArray, name: String, mime: String): Boolean {
    return runCatching {
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(android.provider.MediaStore.MediaColumns.MIME_TYPE, mime)
            put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH,
                if (mime.startsWith("video")) "Movies/LocalDreamET" else "Pictures/LocalDreamET")
            put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val collection = if (mime.startsWith("video"))
            android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        else android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val uri = ctx.contentResolver.insert(collection, values) ?: return false
        ctx.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
        values.clear()
        values.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0)
        ctx.contentResolver.update(uri, values, null, null)
        true
    }.getOrDefault(false)
}

private fun nowSeconds(): Long = System.currentTimeMillis() / 1000L

@Composable
private fun MessageRow(
    message: ChatMessage,
    mine: Boolean,
    admin: Boolean,
    playing: Boolean,
    onPlayAudio: () -> Unit,
    onAvatar: (String) -> Unit,
    onOpenMedia: (List<String>) -> Unit,
    onOpenVideo: (List<String>) -> Unit,
    onLink: (String) -> Unit,
    onLongPress: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Top,
    ) {
        if (!mine) {
            Avatar(
                name = message.author,
                avatarUrl = message.avatar,
                size = 36,
                modifier = Modifier.clickable { onAvatar(message.author) },
            )
            Spacer(Modifier.width(6.dp))
        }
        Column(horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
            // Every message keeps its header, including my own long messages:
            // an unbounded bubble used to push the avatar/name off-screen.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
            ) {
                Text(
                    message.author.ifBlank { "匿名" },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 180.dp),
                )
                    if (admin) {
                        Spacer(Modifier.width(4.dp))
                        Surface(
                            color = Color(0xFF07C160),
                            shape = RoundedCornerShape(3.dp),
                        ) {
                            Text(
                                "管理员",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                            )
                        }
                    }
            }
            Surface(
                modifier = Modifier
                    .widthIn(max = 272.dp)
                    .combinedClickable(onClick = {}, onLongClick = onLongPress),
                color = if (mine) Color(0xFF95EC69) else MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = RoundedCornerShape(
                    topStart = if (mine) 10.dp else 2.dp,
                    topEnd = if (mine) 2.dp else 10.dp,
                    bottomStart = 10.dp,
                    bottomEnd = 10.dp,
                ),
                tonalElevation = if (mine) 0.dp else 1.dp,
            ) {
                Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                    when (message.kind) {
                        ChatKind.TEXT -> LinkText(
                            text = message.text,
                            color = if (mine) Color(0xFF111111) else MaterialTheme.colorScheme.onSurface,
                            linkColor = if (mine) Color(0xFF1A5AA0) else MaterialTheme.colorScheme.primary,
                            onLink = onLink,
                        )

                        ChatKind.IMAGE -> {
                            val urls = listOfNotNull(message.cdnUrl, message.mediaUrl)
                            RemoteImage(
                                urls = urls,
                                modifier = Modifier
                                    .widthIn(max = 220.dp)
                                    .heightIn(max = 260.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable { onOpenMedia(urls) },
                            )
                        }

                        ChatKind.VIDEO -> {
                            Surface(
                                color = Color(0xFF1F1F1F),
                                shape = RoundedCornerShape(6.dp),
                                modifier = Modifier
                                    .width(180.dp)
                                    .height(110.dp)
                                    .clickable {
                                        onOpenVideo(listOfNotNull(message.cdnUrl, message.mediaUrl))
                                    },
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        Icons.Default.PlayCircleFilled,
                                        contentDescription = "播放视频",
                                        tint = Color.White,
                                        modifier = Modifier.size(40.dp),
                                    )
                                    Text(
                                        "视频 ${ChatTime.duration(message.durationSec)}",
                                        color = Color.White,
                                        style = MaterialTheme.typography.labelSmall,
                                        modifier = Modifier.align(Alignment.BottomStart).padding(6.dp),
                                    )
                                }
                            }
                        }

                        ChatKind.AUDIO -> Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .width(140.dp)
                                .clickable { onPlayAudio() },
                        ) {
                            Icon(
                                imageVector = if (playing) Icons.Default.PauseCircle else Icons.Default.PlayCircle,
                                contentDescription = "播放语音",
                                tint = if (mine) Color(0xFF111111) else MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(26.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                ChatTime.duration(message.durationSec),
                                color = if (mine) Color(0xFF111111) else MaterialTheme.colorScheme.onSurface,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                    if (message.text.isNotBlank() && message.kind != ChatKind.TEXT) {
                        Text(
                            message.text,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (mine) Color(0xFF111111) else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
        }
        if (mine) {
            Spacer(Modifier.width(6.dp))
            Avatar(
                name = message.author,
                avatarUrl = message.avatar,
                size = 36,
                modifier = Modifier.clickable { onAvatar(message.author) },
            )
        }
    }
}

/**
 * Chat text with tappable blue links. Links open the in-app browser instead of
 * handing the user off to the system browser.
 */
@Composable
private fun LinkText(
    text: String,
    color: Color,
    linkColor: Color,
    onLink: (String) -> Unit,
) {
    val urlRegex = remember { Regex("(https?://[\\w\\-._~:/?#@!$&'()*+,;=%]+)|(www\\.\\w+\\.\\w+)") }
    val annotated = remember(text) {
        buildAnnotatedString {
            var cursor = 0
            urlRegex.findAll(text).forEach { match ->
                val start = match.range.first
                val end = match.range.last + 1
                if (start > cursor) append(text.substring(cursor, start))
                val raw = match.value
                val url = if (raw.startsWith("http")) raw else "https://$raw"
                pushStringAnnotation(tag = "url", annotation = url)
                withStyle(SpanStyle(color = linkColor)) { append(raw) }
                pop()
                cursor = end
            }
            if (cursor < text.length) append(text.substring(cursor))
        }
    }
    ClickableText(
        text = annotated,
        style = androidx.compose.ui.text.TextStyle(
            color = color,
            fontSize = MaterialTheme.typography.bodyLarge.fontSize,
        ),
        onClick = { offset ->
            annotated.getStringAnnotations("url", offset, offset).firstOrNull()?.let {
                onLink(it.item)
            }
        },
    )
}

@Composable
private fun Avatar(
    name: String,
    avatarUrl: String?,
    size: Int,
    modifier: Modifier = Modifier,
) {
    val colors = listOf(
        Color(0xFF7E57C2), Color(0xFF42A5F5), Color(0xFF26A69A),
        Color(0xFFEF5350), Color(0xFFFFA726), Color(0xFF66BB6A),
    )
    val color = colors[(name.hashCode().absoluteValue) % colors.size]
    if (avatarUrl != null) {
        AsyncImage(
            model = avatarUrl,
            contentDescription = name,
            modifier = modifier.size(size.dp).clip(CircleShape),
            contentScale = ContentScale.Crop,
        )
    } else {
        Box(
            modifier = modifier.size(size.dp).clip(CircleShape).background(color),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = name.take(1).uppercase().ifBlank { "?" },
                color = Color.White,
                fontSize = (size / 2.5f).sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

/** Tries the CDN mirror first, then the canonical raw URL, then the other one. */
@Composable
private fun RemoteImage(urls: List<String>, modifier: Modifier = Modifier) {
    var index by remember(urls) { mutableIntStateOf(0) }
    Box(modifier = modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
        AsyncImage(
            model = urls.getOrNull(index),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
            onError = {
                if (index < urls.lastIndex) index++ else if (index > 0) index = 0
            },
        )
    }
}

private val Int.absoluteValue: Int get() = if (this < 0) -this else this
