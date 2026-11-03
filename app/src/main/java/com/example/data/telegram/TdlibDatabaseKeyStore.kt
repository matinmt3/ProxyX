package com.example.data.telegram

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.io.FileNotFoundException
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal class TdlibDatabaseKeyStore(context: Context) {
    private val wrappedKeyFile = AtomicFile(
        context.noBackupFilesDir.resolve(WRAPPED_KEY_FILE_NAME)
    )

    @Synchronized
    fun getOrCreate(): ByteArray {
        val wrapped = readWrappedKey()
        val wrappingKey = getOrCreateWrappingKey()
        if (wrapped != null) {
            return decrypt(wrappingKey, wrapped)
        }
        val databaseKey = ByteArray(DATABASE_KEY_BYTES).also(SecureRandom()::nextBytes)
        val encrypted = encrypt(wrappingKey, databaseKey)
        val stream = wrappedKeyFile.startWrite()
        try {
            stream.write(encrypted)
            wrappedKeyFile.finishWrite(stream)
        } catch (error: Exception) {
            wrappedKeyFile.failWrite(stream)
            throw error
        }
        return databaseKey
    }

    internal fun readWrappedKey(): ByteArray? {
        val input = try {
            // openRead must recover an interrupted AtomicFile write before testing existence.
            wrappedKeyFile.openRead()
        } catch (error: FileNotFoundException) {
            if (wrappedKeyFile.baseFile.exists() ||
                File(wrappedKeyFile.baseFile.path + ".bak").exists()
            ) throw error
            return null
        }
        return input.use {
            val buffer = ByteArray(MAX_WRAPPED_KEY_BYTES + 1)
            var size = 0
            while (size < buffer.size) {
                val count = it.read(buffer, size, buffer.size - size)
                if (count < 0) break
                size += count
            }
            require(size in MIN_WRAPPED_KEY_BYTES..MAX_WRAPPED_KEY_BYTES) {
                "Invalid wrapped TDLib key size. The existing key has not been replaced."
            }
            buffer.copyOf(size)
        }
    }

    private fun getOrCreateWrappingKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build()
            )
            generateKey()
        }
    }

    private fun encrypt(key: SecretKey, value: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, key)
        }
        val encrypted = cipher.doFinal(value)
        return byteArrayOf(cipher.iv.size.toByte()) + cipher.iv + encrypted
    }

    private fun decrypt(key: SecretKey, wrapped: ByteArray): ByteArray {
        require(wrapped.isNotEmpty()) { "Missing wrapped TDLib key." }
        val ivSize = wrapped.first().toInt() and 0xff
        require(ivSize in 12..16 && wrapped.size > ivSize + 1) { "Invalid wrapped TDLib key." }
        val iv = wrapped.copyOfRange(1, 1 + ivSize)
        val encrypted = wrapped.copyOfRange(1 + ivSize, wrapped.size)
        return Cipher.getInstance(TRANSFORMATION).run {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            doFinal(encrypted)
        }.also { keyBytes ->
            require(keyBytes.size == DATABASE_KEY_BYTES) { "Invalid TDLib database key size." }
        }
    }

    private companion object {
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val KEY_ALIAS = "proxyx_tdlib_database_key"
        const val WRAPPED_KEY_FILE_NAME = "tdlib-database-key.bin"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val DATABASE_KEY_BYTES = 32
        const val GCM_TAG_BITS = 128
        const val MIN_WRAPPED_KEY_BYTES = 1 + 12 + DATABASE_KEY_BYTES + GCM_TAG_BITS / 8
        const val MAX_WRAPPED_KEY_BYTES = 1 + 16 + DATABASE_KEY_BYTES + GCM_TAG_BITS / 8
    }
}
