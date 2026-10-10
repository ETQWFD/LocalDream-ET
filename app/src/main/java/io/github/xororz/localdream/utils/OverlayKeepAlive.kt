package io.github.xororz.localdream.utils

import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * et.50 optional overlay keep-alive.
 *
 * A minimal floating window shown while a foreground generation / API service runs.
 * It is purely a process-priority / keep-alive aid: it is only created when the user
 * has granted SYSTEM_ALERT_WINDOW. Without it the app still runs as a normal foreground
 * service — nothing here must throw or block.
 *
 * Safety: idempotent add/remove (token-checked), survives rotation (remove-before-add),
 * always removed on stop.
 */
object OverlayKeepAlive {

    fun canDraw(ctx: Context): Boolean =
        Settings.canDrawOverlays(ctx.applicationContext)

    /** Intent that opens this app's "draw over other apps" settings page. */
    fun settingsIntent(ctx: Context): Intent =
        Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${ctx.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private var windowManager: WindowManager? = null
    private var currentView: View? = null
    private var currentParams: WindowManager.LayoutParams? = null

    /** Show the floating window. No-op (and safe) when permission is missing. */
    @JvmStatic
    @JvmOverloads
    fun show(
        service: Service,
        statusText: String = "LocalDream ET 推理中",
        onStop: (() -> Unit)? = null,
    ) {
        val ctx = service.applicationContext
        if (!canDraw(ctx)) return // degrade to plain foreground service, never crash
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        synchronized(this) {
            removeLocked(wm) // avoid duplicate after rotation / double-start
            val ll = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(28, 20, 28, 20)
                setBackgroundColor(0xCC222222.toInt())
            }
            val tv = TextView(ctx).apply {
                text = statusText
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 13f
            }
            val stop = Button(ctx).apply {
                text = "停止"
                setTextColor(0xFFFFFFFF.toInt())
                setOnClickListener {
                    onStop?.invoke()
                    hide(service)
                }
            }
            ll.addView(tv)
            ll.addView(stop)

            val type = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                android.graphics.PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 24
                y = 160
            }

            // Drag handling
            ll.setOnTouchListener(object : View.OnTouchListener {
                var dx = 0
                var dy = 0
                var initX = 0
                var initY = 0
                var downTime = 0L
                override fun onTouch(v: View, e: android.view.MotionEvent): Boolean {
                    when (e.action) {
                        android.view.MotionEvent.ACTION_DOWN -> {
                            initX = params.x; initY = params.y
                            dx = e.rawX.toInt(); dy = e.rawY.toInt()
                            downTime = android.os.SystemClock.uptimeMillis()
                        }
                        android.view.MotionEvent.ACTION_MOVE -> {
                            params.x = initX + (e.rawX.toInt() - dx)
                            params.y = initY + (e.rawY.toInt() - dy)
                            runCatching { wm.updateViewLayout(ll, params) }
                        }
                        android.view.MotionEvent.ACTION_UP -> {
                            if (android.os.SystemClock.uptimeMillis() - downTime < 150) {
                                // tap (not drag): return to app
                                runCatching {
                                    val launch = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)
                                    if (launch != null) {
                                        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        ctx.startActivity(launch)
                                    }
                                }
                            }
                        }
                    }
                    return true
                }
            })

            runCatching { wm.addView(ll, params) }
                .onSuccess {
                    windowManager = wm
                    currentView = ll
                    currentParams = params
                }
                .onFailure {
                    Toast.makeText(ctx, "悬浮窗显示失败，已用普通前台服务保活", Toast.LENGTH_SHORT).show()
                }
        }
    }

    @JvmStatic
    fun hide(service: Context) {
        val wm = windowManager ?: service.applicationContext
            .getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        synchronized(this) { removeLocked(wm) }
    }

    private fun removeLocked(wm: WindowManager?) {
        val v = currentView
        if (wm != null && v != null) {
            runCatching { wm.removeView(v) }
        }
        currentView = null
        currentParams = null
        windowManager = null
    }
}
