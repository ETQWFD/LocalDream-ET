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
    var q by remember { mutableStateOf("") }
    var showWarn by remember { mutableStateOf(!FavoriteStore.seenWarning(ctx)) }

    LaunchedEffect(Unit) {
        items = GalleryClient.list()
        loading = false
    }
    // et.213: search by author / image name.
    val filtered = remember(items, q) {
        if (q.isBlank()) items else items.filter {
            it.author.contains(q, true) || it.name.contains(q, true) || it.title.contains(q, true)
        }
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
            Column(Modifier.fillMaxSize().padding(pad)) {
                // et.213: top search box (by author / image name).
                OutlinedTextField(
                    value = q, onValueChange = { q = it },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                    placeholder = { Text("搜索作品 / 作者") }, singleLine = true,
                )
                LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                contentPadding = PaddingValues(8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(filtered, key = { it.name }) { g ->
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
    if (showUpload) UploadDialog(ctx) {
        showUpload = false
        // et.144: refresh community list after a successful publish.
        loading = true
        items = GalleryClient.list()
        loading = false
    }
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
private fun UploadDialog(ctx: Context, onDismiss: () -> Unit) {
    var price by remember { mutableStateOf("100") }
    var name by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }
    var showPicker by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf<List<java.io.File>>(emptyList()) }
    // et.144: scan app-generated pngs from filesDir (history output).
    val allImages = remember(ctx) {
        ctx.filesDir.walkTopDown().filter { it.isFile && it.name.endsWith(".png") }
            .sortedByDescending { it.lastModified() }.take(60).toList()
    }
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(20.dp)) {
                Text("上传作品", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("作品名") }, singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = price, onValueChange = { price = it.filter { c -> c.isDigit() } },
                    label = { Text("售价额度（最高9000）") }, singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { showPicker = true }, Modifier.fillMaxWidth()) {
                    Text(if (picked.isEmpty()) "选择图片" else "已选 ${picked.size} 张（首张为封面）")
                }
                if (showPicker) {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(4),
                        modifier = Modifier.height(160.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        items(allImages.size) { i ->
                            val f = allImages[i]
                            val sel = picked.contains(f)
                            AsyncImage(
                                model = f, contentDescription = f.name,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.clickable {
                                    if (sel) picked = picked - f
                                    else if (picked.size < 9) picked = picked + f
                                    else msg = "最多选9张"
                                }.size(56.dp),
                            )
                        }
                    }
                }
                if (msg.isNotEmpty()) Text(msg)
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onDismiss) { Text("取消") }
                    Button(onClick = {
                        val p = price.toIntOrNull() ?: 0
                        when {
                            p > 9000 -> msg = "无法发布"
                            picked.isEmpty() -> msg = "请先选择图片"
                            else -> {
                                // et.144: upload cover (first) straight to GitHub raw repo.
                                val ok = GalleryClient.upload(
                                    picked.first().readBytes(),
                                    author = "ETQWFD", price = p, name = name,
                                )
                                msg = if (ok) "发布成功" else "发布失败，请重试"
                                if (ok) onDismiss()
                            }
                        }
                    }) { Text("确认发布") }
                }
            }
        }
    }
}
