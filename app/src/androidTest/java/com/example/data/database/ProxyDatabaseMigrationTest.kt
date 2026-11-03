package com.example.data.database

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProxyDatabaseMigrationTest {
    @Test
    fun migration15To16AddsWebTableWithoutTouchingTelegramData() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(null)
                .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: SupportSQLiteDatabase) = Unit
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                }).build())
        try {
            val db = helper.writableDatabase
            db.execSQL("CREATE TABLE favorite_proxies (url TEXT PRIMARY KEY, isActive INTEGER)")
            db.execSQL("INSERT INTO favorite_proxies VALUES ('fixture', 1)")
            db.execSQL("CREATE TABLE mtproto_proxies (url TEXT PRIMARY KEY, telegramDownloadKbps INTEGER)")
            db.execSQL("INSERT INTO mtproto_proxies VALUES ('fixture', 5000)")
            ProxyDatabase.MIGRATION_15_16.migrate(db)
            db.query("SELECT isActive FROM favorite_proxies").use { assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0)) }
            db.query("SELECT telegramDownloadKbps FROM mtproto_proxies").use { assertTrue(it.moveToFirst()); assertEquals(5000, it.getInt(0)) }
            db.query("SELECT COUNT(*) FROM web_proxy_nodes").use { assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)) }
        } finally { helper.close() }
    }

    @Test
    fun migration14To15PreservesMeasurementsAndLeavesUnknownFloorsZero() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(null)
                .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: SupportSQLiteDatabase) = Unit
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                }).build())
        try {
            val db = helper.writableDatabase
            db.execSQL("CREATE TABLE mtproto_proxies (url TEXT NOT NULL PRIMARY KEY, telegramDownloadKbps INTEGER NOT NULL, telegramUploadKbps INTEGER NOT NULL)")
            db.execSQL("INSERT INTO mtproto_proxies VALUES ('preserved', 5000, 2000)")
            ProxyDatabase.MIGRATION_14_15.migrate(db)
            db.query("SELECT telegramDownloadKbps, telegramUploadKbps, telegramDownloadFloorKbps, telegramUploadFloorKbps, telegramDownloadPeakKbps, telegramUploadPeakKbps FROM mtproto_proxies WHERE url = 'preserved'").use {
                assertTrue(it.moveToFirst())
                assertEquals(5000, it.getInt(0))
                assertEquals(2000, it.getInt(1))
                for (index in 2..5) assertEquals(0, it.getInt(index))
            }
        } finally { helper.close() }
    }

    @Test
    fun migration13To14PreservesRunAndAddsNetworkBaselines() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(null)
                .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: SupportSQLiteDatabase) = Unit
                    override fun onUpgrade(
                        db: SupportSQLiteDatabase,
                        oldVersion: Int,
                        newVersion: Int
                    ) = Unit
                })
                .build()
        )
        val database = helper.writableDatabase
        try {
            database.execSQL(
                """
                CREATE TABLE benchmark_run_state (
                    id INTEGER NOT NULL PRIMARY KEY,
                    status TEXT NOT NULL,
                    requestedCount INTEGER NOT NULL,
                    selectedWorkers INTEGER NOT NULL,
                    stage INTEGER NOT NULL,
                    stageCompleted INTEGER NOT NULL,
                    stageTotal INTEGER NOT NULL,
                    completedWork INTEGER NOT NULL,
                    totalWork INTEGER NOT NULL,
                    telegramTotal INTEGER NOT NULL,
                    telegramCompleted INTEGER NOT NULL,
                    telegramSucceeded INTEGER NOT NULL,
                    telegramFailed INTEGER NOT NULL,
                    enableTelegramTransfers INTEGER NOT NULL,
                    startedAt INTEGER NOT NULL,
                    updatedAt INTEGER NOT NULL,
                    finishedAt INTEGER NOT NULL,
                    failure TEXT NOT NULL
                )
                """.trimIndent()
            )
            database.execSQL(
                """
                INSERT INTO benchmark_run_state VALUES
                    (1, 'INTERRUPTED', 100, 8, 2, 10, 50, 110, 200, 0, 0, 0, 0, 0,
                     1000, 2000, 0, '')
                """.trimIndent()
            )

            ProxyDatabase.MIGRATION_13_14.migrate(database)

            database.query(
                "SELECT requestedCount, accuracyMode FROM benchmark_run_state WHERE id = 1"
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(100, cursor.getInt(0))
                assertEquals("BALANCED", cursor.getString(1))
            }
            database.query(
                "SELECT name FROM sqlite_master WHERE type = 'table' " +
                    "AND name = 'network_speed_baselines'"
            ).use { cursor -> assertTrue(cursor.moveToFirst()) }
            database.query(
                "SELECT name FROM sqlite_master WHERE type = 'index' " +
                    "AND name = 'index_network_speed_baselines_measuredAt'"
            ).use { cursor -> assertTrue(cursor.moveToFirst()) }
        } finally {
            helper.close()
        }
    }
}
