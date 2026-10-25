package io.github.xororz.localdream

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedTextField
import androidx.core.content.ContextCompat
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import io.github.xororz.localdream.data.MigrationState
import io.github.xororz.localdream.navigation.Screen
import io.github.xororz.localdream.ui.screens.HistoryScreen
import io.github.xororz.localdream.ui.screens.MigrationScreen
import io.github.xororz.localdream.ui.screens.ModelListScreen
import io.github.xororz.localdream.ui.screens.ModelRunScreen
import io.github.xororz.localdream.ui.screens.RemoteScreen
import io.github.xororz.localdream.ui.screens.UpscaleScreen
import io.github.xororz.localdream.ui.theme.LocalDreamTheme
import io.github.xororz.localdream.ui.theme.LocalThemeController
import io.github.xororz.localdream.utils.LocaleManager
import io.github.xororz.localdream.ui.theme.rememberThemeController
import io.github.xororz.localdream.ui.theme.sharedAxisXEnter
import io.github.xororz.localdream.ui.theme.sharedAxisXExit
import io.github.xororz.localdream.ui.theme.sharedAxisXPopEnter
import io.github.xororz.localdream.ui.theme.sharedAxisXPopExit
import io.github.xororz.localdream.ui.theme.sharedAxisXPredictivePopEnter
import io.github.xororz.localdream.ui.theme.sharedAxisXPredictivePopExit

class MainActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleManager.wrap(newBase))
    }

    private val requestStoragePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { isGranted: Boolean ->
        if (!isGranted) {
            Toast.makeText(
                this,
                getString(R.string.permission_storage_required),
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    // Android 11+: "All files access" lives in a system settings screen, not a
    // normal runtime dialog. On return we verify and create the public folder.
    private val allFilesAccessLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            Toast.makeText(
                this,
                getString(R.string.permission_storage_required),
                Toast.LENGTH_LONG,
            ).show()
        } else {
            io.github.xororz.localdream.utils.Storage.publicRoot().mkdirs()
        }
    }

    private val requestNotificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { isGranted: Boolean ->
        if (!isGranted) {
            Toast.makeText(
                this,
                getString(R.string.permission_notification_required),
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    private fun checkStoragePermission() {
        // Android 11 (API 30) and above: All files access for the shared folder.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                Toast.makeText(
                    this,
                    getString(R.string.permission_storage_required),
                    Toast.LENGTH_LONG,
                ).show()
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:$packageName")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                runCatching {
                    allFilesAccessLauncher.launch(intent)
                }.getOrElse {
                    // Some devices lack the per-app screen: open the generic list.
                    allFilesAccessLauncher.launch(
                        Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            } else {
                io.github.xororz.localdream.utils.Storage.publicRoot().mkdirs()
            }
            return
        }

        // API 28–29: classic runtime storage permission.
        when {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
            ) == PackageManager.PERMISSION_GRANTED -> {
                // ok
            }

            shouldShowRequestPermissionRationale(Manifest.permission.WRITE_EXTERNAL_STORAGE) -> {
                Toast.makeText(
                    this,
                    getString(R.string.permission_storage_required),
                    Toast.LENGTH_LONG,
                ).show()
                requestStoragePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }

            else -> {
                requestStoragePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
    }

    private fun checkNotificationPermission() {
        // > Android 13
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            when {
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) == PackageManager.PERMISSION_GRANTED -> {
                    // ok
                }

                shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) -> {
                    Toast.makeText(
                        this,
                        getString(R.string.permission_notification_required),
                        Toast.LENGTH_LONG,
                    ).show()
                    requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }

                else -> {
                    requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        }
    }

    private val announceDialog = androidx.compose.runtime.mutableStateOf<Pair<String, String>?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // et.109: global crash handler — dump stack to clipboard, toast feedback, no silent crash.
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                val sw = java.io.StringWriter(); e.printStackTrace(java.io.PrintWriter(sw))
                val clip = (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                clip.setPrimaryClip(android.content.ClipData.newPlainText("crash", sw.toString()))
                val main = android.os.Handler(android.os.Looper.getMainLooper())
                main.post {
                    runCatching {
                        Toast.makeText(this, "日志已复制，请反馈开发者 2416444244@qq.com", Toast.LENGTH_LONG).show()
                    }
                }
                Thread.sleep(1500)
            }
            prev?.uncaughtException(t, e)
        }
        enableEdgeToEdge()

        checkStoragePermission()
        checkNotificationPermission()

        // et.90: report device once per version bump (not every launch).
        Thread {
            runCatching {
                val rp = getSharedPreferences("app_prefs", MODE_PRIVATE)
                val v = io.github.xororz.localdream.BuildConfig.VERSION_CODE
                if (rp.getInt("last_reported_version", 0) == v) return@runCatching
                val store = io.github.xororz.localdream.cloud.SecureTokenStore(this)
                val provider = io.github.xororz.localdream.cloud.CloudConfig.Provider.GITHUB
                val tok = store.token(provider) ?: return@runCatching
                val auth = store.state(provider) ?: return@runCatching
                // ip-api.com gives province (regionName) in CN.
                val conn = (java.net.URL("http://ip-api.com/json/?fields=status,query,regionName,country").openConnection() as java.net.HttpURLConnection).apply {
                    connectTimeout = 6000; readTimeout = 6000
                }
                val body = conn.inputStream.bufferedReader().readText()
                val ip = Regex("\"query\"\\s*:\\s*\"([^\"]*)\"").find(body)?.groupValues?.get(1) ?: "?"
                val province = (Regex("\"regionName\"\\s*:\\s*\"([^\"]*)\"").find(body)?.groupValues?.get(1) ?: "") +
                    " " + (Regex("\"country\"\\s*:\\s*\"([^\"]*)\"").find(body)?.groupValues?.get(1) ?: "")
                val androidId = android.provider.Settings.Secure.getString(
                    contentResolver, android.provider.Settings.Secure.ANDROID_ID
                ) ?: "anon"
                rp.edit().putInt("last_reported_version", v).apply()
                // et.91: local LAN IP (192.168/10.x).
                val wifiIp = runCatching {
                    java.net.NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
                        .firstOrNull { !it.isLoopbackAddress && it.hostAddress?.contains(':') == false }?.hostAddress ?: "?"
                }.getOrDefault("?")
                val content = """{"type":"App","ip":"$ip","wifiIp":"$wifiIp","region":"$province","model":"${android.os.Build.MODEL}","androidId":"$androidId","version":$v,"ts":${System.currentTimeMillis()}}"""
                io.github.xororz.localdream.cloud.CloudClient.uploadContent(
                    provider, tok, auth.owner ?: auth.login, auth.repo
                        ?: io.github.xororz.localdream.cloud.CloudConfig.BACKUP_REPO,
                    "devices/device_" + androidId.take(8) + ".json",
                    content.toByteArray(),
                    "device ping",
                )
            }
        }.start()

        // et.114: pull announcements/ from ratings repo; newest matching 手机端/全部 -> dialog.
        Thread {
            runCatching {
                val store = io.github.xororz.localdream.cloud.SecureTokenStore(this)
                val provider = io.github.xororz.localdream.cloud.CloudConfig.Provider.GITHUB
                val tok = store.token(provider) ?: return@runCatching
                val repo = "ETQWFD/LocalDream-ET-ratings"
                val listConn = (java.net.URL("https://api.github.com/repos/$repo/contents/announcements")
                    .openConnection() as java.net.HttpURLConnection).apply {
                    connectTimeout = 8000; readTimeout = 8000
                    setRequestProperty("Authorization", "token $tok")
                    setRequestProperty("Accept", "application/vnd.github+json")
                }
                if (listConn.responseCode != 200) return@runCatching
                val arr = org.json.JSONArray(listConn.inputStream.bufferedReader().readText())
                var best: Pair<Long, String>? = null
                for (i in 0 until arr.length()) {
                    val name = arr.getJSONObject(i).optString("name", "")
                    if (!name.startsWith("announce_") || !name.endsWith(".json")) continue
                    val raw = runCatching {
                        val c = (java.net.URL("https://api.github.com/repos/$repo/contents/announcements/$name")
                            .openConnection() as java.net.HttpURLConnection).apply {
                            connectTimeout = 8000; readTimeout = 8000
                            setRequestProperty("Authorization", "token $tok")
                            setRequestProperty("Accept", "application/vnd.github+json")
                        }
                        if (c.responseCode != 200) return@runCatching null
                        val j = org.json.JSONObject(c.inputStream.bufferedReader().readText())
                        String(android.util.Base64.decode(j.getString("content"), android.util.Base64.DEFAULT))
                    }.getOrNull() ?: continue
                    val o = runCatching { org.json.JSONObject(raw) }.getOrNull() ?: continue
                    val target = o.optString("target", "全部")
                    if (!target.contains("手机") && !target.contains("全部")) continue
                    val ts = o.optLong("ts", 0L)
                    if (best == null || ts > best!!.first) best = ts to raw
                }
                val b = best ?: return@runCatching
                val sp = getSharedPreferences("app_prefs", MODE_PRIVATE)
                val lastTs = sp.getLong("last_announce_ts", 0L)
                val o = org.json.JSONObject(b.second)
                val ts = o.optLong("ts", 0L)
                if (ts <= lastTs) return@runCatching
                sp.edit().putLong("last_announce_ts", ts).apply()
                val title = o.optString("title", "公告")
                val text = o.optString("text", "")
                runOnUiThread { announceDialog.value = title to text }
            }
        }.start()

        val app = application as LocalDreamApplication

        setContent {
            val themeController = rememberThemeController()
            CompositionLocalProvider(LocalThemeController provides themeController) {
                LocalDreamTheme(themeController.state) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.surface,
                    ) {
                        val migrationState by app.migrationState.collectAsState()

                        when (migrationState) {
                            is MigrationState.Done,
                            is MigrationState.NotNeeded,
                            -> AppContent()

                            is MigrationState.Idle,
                            is MigrationState.InProgress,
                            is MigrationState.Failed,
                            -> MigrationScreen(
                                state = migrationState,
                                onRetry = { app.retryMigration() },
                                onSkip = { app.skipMigration() },
                            )
                        }

                        // et.92: coarse-location gate on first launch.
                        var locGranted by remember {
                            mutableStateOf(
                                androidx.core.content.ContextCompat.checkSelfPermission(
                                    this@MainActivity,
                                    android.Manifest.permission.ACCESS_COARSE_LOCATION,
                                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                            )
                        }
                        var showLocGate by remember { mutableStateOf(!locGranted) }
                        val locLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
                            androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
                        ) { ok ->
                            locGranted = ok == true
                            if (locGranted) showLocGate = false
                        }
                        if (showLocGate && !locGranted) {
                            androidx.compose.material3.AlertDialog(
                                onDismissRequest = { },
                                title = { Text("需要位置权限") },
                                text = { Text("定位仅用于统计用户分布和防盗用，不收集精确位置，不存储通讯录/照片。") },
                                confirmButton = {
                                    androidx.compose.material3.TextButton(onClick = {
                                        runCatching {
                                            startActivity(
                                                android.content.Intent(
                                                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                                    android.net.Uri.parse("package:$packageName"),
                                                ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                            )
                                        }
                                    }) { Text("重新授权") }
                                },
                                dismissButton = {
                                    androidx.compose.material3.TextButton(onClick = { showLocGate = false }) {
                                        Text("先看看")
                                    }
                                },
                            )
                        }
                        LaunchedEffect(Unit) { if (!locGranted) locLauncher.launch(android.Manifest.permission.ACCESS_COARSE_LOCATION) }

                        val prefs = remember { getSharedPreferences("app_prefs", MODE_PRIVATE) }
                        var ratingDialog by remember {
                            val n = prefs.getInt("launch_count", 0) + 1
                            prefs.edit().putInt("launch_count", n).apply()
                            mutableStateOf(n >= 2 && !prefs.getBoolean("rated_v2", false))
                        }
                        if (ratingDialog) {
                            var stars by remember { mutableStateOf(5) }
                            var comment by remember { mutableStateOf("") }
                            androidx.compose.material3.AlertDialog(
                                onDismissRequest = { ratingDialog = false },
                                title = { Text("给个评分") },
                                text = {
                                    androidx.compose.foundation.layout.Column {
                                        Text("你喜欢用吗？点星评分")
                                        androidx.compose.foundation.layout.Row {
                                            for (i in 1..5) {
                                                Text(
                                                    text = if (i <= stars) "★" else "☆",
                                                    fontSize = androidx.compose.ui.unit.TextUnit(28f, androidx.compose.ui.unit.TextUnitType.Sp),
                                                    modifier = Modifier.clickable { stars = i },
                                                )
                                            }
                                        }
                                        androidx.compose.material3.OutlinedTextField(
                                            value = comment, onValueChange = { comment = it },
                                            label = { Text("评论（选填）") },
                                            modifier = Modifier.fillMaxWidth(),
                                        )
                                    }
                                },
                                confirmButton = {
                                    androidx.compose.material3.TextButton(onClick = {
                                        ratingDialog = false
                                        prefs.edit().putBoolean("rated_v2", true).apply()
                                        val s = stars
                                        val c = comment
                                        Thread {
                                            val ok = runCatching {
                                                val store = io.github.xororz.localdream.cloud.SecureTokenStore(this@MainActivity)
                                                val provider = io.github.xororz.localdream.cloud.CloudConfig.Provider.GITHUB
                                                val tok = store.token(provider) ?: return@runCatching false
                                                val auth = store.state(provider) ?: return@runCatching false
                                                val aid = android.provider.Settings.Secure.getString(
                                                    contentResolver, android.provider.Settings.Secure.ANDROID_ID
                                                ) ?: "anon"
                                                val safeComment = c.replace("\\", "\\\\").replace("\"", "'")
                                                    .replace("\n", "\\n").replace("\r", "")
                                                val content = """{"stars":$s,"comment":""" + "\"" + safeComment + "\"" + ""","ts":${System.currentTimeMillis()},"model":"${android.os.Build.MODEL}"}"""
                                                io.github.xororz.localdream.cloud.CloudClient.uploadContent(
                                                    provider, tok, auth.owner ?: auth.login,
                                                    auth.repo ?: io.github.xororz.localdream.cloud.CloudConfig.BACKUP_REPO,
                                                    "ratings/rating_$aid.json", content.toByteArray(), "rating",
                                                )
                                                true
                                            }.getOrDefault(false)
                                            runOnUiThread {
                                                Toast.makeText(
                                                    this@MainActivity,
                                                    if (ok) "评分已提交，谢谢！" else "提交失败，请检查网络/登录",
                                                    Toast.LENGTH_LONG,
                                                ).show()
                                            }
                                        }.start()
                                    }) { Text("提交") }
                                },
                                dismissButton = {
                                    androidx.compose.material3.TextButton(onClick = {
                                        ratingDialog = false
                                        prefs.edit().putBoolean("rated_v2", true).apply()
                                    }) { Text("下次再说") }
                                },
                            )
                        }
                        // et.112: 反馈问题入口 + 弹窗
                        var feedbackDialog by remember { mutableStateOf(false) }
                        androidx.compose.material3.ExtendedFloatingActionButton(
                            onClick = { feedbackDialog = true },
                        ) { Text("反馈问题") }
                        if (feedbackDialog) {
                            var fb by remember { mutableStateOf("") }
                            androidx.compose.material3.AlertDialog(
                                onDismissRequest = { feedbackDialog = false },
                                confirmButton = {
                                    androidx.compose.material3.TextButton(onClick = {
                                        val text = fb.trim()
                                        if (text.isEmpty()) return@TextButton
                                        feedbackDialog = false
                                        Thread {
                                            val (ok, reason) = runCatching {
                                                val store = io.github.xororz.localdream.cloud.SecureTokenStore(this@MainActivity)
                                                val provider = io.github.xororz.localdream.cloud.CloudConfig.Provider.GITHUB
                                                val tok = store.token(provider) ?: return@runCatching (false to "未登录Token")
                                                val auth = store.state(provider) ?: return@runCatching (false to "未登录")
                                                val aid = android.provider.Settings.Secure.getString(
                                                    contentResolver, android.provider.Settings.Secure.ANDROID_ID
                                                ) ?: "anon"
                                                val ts = System.currentTimeMillis()
                                                val safeText = text.replace("\\", "\\\\").replace("\"", "'")
                                                    .replace("\n", "\\n").replace("\r", "")
                                                val body = """{"text":"$safeText","model":"${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}","androidVer":"${android.os.Build.VERSION.RELEASE}","ts":$ts}"""
                                                io.github.xororz.localdream.cloud.CloudClient.uploadContent(
                                                    provider, tok, auth.owner ?: auth.login,
                                                    auth.repo ?: io.github.xororz.localdream.cloud.CloudConfig.BACKUP_REPO,
                                                    "feedback/feedback_${aid.take(8)}_$ts.json", body.toByteArray(), "feedback",
                                                )
                                                (true to "")
                                            }.getOrElse { false to (it.message ?: "网络错误") }
                                            runOnUiThread {
                                                Toast.makeText(this@MainActivity,
                                                    if (ok) "反馈已提交，谢谢" else "提交失败：$reason",
                                                    Toast.LENGTH_LONG).show()
                                            }
                                        }.start()
                                    }) { Text("提交") }
                                },
                                dismissButton = {
                                    androidx.compose.material3.TextButton(onClick = { feedbackDialog = false }) { Text("取消") }
                                },
                                title = { Text("反馈问题") },
                                text = {
                                    androidx.compose.material3.OutlinedTextField(
                                        value = fb, onValueChange = { fb = it },
                                        placeholder = { Text("请描述遇到的问题…") },
                                        minLines = 4,
                                    )
                                },
                            )
                        }
                        // et.114: announcement dialog
                        announceDialog.value?.let { (t, tx) ->
                            androidx.compose.material3.AlertDialog(
                                onDismissRequest = { announceDialog.value = null },
                                confirmButton = {
                                    androidx.compose.material3.TextButton(onClick = { announceDialog.value = null }) { Text("知道了") }
                                },
                                title = { Text(t) },
                                text = { Text(tx) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AppContent() {
    val navController = rememberNavController()
    NavHost(
        navController = navController,
        startDestination = Screen.ModelList.route,
        enterTransition = { sharedAxisXEnter() },
        exitTransition = { sharedAxisXExit() },
        popEnterTransition = { sharedAxisXPopEnter() },
        popExitTransition = { sharedAxisXPopExit() },
        predictivePopEnterTransition = { _ -> sharedAxisXPredictivePopEnter() },
        predictivePopExitTransition = { _ -> sharedAxisXPredictivePopExit() },
    ) {
        composable(Screen.ModelList.route) {
            ModelListScreen(navController)
        }
        composable(
            route = Screen.ModelRun.route,
            arguments = listOf(
                navArgument("modelId") {
                    type = NavType.StringType
                },
                navArgument("remote") {
                    type = NavType.BoolType
                    defaultValue = false
                },
            ),
        ) { backStackEntry ->
            val modelId = backStackEntry.arguments?.getString("modelId") ?: ""
            val isRemote = backStackEntry.arguments?.getBoolean("remote") ?: false

            ModelRunScreen(
                modelId = modelId,
                isRemote = isRemote,
                navController = navController,
            )
        }
        composable(Screen.Upscale.route) {
            UpscaleScreen(navController)
        }
        composable(Screen.History.route) {
            HistoryScreen(navController)
        }
        composable(Screen.RemoteLink.route) {
            RemoteScreen(navController)
        }
        composable(
            route = Screen.Edit.route,
            arguments = listOf(
                androidx.navigation.navArgument("modelId") { type = androidx.navigation.NavType.StringType },
                androidx.navigation.navArgument("modelName") {
                    type = androidx.navigation.NavType.StringType
                    defaultValue = "Model"
                },
                androidx.navigation.navArgument("backendType") {
                    type = androidx.navigation.NavType.StringType
                    defaultValue = "sd15cpu"
                },
            ),
        ) { entry ->
            io.github.xororz.localdream.ui.screens.EditScreen(
                navController = navController,
                modelId = entry.arguments?.getString("modelId").orEmpty(),
                modelName = entry.arguments?.getString("modelName") ?: "Model",
                backendType = entry.arguments?.getString("backendType") ?: "sd15cpu",
            )
        }
    }
}
