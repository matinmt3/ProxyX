package com.example.data.network

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SignedCatalogCacheTest {
    @get:Rule val folder = TemporaryFolder()

    private fun context(): Context = object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
        override fun getNoBackupFilesDir(): File = folder.root
    }

    @Test fun backupIsRecoveredWhenBaseFileIsMissing() {
        val context = context()
        val file = File(context.noBackupFilesDir, "source-catalog/verified-envelope.json")
        file.parentFile!!.mkdirs()
        val backup = File(file.path + ".bak")
        backup.writeText("cached-envelope", Charsets.UTF_8)
        assertFalse(file.exists())

        assertEquals("cached-envelope", SignedSourceCatalogClient(context).readCachedEnvelope())
        assertEquals("cached-envelope", file.readText(Charsets.UTF_8))
    }

    @Test fun cacheReadRemainsBoundedAfterRecovery() {
        val context = context()
        val file = File(context.noBackupFilesDir, "source-catalog/verified-envelope.json")
        file.parentFile!!.mkdirs()
        File(file.path + ".bak").writeText("x".repeat(512 * 1024 + 1), Charsets.UTF_8)

        assertThrows(IOException::class.java) {
            SignedSourceCatalogClient(context).readCachedEnvelope()
        }
    }
}
