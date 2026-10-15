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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
                            mutableStateOf(n >= 2 && !prefs.getBoolean("rated", false))
                        }
                        if (ratingDialog) {
                            var stars by remember { mutableStateOf(5) }
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
                                    }
                                },
                                confirmButton = {
                                    androidx.compose.material3.TextButton(onClick = {
                                        ratingDialog = false
                                        prefs.edit().putBoolean("rated", true).apply()
                                        val s = stars
                                        Thread {
                                            runCatching {
                                                val store = io.github.xororz.localdream.cloud.SecureTokenStore(this@MainActivity)
                                                val provider = io.github.xororz.localdream.cloud.CloudConfig.Provider.GITHUB
                                                val tok = store.token(provider) ?: return@runCatching
                                                val auth = store.state(provider) ?: return@runCatching
                                                val aid = android.provider.Settings.Secure.getString(
                                                    contentResolver, android.provider.Settings.Secure.ANDROID_ID
                                                ) ?: "anon"
                                                val content = """{"stars":$s,"ts":${System.currentTimeMillis()},"model":"${android.os.Build.MODEL}"}"""
                                                io.github.xororz.localdream.cloud.CloudClient.uploadContent(
                                                    provider, tok, auth.owner ?: auth.login,
                                                    auth.repo ?: io.github.xororz.localdream.cloud.CloudConfig.BACKUP_REPO,
                                                    "ratings/rating_$aid.json", content.toByteArray(), "rating",
                                                )
                                            }
                                        }.start()
                                    }) { Text("提交") }
                                },
                                dismissButton = {
                                    androidx.compose.material3.TextButton(onClick = {
                                        ratingDialog = false
                                        prefs.edit().putBoolean("rated", true).apply()
                                    }) { Text("下次再说") }
                                },
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
