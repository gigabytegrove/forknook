package dev.recipebridge.mobile

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Android Keystore-backed session storage. Android backup is disabled in the manifest. */
internal object SessionVault {
    private const val ALIAS = "forknook-session-v1"
    private const val PREFS = "forknook_native"
    private const val ENCRYPTED = "session_encrypted"

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    fun save(context: Context, token: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val ciphertext = cipher.doFinal(token.toByteArray(StandardCharsets.UTF_8))
        val payload = cipher.iv + ciphertext
        check(prefs.edit()
            .putString(ENCRYPTED, Base64.encodeToString(payload, Base64.NO_WRAP))
            .remove("session").commit()) { "Could not securely save the Forknook session" }
    }

    fun load(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val encrypted = prefs.getString(ENCRYPTED, null)
        if (encrypted != null) {
            return try {
                val bytes = Base64.decode(encrypted, Base64.NO_WRAP)
                require(bytes.size > 28)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
                String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), StandardCharsets.UTF_8)
            } catch (_: Exception) {
                prefs.edit().remove(ENCRYPTED).remove("session").apply()
                ""
            }
        }
        // One-time migration from existing releases. Never leave the old plaintext behind.
        val legacy = prefs.getString("session", "").orEmpty()
        if (legacy.isNotBlank()) save(context, legacy)
        return legacy
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(ENCRYPTED).remove("session").apply()
    }
}
