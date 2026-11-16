package io.github.xororz.localdream.ui.screens

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Star
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
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.graphics.graphicsLayer
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
    var saleNotice by remember { mutableStateOf<io.github.xororz.localdream.service.GalleryEarnings.EarningInfo?>(null) }

    fun reload(force: Boolean) {
        scope.launch {
            loading = true
            if (force) GalleryClient.invalidate()
            items = GalleryClient.list(force)
            loading = false
        }
    }

    // Other people's uploads should appear without a manual refresh.
    LaunchedEffect(Unit) {
        while (true) {
            // 2026-11：原来 15 秒强制 list(true)，每次都换一批 GalleryItem，
            // 网格跟着重组、图片重新请求，滑动时观感就是“一直很慢、上下翻又重载”。
            // 改成 60 秒且只在内容真的变了才替换，保持列表对象稳定，靠 key 复用。
            kotlinx.coroutines.delay(60_000)
            if (!loading && !showUpload) {
                val fresh = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    GalleryClient.list(true)
                }
                if (fresh.size != items.size || fresh.map { it.name } != items.map { it.name }) {
                    items = fresh
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        io.github.xororz.localdream.service.SharedRepos.ensure(ctx)
        reload(false)
        // Credit any new picture sales to this device's quota (85% share).
        val earned = io.github.xororz.localdream.service.GalleryEarnings.sync(ctx)
        if (earned != null && earned.total > 0) saleNotice = earned
    }

    saleNotice?.let { info ->
        AlertDialog(
            onDismissRequest = { saleNotice = null },
            title = { Text("作品售出通知") },
            text = {
                Column {
                    Text("您发布的《${info.item.ifBlank { "作品" }}》被购买了。")
                    Spacer(Modifier.height(8.dp))
                    Text("售价：${info.price} 额度")
                    Text("官方抽取 15%：${info.price - info.share} 额度")
                    Text("您获得 85%：${info.share} 额度")
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "已到账 +${info.total} 额度，永久计入你的账号，可在生成时使用。",
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            },
            confirmButton = { TextButton(onClick = { saleNotice = null }) { Text("知道了") } },
        )
    }
    // et.213: search by author / image name.
    var profileAuthor by remember { mutableStateOf<String?>(null) }
    var showRank by remember { mutableStateOf(false) }
    var showSend by remember { mutableStateOf(false) }
    var showBounty by remember { mutableStateOf(false) }
    var bountyTitle by remember { mutableStateOf("") }
    var bountyAmount by remember { mutableStateOf("") }
    var bountyItems by remember { mutableStateOf<List<org.json.JSONObject>>(emptyList()) }
    var bountyBusy by remember { mutableStateOf(false) }
    var pendingBounty by remember { mutableStateOf<org.json.JSONObject?>(null) }
    var bountySubs by remember { mutableStateOf<List<org.json.JSONObject>>(emptyList()) }
    var subsFor by remember { mutableStateOf<org.json.JSONObject?>(null) }
    val bountyFilePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        val b = pendingBounty
        if (uri != null && b != null) {
            val login = io.github.xororz.localdream.cloud.SecureTokenStore(ctx)
                .state(io.github.xororz.localdream.cloud.CloudConfig.Provider.GITHUB)?.login
            if (login.isNullOrBlank()) {
                android.widget.Toast.makeText(ctx, "请先登录 GitHub", android.widget.Toast.LENGTH_LONG).show()
            } else {
                scope.launch {
                    bountyBusy = true
                    val bytes = withContext(Dispatchers.IO) {
                        runCatching { ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
                    }
                    val name = withContext(Dispatchers.IO) {
                        runCatching {
                            ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                                if (c.moveToFirst()) c.getString(c.getColumnIndexOrThrow(android.provider.OpenableColumns.DISPLAY_NAME)) else null
                            }
                        }.getOrNull() ?: "submission.bin"
                    }
                    if (bytes == null || bytes.size > 20 * 1024 * 1024) {
                        bountyBusy = false
                        android.widget.Toast.makeText(ctx, "文件读取失败或超过 20MB", android.widget.Toast.LENGTH_LONG).show()
                    } else {
                        val (ok, err) = withContext(Dispatchers.IO) {
                            io.github.xororz.localdream.service.BountyClient.submit(b, login, name, bytes)
                        }
                        bountyBusy = false
                        android.widget.Toast.makeText(
                            ctx, if (ok) "已加密提交，只有发布者能打开" else "提交失败：$err",
                            android.widget.Toast.LENGTH_LONG,
                        ).show()
                    }
                    pendingBounty = null
                }
            }
        } else pendingBounty = null
    }
    fun refreshBounty() {
        scope.launch {
            bountyBusy = true
            val list = withContext(Dispatchers.IO) {
                runCatching { io.github.xororz.localdream.service.BountyClient.list() }.getOrDefault(emptyList())
            }
            val login = io.github.xororz.localdream.cloud.SecureTokenStore(ctx)
                .state(io.github.xororz.localdream.cloud.CloudConfig.Provider.GITHUB)?.login.orEmpty()
            if (login.isNotBlank()) {
                withContext(Dispatchers.IO) {
                    runCatching { io.github.xororz.localdream.service.BountyClient.autoSettle(ctx, login, list) }
                }
            }
            bountyItems = withContext(Dispatchers.IO) {
                runCatching { io.github.xororz.localdream.service.BountyClient.list() }.getOrDefault(emptyList())
            }
            bountyBusy = false
        }
    }
    var sendTo by remember { mutableStateOf("") }
    var sendAmount by remember { mutableStateOf("") }
    var pendingSend by remember { mutableStateOf<Pair<String, Long>?>(null) }
    val keyguardLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { res ->
        val p = pendingSend
        if (res.resultCode == android.app.Activity.RESULT_OK && p != null) {
            val (to, amount) = p
            val login = io.github.xororz.localdream.cloud.SecureTokenStore(ctx)
                .state(io.github.xororz.localdream.cloud.CloudConfig.Provider.GITHUB)?.login
            if (login.isNullOrBlank()) {
                android.widget.Toast.makeText(ctx, "请先登录 GitHub", android.widget.Toast.LENGTH_LONG).show()
            } else if (io.github.xororz.localdream.service.QuotaManager.remaining(ctx) < amount) {
                android.widget.Toast.makeText(ctx, "额度不足", android.widget.Toast.LENGTH_LONG).show()
            } else {
                scope.launch {
                    io.github.xororz.localdream.service.QuotaManager.consume(ctx, amount)
                    val (ok, err) = withContext(Dispatchers.IO) {
                        GalleryClient.sendQuota(login, to, amount)
                    }
                    if (!ok) {
                        io.github.xororz.localdream.service.QuotaManager.refund(ctx, amount)
                    } else {
                        withContext(Dispatchers.IO) {
                            runCatching {
                                io.github.xororz.localdream.service.NoticesClient.post(
                                    to, "收到转账额度", "来自 @$login 的 $amount 额度已发送，进社区领取后到账",
                                )
                            }
                        }
                    }
                    android.widget.Toast.makeText(
                        ctx,
                        if (ok) "已向 @$to 发送 $amount 额度" else "发送失败：$err",
                        android.widget.Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
        pendingSend = null
    }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        val login = io.github.xororz.localdream.cloud.SecureTokenStore(ctx)
            .state(io.github.xororz.localdream.cloud.CloudConfig.Provider.GITHUB)?.login
        if (!login.isNullOrBlank()) {
            val got = withContext(Dispatchers.IO) {
                runCatching { GalleryClient.claimTransfers(ctx, login) }.getOrDefault(0L)
            }
            if (got > 0) android.widget.Toast.makeText(ctx, "收到转账额度 +$got", android.widget.Toast.LENGTH_LONG).show()
        }
    }
    var authorFilter by remember { mutableStateOf<String?>(null) }
    val filtered = remember(items, q) {
        if (q.isBlank()) items else items.filter {
            it.author.contains(q, true) || it.name.contains(q, true) || it.title.contains(q, true)
        }
    }
    // 2026-11：按作者分组展示，同一作者的图排在一起，作者名作为分组标识。
    val groupedFiltered = remember(filtered, authorFilter) {
        val base = authorFilter?.let { a -> filtered.filter { it.author == a } } ?: filtered
        base.sortedWith(compareBy({ it.author.lowercase() }, { -it.ts }))
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
                    IconButton(onClick = { showRank = true }) {
                        Icon(Icons.Default.Star, contentDescription = "本月下载榜")
                    }
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
                // 2026-11：草图里的“作者分组”条：一行作者头像 + 关注开关。
                val authors = groupedFiltered.map { it.author }.distinct()
                if (authors.isNotEmpty()) {
                    androidx.compose.foundation.layout.Row(
                        modifier = Modifier.fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        androidx.compose.material3.AssistChip(
                            onClick = { authorFilter = null },
                            label = { Text(if (authorFilter == null) "全部 ✓" else "全部") },
                        )
                        authors.forEach { a ->
                            val following = io.github.xororz.localdream.service.FollowStore.isFollowing(ctx, a)
                            androidx.compose.material3.AssistChip(
                                onClick = { authorFilter = if (authorFilter == a) null else a },
                                leadingIcon = {
                                    Box(
                                        Modifier.size(22.dp)
                                            .clip(androidx.compose.foundation.shape.CircleShape)
                                            .background(MaterialTheme.colorScheme.primaryContainer),
                                        contentAlignment = Alignment.Center,
                                    ) { Text(a.take(1).uppercase(), style = MaterialTheme.typography.labelSmall) }
                                },
                                label = {
                                    Text(
                                        "@$a" + (if (authorFilter == a) " ✓" else "") + (if (following) " ★" else ""),
                                        maxLines = 1,
                                    )
                                },
                            )
                        }
                    }
                }
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    contentPadding = PaddingValues(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    itemsIndexed(groupedFiltered, key = { _, g -> g.name }) { idx, g ->
                        // 2026-11：卡片按下时轻微缩到 0.95 再弹回，配合图片渐显，
                        // 让社区网格点起来有“灵动”反馈；不改变点击区域与命中逻辑。
                        val pressSrc = remember { MutableInteractionSource() }
                        val pressed by pressSrc.collectIsPressedAsState()
                        // 2026-11：首次出现时按索引错峰淡入 + 轻微上移，形成“从上往下铺开”的入场；
                        // 延迟封顶 300ms，长列表不会越往下越慢。
                        val enter = remember(g.name) { androidx.compose.animation.core.Animatable(0f) }
                        androidx.compose.runtime.LaunchedEffect(g.name) {
                            kotlinx.coroutines.delay((idx.coerceAtMost(10) * 30).toLong())
                            enter.animateTo(1f, androidx.compose.animation.core.tween(260))
                        }
                        val pressScale by animateFloatAsState(
                            targetValue = if (pressed) 0.95f else 1f,
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioMediumBouncy,
                                stiffness = Spring.StiffnessMediumLow,
                            ),
                            label = "galleryPressScale",
                        )
                        Column(
                            Modifier
                                // 玻璃卡片：圆角 + 半透明白底 + 1dp 描边(见下方 background/border)
                                .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                                .background(
                                    if (androidx.compose.foundation.isSystemInDarkTheme())
                                        androidx.compose.ui.graphics.Color.White.copy(alpha = 0.05f)
                                    else
                                        androidx.compose.material3.MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                                )
                                .border(
                                    width = 1.dp,
                                    color = if (androidx.compose.foundation.isSystemInDarkTheme())
                                        androidx.compose.ui.graphics.Color.White.copy(alpha = 0.10f)
                                    else
                                        androidx.compose.material3.MaterialTheme.colorScheme.outline.copy(alpha = 0.25f),
                                    shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                                )
                                // 列表项出现/移动时淡入淡出并弹性归位，滑动找图不再“啪”地跳变
                                .animateItem(
                                    fadeInSpec = tween(300),
                                    fadeOutSpec = tween(200),
                                    placementSpec = spring(
                                        dampingRatio = Spring.DampingRatioMediumBouncy,
                                        stiffness = Spring.StiffnessMediumLow,
                                    ),
                                )
                                .graphicsLayer {
                                    scaleX = pressScale; scaleY = pressScale
                                    alpha = enter.value
                                    translationY = (1f - enter.value) * 28f
                                }
                                .clickable(
                                    interactionSource = pressSrc,
                                    indication = null,
                                ) { previewItem = g },
                        ) {
                            // Mirror chain instead of a single URL: a stale
                            // jsDelivr entry no longer shows as a black tile.
                            FallbackImage(
                                // 预览只给原图，缩略图只用于网格；否则点开大图还是 200px 糊图。
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

    // 2026-11：作者主页（与共享聊天社区完全同步：同一份 profiles/<login>.json）。
    profileAuthor?.let { author ->
        val profile = remember(author) { mutableStateOf<io.github.xororz.localdream.chat.ChatProfile?>(null) }
        var nick by remember(author) { mutableStateOf("") }
        var bio by remember(author) { mutableStateOf("") }
        var avatar by remember(author) { mutableStateOf("") }
        var saving by remember { mutableStateOf(false) }
        var pmsg by remember { mutableStateOf("") }
        androidx.compose.runtime.LaunchedEffect(author) {
            val p = withContext(Dispatchers.IO) {
                runCatching { io.github.xororz.localdream.chat.ChatProfileRepo.load(author) }.getOrNull()
            }
            p?.let {
                profile.value = it
                nick = it.nickname
                bio = it.bio
                avatar = it.avatar
            }
        }
        val myLogin = io.github.xororz.localdream.cloud.SecureTokenStore(ctx)
            .state(io.github.xororz.localdream.cloud.CloudConfig.Provider.GITHUB)?.login
        val following = io.github.xororz.localdream.service.FollowStore.isFollowing(ctx, author)
        AlertDialog(
            onDismissRequest = { profileAuthor = null },
            title = { Text("作者主页") },
            text = {
                Column(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val av = avatar.ifBlank { profile.value?.avatar ?: "" }
                        if (av.isNotBlank()) {
                            AsyncImage(
                                model = av, contentDescription = null,
                                modifier = Modifier.size(52.dp).clip(androidx.compose.foundation.shape.CircleShape),
                                contentScale = ContentScale.Crop,
                            )
                        } else {
                            Box(
                                Modifier.size(52.dp).clip(androidx.compose.foundation.shape.CircleShape)
                                    .background(MaterialTheme.colorScheme.primaryContainer),
                                contentAlignment = Alignment.Center,
                            ) { Text(author.take(1).uppercase()) }
                        }
                        Column(Modifier.padding(start = 10.dp)) {
                            Text(profile.value?.displayName ?: author, style = MaterialTheme.typography.titleSmall)
                            Text("@$author", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    if (myLogin == author) {
                        OutlinedTextField(value = nick, onValueChange = { nick = it }, label = { Text("昵称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = bio, onValueChange = { bio = it }, label = { Text("简介") }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = avatar, onValueChange = { avatar = it }, label = { Text("头像图片 URL") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        Text("此主页同时用于共享聊天社区，保存后两边同步。", style = MaterialTheme.typography.labelSmall)
                        if (pmsg.isNotBlank()) Text(pmsg, color = MaterialTheme.colorScheme.error)
                    } else {
                        Text(profile.value?.bio?.ifBlank { "这个人很懒，还没有写简介" } ?: "加载中…", style = MaterialTheme.typography.bodySmall)
                        Text("本机已关注" .takeIf { following } ?: "", style = MaterialTheme.typography.labelSmall)
                    }
                }
            },
            confirmButton = {
                Row {
                    TextButton(onClick = {
                        io.github.xororz.localdream.service.FollowStore.toggle(ctx, author); profileAuthor = null
                    }) { Text(if (following) "取消关注" else "关注") }
                    if (myLogin == author) {
                        TextButton(enabled = !saving, onClick = {
                            saving = true; pmsg = ""
                            scope.launch {
                                val res = withContext(Dispatchers.IO) {
                                    io.github.xororz.localdream.chat.ChatProfileRepo.save(
                                        ctx,
                                        io.github.xororz.localdream.chat.ChatProfile(
                                            login = author, nickname = nick, bio = bio, avatar = avatar,
                                        ),
                                    )
                                }
                                saving = false
                                res.onSuccess { pmsg = "已保存，聊天社区同步生效" }.onFailure { pmsg = "保存失败：${it.message}" }
                            }
                        }) { Text(if (saving) "保存中…" else "保存") }
                    }
                }
            },
            dismissButton = { TextButton(onClick = { profileAuthor = null }) { Text("关闭") } },
        )
    }
    if (showBounty) {
        val login = io.github.xororz.localdream.cloud.SecureTokenStore(ctx)
            .state(io.github.xororz.localdream.cloud.CloudConfig.Provider.GITHUB)?.login.orEmpty()
        AlertDialog(
            onDismissRequest = { showBounty = false },
            title = { Text("悬赏") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    OutlinedTextField(
                        value = bountyTitle, onValueChange = { bountyTitle = it },
                        label = { Text("悬赏目的") }, modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = bountyAmount, onValueChange = { bountyAmount = it.filter { c -> c.isDigit() } },
                        label = { Text("悬赏额度（发布时先托管扣除）") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = {
                            if (login.isBlank()) {
                                android.widget.Toast.makeText(ctx, "请先登录 GitHub", android.widget.Toast.LENGTH_SHORT).show()
                            } else {
                                scope.launch {
                                    bountyBusy = true
                                    val (ok, err) = withContext(Dispatchers.IO) {
                                        io.github.xororz.localdream.service.BountyClient.create(
                                            ctx, login, bountyTitle.trim(), bountyAmount.toLongOrNull() ?: 0L,
                                        )
                                    }
                                    bountyBusy = false
                                    android.widget.Toast.makeText(
                                        ctx, if (ok) "悬赏已发布，额度已托管" else "发布失败：$err", android.widget.Toast.LENGTH_LONG,
                                    ).show()
                                    if (ok) { bountyTitle = ""; bountyAmount = ""; refreshBounty() }
                                }
                            }
                        },
                        enabled = !bountyBusy,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("发布悬赏") }
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    Text("进行中的悬赏", style = MaterialTheme.typography.titleSmall)
                    bountyItems.forEach { b ->
                        val creator = b.optString("creator")
                        Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                            Text("@$creator · ${b.optString("amount")} 额度", style = MaterialTheme.typography.labelSmall)
                            Text(b.optString("title"), style = MaterialTheme.typography.bodyMedium)
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                TextButton(onClick = {
                                    if (login.isBlank()) {
                                        android.widget.Toast.makeText(ctx, "请先登录 GitHub", android.widget.Toast.LENGTH_SHORT).show()
                                    } else { pendingBounty = b; bountyFilePicker.launch("*/*") }
                                }) { Text("提交成果") }
                                if (creator == login) {
                                    TextButton(onClick = {
                                        scope.launch {
                                            bountyBusy = true
                                            bountySubs = withContext(Dispatchers.IO) {
                                                runCatching { io.github.xororz.localdream.service.BountyClient.listSubs(b) }.getOrDefault(emptyList())
                                            }
                                            bountyBusy = false
                                            subsFor = b
                                        }
                                    }) { Text("查看提交") }
                                }
                            }
                        }
                        HorizontalDivider()
                    }
                    if (bountyBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            },
            confirmButton = { TextButton(onClick = { showBounty = false }) { Text("关闭") } },
        )
    }
    if (subsFor != null) {
        val b = subsFor!!
        val login = io.github.xororz.localdream.cloud.SecureTokenStore(ctx)
            .state(io.github.xororz.localdream.cloud.CloudConfig.Provider.GITHUB)?.login.orEmpty()
        AlertDialog(
            onDismissRequest = { subsFor = null },
            title = { Text("提交成果") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    if (bountySubs.isEmpty()) Text("还没有人提交")
                    bountySubs.forEach { sub ->
                        Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                            Text("@${sub.optString("submitter")} · ${sub.optString("name")}", style = MaterialTheme.typography.bodySmall)
                            val st = sub.optString("status")
                            if (st.isNotBlank()) {
                                Text(
                                    if (st == "rejected") "已驳回：${sub.optString("reason")}" else st,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (st == "rejected") androidx.compose.ui.graphics.Color(0xFFB00020) else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                TextButton(onClick = {
                                    scope.launch {
                                        val bytes = withContext(Dispatchers.IO) {
                                            runCatching { io.github.xororz.localdream.service.BountyClient.decrypt(ctx, sub) }.getOrNull()
                                        }
                                        if (bytes == null) {
                                            android.widget.Toast.makeText(ctx, "解密失败：只有发布者设备能打开", android.widget.Toast.LENGTH_LONG).show()
                                        } else {
                                            withContext(Dispatchers.IO) {
                                                val name = sub.optString("name").ifBlank { "bounty_${System.currentTimeMillis()}" }
                                                val values = android.content.ContentValues().apply {
                                                    put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name)
                                                    put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
                                                    put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS)
                                                }
                                                val uri = ctx.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                                                uri?.let { ctx.contentResolver.openOutputStream(it)?.use { o -> o.write(bytes) } }
                                            }
                                            android.widget.Toast.makeText(ctx, "已解密并保存到下载目录", android.widget.Toast.LENGTH_LONG).show()
                                        }
                                    }
                                }) { Text("打开/保存") }
                                TextButton(onClick = {
                                    scope.launch {
                                        val amount = b.optLong("amount")
                                        val to = sub.optString("submitter")
                                        val ok = withContext(Dispatchers.IO) {
                                            io.github.xororz.localdream.service.BountyClient.pay(login, to, amount)
                                        }
                                        if (ok) {
                                            withContext(Dispatchers.IO) {
                                                io.github.xororz.localdream.service.BountyClient.closeBounty(b, "paid")
                                            }
                                            subsFor = null
                                        }
                                        android.widget.Toast.makeText(
                                            ctx, if (ok) "已把 $amount 额度付给 @$to，悬赏完成" else "支付失败",
                                            android.widget.Toast.LENGTH_LONG,
                                        ).show()
                                    }
                                }) { Text("支付悬赏") }
                                TextButton(onClick = {
                                    scope.launch {
                                        val ok = withContext(Dispatchers.IO) {
                                            io.github.xororz.localdream.service.BountyClient.reject(sub, "不满意，请重新提交")
                                        }
                                        android.widget.Toast.makeText(
                                            ctx, if (ok) "已驳回，提交者会看到原因" else "驳回失败",
                                            android.widget.Toast.LENGTH_LONG,
                                        ).show()
                                        bountySubs = withContext(Dispatchers.IO) {
                                            runCatching { io.github.xororz.localdream.service.BountyClient.listSubs(b) }.getOrDefault(emptyList())
                                        }
                                    }
                                }) { Text("驳回") }
                            }
                            HorizontalDivider()
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { subsFor = null }) { Text("关闭") } },
        )
    }
    if (showSend) {
        AlertDialog(
            onDismissRequest = { showSend = false },
            title = { Text("发送额度") },
            text = {
                Column {
                    val users = items.map { it.author }.distinct()
                    OutlinedTextField(
                        value = sendTo, onValueChange = { sendTo = it },
                        label = { Text("发给谁（输入用户名）") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (users.isNotEmpty()) {
                        Text("可点选作者：", style = MaterialTheme.typography.labelSmall)
                        androidx.compose.foundation.layout.Row(
                            modifier = Modifier
                                .horizontalScroll(rememberScrollState())
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            users.take(30).forEach { u ->
                                androidx.compose.material3.AssistChip(
                                    onClick = { sendTo = u },
                                    label = { Text("@$u") },
                                )
                            }
                        }
                    }
                    OutlinedTextField(
                        value = sendAmount,
                        onValueChange = { sendAmount = it.filter { c -> c.isDigit() } },
                        label = { Text("额度数量") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
                        ),
                    )
                    Text("确认后需要验证锁屏密码，通过才会真正发送。", style = MaterialTheme.typography.labelSmall)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val amount = sendAmount.toLongOrNull() ?: 0L
                    if (sendTo.isBlank() || amount <= 0L) {
                        android.widget.Toast.makeText(ctx, "请填写用户和额度", android.widget.Toast.LENGTH_SHORT).show()
                    } else {
                        pendingSend = sendTo to amount
                        val locked = androidx.core.content.ContextCompat.getSystemService(
                            ctx, android.app.KeyguardManager::class.java,
                        )
                        val intent = locked?.createConfirmDeviceCredentialIntent("验证身份", "发送额度需要验证")
                        if (intent != null) keyguardLauncher.launch(intent)
                        else {
                            pendingSend = null
                            android.widget.Toast.makeText(
                                ctx, "设备未设置锁屏，请先在系统设置里设置锁屏密码再发送",
                                android.widget.Toast.LENGTH_LONG,
                            ).show()
                        }
                        showSend = false
                    }
                }) { Text("发送") }
            },
            dismissButton = { TextButton(onClick = { showSend = false }) { Text("取消") } },
        )
    }
    if (showRank) {
        var rows by remember { mutableStateOf<List<Pair<String, Int>>?>(null) }
        androidx.compose.runtime.LaunchedEffect(Unit) {
            rows = withContext(Dispatchers.IO) { runCatching { GalleryClient.monthlyRanking() }.getOrDefault(emptyList()) }
        }
        AlertDialog(
            onDismissRequest = { showRank = false },
            title = { Text("本月下载榜 · 前 18 名") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    val list = rows
                    if (list == null) {
                        Text("加载中…", style = MaterialTheme.typography.bodySmall)
                    } else if (list.isEmpty()) {
                        Text("本月还没有下载记录", style = MaterialTheme.typography.bodySmall)
                    } else {
                        list.take(18).forEachIndexed { i, (author, count) ->
                            val medal = when (i) {
                                0 -> "🥇"; 1 -> "🥈"; 2 -> "🥉"; else -> "${i + 1}."
                            }
                            androidx.compose.foundation.layout.Row(
                                modifier = Modifier.fillMaxWidth()
                                    .clickable { showRank = false; profileAuthor = author }
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(medal, modifier = Modifier.width(38.dp))
                                Text("@$author", modifier = Modifier.weight(1f), maxLines = 1)
                                Text("$count 次", style = MaterialTheme.typography.labelSmall)
                            }
                            androidx.compose.material3.HorizontalDivider()
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showRank = false }) { Text("关闭") } },
        )
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
    if (showUpload) UploadDialog(ctx, onPublished = {
        GalleryClient.invalidate()
        // Refresh behind the dialog immediately, so closing it shows the new
        // picture instead of an old cached list.
        reload(true)
    }) {
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

    previewItem?.let { item ->
        PreviewDialog(
            ctx, item,
            onAuthorClick = { a -> previewItem = null; profileAuthor = a },
        ) { previewItem = null }
    }
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
private fun UploadDialog(ctx: Context, onPublished: () -> Unit = {}, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var price by remember { mutableStateOf("100") }
    var name by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }
    var uploading by remember { mutableStateOf(false) }
    var showPicker by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(-1f) }
    var pickedUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    val pickedUri: Uri? = pickedUris.firstOrNull()
    var pickedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var pickedFile by remember { mutableStateOf<File?>(null) }
    // 2026-11：不能 remember。设置里退出登录后页面不会重建，remember 的
    // login 仍是旧账号名，于是“已退出还显示我的名字”。每次重组直接读存储。
    val token = SecureTokenStore(ctx).token(CloudConfig.Provider.GITHUB)
    val login = SecureTokenStore(ctx).state(CloudConfig.Provider.GITHUB)?.login

    // 2026-11：相册支持一次选多张，逐张独立发布（后台也会分别生成条目）。
    // 2026-11：系统照片选择器，一次最多 100 张。
    val photoPicker = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickMultipleVisualMedia(100),
    ) { uris ->
        if (uris.isNotEmpty()) {
            pickedFile = null
            pickedUris = uris.take(100)
            pickedBitmap = io.github.xororz.localdream.utils.ImageDecode.decodeSampledUri(ctx, pickedUris.first(), 1024)
        }
    }
    val albumLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) {
            pickedFile = null
            pickedUris = uris
            pickedBitmap = io.github.xororz.localdream.utils.ImageDecode.decodeSampledUri(ctx, uris.first(), 1024)
        }
    }

    val localImages = remember(ctx) { scanLocalOutputs(ctx) }

    Dialog(onDismissRequest = { if (!uploading) onDismiss() }) {
        Surface(shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(20.dp)) {
                Text("上传作品", style = MaterialTheme.typography.titleMedium)
                if (login == null) {
                    Text(
                        "未登录 GitHub：作品将以匿名作者发布；登录后作品会署名你的账号。",
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
                    OutlinedButton(onClick = {
                        runCatching {
                            photoPicker.launch(
                                androidx.activity.result.PickVisualMediaRequest(
                                    androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly,
                                ),
                            )
                        }.onFailure { albumLauncher.launch("image/*") }
                    }, Modifier.weight(1f)) {
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
                    Text(
                        if (pickedUris.size > 1) "已选 ${pickedUris.size} 张（预览首张 ${b.width}×${b.height}）"
                        else "已选：${b.width}×${b.height}",
                        style = MaterialTheme.typography.labelSmall,
                    )
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
                                    pickedUris = emptyList()
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
                                    val targets: List<Pair<Uri?, File?>> =
                                        if (pickedUris.isNotEmpty()) pickedUris.map { it to null }
                                        else listOf(pickedUri to pickedFile)
                                    // 2026-11：退出登录后 login 为空时，旧代码回落成
                                    // "ETQWFD"，于是匿名发布被记到官方号名下。
                                    // 未登录必须按匿名作者发布。
                                    val author = login?.takeIf { it.isNotBlank() } ?: "匿名"
                                    var ok = 0
                                    var fail = 0
                                    var lastErr = ""
                                    targets.forEachIndexed { i, (u, f) ->
                                        val bytes = withContext(Dispatchers.IO) { encodeForGallery(ctx, u, f) }
                                        if (bytes == null || bytes.isEmpty()) {
                                            fail++; lastErr = "图片读取失败"
                                            return@forEachIndexed
                                        }
                                        var lastPct = -1
                                        val base = name.ifBlank { "作品" }
                                        val res = GalleryClient.upload(
                                            bytes,
                                            author = author,
                                            price = p,
                                            name = if (targets.size > 1) "$base ${i + 1}" else base,
                                            userToken = token,
                                            onProgress = { v ->
                                                val pct = (v * 100).toInt().coerceIn(0, 100)
                                                if (pct != lastPct) {
                                                    lastPct = pct
                                                    progress = (i.toFloat() + v) / targets.size.toFloat()
                                                }
                                            },
                                        )
                                        if (res.ok) ok++ else { fail++; lastErr = res.reason }
                                    }
                                    progress = -1f
                                    uploading = false
                                    msg = when {
                                        ok > 0 && fail == 0 -> if (targets.size > 1) "已发布 $ok 张" else "发布成功"
                                        ok > 0 -> "已发布 $ok 张，失败 $fail 张：$lastErr"
                                        else -> "发布失败：$lastErr"
                                    }
                                    if (ok > 0) {
                                        onPublished()
                                        onDismiss()
                                    }
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
            io.github.xororz.localdream.utils.ImageDecode.decodeSampledFile(file.absolutePath, 2048)
        } else {
            null
        }) ?: return null

        val maxEdge = 2560
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
private fun PreviewDialog(
    ctx: Context,
    item: GalleryClient.GalleryItem,
    onAuthorClick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
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
                    zoomable = true,
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { onAuthorClick(item.author) },
                ) {
                    Box(
                        Modifier.size(28.dp).clip(androidx.compose.foundation.shape.CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center,
                    ) { Text(item.author.take(1).uppercase(), style = MaterialTheme.typography.labelSmall) }
                    Spacer(Modifier.width(6.dp))
                    Text("作者：${item.author}（查看主页）", style = MaterialTheme.typography.bodyMedium)
                }
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
                run {
                    val myLogin = io.github.xororz.localdream.cloud.SecureTokenStore(ctx)
                        .state(io.github.xororz.localdream.cloud.CloudConfig.Provider.GITHUB)?.login
                    val myToken = io.github.xororz.localdream.cloud.SecureTokenStore(ctx)
                        .token(io.github.xororz.localdream.cloud.CloudConfig.Provider.GITHUB)
                    if (myLogin != null && item.author == myLogin && !myToken.isNullOrBlank()) {
                        TextButton(enabled = !busy, onClick = {
                            busy = true; msg = ""
                            scope.launch {
                                val (ok, err) = withContext(Dispatchers.IO) {
                                    GalleryClient.delete(item.name, myToken)
                                }
                                busy = false
                                if (ok) { onDismiss() } else { msg = "下架失败：$err" }
                            }
                        }) { Text("下架我的作品", color = MaterialTheme.colorScheme.error) }
                    }
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
            // Mirrors can lag or be blocked in mainland China. The Contents API
            // with the owner token is the guaranteed source of the bytes.
            if (bytes == null) {
                runCatching {
                    val conn = (java.net.URL(
                        "https://api.github.com/repos/ETQWFD/LocalDreamET-Gallery/contents/png/${item.name}"
                    ).openConnection() as java.net.HttpURLConnection)
                    conn.connectTimeout = 12000
                    conn.readTimeout = 30000
                    conn.setRequestProperty("Authorization", "token ${io.github.xororz.localdream.BuildConfig.GALLERY_TOKEN}")
                    conn.setRequestProperty("Accept", "application/vnd.github+json")
                    conn.setRequestProperty("User-Agent", "LocalDream-ET")
                    if (conn.responseCode in 200..299) {
                        val obj = org.json.JSONObject(conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) })
                        val b64 = obj.optString("content", "").replace("\n", "")
                        if (b64.isNotBlank()) bytes = java.util.Base64.getDecoder().decode(b64)
                    }
                }.onFailure { lastReason = it.message ?: "下载失败" }
            }
            val data = bytes ?: return@withContext lastReason
            // 2026-11：超大图直接整张解码会 OOM/返回 null，先算采样再解码，避免“下载失败”。
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
            var sample = 1
            while (bounds.outWidth / sample > 4096 || bounds.outHeight / sample > 4096) sample *= 2
            val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
            val bmp = android.graphics.BitmapFactory.decodeByteArray(data, 0, data.size, opts)
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
            // 2026-11：下载保留原始分辨率；长边不足 7680 时做一次高质量放大到 8K
            // （RGB_565 控制内存，失败自动退回原图，绝不让下载崩掉）。
            val hi = runCatching {
                val longEdge = maxOf(wm.width, wm.height)
                if (longEdge >= 7680) wm else {
                    val k = 7680f / longEdge
                    android.graphics.Bitmap.createScaledBitmap(
                        wm,
                        (wm.width * k).toInt().coerceAtLeast(1),
                        (wm.height * k).toInt().coerceAtLeast(1),
                        true,
                    )
                }
            }.getOrNull() ?: wm
            val out = ByteArrayOutputStream()
            hi.compress(Bitmap.CompressFormat.PNG, 100, out)
            saveToGallery(ctx, "LocalDreamET_${item.name.replace('/', '_')}", out.toByteArray())
            qm.consume(ctx, item.price.toLong())
            runCatching { GalleryClient.bumpDownload(item.author) }
            // 85% to the author, 15% to the platform; failures never block the
            // buyer from getting the picture they paid for.
            runCatching {
                io.github.xororz.localdream.service.GalleryEarnings
                    .record(ctx, item.author, item.price, item.title)
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
