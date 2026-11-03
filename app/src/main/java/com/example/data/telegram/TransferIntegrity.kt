package com.example.data.telegram

import java.io.File
import java.security.MessageDigest

internal object TransferIntegrity {
    fun digest(file: File, expectedBytes: Long): ByteArray {
        require(expectedBytes > 0 && file.isFile && file.length() == expectedBytes) {
            "The transferred file has an unexpected size."
        }
        val digest = MessageDigest.getInstance("SHA-256")
        var count = 0L
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                count += read
                check(count <= expectedBytes) { "The transferred file grew during verification." }
                digest.update(buffer, 0, read)
            }
        }
        check(count == expectedBytes) { "The transferred file is incomplete." }
        return digest.digest()
    }

    fun verify(file: File, expectedBytes: Long, expectedDigest: ByteArray) {
        check(MessageDigest.isEqual(expectedDigest, digest(file, expectedBytes))) {
            "The downloaded test file does not match the uploaded file."
        }
    }
}
