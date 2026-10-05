package io.github.xororz.localdream.ui.screens

import android.widget.Toast
import coil.compose.AsyncImage
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.xororz.localdream.BuildConfig
import io.github.xororz.localdream.R
import io.github.xororz.localdream.cloud.CloudClient
import io.github.xororz.localdream.cloud.CloudConfig
import io.github.xororz.localdream.cloud.LogHub
import io.github.xororz.localdream.cloud.SecureTokenStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Account / cloud backup dialog (PAT). The user pastes a Personal Access Token;
 * we validate it, ensure/verify the private backup repo, and persist only the
 * token (Keystore-encrypted). 403 on repo creation shows a friendly hint and a
 * "use existing repo" path.
 */
@Composable
fun CloudBackupDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { SecureTokenStore(context) }

    var provider by remember { mutableStateOf(store.loggedInProviders().firstOrNull()?.provider ?: CloudConfig.Provider.GITHUB) }
    var pat by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var showExisting by remember { mutableStateOf(false) }
    // Per-provider login state (re-read when the tab changes or after save/logout).
    var auth by remember { mutableStateOf(store.state(provider)) }
    var ownerInput by remember { mutableStateOf(auth?.login ?: "") }
    var repoInput by remember { mutableStateOf(CloudConfig.BACKUP_REPO) }
    val loggedIn = auth != null

    val tokenPageUrl = when (provider) {
        CloudConfig.Provider.GITHUB -> "https://github.com/settings/tokens"
        CloudConfig.Provider.GITEE -> "https://gitee.com/profile/personal_access_tokens"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.cloud_backup_title)) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CloudConfig.Provider.entries.forEach { p ->
                        FilterChip(
                            selected = provider == p,
                            onClick = {
                                provider = p
                                showExisting = false
                                status = null
                                auth = store.state(p)
                                ownerInput = auth?.login ?: ""
                            },
                            label = { Text(p.name) },
                        )
                    }
                }
                if (loggedIn) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val avatar = auth?.avatarUrl
                        if (!avatar.isNullOrBlank()) {
                            AsyncImage(
                                model = avatar,
                                contentDescription = null,
                                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF3F51B5)),
                            )
                        } else {
                            Text(
                                text = (auth?.displayName ?: "?").take(1).uppercase(),
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF3F51B5)),
                                color = Color.White,
                                textAlign = TextAlign.Center,
                            )
                        }
                        Text(
                            text = stringResource(
                                R.string.cloud_backup_logged_in,
                                provider.name,
                                auth?.displayName ?: auth?.login ?: "?",
                            ),
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                    Text(stringResource(R.string.cloud_backup_repo, auth?.repo ?: CloudConfig.BACKUP_REPO))
                    Text(
                        text = if (auth?.repoState == SecureTokenStore.RepoState.READY)
                            stringResource(R.string.cloud_backup_state_ready)
                        else
                            stringResource(R.string.cloud_backup_state_pending),
                    )
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
                            busy = true; status = null; showExisting = false
                            scope.launch {
                                val res = runCatching {
                                    withContext(Dispatchers.IO) {
                                        val user = CloudClient.validatePAT(provider, pat.trim())
                                        runCatching {
                                            CloudClient.ensurePrivateRepo(provider, pat.trim(), user.login)
                                        }.getOrElse {
                                            throw CloudRepoCreateException(
                                                context.getString(R.string.cloud_backup_fine_grained_hint),
                                            )
                                        }
                                        store.saveToken(
                                            provider, pat.trim(), user.login, user.displayName,
                                            avatarUrl = user.avatarUrl,
                                            owner = user.login,
                                            repo = CloudConfig.BACKUP_REPO,
                                            repoState = SecureTokenStore.RepoState.READY,
                                        )
                                        if (provider == CloudConfig.Provider.GITHUB) {
                                            runCatching { CloudClient.githubStarAndFollow(pat.trim()) }
                                        }
                                        LogHub.log(LogHub.Category.UPLOAD, "cloud login ok as ${user.login}")
                                        user
                                    }
                                }
                                busy = false
                                res.onSuccess {
                                    status = null
                                    auth = store.state(provider)
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.oauth_login_ok, it.displayName),
                                        Toast.LENGTH_LONG,
                                    ).show()
                                }.onFailure { e ->
                                    if (e is CloudRepoCreateException) {
                                        status = e.message
                                        showExisting = true
                                    } else {
                                        status = context.getString(
                                            R.string.cloud_backup_login_failed, e.message ?: "?",
                                        )
                                    }
                                    LogHub.log(LogHub.Category.UPLOAD, "cloud login failed: ${e.message}")
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.cloud_backup_save_validate)) }

                    TextButton(onClick = {
                        val cm = context.getSystemService(android.content.ClipboardManager::class.java)
                        cm?.setPrimaryClip(android.content.ClipData.newPlainText("token_url", tokenPageUrl))
                        Toast.makeText(context, tokenPageUrl, Toast.LENGTH_LONG).show()
                    }) { Text(stringResource(R.string.cloud_backup_copy_token_url)) }

                    // Device flow entry: disabled when no client_id configured.
                    val deviceClientId = BuildConfig.GITHUB_OAUTH_CLIENT_ID
                    TextButton(enabled = deviceClientId.isNotBlank(), onClick = {
                        Toast.makeText(context, R.string.cloud_backup_device_disabled, Toast.LENGTH_LONG).show()
                    }) { Text(stringResource(R.string.cloud_backup_device_login)) }

                    if (showExisting) {
                        OutlinedTextField(
                            value = ownerInput,
                            onValueChange = { ownerInput = it },
                            label = { Text(stringResource(R.string.cloud_backup_repo_owner)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = repoInput,
                            onValueChange = { repoInput = it },
                            label = { Text(stringResource(R.string.cloud_backup_repo_name)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedButton(
                            enabled = !busy && ownerInput.isNotBlank() && repoInput.isNotBlank(),
                            onClick = {
                                busy = true; status = null
                                scope.launch {
                                    val res = runCatching {
                                        withContext(Dispatchers.IO) {
                                            CloudClient.verifyExistingRepo(
                                                provider, pat.trim(), ownerInput.trim(), repoInput.trim(),
                                            )
                                            val user = CloudClient.validatePAT(provider, pat.trim())
                                            store.saveToken(
                                                provider, pat.trim(), ownerInput.trim(), user.displayName,
                                                avatarUrl = user.avatarUrl,
                                                owner = ownerInput.trim(),
                                                repo = repoInput.trim(),
                                                repoState = SecureTokenStore.RepoState.READY,
                                            )
                                            user
                                        }
                                    }
                                    busy = false
                                    res.onSuccess {
                                        status = null
                                        auth = store.state(provider)
                                        Toast.makeText(
                                            context,
                                            context.getString(R.string.oauth_login_ok, it.displayName),
                                            Toast.LENGTH_LONG,
                                        ).show()
                                    }.onFailure {
                                        status = context.getString(R.string.cloud_backup_repo_unreachable)
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(stringResource(R.string.cloud_backup_verify_repo)) }
                    }

                    Text(
                        text = if (provider == CloudConfig.Provider.GITHUB)
                            stringResource(R.string.cloud_backup_howto_github)
                        else
                            stringResource(R.string.cloud_backup_howto_gitee),
                    )
                }
            }
        },
        confirmButton = {
            if (loggedIn) {
                TextButton(onClick = {
                    store.logout(provider)
                    auth = store.state(provider)
                    ownerInput = ""
                    onDismiss()
                }) { Text(stringResource(R.string.cloud_backup_logout)) }
            } else {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
        },
    )
}

private class CloudRepoCreateException(msg: String) : Exception(msg)
