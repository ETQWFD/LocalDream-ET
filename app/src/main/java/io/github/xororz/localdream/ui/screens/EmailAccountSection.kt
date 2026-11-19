package io.github.xororz.localdream.ui.screens

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.xororz.localdream.account.EmailAccount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * et.246：邮箱注册 / 登录。
 *
 * 交互按需求做：邮箱 + 密码 + 再输一次密码确认 + 勾选"我已同意协议"，
 * 点"确认注册"后把账号写进私密仓库 users/<邮箱>/account.json（只存 PBKDF2
 * 哈希，不存明文密码）。登录则是把这份文件读回来比对。
 */
@Composable
fun EmailAccountSection(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf(EmailAccount.signedInEmail(context)) }
    var pwd by remember { mutableStateOf("") }
    var pwd2 by remember { mutableStateOf("") }
    var agreed by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var signedIn by remember { mutableStateOf(EmailAccount.isSignedIn(context)) }
    // et.251：登录 / 注册必须能切。以前是把"确认密码 + 协议勾选 + 确认注册"
    // 全都常显，登录只是在旁边放一个按钮 —— 用户看到的就是"一直是注册界面"。
    // 现在默认进登录页（已注册的人直接登录），要点一下才切到注册。
    var registerMode by remember { mutableStateOf(false) }
    var showTerms by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            when {
                signedIn -> "邮箱账号：已登录 ${EmailAccount.signedInEmail(context)}"
                registerMode -> "邮箱账号 · 注册（注册后自动登录）"
                else -> "邮箱账号 · 登录"
            },
            style = MaterialTheme.typography.titleSmall,
        )
        if (!signedIn) {
            // 两个可点的模式标签：当前模式填充，另一个描边。
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (registerMode) {
                    OutlinedButton(onClick = { registerMode = false; status = null }) { Text("登录") }
                    Button(onClick = { registerMode = true }) { Text("注册") }
                } else {
                    Button(onClick = { registerMode = false }) { Text("登录") }
                    OutlinedButton(onClick = { registerMode = true; status = null }) { Text("注册") }
                }
            }
        }
        Text(
            "账号与图片存放在私密仓库 NightmareRoad-ET-Private 的 users/<邮箱>/ 目录，" +
                "云盘 App 用同一个账号登录。密码只保存 PBKDF2 哈希，不保存明文。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text("邮箱") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.fillMaxWidth(),
        )
        if (!signedIn) {
            OutlinedTextField(
                value = pwd,
                onValueChange = { pwd = it },
                label = { Text("密码（至少 6 位）") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            if (registerMode) {
                OutlinedTextField(
                    value = pwd2,
                    onValueChange = { pwd2 = it },
                    label = { Text("再次输入密码确认") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = agreed, onCheckedChange = { agreed = it })
                    Text(
                        "我已阅读并同意《用户协议与隐私政策》",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                }
                // 原来这个按钮和上面那行文字挤在同一个 Row 里，宽度被压到
                // 只能一个字一行，就成了截图里竖着排的"查看协议"。单独一行。
                androidx.compose.material3.TextButton(
                    onClick = { showTerms = true },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                    modifier = Modifier.align(Alignment.Start),
                ) { Text("查看《用户协议与隐私政策》") }
            }
        }
        status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        when {
            signedIn -> OutlinedButton(
                onClick = {
                    EmailAccount.signOut(context)
                    signedIn = false
                    pwd = ""; pwd2 = ""
                    status = "已退出邮箱账号"
                },
            ) { Text("退出登录") }
            registerMode -> {
                Button(
                    enabled = !busy,
                    onClick = {
                        busy = true; status = null
                        scope.launch {
                            val err = withContext(Dispatchers.IO) {
                                EmailAccount.register(context, email, pwd, pwd2, agreed)
                            }
                            busy = false
                            if (err == null) {
                                signedIn = true
                                status = "注册成功，已登录"
                                Toast.makeText(context, "注册成功", Toast.LENGTH_SHORT).show()
                            } else status = err
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (busy) "处理中…" else "确认注册") }
                androidx.compose.material3.TextButton(onClick = { registerMode = false; status = null }) {
                    Text("已有账号？返回登录")
                }
            }
            else -> {
                Button(
                    enabled = !busy,
                    onClick = {
                        busy = true; status = null
                        scope.launch {
                            val err = withContext(Dispatchers.IO) {
                                EmailAccount.login(context, email, pwd)
                            }
                            busy = false
                            if (err == null) {
                                signedIn = true
                                pwd = ""; pwd2 = ""
                                status = "登录成功"
                            } else status = err
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (busy) "处理中…" else "登录") }
                androidx.compose.material3.TextButton(onClick = { registerMode = true; status = null }) {
                    Text("还没有账号？用邮箱注册")
                }
            }
        }
    }

    if (showTerms) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showTerms = false },
            title = { Text("用户协议与隐私政策") },
            text = {
                Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                    Text(
                        """Nightmare Road-ET · 用户协议与隐私政策

1. 账号与数据：邮箱账号与图片保存在作者的私密仓库 NightmareRoad-ET-Private 中，目录以你的邮箱命名，只有该目录的持有者可见；仓库本身不公开。
2. 出图在本机完成：模型推理全部在手机本地运行，不会把你的提示词或相册内容上传到任何服务器。
3. 上传范围：只有你在发布框里手动选中并确认发布的图片会被上传到图片社区，以及（登录后）同时镜像一份到你自己的图片文件夹。应用不会自动扫描相册。
4. 密码：本地只保存邮箱，不保存明文密码；服务端仅保存 PBKDF2-HMAC-SHA256（120000 轮）派生的校验值与随机盐。
5. 内容责任：请勿发布违法、侵权或他人隐私内容，由此造成的后果由发布者承担，作者有权下架违规内容。
6. 协议更新：继续使用该应用即视为接受最新版本的协议。""".trimIndent(),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = {
                Button(onClick = { agreed = true; showTerms = false }) { Text("同意并勾选") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showTerms = false }) { Text("关闭") }
            },
        )
    }
}
