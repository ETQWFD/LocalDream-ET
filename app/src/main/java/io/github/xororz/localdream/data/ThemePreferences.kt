package io.github.xororz.localdream.data

import android.content.Context
import android.os.Build
import androidx.core.content.edit
import io.github.xororz.localdream.ui.theme.ThemePreset

enum class DarkModePreference { SYSTEM, LIGHT, DARK }

data class ThemeState(
    val dynamicColor: Boolean = true,
    val preset: ThemePreset = ThemePreset.TANGERINE,
    val darkMode: DarkModePreference = DarkModePreference.SYSTEM,
)

class ThemePreferences(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun read(): ThemeState {
        val supportsDynamic = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        val dynamic = supportsDynamic && prefs.getBoolean(KEY_DYNAMIC, supportsDynamic)
        val preset = prefs.getString(KEY_PRESET, null)
            ?.let { name -> runCatching { ThemePreset.valueOf(name) }.getOrNull() }
            ?: ThemePreset.TANGERINE
        val darkMode = prefs.getString(KEY_DARK_MODE, null)
            ?.let { name -> runCatching { DarkModePreference.valueOf(name) }.getOrNull() }
            ?: DarkModePreference.SYSTEM
        return ThemeState(dynamic, preset, darkMode)
    }

    fun write(state: ThemeState) {
        prefs.edit {
            putBoolean(KEY_DYNAMIC, state.dynamicColor)
            putString(KEY_PRESET, state.preset.name)
            putString(KEY_DARK_MODE, state.darkMode.name)
        }
    }

    companion object {
        private const val PREFS_NAME = "theme_prefs"
        private const val KEY_DYNAMIC = "dynamic_color"
        private const val KEY_PRESET = "preset"
        private const val KEY_DARK_MODE = "dark_mode"
    }
}
