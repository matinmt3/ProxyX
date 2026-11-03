package com.example.data.telegram

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class TdlibWrappedKeyRecoveryTest {
    @get:Rule val folder = TemporaryFolder()

    private val keyFile get() = File(folder.root, "tdlib-database-key.bin")

    private fun keyStore(): TdlibDatabaseKeyStore = TdlibDatabaseKeyStore(
        object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
            override fun getNoBackupFilesDir(): File = folder.root
        }
    )

    @Test fun missingFileDoesNotCreateAnythingDuringRead() {
        assertNull(keyStore().readWrappedKey())
        assertFalse(keyFile.exists())
    }

    @Test fun backupOnlyIsRecoveredInsteadOfTreatedAsANewLogin() {
        val bytes = ByteArray(61) { it.toByte() }
        val backup = File(keyFile.path + ".bak").apply { writeBytes(bytes) }
        assertFalse(keyFile.exists())

        assertArrayEquals(bytes, keyStore().readWrappedKey())
        assertArrayEquals(bytes, keyFile.readBytes())
        assertFalse(backup.exists())
    }

    @Test fun truncatedKeyIsRejectedWithoutReplacingIt() {
        val bytes = ByteArray(10) { 42 }
        keyFile.writeBytes(bytes)
        assertThrows(IllegalArgumentException::class.java) { keyStore().readWrappedKey() }
        assertArrayEquals(bytes, keyFile.readBytes())
    }

    @Test fun oversizedKeyIsRejectedWithoutReplacingIt() {
        val bytes = ByteArray(66) { 42 }
        keyFile.writeBytes(bytes)
        assertThrows(IllegalArgumentException::class.java) { keyStore().readWrappedKey() }
        assertArrayEquals(bytes, keyFile.readBytes())
    }
}
