package com.orchestrator.app.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.orchestrator.app.attempt
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Keeps a secret (the sign-in token, the device's private key) so that only this app on this phone can read it back. */
interface Vault {
    fun seal(plain: String): String

    /** What was sealed, or null when it can't be opened any more (the phone's key is gone: sign in again). */
    fun open(sealed: String): String?
}

/**
 * AES-256-GCM with a key that lives in the Android Keystore and never leaves it: what's written to
 * the app's storage is useless without the phone's secure hardware.
 */
class KeystoreVault(private val alias: String = "samo-vault") : Vault {
    private fun key(): SecretKey {
        val store = KeyStore.getInstance(STORE).apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, STORE).apply { init(spec) }.generateKey()
    }

    @Synchronized
    override fun seal(plain: String): String {
        val cipher = Cipher.getInstance(AES_GCM).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val sealed = cipher.doFinal(plain.toByteArray())
        return Base64.getEncoder().encodeToString(cipher.iv + sealed)
    }

    @Synchronized
    override fun open(sealed: String): String? = attempt {
        val bytes = Base64.getDecoder().decode(sealed)
        val cipher = Cipher.getInstance(AES_GCM).apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, IV)) }
        String(cipher.doFinal(bytes, IV, bytes.size - IV))
    }.getOrNull()

    private companion object {
        const val STORE = "AndroidKeyStore"
        const val AES_GCM = "AES/GCM/NoPadding"
        const val IV = 12
    }
}
