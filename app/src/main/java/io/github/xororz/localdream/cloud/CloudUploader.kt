package io.github.xororz.localdream.cloud

import android.content.Context
import io.github.xororz.localdream.R
import java.io.File
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * et.35/36: single shared real-upload path used by BOTH the history "cloud" button
 * and the "upload images" multi-select page.
 *
 * There is deliberately NO network pre-check / NET_CAPABILITY_VALIDATED gate / ping
 * probe: on domestic networks and transparent proxies those falsely report "no
 * network" even when GitHub is actually reachable. We just fire the real HTTP
 * request and classify its outcome. No user-facing string ever concatenates the raw
 * exception message (that produced the old "…:?" / "…:null").
 */
object CloudUploader {

    /** Thrown when no PAT / login state exists — callers should open the login UI, not say "no network". */
    class NotLoggedInException : Exception()

    /**
     * Uploads [file] to the logged-in provider's private repo under [destPath]
     * (e.g. "png/202610061530.png"). Creates/ensures the backup repo on first use.
     * Throws [NotLoggedInException] or one of the raw network / [CloudHttpException]
     * failures that [classify] understands.
     */
    suspend fun uploadFile(context: Context, file: File, destPath: String): String {
        val store = SecureTokenStore(context)
        val auth = store.loggedInProviders().firstOrNull() ?: throw NotLoggedInException()
        val provider = auth.provider
        val token = store.accessToken(provider) ?: throw NotLoggedInException()
        // ensurePrivateRepo + uploadContent throw typed/raw exceptions on failure.
        CloudClient.ensurePrivateRepo(provider, token, auth.login)
        val owner = auth.owner ?: auth.login
        val repo = auth.repo ?: CloudConfig.BACKUP_REPO
        return CloudClient.uploadContent(
            provider, token, owner, repo,
            destPath, file.readBytes(), "upload $destPath",
        )
    }

    /** True when a PAT login exists for at least one provider. */
    fun isLoggedIn(context: Context): Boolean =
        SecureTokenStore(context).loggedInProviders().isNotEmpty()

    /**
     * Maps any upload failure to a clean, actionable Chinese string. Never embeds
     * the raw exception message.
     */
    fun classify(context: Context, t: Throwable): String = when (t) {
        is NotLoggedInException -> context.getString(R.string.logs_upload_need_login)
        is CloudHttpException -> when (t.code) {
            401, 403 -> context.getString(R.string.cloud_err_auth)
            404 -> context.getString(R.string.cloud_err_notfound)
            else -> context.getString(R.string.cloud_err_http, t.code)
        }
        is SocketTimeoutException -> context.getString(R.string.cloud_err_timeout)
        is UnknownHostException -> context.getString(R.string.cloud_err_unreachable)
        is ConnectException -> context.getString(R.string.cloud_err_unreachable)
        else -> context.getString(R.string.cloud_err_other)
    }
}
