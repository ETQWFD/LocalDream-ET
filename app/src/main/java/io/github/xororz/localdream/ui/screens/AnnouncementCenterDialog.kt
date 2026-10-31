package io.github.xororz.localdream.ui.screens

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.clickable
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

private fun loadAll(context: Context): List<AnnouncementItem> {
    val store = SecureTokenStore(context)
    val tok = store.token(CloudConfig.Provider.GITHUB) ?: return emptyList()
    val repo = "ETQWFD/LocalDream-ET-ratings"
    val listConn = (java.net.URL("https://api.github.com/repos/$repo/contents/announcements")
        .openConnection() as java.net.HttpURLConnection).apply {
        connectTimeout = 8000; readTimeout = 8000
        setRequestProperty("Authorization", "token $tok")
        setRequestProperty("Accept", "application/vnd.github+json")
    }
    if (listConn.responseCode != 200) return emptyList()
    val arr = org.json.JSONArray(listConn.inputStream.bufferedReader().readText())
    val out = ArrayList<AnnouncementItem>()
    for (i in 0 until arr.length()) {
        val name = arr.getJSONObject(i).optString("name", "")
        if (!name.startsWith("announce_") || !name.endsWith(".json")) continue
        runCatching {
            val c = (java.net.URL("https://api.github.com/repos/$repo/contents/announcements/$name")
                .openConnection() as java.net.HttpURLConnection).apply {
                connectTimeout = 8000; readTimeout = 8000
                setRequestProperty("Authorization", "token $tok")
                setRequestProperty("Accept", "application/vnd.github+json")
            }
            if (c.responseCode != 200) return@runCatching
            val j = org.json.JSONObject(c.inputStream.bufferedReader().readText())
            val raw = String(android.util.Base64.decode(j.getString("content"), android.util.Base64.DEFAULT))
            val o = org.json.JSONObject(raw)
            val target = o.optString("target", "全部")
            if (!target.contains("手机") && !target.contains("全部")) return@runCatching
            val title = o.optString("title", "公告")
            val content = parseAnnounceContent(o)
            val ts = parseAnnounceTs(o)
            android.util.Log.i("Announce", "loaded $name title=$title ts=$ts contentLen=${content.length}")
            out.add(AnnouncementItem(title, content, ts))
        }
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
                                    .clickable { openItem = a; markRead(a.ts) }
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
