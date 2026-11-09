package io.github.xororz.localdream.ui.screens

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.xororz.localdream.cloud.CloudConfig
import io.github.xororz.localdream.cloud.SecureTokenStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class AnnouncementItem(val title: String, val text: String, val ts: Long)

/** et.130: accept Unix seconds / milliseconds / ISO-8601; always return epoch ms. */
internal fun parseAnnounceTs(o: org.json.JSONObject): Long {
    // numeric may be seconds (~1.7e9) or ms (~1.7e12); ISO strings parse to ms.
    if (o.has("ts") && !o.isNull("ts")) {
        val s = o.optString("ts", "").trim()
        if (s.isNotEmpty()) {
            val num = s.toLongOrNull()
            if (num != null) {
                // < ~1e12 means seconds (ms epoch for 2001 is 1e9; 2286 ms is 4e12)
                return if (num < 1_000_000_000_000L) num * 1000L else num
            }
            runCatching {
                return java.text.SimpleDateFormat(
                    "yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US,
                ).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
                    .parse(s.substringBefore('.'))?.time ?: 0L
            }
        }
    }
    return 0L
}

/** et.130: body field is `content` per backend; fall back to legacy `text`. */
internal fun parseAnnounceContent(o: org.json.JSONObject): String =
    o.optString("content", "").ifBlank { o.optString("text", "") }

private fun hiddenSet(context: Context): MutableSet<String> =
    context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        .getStringSet("hidden_announce_ts", emptySet())?.toMutableSet() ?: mutableSetOf()

/** et.130追加11: mark an announcement hidden on THIS device/account only. */
internal fun hideAnnounceLocal(context: Context, ts: Long) {
    val sp = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
    val s = hiddenSet(context)
    s.add(ts.toString())
    // et.161: 本地隐藏最多保留 50 条，老的自动清理，避免无限膨胀
    val sorted = s.mapNotNull { it.toLongOrNull() }.sorted().toMutableSet()
    while (sorted.size > 50) sorted.remove(sorted.first())
    sp.edit().putStringSet("hidden_announce_ts", sorted.map { it.toString() }.toSet()).apply()
}

/** et.130追加11: home bell badge. Set/cleared by the dialog + startup popup. */
internal fun setAnnounceUnread(context: Context, unread: Boolean) {
    context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        .edit().putBoolean("announce_unread", unread).apply()
}
internal fun hasAnnounceUnread(context: Context): Boolean =
    context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        .getBoolean("announce_unread", false)

private fun loadAll(context: Context): List<AnnouncementItem> {
    val store = SecureTokenStore(context)
    var tok = store.token(CloudConfig.Provider.GITHUB)
    if (tok.isNullOrBlank()) tok = io.github.xororz.localdream.BuildConfig.GALLERY_TOKEN
    if (tok.isNullOrBlank()) return emptyList()
    val repo = "ETQWFD/LocalDream-ET-ratings"
    // et.147: 国内 API 被墙时用镜像拉 index.json 与公告正文。
    fun mirrorUrls(file: String) = listOf(
        "https://raw.gitmirror.com/$repo/main/announcements/$file",
        "https://cdn.statically.io/gh/$repo@main/announcements/$file",
        "https://raw.githubusercontent.com/$repo/main/announcements/$file",
        "https://gh-proxy.com/https://raw.githubusercontent.com/$repo/main/announcements/$file",
    )
    fun httpGet(url: String): Pair<Int, String> {
        return try {
            val c = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                connectTimeout = 8000; readTimeout = 8000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("Authorization", "token " + tok!!)
            }
            val code = c.responseCode
            val body = (if (code == 200) c.inputStream else c.errorStream)
                ?.bufferedReader()?.readText().orEmpty()
            c.disconnect(); code to body
        } catch (e: Exception) { 0 to (e.message ?: "err") }
    }
    // 1) 先拿 index.json（镜像优先）
    var files: List<String> = emptyList()
    for (u in mirrorUrls("index.json")) {
        val (code, text) = httpGet(u)
        if (code == 200) {
            runCatching {
                files = org.json.JSONObject(text).optJSONArray("files")?.let { arr ->
                    (0 until arr.length()).map { arr.optString(it) }
                } ?: emptyList()
            }
            if (files.isNotEmpty()) break
        }
    }
    if (files.isEmpty()) return emptyList()
    val out = ArrayList<AnnouncementItem>()
    for (name in files) {
        if (!name.startsWith("announce_") || !name.endsWith(".json")) continue
        // 2) 正文：镜像优先，失败再试 Contents API
        var raw: String? = null
        for (u in mirrorUrls(name)) {
            val (code, text) = httpGet(u)
            if (code == 200) { raw = text; break }
        }
        if (raw == null) {
            val (code, text) = httpGet("https://api.github.com/repos/$repo/contents/announcements/$name")
            if (code == 200) runCatching {
                val j = org.json.JSONObject(text)
                raw = String(android.util.Base64.decode(j.getString("content"), android.util.Base64.DEFAULT))
            }
        }
        val r = raw ?: continue
        val o = runCatching { org.json.JSONObject(r) }.getOrNull() ?: continue
        val target = o.optString("target", "全部")
        if (!target.contains("手机") && !target.contains("全部")) continue
        val title = o.optString("title", "公告")
        val content = parseAnnounceContent(o)
        val ts = parseAnnounceTs(o)
        val hidden = hiddenSet(context)
        if (ts.toString() in hidden) continue
        out.add(AnnouncementItem(title, content, ts))
    }
    out.sortByDescending { it.ts }
    return out
}

