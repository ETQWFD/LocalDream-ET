package io.github.xororz.localdream.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the pure pixel math behind the 修图 safety checks.
 * No Android framework classes are touched here.
 */
class ImageDiagnosticsTest {

    private fun checkerboard(size: Int): IntArray {
        val pixels = IntArray(size * size)
        for (y in 0 until size) {
            for (x in 0 until size) {
                val v = if ((x + y) % 2 == 0) 255 else 0
                pixels[y * size + x] = argb(v, v, v)
            }
        }
        return pixels
    }

    private fun flat(size: Int, value: Int): IntArray {
        val pixels = IntArray(size * size)
        for (i in pixels.indices) pixels[i] = argb(value, value, value)
        return pixels
    }

    private fun argb(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    @Test
    fun sharpCheckerboardHasHighLaplacianVariance() {
        val score = ImageDiagnostics.laplacianVariance(checkerboard(32), 32, 32)
        assertTrue("checkerboard score=$score", score > ImageDiagnostics.SHARP_VARIANCE)
        assertTrue(ImageDiagnostics.isAlreadySharp(score))
    }

    @Test
    fun flatColourHasZeroLaplacianVariance() {
        val score = ImageDiagnostics.laplacianVariance(flat(32, 128), 32, 32)
        assertEquals(0.0, score, 0.001)
        assertFalse(ImageDiagnostics.isAlreadySharp(score))
    }

    @Test
    fun tinyImageIsSafe() {
        assertEquals(0.0, ImageDiagnostics.laplacianVariance(IntArray(4), 2, 2), 0.0)
        assertEquals(0.0, ImageDiagnostics.laplacianVariance(IntArray(0), 0, 0), 0.0)
    }

    @Test
    fun exposureDetectsOverexposedImage() {
        val stats = ImageDiagnostics.exposureStats(flat(16, 250))
        assertEquals(250.0, stats.meanLuma, 0.5)
        assertEquals(1.0, stats.overexposedRatio, 0.001)
        assertTrue(stats.isOverexposed)
        assertFalse(stats.isUnderexposed)
    }

    @Test
    fun exposureDetectsUnderexposedImage() {
        val stats = ImageDiagnostics.exposureStats(flat(16, 5))
        assertEquals(1.0, stats.underexposedRatio, 0.001)
        assertTrue(stats.isUnderexposed)
    }

    @Test
    fun exposureOfMixedImageIsBalanced() {
        val pixels = IntArray(100)
        for (i in 0 until 50) pixels[i] = argb(130, 130, 130)
        for (i in 50 until 100) pixels[i] = argb(120, 120, 120)
        val stats = ImageDiagnostics.exposureStats(pixels)
        assertEquals(0.0, stats.overexposedRatio, 0.0)
        assertEquals(0.0, stats.underexposedRatio, 0.0)
    }

    @Test
    fun emptyMaskHasZeroCoverage() {
        assertEquals(0.0, ImageDiagnostics.maskCoverage(IntArray(100)), 0.0)
    }

    @Test
    fun fullyPaintedMaskHasFullCoverage() {
        val alpha = IntArray(100) { 255 }
        assertEquals(1.0, ImageDiagnostics.maskCoverage(alpha), 0.0)
    }

    @Test
    fun smallBrushStrokeIsFlaggedAsTiny() {
        val alpha = IntArray(1000)
        for (i in 0 until 5) alpha[i] = 255
        val coverage = ImageDiagnostics.maskCoverage(alpha)
        assertEquals(0.005, coverage, 1e-9)
        assertTrue(coverage < ImageDiagnostics.MASK_TOO_SMALL)
    }

    @Test
    fun nearlyWholeMaskIsFlaggedAsLarge() {
        val alpha = IntArray(100) { 255 }
        alpha[10] = 0
        alpha[11] = 0
        val coverage = ImageDiagnostics.maskCoverage(alpha)
        assertTrue(coverage > ImageDiagnostics.MASK_TOO_LARGE)
    }

    @Test
    fun identicalImagesHaveNoVisibleChange() {
        val pixels = checkerboard(16)
        assertEquals(0.0, ImageDiagnostics.meanAbsDiff(pixels, pixels), 0.0)
        assertFalse(ImageDiagnostics.hasVisibleChange(ImageDiagnostics.meanAbsDiff(pixels, pixels)))
    }

    @Test
    fun differentImagesProduceVisibleChange() {
        val before = flat(16, 0)
        val after = flat(16, 255)
        val diff = ImageDiagnostics.meanAbsDiff(before, after)
        assertEquals(255.0, diff, 0.5)
        assertTrue(ImageDiagnostics.hasVisibleChange(diff))
    }

    @Test
    fun slightNoiseIsBelowVisibilityThreshold() {
        val before = flat(16, 120)
        val after = flat(16, 121)
        val diff = ImageDiagnostics.meanAbsDiff(before, after)
        assertEquals(1.0, diff, 0.01)
        assertFalse(ImageDiagnostics.hasVisibleChange(diff))
    }

    @Test
    fun aspectRatioSnapsToNearestSupportedValue() {
        assertEquals("1:1", ImageDiagnostics.aspectRatioFor(512, 512))
        assertEquals("3:4", ImageDiagnostics.aspectRatioFor(480, 640))
        assertEquals("4:3", ImageDiagnostics.aspectRatioFor(640, 480))
        assertEquals("9:16", ImageDiagnostics.aspectRatioFor(576, 1024))
        assertEquals("16:9", ImageDiagnostics.aspectRatioFor(1024, 576))
        assertEquals("1:1", ImageDiagnostics.aspectRatioFor(0, 0))
    }

    @Test
    fun percentRounds() {
        assertEquals(45, ImageDiagnostics.percent(0.45))
        assertEquals(70, ImageDiagnostics.percent(0.699))
    }
}
