package com.revitvisualizer.app.storage

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.io.IOException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class CredentialStorageException(message: String) : IOException(message)

/** Only ciphertext is written to app-private storage; its AES key stays in Android Keystore. */
class DeviceCredentialStore internal constructor(private val file: AtomicFile, private val keyAlias: String) {
    constructor(context: Context) : this(AtomicFile(File(context.noBackupFilesDir, "openai-credential.enc")), KEY_ALIAS)

    val hasSavedCredential: Boolean
        get() = file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()

    fun save(value: CharArray) = synchronized(lock) {
        require(value.isNotEmpty() && value.size <= MAX_KEY_LENGTH && value.all { it.code in 33..126 })
        val plaintext = ByteArray(value.size) { value[it].code.toByte() }
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, encryptionKey())
            cipher.updateAAD(AAD)
            val encrypted = cipher.doFinal(plaintext)
            check(cipher.iv.size == IV_LENGTH)
            val output = file.startWrite()
            try {
                output.write(VERSION)
                output.write(cipher.iv)
                output.write(encrypted)
                file.finishWrite(output)
            } catch (error: Exception) {
                file.failWrite(output)
                throw error
            }
        } catch (_: Exception) {
            throw CredentialStorageException("The API key could not be remembered on this device. Try again or leave Remember on this device off.")
        } finally { plaintext.fill(0) }
    }

    fun load(): CharArray? = synchronized(lock) {
        if (!hasSavedCredential) return@synchronized null
        try {
            val payload = file.openRead().use { input ->
                val buffer = ByteArray(MAX_PAYLOAD_LENGTH + 1)
                var count = 0
                while (count < buffer.size) {
                    val read = input.read(buffer, count, buffer.size - count)
                    if (read < 0) break
                    count += read
                }
                require(count in (1 + IV_LENGTH + TAG_LENGTH + 1)..MAX_PAYLOAD_LENGTH)
                val bytes = buffer.copyOf(count)
                require(bytes[0].toInt() == VERSION)
                bytes
            }
            // A missing key must never be replaced while trying to decrypt an existing record.
            val key = keyStore().getKey(keyAlias, null) as? SecretKey ?: error("Missing encryption key")
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_LENGTH * 8, payload.copyOfRange(1, 1 + IV_LENGTH)))
            cipher.updateAAD(AAD)
            val plaintext = cipher.doFinal(payload, 1 + IV_LENGTH, payload.size - 1 - IV_LENGTH)
            try {
                require(plaintext.isNotEmpty() && plaintext.size <= MAX_KEY_LENGTH && plaintext.all { it.toInt() in 33..126 })
                CharArray(plaintext.size) { plaintext[it].toInt().toChar() }
            } finally { plaintext.fill(0) }
        } catch (_: Exception) {
            throw CredentialStorageException("The saved API key could not be unlocked. Enter it again or use Clear key to remove it.")
        }
    }

    fun clear() = synchronized(lock) {
        var failed = false
        try { file.delete() } catch (_: Exception) { failed = true }
        try { keyStore().deleteEntry(keyAlias) } catch (_: Exception) { failed = true }
        if (failed || hasSavedCredential) {
            throw CredentialStorageException("The saved API key could not be fully removed. Use Clear key to try again.")
        }
    }

    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun encryptionKey(): SecretKey {
        (keyStore().getKey(keyAlias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build())
            generateKey()
        }
    }

    private companion object {
        val lock = Any()
        const val KEY_ALIAS = "revit-visualizer.openai-credential.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val VERSION = 1
        const val IV_LENGTH = 12
        const val TAG_LENGTH = 16
        const val MAX_KEY_LENGTH = 4096
        const val MAX_PAYLOAD_LENGTH = 1 + IV_LENGTH + TAG_LENGTH + MAX_KEY_LENGTH
        val AAD = "com.revitvisualizer.app/openai-credential/v1".toByteArray(Charsets.US_ASCII)
    }
}
