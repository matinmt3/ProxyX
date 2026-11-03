package com.example.data.telegram

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TransferIntegrityTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun verifiesBytesAndRejectsChangedOrTruncatedDownload() {
        val original = folder.newFile().apply { writeBytes(ByteArray(4096) { it.toByte() }) }
        val downloaded = folder.newFile().apply { writeBytes(original.readBytes()) }
        val digest = TransferIntegrity.digest(original, 4096)
        TransferIntegrity.verify(downloaded, 4096, digest)
        assertArrayEquals(digest, TransferIntegrity.digest(downloaded, 4096))
        downloaded.writeBytes(ByteArray(4096))
        assertThrows(IllegalStateException::class.java) { TransferIntegrity.verify(downloaded, 4096, digest) }
        downloaded.writeBytes(ByteArray(4000))
        assertThrows(IllegalArgumentException::class.java) { TransferIntegrity.verify(downloaded, 4096, digest) }
    }
}
