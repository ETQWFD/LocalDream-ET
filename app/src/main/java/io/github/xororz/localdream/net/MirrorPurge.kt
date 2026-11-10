package io.github.xororz.localdream.net

import java.net.HttpURLConnection
import java.net.URL

/**
 * jsDelivr caches a GitHub path on first request. A file uploaded seconds ago
 * therefore 404s on the CDN until its cache is refreshed, which is why freshly
 * posted chat images or gallery works looked "broken" to other users.
 * Purging is fire-and-forget: failing it never affects the upload result.
 */
object MirrorPurge {
    fun jsdelivr(repo: String, path: String) {
        runCatching {
            val url = URL("https://purge.jsdelivr.net/gh/ETQWFD/$repo@main/$path")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 6000
            conn.readTimeout = 6000
            conn.requestMethod = "GET"
            conn.responseCode
            conn.inputStream?.close()
            conn.disconnect()
        }
    }
}
