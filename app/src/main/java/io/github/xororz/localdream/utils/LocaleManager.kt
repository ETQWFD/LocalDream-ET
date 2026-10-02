package io.github.xororz.localdream.utils

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * Lightweight in-app language switcher. The app is pure Compose with no
 * AppCompat, so we persist a language tag, wrap the base Context with the
 * chosen [Locale], and recreate the Activity when it changes. Values:
 * "system" (follow device), "zh-CN", "en", "zh-TW".
 */
object LocaleManager {
    private const val PREFS = "locale_prefs"
    private const val KEY_LANG = "app_language"

    const val SYSTEM = "system"
    const val ZH_CN = "zh-CN"
    const val EN = "en"
    const val ZH_TW = "zh-TW"

    fun getSavedLanguage(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LANG, SYSTEM) ?: SYSTEM

    fun setLanguage(context: Context, lang: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_LANG, lang).apply()
    }

    private fun localeFor(lang: String): Locale? = when (lang) {
        ZH_CN -> Locale.SIMPLIFIED_CHINESE
        ZH_TW -> Locale.TRADITIONAL_CHINESE
        EN -> Locale.ENGLISH
        else -> null
    }

    /** Wrap a Context so resources resolve in the saved language. */
    fun wrap(context: Context): Context {
        val lang = getSavedLanguage(context)
        val locale = localeFor(lang) ?: return context
        Locale.setDefault(locale)
        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        return context.createConfigurationContext(config)
    }

    /** Apply then recreate if the active language actually changed. */
    fun applyAndRecreate(activity: Activity, lang: String) {
        if (getSavedLanguage(activity) == lang) return
        setLanguage(activity, lang)
        activity.recreate()
    }
}
