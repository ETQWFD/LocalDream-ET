package io.github.xororz.localdream.ui.screens

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import io.github.xororz.localdream.R
import io.github.xororz.localdream.cloud.CloudConfig
import io.github.xororz.localdream.cloud.CloudClient
import io.github.xororz.localdream.cloud.LogHub
import io.github.xororz.localdream.cloud.SecureTokenStore
import io.github.xororz.localdream.utils.Storage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * On-device log viewer. Combines the in-process LogHub ring buffer with the
 * persisted engine_*.log / convert_*.log files. There is intentionally NO clear
 * button: logs are never deleted by the user-facing UI.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var filter by remember { mutableStateOf<LogHub.Category?>(null) }
    val scope = CoroutineScope(Dispatchers.IO)

    val text = remember(filter) {
        buildString {
            appendLine("==== LocalDream ET Logs ====")
            appendLine("generated: ${Date()}")
            appendLine()
            appendLine("---- In-app events ----")
            append(LogHub.formatted(filter))
            appendLine()
            appendEngineAndConvertFiles(context, filter)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.logs_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = null) }
                },
                actions = {
                    IconButton(onClick = { copyAndShare(context, clipboard, text) }) {
                        Icon(Icons.Default.Share, contentDescription = stringResource(R.string.logs_share))
                    }
                },
            )
        },
    ) { pad ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(pad)
                .padding(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = filter == null,
                    onClick = { filter = null },
                    label = { Text(stringResource(R.string.logs_filter_all)) },
                )
                LogHub.Category.entries.forEach { c ->
                    FilterChip(
                        selected = filter == c,
                        onClick = { filter = c },
                        label = { Text(c.name.lowercase()) },
                    )
                }
            }
            OutlinedButton(
                onClick = { uploadLogs(context, scope) },
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            ) { Text(stringResource(R.string.logs_upload_cloud)) }
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
            )
        }
    }
}

private fun copyAndShare(context: Context, clipboard: ClipboardManager, text: String) {
    clipboard.setText(AnnotatedString(text))
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(Intent.createChooser(send, context.getString(R.string.logs_share))) }
    Toast.makeText(context, R.string.logs_copied, Toast.LENGTH_SHORT).show()
}

private fun uploadLogs(context: Context, scope: CoroutineScope) {
    val store = SecureTokenStore(context)
    val provider = store.provider
    if (!store.isLoggedIn || provider == null) {
        Toast.makeText(context, R.string.logs_upload_need_login, Toast.LENGTH_LONG).show()
        return
    }
    val token = store.accessToken() ?: run {
        Toast.makeText(context, R.string.logs_upload_need_login, Toast.LENGTH_LONG).show(); return
    }
    Toast.makeText(context, R.string.logs_uploading, Toast.LENGTH_SHORT).show()
    scope.launch {
        val res = runCatching {
            val user = CloudClient.fetchUser(provider, token)
            CloudClient.ensurePrivateRepo(provider, token, user.login)
            val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            CloudClient.uploadContent(
                provider, token, user.login, CloudConfig.BACKUP_REPO,
                "log/logs_$ts.txt",
                LogHub.formatted(null).toByteArray(),
                "logs $ts",
            )
        }
        launch(Dispatchers.Main) {
            res.onSuccess { path ->
                Toast.makeText(context, context.getString(R.string.logs_upload_ok, path), Toast.LENGTH_LONG).show()
            }.onFailure { e ->
                Toast.makeText(context, context.getString(R.string.logs_upload_failed, e.message ?: "?"), Toast.LENGTH_LONG).show()
            }
        }
    }
}

private fun StringBuilder.appendEngineAndConvertFiles(context: Context, filter: LogHub.Category?) {
    if (filter != null && filter != LogHub.Category.ENGINE) {
        // still append engine files only when not filtered them out entirely
    }
    runCatching {
        appendLine("---- Engine files (filesDir engine_*.log) ----")
        val logs = File(context.filesDir, ".")
            .listFiles { f -> f.name.startsWith("engine_") && f.name.endsWith(".log") }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()
        if (logs.isEmpty()) appendLine("(none)")
        logs.take(3).forEach { f ->
            appendLine("== ${f.name} ==")
            f.readLines().takeLast(80).forEach { appendLine("  $it") }
        }
    }
    runCatching {
        appendLine()
        appendLine("---- Convert files (temp convert_*.log) ----")
        val logs = Storage.tempDir(context)
            .listFiles { f -> f.name.startsWith("convert_") && f.name.endsWith(".log") }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()
        if (logs.isEmpty()) appendLine("(none)")
        logs.take(3).forEach { f ->
            appendLine("== ${f.name} ==")
            f.readLines().takeLast(60).forEach { appendLine("  $it") }
        }
    }
}
