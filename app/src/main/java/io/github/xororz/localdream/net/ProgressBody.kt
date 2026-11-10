package io.github.xororz.localdream.net

import java.io.IOException
import okhttp3.MediaType
import okhttp3.RequestBody
import okio.Buffer
import okio.BufferedSink

/**
 * JSON (or any) request body that reports how many bytes have actually been
 * written to the socket, so the UI can show a real upload percentage instead of
 * a frozen spinner. Also lets the caller cancel through the owning okhttp Call.
 */
class ProgressBody(
    private val bytes: ByteArray,
    private val contentType: MediaType?,
    private val onProgress: ((Float) -> Unit)? = null,
) : RequestBody() {
    override fun contentType(): MediaType? = contentType

    override fun contentLength(): Long = bytes.size.toLong()

    @Throws(IOException::class)
    override fun writeTo(sink: BufferedSink) {
        val buffer = Buffer().write(bytes)
        val total = bytes.size.toLong().coerceAtLeast(1L)
        var written = 0L
        onProgress?.invoke(0f)
        while (buffer.size > 0) {
            val chunk = minOf(buffer.size, 32L * 1024L)
            sink.write(buffer, chunk)
            written += chunk
            onProgress?.invoke((written.toFloat() / total).coerceIn(0f, 1f))
        }
        sink.flush()
    }
}
