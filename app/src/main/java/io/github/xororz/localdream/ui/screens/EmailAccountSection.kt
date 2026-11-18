package io.github.xororz.localdream.ui.screens

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

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            if (signedIn) "邮箱账号：已登录 ${EmailAccount.signedInEmail(context)}" else "邮箱账号（注册 / 登录）",
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            "注册后你的账号与图片存放在私密仓库 NightmareRoad-ET-Private 的 users/<邮箱>/ 目录，" +
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
                Text("我已阅读并同意《用户协议与隐私政策》", style = MaterialTheme.typography.bodySmall)
                TextButton(
                    onClick = {
                        status = "协议：本 App 在本地出图，不上传相册内容；" +
                            "邮箱与图片存放在作者的私密仓库，账号密码只保存哈希；" +
                            "请勿用于生成违法内容，违规账号会被清除。"
                    },
                ) { Text("查看协议") }
            }
        }
        status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        if (signedIn) {
            OutlinedButton(
                onClick = {
                    EmailAccount.signOut(context)
                    signedIn = false
                    pwd = ""; pwd2 = ""
                    status = "已退出邮箱账号"
                },
            ) { Text("退出登录") }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                ) { Text(if (busy) "处理中…" else "确认注册") }
                OutlinedButton(
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
                ) { Text("登录") }
            }
        }
    }
}
