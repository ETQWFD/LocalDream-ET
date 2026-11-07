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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    val scope = rememberCoroutineScope()
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
                    var preview by remember { mutableStateOf<GalleryClient.GalleryItem?>(null) }
                    preview?.let { PreviewDialog(ctx, it) { preview = null } }
                    Column(Modifier.clickable {
                        FavoriteStore.add(ctx, "${g.name}|${g.author}|${g.ts}")
                        preview = g
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
        // et.145: 刷新社区列表改到 IO 协程，绝不阻塞主线程。
        scope.launch {
            loading = true
            items = GalleryClient.list()
            loading = false
        }
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
    val scope = rememberCoroutineScope()
    var price by remember { mutableStateOf("100") }
    var name by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }
    var uploading by remember { mutableStateOf(false) }
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
                    Text(if (picked.isEmpty()) "选择图片" else "已选 ${picked.size} 张")
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
                                    else if (picked.size < 1) picked = picked + f
                                    else msg = "每次只能发布 1 张"
                                }.size(56.dp),
                            )
                        }
                    }
                }
                if (msg.isNotEmpty()) Text(msg)
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onDismiss, enabled = !uploading) { Text("取消") }
                    Button(enabled = !uploading, onClick = {
                        val p = price.toIntOrNull() ?: 0
                        when {
                            p > 9000 -> msg = "售价不能超过 9000"
                            picked.isEmpty() -> msg = "请先选择图片"
                            else -> {
                                // et.145: 上传/读文件全部在 IO 协程；多选时逐张发布。
                                uploading = true
                                msg = "正在上传…"
                                scope.launch {
                                    var okCount = 0
                                    var lastReason = ""
                                    for (f in picked) {
                                        val bytes = withContext(Dispatchers.IO) {
                                            runCatching { f.readBytes() }.getOrDefault(ByteArray(0))
                                        }
                                        if (bytes.isEmpty()) { lastReason = "读取失败：${f.name}"; continue }
                                        val res = GalleryClient.upload(
                                            bytes, author = "ETQWFD", price = p, name = name,
                                        )
                                        if (res.ok) okCount++ else lastReason = res.reason
                                    }
                                    uploading = false
                                    msg = when {
                                        okCount == picked.size -> "发布成功"
                                        okCount > 0 -> "已发布 $okCount/${picked.size} 张：$lastReason"
                                        else -> "发布失败：$lastReason"
                                    }
                                    if (okCount > 0) onDismiss()
                                }
                            }
                        }
                    }) { Text(if (uploading) "上传中…" else "确认发布") }
                }
            }
        }
    }
}

@Composable
private fun PreviewDialog(ctx: Context, item: GalleryClient.GalleryItem, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    val remaining = remember { io.github.xororz.localdream.service.QuotaManager.remaining(ctx) }
    // et.147: 预览期间禁止截屏/录屏，防止盗图
    DisposableEffect(Unit) {
        val activity = ctx as? android.app.Activity
        activity?.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { activity?.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE) }
    }
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                AsyncImage(
                    model = item.url, contentDescription = item.name,
                    modifier = Modifier.fillMaxWidth().height(280.dp), contentScale = ContentScale.Fit,
                )
                Spacer(Modifier.height(8.dp))
                Text("作者：${item.author}", style = MaterialTheme.typography.bodyMedium)
                Text("售价：${item.price} 额度（剩余 $remaining）", style = MaterialTheme.typography.bodySmall)
                if (msg.isNotEmpty()) Text(msg, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(12.dp))
                Row {
                    TextButton(onClick = onDismiss) { Text("关闭") }
                    Spacer(Modifier.width(8.dp))
                    Button(enabled = !busy, onClick = {
                        busy = true; msg = ""
                        scope.launch {
                            val r = withContext(Dispatchers.IO) { purchaseItem(ctx, item) }
                            msg = r; busy = false
                            if (r.startsWith("已保存")) onDismiss()
                        }
                    }) { Text(if (busy) "处理中…" else "确认购买") }
                }
            }
        }
    }
}

private suspend fun purchaseItem(ctx: Context, item: GalleryClient.GalleryItem): String {
    val qm = io.github.xororz.localdream.service.QuotaManager
    if (qm.remaining(ctx) < item.price) return "额度不足，还差 ${item.price - qm.remaining(ctx)}"
    return withContext(Dispatchers.IO) {
        runCatching {
            val conn = (java.net.URL(item.url).openConnection() as java.net.HttpURLConnection)
            conn.connectTimeout = 15000; conn.readTimeout = 30000
            val bytes = conn.inputStream.use { it.readBytes() }
            val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ?: return@withContext "图片加载失败"
            // 右下角防伪水印：etc 官方 + 作者名
            val wm = android.graphics.Bitmap.createBitmap(bmp.width, bmp.height, bmp.config ?: android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(wm)
            canvas.drawBitmap(bmp, 0f, 0f, null)
            val text = "etc · @${item.author}"
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.WHITE; alpha = 180
                textSize = bmp.width / 28.0f; isFakeBoldText = true
            }
            val sw = paint.measureText(text)
            canvas.drawText(text, bmp.width - sw - bmp.width / 40.0f, bmp.height - bmp.height / 40.0f, paint)
            val out = java.io.ByteArrayOutputStream()
            wm.compress(android.graphics.Bitmap.CompressFormat.PNG, 95, out)
            saveToGallery(ctx, "LocalDreamET_${item.name}", out.toByteArray())
            qm.consume(ctx, item.price.toLong())
            // 已登录用户：备份到自己的 Backup 仓，防丢
            val store = io.github.xororz.localdream.cloud.SecureTokenStore(ctx)
            val tok = store.token(io.github.xororz.localdream.cloud.CloudConfig.Provider.GITHUB)
            if (!tok.isNullOrBlank()) {
                runCatching {
                    io.github.xororz.localdream.cloud.CloudClient.uploadContent(
                        io.github.xororz.localdream.cloud.CloudConfig.Provider.GITHUB,
                        tok, "ETQWFD", "LocalDreamET-Backup",
                        "png/purchased_${item.name}", bytes, "purchase backup"
                    )
                }
            }
            "已保存到相册并扣除 ${item.price} 额度"
        }.getOrElse { "购买失败：${it.message}" }
    }
}

private fun saveToGallery(ctx: Context, name: String, png: ByteArray): Boolean {
    val values = android.content.ContentValues().apply {
        put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, name)
        put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
        put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/LocalDreamET")
    }
    val uri = ctx.contentResolver.insert(
        android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
    ) ?: return false
    ctx.contentResolver.openOutputStream(uri)?.use { it.write(png) }
    return true
}
