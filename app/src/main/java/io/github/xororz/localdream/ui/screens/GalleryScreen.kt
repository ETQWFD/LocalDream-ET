package io.github.xororz.localdream.ui.screens

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.navigation.NavController
import coil.compose.AsyncImage
import io.github.xororz.localdream.cloud.CloudConfig
import io.github.xororz.localdream.cloud.SecureTokenStore
import io.github.xororz.localdream.navigation.popBackStackIfResumed
import io.github.xororz.localdream.service.GalleryClient
import io.github.xororz.localdream.ui.components.FallbackImage
import io.github.xororz.localdream.utils.Storage
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Favorites are scoped per signed-in GitHub user, so two people sharing one
 * phone never see each other's collection. Entries are "name|author|ts".
 */
object FavoriteStore {
    private const val P = "gallery_fav"
    private const val LEGACY = "items"

    fun owner(ctx: Context): String = runCatching {
        io.github.xororz.localdream.cloud.SecureTokenStore(ctx)
            .state(io.github.xororz.localdream.cloud.CloudConfig.Provider.GITHUB)?.login
    }.getOrNull() ?: "guest"

    private fun key(ctx: Context) = "items_" + owner(ctx).lowercase()

    fun all(ctx: Context): List<String> {
        val sp = ctx.getSharedPreferences(P, Context.MODE_PRIVATE)
        val mine = sp.getStringSet(key(ctx), null)
        if (mine != null) return mine.sortedDescending()
        // One-time migration: old global list belongs to the current user only.
        val legacy = sp.getStringSet(LEGACY, emptySet()) ?: emptySet()
        if (legacy.isNotEmpty()) {
            sp.edit().putStringSet(key(ctx), HashSet(legacy)).remove(LEGACY).apply()
            return legacy.sortedDescending()
        }
        return emptyList()
    }

    fun add(ctx: Context, item: String) {
        val s = HashSet(all(ctx)); s.add(item)
        ctx.getSharedPreferences(P, Context.MODE_PRIVATE).edit()
            .putStringSet(key(ctx), s).apply()
    }

    fun remove(ctx: Context, item: String) {
        val s = HashSet(all(ctx)); s.remove(item)
        ctx.getSharedPreferences(P, Context.MODE_PRIVATE).edit()
            .putStringSet(key(ctx), s).apply()
    }

