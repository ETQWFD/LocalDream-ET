package io.github.xororz.localdream.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * et.28: vendor-specific background / autostart guidance for aggressive ROMs.
 * Falls back to the app details page when no vendor activity resolves. Uses a
 * shared-preferences flag so it only prompts once.
 */
object VendorCompat {
    private const val PREF = "ldet_vendor"
    private const val KEY_PROMPTED = "autostart_prompted"

    fun promptAutostartIfNeeded(context: Context) {
        runCatching {
            val sp = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            if (sp.getBoolean(KEY_PROMPTED, false)) return
            sp.edit().putBoolean(KEY_PROMPTED, true).apply()

            val pkg = context.packageName
            val candidates = mutableListOf<Array<String>>()
            when (Build.BRAND.lowercase()) {
                "huawei", "honor" -> candidates += arrayOf(
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
                )
                "xiaomi", "redmi", "poco" -> candidates += arrayOf(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity",
                )
                "oppo", "oneplus", "realme" -> candidates += arrayOf(
                    "com.coloros.safecenter",
                    "com.coloros.safecenter.permission.startup.StartupAppListActivity",
                )
                "vivo", "iqoo" -> candidates += arrayOf(
                    "com.vivo.permissionmanager",
                    "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
                )
                "samsung" -> candidates += arrayOf(
                    "com.samsung.android.lool",
                    "com.samsung.android.sm.ui.battery.BatteryActivity",
                )
            }
            // Generic app details fallback.
            var launched = false
            for ((pkgName, cls) in candidates) {
                try {
                    val i = Intent().setClassName(pkgName, cls)
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(i)
                    launched = true
                    break
                } catch (_: Throwable) {
                }
            }
            if (!launched) {
                val i = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.parse("package:$pkg"))
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(i)
            }
        }
    }
}
