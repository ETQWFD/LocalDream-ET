package io.github.xororz.localdream.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.xororz.localdream.utils.AppUpdater
import java.io.File

/** Tap on the "update ready" notification -> launch the system installer. */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val path = intent.getStringExtra("apk_path") ?: return
        runCatching {
            AppUpdater.install(context, File(path))
        }
    }
}
