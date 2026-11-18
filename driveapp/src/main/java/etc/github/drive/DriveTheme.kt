package etc.github.drive

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * 与主 App（Nightmare Road-ET）默认主题 tangerine 同一套配色，
 * 保证云盘的界面、弹窗观感和出图软件一致。
 */
val TangerineLight = lightColorScheme(
    primary = Color(0xFF8F4C38),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFDBCF),
    onPrimaryContainer = Color(0xFF3A0B01),
    secondary = Color(0xFF77574E),
    onSecondary = Color(0xFFFFFFFF),
    surface = Color(0xFFFFF8F6),
    onSurface = Color(0xFF231916),
    surfaceVariant = Color(0xFFF4DED7),
    onSurfaceVariant = Color(0xFF52433F),
    outline = Color(0xFF85736D),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
)

val TangerineDark = darkColorScheme(
    primary = Color(0xFFFFB5A0),
    onPrimary = Color(0xFF561F0D),
    primaryContainer = Color(0xFF723422),
    onPrimaryContainer = Color(0xFFFFDBCF),
    secondary = Color(0xFFE7BDB2),
    onSecondary = Color(0xFF442B25),
    surface = Color(0xFF1A110F),
    onSurface = Color(0xFFF4DED7),
    surfaceVariant = Color(0xFF52433F),
    onSurfaceVariant = Color(0xFFD8C2BC),
    outline = Color(0xFFA08D87),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
)

fun driveScheme(dark: Boolean = isSystemInDarkThemeSafe()): ColorScheme =
    if (dark) TangerineDark else TangerineLight

private fun isSystemInDarkThemeSafe(): Boolean = runCatching {
    val cfg = android.content.res.Configuration(
        android.content.res.Resources.getSystem().configuration,
    )
    (cfg.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
        android.content.res.Configuration.UI_MODE_NIGHT_YES
}.getOrDefault(false)
