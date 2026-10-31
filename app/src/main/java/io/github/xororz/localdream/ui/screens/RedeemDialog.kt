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
                enabled = !busy && code.length >= 6,
                onClick = {
                    busy = true
                    val result = kotlin.runCatching {
                        io.github.xororz.localdream.service.RedemptionManager.redeem(context, code)
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
                        RedemptionManager.Result.Invalid ->
                            Toast.makeText(context, "兑换码无效或已被使用", Toast.LENGTH_LONG).show()
                        RedemptionManager.Result.NeedLogin ->
                            Toast.makeText(context, "你确定有这个奖品兑换码吗？请先登录", Toast.LENGTH_LONG).show()
                    }
                },
            ) { Text("兑换") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        title = { Text("兑换奖品") },
        text = {
            Column {
                Text(
                    "输入 9 位兑换码",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = code,
                    onValueChange = { if (it.length <= 12) code = it.uppercase() },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("兑换码") },
                )
            }
        },
    )
}
