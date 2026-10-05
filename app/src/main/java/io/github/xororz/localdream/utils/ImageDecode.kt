package io.github.xororz.localdream.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File

/**
 * et.30: centralized sampled image decoding. Phone photos are often 3000-4000px;
 * decoding them at full resolution holds tens of MB of ARGB in heap. Every
 * non-essential decode path goes through here so bitmaps are capped to a sane
 * max edge and intermediate bitmaps are recycled.
 */
object ImageDecode {

    /** Decode [uri] scaled so its longest edge <= maxEdge. */
    fun decodeSampledUri(context: Context, uri: Uri, maxEdge: Int): Bitmap? {
        return runCatching {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            }
            val w = opts.outWidth
            val h = opts.outHeight
            if (w <= 0 || h <= 0) return@runCatching null
            var sample = 1
            var longEdge = maxOf(w, h)
            while (longEdge / 2 >= maxEdge) { sample *= 2; longEdge /= 2 }
            opts.inSampleSize = sample
            opts.inJustDecodeBounds = false
            val raw = context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            } ?: return@runCatching null
            scaleToMax(raw, maxEdge)
        }.getOrNull()
    }

    /** Decode a File path scaled so its longest edge <= maxEdge. */
    fun decodeSampledFile(path: String, maxEdge: Int): Bitmap? {
        return runCatching {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, opts)
            val w = opts.outWidth; val h = opts.outHeight
            if (w <= 0 || h <= 0) return@runCatching null
            var sample = 1
            var longEdge = maxOf(w, h)
            while (longEdge / 2 >= maxEdge) { sample *= 2; longEdge /= 2 }
            opts.inSampleSize = sample
            opts.inJustDecodeBounds = false
            val raw = BitmapFactory.decodeFile(path, opts) ?: return@runCatching null
            scaleToMax(raw, maxEdge)
        }.getOrNull()
    }

    private fun scaleToMax(raw: Bitmap, maxEdge: Int): Bitmap {
        val longEdge = maxOf(raw.width, raw.height)
        if (longEdge <= maxEdge) return raw
        val ratio = maxEdge.toFloat() / longEdge
        val scaled = Bitmap.createScaledBitmap(
            raw,
            (raw.width * ratio).toInt().coerceAtLeast(1),
            (raw.height * ratio).toInt().coerceAtLeast(1),
            true,
        )
        if (scaled != raw) raw.recycle()
        return scaled
    }

    /** Encode a bitmap to JPEG base64 (smaller than PNG for photos). */
    fun toJpegBase64(bmp: Bitmap, quality: Int = 92): String {
        val baos = java.io.ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, quality, baos)
        return android.util.Base64.encodeToString(baos.toByteArray(), android.util.Base64.NO_WRAP)
    }
}