/**
 * et.118/build195b: in-app announcement center. Opens a scrollable list (newest
 * first), marks unread items with a red dot, and marks an item read (advancing
 * last_announce_ts) when the user taps it.
 */
@Composable
fun AnnouncementCenterDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var items by remember { mutableStateOf<List<AnnouncementItem>?>(null) }
    var openItem by remember { mutableStateOf<AnnouncementItem?>(null) }

    LaunchedEffect(Unit) {
        // Opening the list = viewed; clear the home bell badge immediately.
        setAnnounceUnread(context, false)
        items = kotlin.runCatching {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { loadAll(context) }
        }.getOrElse { emptyList() }
    }

    fun markRead(ts: Long) {
        val sp = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val cur = sp.getLong("last_announce_ts", 0L)
        if (ts > cur) sp.edit().putLong("last_announce_ts", ts).apply()
    }

    val fmt = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
        title = { Text("公告") },
        text = {
            when (val list = items) {
                null -> Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                else -> if (list.isEmpty()) {
                    Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                        Text("暂无公告", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    val lastRead = remember {
                        context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                            .getLong("last_announce_ts", 0L)
                    }
                    Column(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        list.forEach { a ->
                            val unread = a.ts > lastRead
                            Row(
                                modifier = Modifier.fillMaxWidth()
                                    .combinedClickable(
                                        onClick = { openItem = a; markRead(a.ts) },
                                        onLongClick = {
                                            // et.130追加11: hide locally only (no cloud delete).
                                            hideAnnounceLocal(context, a.ts)
                                            items = items?.filterNot { it.ts == a.ts }
                                            Toast.makeText(
                                                context,
                                                "已在本机隐藏该公告（不影响其他用户/云端）",
                                                Toast.LENGTH_LONG,
                                            ).show()
                                        },
                                    )
                                    .padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(
                                    modifier = Modifier.size(10.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (unread) Box(
                                        Modifier.size(10.dp).background(Color(0xFFE53935)),
                                    )
                                }
                                Spacer(Modifier.width(8.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(a.title, fontWeight = FontWeight.Bold, maxLines = 1)
                                    Text(
                                        fmt.format(Date(a.ts)),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
        },
    )

    openItem?.let { a ->
        AlertDialog(
            onDismissRequest = { openItem = null },
            confirmButton = { TextButton(onClick = { openItem = null }) { Text("知道了") } },
            title = { Text(a.title) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        fmt.format(Date(a.ts)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(if (a.text.isBlank()) "（无内容）" else a.text)
                }
            },
        )
    }
}
