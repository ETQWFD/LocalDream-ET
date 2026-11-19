package etc.github.drive

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver

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

/** 主题色：每个色板给出浅色/深色两套主色，避免深色模式下刺眼。 */
data class Accent(val label: String, val light: Color, val dark: Color, val onLight: Color, val onDark: Color)

val DRIVE_ACCENTS = listOf(
    Accent("琥珀", Color(0xFF8F4C38), Color(0xFFFFB5A0), Color(0xFFFFFFFF), Color(0xFF561F0D)),
    Accent("紫罗兰", Color(0xFF6A4BAF), Color(0xFFCFBDFF), Color(0xFFFFFFFF), Color(0xFF2A1550)),
    Accent("海蓝", Color(0xFF1F6FB2), Color(0xFFA9CDFF), Color(0xFFFFFFFF), Color(0xFF00325A)),
    Accent("青碧", Color(0xFF1F8A78), Color(0xFF9CE3D5), Color(0xFFFFFFFF), Color(0xFF003731)),
    Accent("玫红", Color(0xFFA63A5F), Color(0xFFFFB2C8), Color(0xFFFFFFFF), Color(0xFF5A0F27)),
)

/** 主题色索引持久化（网盘自己的一份，和主 App 互不影响）。 */
object DrivePrefs {
    private const val FILE = "drive_ui"
    private const val KEY = "accent"
    fun accent(ctx: android.content.Context): Int =
        ctx.getSharedPreferences(FILE, android.content.Context.MODE_PRIVATE).getInt(KEY, 0)
            .coerceIn(0, DRIVE_ACCENTS.lastIndex)
    fun setAccent(ctx: android.content.Context, i: Int) {
        ctx.getSharedPreferences(FILE, android.content.Context.MODE_PRIVATE)
            .edit().putInt(KEY, i.coerceIn(0, DRIVE_ACCENTS.lastIndex)).apply()
    }
}

/** 带主题色的配色：主色/容器色按所选色板替换，其余保持 tangerine 的中性底色。 */
fun driveScheme(dark: Boolean, accent: Int): ColorScheme {
    val base = if (dark) TangerineDark else TangerineLight
    val a = DRIVE_ACCENTS[accent.coerceIn(0, DRIVE_ACCENTS.lastIndex)]
    return if (dark) base.copy(
        primary = a.dark,
        onPrimary = a.onDark,
        primaryContainer = a.dark.copy(alpha = 0.28f).compositeOver(base.surface),
        onPrimaryContainer = a.dark,
    ) else base.copy(
        primary = a.light,
        onPrimary = a.onLight,
        primaryContainer = a.light.copy(alpha = 0.16f).compositeOver(base.surface),
        onPrimaryContainer = a.light,
    )
}
