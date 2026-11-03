package com.example.viewmodel

import com.example.model.MtProtoProxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ProxyRankingTest {
    @Test
    fun untestedCandidateRanksAboveKnownOfflineEndpoint() {
        val untested = proxy("new", 0, 0).copy(lastChecked = 0,
            verificationStatus = MtProtoProxy.VERIFICATION_UNTESTED)
        val offline = proxy("offline", 0, 0)
        assertEquals(untested, ProxyRanking.sort(listOf(offline, untested), SortBy.BEST).first())
    }
    @Test
    fun realTransferOutranksUnmeasuredFinalistAndIgnoresRawPing() {
        val native = proxy("native", 500, 800).copy(benchmarkStage = 2,
            telegramTransferMeasuredAt = 100, telegramTransferRounds = 3,
            telegramDownloadKbps = 5000, telegramUploadKbps = 2000)
        val finalist = proxy("route-only", 50, 60).copy(benchmarkStage = 3, benchmarkRounds = 3)
        assertEquals(native, ProxyRanking.sort(listOf(finalist, native), SortBy.BEST).first())
    }

    @Test
    fun stalePartialAndOfflineTransferResultsCannotBeatCurrentCompleteMeasurements() {
        val native = proxy("native", 500, 800).copy(benchmarkStage = 2,
            telegramTransferMeasuredAt = 100, telegramTransferRounds = 3,
            telegramDownloadKbps = 500, telegramUploadKbps = 200)
        val fast = native.copy(url = "invalid", telegramDownloadKbps = 100000, telegramUploadKbps = 100000)
        val invalid = listOf(fast.copy(lastChecked = 200), fast.copy(telegramTransferRounds = 2),
            fast.copy(isWorking = false), fast.copy(benchmarkFailureReason = "CONTINUITY_LOST"))
        for (mode in listOf(SortBy.BEST, SortBy.DOWN_UP, SortBy.HIGH, SortBy.DOWNLOAD, SortBy.UPLOAD)) {
            for (bad in invalid) assertEquals(mode.name, native, ProxyRanking.sort(listOf(bad, native), mode).first())
        }
    }

    @Test
    fun bestPrefersMeasuredTelegramTransferWithinSameEvidenceTier() {
        val measured = proxy("measured", tcpLatency = 180L, protocolLatency = 210L).copy(
            transferScore = 80,
            telegramDownloadKbps = 6_000,
            telegramUploadKbps = 2_500,
            telegramTransferMeasuredAt = 100L,
            telegramTransferRounds = 3
        )
        val unmeasured = proxy(
            "unmeasured",
            tcpLatency = 80L,
            protocolLatency = 100L
        ).copy(transferScore = 99)

        assertEquals(measured, ProxyRanking.sort(listOf(unmeasured, measured), SortBy.BEST).first())
    }

    @Test
    fun strictRejectionCannotOutrankAHealthyVerifiedProxy() {
        val healthy = proxy("healthy-proof", 150L, 190L).copy(transferScore = 72)
        val rejected = proxy("rejected-proof", 60L, 80L).copy(
            transferScore = 100,
            benchmarkFailureReason = "STABILITY_LOW"
        )

        assertEquals(healthy, ProxyRanking.sort(listOf(rejected, healthy), SortBy.BEST).first())
    }

    @Test
    fun bestRewardsMeasuredProtocolQualityAndReliability() {
        val healthy = proxy(
            name = "healthy",
            tcpLatency = 100,
            protocolLatency = 140,
            successRate = 1f,
            stability = 0.95f,
            protocolRate = 1f
        )
        val unstable = proxy(
            name = "unstable",
            tcpLatency = 80,
            protocolLatency = 120,
            successRate = 0.5f,
            stability = 0.35f,
            protocolRate = 0.5f
        )

        assertEquals(healthy, ProxyRanking.sort(listOf(unstable, healthy), SortBy.BEST).first())
    }

    @Test
    fun latencySortUsesMeasuredTcpRoundTrip() {
        val low = proxy("low", tcpLatency = 80, protocolLatency = 160)
        val high = proxy("high", tcpLatency = 180, protocolLatency = 90)

        assertEquals(low, ProxyRanking.sort(listOf(high, low), SortBy.LATENCY).first())
    }

    @Test
    fun protocolSortUsesMeasuredMtProtoRoundTrip() {
        val low = proxy("low", tcpLatency = 180, protocolLatency = 90)
        val high = proxy("high", tcpLatency = 80, protocolLatency = 220)

        assertEquals(low, ProxyRanking.sort(listOf(high, low), SortBy.PROTOCOL).first())
    }

    @Test
    fun stabilitySortUsesActualSuccessAndJitterEvidence() {
        val stable = proxy(
            name = "stable",
            tcpLatency = 160,
            protocolLatency = 180,
            successRate = 1f,
            stability = 0.98f,
            protocolRate = 1f
        )
        val unreliable = proxy(
            name = "unreliable",
            tcpLatency = 70,
            protocolLatency = 90,
            successRate = 0.5f,
            stability = 0.3f,
            protocolRate = 0.5f
        )

        assertEquals(
            stable,
            ProxyRanking.sort(listOf(unreliable, stable), SortBy.STABILITY).first()
        )
    }

    @Test
    fun completedOfflineProxyCannotBeatVerifiedProxy() {
        val working = proxy("working", tcpLatency = 180, protocolLatency = 200)
        val offline = proxy("offline", tcpLatency = 40, protocolLatency = 40).copy(
            isWorking = false,
            verificationStatus = MtProtoProxy.VERIFICATION_OFFLINE
        )

        assertEquals(working, ProxyRanking.sort(listOf(offline, working), SortBy.BEST).first())
    }

    @Test
    fun benchmarkResultsExcludeUntestedRowsButKeepCompletedFailures() {
        val tested = proxy("tested", tcpLatency = 120, protocolLatency = 150)
        val failed = proxy("failed", tcpLatency = 0, protocolLatency = 0).copy(
            isWorking = false,
            lastChecked = 11L,
            verificationStatus = MtProtoProxy.VERIFICATION_OFFLINE
        )
        val untested = proxy("untested", tcpLatency = 80, protocolLatency = 100)
            .copy(lastChecked = 0L)

        val rows = ProxyRanking.benchmarkResults(listOf(untested, failed, tested), limit = 10)

        assertEquals(listOf(tested, failed), rows)
        assertFalse(rows.contains(untested))
    }

    @Test
    fun nonFiniteEvidenceCannotWinBestRanking() {
        val healthy = proxy("healthy", tcpLatency = 150, protocolLatency = 170)
        val corrupt = proxy("corrupt", tcpLatency = 90, protocolLatency = 100).copy(
            successRate = Float.NaN,
            stability = Float.NaN,
            protocolSuccessRate = Float.NaN,
            score = 100
        )

        assertEquals(healthy, ProxyRanking.sort(listOf(corrupt, healthy), SortBy.BEST).first())
    }

    @Test
    fun bestUsesTailLatencyAndJitterInsteadOfOptimisticMedianAlone() {
        val consistent = proxy("consistent", tcpLatency = 100, protocolLatency = 140)
        val spiky = proxy("spiky", tcpLatency = 80, protocolLatency = 120).copy(
            stability = 0.75f,
            tcpP90Latency = 1_200,
            tcpJitter = 100,
            protocolP90Latency = 2_100,
            protocolJitter = 190
        )

        assertEquals(consistent, ProxyRanking.sort(listOf(spiky, consistent), SortBy.BEST).first())
    }

    @Test
    fun bestPrioritizesSustainedTransferEvidenceOverRawPing() {
        val sustained = proxy(
            name = "sustained",
            tcpLatency = 190,
            protocolLatency = 230,
            stability = 0.98f
        ).copy(
            tcpP90Latency = 240,
            tcpJitter = 5,
            protocolP90Latency = 290,
            protocolJitter = 8
        )
        val lowPingButSpiky = proxy(
            name = "low-ping",
            tcpLatency = 55,
            protocolLatency = 80,
            stability = 0.83f
        ).copy(
            tcpP90Latency = 1_400,
            tcpJitter = 180,
            protocolP90Latency = 2_300,
            protocolJitter = 310
        )

        assertEquals(
            sustained,
            ProxyRanking.sort(listOf(lowPingButSpiky, sustained), SortBy.BEST).first()
        )
    }

    @Test
    fun countrySortUsesCurrentMeasuredResultsInsteadOfStaticCountryTier() {
        val slowerGermany = proxy(
            name = "germany",
            tcpLatency = 260,
            protocolLatency = 320,
            country = "Germany",
            countryCode = "DE"
        )
        val fasterFrance = proxy(
            name = "france",
            tcpLatency = 80,
            protocolLatency = 110,
            country = "France",
            countryCode = "FR"
        )
        val unknown = proxy(
            name = "unknown",
            tcpLatency = 60,
            protocolLatency = 90
        )

        val sorted = ProxyRanking.sort(
            listOf(slowerGermany, unknown, fasterFrance),
            SortBy.COUNTRY
        )

        assertEquals(fasterFrance, sorted.first())
        assertEquals(unknown, sorted.last())
    }

    @Test
    fun completedFinalistEvidenceRanksAboveAnOptimisticSingleRound() {
        val finalist = proxy(
            name = "finalist",
            tcpLatency = 210,
            protocolLatency = 260,
            stability = 0.96f
        ).copy(
            transferScore = 92,
            benchmarkStage = 3,
            benchmarkRounds = 3,
            continuityRate = 1f
        )
        val singleRound = proxy(
            name = "single-round",
            tcpLatency = 45,
            protocolLatency = 65,
            stability = 1f
        ).copy(
            transferScore = 99,
            benchmarkStage = 2,
            benchmarkRounds = 1,
            continuityRate = 1f
        )

        assertEquals(
            finalist,
            ProxyRanking.sort(listOf(singleRound, finalist), SortBy.BEST).first()
        )
    }

    @Test
    fun storedTransferScoreIsUsedInsteadOfLegacyScore() {
        val strongEvidence = proxy("strong", tcpLatency = 180, protocolLatency = 220).copy(
            score = 10,
            transferScore = 95,
            benchmarkStage = 3,
            benchmarkRounds = 3,
            continuityRate = 1f
        )
        val legacyHigh = proxy("legacy", tcpLatency = 90, protocolLatency = 110).copy(
            score = 100,
            transferScore = 70,
            benchmarkStage = 3,
            benchmarkRounds = 3,
            continuityRate = 1f
        )

        assertEquals(
            strongEvidence,
            ProxyRanking.sort(listOf(legacyHigh, strongEvidence), SortBy.BEST).first()
        )
    }

    @Test
    fun repeatableThroughputRanksAboveAOneOffBurst() {
        val base = proxy("stable", 100, 120).copy(benchmarkStage = 3, benchmarkRounds = 3,
            continuityRate = 1f, telegramTransferMeasuredAt = 100, telegramTransferRounds = 3,
            telegramDownloadKbps = 6000, telegramUploadKbps = 6000,
            telegramDownloadFloorKbps = 5000, telegramUploadFloorKbps = 5000)
        val burst = base.copy(url = "tg://proxy/burst", telegramDownloadKbps = 10000, telegramUploadKbps = 10000,
            telegramDownloadFloorKbps = 100, telegramUploadFloorKbps = 100)
        for (mode in listOf(SortBy.BEST, SortBy.HIGH, SortBy.DOWN_UP, SortBy.DOWNLOAD, SortBy.UPLOAD)) {
            assertEquals(mode.name, base, ProxyRanking.sort(listOf(burst, base), mode).first())
        }
    }

    private fun proxy(
        name: String,
        tcpLatency: Long,
        protocolLatency: Long,
        successRate: Float = 1f,
        stability: Float = 1f,
        protocolRate: Float = 1f,
        country: String = "Global",
        countryCode: String = "UN"
    ): MtProtoProxy {
        val protocolAttempts = if (protocolLatency > 0L) 8 else 0
        val protocolConfirmations = (protocolAttempts * protocolRate)
            .toInt()
            .coerceIn(0, protocolAttempts)
        val status = when {
            protocolConfirmations == 8 -> MtProtoProxy.VERIFICATION_TELEGRAM_READY
            protocolConfirmations > 0 -> MtProtoProxy.VERIFICATION_MTPROTO_VERIFIED
            tcpLatency > 0L -> MtProtoProxy.VERIFICATION_TCP_OPEN
            else -> MtProtoProxy.VERIFICATION_OFFLINE
        }
        return MtProtoProxy(
            url = "tg://proxy/$name",
            server = "$name.example.com",
            port = 443,
            secret = "dd79e344818749bd7ac519130220c25d09",
            country = country,
            countryCode = countryCode,
            isWorking = tcpLatency > 0L,
            latency = tcpLatency,
            successRate = successRate,
            stability = stability,
            score = MtProtoProxy.calculateScore(
                latency = tcpLatency,
                successRate = successRate,
                stability = stability,
                protocolLatency = protocolLatency,
                protocolSuccessRate = protocolRate,
                tcpP90Latency = tcpLatency + 20,
                tcpJitter = 4,
                protocolP90Latency = protocolLatency.takeIf { it > 0L }?.plus(30) ?: 0L,
                protocolJitter = protocolLatency.takeIf { it > 0L }?.let { 6L } ?: 0L
            ),
            lastChecked = 1L,
            verificationStatus = status,
            verificationConfidence = 95,
            protocolLatency = protocolLatency,
            protocolSuccessRate = protocolRate,
            tcpAttempts = 10,
            tcpSuccesses = (10 * successRate).toInt().coerceIn(0, 10),
            protocolAttempts = protocolAttempts,
            protocolConfirmations = protocolConfirmations,
            tcpP90Latency = tcpLatency.takeIf { it > 0L }?.plus(20) ?: 0L,
            tcpJitter = tcpLatency.takeIf { it > 0L }?.let { 4L } ?: 0L,
            protocolP90Latency = protocolLatency.takeIf { it > 0L }?.plus(30) ?: 0L,
            protocolJitter = protocolLatency.takeIf { it > 0L }?.let { 6L } ?: 0L
        )
    }
}
