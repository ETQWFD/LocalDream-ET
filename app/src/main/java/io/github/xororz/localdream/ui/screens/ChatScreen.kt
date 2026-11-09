package io.github.xororz.localdream.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
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
                                return@withContext JSONArray(c.inputStream.bufferedReader().readText())
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

    fun sendText(text: String) {
        if (text.isBlank()) return
        sending = true; err = ""
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val tok = SecureTokenStore(ctx).token(CloudConfig.Provider.GITHUB) ?: ""
                    if (tok.isBlank()) { err = "请先登录 GitHub"; return@withContext }
                    val newMsg = JSONObject()
                        .put("author", "user").put("text", text)
                        .put("ts", System.currentTimeMillis() / 1000)
                    val newArr = JSONArray()
                    msgs.forEach { newArr.put(it) }
                    newArr.put(newMsg)
                    var sha = ""
                    try {
                        val gu = URL("https://api.github.com/repos/ETQWFD/LocalDreamET-Chat/contents/messages.json")
                        val gc = (gu.openConnection() as HttpURLConnection).apply {
                            connectTimeout=5000; readTimeout=6000
                            setRequestProperty("Authorization","token $tok")
                        }
                        if (gc.responseCode == 200)
                            sha = JSONObject(gc.inputStream.bufferedReader().readText()).optString("sha","")
                    } catch (_: Exception) {}
                    val body = JSONObject()
                        .put("message", "chat")
                        .put("content", android.util.Base64.encodeToString(newArr.toString().toByteArray(), android.util.Base64.NO_WRAP))
                        .put("sha", sha)
                    val pu = URL("https://api.github.com/repos/ETQWFD/LocalDreamET-Chat/contents/messages.json")
                    val pc = (pu.openConnection() as HttpURLConnection).apply {
                        requestMethod="PUT"; connectTimeout=10000; readTimeout=15000
                        setRequestProperty("Authorization","token $tok")
                        setRequestProperty("Accept","application/vnd.github+json")
                        setRequestProperty("Content-Type","application/json")
                        doOutput=true
                    }
                    pc.outputStream.use { it.write(body.toString().toByteArray()) }
                    if (pc.responseCode !in 200..299) err = "发送失败 code=${pc.responseCode}" else input = ""
                }
            }
            sending = false; load()
        }
    }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { sendText("[图片] $it") }
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
                            ChatTextWithLinks(m.optString("text",""))
                            Text(SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(m.optLong("ts",0L)*1000L)),
                                fontSize=10.sp, color=MaterialTheme.colorScheme.outline)
                        }
                    }
                }
            }
            if (err.isNotEmpty()) Text(err, color=MaterialTheme.colorScheme.error, fontSize=12.sp)
            Row(Modifier.fillMaxWidth().padding(top=8.dp), horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                IconButton(onClick = { pickImage.launch("image/*") }) { Icon(Icons.Default.Add, "发图片") }
                OutlinedTextField(value = input, onValueChange = { input = it },
                    modifier = Modifier.weight(1f), placeholder = { Text("说点什么…") })
                Button(onClick = { sendText(input) }, enabled = !sending) { Icon(Icons.Default.Send, "发送") }
            }
        }
    }
}

@Composable
private fun ChatTextWithLinks(text: String) {
    val ctx = LocalContext.current
    val urlRe = Regex("(https?://[^\\s]+|www\\.[^\\s]+|[a-zA-Z0-9.-]+\\.(?:com|cn|net|org|io|gg|kg)[^\\s]*)")
    val annotated = buildAnnotatedString {
        var last = 0
        for (m in urlRe.findAll(text)) {
            append(text.substring(last, m.range.first))
            pushStringAnnotation("url", m.value)
            withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary)) { append(m.value) }
            pop()
            last = m.range.last + 1
        }
        append(text.substring(last))
    }
    ClickableText(text = annotated, style = LocalTextStyle.current, onClick = { off ->
        annotated.getStringAnnotations("url", off, off).firstOrNull()?.let { a ->
            var u = a.item
            if (!u.startsWith("http")) u = "https://$u"
            runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, u.toUri())) }
        }
    })
}
