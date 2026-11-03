package com.thassan.bohoman.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.thassan.bohoman.core.network.RefreshTokenStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Only ciphertext is persisted; the AES key remains in Android Keystore. */
class KeystoreRefreshTokenStore(context: Context) : RefreshTokenStore {
    private val preferences = context.getSharedPreferences("bohoman_session", Context.MODE_PRIVATE)
    private val alias = "bohoman_refresh_key_v1"

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build())
            generateKey()
        }
    }

    override suspend fun read(): String? = withContext(Dispatchers.IO) {
        val stored = preferences.getString("refresh", null) ?: return@withContext null
        try {
            val parts = stored.split(':', limit = 2)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
            cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)).toString(Charsets.UTF_8)
        } catch (_: Exception) {
            preferences.edit().remove("refresh").commit()
            null // Device key invalidated or restored ciphertext: require sign-in again.
        }
    }

    override suspend fun write(value: String?) = withContext(Dispatchers.IO) {
        if (value == null) {
            check(preferences.edit().remove("refresh").commit()) { "Could not clear the saved session." }
        } else {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
            val ciphertext = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
            val encoded = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(ciphertext, Base64.NO_WRAP)
            check(preferences.edit().putString("refresh", encoded).commit()) { "Could not securely save the session." }
        }
        Unit
    }
}
