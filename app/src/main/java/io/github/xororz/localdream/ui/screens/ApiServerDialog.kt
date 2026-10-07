package io.github.xororz.localdream.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.xororz.localdream.R
import io.github.xororz.localdream.cloud.ApiKeyStore
import io.github.xororz.localdream.data.Model
import io.github.xororz.localdream.data.ModelRepository
import io.github.xororz.localdream.service.LanImageService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * et.37 (Bug9): settings dialog to start/stop the LAN local image HTTP API, pick the
 * model it serves, and show the copy-able address / key / model triple.
 */
@Composable
fun ApiServerDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val running by LanImageService.isRunning.collectAsState()
    val port by LanImageService.port.collectAsState()
    val servedModel by LanImageService.model.collectAsState()

    var models by remember { mutableStateOf<List<Model>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var selected by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        loading = true
        val list = withContext(Dispatchers.IO) {
            val repo = ModelRepository.getInstance(context)
            // et.43: force a FRESH disk scan every time the dialog opens, not just
            // ensureLoaded(). The app may have first scanned before "All files access"
            // was granted, caching isDownloaded=false for models that are in fact present
            // in the public models dir; ensureLoaded() short-circuits on that stale cache.
            runCatching { repo.refreshAllModels() }
            repo.models
                .filter { it.id != "upscaler_anime" && it.id != "upscaler_realistic" }
                .sortedWith(compareByDescending<Model> { it.isDownloaded }.thenBy { it.name.lowercase() })
        }
        models = list
        selected = list.firstOrNull { it.isDownloaded }?.id
        loading = false
    }

    // et.40: reactive so "reset key" updates the displayed value immediately (the
    // running server already reads the key live, no restart needed).
    var keyDisplay by remember { mutableStateOf(ApiKeyStore(context).current() ?: ApiKeyStore(context).getOrCreate()) }
    val lanIp = remember { LanImageService.localLanIp() }
    val onWifi = remember { isOnWifi(context) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
        title = { Text(stringResource(R.string.api_server_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                // Status
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(
                            if (running) R.string.api_server_running else R.string.api_server_stopped,
                        ),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }

                if (!running) {
                    if (!onWifi) {
                        Text(
                            stringResource(R.string.api_server_no_wifi),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                    }
                    Text(
                        stringResource(R.string.api_server_choose_model),
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                    if (loading) {
                        CircularProgressIndicator()
                    } else {
                        Column(Modifier.fillMaxWidth()) {
                            models.forEach { m ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    RadioButton(
                                        selected = selected == m.id,
                                        enabled = m.isDownloaded,
                                        onClick = { if (m.isDownloaded) selected = m.id },
                                    )
                                    Column(Modifier.weight(1f)) {
                                        Text(m.name)
                                        if (!m.isDownloaded) {
                                            Text(
                                                stringResource(R.string.api_server_model_not_ready),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.error,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    Button(
                        onClick = {
                            val mid = selected ?: return@Button
                            LanImageService.start(context, mid)
                        },
                        enabled = onWifi && selected != null,
                        modifier = Modifier.padding(top = 12.dp),
                    ) { Text(stringResource(R.string.api_server_start)) }
                } else {
                    // Running: three copy-able read-only rows.
                    CopyRow(
                        label = stringResource(R.string.api_server_addr_label),
                        value = "http://$lanIp:$port",
                        context = context,
                    )
                    CopyRow(
                        label = stringResource(R.string.api_server_key_label),
                        value = keyDisplay,
                        context = context,
                    )
                    CopyRow(
                        label = stringResource(R.string.api_server_model_label),
                        value = servedModel ?: "",
                        context = context,
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    Text(
                        stringResource(R.string.api_server_cleartext_note),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        stringResource(R.string.api_server_background_note),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    Row(Modifier.padding(top = 12.dp)) {
                        Button(onClick = { LanImageService.stop(context) }) {
                            Text(stringResource(R.string.api_server_stop_btn))
                        }
                        OutlinedButton(
                            onClick = {
                                ApiKeyStore(context).resetKey()
                                keyDisplay = ApiKeyStore(context).current().orEmpty()
                                Toast.makeText(context, R.string.api_server_copied, Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.padding(start = 8.dp),
                        ) { Text(stringResource(R.string.api_server_reset_key)) }
                    }
                }
            }
        },
    )
}

@Composable
private fun CopyRow(label: String, value: String, context: Context) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall)
        OutlinedButton(
            onClick = {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText(label, value))
                Toast.makeText(context, R.string.api_server_copied, Toast.LENGTH_SHORT).show()
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(value, style = MaterialTheme.typography.bodySmall) }
    }
}

private fun isOnWifi(context: Context): Boolean = runCatching {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    @Suppress("DEPRECATION")
    val info = cm.activeNetworkInfo ?: return false
    @Suppress("DEPRECATION")
    info.type == ConnectivityManager.TYPE_WIFI && info.isConnected
}.getOrDefault(false)
