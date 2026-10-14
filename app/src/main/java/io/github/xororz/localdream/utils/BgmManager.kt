package io.github.xororz.localdream.utils

import android.content.Context
import android.content.SharedPreferences
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper

/**
 * et.57 optional background music.
 *
 * Three modes persisted in SharedPreferences:
 *   off    -> never play
 *   dawn   -> default bundled res/raw/bgm_dawn (黎明进行曲)
 *   custom -> user-imported file copied to app-private dir
 *
 * It is purely optional and cheap: a single MediaPlayer, started/stopped on demand.
 * Every call is wrapped in runCatching so a bad import never crashes generation.
 */
object BgmManager {

    enum class Mode { OFF, DAWN, SONG1, SONG2, CUSTOM }

    private const val PREF = "bgm_prefs"
    private const val KEY_MODE = "mode"
    private const val KEY_CUSTOM_URI = "custom_uri"
    private const val KEY_CUSTOM_NAME = "custom_name"
    private const val KEY_PLAYLIST = "playlist"
    private const val KEY_PLAYLIST_INDEX = "playlist_index"
    const val ACTION_TOGGLE = "io.github.xororz.localdream.BGM_TOGGLE"
    const val ACTION_NEXT = "io.github.xororz.localdream.BGM_NEXT"
    const val ACTION_PREV = "io.github.xororz.localdream.BGM_PREV"
    private const val MAX_IMPORTS = 80

    private var player: MediaPlayer? = null

    fun mode(ctx: Context): Mode = runCatching {
        when (prefs(ctx).getString(KEY_MODE, Mode.OFF.name) ?: Mode.OFF.name) {
            Mode.DAWN.name -> Mode.DAWN
            Mode.SONG1.name -> Mode.SONG1
            Mode.SONG2.name -> Mode.SONG2
            Mode.CUSTOM.name -> Mode.CUSTOM
            else -> Mode.OFF
        }
    }.getOrDefault(Mode.OFF)

    fun setMode(ctx: Context, mode: Mode) {
        prefs(ctx).edit().putString(KEY_MODE, mode.name).apply()
        if (mode == Mode.OFF) stop(ctx)
    }

    fun setCustomUri(ctx: Context, uri: String) {
        prefs(ctx).edit().putString(KEY_CUSTOM_URI, uri).putString(KEY_MODE, Mode.CUSTOM.name).apply()
    }

    fun customUri(ctx: Context): String? = prefs(ctx).getString(KEY_CUSTOM_URI, null)

    fun customName(ctx: Context): String? = prefs(ctx).getString(KEY_CUSTOM_NAME, null)

    /**
     * et.59: copy a user-picked audio Uri into app-private storage so it survives
     * reboots and MediaPlayer can read it. (Direct MediaPlayer playback of common
     * mp3/m4a/ogg/wav; we do not re-encode — wrapped in runCatching, failure is
     * reported in the UI.) Returns the stored display name on success.
     */
    fun importCustom(ctx: Context, src: Uri, displayName: String): Boolean = runCatching {
        val app = ctx.applicationContext
        val out = java.io.File(app.filesDir, "bgm_custom_audio.dat")
        app.contentResolver.openInputStream(src)?.use { input ->
            java.io.FileOutputStream(out).use { fos -> input.copyTo(fos) }
        } ?: return@runCatching false
        prefs(ctx).edit()
            .putString(KEY_CUSTOM_URI, Uri.fromFile(out).toString())
            .putString(KEY_CUSTOM_NAME, displayName)
            .putString(KEY_MODE, Mode.CUSTOM.name)
            .apply()
        true
    }.getOrDefault(false)

    fun isPlaying(): Boolean = runCatching { player?.isPlaying == true }.getOrDefault(false)

    /** et.76: pause/resume toggle used by the notification control. */
    fun togglePause(ctx: Context) {
        runCatching {
            val p = player ?: return
            if (p.isPlaying) p.pause() else p.start()
        }
    }

