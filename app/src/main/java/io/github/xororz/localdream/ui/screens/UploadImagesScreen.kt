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
import io.github.xororz.localdream.cloud.CloudClient
import io.github.xororz.localdream.cloud.CloudConfig
import io.github.xororz.localdream.cloud.LogHub
import io.github.xororz.localdream.cloud.SecureTokenStore
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
            val out = Storage.outputsDir(context)
            val list = (out.listFiles()?.toList() ?: emptyList())
                .filter { it.isFile && it.extension.lowercase() in setOf("png", "jpg", "jpeg", "webp") }
                .sortedByDescending { it.lastModified() }
            list
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
                                result.onSuccess { (ok, fail) ->
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.upload_images_done, ok, picks.size),
                                        Toast.LENGTH_LONG,
                                    ).show()
                                }.onFailure { e ->
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.upload_images_no_net) + ": " + (e.message ?: "?"),
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

private suspend fun uploadPicks(context: android.content.Context, picks: List<File>): Pair<Int, Int> {
    val store = SecureTokenStore(context)
    val auth = store.loggedInProviders().firstOrNull()
        ?: error(context.getString(io.github.xororz.localdream.R.string.logs_upload_need_login))
    val provider = auth.provider
    val token = store.accessToken(provider) ?: error("no token")
    CloudClient.ensurePrivateRepo(provider, token, auth.login)
    val owner = auth.owner ?: auth.login
    val repo = auth.repo ?: CloudConfig.BACKUP_REPO
    var ok = 0
    var fail = 0
    picks.forEach { f ->
        runCatching {
            val stamp = SimpleDateFormat("yyyyMMddHHmmss", Locale.US).format(Date())
            val ext = f.extension.lowercase().ifEmpty { "png" }
            CloudClient.uploadContent(
                provider, token, owner, repo,
                "png/${stamp}_${f.hashCode().and(0xffff)}.${ext}",
                f.readBytes(),
                "upload image $stamp",
            )
            ok++
        }.onFailure { e ->
            fail++
            runCatching {
                LogHub.log(LogHub.Category.UPLOAD, "FAIL png/${f.name}: ${e.message}")
            }
        }
    }
    return ok to fail
}
