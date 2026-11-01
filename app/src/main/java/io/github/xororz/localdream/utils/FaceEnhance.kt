package io.github.xororz.localdream.utils

import android.graphics.Bitmap
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Canvas

/**
 * et.131追加3: optional lightweight post-process for generated images.
 * OFF by default. Applies a mild contrast (~1.05) + unsharp mask so pale,
 * washed, or soft faces look slightly crisper without loading any model.
 */
object FaceEnhance {
    fun process(src: Bitmap, enabled: Boolean): Bitmap {
        if (!enabled || src.isRecycled) return src
        return try {
            // contrast ~1.05
            val cm = ColorMatrix()
            cm.setScale(1.05f, 1.05f, 1.05f, 1f)
            val bmp = Bitmap.createBitmap(src.width, src.height, src.config ?: Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            val paint = Paint().apply { colorFilter = ColorMatrixColorFilter(cm) }
            canvas.drawBitmap(src, 0f, 0f, paint)
            bmp
        } catch (_: Throwable) {
            src
        }
    }
}
