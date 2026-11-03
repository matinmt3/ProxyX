package com.example.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.database.ProxyDatabase
import com.example.data.network.*
import com.example.model.MtProtoProxy
import com.example.model.PrivateProxyNode
import com.example.model.FavoriteProxy
import com.example.data.telegram.TelegramTransferResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PrivateNodeOperationTest {
    private lateinit var database: ProxyDatabase
    private lateinit var repository: ProxyRepository

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, ProxyDatabase::class.java)
            .allowMainThreadQueries().build()
        repository = ProxyRepository(database.proxyDao(), ProxyCollector(context),
            ProxyVerifier(context), ProxyCountryResolver(context), NetworkSpeedMeter(null),
            database.sourceHealthDao())
    }

    @After
    fun tearDown() { database.close() }

    @Test
    fun aPrivateTestCannotOverlapAReservedTelegramTransfer() = runTest {
        assertTrue(repository.beginForegroundBenchmark())
        try {
            repository.testPrivateNode("missing-node", 1000)
            fail("The competing private-node test must be rejected.")
        } catch (_: IllegalStateException) {
            assertTrue(repository.isForegroundOperationActive)
            assertFalse(repository.beginForegroundBenchmark())
        } finally { repository.endForegroundBenchmark() }
    }

    @Test
    fun aMissingNodeReleasesTheNetworkReservation() = runTest {
        assertNull(repository.testPrivateNode("missing-node", 1000))
        assertFalse(repository.isForegroundOperationActive)
        assertTrue(repository.beginForegroundBenchmark())
        repository.endForegroundBenchmark()
    }

    @Test
    fun aStorageFailureDoesNotLeaveOtherControlsPermanentlyBusy() = runTest {
        database.close()
        try {
            repository.testPrivateNode("missing-node", 1000)
            fail("Closed storage must fail.")
        } catch (_: IllegalStateException) {
            assertFalse(repository.isForegroundOperationActive)
        }
    }

    @Test
    fun comparisonUsesTheNewestPrivateOrPublicTest() = runTest {
        val proxy = proxy()
        database.proxyDao().insertOrReplace(proxy.copy(lastChecked = 10, latency = 10))
        database.proxyDao().upsertPrivateNode(PrivateProxyNode.fromProxy(proxy, "Test", 1)
            .copy(lastChecked = 20, latency = 20, lastError = "MTPROTO_UNCONFIRMED"))
        val privateResult = repository.observeComparisonProxy(proxy.url).first()!!
        assertEquals(20L, privateResult.lastChecked)
        assertEquals("MTPROTO_UNCONFIRMED", privateResult.benchmarkFailureReason)
        database.proxyDao().insertOrReplace(proxy.copy(lastChecked = 30, latency = 30))
        assertEquals(30L, repository.observeComparisonProxy(proxy.url).first()!!.lastChecked)
    }

    @Test
    fun strictFailureIsExcludedFromPrivateRecoveryRoutes() = runTest {
        val node = PrivateProxyNode.fromProxy(proxy(), "Test", 1).copy(
            lastChecked = 20, latency = 100,
            verificationStatus = MtProtoProxy.VERIFICATION_MTPROTO_VERIFIED,
            lastError = "MTPROTO_UNCONFIRMED")
        database.proxyDao().upsertPrivateNode(node)
        assertTrue(repository.getTelegramRouteRecoveryCandidates(5).isEmpty())
        database.proxyDao().upsertPrivateNode(node.copy(lastError = ""))
        assertEquals(1, repository.getTelegramRouteRecoveryCandidates(5).size)
    }

    @Test
    fun nativeTransferUpdatesFavoriteWithTheActualMeasurementTime() = runTest {
        val proxy = proxy().copy(isWorking = true, latency = 100, lastChecked = 100,
            protocolLatency = 120, protocolSuccessRate = 1f,
            verificationStatus = MtProtoProxy.VERIFICATION_MTPROTO_VERIFIED,
            networkFingerprint = "network")
        val dao = database.proxyDao()
        dao.insertOrReplace(proxy)
        dao.setFavorite(proxy, true, FavoriteProxy.SYNC_LOCAL_ONLY, 100)
        assertTrue(repository.persistTelegramTransfer(TelegramTransferResult(
            proxyUrl = proxy.url, uploadKbps = 1500, downloadKbps = 2500,
            sampleBytes = 1024, uploadDurationMs = 100, downloadDurationMs = 100,
            measuredAt = 200, networkFingerprint = "network", networkType = "WIFI", rounds = 3)))
        val favorite = dao.getFavoriteByUrl(proxy.url)!!
        assertEquals(200L, favorite.lastKnownGoodAt)
        assertEquals(2500, favorite.lastKnownDownloadKbps)
        assertEquals(1500, favorite.lastKnownUploadKbps)
    }

    @Test
    fun anOlderPrivateTestCannotHideANewerCompletePublicTransfer() = runTest {
        val proxy = proxy().copy(lastChecked = 10, isWorking = true, latency = 100,
            protocolLatency = 120, protocolSuccessRate = 1f,
            verificationStatus = MtProtoProxy.VERIFICATION_MTPROTO_VERIFIED,
            telegramTransferMeasuredAt = 30, telegramTransferRounds = 3,
            telegramDownloadKbps = 2500, telegramUploadKbps = 1500)
        database.proxyDao().insertOrReplace(proxy)
        database.proxyDao().upsertPrivateNode(PrivateProxyNode.fromProxy(proxy, "Test", 1)
            .copy(lastChecked = 20, latency = 120))
        assertEquals(2500, repository.observeComparisonProxy(proxy.url).first()!!.telegramDownloadKbps)
        database.proxyDao().insertOrReplace(proxy.copy(telegramTransferRounds = 1))
        assertEquals(20L, repository.observeComparisonProxy(proxy.url).first()!!.lastChecked)
    }

    private fun proxy() = MtProtoProxy("route", "1.1.1.1", 443,
        "0123456789abcdef0123456789abcdef", "Unknown", "XX", false, 0, 0f, 0f)
}
