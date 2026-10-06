package io.github.xororz.localdream.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import io.github.xororz.localdream.R
import io.github.xororz.localdream.cloud.CloudUploader
import io.github.xororz.localdream.cloud.LogHub
import io.github.xororz.localdream.utils.Storage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** et.32: grid of generated images for multi-select cloud upload. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UploadImagesScreen(onBack: () -> Unit, onGoGenerate: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var images by remember { mutableStateOf<List<File>>(emptyList()) }
    val selected = remember { mutableStateMapOf<String, Boolean>() }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        images = withContext(Dispatchers.IO) {
            // et.34: the real history PNGs live in the app-private history tree
            // filesDir/history/<modelId>/<ts>.png|jpg, NOT the public outputs dir
            // (which only holds explicit "save to gallery" copies). Scan every
            // place a generated image can actually be, then dedupe.
            val exts = setOf("png", "jpg", "jpeg", "webp")
            fun collectImages(root: File?): List<File> {
                root ?: return emptyList()
                if (!root.exists()) return emptyList()
                return (root.walkTopDown().filter {
                    it.isFile && it.extension.lowercase() in exts
                }.toList())
            }

            val sources = mutableListOf<File>()
            // 1) App-private history tree (authoritative source of generated images).
            sources += collectImages(File(context.filesDir, "history"))
            // 2) Public / private-fallback outputs dir (where this page used to look).
            sources += collectImages(Storage.outputsDir(context))
            // 3) Gallery/Pictures/LocalDream copies written by "save to gallery".
            runCatching {
                val pics = File(
                    android.os.Environment.getExternalStoragePublicDirectory(
                        android.os.Environment.DIRECTORY_PICTURES,
                    ),
                    "LocalDream",
                )
                sources += collectImages(pics)
            }

            sources
                .distinctBy { it.absolutePath }
                .sortedByDescending { it.lastModified() }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.upload_images_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null) }
                },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().padding(8.dp)) {
            if (images.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stringResource(R.string.upload_images_empty))
                        Button(onClick = onGoGenerate, modifier = Modifier.padding(top = 8.dp)) {
                            Text(stringResource(R.string.upload_images_go_generate))
                        }
                    }
                }
            } else {
                Button(
                    onClick = {
                        val picks = images.filter { selected[it.absolutePath] == true }
                        if (picks.isEmpty()) return@Button
                        busy = true
                        scope.launch {
                            val result = runCatching { uploadPicks(context, picks) }
                            withContext(Dispatchers.Main) {
                                busy = false
                                result.onSuccess { r ->
                                    // et.35/36: never blindly say "no network". Show the real
                                    // outcome; if anything failed, surface the mapped reason.
                                    when {
                                        r.fail == 0 -> Toast.makeText(
                                            context,
                                            context.getString(R.string.upload_images_done, r.ok, picks.size),
                                            Toast.LENGTH_LONG,
                                        ).show()
                                        r.ok == 0 -> Toast.makeText(
                                            context,
                                            CloudUploader.classify(context, r.firstError ?: Exception()),
                                            Toast.LENGTH_LONG,
                                        ).show()
                                        else -> Toast.makeText(
                                            context,
                                            context.getString(R.string.upload_images_done, r.ok, picks.size) +
                                                "（" + CloudUploader.classify(context, r.firstError ?: Exception()) + "）",
                                            Toast.LENGTH_LONG,
                                        ).show()
                                    }
                                }.onFailure { e ->
                                    Toast.makeText(
                                        context,
                                        CloudUploader.classify(context, e),
                                        Toast.LENGTH_LONG,
                                    ).show()
                                }
                            }
                        }
                    },
                    enabled = !busy && selected.any { it.value },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                ) {
                    Text(
                        stringResource(
                            R.string.upload_images_confirm,
                            selected.count { it.value },
                        ),
                    )
                }
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(96.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(images, key = { it.absolutePath }) { f ->
                        val checked = selected[f.absolutePath] == true
                        Box(
                            Modifier
                                .aspectRatio(1f)
                                .clip(MaterialTheme.shapes.small)
                                .background(if (checked) Color(0x884CAF50) else Color.Transparent)
                                .clickable { selected[f.absolutePath] = !checked },
                        ) {
                            AsyncImage(
                                model = f,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                            if (checked) {
                                Text(
                                    "✓",
                                    color = Color.White,
                                    modifier = Modifier.align(Alignment.TopEnd).padding(4.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private class BatchUploadResult(val ok: Int, val fail: Int, val firstError: Throwable?)

// et.35/36: uses the shared real-upload path. No network pre-check — the real
// HTTP call decides. Per-file failures are collected (first reason surfaced to UI).
private suspend fun uploadPicks(context: android.content.Context, picks: List<File>): BatchUploadResult {
    var ok = 0
    var fail = 0
    var firstError: Throwable? = null
    picks.forEach { f ->
        runCatching {
            val stamp = SimpleDateFormat("yyyyMMddHHmm", Locale.US).format(Date())
            val ext = f.extension.lowercase().ifEmpty { "png" }
            CloudUploader.uploadFile(context, f, "png/$stamp.$ext")
        }.onSuccess {
            ok++
        }.onFailure { e ->
            fail++
            if (firstError == null) firstError = e
            runCatching {
                LogHub.log(LogHub.Category.UPLOAD, "FAIL png/${f.name}: ${e.message}")
            }
        }
    }
    return BatchUploadResult(ok, fail, firstError)
}
