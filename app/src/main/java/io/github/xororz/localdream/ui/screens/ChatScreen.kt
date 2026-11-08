package io.github.xororz.localdream.ui.screens

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.xororz.localdream.cloud.SecureTokenStore
import io.github.xororz.localdream.cloud.CloudConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** et.157: 公共聊天，messages.json 在 ETQWFD/LocalDreamET-Chat 仓库。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var msgs by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var input by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf("") }

    fun load() {
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val urls = listOf(
                        "https://cdn.jsdelivr.net/gh/ETQWFD/LocalDreamET-Chat@main/messages.json",
                        "https://raw.bgithub.xyz/ETQWFD/LocalDreamET-Chat/main/messages.json",
                        "https://raw.kkgithub.com/ETQWFD/LocalDreamET-Chat/main/messages.json",
                    )
                    for (u in urls) {
                        try {
                            val c = (URL(u).openConnection() as HttpURLConnection).apply {
                                connectTimeout = 5000; readTimeout = 6000
                            }
                            if (c.responseCode == 200) {
                                val body = c.inputStream.bufferedReader().readText()
                                return@withContext JSONArray(body)
                            }
                        } catch (_: Exception) {}
                    }
                    JSONArray("[]")
                }
            }.onSuccess { arr ->
                val list = mutableListOf<JSONObject>()
                for (i in 0 until arr.length()) list.add(arr.getJSONObject(i))
                msgs = list
            }
        }
    }
    LaunchedEffect(Unit) { load() }

    fun send() {
        val text = input.trim()
        if (text.isEmpty()) return
        sending = true; err = ""
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val tok = SecureTokenStore(ctx).token(CloudConfig.Provider.GITHUB) ?: ""
                    if (tok.isBlank()) { err = "请先在侧边栏登录 GitHub"; return@withContext }
                    val author = "user"
                    val newMsg = JSONObject()
                        .put("author", author)
                        .put("text", text)
                        .put("ts", System.currentTimeMillis() / 1000)
                    val newArr = JSONArray()
                    msgs.forEach { newArr.put(it) }
                    newArr.put(newMsg)
                    // GET sha
                    var sha = ""
                    try {
                        val gu = URL("https://api.github.com/repos/ETQWFD/LocalDreamET-Chat/contents/messages.json")
                        val gc = (gu.openConnection() as HttpURLConnection).apply {
                            connectTimeout=5000; readTimeout=6000
                            setRequestProperty("Authorization","token $tok")
                        }
                        if (gc.responseCode == 200) {
                            sha = JSONObject(gc.inputStream.bufferedReader().readText()).optString("sha","")
                        }
                    } catch (_: Exception) {}
                    val body = JSONObject()
                        .put("message", "chat: $text")
                        .put("content", android.util.Base64.encodeToString(
                            newArr.toString().toByteArray(), android.util.Base64.NO_WRAP))
                        .put("sha", sha)
                    val pu = URL("https://api.github.com/repos/ETQWFD/LocalDreamET-Chat/contents/messages.json")
                    val pc = (pu.openConnection() as HttpURLConnection).apply {
                        requestMethod="PUT"; connectTimeout=8000; readTimeout=10000
                        setRequestProperty("Authorization","token $tok")
                        setRequestProperty("Content-Type","application/json")
                        doOutput=true
                    }
                    pc.outputStream.use { it.write(body.toString().toByteArray()) }
                    if (pc.responseCode !in 200..299) {
                        err = "发送失败 code=${pc.responseCode}"
                    } else {
                        input = ""
                    }
                }
            }
            sending = false
            load()
        }
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text("公共聊天") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "返回") } })
    }) { pad ->
        Column(Modifier.padding(pad).padding(16.dp)) {
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(msgs.reversed()) { m ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(m.optString("author","?"), fontSize=12.sp, color=MaterialTheme.colorScheme.primary)
                            Text(m.optString("text",""), fontSize=14.sp)
                            val ts = m.optLong("ts",0L) * 1000L
                            Text(SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ts)),
                                fontSize=10.sp, color=MaterialTheme.colorScheme.outline)
                        }
                    }
                }
            }
            if (err.isNotEmpty()) Text(err, color=MaterialTheme.colorScheme.error, fontSize=12.sp)
            Row(Modifier.fillMaxWidth().padding(top=8.dp), horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = input, onValueChange = { input = it },
                    modifier = Modifier.weight(1f), placeholder = { Text("说点什么…") })
                Button(onClick = { send() }, enabled = !sending) { Text("发送") }
            }
        }
    }
}
