package etc.github.ai.chat.community

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import etc.github.ai.chat.MainActivity
import etc.github.ai.chat.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * et.187b: @-mention watcher. The community is a GitHub-backed chat with no
 * server push, so while the app process is alive we poll the message file and
 * raise a real system notification (heads-up, like WeChat) when a new message
 * mentions my nickname or @所有人. Previously a mention was invisible until the
 * user manually opened the community.
 */
class MentionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    companion object {
        private const val CHANNEL_ID = "et_chat_mentions"
        private const val NOTIF_ID = 4210
        private const val POLL_MS = 40_000L

        fun start(context: Context) {
            runCatching {
                context.startForegroundService(Intent(context, MentionService::class.java))
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "有人@我", NotificationManager.IMPORTANCE_HIGH).apply {
                        description = "共享聊天社区里有人艾特你时提醒"
                        enableVibration(true)
                    },
                )
            }
        }
    }

    private fun foregroundNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("ET 聊天社区")
            .setContentText("正在监听 @ 消息")
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(open)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID + 1, foregroundNotification())
        if (job == null) {
            job = scope.launch {
                val sp = getSharedPreferences("et_chat", Context.MODE_PRIVATE)
                var lastTs = sp.getLong("last_mention_ts", 0L)
                var primed = sp.getBoolean("mention_primed", false)
                while (isActive) {
                    runCatching {
                        // Don't notify for history on first run.
                        val msgs = ChatApi.load()
                        val me = sp.getString("nick", "").orEmpty()
                        val newest = msgs.maxOfOrNull { it.ts } ?: lastTs
                        if (!primed) {
                            primed = true
                            sp.edit().putBoolean("mention_primed", true)
                                .putLong("last_mention_ts", newest).apply()
                            lastTs = newest
                        } else {
                            val hits = msgs.filter { m ->
                                m.ts > lastTs && m.author != me && m.text.isNotBlank() &&
                                    (m.text.contains("@$me") ||
                                        m.text.contains("@所有人") ||
                                        m.text.contains("@all", true))
                            }
                            hits.takeLast(3).forEach { m ->
                                notifyMention(m.author, m.text)
                            }
                            if (newest > lastTs) {
                                lastTs = newest
                                sp.edit().putLong("last_mention_ts", newest).apply()
                            }
                        }
                    }
                    delay(POLL_MS)
                }
            }
        }
        return START_STICKY
    }

    private fun notifyMention(author: String, text: String) {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        val open = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("$author 在社区 @ 了你")
            .setContentText(text.take(80))
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        runCatching { nm.notify(NOTIF_ID + text.hashCode().and(0xffff), n) }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        job?.cancel()
        super.onDestroy()
    }
}
