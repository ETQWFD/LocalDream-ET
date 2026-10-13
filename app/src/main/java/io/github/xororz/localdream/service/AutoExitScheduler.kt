package io.github.xororz.localdream.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log

/**
 * et.67: 自动退出/省电闹钟。
 * 到点由 AlarmManager 触发 [AutoExitReceiver] -> 停止生成服务、释放引擎、移除通知/悬浮球。
 */
class AutoExitReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null) return
        val why = intent?.getStringExtra("reason") ?: "auto_exit"
        Log.d("AutoExit", "triggered reason=$why")
        try {
            context.startService(
                Intent(context, BackgroundGenerationService::class.java).apply {
                    action = "io.github.xororz.localdream.STOP_GENERATION"
                },
            )
        } catch (e: Exception) {
            Log.w("AutoExit", "stop gen failed", e)
        }
        try {
            context.startService(
                Intent(context, BackendService::class.java).apply {
                    action = BackendService.ACTION_STOP
                },
            )
        } catch (e: Exception) {
            Log.w("AutoExit", "stop engine failed", e)
        }
    }
}

object AutoExitScheduler {
    private const val REQ_DURATION = 1001
    private const val REQ_END = 1002

    private fun pi(context: Context, req: Int): PendingIntent {
        val i = Intent(context, AutoExitReceiver::class.java).apply {
            action = "io.github.xororz.localdream.AUTO_EXIT"
            putExtra("reason", if (req == REQ_DURATION) "duration" else "daily_end")
        }
        return PendingIntent.getBroadcast(
            context, req, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** a) 开启后 hours 小时触发一次。 */
    fun scheduleDuration(context: Context, hours: Double) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val triggerAt = SystemClock.elapsedRealtime() + (hours * 3600_000L).toLong()
        am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi(context, REQ_DURATION))
    }

    fun cancelDuration(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        try { am.cancel(pi(context, REQ_DURATION)) } catch (_: Exception) {}
    }

    /** b) 每日 endHour:endMinute 触发（到点杀后台）。 */
    fun scheduleDailyEnd(context: Context, endHour: Int, endMinute: Int) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val cal = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, endHour)
            set(java.util.Calendar.MINUTE, endMinute)
            set(java.util.Calendar.SECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) {
                add(java.util.Calendar.DAY_OF_YEAR, 1)
            }
        }
        am.setRepeating(
            AlarmManager.RTC_WAKEUP,
            cal.timeInMillis,
            AlarmManager.INTERVAL_DAY,
            pi(context, REQ_END),
        )
    }

    fun cancelDailyEnd(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        try { am.cancel(pi(context, REQ_END)) } catch (_: Exception) {}
    }
}
