package io.github.xororz.localdream.ui.screens

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.xororz.localdream.service.RedemptionManager

/** et.119/build197: prize-code redemption dialog (9-char code). */
@Composable
fun RedeemDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                enabled = !busy,
                onClick = {
                    val input = code.trim()
                    if (input.isEmpty()) {
                        Toast.makeText(context, "请输入兑换码", Toast.LENGTH_LONG).show()
                        return@TextButton
                    }
                    if (input.length > 9) {
                        Toast.makeText(context, "兑换码最长9位", Toast.LENGTH_LONG).show()
                        return@TextButton
                    }
                    busy = true
                    val result = kotlin.runCatching {
                        io.github.xororz.localdream.service.RedemptionManager.redeem(context, input)
                    }.getOrElse { RedemptionManager.Result.Invalid }
                    busy = false
                    when (result) {
                        is RedemptionManager.Result.Ok -> {
                            Toast.makeText(
                                context,
                                if (result.credit > 0) "兑换成功，额度 +${result.credit}"
                                else "兑换成功",
                                Toast.LENGTH_LONG,
                            ).show()
                            onDismiss()
                        }
                        RedemptionManager.Result.Used ->
                            Toast.makeText(context, "该兑换码已使用", Toast.LENGTH_LONG).show()
                        RedemptionManager.Result.Invalid ->
                            Toast.makeText(context, "兑换码无效", Toast.LENGTH_LONG).show()
                        RedemptionManager.Result.NeedLogin ->
                            Toast.makeText(context, "你确定有这个奖品兑换码吗？请先登录", Toast.LENGTH_LONG).show()
                        RedemptionManager.Result.Timeout ->
                            Toast.makeText(context, "请求超时，请重试", Toast.LENGTH_LONG).show()
                        RedemptionManager.Result.NoNetwork ->
                            Toast.makeText(context, "无网络连接，请检查网络", Toast.LENGTH_LONG).show()
                        RedemptionManager.Result.Forbidden ->
                            Toast.makeText(context, "令牌无效或权限不足", Toast.LENGTH_LONG).show()
                        is RedemptionManager.Result.WriteFailed ->
                            Toast.makeText(context, "写入失败：${(result as RedemptionManager.Result.WriteFailed).reason}", Toast.LENGTH_LONG).show()
                    }
                },
            ) { Text("兑换") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        title = { Text("兑换奖品") },
        text = {
            Column {
                Text(
                    "输入兑换码（最多9位，数字+字母）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = code,
                    onValueChange = { v ->
                        // et.130: max 9 alphanumeric, no minimum length.
                        code = v.uppercase().filter { it.isLetterOrDigit() }.take(9)
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("兑换码") },
                )
            }
        },
    )
}
