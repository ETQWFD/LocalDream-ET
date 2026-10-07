package io.github.xororz.localdream.cloud

import android.content.Context
import io.github.xororz.localdream.R
import java.io.File
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * et.35/36/38: single shared real-upload path used by the history "cloud" button,
 * the "upload images" multi-select page, and the log upload.
 *
 * Deliberately NO network pre-check / NET_CAPABILITY_VALIDATED gate / ping probe: on
 * domestic networks and transparent proxies those falsely report "no network" even
 * when GitHub is reachable. We fire the real HTTP request and classify its outcome.
 * No user-facing string ever concatenates the raw exception message.
 *
 * et.38 fix: when the user bound their OWN existing repo (auth.repo set), we no
 * longer call ensurePrivateRepo — that used to POST /user/repos, which a
 * fine-grained token rejects with 403 and blocked the upload to the already-bound
 * repo before it ever started.
 */
object CloudUploader {

    /** Thrown when no PAT / login state exists — callers should open the login UI. */
    class NotLoggedInException : Exception()

    /**
     * et.41: the recorded image file is gone (renamed/moved/cleaned) or empty.
     * Callers surface a clean Chinese message and must NOT retry-spam it.
     */
    class FileMissingException(val path: String) : Exception(path)

    // et.41: idempotency — one in-flight upload per canonical absolute path, so a
    // double-tap / multi-select of the same file does not fire N concurrent attempts.
    private val inFlight = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    /**
     * Resolve the *current* on-disk file for a recorded path. Some history rows point
     * at an old name (generated_image_<ms>_0.png / bare ms.png) that was later moved or
     * renamed; fall back to searching the private history dir for the same stem.
     */
    private fun resolveRealFile(context: Context, recorded: File): File? {
        if (recorded.exists() && recorded.isFile) return recorded
        // Best-effort: search filesDir/history recursively for a png/jpg containing the
        // original file stem (without extension).
        val stem = recorded.nameWithoutExtension
        if (stem.isBlank()) return null
        val root = File(context.filesDir, "history")
        if (root.isDirectory) {
            root.walkTopDown().filter { it.isFile && it.extension.lowercase() in setOf("png", "jpg", "jpeg") }
                .firstOrNull { it.nameWithoutExtension.contains(stem) || stem.contains(it.nameWithoutExtension) }
                ?.let { return it }
        }
        return null
    }

    /**
     * Uploads [file] to the logged-in provider's private repo under [destPath]
     * (e.g. "png/202610061530.png"). Throws [NotLoggedInException] or a typed/raw
     * failure that [classify] understands. Falls back to the other provider when the
     * primary channel is unreachable.
     */
    suspend fun uploadFile(context: Context, file: File, destPath: String): String {
        val real = resolveRealFile(context, file)
            ?: throw FileMissingException(file.absolutePath)
        if (!real.exists() || real.length() <= 0L) throw FileMissingException(real.absolutePath)
        // Idempotent: skip (treat as already running) if this exact file is uploading.
        val key = real.canonicalPath
        if (!inFlight.add(key)) throw FileMissingException("__inflight__")
        try {
            return uploadBytes(context, real.readBytes(), destPath)
        } finally {
            inFlight.remove(key)
        }
    }

    /**
     * Upload raw bytes under [destPath] (e.g. "log/logs_….txt"). Shared by the image
     * upload and the log upload so both get the same retry / fallback / error mapping.
     */
    suspend fun uploadBytes(context: Context, bytes: ByteArray, destPath: String): String {
        val store = SecureTokenStore(context)
        val logins = store.loggedInProviders()
        if (logins.isEmpty()) throw NotLoggedInException()

        val order = logins.map { it.provider } // primary first, then any fallback channel
        var lastError: Throwable? = null
        for (provider in order) {
            val auth = store.state(provider) ?: continue
            val token = store.accessToken(provider) ?: continue
            val owner = auth.owner ?: auth.login
            // et.38: only auto-create the default backup repo when we are actually
            // falling back to it. When the user bound an existing repo, skip the
            // create-repo step entirely (fine-grained tokens can't create repos).
            val repo = auth.repo
            if (repo == null) {
                runCatching { CloudClient.ensurePrivateRepo(provider, token, owner) }
                    .onFailure { lastError = it; if (!isTransient(it)) throw it }
            }
            val targetRepo = repo ?: CloudConfig.BACKUP_REPO
            try {
                return CloudClient.uploadContent(
                    provider, token, owner, targetRepo,
                    destPath, bytes, "upload $destPath",
                )
            } catch (e: Throwable) {
                lastError = e
                // Only fall back to the other channel on a transient network/5xx
                // failure. Auth/permission errors are channel-specific but not worth
                // retrying on the other account.
                if (!isTransient(e)) throw e
                LogHub.log(LogHub.Category.UPLOAD, "primary ${provider.name} failed, trying next: ${e.message}")
            }
        }
        throw lastError ?: NotLoggedInException()
    }

    /** True when a PAT login exists for at least one provider. */
    fun isLoggedIn(context: Context): Boolean =
        SecureTokenStore(context).loggedInProviders().isNotEmpty()

    private fun isTransient(t: Throwable): Boolean = when (t) {
        is SocketTimeoutException, is UnknownHostException, is ConnectException -> true
        is CloudHttpException -> t.code in 500..599
        else -> false
    }

    /**
     * Maps any upload failure to a clean, actionable Chinese string. Never embeds the
     * raw exception message. Distinguishes 401 / 403(fine-grained) / 404 / network.
     */
    fun classify(context: Context, t: Throwable): String = when (t) {
        is NotLoggedInException -> context.getString(R.string.logs_upload_need_login)
        is FileMissingException -> {
            if (t.path == "__inflight__") context.getString(R.string.cloud_err_inflight)
            else context.getString(R.string.cloud_err_file_missing)
        }
        is CloudHttpException -> when (t.code) {
            401 -> context.getString(R.string.cloud_err_401)
            403 -> {
                val d = t.detail.orEmpty().lowercase()
                if (d.contains("fine-grained") || d.contains("resource not accessible") ||
                    d.contains("contents") || d.contains("organization")
                ) {
                    context.getString(R.string.cloud_err_403_finegrained)
                } else {
                    context.getString(R.string.cloud_err_403_generic)
                }
            }
            404 -> context.getString(R.string.cloud_err_notfound)
            else -> context.getString(R.string.cloud_err_http, t.code)
        }
        is SocketTimeoutException -> context.getString(R.string.cloud_err_timeout)
        is UnknownHostException -> context.getString(R.string.cloud_err_unreachable)
        is ConnectException -> context.getString(R.string.cloud_err_unreachable)
        else -> context.getString(R.string.cloud_err_other)
    }
}
