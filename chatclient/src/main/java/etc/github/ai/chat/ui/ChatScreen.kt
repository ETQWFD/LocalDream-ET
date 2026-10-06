package etc.github.ai.chat.ui

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import etc.github.ai.chat.api.ServerApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(vm: ChatViewModel) {
    val context = LocalContext.current
    val listState = rememberLazyListState()
    var viewerBitmap by remember { mutableStateOf<Bitmap?>(null) }

    // auto-scroll to the newest message whenever the list changes
    LaunchedEffect(vm.messages.size, vm.generating) {
        if (vm.messages.isNotEmpty()) {
            runCatching { listState.animateScrollToItem(vm.messages.lastIndex) }
        }
    }

    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri: Uri? ->
        if (uri != null) {
            CoroutineScope(Dispatchers.Main).launch {
                val preview = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openInputStream(uri).use { input ->
                            val opts = android.graphics.BitmapFactory.Options()
                            opts.inJustDecodeBounds = true
                            android.graphics.BitmapFactory.decodeStream(input, null, opts)
                            var sample = 1
                            while (opts.outWidth / sample > 512 || opts.outHeight / sample > 512) sample *= 2
                            context.contentResolver.openInputStream(uri).use { i2 ->
                                android.graphics.BitmapFactory.decodeStream(
                                    i2, null,
                                    android.graphics.BitmapFactory.Options().apply { inSampleSize = sample },
                                )
                            }
                        }
                    }.getOrNull()
                }
                vm.setPickedImage(uri, preview)
            }
        }
    }

    if (vm.showSettings) {
        SettingsScreen(vm, onClose = { vm.closeSettings() })
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("ET 聊天画师") },
                actions = {
                    IconButton(onClick = { vm.openSettings() }) {
                        Icon(Icons.Default.Settings, contentDescription = "设置")
                    }
                },
            )
        },
        bottomBar = {
            ComposerBar(
                vm = vm,
                onPickImage = {
                    pickImage.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                    )
                },
            )
        },
    ) { pad ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .background(MaterialTheme.colorScheme.background),
        ) {
            if (vm.messages.isEmpty()) {
                Column(
                    Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        "发一句提示词开始生成",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "首次使用请点右上角齿轮，填服务端地址和 46 位 Key",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 12.dp, end = 12.dp, top = 12.dp, bottom = 12.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(vm.messages, key = { it.id }) { msg ->
                    when (msg) {
                        is ChatMsg.User -> UserBubble(msg)
                        is ChatMsg.Ai -> AiBubble(
                            msg,
                            onImageClick = { viewerBitmap = it },
                            onRetry = { vm.retryError(msg) },
                        )
                    }
                }
            }
        }
    }

    // send without config -> guide to settings, no dead button
    if (vm.needSetup) {
        AlertDialog(
            onDismissRequest = { vm.dismissNeedSetup() },
            title = { Text("还没配置服务端") },
            text = { Text("请先在设置里填写服务端地址（http://电脑局域网IP:8080）和 46 位 API Key，再发送提示词。") },
            confirmButton = { TextButton(onClick = { vm.goSetupFromNeedSetup() }) { Text("去设置") } },
            dismissButton = { TextButton(onClick = { vm.dismissNeedSetup() }) { Text("取消") } },
        )
    }

    viewerBitmap?.let { bmp ->
        FullscreenViewer(
            bitmap = bmp,
            cloudEnabled = vm.settings.githubToken.isNotBlank() &&
                vm.settings.githubRepo.isNotBlank(),
            onDismiss = { viewerBitmap = null },
            onSave = { saveBitmapToGallery(context, bmp) },
            onShare = { shareBitmap(context, bmp) },
            onUpload = {
                CoroutineScope(Dispatchers.Main).launch {
                    val r = withContext(Dispatchers.IO) {
                        runCatching {
                            ServerApi.uploadToGithub(vm.settings.githubToken, vm.settings.githubRepo, bmp)
                        }
                    }
                    r.onSuccess { url ->
                        Toast.makeText(context, "已上传：$url", Toast.LENGTH_LONG).show()
                    }.onFailure { e ->
                        Toast.makeText(context, e.message ?: "上传失败", Toast.LENGTH_LONG).show()
                    }
                }
            },
        )
    }
}

