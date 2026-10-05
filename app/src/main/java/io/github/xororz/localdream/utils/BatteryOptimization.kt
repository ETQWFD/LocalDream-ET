package io.github.xororz.localdream.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/**
 * et.27: prompts the user once to disable battery optimization for this app so
 * long downloads / conversions / generations are not suspended in the background.
 * Uses a shared-preferences flag so it only prompts once.
 */
object BatteryOptimization {
    private const val PREF = "ldet_battery"
    private const val KEY_PROMPTED = "prompted"

    fun ensureIgnoring(context: Context) {
        runCatching {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            if (pm.isIgnoringBatteryOptimizations(context.packageName)) return
            val sp = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            if (sp.getBoolean(KEY_PROMPTED, false)) return
            sp.edit().putBoolean(KEY_PROMPTED, true).apply()
            val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData(Uri.parse("package:${context.packageName}"))
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(i)
        }
    }
}
