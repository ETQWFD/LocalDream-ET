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

    enum class Mode { OFF, DAWN, CUSTOM }

    private const val PREF = "bgm_prefs"
    private const val KEY_MODE = "mode"
    private const val KEY_CUSTOM_URI = "custom_uri"
    private const val KEY_CUSTOM_NAME = "custom_name"

    private var player: MediaPlayer? = null

    fun mode(ctx: Context): Mode = runCatching {
        when (prefs(ctx).getString(KEY_MODE, Mode.OFF.name) ?: Mode.OFF.name) {
            Mode.DAWN.name -> Mode.DAWN
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
                else -> return@runCatching
            }
            mp.isLooping = true
            mp.setVolume(0.5f, 0.5f)
            mp.prepare()
            mp.start()
            player = mp
        }
    }

    fun stop(ctx: Context) {
        runCatching {
            player?.let {
                if (it.isPlaying) it.stop()
                it.release()
            }
        }
        player = null
    }

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
}