@Composable
private fun ComposerBar(vm: ChatViewModel, onPickImage: () -> Unit) {
    Surface(tonalElevation = 3.dp) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp)
                .navigationBarsPadding()
                .imePadding(),
        ) {
            // picked reference image chip with remove badge
            vm.pendingImagePreview?.let { prev ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box {
                        Image(
                            bitmap = prev.asImageBitmap(),
                            contentDescription = "已选参考图",
                            modifier = Modifier
                                .size(56.dp)
                                .clip(RoundedCornerShape(8.dp)),
                            contentScale = ContentScale.Crop,
                        )
                        IconButton(
                            onClick = { vm.clearPickedImage() },
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .size(18.dp)
                                .background(MaterialTheme.colorScheme.scrim, CircleShape),
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "移除参考图",
                                tint = androidx.compose.ui.graphics.Color.White,
                                modifier = Modifier.size(12.dp),
                            )
                        }
                    }
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text("重绘幅度", style = MaterialTheme.typography.bodySmall)
                        Slider(
                            value = vm.denoising,
                            onValueChange = vm::onDenoisingChange,
                            valueRange = 0.1f..1.0f,
                            modifier = Modifier.width(200.dp),
                        )
                    }
                }
            }
            Row(verticalAlignment = Alignment.Bottom) {
                // circular + photo button
                Surface(
                    onClick = onPickImage,
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.padding(4.dp).size(40.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Add, contentDescription = "选择图片")
                    }
                }
                OutlinedTextField(
                    value = vm.input,
                    onValueChange = vm::onInputChange,
                    modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
                    placeholder = { Text("描述你想生成的画面…") },
                    minLines = 1,
                    maxLines = 5,
                )
                Spacer(Modifier.width(4.dp))
                // circular send button
                FloatingActionButton(
                    onClick = { vm.send() },
                    shape = CircleShape,
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(44.dp),
                ) {
                    Icon(Icons.Default.Send, contentDescription = "发送")
                }
            }
        }
    }
}

@Composable
private fun UserBubble(msg: ChatMsg.User) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Column(
            Modifier
                .clip(RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp))
                .background(MaterialTheme.colorScheme.primaryContainer)
                .padding(10.dp)
                .widthIn(max = 290.dp),
            horizontalAlignment = Alignment.End,
        ) {
            msg.preview?.let {
                Image(
                    it.asImageBitmap(), "参考图",
                    Modifier
                        .widthIn(max = 200.dp)
                        .clip(RoundedCornerShape(10.dp)),
                    contentScale = ContentScale.Fit,
                )
                Spacer(Modifier.height(6.dp))
            }
            if (msg.text.isNotBlank()) {
                Text(msg.text, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
    }
}

@Composable
private fun AiBubble(msg: ChatMsg.Ai, onImageClick: (Bitmap) -> Unit, onRetry: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Column(
            Modifier
                .clip(RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(10.dp)
                .widthIn(max = 330.dp),
        ) {
            if (msg.pending) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("AI 生成中…", style = MaterialTheme.typography.bodySmall)
                }
            }
            if (msg.error != null) {
                Text("出错了：${msg.error}", color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = onRetry, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                    Text("重试")
                }
            }
            // EVERY image the server returns, inline in this bubble, 3 per row
            if (msg.images.isNotEmpty()) {
                msg.images.chunked(3).forEach { rowImgs ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        rowImgs.forEach { bmp ->
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = "生成图",
                                modifier = Modifier
                                    .weight(1f)
                                    .aspectRatio(1f)
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable { onImageClick(bmp) },
                                contentScale = ContentScale.Crop,
                            )
                        }
                        repeat(3 - rowImgs.size) { Spacer(Modifier.weight(1f)) }
                    }
                    Spacer(Modifier.height(6.dp))
                }
            }
        }
    }
}

@Composable
private fun FullscreenViewer(
    bitmap: Bitmap,
    cloudEnabled: Boolean,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
    onShare: () -> Unit,
    onUpload: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        title = { Text("查看图片") },
        text = {
            Column {
                Image(
                    bitmap.asImageBitmap(), null,
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)),
                    contentScale = ContentScale.Fit,
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onSave) { Text("保存到相册") }
                    OutlinedButton(onClick = onShare) { Text("分享") }
                }
                if (cloudEnabled) {
                    Spacer(Modifier.height(4.dp))
                    OutlinedButton(onClick = onUpload, modifier = Modifier.fillMaxWidth()) {
                        Text("云上传（GitHub）")
                    }
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

private fun saveBitmapToGallery(context: android.content.Context, bmp: Bitmap) {
    CoroutineScope(Dispatchers.Main).launch {
        val ok = withContext(Dispatchers.IO) {
            runCatching {
                val name = "ET_${System.currentTimeMillis()}.png"
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, name)
                        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                        put(MediaStore.Images.Media.RELATIVE_PATH,
                            Environment.DIRECTORY_PICTURES + "/LocalDreamChat")
                    }
                    val uri = context.contentResolver.insert(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!
                    context.contentResolver.openOutputStream(uri)!!.use { out ->
                        bmp.compress(Bitmap.CompressFormat.PNG, 90, out)
                    }
                } else {
                    @Suppress("DEPRECATION")
                    val dir = File(
                        Environment.getExternalStoragePublicDirectory(
                            Environment.DIRECTORY_PICTURES,
                        ), "LocalDreamChat",
                    ).apply { mkdirs() }
                    File(dir, name).outputStream().use { out ->
                        bmp.compress(Bitmap.CompressFormat.PNG, 90, out)
                    }
                }
                true
            }.getOrElse { false }
        }
        Toast.makeText(context, if (ok) "已保存到相册" else "保存失败", Toast.LENGTH_SHORT).show()
    }
}

private fun shareBitmap(context: android.content.Context, bmp: Bitmap) {
    runCatching {
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        val file = File(dir, "et_chat_${System.currentTimeMillis()}.png")
        file.outputStream().use { out -> bmp.compress(Bitmap.CompressFormat.PNG, 90, out) }
        val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "分享图片"))
    }.onFailure {
        Toast.makeText(context, "分享失败：${it.message}", Toast.LENGTH_SHORT).show()
    }
}
