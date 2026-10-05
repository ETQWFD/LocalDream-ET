package io.github.xororz.localdream.cloud

import android.graphics.Bitmap

/**
 * Deterministic structural health check on the decoded RGB result bitmap.
 *
 * Catches latent-space divergence / VAE NaN output ("shattered glass") which
 * shows up as: (a) near-flat image (variance ~ 0), (b) extreme near-white /
 * near-black, or (c) near-pure random noise (very high mean absolute adjacent
 * pixel difference with no large smooth structure). Thresholds are set with
 * margin so normal high-detail photos are never rejected.
 *
 * Returns null when healthy, or a reason string (HEALTH_BAD:...) when bad.
 */
object BitmapHealth {
    fun assess(bmp: Bitmap): String? {
        return runCatching {
            val w = bmp.width
            val h = bmp.height
            if (w <= 0 || h <= 0) return "HEALTH_BAD:empty"
            // Sample every 4th pixel for speed on the generation thread.
            val step = 4
            var n = 0
            var sum = 0.0
            var sumSq = 0.0
            var min = 255
            var max = 0
            val px = IntArray(w * h)
            bmp.getPixels(px, 0, w, 0, 0, w, h)

            // Per-pixel luma accumulation.
            var noiseAcc = 0.0
            var noiseCount = 0
            for (y in 0 until h step step) {
                for (x in 0 until w step step) {
                    val c = px[y * w + x]
                    val r = (c ushr 16) and 0xff
                    val g = (c ushr 8) and 0xff
                    val b = c and 0xff
                    val luma = 0.299 * r + 0.587 * g + 0.114 * b
                    n++
                    sum += luma
                    sumSq += luma * luma
                    if (luma < min) min = luma.toInt()
                    if (luma > max) max = luma.toInt()
                    // Adjacent (right) luma difference as a noise proxy.
                    if (x + step < w) {
                        val c2 = px[y * w + x + step]
                        val l2 = 0.299 * ((c2 ushr 16) and 0xff) +
                            0.587 * ((c2 ushr 8) and 0xff) + 0.114 * (c2 and 0xff)
                        noiseAcc += Math.abs(l2 - luma)
                        noiseCount++
                    }
                }
            }
            if (n == 0) return "HEALTH_BAD:empty"
            val mean = sum / n
            val variance = (sumSq / n) - mean * mean
            val stdDev = Math.sqrt(Math.max(0.0, variance))
            val noise = if (noiseCount > 0) noiseAcc / noiseCount else 0.0

            // Flat: almost no structure.
            if (stdDev < 6.0) return "HEALTH_BAD:flat(std=${String.format("%.1f", stdDev)})"
            // Extreme out of range / clipped.
            if (max < 12 || min > 243) return "HEALTH_BAD:clipped(min=$min,max=$max)"
            // Pure random noise: adjacent luma jumps are very large (>38/255)
            // AND overall std is very high (>55), i.e. no smooth large regions.
            if (noise > 38.0 && stdDev > 55.0) {
                return "HEALTH_BAD:noise(noise=${String.format("%.1f", noise)},std=${String.format("%.1f", stdDev)})"
            }
            null
        }.getOrElse { "HEALTH_BAD:assess_failed:${it.message}" }
    }
}
