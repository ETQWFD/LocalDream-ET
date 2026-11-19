package etc.github.drive

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.foundation.border
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Image
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.util.Base64 as JBase64

/**
 * Nightmare 云盘：用出图 App 注册的邮箱账号登录，查看自己上传到私密仓库
 * `ETQWFD/NightmareRoad-ET-Private` 里 `users/<邮箱>/images/` 的图片，
 * 可原图预览（缩放）与下载到相册。按需求不提供删除。
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            // et.252：主题色可在网盘里自己换（5 套），深色跟随系统。
            var accent by remember { mutableStateOf(DrivePrefs.accent(this)) }
            val dark = androidx.compose.foundation.isSystemInDarkTheme()
            MaterialTheme(colorScheme = driveScheme(dark, accent)) {
                DriveRoot(
                    accent = accent,
                    onAccent = { i -> DrivePrefs.setAccent(this, i); accent = i },
                )
            }
        }
    }
}

private val client = OkHttpClient.Builder().connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
    .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS).build()

/** 走 API 取文件（download_url 有时效，统一用 Contents API 的 base64 更稳）。 */
private fun fetchBytes(path: String): ByteArray? {
    val r = DriveAccount.call(path)
    if (r.code !in 200..299) return null
    return runCatching {
        val o = JSONObject(r.body)
        val b64 = o.optString("content").replace("\n", "")
        if (b64.isNotBlank()) return@runCatching JBase64.getDecoder().decode(b64)
        // 超过 1 MB 的文件 Contents API 不返回正文，用 download_url 直接下。
        val url = o.optString("download_url")
        if (url.isBlank()) return@runCatching null
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 120_000
        c.setRequestProperty("User-Agent", "NightmareDrive")
        c.inputStream.use { it.readBytes() }
    }.getOrNull()
}

private fun decodeBitmap(bytes: ByteArray, maxSide: Int): Bitmap? = runCatching {
    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    var sample = 1
    val longest = maxOf(opts.outWidth, opts.outHeight)
    while (longest / sample > maxSide) sample *= 2
    BitmapFactory.decodeByteArray(
        bytes, 0, bytes.size,
        BitmapFactory.Options().apply { inSampleSize = sample },
    )
}.getOrNull()

private fun saveToGallery(ctx: android.content.Context, bytes: ByteArray, name: String): Boolean =
    runCatching {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, if (name.endsWith(".png", true)) "image/png" else "image/jpeg")
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Nightmare云盘")
            }
        }
        val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
        ctx.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: return false
        true
    }.getOrDefault(false)

@Composable
private fun DriveRoot(accent: Int, onAccent: (Int) -> Unit) {
    val ctx = LocalContext.current
    var email by remember { mutableStateOf(DriveAccount.email(ctx)) }
    var showAccent by remember { mutableStateOf(false) }
    // 登录页与云盘之间做淡入淡出，不再"啪"一下换屏。
    androidx.compose.animation.Crossfade(
        targetState = email.isNotBlank(),
        animationSpec = androidx.compose.animation.core.tween(320),
        label = "driveRoot",
    ) { signedIn ->
        if (!signedIn) {
            Box(Modifier.fillMaxSize()) {
                LoginScreen(
                    onLogin = { e -> DriveAccount.remember(ctx, e); email = e },
                )
                androidx.compose.material3.TextButton(
                    onClick = { showAccent = true },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
                ) { Text("主题色") }
            }
        } else {
            DriveScreen(
                email = email,
                accent = accent,
                onAccent = onAccent,
                onLogout = { DriveAccount.signOut(ctx); email = "" },
            )
        }
    }
    if (showAccent) {
        AccentDialog(
            current = accent,
            onPick = { i -> onAccent(i); showAccent = false },
            onDismiss = { showAccent = false },
        )
    }
}

