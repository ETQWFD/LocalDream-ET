package io.github.xororz.localdream.ui.screens

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import io.github.xororz.localdream.service.BackendService
import io.github.xororz.localdream.service.BackgroundGenerationService
import io.github.xororz.localdream.utils.saveImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 修图 / 图生图 (img2img) with real inpainting.
 *
 * et.168 rewrite, addressing the reported "怎么弄都有衣服 / 人物崩坏":
 *  1. 局部重绘 (mask): paint the area to change; only that region is
 *     re-diffused, so the face and pose stay intact. The mask goes to the
 *     native engine as `has_mask` + PNG base64 (same contract as the
 *     generation page uses).
 *  2. 重绘强度 slider now spans the useful 0.30–0.70 band (was 0.30–0.50,
 *     which could never remove anything but also could not restructure),
 *     with 0.35 / 0.55 / 0.65 presets.
 *  3. The working canvas is 512 by default instead of 384; a 384px长边
 *     re-diffusion is the main reason hands/limbs fused into blobs.
 *  4. Anti-anatomy negative prompt is pre-filled and editable.
 *  5. Empty prompt no longer blocks generation (quality defaults are used).
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

    var originalPicked by remember { mutableStateOf<Bitmap?>(null) }
    var sourceBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var resultBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var maskBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var maskBase64 by remember { mutableStateOf<String?>(null) }
    var maskPaths by remember { mutableStateOf<List<PathData>>(emptyList()) }
    var showInpaint by remember { mutableStateOf(false) }
    var showMaskOverlay by remember { mutableStateOf(true) }

    var positive by remember { mutableStateOf("") }
    var negative by remember {
        mutableStateOf(
            "lowres, worst quality, blurry, bad anatomy, bad hands, missing fingers, " +
                "extra fingers, extra limbs, fused limbs, extra legs, extra arms, " +
                "malformed limbs, mutated hands, poorly drawn hands, poorly drawn face, " +
                "long neck, deformed, disfigured, mutated, watermark, text, signature",
        )
    }
    var denoise by remember { mutableFloatStateOf(0.55f) }
    var canvasSize by remember { mutableIntStateOf(512) }
    var steps by remember { mutableIntStateOf(12) }

    var working by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var showExitConfirm by remember { mutableStateOf(false) }
    var posOpen by remember { mutableStateOf(true) }
    var negOpen by remember { mutableStateOf(false) }
    var advancedOpen by remember { mutableStateOf(false) }

    fun loadIntoCanvas(bitmap: Bitmap) {
        val scaled = scaleToCanvas(bitmap, canvasSize)
        sourceBitmap = scaled
        maskBitmap = null
        maskBase64 = null
        maskPaths = emptyList()
        resultBitmap = null
        errorMsg = null
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                val bmp = withContext(Dispatchers.IO) { decodeSampled(context, uri, 1536) }
                if (bmp == null) {
                    errorMsg = "图片读取失败，请换一张"
                } else {
                    originalPicked = bmp
                    loadIntoCanvas(bmp)
                }
            }
        }
    }

    fun exit() {
        if (working) showExitConfirm = true else navController.popBackStack()
    }
    BackHandler { exit() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("修图 · $modelName", maxLines = 1)
                        Text(
                            "局部重绘 + 可调重绘强度",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { exit() }) {
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
            // ---- preview -------------------------------------------------
            val preview = resultBitmap ?: sourceBitmap
            if (preview != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(240.dp)
                        .clip(RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        bitmap = preview.asImageBitmap(),
                        contentDescription = "预览",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                    )
                    val mask = maskBitmap
                    if (mask != null && showMaskOverlay && resultBitmap == null) {
                        Image(
                            bitmap = mask.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer(alpha = 0.55f),
                            contentScale = ContentScale.Fit,
                            colorFilter = ColorFilter.tint(Color(0xFFFF1744)),
                        )
                    }
                    if (mask != null) {
                        Surface(
                            modifier = Modifier.align(Alignment.BottomStart).padding(8.dp),
                            color = MaterialTheme.colorScheme.errorContainer,
                            shape = MaterialTheme.shapes.small,
                        ) {
                            Text(
                                "局部重绘：已涂 ${maskPaths.size} 笔",
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            )
                        }
                    }
                    if (sourceBitmap != null) {
                        Surface(
                            modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shape = MaterialTheme.shapes.small,
                        ) {
                            Text(
                                "${sourceBitmap!!.width} × ${sourceBitmap!!.height}",
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            )
                        }
                    }
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { picker.launch("image/*") },
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.Brush, contentDescription = null)
                        Spacer(Modifier.height(8.dp))
                        Text("点击选择要修的图片")
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { picker.launch("image/*") },
                    enabled = !working,
                    modifier = Modifier.weight(1f),
                ) { Text(if (sourceBitmap == null) "选择图片" else "重新选择") }
                OutlinedButton(
                    onClick = { showInpaint = true },
                    enabled = sourceBitmap != null && !working,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.Brush, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("局部重绘")
                }
            }
            if (maskBitmap != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = showMaskOverlay, onCheckedChange = { showMaskOverlay = it })
                    Spacer(Modifier.width(8.dp))
                    Text("显示蒙版", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = {
                        maskBitmap = null
                        maskBase64 = null
                        maskPaths = emptyList()
                    }) { Text("清除蒙版") }
                }
            }

            // ---- canvas size ---------------------------------------------
            Text("生成尺寸（长边）", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(512, 640, 768).forEach { size ->
                    FilterChip(
                        selected = canvasSize == size,
                        onClick = {
                            if (!working && canvasSize != size) {
                                canvasSize = size
                                originalPicked?.let { loadIntoCanvas(it) }
                            }
                        },
                        label = { Text("$size") },
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(
                    "小图更快，大图更稳",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // ---- prompt ---------------------------------------------------
            SectionHeader("提示词（中文也可）", posOpen) { posOpen = !posOpen }
            if (posOpen) {
                OutlinedTextField(
                    value = positive,
                    onValueChange = { positive = it },
                    label = { Text("想画成什么；留空则只做画质/细节修正") },
                    enabled = !working,
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 4,
                )
            }

            SectionHeader("负向提示词（防畸形，可编辑）", negOpen) { negOpen = !negOpen }
            if (negOpen) {
                OutlinedTextField(
                    value = negative,
                    onValueChange = { negative = it },
                    label = { Text("负向提示词") },
                    enabled = !working,
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 6,
                )
                Text(
                    "人物崩坏时，保留这些词并把重绘强度降到 0.45 以下更稳。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // ---- denoise --------------------------------------------------
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("重绘强度", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.width(8.dp))
                Text(
                    "%.2f".format(denoise),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    when {
                        denoise < 0.45f -> "轻微改色/加细节"
                        denoise < 0.6f -> "推荐：换装/换风格"
                        else -> "大改：容易崩，建议配合蒙版"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Slider(
                value = denoise,
                onValueChange = { denoise = it },
                enabled = !working,
                valueRange = 0.30f..0.70f,
                steps = 39,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0.35f to "0.35 轻微", 0.55f to "0.55 推荐", 0.65f to "0.65 强力").forEach { (v, label) ->
                    AssistChip(
                        onClick = { if (!working) denoise = v },
                        label = { Text(label) },
                    )
                }
            }
            Text(
                "只改局部时把蒙版涂在要改的地方（脸、背景别涂），强度 0.6 左右即可；" +
                    "整张图重绘时建议 0.45–0.55，超过 0.65 人物容易崩。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // ---- advanced ------------------------------------------------
            SectionHeader("高级参数", advancedOpen) { advancedOpen = !advancedOpen }
            if (advancedOpen) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("步数 ${steps}", style = MaterialTheme.typography.bodySmall)
                    Slider(
                        value = steps.toFloat(),
                        onValueChange = { steps = it.toInt() },
                        valueRange = 8f..20f,
                        steps = 11,
                        enabled = !working,
                        modifier = Modifier.weight(1f).padding(start = 8.dp),
                    )
                }
                Text(
                    "当前引擎：$backendType · 模型 $modelName\n" +
                        "写实模型画二次元容易“出油”，画二次元建议在首页“修图”里换一个二次元模型。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (working) {
                LinearProgressIndicator(
                    progress = { progress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("修图中 ${(progress * 100).toInt()}%")
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = {
                        runCatching {
                            context.startService(
                                Intent(context, BackgroundGenerationService::class.java).apply {
                                    action = "io.github.xororz.localdream.STOP_GENERATION"
                                },
                            )
                        }
                        working = false
                    }) { Text("停止") }
                }
            }
            errorMsg?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            Button(
                onClick = {
                    startEdit(
                        context = context,
                        source = sourceBitmap,
                        prompt = positive.ifBlank { DEFAULT_POSITIVE },
                        negative = negative,
                        denoise = denoise,
                        steps = steps,
                        modelId = modelId,
                        backendType = backendType,
                        maskBase64 = maskBase64,
                        workingRef = { working = it; if (it) progress = 0f },
                    )
                },
                enabled = sourceBitmap != null && !working,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (working) "修图中…" else if (maskBitmap != null) "只重绘蒙版区域" else "确认修图")
            }

            resultBitmap?.let { result ->
                ElevatedCard(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Text("修图结果", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(8.dp))
                        Image(
                            bitmap = result.asImageBitmap(),
                            contentDescription = "修图结果",
                            modifier = Modifier.fillMaxWidth().height(220.dp),
                            contentScale = ContentScale.Fit,
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalButton(onClick = {
                                scope.launch {
                                    saveImage(
                                        context = context,
                                        bitmap = result,
                                        onSuccess = {
                                            Toast.makeText(context, "已保存到相册", Toast.LENGTH_SHORT).show()
                                        },
                                        onError = { errorMsg = it },
                                    )
                                }
                            }) {
                                Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("保存")
                            }
                            OutlinedButton(onClick = {
                                originalPicked = result
                                loadIntoCanvas(result)
                            }) { Text("用这张继续修") }
                            TextButton(onClick = { resultBitmap = null }) { Text("关闭") }
                        }
                    }
                }
            }
        }
    }

    if (showInpaint) {
        val base = sourceBitmap
        if (base != null) {
            InpaintScreen(
                originalBitmap = base,
                existingMaskBitmap = maskBitmap,
                existingPathHistory = maskPaths,
                onInpaintComplete = { maskB64, _, maskBmp, pathHistory ->
                    showInpaint = false
                    maskBitmap = maskBmp
                    maskBase64 = maskB64
                    maskPaths = pathHistory
                    showMaskOverlay = true
                },
                onCancel = { showInpaint = false },
            )
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

    // Collect engine state: progress / result / error.
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
private fun SectionHeader(title: String, open: Boolean, onToggle: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable { onToggle() },
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        Icon(
            imageVector = if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
            contentDescription = null,
        )
    }
}

private const val DEFAULT_POSITIVE =
    "masterpiece, best quality, highly detailed, sharp focus, correct anatomy"

/** Contain-fits [src] so its long edge equals [canvas] and both sides are /8. */
internal fun scaleToCanvas(src: Bitmap, canvas: Int): Bitmap {
    val longEdge = maxOf(src.width, src.height)
    val scale = canvas.toFloat() / longEdge.toFloat()
    var w = (src.width * scale).toInt().coerceAtLeast(64)
    var h = (src.height * scale).toInt().coerceAtLeast(64)
    w = (w / 8) * 8
    h = (h / 8) * 8
    if (w == src.width && h == src.height) return src
    return Bitmap.createScaledBitmap(src, w, h, true)
}

private fun decodeSampled(context: Context, uri: Uri, maxEdge: Int): Bitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
    var sample = 1
    while (bounds.outWidth / sample > maxEdge || bounds.outHeight / sample > maxEdge) {
        sample *= 2
    }
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
}.getOrNull()

private fun startEdit(
    context: Context,
    source: Bitmap?,
    prompt: String,
    negative: String,
    denoise: Float,
    steps: Int,
    modelId: String,
    backendType: String,
    maskBase64: String?,
    workingRef: (Boolean) -> Unit,
) {
    if (source == null) {
        Toast.makeText(context, "请先选择要修的图片", Toast.LENGTH_SHORT).show()
        return
    }
    try {
        val w = source.width
        val h = source.height

        val baos = ByteArrayOutputStream()
        source.compress(Bitmap.CompressFormat.PNG, 100, baos)
        val b64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
        FileOutputStream(File(context.filesDir, "tmp.txt")).use { it.write(b64.toByteArray()) }

        val maskFile = File(context.filesDir, "mask.txt")
        if (maskBase64.isNullOrBlank()) {
            runCatching { if (maskFile.exists()) maskFile.delete() }
        } else {
            FileOutputStream(maskFile).use { it.write(maskBase64.toByteArray()) }
        }

        // launch/attach the native engine for this model (binds 8081)
        runCatching {
            val beIntent = Intent(context, BackendService::class.java).apply {
                putExtra("modelId", modelId)
                putExtra("backendType", backendType)
                putExtra("width", w)
                putExtra("height", h)
            }
            context.startForegroundService(beIntent)
        }

        val intent = Intent(context, BackgroundGenerationService::class.java).apply {
            putExtra("prompt", prompt)
            putExtra("negative_prompt", negative)
            putExtra("steps", steps)
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
            if (!maskBase64.isNullOrBlank()) putExtra("has_mask", true)
            putExtra("modelId", modelId)
            putExtra("backendType", backendType)
            putExtra("backend_host", "127.0.0.1:8081")
        }
        context.startForegroundService(intent)
        workingRef(true)
    } catch (e: Exception) {
        Log.e("EditScreen", "startEdit failed", e)
        Toast.makeText(context, "启动修图失败：${e.message}", Toast.LENGTH_LONG).show()
    }
}
