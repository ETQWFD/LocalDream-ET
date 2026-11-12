package io.github.xororz.localdream.util

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Local, offline image checks used by the 修图 (img2img/inpaint) page.
 *
 * Everything here is plain arithmetic on pixel arrays: no network, no model,
 * no ML inference. The UI therefore calls the results "本地检测" and never
 * claims that an "AI" recognised anything. The goal is to stop the reported
 * "AI 无法识别的问题乱修" behaviour:
 *
 *  - a photo that is already sharp must not be silently re-diffused with a
 *    high strength when the user asked for nothing;
 *  - an empty / tiny brush mask must be reported instead of repainting the
 *    whole picture;
 *  - a result that is visually identical to the input must not pop
 *    "修图完成";
 *  - pixels outside the mask must be provably untouched.
 *
 * Pure functions are kept separate from [Bitmap] so they can be unit-tested on
 * the JVM (see ImageDiagnosticsTest).
 */
object ImageDiagnostics {

    /** Laplacian-variance sharpness score. Higher = sharper; ~0..1000 scale. */
    fun laplacianVariance(pixels: IntArray, width: Int, height: Int): Double {
        if (width < 3 || height < 3) return 0.0
        val n = width * height
        if (pixels.size < n) return 0.0
        val gray = IntArray(n)
        for (i in 0 until n) {
            val c = pixels[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            gray[i] = (r * 299 + g * 587 + b * 114) / 1000
        }
        var sum = 0.0
        var sumSq = 0.0
        var count = 0
        for (y in 1 until height - 1) {
            val row = y * width
            for (x in 1 until width - 1) {
                val i = row + x
                // 4-neighbour Laplacian.
                val lap = (4 * gray[i] - gray[i - 1] - gray[i + 1] - gray[i - width] - gray[i + width]).toDouble()
                sum += lap
                sumSq += lap * lap
                count++
            }
        }
        if (count == 0) return 0.0
        val mean = sum / count
        val variance = sumSq / count - mean * mean
        return if (variance < 0.0) 0.0 else variance
    }

    /** True when the image already has plenty of high-frequency detail. */
    fun isAlreadySharp(score: Double): Boolean = score >= SHARP_VARIANCE

    data class Exposure(
        val meanLuma: Double,
        val overexposedRatio: Double,
        val underexposedRatio: Double,
    ) {
        val isOverexposed: Boolean get() = overexposedRatio >= 0.35
        val isUnderexposed: Boolean get() = underexposedRatio >= 0.35
    }

    fun exposureStats(pixels: IntArray): Exposure {
        if (pixels.isEmpty()) return Exposure(0.0, 0.0, 0.0)
        var sum = 0.0
        var over = 0
        var under = 0
        for (c in pixels) {
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            val luma = (r * 299 + g * 587 + b * 114) / 1000
            sum += luma
            if (luma >= 245) over++
            if (luma <= 10) under++
        }
        val n = pixels.size.toDouble()
        return Exposure(sum / n, over / n, under / n)
    }

    /** Fraction (0..1) of pixels whose alpha is above [alphaThreshold]. */
    fun maskCoverage(alpha: IntArray, alphaThreshold: Int = 24): Double {
        if (alpha.isEmpty()) return 0.0
        var painted = 0
        for (a in alpha) if (a > alphaThreshold) painted++
        return painted.toDouble() / alpha.size.toDouble()
    }

    /** Mean absolute per-channel difference, 0..255. */
    fun meanAbsDiff(a: IntArray, b: IntArray): Double {
        val n = minOf(a.size, b.size)
        if (n == 0) return 0.0
        var sum = 0.0
        for (i in 0 until n) {
            val x = a[i]
            val y = b[i]
            sum += abs(((x shr 16) and 0xFF) - ((y shr 16) and 0xFF)).toDouble()
            sum += abs(((x shr 8) and 0xFF) - ((y shr 8) and 0xFF)).toDouble()
            sum += abs((x and 0xFF) - (y and 0xFF)).toDouble()
        }
        return sum / (n * 3.0)
    }

    /** True when [after] really differs from [before] in a visible way. */
    fun hasVisibleChange(diff: Double): Boolean = diff >= MIN_VISIBLE_DIFF

    // ---- Bitmap helpers -------------------------------------------------

    fun pixelsOf(bitmap: Bitmap): IntArray {
        val w = bitmap.width
        val h = bitmap.height
        val out = IntArray(w * h)
        bitmap.getPixels(out, 0, w, 0, 0, w, h)
        return out
    }

    fun alphaOf(bitmap: Bitmap): IntArray {
        val w = bitmap.width
        val h = bitmap.height
        val out = IntArray(w * h)
        bitmap.getPixels(out, 0, w, 0, 0, w, h)
        for (i in out.indices) out[i] = (out[i] ushr 24) and 0xFF
        return out
    }

    fun sharpnessOf(bitmap: Bitmap): Double = laplacianVariance(pixelsOf(bitmap), bitmap.width, bitmap.height)

    fun exposureOf(bitmap: Bitmap): Exposure = exposureStats(pixelsOf(bitmap))

    fun maskCoverageOf(bitmap: Bitmap): Double = maskCoverage(alphaOf(bitmap))

    /**
     * Force every pixel outside [mask] back to [original]. Returns the original
     * instance when the two bitmaps do not share dimensions (caller must have
     * validated that already) or when the mask has no painted pixels.
     */
    fun preserveOutsideMask(original: Bitmap, result: Bitmap, mask: Bitmap): Bitmap {
        if (original.width != result.width || original.height != result.height) return result
        if (mask.width != original.width || mask.height != original.height) return result
        val w = original.width
        val h = original.height
        val origPixels = pixelsOf(original)
        val resPixels = pixelsOf(result)
        val maskPixels = alphaOf(mask)
        var changed = false
        for (i in maskPixels.indices) {
            if (maskPixels[i] <= MASK_ALPHA_THRESHOLD) {
                if (resPixels[i] != origPixels[i]) {
                    resPixels[i] = origPixels[i]
                    changed = true
                }
            }
        }
        if (!changed) return result
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(resPixels, 0, w, 0, 0, w, h)
        return out
    }

    /** Difference between two same-size images, 0..255. */
    fun diffOf(a: Bitmap, b: Bitmap): Double {
        if (a.width != b.width || a.height != b.height) return 0.0
        return meanAbsDiff(pixelsOf(a), pixelsOf(b))
    }

    /** Nearest supported SD aspect ratio for an arbitrary w/h. */
    fun aspectRatioFor(width: Int, height: Int): String {
        if (width <= 0 || height <= 0) return "1:1"
        val ratio = width.toDouble() / height.toDouble()
        val candidates = listOf(
            "1:1" to 1.0,
            "3:4" to 3.0 / 4.0,
            "4:3" to 4.0 / 3.0,
            "9:16" to 9.0 / 16.0,
            "16:9" to 16.0 / 9.0,
        )
        return candidates.minByOrNull { abs(it.second - ratio) }?.first ?: "1:1"
    }

    fun percent(value: Double): Int = (value * 100).roundToInt()

    /** Sharpness above this = "already sharp" (well above a blurred photo). */
    const val SHARP_VARIANCE = 80.0

    /** Below this mean-abs difference a result counts as untouched. */
    const val MIN_VISIBLE_DIFF = 2.0

    const val MASK_ALPHA_THRESHOLD = 24
    const val MASK_TOO_SMALL = 0.01
    const val MASK_TOO_LARGE = 0.70
}
