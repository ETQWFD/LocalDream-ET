package etc.github.ai.chat.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import etc.github.ai.chat.api.ServerApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val ACTIONABLE_HINT =
    "连不上服务端，请按顺序检查：\n" +
        "① 手机和电脑连同一个 WiFi（不是手机热点/不同网段）；\n" +
        "② 地址填电脑物理网卡的局域网 IP（不要填 172.17/169.254 这类虚拟网卡地址；服务端窗口会把推荐地址列在最前面）；\n" +
        "③ Windows 首次防火墙弹窗点了「允许」，或在防火墙放行 8080；\n" +
        "④ 电脑上服务端已启动并显示就绪。"

/** Settings page: gear icon on the chat top bar. Persists on save. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: ChatViewModel, onClose: () -> Unit) {
    var form by remember { mutableStateOf(vm.settings) }
    var keyVisible by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // health test states: 0 idle, 1 ready(green), 2 starting(yellow), 3 fail(red)
    var healthState by remember { mutableStateOf(0) }
    var healthMsg by remember { mutableStateOf("") }
    var testing by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { pad ->
        Column(
            Modifier
                .fillMaxWidth()
                .padding(pad)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("服务端", style = MaterialTheme.typography.titleMedium)

            // address + scan button on one row
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = form.baseUrl,
                    onValueChange = { form = form.copy(baseUrl = it) },
                    label = { Text("服务端地址 http://IP:8080") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                )
                Spacer(Modifier.padding(horizontal = 4.dp))
                if (vm.scanning) {
                    OutlinedButton(onClick = { vm.cancelLanScan() }) { Text("取消扫描") }
                } else {
                    OutlinedButton(onClick = { vm.startLanScan() }) { Text("扫描局域网") }
                }
            }
            if (vm.scanning) {
                Text(vm.scanProgress, style = MaterialTheme.typography.bodySmall)
            }
            vm.scanFound.forEach { f ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            vm.fillAddress(f.base)
                            form = form.copy(baseUrl = f.base)
                        }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        f.base,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        if (f.ready) "就绪 ${f.model}" else "模型加载中",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (f.ready) Color(0xFF2E7D32) else Color(0xFFF9A825),
                    )
                }
            }

            OutlinedTextField(
                value = form.apiKey,
                onValueChange = { form = form.copy(apiKey = it) },
                label = { Text("API Key（46 位）") },
                visualTransformation = if (keyVisible)
                    androidx.compose.ui.text.input.VisualTransformation.None
                else androidx.compose.ui.text.input.PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { keyVisible = !keyVisible }) {
                        Icon(
                            if (keyVisible) Icons.Default.Visibility
                            else Icons.Default.VisibilityOff, "显示/隐藏",
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = form.modelName,
                onValueChange = { form = form.copy(modelName = it) },
                label = { Text("模型名 / AI 名称（仅展示）") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = form.negativePrompt,
                onValueChange = { form = form.copy(negativePrompt = it) },
                label = { Text("全局负面提示语（应用到所有出图）") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                maxLines = 4,
            )
            OutlinedTextField(
                value = form.batchSize,
                onValueChange = { form = form.copy(batchSize = it) },
                label = { Text("批量数（留空 = 跟随服务端）") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("留空=跟随服务端") },
            )

            Button(
                onClick = {
                    testing = true
                    healthState = 0
                    healthMsg = ""
                    scope.launch {
                        val r = withContext(Dispatchers.IO) {
                            runCatching { ServerApi.healthz(form.baseUrl) }
                        }
                        testing = false
                        r.onSuccess { h ->
                            when (h) {
                                is ServerApi.Health.Ready -> {
                                    healthState = 1
                                    healthMsg = "服务端就绪，当前模型 ${h.model}"
                                }
                                ServerApi.Health.Starting -> {
                                    healthState = 2
                                    healthMsg = "模型加载中，暂不能出图"
                                }
                                is ServerApi.Health.Fail -> {
                                    healthState = 3
                                    healthMsg = "${h.message}\n\n$ACTIONABLE_HINT"
                                }
                            }
                        }.onFailure {
                            healthState = 3
                            healthMsg = "${it.message ?: "超时或地址错误"}\n\n$ACTIONABLE_HINT"
                        }
                    }
                },
                enabled = !testing,
            ) { Text(if (testing) "检测中…" else "连接测试") }

            when (healthState) {
                1 -> Text(healthMsg, color = Color(0xFF2E7D32))
                2 -> Text(healthMsg, color = Color(0xFFF9A825))
                3 -> Text(healthMsg, color = Color(0xFFC62828))
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            Text("可选：云上传（GitHub）", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = form.githubToken,
                onValueChange = { form = form.copy(githubToken = it) },
                label = { Text("GitHub Token（留空=关闭云上传）") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = form.githubRepo,
                onValueChange = { form = form.copy(githubRepo = it) },
                label = { Text("仓库 owner/repo") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("owner/repo") },
            )

            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = {
                    vm.updateSettings(form)
                    onClose()
                }) { Text("保存并返回") }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