@Composable
private fun LoginScreen(onLogin: (String) -> Unit) {
    val ctx = LocalContext.current
    var mail by remember { mutableStateOf("") }
    var pwd by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    // et.250：网盘之前只有登录、没有注册入口，也没有用户协议；
    // 邮箱没注册过就只能回主 App 注册，体验上等于"云盘用不了"。
    var regMode by remember { mutableStateOf(false) }
    var pwd2 by remember { mutableStateOf("") }
    var agreed by remember { mutableStateOf(false) }
    var showTerms by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(28.dp))
                .background(MaterialTheme.colorScheme.surface)
                .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f), RoundedCornerShape(28.dp))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Nightmare 云盘", style = MaterialTheme.typography.headlineSmall)
            Text(
                "使用 Nightmare Road-ET 里注册的邮箱账号登录。图片存放在作者的私密仓库，只有你能看到自己的目录。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
            OutlinedTextField(
                value = mail, onValueChange = { mail = it }, label = { Text("邮箱") },
                singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = pwd, onValueChange = { pwd = it }, label = { Text("密码") },
                singleLine = true, visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            if (regMode) {
                OutlinedTextField(
                    value = pwd2, onValueChange = { pwd2 = it }, label = { Text("再次输入密码确认") },
                    singleLine = true, visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                androidx.compose.material3.Checkbox(checked = agreed, onCheckedChange = { agreed = it })
                Text(
                    "我已阅读并同意《用户协议与隐私政策》",
                    style = MaterialTheme.typography.bodySmall,
                )
                androidx.compose.material3.TextButton(onClick = { showTerms = true }) {
                    Text("查看协议")
                }
            }
            err?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Button(
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    busy = true; err = null
                    scope.launch {
                        val e = withContext(Dispatchers.IO) {
                            if (regMode) DriveAccount.register(ctx, mail, pwd, pwd2, agreed)
                            else DriveAccount.login(mail, pwd)
                        }
                        busy = false
                        if (e == null) onLogin(mail.trim().lowercase()) else err = e
                    }
                },
            ) {
                Text(
                    when {
                        busy && regMode -> "注册中…"
                        busy -> "登录中…"
                        regMode -> "注册并登录"
                        else -> "登录"
                    },
                )
            }
            androidx.compose.material3.TextButton(
                onClick = { regMode = !regMode; err = null },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (regMode) "已有账号？返回登录" else "还没有账号？用邮箱注册")
            }
            if (busy) CircularProgressIndicator(modifier = Modifier.padding(top = 4.dp))
            if (showTerms) {
                AlertDialog(
                    onDismissRequest = { showTerms = false },
                    title = { Text("用户协议与隐私政策") },
                    text = {
                        Text(
                        """Nightmare 云盘 · 用户协议与隐私政策

1. 账号与数据：邮箱账号与图片保存在作者的私密仓库中，目录以你的邮箱命名，只有该目录的持有者可见；仓库本身不公开。
2. 云盘为只读：本应用提供预览与下载，不提供删除，如需清理请联系作者，避免误删已发布作品的原始文件。
3. 图片来源：云盘里的图片来自你在 Nightmare Road-ET 中主动发布或保存的作品。应用不会自动扫描或上传相册，任何上传都需要你在发布框里手动确认。
4. 密码：本地只保存邮箱，不保存明文密码；服务端仅保存 PBKDF2-HMAC-SHA256（120000 轮）派生的校验值与随机盐。
5. 责任：请勿上传违法、侵权或他人隐私内容，由此造成的后果由发布者承担，作者有权下架违规内容。
6. 协议更新：继续使用该应用即视为接受最新版本的协议。""".trimIndent()
                        )
                    },
                    confirmButton = {
                        Button(onClick = { showTerms = false; agreed = true }) { Text("同意并勾选") }
                    },
                    dismissButton = {
                        androidx.compose.material3.TextButton(onClick = { showTerms = false }) { Text("关闭") }
                    },
                )
            }
            }
        }
    }
}

