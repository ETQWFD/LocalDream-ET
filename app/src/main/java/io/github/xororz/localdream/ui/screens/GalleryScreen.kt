package io.github.xororz.localdream.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.navigation.NavController
import io.github.xororz.localdream.navigation.popBackStackIfResumed
import coil.compose.AsyncImage
import io.github.xororz.localdream.service.GalleryClient

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(navController: NavController) {
    var items by remember { mutableStateOf<List<GalleryClient.GalleryItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var showUpload by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        items = GalleryClient.list()
        loading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("图片社区") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStackIfResumed() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showUpload = true }) {
                Icon(Icons.Default.Add, contentDescription = "上传")
            }
        },
    ) { pad ->
        if (loading) {
            Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (items.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
                Text("社区暂无图片，点右下角上传第一张")
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                contentPadding = PaddingValues(8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxSize().padding(pad),
            ) {
                items(items, key = { it.name }) { g ->
                    Column(
                        Modifier.clickable { /* 购买页：点他人图购买 */ }
                    ) {
                        AsyncImage(
                            model = g.url,
                            contentDescription = g.name,
                            modifier = Modifier.aspectRatio(1f),
                        )
                        Text(g.author, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                        Text("额度 ${g.price}", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }

    if (showUpload) {
        UploadDialog(
            onDismiss = { showUpload = false },
            onDone = {
                showUpload = false
                loading = true
                items = GalleryClient.list()
                loading = false
            },
        )
    }
}

@Composable
private fun UploadDialog(onDismiss: () -> Unit, onDone: () -> Unit) {
    var price by remember { mutableStateOf("100") }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(20.dp)) {
                Text("上传图片到社区", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = price,
                    onValueChange = { price = it.filter { c -> c.isDigit() } },
                    label = { Text("定价额度（最高9000）") },
                    singleLine = true,
                )
                if (msg.isNotEmpty()) { Spacer(Modifier.height(8.dp)); Text(msg) }
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onDismiss) { Text("取消") }
                    Button(enabled = !busy, onClick = {
                        val p = price.toIntOrNull() ?: 0
                        if (p > 9000) { msg = "定价不能超过9000"; return@Button }
                        busy = true
                        // 上传本机最近一张示意图（真实多图选择在历史目录扫描接入）
                        Thread {
                            val ok = GalleryClient.upload(
                                ByteArray(0), "local", p
                            )
                            // ByteArray(0) 占位：实际接入时传选中的 png 字节
                            busy = false
                            if (ok) onDone() else msg = "上传失败，请重试"
                        }.start()
                    }) { Text("确认上传") }
                }
            }
        }
    }
}
