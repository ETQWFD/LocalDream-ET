package io.github.xororz.localdream.cloud

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * et.37: API key for the LAN "local image server" (Bug9).
 *
 * A fresh 46-char random key is generated on first enable, encrypted at rest with an
 * Android Keystore AES/GCM key (same scheme as [SecureTokenStore]), and reused across
 * restarts. Never logged, never printed. resetKey() rotates it.
 */
class ApiKeyStore(context: Context) {
    private val app = context.applicationContext

    private fun prefs(): SharedPreferences =
        app.getSharedPreferences("api_server_secure", Context.MODE_PRIVATE)

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

    /** Returns the existing key, generating a fresh 46-char one on first call. */
    fun getOrCreate(): String {
        prefs().getString(KEY, null)?.let { decrypt(it) }?.let { return it }
        val fresh = randomKey()
        prefs().edit().putString(KEY, encrypt(fresh)).apply()
        return fresh
    }

    fun current(): String? = prefs().getString(KEY, null)?.let { decrypt(it) }

    fun resetKey(): String {
        val fresh = randomKey()
        prefs().edit().putString(KEY, encrypt(fresh)).apply()
        return fresh
    }

    private fun randomKey(): String {
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"
        val rnd = SecureRandom()
        val sb = StringBuilder(KEY_LEN)
        repeat(KEY_LEN) { sb.append(alphabet[rnd.nextInt(alphabet.length)]) }
        return sb.toString()
    }

    private companion object {
        const val ALIAS = "ldet_api_key"
        const val KEY = "api_key"
        const val KEY_LEN = 46
    }
}
