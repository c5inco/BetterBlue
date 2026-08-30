package com.betterblue.app.data.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject

/**
 * Encrypts/decrypts credential strings for storage. Interfaced so JVM unit
 * tests can substitute a pass-through fake (AndroidKeyStore doesn't exist
 * off-device).
 */
interface CredentialCipher {
    fun encrypt(plaintext: String): String

    fun decrypt(ciphertext: String): String

    fun encryptOrNull(plaintext: String?): String? = plaintext?.let { encrypt(it) }

    fun decryptOrNull(ciphertext: String?): String? = ciphertext?.let { decrypt(it) }
}

/**
 * AndroidKeyStore-backed AES-256-GCM cipher. Each value is encrypted with a
 * fresh random IV, stored as base64(iv || ciphertext). The key never leaves
 * the Keystore. (androidx security-crypto is deprecated, hence hand-rolled.)
 */
class KeystoreCredentialCipher
    @Inject
    constructor() : CredentialCipher {
        private val keyAlias = "betterblue-credentials"

        private fun key(): SecretKey {
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (keyStore.getKey(keyAlias, null) as? SecretKey)?.let { return it }

            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            generator.init(
                KeyGenParameterSpec
                    .Builder(
                        keyAlias,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                    ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setUserAuthenticationRequired(false)
                    .build(),
            )
            return generator.generateKey()
        }

        override fun encrypt(plaintext: String): String {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
            return Base64.getEncoder().encodeToString(cipher.iv + ciphertext)
        }

        override fun decrypt(ciphertext: String): String {
            val blob = Base64.getDecoder().decode(ciphertext)
            require(blob.size > GCM_IV_LENGTH) { "Ciphertext too short" }
            val iv = blob.copyOfRange(0, GCM_IV_LENGTH)
            val payload = blob.copyOfRange(GCM_IV_LENGTH, blob.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            return cipher.doFinal(payload).toString(Charsets.UTF_8)
        }

        private companion object {
            const val GCM_IV_LENGTH = 12
        }
    }

/** Pass-through cipher for JVM tests. */
class PlaintextCredentialCipher : CredentialCipher {
    override fun encrypt(plaintext: String): String = plaintext

    override fun decrypt(ciphertext: String): String = ciphertext
}
