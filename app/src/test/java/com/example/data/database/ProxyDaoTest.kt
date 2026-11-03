package com.example.data.database

import android.content.Context
import androidx.paging.PagingSource
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.model.MtProtoProxy
import com.example.model.BenchmarkRunRecord
import com.example.model.FavoriteProxy
import com.example.model.ProxySourceHealth
import com.example.model.ProxySourceMembership
import com.example.model.ProxyTestHistory
import com.example.model.NetworkSpeedBaseline
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ProxyDaoTest {
    private lateinit var database: ProxyDatabase
    private lateinit var dao: ProxyDao

    @Test
    fun benchmarkTableChartAndCountsExcludePreviousRuns() = runTest {
        val previous = verifiedProxy("old-run.example.com", "Germany", "DE", 100).copy(lastChecked = 1)
        val current = verifiedProxy("new-run.example.com", "Germany", "DE", 60).copy(lastChecked = 20)
        dao.insertOrReplaceAll(listOf(previous, current))
        assertEquals(1, dao.observeBenchmarkCounts(since = 10).first().testedCount)
        assertEquals(listOf(current.url), dao.observeBenchmarkPreview(10, "", since = 10).first().map { it.url })
        assertEquals(listOf(current.url), dao.observeBenchmarkChart(10, 1000, since = 10).first().map { it.url })
        assertEquals(2, dao.observeBenchmarkCounts().first().testedCount)
    }

    @Test
    fun manualRetestClearsOldSpeedsAndRecordsFailureOnlyOnTheVerifiedNetwork() = runTest {
        val proxy = verifiedProxy("retry.example.com", "Germany", "DE", 80)
            .copy(networkFingerprint = "current", telegramTransferMeasuredAt = 100,
                telegramTransferRounds = 3, telegramDownloadKbps = 9000, telegramUploadKbps = 2000)
        dao.insertOrReplace(proxy)
        assertEquals(0, dao.clearTelegramTransfer(proxy.url, "old"))
        assertEquals(1, dao.clearTelegramTransfer(proxy.url, "current"))
        val cleared = dao.getProxyByUrl(proxy.url)!!
        assertEquals(0L, cleared.telegramTransferMeasuredAt)
        assertEquals(0, cleared.telegramDownloadKbps)
        assertEquals(0, cleared.telegramTransferRounds)
        assertEquals(0, dao.markTelegramTransferFailed(proxy.url, "old"))
        assertEquals(1, dao.markTelegramTransferFailed(proxy.url, "current"))
        assertEquals("TELEGRAM_TRANSFER_FAILED", dao.getProxyByUrl(proxy.url)!!.benchmarkFailureReason)
    }

    @Test
    fun aDifferentNetworkTransferCannotWinCurrentNetworkBestRanking() = runTest {
        val current = verifiedProxy("current-only.example.com", "Germany", "DE", 70)
            .copy(networkFingerprint = "current", benchmarkStage = 2)
        val other = current.copy(url = "other", networkFingerprint = "other", benchmarkStage = 3,
            telegramTransferMeasuredAt = 100, telegramTransferRounds = 3,
            telegramDownloadKbps = 50000, telegramUploadKbps = 20000)
        dao.insertOrReplaceAll(listOf(other, current))
        assertEquals(current.url, loadPage("BEST", "current").first().url)
        assertEquals(current.url, dao.observeBenchmarkPreview(10, "current").first().first().url)
    }

    @Test
    fun realTransferHasPriorityOverStageNumberInHomeAndBenchmarkTable() = runTest {
        val measured = verifiedProxy("native.example.com", "Germany", "DE", 60, stage = 2, rounds = 1)
            .copy(networkFingerprint = "same-network", telegramTransferMeasuredAt = 100, telegramTransferRounds = 3,
                telegramDownloadKbps = 5000, telegramUploadKbps = 2000)
        val unmeasured = verifiedProxy("route-only.example.com", "Germany", "DE", 100)
            .copy(networkFingerprint = "same-network")
        dao.insertOrReplaceAll(listOf(unmeasured, measured))
        assertEquals(measured.url, loadPage("BEST").first().url)
        assertEquals(measured.url, dao.observeBenchmarkPreview(10, "").first().first().url)
        assertEquals(measured.url, dao.observeSmartRecommendation("same-network", 0).first()?.url)
    }

    @Test
    fun pagingAndInMemoryAgreeOnCompleteVersusInvalidTransferEvidence() = runTest {
        val measured = verifiedProxy("valid-native.example.com", "Germany", "DE", 60)
            .copy(telegramTransferMeasuredAt = 100, telegramTransferRounds = 3,
                telegramDownloadKbps = 500, telegramUploadKbps = 200)
        val fast = measured.copy(telegramDownloadKbps = 100000, telegramUploadKbps = 100000)
        val invalid = listOf(fast.copy(url = "stale", lastChecked = 200),
            fast.copy(url = "partial", telegramTransferRounds = 2),
            fast.copy(url = "offline", isWorking = false),
            fast.copy(url = "failed", benchmarkFailureReason = "CONTINUITY_LOST"))
        dao.insertOrReplaceAll(invalid + measured)
        for (mode in listOf("BEST", "HIGH", "DOWN_UP", "DOWNLOAD", "UPLOAD")) {
            assertEquals(mode, measured.url, loadPage(mode).first().url)
            assertEquals(mode, measured.url, com.example.viewmodel.ProxyRanking.sort(
                invalid + measured, com.example.viewmodel.SortBy.valueOf(mode)).first().url)
        }
    }

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, ProxyDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.proxyDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun storesNetworkBaselineAndTracksLatestBenchmarkNetwork() = runTest {
        val baseline = NetworkSpeedBaseline(
            networkFingerprint = "wifi-a",
            networkType = "WIFI",
            isMetered = false,
            downloadKbps = 12_000,
            uploadKbps = 4_000,
            downloadSampleBytes = 1_000_000,
            uploadSampleBytes = 500_000,
            measuredAt = 500L
        )
        dao.upsertNetworkSpeedBaseline(baseline)
        dao.insertProxies(
            listOf(
                proxy("old.example.com").copy(lastChecked = 100L, networkFingerprint = "cell-b"),
                proxy("new.example.com").copy(lastChecked = 600L, networkFingerprint = "wifi-a")
            )
        )

        assertEquals(baseline, dao.observeNetworkSpeedBaseline("wifi-a").first())
        assertEquals("wifi-a", dao.observeLatestBenchmarkNetworkFingerprint().first())
    }

    @Test
    fun refreshStartsFreshAndDropsOldBenchmarkState() = runTest {
        val existing = proxy("shared.example.com").copy(
            url = proxy("shared.example.com").url + "#old-source",
            isWorking = true,
            latency = 120,
            successRate = 1f,
            stability = 0.9f,
            score = 90,
            isFavorite = true,
            recentlyUsed = 1234L,
            lastChecked = 5678L,
            verificationStatus = MtProtoProxy.VERIFICATION_TELEGRAM_READY,
            protocolLatency = 140L,
            protocolSuccessRate = 1f,
            tcpAttempts = 10,
            tcpSuccesses = 10,
            protocolAttempts = 8,
            protocolConfirmations = 8,
            tcpP90Latency = 150L,
            tcpJitter = 4L,
            protocolP90Latency = 180L,
            protocolJitter = 6L
        )
        dao.insertOrReplace(existing)
        dao.insertOrReplace(proxy("removed.example.com"))

        val fresh = proxy("shared.example.com").copy(
            isWorking = true,
            latency = 88,
            successRate = 1f,
            stability = 1f,
            score = 99,
            isFavorite = true,
            recentlyUsed = 9999L,
            lastChecked = 9999L,
            tcpAttempts = 10,
            tcpSuccesses = 10,
            protocolAttempts = 8,
            protocolConfirmations = 8,
            tcpP90Latency = 100L,
            tcpJitter = 3L,
            protocolP90Latency = 120L,
            protocolJitter = 5L
        )
        val inserted = dao.replaceAll(listOf(fresh, proxy("new.example.com")))
        val snapshot = dao.getAllSnapshot()
        val refreshed = dao.getProxyByUrl(fresh.url)!!

        assertEquals(2, inserted)
        assertEquals(2, snapshot.size)
        assertFalse(refreshed.isWorking)
        assertFalse(refreshed.isFavorite)
        assertEquals(0L, refreshed.latency)
        assertEquals(0, refreshed.tcpAttempts)
        assertEquals(0, refreshed.protocolAttempts)
        assertEquals(0L, refreshed.recentlyUsed)
        assertEquals(0L, refreshed.lastChecked)
        assertEquals(MtProtoProxy.VERIFICATION_UNTESTED, refreshed.verificationStatus)
        assertFalse(snapshot.any { it.server == "removed.example.com" })
    }

    @Test
    fun emptyRefreshKeepsExistingCache() = runTest {
        dao.insertOrReplace(proxy("keep.example.com"))

        assertEquals(0, dao.replaceAll(emptyList()))
        assertEquals(1, dao.getAllSnapshot().size)
    }

    @Test
    fun cancelledProgressiveRefreshKeepsCommittedFreshSnapshot() = runTest {
        val previous = proxy("restore.example.com").copy(
            isWorking = true,
            latency = 125,
            successRate = 1f,
            stability = 0.94f,
            score = 92,
            isFavorite = true,
            recentlyUsed = 33L,
            lastChecked = 44L,
            verificationStatus = MtProtoProxy.VERIFICATION_TELEGRAM_READY,
            verificationConfidence = 97,
            protocolLatency = 160L,
            protocolSuccessRate = 1f,
            tcpAttempts = 10,
            tcpSuccesses = 10,
            protocolAttempts = 8,
            protocolConfirmations = 8,
            tcpP90Latency = 170L,
            tcpJitter = 4L,
            protocolP90Latency = 210L,
            protocolJitter = 7L
        )
        dao.insertOrReplace(previous)

        val partial = proxy("partial.example.com")
        dao.replaceAll(listOf(partial))

        assertEquals(listOf(partial.asUntestedExpected()), dao.getAllSnapshot())
        assertFalse(dao.getAllSnapshot().any { it.url == previous.url })
    }

    @Test
    fun progressiveRefreshAppendsFreshChunksWithoutLeakingOldMetrics() = runTest {
        dao.insertOrReplace(proxy("stale.example.com").copy(latency = 90, lastChecked = 10L))

        val firstChunk = listOf(proxy("first.example.com"), proxy("second.example.com"))
        val measuredLaterChunk = listOf(
            proxy("third.example.com").copy(
                isWorking = true,
                latency = 75,
                isFavorite = true,
                lastChecked = 20L,
                verificationStatus = MtProtoProxy.VERIFICATION_TELEGRAM_READY,
                protocolLatency = 80L,
                protocolSuccessRate = 1f,
                tcpAttempts = 10,
                tcpSuccesses = 10,
                protocolAttempts = 8,
                protocolConfirmations = 8,
                tcpP90Latency = 90L,
                tcpJitter = 3L,
                protocolP90Latency = 105L,
                protocolJitter = 4L
            )
        )

        assertEquals(2, dao.replaceAll(firstChunk))
        assertEquals(2, dao.getAllSnapshot().size)
        assertEquals(1, dao.insertFreshChunk(measuredLaterChunk))

        val snapshot = dao.getAllSnapshot()
        val appended = dao.getProxyByUrl(measuredLaterChunk.single().url)!!
        assertEquals(3, snapshot.size)
        assertFalse(snapshot.any { it.server == "stale.example.com" })
        assertFalse(appended.isWorking)
        assertFalse(appended.isFavorite)
        assertEquals(0L, appended.latency)
        assertEquals(0, appended.tcpAttempts)
        assertEquals(0, appended.protocolAttempts)
        assertEquals(0L, appended.lastChecked)
        assertEquals(MtProtoProxy.VERIFICATION_UNTESTED, appended.verificationStatus)
    }

    @Test
    fun benchmarkQueueHasNoOneThousandProxyCap() = runTest {
        val proxies = (1..1_005).map { index -> proxy("proxy-$index.example.com") }
        dao.insertProxies(proxies)

        val queue = dao.getProxiesForBenchmark(Int.MAX_VALUE)

        assertEquals(1_005, queue.size)
        assertEquals(proxies.map { it.url }.toSet(), queue.map { it.url }.toSet())
    }

    @Test
    fun bestPagingDoesNotPromoteAnOfflineFinalistAboveVerifiedEvidence() = runTest {
        val verified = verifiedProxy(
            server = "verified.example.com",
            country = "France",
            countryCode = "FR",
            transferScore = 82,
            stage = 2,
            rounds = 1
        )
        val offlineFinalist = proxy("offline-finalist.example.com").copy(
            lastChecked = 2L,
            verificationStatus = MtProtoProxy.VERIFICATION_OFFLINE,
            transferScore = 100,
            benchmarkStage = 3,
            benchmarkRounds = 3
        )
        dao.insertOrReplaceAll(listOf(offlineFinalist, verified))

        val rows = loadPage(sort = "BEST")

        assertEquals(verified.url, rows.first().url)
        assertEquals(offlineFinalist.url, rows.last().url)
    }


    @Test
    fun bestPagingPlacesStrictlyRejectedEvidenceBelowHealthyEvidence() = runTest {
        val healthy = verifiedProxy(
            server = "healthy-ranking.example.com",
            country = "Germany",
            countryCode = "DE",
            transferScore = 74
        )
        val rejected = verifiedProxy(
            server = "rejected-ranking.example.com",
            country = "Germany",
            countryCode = "DE",
            transferScore = 100
        ).copy(benchmarkFailureReason = "STABILITY_LOW")
        dao.insertOrReplaceAll(listOf(rejected, healthy))

        val rows = loadPage(sort = "BEST")

        assertEquals(healthy.url, rows.first().url)
        assertEquals(rejected.url, rows.last().url)
    }

    @Test
    fun bestPagingKeepsUntestedCandidatesAboveKnownOfflineRows() = runTest {
        val offline = proxy("offline.example.com").copy(
            lastChecked = 2L,
            verificationStatus = MtProtoProxy.VERIFICATION_OFFLINE
        )
        val untested = proxy("untested.example.com")
        dao.insertOrReplaceAll(listOf(offline, untested))

        val rows = loadPage(sort = "BEST")

        assertEquals(listOf(untested.url, offline.url), rows.map(MtProtoProxy::url))
    }

    @Test
    fun bestPagingRanksRejectedVerifiedEvidenceAboveUntestedCandidates() = runTest {
        val verifiedButRejected = verifiedProxy(
            server = "verified-rejected.example.com",
            country = "Germany",
            countryCode = "DE",
            transferScore = 70,
            stage = 3,
            rounds = 3
        ).copy(benchmarkFailureReason = "STABILITY_LOW")
        val untested = proxy("untested-after-verified.example.com")
        dao.insertOrReplaceAll(listOf(untested, verifiedButRejected))

        val rows = loadPage(sort = "BEST")

        assertEquals(
            listOf(verifiedButRejected.url, untested.url),
            rows.map(MtProtoProxy::url)
        )
    }

    @Test
    fun bestPagingUsesNetworkAffinityOnlyWithinTheSameQualityTier() = runTest {
        val currentHealthy = verifiedProxy(
            server = "current-healthy.example.com",
            country = "Germany",
            countryCode = "DE",
            transferScore = 60
        ).copy(networkFingerprint = "current-network")
        val otherHealthy = verifiedProxy(
            server = "other-healthy.example.com",
            country = "Finland",
            countryCode = "FI",
            transferScore = 99
        ).copy(networkFingerprint = "other-network")
        val currentOffline = proxy("current-offline.example.com").copy(
            lastChecked = 10L,
            verificationStatus = MtProtoProxy.VERIFICATION_OFFLINE,
            networkFingerprint = "current-network"
        )
        dao.insertOrReplaceAll(listOf(currentOffline, otherHealthy, currentHealthy))

        val rows = loadPage(sort = "BEST", currentNetworkFingerprint = "current-network")

        assertEquals(
            listOf(currentHealthy.url, otherHealthy.url, currentOffline.url),
            rows.map(MtProtoProxy::url)
        )
    }

    @Test
    fun benchmarkPreviewNeverPromotesCurrentNetworkOfflineEvidence() = runTest {
        val otherHealthy = verifiedProxy(
            server = "preview-healthy.example.com",
            country = "Germany",
            countryCode = "DE",
            transferScore = 80
        ).copy(networkFingerprint = "other-network")
        val currentRejected = verifiedProxy(
            server = "preview-rejected.example.com",
            country = "France",
            countryCode = "FR",
            transferScore = 100
        ).copy(
            networkFingerprint = "current-network",
            benchmarkFailureReason = "STABILITY_LOW"
        )
        val currentOffline = proxy("preview-offline.example.com").copy(
            lastChecked = 10L,
            verificationStatus = MtProtoProxy.VERIFICATION_OFFLINE,
            networkFingerprint = "current-network"
        )
        dao.insertOrReplaceAll(listOf(currentOffline, currentRejected, otherHealthy))

        val rows = dao.observeBenchmarkPreview(10, "current-network").first()

        assertEquals(
            listOf(otherHealthy.url, currentRejected.url, currentOffline.url),
            rows.map(MtProtoProxy::url)
        )
    }

    @Test
    fun downUpPagingUsesOnlyMeasuredTelegramTransferRates() = runTest {
        val fastest = verifiedProxy(
            server = "fast-transfer.example.com",
            country = "Germany",
            countryCode = "DE",
            transferScore = 40
        ).copy(
            telegramDownloadKbps = 9_000,
            telegramUploadKbps = 5_000,
            telegramTransferBytes = 8L * 1024L * 1024L,
            telegramTransferMeasuredAt = 300L,
            telegramTransferRounds = 3,
            benchmarkFailureReason = "STABILITY_LOW"
        )
        val healthierButSlower = verifiedProxy(
            server = "slow-transfer.example.com",
            country = "Finland",
            countryCode = "FI",
            transferScore = 99
        ).copy(
            telegramDownloadKbps = 4_000,
            telegramUploadKbps = 2_000,
            telegramTransferBytes = 8L * 1024L * 1024L,
            telegramTransferMeasuredAt = 200L,
            telegramTransferRounds = 3
        )
        val unmeasured = verifiedProxy(
            server = "unmeasured-transfer.example.com",
            country = "France",
            countryCode = "FR",
            transferScore = 100
        )
        dao.insertOrReplaceAll(listOf(unmeasured, healthierButSlower, fastest))

        val rows = loadPage(sort = "DOWN_UP")

        assertEquals(
            listOf(fastest.url, healthierButSlower.url, unmeasured.url),
            rows.map(MtProtoProxy::url)
        )
    }

    @Test
    fun highPagingKeepsStrictLargeFileEvidenceAboveFasterRejectedRows() = runTest {
        val strict = verifiedProxy(
            server = "strict-high.example.com",
            country = "Germany",
            countryCode = "DE",
            transferScore = 88
        ).copy(
            telegramDownloadKbps = 6_000,
            telegramUploadKbps = 2_000,
            telegramTransferBytes = 8L * 1024L * 1024L,
            telegramTransferMeasuredAt = 300L,
            telegramTransferRounds = 3
        )
        val rejected = verifiedProxy(
            server = "rejected-high.example.com",
            country = "Germany",
            countryCode = "DE",
            transferScore = 100
        ).copy(
            telegramDownloadKbps = 12_000,
            telegramUploadKbps = 5_000,
            telegramTransferBytes = 8L * 1024L * 1024L,
            telegramTransferMeasuredAt = 400L,
            telegramTransferRounds = 3,
            benchmarkFailureReason = "CONTINUITY_LOW"
        )
        dao.insertOrReplaceAll(listOf(rejected, strict))

        val rows = loadPage(sort = "HIGH")

        assertEquals(listOf(strict.url, rejected.url), rows.map(MtProtoProxy::url))
    }

    @Test
    fun filteredCountMatchesSearchAndVerificationFilters() = runTest {
        val verified = verifiedProxy(
            server = "berlin.example.com",
            country = "Germany",
            countryCode = "DE",
            transferScore = 90
        )
        val reachable = proxy("paris.example.com").copy(
            country = "France",
            countryCode = "FR",
            isWorking = true,
            latency = 100L,
            lastChecked = 1L,
            verificationStatus = MtProtoProxy.VERIFICATION_TCP_OPEN
        )
        val untested = proxy("unknown.example.com")
        dao.insertOrReplaceAll(listOf(verified, reachable, untested))

        assertEquals(3, dao.observeFilteredProxyCount("", "ALL").first())
        assertEquals(1, dao.observeFilteredProxyCount("germany", "ALL").first())
        assertEquals(1, dao.observeFilteredProxyCount("", "TELEGRAM_VERIFIED").first())
        assertEquals(2, dao.observeFilteredProxyCount("", "REACHABLE_ONLY").first())
    }

    @Test
    fun countryPagingUsesAggregateQualityWithSampleConfidence() = runTest {
        val singleLuckyResult = verifiedProxy(
            server = "lucky.example.com",
            country = "Germany",
            countryCode = "DE",
            transferScore = 100
        )
        val consistentResults = (1..3).map { index ->
            verifiedProxy(
                server = "consistent-$index.example.com",
                country = "France",
                countryCode = "FR",
                transferScore = 90
            )
        }
        dao.insertOrReplaceAll(listOf(singleLuckyResult) + consistentResults)

        val rows = loadPage(sort = "COUNTRY")

        assertEquals(listOf("FR", "FR", "FR", "DE"), rows.map(MtProtoProxy::countryCode))
    }

    @Test
    fun benchmarkQueuePrioritizesUntestedThenOldestAndPruneSkipsUntested() = runTest {
        val untested = proxy("untested.example.com")
        val oldest = proxy("oldest.example.com").copy(lastChecked = 100L, isWorking = true)
        val newest = proxy("newest.example.com").copy(lastChecked = 300L, isWorking = true)
        val dead = proxy("dead.example.com").copy(
            lastChecked = 200L,
            isWorking = false,
            verificationStatus = MtProtoProxy.VERIFICATION_OFFLINE
        )
        dao.insertProxies(listOf(newest, dead, oldest, untested))

        val queue = dao.getProxiesForBenchmark(3)
        val deleted = dao.pruneDeadProxies()

        assertEquals(listOf("untested.example.com", "oldest.example.com", "dead.example.com"), queue.map { it.server })
        assertEquals(1, deleted)
        assertTrue(dao.getAllSnapshot().any { it.server == "untested.example.com" })
    }

    @Test
    fun smallBenchmarkIncludesConfirmedRoutesButNotPortOnlyOrFailedRoutesFirst() = runTest {
        val confirmed = verifiedProxy("confirmed.example.com", "Unknown", "XX", 70)
            .copy(lastChecked = 500)
        val tcpOnly = proxy("tcp-only.example.com").copy(lastChecked = 100, isWorking = true,
            verificationStatus = MtProtoProxy.VERIFICATION_TCP_OPEN)
        val failed = confirmed.copy(url = "failed", server = "failed.example.com",
            lastChecked = 50, benchmarkFailureReason = "CONTINUITY_LOST")
        dao.insertProxies(listOf(proxy("untested.example.com"), failed, tcpOnly, confirmed))
        assertEquals(listOf("confirmed.example.com", "untested.example.com"),
            dao.getProxiesForBenchmark(2).map { it.server })
        assertEquals(4, dao.getProxiesForBenchmark(Int.MAX_VALUE).size)
    }

    @Test
    fun successfulSnapshotReplacementClearsPreviousBenchmarkJournal() = runTest {
        dao.upsertBenchmarkRun(benchmarkRun("COMPLETED"))
        dao.replaceAll(listOf(proxy("fresh.example.com")))
        assertEquals(null, dao.observeBenchmarkRun().first())
    }

    @Test
    fun pruneRemovesMembershipRowsForDeletedProxies() = runTest {
        val dead = proxy("dead-member.example.com").copy(
            lastChecked = 1L,
            isWorking = false,
            verificationStatus = MtProtoProxy.VERIFICATION_OFFLINE
        )
        val live = proxy("live-member.example.com").copy(lastChecked = 1L, isWorking = true)
        val memberships = listOf(
            ProxySourceMembership("source", dead.url),
            ProxySourceMembership("source", live.url)
        )
        dao.insertOrReplaceAll(listOf(dead, live))
        dao.insertSourceMemberships(memberships)

        assertEquals(1, dao.pruneDeadProxies())

        assertEquals(
            listOf(ProxySourceMembership("source", live.url)),
            database.sourceHealthDao().getAllMemberships()
        )
    }

    @Test
    fun prunePreservesInconclusiveNetworkMeasurements() = runTest {
        val inconclusive = proxy("temporary-network-error.example.com").copy(
            lastChecked = 1L,
            isWorking = false,
            verificationStatus = MtProtoProxy.VERIFICATION_INCONCLUSIVE
        )
        dao.insertProxies(listOf(inconclusive))

        assertEquals(0, dao.pruneDeadProxies())
        assertEquals(inconclusive.url, dao.getAllSnapshot().single().url)
    }

    @Test
    fun clearAllDataRemovesProxiesMembershipsAndSourceHealth() = runTest {
        val stored = proxy("clear.example.com")
        dao.replaceAll(
            listOf(stored),
            listOf(ProxySourceMembership("source", stored.url))
        )
        database.sourceHealthDao().upsert(
            ProxySourceHealth(sourceUrl = "source", sourceName = "Source")
        )
        dao.setFavorite(stored, true, FavoriteProxy.SYNC_PENDING, now = 10L)
        dao.insertTestHistories(listOf(ProxyTestHistory.fromProxy(stored, measuredAt = 11L)))
        dao.upsertBenchmarkRun(benchmarkRun(status = "RUNNING"))

        assertEquals(1, dao.clearAllData())

        assertTrue(dao.getAllSnapshot().isEmpty())
        assertTrue(database.sourceHealthDao().getAllMemberships().isEmpty())
        assertTrue(database.sourceHealthDao().getAll().isEmpty())
        assertEquals(null, dao.getFavoriteByUrl(stored.url))
        assertTrue(dao.getTestHistory(stored.url, 10).isEmpty())
        assertEquals(null, dao.getBenchmarkRun())
    }

    @Test
    fun emptyDatabaseHasZeroBenchmarkSummary() = runTest {
        val summary = dao.observeBenchmarkCounts().first()

        assertEquals(0, summary.testedCount)
        assertEquals(0, summary.readyCount)
        assertEquals(0, summary.verifiedCount)
        assertEquals(0, summary.tcpOnlyCount)
    }

    @Test
    fun cancelledRefreshKeepsMembershipForCommittedFreshSnapshot() = runTest {
        val old = proxy("old.example.com")
        val fresh = proxy("fresh.example.com")
        val oldMembership = ProxySourceMembership("old-source", old.url)
        val freshMembership = ProxySourceMembership("fresh-source", fresh.url)

        dao.replaceAll(listOf(old), listOf(oldMembership))
        dao.replaceAll(listOf(fresh), listOf(freshMembership))

        assertEquals(listOf(fresh.asUntestedExpected()), dao.getAllSnapshot())
        assertEquals(listOf(freshMembership), database.sourceHealthDao().getAllMemberships())
    }

    @Test
    fun realTelegramTransferUpdatesOnlyTheMeasuredProxy() = runTest {
        val measured = proxy("measured.example.com").copy(
            networkFingerprint = "42:WIFI",
            transferScore = 91,
            benchmarkStage = 3,
            benchmarkRounds = 3
        )
        val untouched = proxy("untouched.example.com")
        dao.insertOrReplaceAll(listOf(measured, untouched))

        val updated = dao.updateTelegramTransfer(
            url = measured.url,
            downloadKbps = 5_120,
            uploadKbps = 2_048,
            sampleBytes = 4L * 1024L * 1024L,
            measuredAt = 123_456L,
            rounds = 2,
            confidence = 93,
            networkUploadBytes = 4_300_000L,
            networkDownloadBytes = 4_500_000L,
            networkFingerprint = "42:WIFI",
            networkType = "WIFI"
        )

        val stored = dao.getProxyByUrl(measured.url)!!
        val other = dao.getProxyByUrl(untouched.url)!!
        assertEquals(1, updated)
        assertEquals(5_120, stored.telegramDownloadKbps)
        assertEquals(2_048, stored.telegramUploadKbps)
        assertEquals(4L * 1024L * 1024L, stored.telegramTransferBytes)
        assertEquals(123_456L, stored.telegramTransferMeasuredAt)
        assertEquals(2, stored.telegramTransferRounds)
        assertEquals(93, stored.telegramTransferConfidence)
        assertEquals(4_300_000L, stored.telegramNetworkUploadBytes)
        assertEquals(4_500_000L, stored.telegramNetworkDownloadBytes)
        assertEquals("42:WIFI", stored.networkFingerprint)
        assertEquals("WIFI", stored.testedNetworkType)
        assertEquals(91, stored.transferScore)
        assertEquals(0L, other.telegramTransferMeasuredAt)
    }

    @Test
    fun transferCannotRelabelProtocolEvidenceFromAnotherNetwork() = runTest {
        val measured = proxy("network-bound.example.com").copy(networkFingerprint = "old")
        dao.insertOrReplace(measured)
        val updated = dao.updateTelegramTransfer(measured.url, 1000, 500, 1024, 123,
            3, 80, 2048, 2048, "new", "WIFI")
        assertEquals(0, updated)
        val stored = dao.getProxyByUrl(measured.url)!!
        assertEquals("old", stored.networkFingerprint)
        assertEquals(0, stored.telegramDownloadKbps)
    }

    @Test
    fun delayedTransferCannotOverwriteANewerMeasurementOnTheSameNetwork() = runTest {
        val measured = proxy("out-of-order.example.com").copy(networkFingerprint = "same",
            lastChecked = 100, telegramTransferMeasuredAt = 300, telegramDownloadKbps = 9000)
        dao.insertOrReplace(measured)
        assertEquals(0, dao.updateTelegramTransfer(measured.url, 1000, 500, 1024, 200,
            3, 80, 2048, 2048, "same", "WIFI"))
        assertEquals(9000, dao.getProxyByUrl(measured.url)!!.telegramDownloadKbps)
    }

    @Test
    fun delayedTransferCannotAttachToNewerProtocolEvidence() = runTest {
        val measured = proxy("new-protocol.example.com").copy(networkFingerprint = "same", lastChecked = 300)
        dao.insertOrReplace(measured)
        assertEquals(0, dao.updateTelegramTransfer(measured.url, 1000, 500, 1024, 200,
            3, 80, 2048, 2048, "same", "WIFI"))
    }

    @Test
    fun recommendationDoesNotFallBackToStaleOrUnknownNetwork() = runTest {
        val old = verifiedProxy("stale.example.com", "Germany", "DE", 99)
            .copy(lastChecked = 10, networkFingerprint = "old")
        dao.insertOrReplace(old)
        assertEquals(null, dao.observeSmartRecommendation("new", 1).first())
        assertEquals(null, dao.observeSmartRecommendation("old", 20).first())
        assertEquals(null, dao.observeSmartRecommendation("", 1).first())
    }

    @Test
    fun transferSortsPreferMeasuredFloorOverABurst() = runTest {
        val stable = verifiedProxy("stable-floor.example.com", "Germany", "DE", 85)
            .copy(telegramTransferMeasuredAt = 100, telegramTransferRounds = 3,
                telegramDownloadKbps = 6000, telegramUploadKbps = 6000,
                telegramDownloadFloorKbps = 5000, telegramUploadFloorKbps = 5000)
        val burst = stable.copy(url = "tg://proxy/burst", server = "burst.example.com",
            telegramDownloadKbps = 10000, telegramUploadKbps = 10000,
            telegramDownloadFloorKbps = 100, telegramUploadFloorKbps = 100)
        dao.insertOrReplaceAll(listOf(burst, stable))
        for (sort in listOf("BEST", "HIGH", "DOWN_UP", "DOWNLOAD", "UPLOAD")) {
            assertEquals(sort, stable.url, loadPage(sort = sort).first().url)
        }
    }

    @Test
    fun refreshRemovesEveryOldTelegramTransferMeasurement() = runTest {
        val old = proxy("same.example.com").copy(
            telegramDownloadKbps = 8_000,
            telegramUploadKbps = 3_000,
            telegramTransferBytes = 8L * 1024L * 1024L,
            telegramTransferMeasuredAt = 777L,
            networkFingerprint = "old-network",
            testedNetworkType = "CELLULAR"
        )
        dao.insertOrReplace(old)

        dao.replaceAll(listOf(old))

        val refreshed = dao.getProxyByUrl(old.url)!!
        assertEquals(0, refreshed.telegramDownloadKbps)
        assertEquals(0, refreshed.telegramUploadKbps)
        assertEquals(0L, refreshed.telegramTransferBytes)
        assertEquals(0L, refreshed.telegramTransferMeasuredAt)
        assertEquals("", refreshed.networkFingerprint)
        assertEquals("", refreshed.testedNetworkType)
    }

    @Test
    fun authorizationCandidatesPreferReadyAndExcludeUntestedRoutes() = runTest {
        val ready = verifiedProxy(
            server = "ready.example.com",
            country = "Germany",
            countryCode = "DE",
            transferScore = 82
        )
        val protocolVerified = verifiedProxy(
            server = "verified.example.com",
            country = "Finland",
            countryCode = "FI",
            transferScore = 99
        ).copy(verificationStatus = MtProtoProxy.VERIFICATION_MTPROTO_VERIFIED)
        val untested = proxy("untested.example.com").copy(
            isWorking = true,
            latency = 40L,
            lastChecked = 100L,
            protocolLatency = 50L,
            protocolSuccessRate = 1f,
            verificationStatus = MtProtoProxy.VERIFICATION_UNTESTED
        )
        val offline = ready.copy(
            url = ready.url + "#offline",
            server = "offline.example.com",
            isWorking = false
        )
        dao.insertOrReplaceAll(listOf(protocolVerified, untested, offline, ready))

        val candidates = dao.getTelegramAuthorizationCandidates(limit = 10)

        assertEquals(listOf(ready.url, protocolVerified.url), candidates.map(MtProtoProxy::url))
    }

    @Test
    fun routeRecoveryCandidatesKeepRealProtocolEvidenceDespiteStrictRejection() = runTest {
        val strict = verifiedProxy(
            server = "strict.example.com",
            country = "Germany",
            countryCode = "DE",
            transferScore = 80
        )
        val partialProtocol = proxy("partial.example.com").copy(
            isWorking = true,
            latency = 210L,
            successRate = 0.34f,
            stability = 0.4f,
            lastChecked = 20L,
            verificationStatus = MtProtoProxy.VERIFICATION_TCP_OPEN,
            protocolLatency = 280L,
            protocolSuccessRate = 1f,
            protocolAttempts = 2,
            protocolConfirmations = 2,
            benchmarkFailureReason = "TCP_SUCCESS_RATE_LOW"
        )
        val tcpOnly = proxy("tcp-only.example.com").copy(
            isWorking = true,
            latency = 90L,
            successRate = 1f,
            lastChecked = 30L,
            verificationStatus = MtProtoProxy.VERIFICATION_TCP_OPEN
        )
        val untested = proxy("untested-route.example.com")
        dao.insertOrReplaceAll(listOf(untested, tcpOnly, partialProtocol, strict))

        val candidates = dao.getTelegramRouteRecoveryCandidates(limit = 10)

        assertEquals(
            listOf(strict.url, partialProtocol.url, tcpOnly.url, untested.url),
            candidates.map(MtProtoProxy::url)
        )
    }

    @Test
    fun favoriteVaultSurvivesRefreshWithoutLeakingOldMeasurements() = runTest {
        val favorite = verifiedProxy(
            server = "favorite.example.com",
            country = "Germany",
            countryCode = "DE",
            transferScore = 94
        ).copy(
            telegramDownloadKbps = 8_000,
            telegramUploadKbps = 4_000,
            telegramTransferBytes = 4L * 1024L * 1024L,
            telegramTransferMeasuredAt = 55L
        )
        dao.insertOrReplace(favorite)
        dao.setFavorite(favorite, true, FavoriteProxy.SYNC_PENDING, now = 100L)
        dao.markFavoriteSynced(favorite.url, messageId = 9_001L, updatedAt = 101L)

        dao.replaceAll(listOf(proxy("fresh.example.com")))
        assertEquals(1, dao.restoreActiveFavorites())

        val restored = dao.getProxyByUrl(favorite.url)!!
        val vault = dao.getFavoriteByUrl(favorite.url)!!
        assertTrue(restored.isFavorite)
        assertFalse(restored.isWorking)
        assertEquals(0L, restored.lastChecked)
        assertEquals(0, restored.transferScore)
        assertEquals(0L, restored.telegramTransferMeasuredAt)
        assertTrue(vault.isActive)
        assertEquals(9_001L, vault.savedMessageId)
        assertEquals(FavoriteProxy.SYNC_SYNCED, vault.syncState)
    }

    @Test
    fun importingFavoritePreservesMeasuredCountryAndTelegramIdentity() = runTest {
        val measured = verifiedProxy("imported.example.com", "Germany", "DE", 90)
            .copy(telegramDownloadKbps = 1_200, telegramUploadKbps = 800,
                telegramTransferMeasuredAt = 2, telegramTransferRounds = 3)
        dao.insertOrReplace(measured)
        dao.setFavorite(measured, true, FavoriteProxy.SYNC_PENDING, now = 10L)
        dao.markFavoriteSynced(measured.url, messageId = 77L, updatedAt = 11L)

        assertTrue(dao.importFavorite(proxy(measured.server), "Work", "fast", "Saved", now = 12L))

        val imported = dao.getFavoriteByUrl(measured.url)!!
        assertEquals("DE", imported.countryCode)
        assertEquals("Germany", imported.country)
        assertEquals(FavoriteProxy.SYNC_SYNCED, imported.syncState)
        assertEquals(77L, imported.savedMessageId)
        assertEquals(1_200, imported.lastKnownDownloadKbps)
        assertEquals(2L, imported.lastKnownGoodAt)
        assertEquals("Work", imported.folder)
        assertEquals("fast", imported.tags)
        assertEquals("Saved", imported.note)
        assertTrue(dao.getProxyByUrl(measured.url)!!.isFavorite)
    }

    @Test
    fun importingFavoriteKeepsArchivedCountryWhenPoolHasNoEvidence() = runTest {
        val measured = verifiedProxy("archived-import.example.com", "Finland", "FI", 85)
        dao.setFavorite(measured, true, FavoriteProxy.SYNC_FAILED, now = 10L)
        dao.markFavoriteSyncFailed(measured.url, "ROUTE_UNAVAILABLE", updatedAt = 11L)
        dao.insertOrReplace(proxy(measured.server))

        dao.importFavorite(proxy(measured.server), "Favorites", "", "", now = 12L)

        val imported = dao.getFavoriteByUrl(measured.url)!!
        assertEquals("FI", imported.countryCode)
        assertEquals(FavoriteProxy.SYNC_FAILED, imported.syncState)
        assertEquals("ROUTE_UNAVAILABLE", imported.lastSyncError)
        assertEquals(85, imported.lastKnownTransferScore)
    }

    @Test
    fun reactivatingFavoriteKeepsVerifiedSavedMessageIdentity() = runTest {
        val proxy = proxy("favorite-idempotent.example.com")
        dao.insertOrReplace(proxy)
        dao.setFavorite(proxy, true, FavoriteProxy.SYNC_PENDING, now = 10L)
        dao.markFavoriteSynced(proxy.url, messageId = 77L, updatedAt = 11L)

        dao.setFavorite(proxy, false, FavoriteProxy.SYNC_LOCAL_ONLY, now = 12L)
        val inactive = dao.getFavoriteByUrl(proxy.url)!!
        assertFalse(inactive.isActive)
        assertEquals(77L, inactive.savedMessageId)

        val reactivated = dao.setFavorite(
            proxy,
            true,
            FavoriteProxy.SYNC_PENDING,
            now = 13L
        )
        assertTrue(reactivated.isActive)
        assertEquals(77L, reactivated.savedMessageId)
    }

    @Test
    fun testHistorySurvivesSourceRefreshAndKeepsNetworkIdentity() = runTest {
        val measured = verifiedProxy(
            server = "history.example.com",
            country = "Finland",
            countryCode = "FI",
            transferScore = 88
        ).copy(
            lastChecked = 500L,
            networkFingerprint = "network-a",
            testedNetworkType = "CELLULAR"
        )
        dao.persistBenchmarkResults(
            proxies = listOf(measured),
            history = listOf(ProxyTestHistory.fromProxy(measured))
        )

        dao.replaceAll(listOf(proxy("fresh-history.example.com")))

        val history = dao.getTestHistory(measured.url, 10)
        assertEquals(1, history.size)
        assertEquals("network-a", history.single().networkFingerprint)
        assertEquals("CELLULAR", history.single().networkType)
        assertEquals(88, history.single().transferScore)
    }

    @Test
    fun smartRecommendationPrefersCurrentNetworkRealTransferEvidence() = runTest {
        val now = System.currentTimeMillis()
        val currentNetwork = verifiedProxy(
            server = "current-network.example.com",
            country = "Germany",
            countryCode = "DE",
            transferScore = 82
        ).copy(
            lastChecked = now,
            networkFingerprint = "current-network",
            telegramDownloadKbps = 6_000,
            telegramUploadKbps = 2_500,
            telegramTransferMeasuredAt = now
        )
        val otherNetwork = verifiedProxy(
            server = "other-network.example.com",
            country = "Netherlands",
            countryCode = "NL",
            transferScore = 100
        ).copy(lastChecked = now, networkFingerprint = "other-network")
        dao.insertOrReplaceAll(listOf(otherNetwork, currentNetwork))

        val recommended = dao.observeSmartRecommendation(
            networkFingerprint = "current-network",
            freshAfter = now - 1_000L
        ).first()

        assertEquals(currentNetwork.url, recommended?.url)
    }

    @Test
    fun orphanedBenchmarkIsMarkedInterrupted() = runTest {
        dao.upsertBenchmarkRun(benchmarkRun(status = "RUNNING"))

        assertEquals(1, dao.markOrphanedBenchmarkInterrupted(now = 99L))

        val interrupted = dao.getBenchmarkRun()!!
        assertEquals("INTERRUPTED", interrupted.status)
        assertEquals("PROCESS_INTERRUPTED", interrupted.failure)
        assertEquals(2L, interrupted.updatedAt)
        assertEquals(99L, interrupted.finishedAt)
    }

    private fun proxy(server: String): MtProtoProxy {
        return requireNotNull(
            MtProtoProxy.parseFromUrl(
                "tg://proxy?server=$server&port=443&secret=dd79e344818749bd7ac519130220c25d09"
            )
        )
    }

    private fun verifiedProxy(
        server: String,
        country: String,
        countryCode: String,
        transferScore: Int,
        stage: Int = 3,
        rounds: Int = 3
    ): MtProtoProxy {
        return proxy(server).copy(
            country = country,
            countryCode = countryCode,
            isWorking = true,
            latency = 140L,
            successRate = 1f,
            stability = 0.96f,
            score = transferScore,
            lastChecked = 1L,
            verificationStatus = MtProtoProxy.VERIFICATION_TELEGRAM_READY,
            verificationConfidence = 96,
            protocolLatency = 180L,
            protocolSuccessRate = 1f,
            tcpAttempts = 10,
            tcpSuccesses = 10,
            protocolAttempts = 8,
            protocolConfirmations = 8,
            tcpP90Latency = 170L,
            tcpJitter = 5L,
            protocolP90Latency = 220L,
            protocolJitter = 7L,
            transferScore = transferScore,
            benchmarkStage = stage,
            benchmarkRounds = rounds,
            continuityRate = 1f
        )
    }

    private suspend fun loadPage(
        sort: String,
        currentNetworkFingerprint: String = ""
    ): List<MtProtoProxy> {
        val result = dao.pagingSource(
            query = "",
            status = "ALL",
            sort = sort,
            currentNetworkFingerprint = currentNetworkFingerprint
        ).load(
            PagingSource.LoadParams.Refresh(
                key = null,
                loadSize = 100,
                placeholdersEnabled = false
            )
        )
        return (result as PagingSource.LoadResult.Page).data
    }

    private fun benchmarkRun(status: String): BenchmarkRunRecord {
        return BenchmarkRunRecord(
            status = status,
            requestedCount = 100,
            selectedWorkers = 4,
            stage = 1,
            stageCompleted = 25,
            stageTotal = 100,
            completedWork = 25,
            totalWork = 100,
            telegramTotal = 0,
            telegramCompleted = 0,
            telegramSucceeded = 0,
            telegramFailed = 0,
            enableTelegramTransfers = false,
            startedAt = 1L,
            updatedAt = 2L,
            finishedAt = 0L,
            failure = ""
        )
    }

    private fun MtProtoProxy.asUntestedExpected(): MtProtoProxy = copy()
}
