package io.github.xororz.localdream.ui.screens

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.xororz.localdream.R
import io.github.xororz.localdream.service.BackgroundGenerationService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * et.27: whole-image img2img workbench ("修图"). Reuses the existing
 * BackgroundGenerationService img2img path (tmp.txt base64 init image,
 * has_image=true, denoise_strength clamped 0.3–0.7). No new engine code.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RestoreScreen(
    modelId: String,
    backendType: String,
    useOpenCL: Boolean,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var prompt by remember { mutableStateOf("") }
    var negative by remember {
        mutableStateOf("lowres, blurry, deformed, extra fingers, bad anatomy, watermark, text")
    }
    var strength by remember { mutableStateOf(0.45f) }
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val bmp = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use {
                        BitmapFactory.decodeStream(it)
                    }
                }.getOrNull()
            }
            preview = bmp
            msg = null
        }
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.restore_title)) },
            navigationIcon = { TextButton(onClick = onBack) { Text(stringResource(R.string.back)) } })
    }) { pad ->
        Column(
            Modifier
                .padding(pad)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            Text(stringResource(R.string.restore_tagger_hint), style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(12.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                val p = preview
                if (p != null) {
                    Image(p.asImageBitmap(), null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize())
                } else {
                    Button(onClick = { picker.launch("image/*") }) {
                        Text(stringResource(R.string.restore_pick_image))
                    }
                }
            }
            if (preview != null) {
                TextButton(onClick = { picker.launch("image/*") }) {
                    Text(stringResource(R.string.restore_repick))
                }
            }
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = prompt,
                onValueChange = { prompt = it },
                label = { Text(stringResource(R.string.restore_prompt)) },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = negative,
                onValueChange = { negative = it },
                label = { Text(stringResource(R.string.restore_negative)) },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.restore_strength, "%.2f".format(strength)))
            Slider(
                value = strength,
                onValueChange = { strength = it.coerceIn(0.3f, 0.7f) },
                valueRange = 0.3f..0.7f,
            )
            Spacer(Modifier.height(12.dp))
            Button(
                enabled = preview != null && !busy,
                onClick = {
                    val bmp = preview
                    if (bmp == null) {
                        msg = context.getString(R.string.restore_pick_first)
                        return@Button
                    }
                    busy = true
                    msg = null
                    io.github.xororz.localdream.utils.BatteryOptimization.ensureIgnoring(context)
                    scope.launch {
                        runCatching {
                            withContext(Dispatchers.IO) {
                                // Compute 64-aligned size from the chosen image's
                                // aspect ratio, long edge capped by RAM tier.
                                val ratio = if (bmp.width >= bmp.height)
                                    "${bmp.width / bmp.height}%d".format(1) else "1:${bmp.height / bmp.width}"
                                val (w, h) = sd15SizeForRatio(
                                    if (bmp.width >= bmp.height) "1:1" else "1:1",
                                    io.github.xororz.localdream.utils.DeviceCapabilities
                                        .sd15LongEdgeForRam(context),
                                )
                                val targetW = if (bmp.width >= bmp.height) w else h
                                val targetH = if (bmp.width >= bmp.height) h else w
                                val baos = ByteArrayOutputStream()
                                bmp.compress(Bitmap.CompressFormat.PNG, 100, baos)
                                val b64 = android.util.Base64.encodeToString(
                                    baos.toByteArray(), android.util.Base64.NO_WRAP,
                                )
                                java.io.File(context.filesDir, "tmp.txt").writeText(b64)
                                // 1) declare/start the chosen model engine first.
                                val be = Intent(context, io.github.xororz.localdream.service.BackendService::class.java).apply {
                                    putExtra("modelId", modelId)
                                    putExtra("backendType", backendType)
                                    putExtra("use_opencl", useOpenCL)
                                    putExtra("width", targetW)
                                    putExtra("height", targetH)
                                }
                                context.startForegroundService(be)
                                // Wait for the engine socket (8081) to be ready
                                // before firing /generate, matching the normal
                                // ModelRunScreen flow. Timeout ~45s.
                                val deadline = System.currentTimeMillis() + 45_000L
                                var ready = false
                                while (System.currentTimeMillis() < deadline) {
                                    runCatching {
                                        java.net.Socket("127.0.0.1", 8081).use { }
                                        ready = true
                                    }
                                    if (ready) break
                                    kotlinx.coroutines.delay(800)
                                }
                                if (!ready) error("engine start timeout")
                                // 2) then run img2img generation.
                                val i = Intent(context, BackgroundGenerationService::class.java).apply {
                                    putExtra("prompt", prompt)
                                    putExtra("negative", negative)
                                    putExtra("steps", 20)
                                    putExtra("cfg", 7f)
                                    putExtra("width", targetW)
                                    putExtra("height", targetH)
                                    putExtra("has_image", true)
                                    putExtra("denoise_strength", strength)
                                }
                                context.startForegroundService(i)
                            }
                        }.onSuccess {
                            msg = context.getString(R.string.restore_started)
                        }.onFailure { e ->
                            msg = e.message
                        }
                        busy = false
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.restore_run)) }
            msg?.let { Text(it) }
        }
    }
}
