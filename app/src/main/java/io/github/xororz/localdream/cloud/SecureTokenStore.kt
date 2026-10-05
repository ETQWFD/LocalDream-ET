package io.github.xororz.localdream.cloud

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Per-provider encrypted credential store. GitHub and Gitee are stored in
 * separate SharedPreferences files so the user can be logged into both at once;
 * saving/reading/logout always targets the given provider and never clobbers
 * the other. Tokens are encrypted at rest with an Android Keystore AES key,
 * never logged, and never leak into diagnostic exports.
 */
class SecureTokenStore(context: Context) {
    private val app = context.applicationContext

    enum class RepoState { READY, PENDING }

    data class CloudAuthState(
        val provider: CloudConfig.Provider,
        val login: String,
        val displayName: String,
        val avatarUrl: String?,
        val owner: String?,
        val repo: String?,
        val repoState: RepoState,
    )

    private fun prefs(provider: CloudConfig.Provider): SharedPreferences =
        app.getSharedPreferences("cloud_secure_${provider.name}", Context.MODE_PRIVATE)

    private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun getOrCreateKey(): SecretKey {
        val ks = keyStore()
        (ks.getEntry(ALIAS, null) as? java.security.KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return gen.generateKey()
    }

    private fun encrypt(plain: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = c.iv
        val ct = c.doFinal(plain.toByteArray(Charsets.UTF_8))
        val out = ByteArray(iv.size + ct.size)
        System.arraycopy(iv, 0, out, 0, iv.size)
        System.arraycopy(ct, 0, out, iv.size, ct.size)
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    private fun decrypt(stored: String): String? = runCatching {
        val all = Base64.decode(stored, Base64.NO_WRAP)
        val iv = all.copyOfRange(0, 12)
        val ct = all.copyOfRange(12, all.size)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
        String(c.doFinal(ct), Charsets.UTF_8)
    }.getOrNull()

    fun saveToken(
        provider: CloudConfig.Provider,
        token: String,
        login: String,
        displayName: String,
        avatarUrl: String? = null,
        owner: String? = null,
        repo: String? = null,
        repoState: RepoState = RepoState.PENDING,
    ) {
        prefs(provider).edit()
            .putString(KEY_TOKEN, encrypt(token))
            .putString(KEY_LOGIN, login)
            .putString(KEY_NAME, displayName)
            .putString(KEY_AVATAR, avatarUrl)
            .putString(KEY_OWNER, owner)
            .putString(KEY_REPO, repo)
            .putString(KEY_REPO_STATE, repoState.name)
            .apply()
    }

    fun accessToken(provider: CloudConfig.Provider): String? =
        prefs(provider).getString(KEY_TOKEN, null)?.let { decrypt(it) }

    /** Full login state for a provider, or null if not logged in. */
    fun state(provider: CloudConfig.Provider): CloudAuthState? {
        val p = prefs(provider)
        val token = p.getString(KEY_TOKEN, null)?.let { decrypt(it) } ?: return null
        val login = p.getString(KEY_LOGIN, null) ?: return null
        return CloudAuthState(
            provider = provider,
            login = login,
            displayName = p.getString(KEY_NAME, login) ?: login,
            avatarUrl = p.getString(KEY_AVATAR, null),
            owner = p.getString(KEY_OWNER, null),
            repo = p.getString(KEY_REPO, null),
            repoState = runCatching {
                RepoState.valueOf(p.getString(KEY_REPO_STATE, RepoState.PENDING.name) ?: RepoState.PENDING.name)
            }.getOrDefault(RepoState.PENDING),
        )
    }

    /** All providers currently logged in (for the settings entry). */
    fun loggedInProviders(): List<CloudAuthState> =
        CloudConfig.Provider.entries.mapNotNull { state(it) }

    fun logout(provider: CloudConfig.Provider) {
        prefs(provider).edit().clear().apply()
    }

    private companion object {
        const val ALIAS = "ldet_cloud_key"
        const val KEY_TOKEN = "access_token"
        const val KEY_LOGIN = "login"
        const val KEY_NAME = "display_name"
        const val KEY_AVATAR = "avatar_url"
        const val KEY_OWNER = "owner"
        const val KEY_REPO = "repo"
        const val KEY_REPO_STATE = "repo_state"
    }
}
