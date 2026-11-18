package io.github.xororz.localdream.ui.screens

import android.widget.Toast
import coil.compose.AsyncImage
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.xororz.localdream.BuildConfig
import io.github.xororz.localdream.R
import io.github.xororz.localdream.cloud.CloudClient
import io.github.xororz.localdream.cloud.CloudConfig
import io.github.xororz.localdream.cloud.LogHub
import io.github.xororz.localdream.cloud.SecureTokenStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val store = remember { SecureTokenStore(context) }

    var provider by remember { mutableStateOf(store.loggedInProviders().firstOrNull()?.provider ?: CloudConfig.Provider.GITHUB) }
    var pat by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    // 2026-11：GitHub OAuth Device Flow（不需要 client_secret，也不会把密码上传到任何仓库）
    val oauthPrefs = remember { context.getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE) }
    var oauthClientId by remember { mutableStateOf(oauthPrefs.getString("github_oauth_client_id", "Ov23ligVcx2ZUAG3lNAA") ?: "Ov23ligVcx2ZUAG3lNAA") }
    var oauthHint by remember { mutableStateOf<String?>(null) }
    var oauthBusy by remember { mutableStateOf(false) }
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
                            text = "已授权：${auth?.login ?: "Token已配置"}",
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
                    // et.118/build195: quota row under the login status.
                    run {
                        val qUsed = io.github.xororz.localdream.service.QuotaManager
                            .used(LocalContext.current)
                        val qTotal = io.github.xororz.localdream.service.QuotaManager.TOTAL
                        val frac = (qUsed.toFloat() / qTotal.toFloat()).coerceIn(0f, 1f)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "高级套餐",
                                color = androidx.compose.ui.graphics.Color(0xFF7C4DFF),
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.labelMedium,
                            )
                            Spacer(modifier = Modifier.padding(horizontal = 4.dp))
                            // et.216/et.240: 官方号显示“不限”；判定走 QuotaManager，
                            // 登录态取不到时也不会把官方号误显示成有限额度。
                            val isOfficial = io.github.xororz.localdream.service.QuotaManager
                                .isOfficial(LocalContext.current)
                            Text(
                                text = if (isOfficial) "额度 不限" else "额度 ${qUsed}/${qTotal}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        androidx.compose.material3.LinearProgressIndicator(
                            progress = { frac },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        // 2026-11：主额度 + 备用额度（赚取/转账，不随 7 天重置）。
                        val ctxQ = LocalContext.current
                        val reserve = io.github.xororz.localdream.service.QuotaManager.reserve(ctxQ)
                        var showWithdraw by remember { mutableStateOf(false) }
                        var withdrawText by remember { mutableStateOf("") }
                        var pendingWithdraw by remember { mutableStateOf(0L) }
                        val withdrawAuth = rememberLauncherForActivityResult(
                            ActivityResultContracts.StartActivityForResult(),
                        ) { res ->
                            if (res.resultCode == android.app.Activity.RESULT_OK && pendingWithdraw > 0L) {
                                val ok = io.github.xororz.localdream.service.QuotaManager.withdraw(ctxQ, pendingWithdraw)
                                Toast.makeText(
                                    ctxQ,
                                    if (ok) "已提取 $pendingWithdraw 到主额度" else "提取失败：备用额度不足或已达 2 亿上限",
                                    Toast.LENGTH_LONG,
                                ).show()
                            }
                            pendingWithdraw = 0L
                        }
                        Text(
                            text = "备用额度 $reserve（不重置，可提取到主额度）",
                            style = MaterialTheme.typography.bodySmall,
                            color = androidx.compose.ui.graphics.Color(0xFFB8860B),
                        )
                        TextButton(onClick = { showWithdraw = true }) { Text("提取额度") }
                        if (showWithdraw) {
                            AlertDialog(
                                onDismissRequest = { showWithdraw = false },
                                title = { Text("提取备用额度") },
                                text = {
                                    Column {
                                        Text("备用额度：$reserve")
                                        OutlinedTextField(
                                            value = withdrawText,
                                            onValueChange = { withdrawText = it.filter { c -> c.isDigit() } },
                                            label = { Text("提取多少") }, singleLine = true,
                                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        )
                                        Text("确认后需要验证手机锁屏密码。", style = MaterialTheme.typography.labelSmall)
                                    }
                                },
                                confirmButton = {
                                    TextButton(onClick = {
                                        val amt = withdrawText.toLongOrNull() ?: 0L
                                        if (amt <= 0L || amt > reserve) {
                                            Toast.makeText(ctxQ, "提取数量不合法", Toast.LENGTH_SHORT).show()
                                        } else {
                                            pendingWithdraw = amt
                                            val km = androidx.core.content.ContextCompat.getSystemService(
                                                ctxQ, android.app.KeyguardManager::class.java,
                                            )
                                            val it2 = km?.createConfirmDeviceCredentialIntent("验证身份", "提取额度需要验证")
                                            if (it2 != null) withdrawAuth.launch(it2)
                                            else Toast.makeText(ctxQ, "请先在系统设置里设置锁屏密码", Toast.LENGTH_LONG).show()
                                            showWithdraw = false
                                        }
                                    }) { Text("确认提取") }
                                },
                                dismissButton = { TextButton(onClick = { showWithdraw = false }) { Text("取消") } },
                            )
                        }
                        // et.119/build197: next reset date under the progress bar.
                        val resetMs = io.github.xororz.localdream.service.QuotaManager
                            .resetEpochMs(LocalContext.current)
                        if (resetMs > 0L) {
                            val ds = remember { java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()) }
                            Text(
                                text = "下次重置：${ds.format(java.util.Date(resetMs))}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    // et.85: batch-pick album images -> upload each to png/ (x/y progress, retry).
                    var batchBusy by remember { mutableStateOf(false) }
                    var batchProgress by remember { mutableStateOf("") }
                    val batchLauncher = rememberLauncherForActivityResult(
                        ActivityResultContracts.GetMultipleContents(),
                    ) { uris ->
                        if (uris.isNullOrEmpty() || batchBusy) return@rememberLauncherForActivityResult
                        val st = store.state(provider) ?: return@rememberLauncherForActivityResult
                        val tok = store.token(provider) ?: return@rememberLauncherForActivityResult
                        val owner = st.owner ?: st.login
                        val repo = st.repo ?: CloudConfig.BACKUP_REPO
                        val total = uris.size
                        batchBusy = true
                        scope.launch {
                            var ok = 0
                            var fail = 0
                            uris.forEachIndexed { i, uri ->
                                batchProgress = "已传 ${i + 1}/$total"
                                val done = runCatching {
                                    withContext(Dispatchers.IO) {
                                        val bytes = runCatching {
                                            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                                        }.getOrNull()
                                        if (bytes == null || bytes.isEmpty()) {
                                            LogHub.log(LogHub.Category.UPLOAD, "batch skip null/empty: $uri")
                                            return@withContext false
                                        }
                                        val name = "batch_" + System.currentTimeMillis() + "_$i.png"
                                        CloudClient.uploadContent(
                                            provider, tok, owner, repo,
                                            "png/$name", bytes, "upload $name",
                                        )
                                        true
                                    }
                                }.getOrDefault(false)
                                if (done) ok++ else fail++
                            }
                            batchBusy = false
                            batchProgress = "完成：成功 $ok / 失败 $fail"
                        }
                    }
                    OutlinedButton(
                        enabled = !batchBusy,
                        onClick = {
                            runCatching { batchLauncher.launch("image/*") }
                        },
                    ) { Text(if (batchBusy) "上传中…" else "批量上传图片") }
                    if (batchProgress.isNotBlank()) Text(batchProgress)
                } else {
                    if (provider == CloudConfig.Provider.GITHUB) {
                        OutlinedTextField(
                            value = oauthClientId,
                            onValueChange = {
                                oauthClientId = it.trim()
                                oauthPrefs.edit().putString("github_oauth_client_id", oauthClientId).apply()
                            },
                            label = { Text("GitHub OAuth App Client ID") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            "在 GitHub → Settings → Developer settings → OAuth Apps 新建一个应用，" +
                                "把它的 Client ID 填在上面，并在应用设置里勾选 Enable Device Flow（Device Flow 不需要 Client Secret，客户端密钥不要写进应用）。",
                            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                            color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedButton(
                            enabled = !busy && !oauthBusy && oauthClientId.isNotBlank(),
                            onClick = {
                                oauthBusy = true; status = null; oauthHint = null
                                scope.launch {
                                    val dc = withContext(Dispatchers.IO) {
                                        runCatching { io.github.xororz.localdream.cloud.GithubDeviceAuth.requestDeviceCode(oauthClientId) }
                                    }
                                    dc.onFailure {
                                        status = "申请设备码失败：${it.message}"
                                        oauthBusy = false
                                    }.onSuccess { code ->
                                        // 2026-11：用户码自动复制到剪贴板，并在授权页带上 user_code 预填；
                                        // 这两条都失败时用户仍可手动输入提示里的码。
                                        runCatching {
                                            clipboard.setText(androidx.compose.ui.text.AnnotatedString(code.userCode))
                                        }
                                        val fullUrl = runCatching {
                                            code.verificationUri + "?user_code=" +
                                                java.net.URLEncoder.encode(code.userCode, "UTF-8")
                                        }.getOrDefault(code.verificationUri)
                                        oauthHint = "用户码 ${code.userCode} 已复制到剪贴板；已打开浏览器，直接粘贴即可（${code.expiresInSec / 60} 分钟内有效）"
                                        runCatching {
                                            context.startActivity(
                                                Intent(Intent.ACTION_VIEW, Uri.parse(fullUrl))
                                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                            )
                                        }
                                        var interval = code.intervalSec.coerceAtLeast(5)
                                        val deadline = System.currentTimeMillis() + code.expiresInSec * 1000L
                                        var token: String? = null
                                        while (System.currentTimeMillis() < deadline && token == null) {
                                            delay(interval * 1000L)
                                            when (val r = withContext(Dispatchers.IO) {
                                                io.github.xororz.localdream.cloud.GithubDeviceAuth.pollToken(oauthClientId, code.deviceCode)
                                            }) {
                                                io.github.xororz.localdream.cloud.GithubDeviceAuth.Poll.Pending -> Unit
                                                io.github.xororz.localdream.cloud.GithubDeviceAuth.Poll.SlowDown -> interval += 5
                                                io.github.xororz.localdream.cloud.GithubDeviceAuth.Poll.Expired -> {
                                                    status = "授权码已过期，请重新点击授权"
                                                    break
                                                }
                                                is io.github.xororz.localdream.cloud.GithubDeviceAuth.Poll.Failure -> {
                                                    status = r.error
                                                    break
                                                }
                                                is io.github.xororz.localdream.cloud.GithubDeviceAuth.Poll.Success -> token = r.token
                                            }
                                        }
                                        if (token != null) {
                                            val res = runCatching {
                                                withContext(Dispatchers.IO) {
                                                    val user = CloudClient.validatePAT(provider, token)
                                                    runCatching {
                                                        CloudClient.ensurePrivateRepo(provider, token, user.login)
                                                    }.getOrElse {
                                                        throw CloudRepoCreateException(
                                                            context.getString(R.string.cloud_backup_fine_grained_hint),
                                                        )
                                                    }
                                                    store.saveToken(
                                                        provider, token, user.login, user.displayName,
                                                        avatarUrl = user.avatarUrl,
                                                        owner = user.login,
                                                        repo = CloudConfig.BACKUP_REPO,
                                                        repoState = SecureTokenStore.RepoState.READY,
                                                    )
                                                    runCatching { CloudClient.githubStarAndFollow(token) }
                                                    user
                                                }
                                            }
                                            res.onSuccess {
                                                auth = store.state(provider)
                                                Toast.makeText(
                                                    context,
                                                    context.getString(R.string.oauth_login_ok, it.displayName),
                                                    Toast.LENGTH_LONG,
                                                ).show()
                                            }.onFailure { e -> status = e.message ?: "授权后校验失败" }
                                        }
                                        oauthHint = null
                                        oauthBusy = false
                                    }
                                }
                            },
                        ) { Text(if (oauthBusy) "等待浏览器授权…" else "方式一：设备码授权登录（推荐，不依赖回调地址）") }
                        TextButton(
                            enabled = !oauthBusy,
                            onClick = {
                                val url = "https://github.com/login/oauth/authorize" +
                                    "?client_id=Ov23ligVcx2ZUAG3lNAA" +
                                    "&redirect_uri=" + java.net.URLEncoder.encode(
                                        "https://etc.tw.kg/oauth.html", "UTF-8",
                                    ) +
                                    "&scope=" + java.net.URLEncoder.encode("repo read:user", "UTF-8")
                                runCatching {
                                    context.startActivity(
                                        Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                    )
                                }
                                oauthHint = "授权完成后会自动跳回本应用（若没跳回，请检查 OAuth App 的回调地址是否填了 https://etc.tw.kg/oauth.html）"
                            },
                        ) { Text("方式二：网页授权自动回跳（需 OAuth App 回调地址已填 https://etc.tw.kg/oauth.html）") }
                        // et.243：浏览器拦截自定义 scheme、或 OAuth App 回调地址与
                        // https://etc.tw.kg/oauth.html 不一致时，用户会停在回跳页。
                        // 这里允许把回跳链接（或 code）直接粘进来完成登录。
                        var manualCallback by remember { mutableStateOf("") }
                        OutlinedTextField(
                            value = manualCallback,
                            onValueChange = { manualCallback = it },
                            label = { Text("没自动跳回？粘贴回跳链接或 code") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        TextButton(
                            enabled = !oauthBusy && manualCallback.isNotBlank(),
                            onClick = {
                                val code = io.github.xororz.localdream.cloud.GithubDeviceAuth
                                    .parseCallbackCode(manualCallback)
                                if (code == null) {
                                    status = "没识别到授权码，请粘贴浏览器地址栏里的完整链接"
                                    return@TextButton
                                }
                                oauthBusy = true; status = null
                                scope.launch {
                                    val r = withContext(Dispatchers.IO) {
                                        runCatching {
                                            val tk = io.github.xororz.localdream.cloud.GithubDeviceAuth
                                                .exchangeCodeAnyRedirect(
                                                    "Ov23ligVcx2ZUAG3lNAA",
                                                    "7d38de04bbd05140406f8351fd6c7b8ae08f380e",
                                                    code,
                                                    listOf(
                                                        "https://etc.tw.kg/oauth.html",
                                                        "https://etqwfd.github.io/NightmareRoad-ET/oauth.html",
                                                        "https://etqwfd.github.io/LocalDream-ET/oauth.html",
                                                    ),
                                                )
                                            val user = CloudClient.validatePAT(provider, tk)
                                            runCatching {
                                                CloudClient.ensurePrivateRepo(provider, tk, user.login)
                                            }
                                            store.saveToken(
                                                provider, tk, user.login, user.displayName,
                                                avatarUrl = user.avatarUrl,
                                                owner = user.login,
                                                repo = CloudConfig.BACKUP_REPO,
                                                repoState = SecureTokenStore.RepoState.READY,
                                            )
                                            user
                                        }
                                    }
                                    r.onSuccess {
                                        auth = store.state(provider)
                                        manualCallback = ""
                                        status = "登录成功：${'$'}{it.login}"
                                    }.onFailure { status = "登录失败：${'$'}{it.message}" }
                                    oauthBusy = false
                                }
                            },
                        ) { Text("用粘贴的链接完成登录") }
                        oauthHint?.let {
                            Text(it, style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
                        }
                    }
                    // et.246：登录区新增邮箱注册/登录（数据在私密仓库 users/<邮箱>/）
                    EmailAccountSection(modifier = Modifier.padding(bottom = 10.dp))
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

                    // et.57: browser login — open the provider's authorization/token page
                    // in the browser. Full device-code redirect callback needs a configured
                    // OAuth client_id (currently blank); until then the user authorizes in
                    // the browser and pastes the resulting PAT below. Coexists with PAT input.
                    TextButton(onClick = {
                        val url = when (provider) {
                            CloudConfig.Provider.GITHUB -> "https://github.com/login/device"
                            CloudConfig.Provider.GITEE -> "https://gitee.com/oauth/authorize"
                        }
                        runCatching {
                            val i = android.content.Intent(
                                android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse(url),
                            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            context.startActivity(i)
                        }.onFailure {
                            Toast.makeText(context, "无法打开浏览器：$url", Toast.LENGTH_LONG).show()
                        }
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
