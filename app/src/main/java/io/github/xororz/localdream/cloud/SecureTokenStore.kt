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
 * Stores the OAuth access token encrypted at rest with an Android Keystore AES
 * key (no androidx dependency, works on minSdk 28). The token is never written
 * to plaintext, never logged, and must never leak into diagnostic exports.
 */
class SecureTokenStore(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("cloud_secure", Context.MODE_PRIVATE)

    private companion object {
        const val ALIAS = "ldet_cloud_key"
        const val KEY_TOKEN = "access_token"
        const val KEY_PROVIDER = "provider"
        const val KEY_LOGIN = "login"
        const val KEY_NAME = "display_name"
    }

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

    fun saveToken(provider: CloudConfig.Provider, token: String, login: String, displayName: String) {
        prefs.edit()
            .putString(KEY_TOKEN, encrypt(token))
            .putString(KEY_PROVIDER, provider.name)
            .putString(KEY_LOGIN, login)
            .putString(KEY_NAME, displayName)
            .apply()
    }

    val provider: CloudConfig.Provider? get() =
        prefs.getString(KEY_PROVIDER, null)?.let { runCatching { CloudConfig.Provider.valueOf(it) }.getOrNull() }

    val login: String? get() = prefs.getString(KEY_LOGIN, null)
    val displayName: String? get() = prefs.getString(KEY_NAME, null)

    fun accessToken(): String? =
        prefs.getString(KEY_TOKEN, null)?.let { decrypt(it) }

    val isLoggedIn: Boolean get() = accessToken() != null

    fun logout() {
        prefs.edit().clear().apply()
    }
}
