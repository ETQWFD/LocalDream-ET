package io.github.xororz.localdream.remote

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

/**
 * et.37: lightweight LAN HTTP server (Bug9) that exposes an A1111/OpenAI-compatible
 * image API so a chat app on the same Wi-Fi can drive this phone's local engine.
 *
 * Reuses the blocking HTTP/1.1 skeleton style of [RemoteHostServer] (one response per
 * connection, Connection: close) but adds:
 *  - Bearer / X-API-Key / ?api_key auth (except GET /healthz);
 *  - CORS headers;
 *  - a real generation callback into the local 127.0.0.1:8081 engine.
 *
 * No heavy dependency: plain ServerSocket + a small worker pool.
 */
class LanImageServer(
    private val port: Int,
    // et.40: dynamic provider so a "reset key" takes effect on the very next request
    // without restarting the listener.
    private val authKeyProvider: () -> String,
    private val driver: Driver,
    // et.40: when an external client omits steps/scheduler, inject the user's chosen
    // speed-tier defaults instead of a hardcoded 20.
    private val defaultSteps: Int = 16,
    private val defaultScheduler: String = "dpm",
) {
    /** Bridges this server to the real local engine on 127.0.0.1:8081. Blocking. */
    interface Driver {
        /** Ready iff the native engine has a model loaded and is serving. */
        fun isReady(): Boolean
        fun currentModelId(): String?

        /**
         * Generates exactly [n] images. Returns a list of base64-encoded image bytes
         * (PNG/JPEG). Throws on engine/IO failure. Serialized by the caller.
         */
        fun generate(
            prompt: String,
            negativePrompt: String,
            steps: Int,
            cfg: Float,
            width: Int,
            height: Int,
            seed: Long,
            sampler: String?,
            initImageBase64: String?, // raw base64 (no data: prefix), for img2img
            denoiseStrength: Float,
            n: Int,
        ): List<String>
    }

    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var running = false
    private var acceptThread: Thread? = null
    private var workers = Executors.newFixedThreadPool(WORKER_COUNT)

    @Throws(IOException::class)
    fun start() {
        val socket = ServerSocket(port) // binds all interfaces (0.0.0.0)
        serverSocket = socket
        running = true
        acceptThread = Thread({
            while (running) {
                val client = try {
                    socket.accept()
                } catch (e: IOException) {
                    if (running) Log.w(TAG, "accept failed: ${e.message}")
                    break
                }
                workers.execute { serve(client) }
            }
        }, "lan-image-accept").apply { start() }
        Log.i(TAG, "LAN image server listening on 0.0.0.0:$port")
    }

    fun shutdown() {
        running = false
        try { serverSocket?.close() } catch (_: IOException) {}
        serverSocket = null
        workers.shutdownNow()
        acceptThread = null
        Log.i(TAG, "LAN image server stopped")
    }

    private fun serve(client: Socket) {
        try {
            client.soTimeout = SOCKET_TIMEOUT_MS
            client.use { socket ->
                val input = BufferedInputStream(socket.getInputStream())
                val req = parseRequest(input)
                val resp = if (req == null) {
                    HttpResp(400, error("bad request"))
                } else {
                    route(req)
                }
                writeResponse(socket, resp)
            }
        } catch (e: Exception) {
            Log.w(TAG, "request failed: ${e.message}")
        }
    }

    private data class Request(
        val method: String,
        val path: String,
        val query: String,
        val headers: Map<String, String>,
        val body: ByteArray,
    )

    private fun parseRequest(input: InputStream): Request? {
        val requestLine = readLine(input) ?: return null
        val parts = requestLine.split(" ")
        if (parts.size < 2) return null
        val method = parts[0]
        val rawTarget = parts[1]
        val path = rawTarget.substringBefore('?')
        val query = rawTarget.substringAfter('?', "")

        val headers = HashMap<String, String>()
        var contentLength = 0
        while (true) {
            val line = readLine(input) ?: return null
            if (line.isEmpty()) break
            val idx = line.indexOf(':')
            if (idx < 0) continue
            headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
            if (line.substring(0, idx).trim().lowercase() == "content-length") {
                contentLength = line.substring(idx + 1).trim().toIntOrNull() ?: 0
            }
        }
        if (contentLength in 1..MAX_BODY_BYTES) {
            val bytes = ByteArray(contentLength)
            var read = 0
            while (read < contentLength) {
                val n = input.read(bytes, read, contentLength - read)
                if (n < 0) break
                read += n
            }
            return Request(method, path, query, headers, bytes.copyOf(read))
        }
        return Request(method, path, query, headers, ByteArray(0))
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) break
            if (c != '\r'.code) sb.append(c.toChar())
            if (sb.length > MAX_LINE) return null
        }
        return sb.toString()
    }

    private fun authed(req: Request): Boolean {
        val expected = authKeyProvider()
        val auth = req.headers["authorization"]
        if (auth != null && auth.startsWith("Bearer ", ignoreCase = true)) {
            if (auth.removePrefix("Bearer ").trim() == expected) return true
        }
        req.headers["x-api-key"]?.let { if (it.trim() == expected) return true }
        // ?api_key=
        req.query.split('&').forEach { kv ->
            val e = kv.split('=')
            if (e.size == 2 && e[0] == "api_key" && e[1] == expected) return true
        }
        return false
    }

    private fun route(req: Request): HttpResp {
        // CORS preflight.
        if (req.method == "OPTIONS") return HttpResp(200, JSONObject(), cors = true)

        if (req.method == "GET" && req.path == "/healthz") {
            val ready = driver.isReady()
            val body = JSONObject()
                .put("status", if (ready) "ready" else "starting")
                .put("model", driver.currentModelId())
            return HttpResp(if (ready) 200 else 503, body, cors = true)
        }

        // Everything below requires auth.
        if (!authed(req)) {
            return HttpResp(401, error("missing or invalid API key"), cors = true)
        }

        return when {
            req.method == "GET" && (req.path == "/v1/models" || req.path == "/sdapi/v1/sd-models") -> {
                val id = driver.currentModelId() ?: ""
                val arr = JSONArray().put(
                    JSONObject().put("id", id).put("object", "model"),
                )
                HttpResp(200, JSONObject().put("object", "list").put("data", arr), cors = true)
            }

            req.method == "POST" && req.path == "/sdapi/v1/txt2img" -> handleTxt2img(req)
            req.method == "POST" && req.path == "/sdapi/v1/img2img" -> handleImg2img(req)
            req.method == "POST" && req.path == "/v1/images/generations" -> handleOpenAI(req)

            else -> HttpResp(404, error("not found"), cors = true)
        }
    }

    private fun engineReadyOrError(): HttpResp? =
        if (!driver.isReady()) {
            HttpResp(503, error("engine not ready, try again"), cors = true)
        } else null

    private fun parseBody(req: Request): JSONObject = runCatching {
        if (req.body.isEmpty()) JSONObject() else JSONObject(String(req.body, Charsets.UTF_8))
    }.getOrDefault(JSONObject())

    private fun handleTxt2img(req: Request): HttpResp {
        engineReadyOrError()?.let { return it }
        val b = parseBody(req)
        val prompt = b.optString("prompt", "")
        if (prompt.isBlank()) return HttpResp(400, error("prompt is required"), cors = true)
        val n = b.optInt("batch_size", 1).coerceIn(1, MAX_BATCH)
        val images = runCatching {
            driver.generate(
                prompt = prompt,
                negativePrompt = b.optString("negative_prompt", ""),
                steps = b.optInt("steps", defaultSteps).coerceIn(1, 150),
                cfg = b.optDouble("cfg_scale", 7.0).toFloat(),
                width = b.optInt("width", 512),
                height = b.optInt("height", 512),
                seed = if (b.has("seed")) b.optLong("seed", -1L) else -1L,
                sampler = b.optString("sampler_name", "").ifEmpty { defaultScheduler },
                initImageBase64 = null,
                denoiseStrength = 0.45f,
                n = n,
            )
        }.getOrElse { return HttpResp(502, error("generation failed: ${it.message}"), cors = true) }

        return HttpResp(
            200,
            JSONObject().put("images", JSONArray(images)).put("parameters", b).put("info", "et.37"),
            cors = true,
        )
    }

    private fun handleImg2img(req: Request): HttpResp {
        engineReadyOrError()?.let { return it }
        val b = parseBody(req)
        val prompt = b.optString("prompt", "")
        val initArr = b.optJSONArray("init_images")
        val initB64 = if (initArr != null && initArr.length() > 0) {
            stripDataUri(initArr.optString(0, ""))
        } else null
        if (initB64.isNullOrBlank()) {
            return HttpResp(400, error("init_images[0] is required"), cors = true)
        }
        val n = b.optInt("batch_size", 1).coerceIn(1, MAX_BATCH)
        val images = runCatching {
            driver.generate(
                prompt = prompt,
                negativePrompt = b.optString("negative_prompt", ""),
                steps = b.optInt("steps", defaultSteps).coerceIn(1, 150),
                cfg = b.optDouble("cfg_scale", 7.0).toFloat(),
                width = b.optInt("width", 512),
                height = b.optInt("height", 512),
                seed = if (b.has("seed")) b.optLong("seed", -1L) else -1L,
                sampler = b.optString("sampler_name", "").ifEmpty { defaultScheduler },
                initImageBase64 = initB64,
                denoiseStrength = b.optDouble("denoising_strength", 0.75).toFloat().coerceIn(0.1f, 0.9f),
                n = n,
            )
        }.getOrElse { return HttpResp(502, error("generation failed: ${it.message}"), cors = true) }

        return HttpResp(
            200,
            JSONObject().put("images", JSONArray(images)).put("parameters", b),
            cors = true,
        )
    }

    private fun handleOpenAI(req: Request): HttpResp {
        engineReadyOrError()?.let { return it }
        val b = parseBody(req)
        val prompt = b.optString("prompt", "")
        if (prompt.isBlank()) return HttpResp(400, error("prompt is required"), cors = true)
        val n = b.optInt("n", 1).coerceIn(1, MAX_BATCH)
        val size = b.optString("size", "512x512")
        val (w, h) = parseSize(size)
        val images = runCatching {
            driver.generate(
                prompt = prompt,
                negativePrompt = "",
                steps = b.optInt("steps", defaultSteps).coerceIn(1, 150),
                cfg = b.optDouble("cfg_scale", 7.0).toFloat(),
                width = w,
                height = h,
                seed = -1L,
                sampler = defaultScheduler,
                initImageBase64 = null,
                denoiseStrength = 0.45f,
                n = n,
            )
        }.getOrElse { return HttpResp(502, error("generation failed: ${it.message}"), cors = true) }

        val data = JSONArray()
        images.forEach { data.put(JSONObject().put("b64_json", it)) }
        return HttpResp(
            200,
            JSONObject().put("created", System.currentTimeMillis() / 1000).put("data", data),
            cors = true,
        )
    }

    private fun parseSize(size: String): Pair<Int, Int> {
        val m = Regex("""(\d+)\s*[xX]\s*(\d+)""").find(size) ?: return 512 to 512
        val w = m.groupValues[1].toIntOrNull() ?: 512
        val h = m.groupValues[2].toIntOrNull() ?: 512
        return w.coerceIn(128, 2048) to h.coerceIn(128, 2048)
    }

    private fun stripDataUri(s: String): String =
        if (s.startsWith("data:", true)) s.substringAfter(",", s) else s

    private fun error(message: String): JSONObject = JSONObject().put("error", message)

    private data class HttpResp(val code: Int, val body: JSONObject, val cors: Boolean = false)

    private fun writeResponse(socket: Socket, resp: HttpResp) {
        val bytes = resp.body.toString().toByteArray(StandardCharsets.UTF_8)
        val reason = when (resp.code) {
            200 -> "OK"; 400 -> "Bad Request"; 401 -> "Unauthorized"
            404 -> "Not Found"; 500 -> "Server Error"; 502 -> "Bad Gateway"; 503 -> "Service Unavailable"
            else -> "Error"
        }
        val header = buildString {
            append("HTTP/1.1 ${resp.code} $reason\r\n")
            append("Content-Type: application/json\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("Connection: close\r\n")
            if (resp.cors) append("Access-Control-Allow-Origin: *\r\nAccess-Control-Allow-Methods: GET,POST,OPTIONS\r\nAccess-Control-Allow-Headers: Authorization,X-API-Key,Content-Type\r\n")
            append("\r\n")
        }
        val out = socket.getOutputStream()
        out.write(header.toByteArray(StandardCharsets.ISO_8859_1))
        out.write(bytes)
        out.flush()
    }

    companion object {
        private const val TAG = "LanImageServer"
        private const val WORKER_COUNT = 2
        private const val SOCKET_TIMEOUT_MS = 300_000 // generation can be slow
        private const val MAX_BODY_BYTES = 16 * 1024 * 1024
        private const val MAX_LINE = 16 * 1024
        private const val MAX_BATCH = 8
    }
}
