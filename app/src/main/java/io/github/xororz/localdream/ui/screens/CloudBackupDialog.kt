package io.github.xororz.localdream.ui.screens

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import io.github.xororz.localdream.R
import io.github.xororz.localdream.cloud.CloudClient
import io.github.xororz.localdream.cloud.CloudConfig
import io.github.xororz.localdream.cloud.LogHub
import io.github.xororz.localdream.cloud.SecureTokenStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Account / cloud backup dialog. The user pastes a Personal Access Token (PAT)
 * for GitHub or Gitee; we validate it against the user API, ensure the private
 * backup repo exists, and persist only the token (Keystore-encrypted). There is
 * no OAuth client secret and no automatic token minting.
 */
@Composable
fun CloudBackupDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { SecureTokenStore(context) }

    var provider by remember { mutableStateOf(store.provider ?: CloudConfig.Provider.GITHUB) }
    var pat by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var loggedIn by remember { mutableStateOf(store.isLoggedIn) }
    val login = store.login

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.cloud_backup_title)) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.cloud_backup_desc))
                // Provider selector
                RowProvider(provider) { provider = it }
                if (loggedIn) {
                    Text(stringResource(R.string.cloud_backup_logged_in, provider.name, login ?: "?"))
                    Text(stringResource(R.string.cloud_backup_repo, CloudConfig.BACKUP_REPO))
                } else {
                    OutlinedTextField(
                        value = pat,
                        onValueChange = { pat = it },
                        label = { Text(stringResource(R.string.cloud_backup_pat_hint)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    status?.let { Text(it) }
                    OutlinedButton(
                        enabled = !busy && pat.isNotBlank(),
                        onClick = {
                            busy = true
                            status = null
                            scope.launch {
                                val res = runCatching {
                                    withContext(Dispatchers.IO) {
                                        val user = CloudClient.validatePAT(provider, pat.trim())
                                        CloudClient.ensurePrivateRepo(provider, pat.trim(), user.login)
                                        store.saveToken(provider, pat.trim(), user.login, user.displayName)
                                        LogHub.log(LogHub.Category.UPLOAD, "cloud login ok as ${user.login} on ${provider.name}")
                                        user
                                    }
                                }
                                busy = false
                                res.onSuccess {
                                    loggedIn = true
                                    status = null
                                    Toast.makeText(context, R.string.oauth_login_ok, Toast.LENGTH_LONG).show()
                                }.onFailure { e ->
                                    // Do NOT save a bad token; never log the token itself.
                                    status = context.getString(R.string.cloud_backup_login_failed, e.message ?: "?")
                                    LogHub.log(LogHub.Category.UPLOAD, "cloud login failed: ${e.message}")
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.cloud_backup_save_validate)) }
                }
                Text(stringResource(R.string.cloud_backup_howto, provider.name))
            }
        },
        confirmButton = {
            if (loggedIn) {
                TextButton(onClick = {
                    store.logout()
                    loggedIn = false
                    pat = ""
                    LogHub.log(LogHub.Category.UPLOAD, "cloud logout")
                }) { Text(stringResource(R.string.cloud_backup_logout)) }
            } else {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.got_it)) }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
        },
    )
}

@Composable
private fun RowProvider(selected: CloudConfig.Provider, onChange: (CloudConfig.Provider) -> Unit) {
    androidx.compose.foundation.layout.Row(
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
    ) {
        CloudConfig.Provider.entries.forEach { p ->
            androidx.compose.material3.FilterChip(
                selected = selected == p,
                onClick = { onChange(p) },
                label = { Text(p.name) },
            )
        }
    }
}