    fun contains(ctx: Context, item: String) = all(ctx).contains(item)

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
    // et.168: 图片社区整页禁止截图/录屏（预览弹窗另有同样保护），
    // 公共聊天不再被全局 FLAG_SECURE 连坐。
    DisposableEffect(Unit) {
        val activity = ctx as? android.app.Activity
        activity?.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { activity?.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE) }
    }
    var showWarn by remember { mutableStateOf(!FavoriteStore.seenWarning(ctx)) }
    var previewItem by remember { mutableStateOf<GalleryClient.GalleryItem?>(null) }

    fun reload(force: Boolean) {
        scope.launch {
            loading = true
            if (force) GalleryClient.invalidate()
            items = GalleryClient.list(force)
            loading = false
        }
    }

    LaunchedEffect(Unit) {
        io.github.xororz.localdream.service.SharedRepos.ensure(ctx)
        reload(false)
        // Credit any new picture sales to this device's quota (85% share).
        val earned = io.github.xororz.localdream.service.GalleryEarnings.sync(ctx)
        if (earned > 0) {
            android.widget.Toast.makeText(
                ctx,
                "图片分成到账 +$earned 额度",
                android.widget.Toast.LENGTH_LONG,
            ).show()
        }
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
        if (loading && items.isEmpty()) {
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
                        Column(Modifier.clickable { previewItem = g }) {
                            // Mirror chain instead of a single URL: a stale
                            // jsDelivr entry no longer shows as a black tile.
                            FallbackImage(
                                urls = g.urls,
                                contentDescription = g.name,
                                modifier = Modifier.aspectRatio(1f).fillMaxWidth(),
                                contentScale = ContentScale.Crop,
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
        // et.168: invalidate the in-memory list so the freshly uploaded picture
        // shows up immediately instead of after an app restart.
        reload(true)
    }
    if (showFav) {
        AlertDialog(
            onDismissRequest = { showFav = false },
            title = { Text("我的收藏 · ${FavoriteStore.owner(ctx)}") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    val favs = FavoriteStore.all(ctx)
                    if (favs.isEmpty()) {
                        Text("还没有收藏", style = MaterialTheme.typography.bodySmall)
                    }
                    favs.forEach { raw ->
                        val name = raw.substringBefore('|')
                        val match = items.firstOrNull { it.name == name }
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .clickable(enabled = match != null) {
                                    if (match != null) {
                                        showFav = false
                                        previewItem = match
                                    }
                                }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (match != null) {
                                FallbackImage(
                                    urls = match.urls,
                                    contentDescription = name,
                                    modifier = Modifier.size(44.dp),
                                    contentScale = ContentScale.Crop,
                                )
                                Spacer(Modifier.width(8.dp))
                            }
                            Column(Modifier.weight(1f)) {
                                Text(
                                    raw.replace('|', ' '),
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                )
                                if (match == null) {
                                    Text(
                                        "原图已下架 / 未加载，可从社区列表打开",
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showFav = false }) { Text("关闭") } },
        )
    }

    previewItem?.let { item -> PreviewDialog(ctx, item) { previewItem = null } }
}

/** A generated picture that can be published without leaving the app. */
private data class LocalArt(val file: File)

/**
 * Upload dialog.
 *
 * et.168 root-cause fix: the old dialog only listed *.png directly under
 * filesDir, but generated images are written to MediaStore
 * (Pictures/LocalDream) / <root>/outputs, so the picker was always empty and
 * "发布失败" was guaranteed. Now the user can (a) pick any picture from the
 * phone album, or (b) choose a recent output scanned from the real output
 * folders.
 */
@Composable
private fun UploadDialog(ctx: Context, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var price by remember { mutableStateOf("100") }
    var name by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }
    var uploading by remember { mutableStateOf(false) }
    var showPicker by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(-1f) }
    var pickedUri by remember { mutableStateOf<Uri?>(null) }
    var pickedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var pickedFile by remember { mutableStateOf<File?>(null) }
    val token = remember { SecureTokenStore(ctx).token(CloudConfig.Provider.GITHUB) }
    val login = remember { SecureTokenStore(ctx).state(CloudConfig.Provider.GITHUB)?.login }

    val albumLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            pickedFile = null
            pickedUri = uri
            pickedBitmap = runCatching {
                ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
            }.getOrNull()
        }
    }

    val localImages = remember(ctx) { scanLocalOutputs(ctx) }

    Dialog(onDismissRequest = { if (!uploading) onDismiss() }) {
        Surface(shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(20.dp)) {
                Text("上传作品", style = MaterialTheme.typography.titleMedium)
                if (login == null) {
                    Text(
                        "未登录 GitHub：将使用 App 内置发布令牌；登录后作品会署名你的账号。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("作品名") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = price, onValueChange = { price = it.filter { c -> c.isDigit() } },
                    label = { Text("售价额度（最高9000）") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { albumLauncher.launch("image/*") }, Modifier.weight(1f)) {
                        Text("从相册选图")
                    }
                    OutlinedButton(onClick = { showPicker = !showPicker }, Modifier.weight(1f)) {
                        Text(if (showPicker) "收起本机作品" else "本机作品")
                    }
                }
                pickedBitmap?.let { b ->
                    Spacer(Modifier.height(8.dp))
                    androidx.compose.foundation.Image(
                        bitmap = b.asImageBitmap(),
                        contentDescription = "预览",
                        modifier = Modifier.fillMaxWidth().height(140.dp),
                        contentScale = ContentScale.Fit,
                    )
                    Text("已选：${b.width}×${b.height}", style = MaterialTheme.typography.labelSmall)
                }
                pickedFile?.let { f ->
                    Spacer(Modifier.height(8.dp))
                    Text("已选：${f.name}", style = MaterialTheme.typography.labelSmall)
                }
                if (showPicker) {
                    if (localImages.isEmpty()) {
                        Text(
                            "没有找到本机生成的作品，请用上面的“从相册选图”。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(4),
                            modifier = Modifier.height(160.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            items(localImages.size) { i ->
                                val f = localImages[i].file
                                val selected = pickedFile?.absolutePath == f.absolutePath
                                Column(Modifier.clickable {
                                    pickedUri = null
                                    pickedBitmap = null
                                    pickedFile = f
                                }) {
                                    AsyncImage(
                                        model = f, contentDescription = f.name,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.size(56.dp),
                                    )
                                    if (selected) {
                                        Text(
                                            "已选",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                if (uploading) {
                    Spacer(Modifier.height(8.dp))
                    if (progress >= 0f) {
                        LinearProgressIndicator(
                            progress = { progress.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            "上传中 ${(progress * 100).toInt()}%",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text("正在压缩图片…", style = MaterialTheme.typography.labelSmall)
                    }
                }
                if (msg.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text(msg, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (uploading) {
                        TextButton(onClick = {
                            GalleryClient.cancelUpload()
                            uploading = false
                            progress = -1f
                            msg = "已取消上传"
                        }) {
                            Icon(Icons.Default.Close, contentDescription = "取消上传", modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("取消上传")
                        }
                    } else {
                        TextButton(onClick = onDismiss) { Text("取消") }
                    }
                    Button(enabled = !uploading, onClick = {
                        val p = price.toIntOrNull() ?: 0
                        when {
                            p > 9000 -> msg = "售价不能超过 9000"
                            pickedUri == null && pickedFile == null -> msg = "请先选择图片"
                            else -> {
                                uploading = true
                                msg = "正在压缩并上传…"
                                progress = -1f
                                scope.launch {
                                    val bytes = withContext(Dispatchers.IO) {
                                        encodeForGallery(ctx, pickedUri, pickedFile)
                                    }
                                    if (bytes == null || bytes.isEmpty()) {
                                        uploading = false
                                        msg = "图片读取失败，请换一张"
                                        return@launch
                                    }
                                    val author = login ?: "ETQWFD"
                                    var lastPct = -1
                                    val res = GalleryClient.upload(
                                        bytes,
                                        author = author,
                                        price = p,
                                        name = name.ifBlank { "作品" },
                                        userToken = token,
                                        onProgress = { v ->
                                            val pct = (v * 100).toInt().coerceIn(0, 100)
                                            if (pct != lastPct) {
                                                lastPct = pct
                                                progress = v
                                            }
                                        },
                                    )
                                    progress = -1f
                                    uploading = false
                                    msg = if (res.ok) {
                                        if (res.reason.isBlank()) "发布成功" else res.reason
                                    } else {
                                        "发布失败：${res.reason}"
                                    }
                                    if (res.ok) onDismiss()
                                }
                            }
                        }
                    }) { Text(if (uploading) "上传中…" else "确认发布") }
                }
            }
        }
    }
}

/** Real output folders the app writes to (see ImageUtils.saveImage). */
private fun scanLocalOutputs(ctx: Context): List<LocalArt> {
    val roots = buildList {
        runCatching { add(File(Storage.root(ctx), Storage.OUTPUTS)) }
        add(ctx.filesDir)
    }
    val files = LinkedHashSet<File>()
    for (root in roots) {
        runCatching {
            root.walkTopDown()
                .maxDepth(3)
                .filter {
                    it.isFile && it.length() in 1..(12L * 1024 * 1024) &&
                        it.extension.lowercase() in setOf("png", "jpg", "jpeg", "webp")
                }
                .take(80)
                .forEach { files.add(it) }
        }
    }
    return files.sortedByDescending { it.lastModified() }.take(60).map { LocalArt(it) }
}

/** Decodes/rescales the picked picture and returns PNG bytes for the repo. */
private fun encodeForGallery(ctx: Context, uri: Uri?, file: File?): ByteArray? {
    return runCatching {
        val bitmap = (if (uri != null) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (bounds.outWidth / sample > 2048 || bounds.outHeight / sample > 2048) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        } else if (file != null) {
            BitmapFactory.decodeFile(file.absolutePath)
        } else {
            null
        }) ?: return null

        val maxEdge = 1280
        val scale = minOf(1f, maxEdge.toFloat() / maxOf(bitmap.width, bitmap.height))
        val target = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).toInt().coerceAtLeast(1),
                (bitmap.height * scale).toInt().coerceAtLeast(1),
                true,
            )
        } else {
            bitmap
        }
        val out = ByteArrayOutputStream()
        target.compress(Bitmap.CompressFormat.PNG, 100, out)
        if (target !== bitmap) target.recycle()
        bitmap.recycle()
        out.toByteArray()
    }.getOrNull()
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
                FallbackImage(
                    urls = item.urls,
                    contentDescription = item.name,
                    modifier = Modifier.fillMaxWidth().height(280.dp),
                    contentScale = ContentScale.Fit,
                )
                Spacer(Modifier.height(8.dp))
                Text("作者：${item.author}", style = MaterialTheme.typography.bodyMedium)
                Text("售价：${item.price} 额度（剩余 $remaining）", style = MaterialTheme.typography.bodySmall)
                Text(
                    "购买后 ${100 - 15}% 额度归作者 @${item.author}，平台抽取 15%",
                    style = MaterialTheme.typography.labelSmall,
                )
                val favKey = "${item.name}|${item.author}|${item.ts}"
                var fav by remember { mutableStateOf(FavoriteStore.contains(ctx, favKey)) }
                TextButton(onClick = {
                    if (fav) FavoriteStore.remove(ctx, favKey) else FavoriteStore.add(ctx, favKey)
                    fav = !fav
                }) {
                    Icon(
                        if (fav) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = null,
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(if (fav) "已收藏" else "收藏")
                }
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
            // Try every mirror before giving up: jsDelivr can be stale right
            // after an upload, and raw is blocked for some mainland users.
            var bytes: ByteArray? = null
            var lastReason = "图片加载失败"
            for (u in item.urls) {
                try {
                    val conn = (java.net.URL(u).openConnection() as java.net.HttpURLConnection)
                    conn.connectTimeout = 12000; conn.readTimeout = 30000
                    val code = conn.responseCode
                    if (code !in 200..299) { lastReason = "HTTP $code"; continue }
                    bytes = conn.inputStream.use { it.readBytes() }
                    break
                } catch (e: Exception) {
                    lastReason = e.message ?: "网络错误"
                }
            }
            val data = bytes ?: return@withContext lastReason
            val bmp = android.graphics.BitmapFactory.decodeByteArray(data, 0, data.size)
                ?: return@withContext "图片解码失败"
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
            val out = ByteArrayOutputStream()
            wm.compress(Bitmap.CompressFormat.PNG, 95, out)
            saveToGallery(ctx, "LocalDreamET_${item.name}", out.toByteArray())
            qm.consume(ctx, item.price.toLong())
            // 85% to the author, 15% to the platform; failures never block the
            // buyer from getting the picture they paid for.
            runCatching {
                io.github.xororz.localdream.service.GalleryEarnings
                    .record(ctx, item.author, item.price)
            }
            // 已登录用户：备份到自己的 Backup 仓，防丢
            val store = SecureTokenStore(ctx)
            val tok = store.token(CloudConfig.Provider.GITHUB)
            if (!tok.isNullOrBlank()) {
                runCatching {
                    io.github.xororz.localdream.cloud.CloudClient.uploadContent(
                        CloudConfig.Provider.GITHUB,
                        tok, "ETQWFD", "LocalDreamET-Backup",
                        "png/purchased_${item.name}", data, "purchase backup"
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
