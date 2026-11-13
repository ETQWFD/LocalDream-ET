package etc.github.ai.chat.community

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun chatPrefs(context: Context) = context.getSharedPreferences("et_chat", Context.MODE_PRIVATE)

private fun myName(context: Context): String {
    val sp = chatPrefs(context)
    val saved = sp.getString("nick", null)
    if (saved != null) return saved
    val n = "游客" + (1000..9999).random()
    sp.edit().putString("nick", n).apply()
    return n
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommunityChatScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    var messages by remember { mutableStateOf<List<ChatMsg>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var sending by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    var uploadNote by remember { mutableStateOf<String?>(null) }
    var downloadNote by remember { mutableStateOf<String?>(null) }
    var downloadProgress by remember { mutableStateOf(0f) }
    var downloadingTs by remember { mutableStateOf<Long?>(null) }
    var nickname by remember { mutableStateOf(myName(context)) }

    fun reload() {
        loading = true
        scope.launch {
            messages = ChatApi.load()
            loading = false
        }
    }

    LaunchedEffect(Unit) { reload() }

    // Auto-scroll to the newest message once loaded.
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    val pickFile = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val name = queryName(context, uri)
        scope.launch {
            sending = true
            uploadNote = "准备上传…"
            val res = ChatApi.upload(context, uri, name) { done, total ->
                val pct = if (total <= 0) 0 else (done * 100 / total).toInt()
                uploadNote = "上传中 $pct%（每片 100MB，大文件会自动切片）"
            }
            uploadNote = null
            sending = false
            res.onSuccess { up ->
                val msg = ChatMsg(
                    author = nickname,
                    text = "",
                    ts = System.currentTimeMillis(),
                    kind = "file",
                    fileName = up.name,
                    fileSize = up.size,
                    sha256 = up.sha256,
                    parts = up.parts,
                )
                val sent = ChatApi.append(msg)
                if (sent.isSuccess) {
                    Toast.makeText(context, "已发送（${up.parts.size} 个分片）", Toast.LENGTH_SHORT).show()
                    reload()
                } else {
                    Toast.makeText(context, sent.exceptionOrNull()?.message ?: "发送失败", Toast.LENGTH_LONG).show()
                }
            }.onFailure {
                Toast.makeText(context, it.message ?: "上传失败", Toast.LENGTH_LONG).show()
            }
        }
    }

    fun sendText() {
        if (draft.isBlank() || sending) return
        val text = draft.trim()
        draft = ""
        scope.launch {
            sending = true
            val msg = ChatMsg(author = nickname, text = text, ts = System.currentTimeMillis())
            val r = ChatApi.append(msg)
            sending = false
            if (r.isSuccess) reload() else {
                draft = text
                Toast.makeText(context, r.exceptionOrNull()?.message ?: "发送失败", Toast.LENGTH_LONG).show()
            }
        }
    }

    fun download(msg: ChatMsg) {
        if (msg.parts.isEmpty()) return
        downloadingTs = msg.ts
        downloadProgress = 0f
        scope.launch {
            val dir = File(context.getExternalFilesDir(null), "downloads").apply { mkdirs() }
            val target = File(dir, msg.fileName.ifBlank { "file-${msg.ts}" })
            downloadNote = "正在下载并合并…"
            val r = ChatApi.download(msg.parts, target) { done, total ->
                downloadProgress = if (total <= 0) 0f else (done.toFloat() / total).coerceIn(0f, 1f)
            }
            downloadNote = null
            downloadingTs = null
            r.onSuccess {
                if (msg.sha256.isNotBlank()) {
                    val actual = sha256(target)
                    if (actual != msg.sha256) {
                        Toast.makeText(context, "校验失败：文件可能损坏", Toast.LENGTH_LONG).show()
                        return@onSuccess
                    }
                }
                Toast.makeText(context, "已合并为完整文件：${target.name}", Toast.LENGTH_LONG).show()
                shareFile(context, target)
            }.onFailure {
                Toast.makeText(context, it.message ?: "下载失败", Toast.LENGTH_LONG).show()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("ET 聊天社区", fontWeight = FontWeight.SemiBold)
                        Text(
                            "免登录 · 私密仓库 · 单文件最大 1GB",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { reload() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新")
                    }
                },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())

            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(messages, key = { "${it.ts}|${it.author}" }) { m ->
                    MessageBox(
                        msg = m,
                        mine = m.author == nickname,
                        downloading = downloadingTs == m.ts,
                        progress = downloadProgress,
                        onDownload = { download(m) },
                    )
                }
            }

            uploadNote?.let {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            downloadNote?.let {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LinearProgressIndicator(
                        progress = { downloadProgress },
                        modifier = Modifier.weight(1f).height(4.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("$it ${(downloadProgress * 100).toInt()}%", fontSize = 11.sp)
                }
            }

            Surface(tonalElevation = 2.dp) {
                Row(
                    Modifier.fillMaxWidth().padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { pickFile.launch(arrayOf("*/*")) }) {
                        Icon(Icons.Default.AttachFile, contentDescription = "发送文件（≤1GB）")
                    }
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("说点什么…") },
                        maxLines = 4,
                    )
                    Spacer(Modifier.width(6.dp))
                    IconButton(onClick = { sendText() }, enabled = !sending) {
                        Icon(Icons.Default.Send, contentDescription = "发送")
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageBox(
    msg: ChatMsg,
    mine: Boolean,
    downloading: Boolean,
    progress: Float,
    onDownload: () -> Unit,
) {
    val bubble = if (mine) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .background(bubble, RoundedCornerShape(14.dp))
                .padding(10.dp),
        ) {
            Text(
                msg.author + " · " + formatTs(msg.ts),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (msg.text.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(msg.text, fontSize = 14.sp)
            }
            if (msg.fileName.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(Color(0x11000000), RoundedCornerShape(10.dp))
                        .clickable(enabled = !downloading) { onDownload() }
                        .padding(10.dp),
                ) {
                    Text("📎 ${msg.fileName}", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    Text(
                        buildString {
                            append(humanSize(msg.fileSize))
                            if (msg.parts.size > 1) append(" · ${msg.parts.size} 个分片，下载后自动合并")
                        },
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (downloading) {
                        Spacer(Modifier.height(6.dp))
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    } else {
                        Text("点此下载", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

private fun formatTs(ts: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(if (ts > 0) ts else System.currentTimeMillis()))

private fun humanSize(n: Long): String = when {
    n >= 1024L * 1024 * 1024 -> "%.2f GB".format(n / 1024.0 / 1024 / 1024)
    n >= 1024L * 1024 -> "%.1f MB".format(n / 1024.0 / 1024)
    n >= 1024 -> "%.0f KB".format(n / 1024.0)
    else -> "$n B"
}

private fun queryName(context: Context, uri: Uri): String {
    return runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
        }
    }.getOrNull() ?: "file-${UUID.randomUUID().toString().take(8)}"
}

private fun sha256(file: File): String = runCatching {
    val md = java.security.MessageDigest.getInstance("SHA-256")
    file.inputStream().use { ins ->
        val buf = ByteArray(1 shl 16)
        while (true) {
            val n = ins.read(buf)
            if (n <= 0) break
            md.update(buf, 0, n)
        }
    }
    md.digest().joinToString("") { "%02x".format(it) }
}.getOrDefault("")

private fun shareFile(context: Context, file: File) {
    runCatching {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(i, "打开或分享"))
    }.onFailure {
        Toast.makeText(context, "无法打开文件：${it.message}", Toast.LENGTH_LONG).show()
    }
}
