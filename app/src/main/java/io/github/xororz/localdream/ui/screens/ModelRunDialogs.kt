package io.github.xororz.localdream.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import io.github.xororz.localdream.R
import io.github.xororz.localdream.ui.components.SmoothLinearWavyProgressIndicator

/**
 * Shared confirm/cancel dialog used by the run screen (exit, reset, delete,
 * batch operations, runtime warnings). [destructiveConfirm] tints the confirm
 * action with the error color.
 */
@Composable
internal fun ModelRunConfirmDialog(
    title: String,
    text: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    confirmText: String = stringResource(R.string.confirm),
    dismissText: String = stringResource(R.string.cancel),
    destructiveConfirm: Boolean = false,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = if (destructiveConfirm) {
                    ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    )
                } else {
                    ButtonDefaults.textButtonColors()
                },
            ) {
                Text(confirmText)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(dismissText)
            }
        },
    )
}

/** W:H input dialog for the SDXL custom aspect ratio. Confirms with "W:H". */
@Composable
internal fun CustomAspectRatioDialog(onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var ratioWStr by remember { mutableStateOf("") }
    var ratioHStr by remember { mutableStateOf("") }
    var ratioError by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.aspect_ratio_custom_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.aspect_ratio_custom_hint),
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = ratioWStr,
                        onValueChange = {
                            ratioWStr = it.filter { c -> c.isDigit() }.take(5)
                            ratioError =
                                false
                        },
                        label = { Text("W") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                        shape = MaterialTheme.shapes.medium,
                        isError = ratioError,
                    )
                    Text(":", style = MaterialTheme.typography.titleLarge)
                    OutlinedTextField(
                        value = ratioHStr,
                        onValueChange = {
                            ratioHStr = it.filter { c -> c.isDigit() }.take(5)
                            ratioError =
                                false
                        },
                        label = { Text("H") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                        shape = MaterialTheme.shapes.medium,
                        isError = ratioError,
                    )
                }
                if (ratioError) {
                    Text(
                        stringResource(R.string.aspect_ratio_invalid),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val w = ratioWStr.toIntOrNull()
                val h = ratioHStr.toIntOrNull()
                if (w != null && h != null && w > 0 && h > 0) {
                    onConfirm("$w:$h")
                } else {
                    ratioError = true
                }
            }) { Text(stringResource(R.string.confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

/** Modal progress dialog shown while batch-saving history images to the gallery. */
@Composable
internal fun BatchSaveProgressDialog(current: Int, total: Int) {
    AlertDialog(
        onDismissRequest = { /* not dismissable */ },
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
        ),
        title = { Text(stringResource(R.string.batch_save)) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    stringResource(
                        R.string.batch_saving_progress,
                        current,
                        total,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                val saveProgress = if (total > 0) {
                    current.toFloat() / total
                } else {
                    0f
                }
                SmoothLinearWavyProgressIndicator(
                    progress = saveProgress,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {},
    )
}
