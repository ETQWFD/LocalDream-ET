package io.github.xororz.localdream.ui.screens

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.xororz.localdream.BuildConfig
import io.github.xororz.localdream.R
import io.github.xororz.localdream.utils.AppUpdater
import kotlinx.coroutines.launch
import java.io.File

private enum class UpdatePhase { CHECKING, UP_TO_DATE, AVAILABLE, DOWNLOADING, READY, ERROR, NEED_PERMISSION }

/**
 * Settings -> "Check for updates" dialog. Runs the full in-app update flow:
 * check -> download with progress -> request install permission -> launch the
 * system package installer.
 */
@Composable
internal fun UpdateDialog(
    onDismiss: () -> Unit,
    prefetched: AppUpdater.UpdateInfo? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var phase by remember {
        mutableStateOf(
            if (prefetched != null) UpdatePhase.AVAILABLE else UpdatePhase.CHECKING,
        )
    }
    var info by remember { mutableStateOf(prefetched) }
    var downloadedFile by remember { mutableStateOf<File?>(null) }
    var progress by remember { mutableFloatStateOf(0f) }
    var statusText by remember { mutableStateOf("") }
    var errorText by remember { mutableStateOf("") }
    // Bumped to retrigger the initial/retry check.
    var checkToken by remember { mutableIntStateOf(0) }

    fun beginCheck() {
        phase = UpdatePhase.CHECKING
        scope.launch {
            val result = runCatching { AppUpdater.check() }
            result.onSuccess { update ->
                if (update == null) {
                    phase = UpdatePhase.UP_TO_DATE
                } else {
                    info = update
                    phase = UpdatePhase.AVAILABLE
                }
            }.onFailure { e ->
                errorText = e.message ?: e.toString()
                phase = UpdatePhase.ERROR
            }
        }
    }

    fun startDownload(target: AppUpdater.UpdateInfo) {
        phase = UpdatePhase.DOWNLOADING
        progress = 0f
        statusText = ""
        scope.launch {
            val result = runCatching {
                AppUpdater.download(context, target.apkUrl) { done, total ->
                    progress = if (total > 0) done.toFloat() / total else 0f
                    statusText = if (total > 0) {
                        "${formatBytes(done)} / ${formatBytes(total)}"
                    } else {
                        formatBytes(done)
                    }
                }
            }
            result.onSuccess { apk ->
                downloadedFile = apk
                if (AppUpdater.canInstall(context)) {
                    phase = UpdatePhase.READY
                    AppUpdater.install(context, apk)
                    onDismiss()
                } else {
                    phase = UpdatePhase.NEED_PERMISSION
                }
            }.onFailure { e ->
                errorText = e.message ?: e.toString()
                phase = UpdatePhase.ERROR
            }
        }
    }

    // Returning from the system "install unknown apps" settings page.
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        val apk = downloadedFile
        if (apk != null && AppUpdater.canInstall(context)) {
            AppUpdater.install(context, apk)
            onDismiss()
        } else {
            Toast.makeText(
                context,
                context.getString(R.string.update_permission_denied),
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    LaunchedEffect(checkToken) {
        // A prefetched result means we arrived from the silent startup check;
        // only hit the network again on explicit retry.
        if (checkToken == 0 && prefetched != null) return@LaunchedEffect
        beginCheck()
    }

    val title = when (phase) {
        UpdatePhase.CHECKING -> stringResource(R.string.update_checking)
        UpdatePhase.UP_TO_DATE -> stringResource(R.string.update_up_to_date_title)
        UpdatePhase.AVAILABLE -> stringResource(R.string.update_available_title)
        UpdatePhase.DOWNLOADING -> stringResource(R.string.update_downloading_title)
        UpdatePhase.READY -> stringResource(R.string.update_ready_title)
        UpdatePhase.ERROR -> stringResource(R.string.error_title)
        UpdatePhase.NEED_PERMISSION -> stringResource(R.string.update_permission_title)
    }

    AlertDialog(
        onDismissRequest = {
            if (phase != UpdatePhase.DOWNLOADING) onDismiss()
        },
        title = { Text(title) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                when (phase) {
                    UpdatePhase.CHECKING -> {
                        Text(stringResource(R.string.update_checking_hint))
                        CircularProgressIndicator()
                    }

                    UpdatePhase.UP_TO_DATE -> {
                        Text(
                            stringResource(
                                R.string.update_current_version,
                                BuildConfig.VERSION_NAME,
                                BuildConfig.VERSION_CODE,
                            ),
                        )
                    }

                    UpdatePhase.AVAILABLE -> {
                        val data = info
                        Text(
                            stringResource(
                                R.string.update_new_version,
                                data?.versionName.orEmpty(),
                                (data?.versionCode ?: 0L).toString(),
                            ),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        val notes = data?.releaseNotes.orEmpty().trim()
                        if (notes.isNotEmpty()) {
                            Text(
                                text = notes,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .heightIn(max = 200.dp)
                                    .verticalScroll(rememberScrollState()),
                            )
                        }
                        if ((data?.sizeBytes ?: 0L) > 0L) {
                            Text(
                                stringResource(
                                    R.string.update_size,
                                    formatBytes(data!!.sizeBytes),
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    UpdatePhase.DOWNLOADING -> {
                        LinearProgressIndicator(
                            progress = { progress.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            text = "${(progress * 100).toInt()}%  $statusText",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            stringResource(R.string.update_download_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    UpdatePhase.READY -> {
                        Text(stringResource(R.string.update_ready_hint))
                    }

                    UpdatePhase.ERROR -> {
                        Text(
                            stringResource(R.string.update_error, errorText),
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.verticalScroll(rememberScrollState()),
                        )
                    }

                    UpdatePhase.NEED_PERMISSION -> {
                        Text(stringResource(R.string.update_permission_hint))
                    }
                }
            }
        },
        confirmButton = {
            when (phase) {
                UpdatePhase.AVAILABLE -> TextButton(onClick = { info?.let { startDownload(it) } }) {
                    Text(stringResource(R.string.update_download_now))
                }

                UpdatePhase.NEED_PERMISSION -> TextButton(onClick = {
                    permLauncher.launch(AppUpdater.installPermissionSettingsIntent(context))
                }) { Text(stringResource(R.string.update_grant_permission)) }

                UpdatePhase.ERROR -> TextButton(onClick = { checkToken++ }) {
                    Text(stringResource(R.string.update_retry))
                }

                else -> {}
            }
        },
        dismissButton = {
            if (phase != UpdatePhase.DOWNLOADING) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
            }
        },
    )
}
