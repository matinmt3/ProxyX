package com.example.data.database

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LegacyDatabaseMigrationTest {
    @Test
    fun fullLegacyChainPreservesFavoriteAndInvalidatesObsoleteMeasurements() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "qa-legacy-${UUID.randomUUID()}.db")
        val url = "tg://proxy?server=migration.invalid&port=443&secret=00112233445566778899aabbccddeeff"

        // Reconstruct the version-one input contract, never the owner's database.
        SQLiteDatabase.openOrCreateDatabase(file, null).use { legacy ->
            legacy.execSQL("""CREATE TABLE mtproto_proxies (
                url TEXT NOT NULL PRIMARY KEY, server TEXT NOT NULL, port INTEGER NOT NULL,
                secret TEXT NOT NULL, country TEXT NOT NULL, countryCode TEXT NOT NULL,
                isWorking INTEGER NOT NULL, latency INTEGER NOT NULL, successRate REAL NOT NULL,
                stability REAL NOT NULL, score INTEGER NOT NULL, isFavorite INTEGER NOT NULL,
                recentlyUsed INTEGER NOT NULL, lastChecked INTEGER NOT NULL)""")
            legacy.execSQL("""INSERT INTO mtproto_proxies VALUES
                (?, 'migration.invalid', 443, '00112233445566778899aabbccddeeff',
                 'Unknown', 'UN', 1, 80, 1, 1, 100, 1, 0, 1000)""", arrayOf(url))
            legacy.version = 1
        }

        val migrations = ProxyDatabase::class.java.declaredFields.mapNotNull { field ->
            if (!Migration::class.java.isAssignableFrom(field.type)) return@mapNotNull null
            field.isAccessible = true
            field.get(null) as Migration
        }.sortedBy { it.startVersion }.toTypedArray()
        assertEquals(15, migrations.size)

        val upgraded = Room.databaseBuilder(context, ProxyDatabase::class.java, file.absolutePath)
            .addMigrations(*migrations)
            .allowMainThreadQueries()
            .build()
        try {
            // Opening also runs Room's complete final-schema validation.
            val db = upgraded.openHelper.writableDatabase
            assertEquals(16, db.version)
            db.query("SELECT isWorking, lastChecked, telegramDownloadKbps, telegramUploadKbps FROM mtproto_proxies WHERE url = ?", arrayOf(url)).use {
                assertTrue(it.moveToFirst())
                for (column in 0..3) assertEquals(0L, it.getLong(column))
            }
            db.query("SELECT isActive, savedMessageId FROM favorite_proxies WHERE url = ?", arrayOf(url)).use {
                assertTrue(it.moveToFirst())
                assertEquals(1, it.getInt(0))
                assertEquals(0L, it.getLong(1))
            }
            assertTrue(db.isDatabaseIntegrityOk)
        } finally {
            upgraded.close()
            check(file.canonicalPath.startsWith(context.cacheDir.canonicalPath + File.separator))
            context.deleteDatabase(file.absolutePath)
        }
    }
}