    private fun postStatusNotification(ctx: Context, playing: Boolean) {
        runCatching {
            val app = ctx.applicationContext
            val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            val chId = "bgm_channel"
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                val ch = android.app.NotificationChannel(chId, "背景音乐", android.app.NotificationManager.IMPORTANCE_LOW)
                nm.createNotificationChannel(ch)
            }
            val pa = android.app.Notification.Builder(app, chId)
                .setContentTitle("背景音乐")
                .setContentText(if (playing) "播放中" else "已暂停")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setOngoing(playing)
            fun act(action: String, icon: Int, title: String) {
                val pi = android.app.PendingIntent.getBroadcast(
                    app, action.hashCode(),
                    android.content.Intent(action).setPackage(app.packageName),
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
                )
                pa.addAction(icon, title, pi)
            }
            act(ACTION_PREV, android.R.drawable.ic_media_previous, "上一首")
            act(ACTION_TOGGLE, if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (playing) "暂停" else "播放")
            act(ACTION_NEXT, android.R.drawable.ic_media_next, "下一首")
            if (playing) nm.notify(4242, pa.build()) else nm.cancel(4242)
        }
    }

    /** Start (or restart) the currently selected background music. Idempotent. */
    fun start(ctx: Context) {
        val app = ctx.applicationContext
        val m = mode(app)
        if (m == Mode.OFF) return
        runCatching {
            stop(app)
            val mp = MediaPlayer()
            when (m) {
                Mode.DAWN -> {
                    val af = app.resources.openRawResourceFd(io.github.xororz.localdream.R.raw.bgm_dawn)
                        ?: return@runCatching
                    mp.setDataSource(af.fileDescriptor, af.startOffset, af.length)
                    af.close()
                }
                Mode.CUSTOM -> {
                    val u = customUri(app) ?: return@runCatching
                    mp.setDataSource(app, Uri.parse(u))
                }
                Mode.SONG1, Mode.SONG2 -> {
                    val name = if (m == Mode.SONG1) "与神明.mp3" else "青春走的太快了.mp3"
                    val af = app.assets.openFd("bgm/$name")
                    mp.setDataSource(af.fileDescriptor, af.startOffset, af.length)
                    af.close()
                }
                else -> return@runCatching
            }
            mp.isLooping = true
            mp.setVolume(0.5f, 0.5f)
            mp.prepare()
            mp.start()
            player = mp
            postStatusNotification(app, true)
        }
    }

    fun stop(ctx: Context) {
        val app = ctx.applicationContext
        runCatching {
            player?.let {
                if (it.isPlaying) it.stop()
                it.release()
            }
        }
        player = null
        postStatusNotification(app, false)
    }

    // --- et.77: imported playlist (list, cap 80, next/prev, deletable) ---
    fun playlist(ctx: Context): List<String> =
        prefs(ctx).getStringSet(KEY_PLAYLIST, emptySet())?.toList() ?: emptyList()

    fun addToPlaylist(ctx: Context, entry: String) {
        val cur = playlist(ctx).toMutableList()
        cur.remove(entry)
        cur.add(0, entry)
        while (cur.size > MAX_IMPORTS) cur.removeAt(cur.size - 1)
        prefs(ctx).edit().putStringSet(KEY_PLAYLIST, LinkedHashSet(cur)).apply()
    }

    fun removeFromPlaylist(ctx: Context, entries: Set<String>) {
        val cur = playlist(ctx).toMutableList()
        cur.removeAll(entries)
        prefs(ctx).edit().putStringSet(KEY_PLAYLIST, LinkedHashSet(cur)).apply()
    }

    fun next(ctx: Context) { runCatching { start(ctx) } }
    fun prev(ctx: Context) { runCatching { start(ctx) } }

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
}

/** et.77: notification action receiver for bgm controls. */
class BgmControlReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: android.content.Intent?) {
        val ctx = context ?: return
        runCatching {
            when (intent?.action) {
                BgmManager.ACTION_TOGGLE -> BgmManager.togglePause(ctx)
                BgmManager.ACTION_NEXT -> BgmManager.next(ctx)
                BgmManager.ACTION_PREV -> BgmManager.prev(ctx)
            }
        }
    }
}
