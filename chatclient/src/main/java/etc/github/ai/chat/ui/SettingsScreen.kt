package etc.github.ai.chat.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import etc.github.ai.chat.Settings
import etc.github.ai.chat.api.ServerApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Settings page: gear icon on the chat top bar. Persists on every save. */
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
        topBar = { TopAppBar(title = { Text("设置") }) },
    ) { pad ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("服务端", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = form.baseUrl,
                onValueChange = { form = form.copy(baseUrl = it) },
                label = { Text("服务端地址 (http://IP:端口)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = form.apiKey,
                onValueChange = { form = form.copy(apiKey = it) },
                label = { Text("API Key（46 位）") },
                visualTransformation = if (keyVisible)
                    androidx.compose.ui.text.input.VisualTransformation.None
                else androidx.compose.ui.text.input.PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { keyVisible = !keyVisible }) {
                        Icon(if (keyVisible) Icons.Default.Visibility
                        else Icons.Default.VisibilityOff, "显示/隐藏")
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
                                    healthMsg = "连接失败：${h.message}"
                                }
                            }
                        }.onFailure {
                            healthState = 3
                            healthMsg = "连接失败：${it.message ?: "超时或地址错误"}"
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

            Spacer(Modifier.height(8.dp))
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