/** 主题色选择：5 套色板，点一下立刻生效并记住。 */
@Composable
private fun AccentDialog(current: Int, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("主题色") },
        text = {
            Column {
                DRIVE_ACCENTS.forEachIndexed { i, a ->
                    androidx.compose.material3.TextButton(
                        onClick = { onPick(i) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Box(
                            Modifier
                                .width(18.dp)
                                .height(18.dp)
                                .clip(RoundedCornerShape(9.dp))
                                .background(a.light),
                        )
                        Text(
                            "  ${a.label}${if (i == current) "（当前）" else ""}",
                            color = if (i == current) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DriveScreen(
    email: String,
    accent: Int,
    onAccent: (Int) -> Unit,
    onLogout: () -> Unit,
) {
    val ctx = LocalContext.current
    var items by remember { mutableStateOf<List<DriveAccount.RemoteImage>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var refreshToken by remember { mutableStateOf(0) }
    var viewer by remember { mutableStateOf<DriveAccount.RemoteImage?>(null) }
    var confirmLogout by remember { mutableStateOf(false) }
    var uploading by remember { mutableStateOf(false) }
    var showHelp by remember { mutableStateOf(false) }
    var showAccent2 by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    // et.252：网盘内自助上传——相册选一张，直接进 users/<邮箱>/images/
    val picker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        uploading = true
        scope.launch {
            val raw = withContext(Dispatchers.IO) {
                runCatching { ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
            }
            if (raw == null) {
                uploading = false
                Toast.makeText(ctx, "读取图片失败", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val payload: ByteArray = if (raw.size > 6 * 1024 * 1024) {
                withContext(Dispatchers.IO) {
                    runCatching {
                        val bm = BitmapFactory.decodeByteArray(raw, 0, raw.size)
                        val out = java.io.ByteArrayOutputStream()
                        bm.compress(Bitmap.CompressFormat.JPEG, 92, out)
                        out.toByteArray()
                    }.getOrDefault(raw)
                }
            } else raw
            val name = "pic_${System.currentTimeMillis()}_1.png"
            val up = withContext(Dispatchers.IO) { DriveAccount.uploadImage(email, name, payload) }
            uploading = false
            if (up.code in 200..299) {
                Toast.makeText(ctx, "已上传到你的云盘", Toast.LENGTH_SHORT).show()
                refreshToken++
            } else {
                Toast.makeText(ctx, "上传失败 ${up.code}: ${up.body.take(80)}", Toast.LENGTH_LONG).show()
            }
        }
    }
    LaunchedEffect(refreshToken, email) {
        loading = true
        items = withContext(Dispatchers.IO) { DriveAccount.listImages(email) }
        loading = false
    }
    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "${email} 的云盘（${items.size}）",
                        style = MaterialTheme.typography.titleMedium,
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                actions = {
                    androidx.compose.material3.TextButton(onClick = { showAccent2 = true }) { Text("主题") }
                    androidx.compose.material3.TextButton(onClick = { showHelp = true }) { Text("帮助") }
                    IconButton(onClick = { refreshToken++ }) { Icon(Icons.Default.Refresh, "刷新") }
                    OutlinedButton(onClick = { confirmLogout = true }, modifier = Modifier.padding(end = 8.dp)) { Text("退出") }
                },
            )
        },
    ) { pad ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(pad)
                // 琉璃底：三层深紫—墨蓝渐变，卡片半透明压在它上面
                .background(
                    androidx.compose.ui.graphics.Brush.verticalGradient(
                        listOf(Color(0xFF171018), Color(0xFF241726), Color(0xFF12151E)),
                    ),
                ),
        ) {
            if (loading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else if (items.isEmpty()) {
                Text(
                    "这个文件夹还是空的。\n\n· 点右下角「上传图片」可以直接把相册里的图存到自己云盘；\n" +
                        "· 在 Nightmare Road-ET 里发布作品（登录同一邮箱）也会自动存一份到这里。",
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.85f),
                )
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(110.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(items, key = { it.name }) { img ->
                        Thumb(email = email, img = img, onClick = { viewer = img })
                    }
                }
            }
            androidx.compose.material3.FloatingActionButton(
                onClick = { if (!uploading) picker.launch("image/*") },
                modifier = Modifier.align(Alignment.BottomEnd).padding(18.dp),
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) { Text(if (uploading) "上传中…" else "上传图片") }
            if (uploading) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = Color.White)
                        Text("正在上传…", color = Color.White, modifier = Modifier.padding(top = 10.dp))
                    }
                }
            }
        }
    }

    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text("退出登录") },
            text = { Text("退出后需要重新用邮箱账号登录才能查看云盘图片。相册里已下载的图片不受影响。") },
            confirmButton = {
                Button(onClick = { confirmLogout = false; onLogout() }) { Text("退出") }
            },
            dismissButton = { OutlinedButton(onClick = { confirmLogout = false }) { Text("取消") } },
        )
    }

    if (showHelp) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showHelp = false },
            title = { Text("帮助与反馈") },
            text = {
                Text(
                    "· 云盘只显示你自己账号文件夹里的图片，别人看不到；\n" +
                        "· 支持预览（双指放大，最高原图）与下载到相册 Pictures/Nightmare云盘；\n" +
                        "· 按约定云盘不提供删除；如需清理请联系作者。\n\n" +
                        "反馈：GitHub 仓库 ETQWFD/NightmareRoad-ET 提 Issue，或在群里 @管理员。",
                    style = MaterialTheme.typography.bodySmall,
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { showHelp = false }) { Text("知道了") }
            },
        )
    }
    if (showAccent2) {
        AccentDialog(
            current = accent,
            onPick = { i -> onAccent(i); showAccent2 = false },
            onDismiss = { showAccent2 = false },
        )
    }

    viewer?.let { img ->
        ViewerDialog(
            email = email,
            img = img,
            onDismiss = { viewer = null },
            onDownload = { bytes ->
                val ok = saveToGallery(ctx, bytes, img.name)
                Toast.makeText(ctx, if (ok) "已存到相册 Pictures/Nightmare云盘" else "保存失败", Toast.LENGTH_SHORT).show()
            },
        )
    }
}

