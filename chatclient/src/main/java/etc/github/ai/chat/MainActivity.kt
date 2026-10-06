package etc.github.ai.chat

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
import androidx.lifecycle.viewmodel.compose.viewModel
import etc.github.ai.chat.ui.ChatScreen

/**
 * ET 聊天画师 / LocalDream Chat — chat-style client for the ET server.
 * Standalone applicationId etc.github.ai.chat, installs next to the main app.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AppTheme {
                val vm: etc.github.ai.chat.ui.ChatViewModel = viewModel(
                    factory = etc.github.ai.chat.ui.ChatViewModelFactory(application),
                )
                ChatScreen(vm)
            }
        }
    }
}

@Composable
private fun AppTheme(content: @Composable () -> Unit) {
    // Dark-first (per ET preference), light theme still available.
    val scheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
    MaterialTheme(colorScheme = scheme, content = content)
}
