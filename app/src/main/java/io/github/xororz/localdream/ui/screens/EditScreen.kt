package io.github.xororz.localdream.ui.screens

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import io.github.xororz.localdream.service.BackendService
import io.github.xororz.localdream.service.BackgroundGenerationService
import io.github.xororz.localdream.data.HistoryManager
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * et.66: 独立修图(img2img)页面。不复用文生图页。
 * 上传图 -> base64 PNG 写 filesDir/tmp.txt -> BackgroundGenerationService(has_image=true, denoise)。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditScreen(
    navController: NavController,
    modelId: String,
    modelName: String,
    backendType: String,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val historyManager = remember { HistoryManager(context) }

    var sourceUri by remember { mutableStateOf<Uri?>(null) }
    var sourceBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var resultBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var positive by remember { mutableStateOf("") }
    var negative by remember {
        mutableStateOf("lowres, bad anatomy, bad hands, missing fingers, extra fingers, worst quality, watermark")
    }
    var denoise by remember { mutableStateOf(0.45f) }
    var working by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var showExitConfirm by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            sourceUri = uri
            resultBitmap = null
            errorMsg = null
            try {
                val bmp = context.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it)
                }
                sourceBitmap = bmp
            } catch (e: Exception) {
                errorMsg = "图片读取失败：${e.message}"
            }
        }
    }

    BackHandlerIfWorking(working = working) { showExitConfirm = true }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("修图 · $modelName") },
                navigationIcon = {
                    IconButton(onClick = {
                        if (working) showExitConfirm = true else navController.popBackStack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 上传预览
            val preview = resultBitmap ?: sourceBitmap
            if (preview != null) {
                Image(
                    bitmap = preview.asImageBitmap(),
                    contentDescription = "预览",
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp),
                    contentScale = ContentScale.Fit,
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp),
                    contentAlignment = Alignment.Center,
                ) { Text("尚未选择图片") }
            }
            OutlinedButton(
                onClick = { picker.launch("image/*") },
                enabled = !working,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (sourceBitmap == null) "选择图片" else "重新选择") }

            // et.72: collapsible prompt boxes to save screen space.
            var posOpen by remember { mutableStateOf(true) }
            var negOpen by remember { mutableStateOf(false) }
            Row(verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clickable { posOpen = !posOpen }) {
                Text("提示词（中文也可）", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Icon(if (posOpen) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = null)
            }
            if (posOpen) {
                OutlinedTextField(
                    value = positive,
                    onValueChange = { positive = it },
                    label = { Text("提示词（中文也可）") },
                    enabled = !working,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clickable { negOpen = !negOpen }) {
                Text("负向提示词（默认已加防畸形词）", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Icon(if (negOpen) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = null)
            }
            if (negOpen) {
                OutlinedTextField(
                    value = negative,
                    onValueChange = { negative = it },
                    label = { Text("负向提示词") },
                    enabled = !working,
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 4,
                )
            }

            Text("重绘强度：%.2f".format(denoise))
            Slider(
                value = denoise,
                onValueChange = { denoise = it },
                enabled = !working,
                valueRange = 0.3f..0.5f,
            )

            if (working) {
                LinearProgressIndicator(
                    progress = { progress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("修图中 %d%%".format((progress * 100).toInt()))
            }
            errorMsg?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
            }

            Button(
                onClick = { startEdit(context, sourceBitmap, positive, negative, denoise,
                    modelId, backendType, workingRef = { working = it }) },
                enabled = !working,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (working) "修图中…" else "确认修图") }
        }
    }

    if (showExitConfirm) {
        AlertDialog(
            onDismissRequest = { showExitConfirm = false },
            title = { Text("是否结束当前修图？") },
            text = { Text("结束将停止本次生成并返回。") },
            confirmButton = {
                TextButton(onClick = {
                    showExitConfirm = false
                    runCatching {
                        context.startService(
                            Intent(context, BackgroundGenerationService::class.java).apply {
                                action = "io.github.xororz.localdream.STOP_GENERATION"
                            },
                        )
                    }
                    working = false
                    navController.popBackStack()
                }) { Text("结束") }
            },
            dismissButton = {
                TextButton(onClick = { showExitConfirm = false }) { Text("继续") }
            },
        )
    }

    // 收集生成状态 -> 就地进度/结果/错误
    LaunchedEffect(Unit) {
        BackgroundGenerationService.generationState.collect { st ->
            when (st) {
                is BackgroundGenerationService.GenerationState.Progress -> {
                    working = true
                    progress = st.progress
                }
                is BackgroundGenerationService.GenerationState.Complete -> {
                    working = false
                    progress = 1f
                    resultBitmap = st.bitmap
                    Toast.makeText(context, "修图完成", Toast.LENGTH_SHORT).show()
                }
                is BackgroundGenerationService.GenerationState.Error -> {
                    working = false
                    errorMsg = st.message
                }
                else -> {}
            }
        }
    }
}

