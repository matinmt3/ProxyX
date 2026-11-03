package com.example.data.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.model.WebProxyNode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WebProxyDaoTest {
    private lateinit var database: ProxyDatabase
    private val node = WebProxyNode("tg://webproxy?test", "proxy.example.com", "00".repeat(16), 1)
    @Before fun setup() { database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), ProxyDatabase::class.java).allowMainThreadQueries().build() }
    @After fun close() { database.close() }
    @Test fun duplicateImportPreservesExistingMeasurement() = runTest {
        val dao = database.webProxyDao()
        val measured = node.copy(status = "PROTOCOL_VERIFIED", testedAt = 100, stage = 3)
        dao.insert(listOf(measured))
        assertEquals(-1L, dao.insert(listOf(node)).single())
        assertEquals(measured, dao.observeAll().first().single())
    }
    @Test fun deletionCannotBeUndoneByLateTestUpdate() = runTest {
        val dao = database.webProxyDao()
        dao.insert(listOf(node)); dao.delete(node.url); dao.update(node.copy(stage = 4))
        assertTrue(dao.observeAll().first().isEmpty())
    }
    @Test fun allLocalDataDeletionIncludesWebNodes() = runTest {
        database.webProxyDao().insert(listOf(node))
        database.proxyDao().clearAllData()
        assertTrue(database.webProxyDao().observeAll().first().isEmpty())
    }
    @Test fun interruptedProcessCannotLeaveTestingOrOldSpeedsVisible() = runTest {
        val dao = database.webProxyDao()
        dao.insert(listOf(node.copy(status = "TESTING", downloadKbps = 8000), node.copy(url = "verified", status = "PROTOCOL_VERIFIED")))
        dao.markInterrupted()
        val saved = dao.observeAll().first()
        assertEquals("INTERRUPTED", saved.first { it.url == node.url }.status)
        assertEquals(0, saved.first { it.url == node.url }.downloadKbps)
        assertEquals("PROTOCOL_VERIFIED", saved.first { it.url == "verified" }.status)
    }
    @Test fun successfulRefreshDropsOldPublicMetricsAndPreservesManualNodes() = runTest {
        val dao = database.webProxyDao()
        val manual = node.copy(url = "manual")
        val old = node.copy(source = "PUBLIC", status = "TRANSFER_VERIFIED", downloadKbps = 9999)
        dao.insert(listOf(manual, old))
        dao.replacePublic(listOf(node.copy(source = "PUBLIC")))
        val nodes = dao.observeAll().first()
        assertEquals(2, nodes.size)
        assertEquals(manual, nodes.first { it.url == "manual" })
        assertEquals(0, nodes.first { it.url == node.url }.downloadKbps)
    }
    @Test fun migrationCreatesTableWithoutDestroyingOtherData() {
        val sqlite = database.openHelper.writableDatabase
        sqlite.execSQL("DROP TABLE web_proxy_nodes")
        ProxyDatabase.MIGRATION_15_16.migrate(sqlite)
        sqlite.query("SELECT COUNT(*) FROM web_proxy_nodes").use { assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)) }
        sqlite.query("SELECT COUNT(*) FROM favorite_proxies").use { assertTrue(it.moveToFirst()) }
    }
}
