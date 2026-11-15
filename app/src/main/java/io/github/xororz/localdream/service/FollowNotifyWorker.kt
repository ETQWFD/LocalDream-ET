package io.github.xororz.localdream.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import io.github.xororz.localdream.MainActivity
import io.github.xororz.localdream.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 2026-11：关注作者的新图提醒。
 * 进程存活期间每 15 分钟检查一次（另在进入社区刷新时立即检查），
 * 发现关注作者有比上次记录更新的作品就发通知。
 */
object FollowNotifyWorker {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var started = false

    fun schedule(ctx: Context) {
        if (started) return
        started = true
        scope.launch {
            while (true) {
                runCatching { checkNow(ctx.applicationContext) }
                delay(15 * 60 * 1000L)
            }
        }
    }

    suspend fun checkNow(ctx: Context) {
        val followed = FollowStore.all(ctx)
        if (followed.isEmpty()) return
        val items = runCatching { GalleryClient.list(force = true) }.getOrNull() ?: return
        var fresh = 0
        val names = mutableListOf<String>()
        for (author in followed) {
            val latest = items.filter { it.author == author }.maxOfOrNull { it.ts } ?: continue
            val seen = FollowStore.lastSeen(ctx, author)
            if (seen > 0L && latest > seen) {
                items.filter { it.author == author && it.ts > seen }.forEach {
                    fresh++; names.add("${it.author}：${it.title.ifBlank { it.name }}")
                }
            }
            FollowStore.markSeen(ctx, author, latest)
        }
        if (fresh > 0) withContext(Dispatchers.Main) { notify(ctx, fresh, names.take(5)) }
    }

    private fun notify(ctx: Context, count: Int, names: List<String>) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel("follow_new", "关注作者新图", NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
        val intent = Intent(ctx, MainActivity::class.java)
        val pi = PendingIntent.getActivity(
            ctx, 1, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val body = if (names.isEmpty()) "你关注的作者发布了 $count 张新作品" else names.joinToString("\n")
        val n = NotificationCompat.Builder(ctx, "follow_new")
            .setSmallIcon(R.drawable.ic_gallery_logo)
            .setContentTitle("关注的作者有新作品（$count）")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        runCatching { nm.notify(1001, n) }
    }
}