@Composable
private fun BackHandlerIfWorking(working: Boolean, onBack: () -> Unit) {
    // 轻量占位：返回由顶栏按钮处理；系统返回键通过 onBack 参数在需要时拦截。
    // 此处仅保证 working 状态下顶栏返回弹确认（已在顶栏实现）。
}

private fun startEdit(
    context: Context,
    source: Bitmap?,
    positive: String,
    negative: String,
    denoise: Float,
    modelId: String,
    backendType: String,
    workingRef: (Boolean) -> Unit,
) {
    if (source == null) {
        Toast.makeText(context, "请先选择要修的图片", Toast.LENGTH_SHORT).show()
        return
    }
    if (positive.isBlank()) {
        Toast.makeText(context, "请输入提示词", Toast.LENGTH_SHORT).show()
        return
    }
    try {
        // 缩放到 384 长边，base64 PNG 写 tmp.txt（BackgroundGenerationService 按 has_image 读取）。
        // et.83: 宽高向下对齐到 8 的倍数，否则 VAE 解码花图(Size:349x512 -> 344x...).
        val maxSide = 384f
        val scale = maxSide / maxOf(source.width, source.height)
        var w = (source.width * scale).toInt().coerceAtLeast(64)
        var h = (source.height * scale).toInt().coerceAtLeast(64)
        w = (w / 8) * 8
        h = (h / 8) * 8
        val scaled = Bitmap.createScaledBitmap(source, w, h, true)
        val baos = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.PNG, 100, baos)
        val b64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
        FileOutputStream(File(context.filesDir, "tmp.txt")).use { it.write(b64.toByteArray()) }

        // et.73: like txt2img, first converge the engine for this model
        // (startBackend launches the child process + binds 8081). Without
        // this we only hit 8081 and fail with "引擎8081未就绪".
        runCatching {
            val beIntent = Intent(context, io.github.xororz.localdream.service.BackendService::class.java).apply {
                putExtra("modelId", modelId)
                putExtra("backendType", backendType)
                putExtra("width", w)
                putExtra("height", h)
            }
            context.startForegroundService(beIntent)
        }

        val intent = Intent(context, BackgroundGenerationService::class.java).apply {
            putExtra("prompt", positive)
            putExtra("negative_prompt", negative)
            putExtra("steps", 12)
            putExtra("cfg", 7f)
            putExtra("width", w)
            putExtra("height", h)
            putExtra("effective_width", w)
            putExtra("effective_height", h)
            putExtra("denoise_strength", denoise)
            putExtra("use_opencl", false)
            putExtra("scheduler", "dpm")
            putExtra("aspect_ratio", "1:1")
            putExtra("has_image", true)
            putExtra("modelId", modelId)
            putExtra("backendType", backendType)
            putExtra("backend_host", "127.0.0.1:8081")
        }
        context.startForegroundService(intent)
        workingRef(true)
    } catch (e: Exception) {
        Toast.makeText(context, "启动修图失败：${e.message}", Toast.LENGTH_LONG).show()
    }
}
