package io.github.xororz.localdream.ui.screens

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
import coil.compose.AsyncImage
import io.github.xororz.localdream.cloud.CloudConfig
import io.github.xororz.localdream.cloud.SecureTokenStore
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
                    val tok = runCatching { SecureTokenStore(ctx).token(CloudConfig.Provider.GITHUB) }.getOrNull()
                    val c = (URL("https://api.github.com/repos/ETQWFD/LocalDream-ET/issues/1/comments?per_page=100").openConnection() as HttpURLConnection).apply {
                        connectTimeout = 6000; readTimeout = 8000
                        setRequestProperty("Accept", "application/vnd.github+json")
                        if (!tok.isNullOrBlank()) setRequestProperty("Authorization", "token $tok")
                    }
                    if (c.responseCode == 200) JSONArray(c.inputStream.bufferedReader().readText()) else JSONArray("[]")
                }
            }.onSuccess { arr ->
                val cutoff = System.currentTimeMillis() / 1000 - 7L * 86400L
                val list = mutableListOf<JSONObject>()
                val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val ts = runCatching { fmt.parse(o.optString("created_at",""))?.time ?: 0L }.getOrDefault(0L) / 1000L
                    if (ts >= cutoff) list.add(o)
                }
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
                    val body = JSONObject().put("body", text).toString()
                    val pc = (URL("https://api.github.com/repos/ETQWFD/LocalDream-ET/issues/1/comments").openConnection() as HttpURLConnection).apply {
                        requestMethod="POST"; connectTimeout=8000; readTimeout=10000
                        setRequestProperty("Authorization","token $tok")
                        setRequestProperty("Accept","application/vnd.github+json")
                        setRequestProperty("Content-Type","application/json")
                        doOutput=true
                    }
                    pc.outputStream.use { it.write(body.toByteArray()) }
                    if (pc.responseCode !in 200..299) err = "发送失败 code=${pc.responseCode}" else input = ""
                }
            }
            sending = false
            load()
        }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("公共聊天") },
        navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "返回") } }) }) { pad ->
        Column(Modifier.padding(pad).padding(16.dp)) {
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(msgs.reversed()) { m ->
                    val user = m.optJSONObject("user") ?: JSONObject()
                    val login = user.optString("login", "?")
                    val avatar = user.optString("avatar_url", "")
                    val body = m.optString("body", "")
                    val ts = runCatching { SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).parse(m.optString("created_at",""))?.time ?: 0L }.getOrDefault(0L)
                    Card(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(12.dp)) {
                            if (avatar.isNotBlank()) AsyncImage(model = avatar, contentDescription = null, modifier = Modifier.size(36.dp))
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Row {
                                    Text(login, fontSize=12.sp, color=MaterialTheme.colorScheme.primary)
                                    if (login == "ETQWFD") { Spacer(Modifier.width(6.dp)); Text("管理员", fontSize=10.sp, color=MaterialTheme.colorScheme.primary) }
                                }
                                Text(body, fontSize=14.sp)
                                Text(SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ts)), fontSize=10.sp, color=MaterialTheme.colorScheme.outline)
                            }
                        }
                    }
                }
            }
            if (err.isNotEmpty()) Text(err, color=MaterialTheme.colorScheme.error, fontSize=12.sp)
            Row(Modifier.fillMaxWidth().padding(top=8.dp), horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = input, onValueChange = { input = it }, modifier = Modifier.weight(1f), placeholder = { Text("说点什么…") })
                Button(onClick = { send() }, enabled = !sending) { Text("发送") }
            }
        }
    }
}
