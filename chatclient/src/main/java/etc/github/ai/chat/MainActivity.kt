package etc.github.ai.chat

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import etc.github.ai.chat.community.CommunityChatScreen

/**
 * ET 聊天社区（移植版）— 只包含聊天，免登录，私有仓库存储。
 * Standalone applicationId etc.github.ai.chat, installs next to the main app.
 * This APK is private and is never published on the website or a public repo.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // et.187b: ask for notification permission once, then keep the
        // @-mention watcher running while the community app is alive.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
        }
        runCatching { etc.github.ai.chat.community.MentionService.start(this) }

        setContent {
            AppTheme {
                CommunityChatScreen()
            }
        }
    }
}

@Composable
private fun AppTheme(content: @Composable () -> Unit) {
    val scheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
    MaterialTheme(colorScheme = scheme, content = content)
}
