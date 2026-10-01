package io.github.xororz.localdream.ui.screens

import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.xororz.localdream.R
import io.github.xororz.localdream.data.BackupExportResult
import io.github.xororz.localdream.data.BackupFormatException
import io.github.xororz.localdream.data.BackupImportResult
import io.github.xororz.localdream.data.BackupStats
import io.github.xororz.localdream.data.HistoryBackup
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private sealed interface BackupPhase {
    data object Idle : BackupPhase
    data class Running(val isImport: Boolean, val done: Int, val total: Int) : BackupPhase
    data class ExportDone(val result: BackupExportResult) : BackupPhase
    data class ImportDone(val result: BackupImportResult) : BackupPhase
    data class Error(val message: String) : BackupPhase
}

// Settings entry for exporting the generation history (images plus
// parameters) to a user-picked zip and merging such backups back in.
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun DataBackupDialog(installedModelIds: Set<String>, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var stats by remember { mutableStateOf<BackupStats?>(null) }
    var favoritesOnly by remember { mutableStateOf(false) }
    var phase by remember { mutableStateOf<BackupPhase>(BackupPhase.Idle) }
    var job by remember { mutableStateOf<Job?>(null) }

    val msgInvalidFile = stringResource(R.string.backup_invalid_file)
    val msgUnknownError = stringResource(R.string.unknown_error)

    LaunchedEffect(Unit) {
        stats = HistoryBackup.stats(context)
    }

    fun failureMessage(e: Exception): String = when (e) {
        is BackupFormatException -> msgInvalidFile
        else -> e.message ?: msgUnknownError
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        job = scope.launch {
            phase = BackupPhase.Running(isImport = false, done = 0, total = 0)
            try {
                val result = HistoryBackup.export(context, uri, favoritesOnly) { done, total ->
                    phase = BackupPhase.Running(isImport = false, done = done, total = total)
                }
                phase = BackupPhase.ExportDone(result)
            } catch (e: CancellationException) {
                // Don't leave a half-written backup behind.
                withContext(NonCancellable + Dispatchers.IO) {
                    runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
                }
                phase = BackupPhase.Idle
                throw e
            } catch (e: Exception) {
                withContext(Dispatchers.IO) {
                    runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
                }
                phase = BackupPhase.Error(failureMessage(e))
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        job = scope.launch {
            phase = BackupPhase.Running(isImport = true, done = 0, total = 0)
            try {
                val result = HistoryBackup.import(context, uri, installedModelIds) { done, total ->
                    phase = BackupPhase.Running(isImport = true, done = done, total = total)
                }
                phase = BackupPhase.ImportDone(result)
                stats = HistoryBackup.stats(context)
            } catch (e: CancellationException) {
                // Finished items are kept; a re-import skips them as duplicates.
                phase = BackupPhase.Idle
                stats = runCatching { HistoryBackup.stats(context) }.getOrNull()
                throw e
            } catch (e: Exception) {
                phase = BackupPhase.Error(failureMessage(e))
                stats = runCatching { HistoryBackup.stats(context) }.getOrNull()
            }
        }
    }

    val running = phase as? BackupPhase.Running

    AlertDialog(
        onDismissRequest = { if (running == null) onDismiss() },
        title = { Text(stringResource(R.string.backup_restore)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.backup_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                val currentStats = stats
                Column {
                    Text(
                        text = if (currentStats != null) {
                            stringResource(
                                R.string.backup_stats_all,
                                currentStats.totalCount,
                                formatBytes(currentStats.totalBytes),
                            )
                        } else {
                            stringResource(R.string.backup_preparing)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (currentStats != null) {
                        Text(
                            text = stringResource(
                                R.string.backup_stats_favorites,
                                currentStats.favoriteCount,
                                formatBytes(currentStats.favoriteBytes),
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(
                        ButtonGroupDefaults.ConnectedSpaceBetween,
                    ),
                ) {
                    ToggleButton(
                        checked = !favoritesOnly,
                        onCheckedChange = { checked -> if (checked) favoritesOnly = false },
                        enabled = running == null,
                        shapes = ButtonGroupDefaults.connectedLeadingButtonShapes(),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.backup_scope_all))
                    }
                    ToggleButton(
                        checked = favoritesOnly,
                        onCheckedChange = { checked -> if (checked) favoritesOnly = true },
                        enabled = running == null,
                        shapes = ButtonGroupDefaults.connectedTrailingButtonShapes(),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.backup_scope_favorites))
                    }
                }

                when (val p = phase) {
                    is BackupPhase.Running -> {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (p.total > 0) {
                                LinearProgressIndicator(
                                    progress = { p.done.toFloat() / p.total },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            } else {
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            }
                            Text(
                                text = stringResource(
                                    if (p.isImport) R.string.backup_importing else R.string.backup_exporting,
                                    p.done,
                                    p.total,
                                ),
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontFeatureSettings = "tnum",
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    is BackupPhase.ExportDone -> {
                        Text(
                            text = stringResource(R.string.backup_export_done, p.result.exported),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }

                    is BackupPhase.ImportDone -> {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = stringResource(
                                    R.string.backup_import_done,
                                    p.result.imported,
                                    p.result.duplicates,
                                    p.result.failed,
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            if (p.result.orphanModelIds.isNotEmpty()) {
                                Text(
                                    text = stringResource(
                                        R.string.backup_import_orphan_note,
                                        p.result.orphanModelIds.size,
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }

                    is BackupPhase.Error -> {
                        Text(
                            text = stringResource(R.string.backup_failed, p.message),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }

                    BackupPhase.Idle -> Unit
                }

                if (running == null) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilledTonalButton(
                            onClick = {
                                val name = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                                    .format(Date())
                                exportLauncher.launch("LocalDream_history_$name.zip")
                            },
                            enabled = currentStats != null &&
                                (if (favoritesOnly) currentStats.favoriteCount else currentStats.totalCount) > 0,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(stringResource(R.string.backup_export))
                        }
                        OutlinedButton(
                            onClick = { importLauncher.launch("application/zip") },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(stringResource(R.string.backup_import))
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (running != null) {
                TextButton(onClick = { job?.cancel() }) {
                    Text(stringResource(R.string.cancel))
                }
            } else {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.close))
                }
            }
        },
    )
}
