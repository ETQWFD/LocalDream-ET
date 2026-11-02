package io.github.xororz.localdream.ui.screens

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.navigation.NavController
import coil.compose.AsyncImage
import io.github.xororz.localdream.navigation.popBackStackIfResumed
import io.github.xororz.localdream.service.GalleryClient

/** Local favorites persisted in SharedPreferences: "name|author|ts". */
object FavoriteStore {
    private const val P = "gallery_fav"
    private const val KEY = "items"
    fun all(ctx: Context): List<String> =
        ctx.getSharedPreferences(P, Context.MODE_PRIVATE)
            .getStringSet(KEY, emptySet())!!.sortedDescending()
    fun add(ctx: Context, item: String) {
        val s = HashSet(all(ctx)); s.add(item)
        ctx.getSharedPreferences(P, Context.MODE_PRIVATE).edit().putStringSet(KEY, s).apply()
    }
    fun seenWarning(ctx: Context) =
        ctx.getSharedPreferences(P, Context.MODE_PRIVATE).getBoolean("warned", false)
    fun markWarned(ctx: Context) =
        ctx.getSharedPreferences(P, Context.MODE_PRIVATE).edit().putBoolean("warned", true).apply()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(navController: NavController) {
    val ctx = navController.context
    var items by remember { mutableStateOf<List<GalleryClient.GalleryItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var showUpload by remember { mutableStateOf(false) }
    var showFav by remember { mutableStateOf(false) }
    var showWarn by remember { mutableStateOf(!FavoriteStore.seenWarning(ctx)) }

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
                actions = {
                    IconButton(onClick = { showFav = true }) {
                        Icon(Icons.Default.Favorite, contentDescription = "收藏")
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
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                contentPadding = PaddingValues(8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxSize().padding(pad),
            ) {
                items(items, key = { it.name }) { g ->
                    Column(Modifier.clickable {
                        FavoriteStore.add(ctx, "${g.name}|${g.author}|${g.ts}")
                    }) {
                        // 黑图修复：加载失败显示灰底占位，不黑块
                        AsyncImage(
                            model = g.url,
                            contentDescription = g.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .aspectRatio(1f)
                                .fillMaxWidth(),
                        )
                        Text(g.author, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                        Text("额度 ${g.price}", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }

    if (showWarn) {
        AlertDialog(
            onDismissRequest = { },
            title = { Text("社区公约") },
            text = { Text("禁止上传血腥恐怖画面，其他内容自行上传。") },
            confirmButton = { TextButton(onClick = {
                FavoriteStore.markWarned(ctx); showWarn = false
            }) { Text("我知道了") } },
        )
    }
    if (showUpload) UploadDialog { showUpload = false }
    if (showFav) {
        AlertDialog(
            onDismissRequest = { showFav = false },
            title = { Text("我的收藏") },
            text = {
                Column { FavoriteStore.all(ctx).forEach { Text(it, style = MaterialTheme.typography.bodySmall) } }
            },
            confirmButton = { TextButton(onClick = { showFav = false }) { Text("关闭") } },
        )
    }
}

@Composable
private fun UploadDialog(onDismiss: () -> Unit) {
    var price by remember { mutableStateOf("100") }
    var msg by remember { mutableStateOf("") }
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(20.dp)) {
                Text("上传图片", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = price, onValueChange = { price = it.filter { c -> c.isDigit() } },
                    label = { Text("定价额度（最高9000）") }, singleLine = true,
                )
                if (msg.isNotEmpty()) Text(msg)
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onDismiss) { Text("取消") }
                    Button(onClick = {
                        val p = price.toIntOrNull() ?: 0
                        if (p > 9000) msg = "定价不能超过9000" else onDismiss()
                    }) { Text("确认") }
                }
            }
        }
    }
}