@Composable
private fun Thumb(email: String, img: DriveAccount.RemoteImage, onClick: () -> Unit) {
    var bmp by remember(img.name) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(img.name) {
        bmp = withContext(Dispatchers.IO) {
            fetchBytes("${DriveAccount.imagesDir(email)}/${img.name}")?.let { decodeBitmap(it, 400) }
        }
    }
    // 琉璃卡片：半透明填充 + 细白描边 + 载入时淡入放大
    var appeared by remember(img.name) { mutableStateOf(false) }
    LaunchedEffect(img.name) { appeared = true }
    val alpha by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (appeared) 1f else 0f,
        animationSpec = androidx.compose.animation.core.tween(280),
        label = "thumbFade",
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .graphicsLayer { this.alpha = alpha; scaleX = 0.94f + 0.06f * alpha; scaleY = 0.94f + 0.06f * alpha }
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = 0.07f))
            .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(14.dp))
            .pointerInput(img.name) { detectTapGestures(onTap = { onClick() }) },
        contentAlignment = Alignment.Center,
    ) {
        val b = bmp
        if (b == null) CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp)
        else Image(
            bitmap = b.asImageBitmap(),
            contentDescription = img.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun ViewerDialog(
    email: String,
    img: DriveAccount.RemoteImage,
    onDismiss: () -> Unit,
    onDownload: (ByteArray) -> Unit,
) {
    val ctx = LocalContext.current
    var bytes by remember(img.name) { mutableStateOf<ByteArray?>(null) }
    var bmp by remember(img.name) { mutableStateOf<Bitmap?>(null) }
    var scale by remember { mutableFloatStateOf(1f) }
    var tx by remember { mutableFloatStateOf(0f) }
    var ty by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(img.name) {
        bytes = withContext(Dispatchers.IO) {
            fetchBytes("${DriveAccount.imagesDir(email)}/${img.name}")
        }
        // 预览按原图解码（不降采样），配合缩放可以看到完整细节。
        bmp = bytes?.let {
            decodeBitmap(it, 8192) ?: BitmapFactory.decodeByteArray(it, 0, it.size)
        }
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            val b = bmp
            if (b == null) {
                CircularProgressIndicator(color = Color.White)
            } else {
                Image(
                    bitmap = b.asImageBitmap(),
                    contentDescription = img.name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = scale; scaleY = scale
                            translationX = tx; translationY = ty
                        }
                        .pointerInput(img.name) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                scale = (scale * zoom).coerceIn(1f, 8f)
                                tx += pan.x
                                ty += pan.y
                                if (scale <= 1.01f) { tx = 0f; ty = 0f }
                            }
                        }
                        .pointerInput(img.name) {
                            detectTapGestures(onDoubleTap = {
                                if (scale > 1.1f) { scale = 1f; tx = 0f; ty = 0f } else scale = 2.5f
                            }, onTap = { onDismiss() })
                        },
                )
            }
            Row(
                modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${img.name}  ${"%,d".format((img.size / 1024).coerceAtLeast(1))} KB",
                    color = Color.White,
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(
                    enabled = bytes != null,
                    onClick = { bytes?.let { onDownload(it) } },
                ) {
                    Icon(Icons.Default.Download, null)
                    Text("下载")
                }
            }
        }
    }
}
