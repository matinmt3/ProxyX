package com.example.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class ProxyInsightTest {
    @Test
    fun unknownNetworkIsNotTreatedAsConfirmedCurrentNetwork() {
        assertTrue(!ProxyInsightEngine.evaluate(measuredProxy(), "").currentNetworkMatch)
        assertTrue(!ProxyInsightEngine.evaluate(measuredProxy().copy(networkFingerprint = ""), "wifi-a").currentNetworkMatch)
    }
    @Test
    fun resultFromAnotherNetworkIsClearlyDownranked() {
        val matching = ProxyInsightEngine.evaluate(measuredProxy(), "wifi-a", now = 1_000L)
        val changed = ProxyInsightEngine.evaluate(measuredProxy(), "cell-b", now = 1_000L)

        assertEquals(30, matching.confidence - changed.confidence)
        assertTrue(changed.reasons.contains(ProxyInsightEngine.REASON_DIFFERENT_NETWORK))
    }

    @Test
    fun realMultiRoundTransferProducesScenarioScoresAndEvidence() {
        val insight = ProxyInsightEngine.evaluate(measuredProxy(), "wifi-a")

        assertTrue(insight.reasons.contains(ProxyInsightEngine.REASON_REAL_TRANSFER))
        assertTrue(insight.reasons.contains(ProxyInsightEngine.REASON_MULTI_ROUND))
        assertTrue((insight.profileScores[ProxyUseProfile.DOWNLOAD] ?: 0) > 70)
        assertTrue((insight.profileScores[ProxyUseProfile.UPLOAD] ?: 0) > 60)
    }

    @Test
    fun matchingDirectBaselineProducesCapacityRatios() {
        val proxy = measuredProxy()
        val baseline = NetworkSpeedBaseline(
            networkFingerprint = "wifi-a",
            networkType = "WIFI",
            isMetered = false,
            downloadKbps = 16_384,
            uploadKbps = 8_192,
            downloadSampleBytes = 1_000_000,
            uploadSampleBytes = 1_000_000,
            measuredAt = 1_000L
        )

        val insight = ProxyInsightEngine.evaluate(proxy, "wifi-a", baseline, now = 1_000L)

        assertEquals(50, insight.downloadCapacityPercent)
        assertEquals(50, insight.uploadCapacityPercent)
        assertTrue(insight.reasons.contains(ProxyInsightEngine.REASON_BASELINE_NORMALIZED))
    }

    @Test
    fun oldMeasurementReducesConfidenceWithoutInventingFreshness() {
        val fresh = measuredProxy().copy(lastChecked = 1_000L)
        val recent = ProxyInsightEngine.evaluate(fresh, "wifi-a", now = 1_000L)
        val stale = ProxyInsightEngine.evaluate(
            fresh,
            "wifi-a",
            now = 1_000L + 8L * 24L * 60L * 60L * 1_000L
        )

        assertTrue(stale.confidence < recent.confidence)
        assertTrue(stale.reasons.contains(ProxyInsightEngine.REASON_STALE_RESULT))
    }

    @Test
    fun incompleteOrStaleTransfersCannotIncreaseComparisonScores() {
        val measured = measuredProxy()
        for (invalid in listOf(measured.copy(telegramTransferRounds = 2),
            measured.copy(lastChecked = measured.telegramTransferMeasuredAt + 1),
            measured.copy(telegramTransferMeasuredAt = 0))) {
            val insight = ProxyInsightEngine.evaluate(invalid, "wifi-a")
            val unmeasured = ProxyInsightEngine.evaluate(invalid.copy(
                telegramDownloadKbps = 0, telegramUploadKbps = 0), "wifi-a")
            assertEquals(unmeasured.profileScores, insight.profileScores)
            assertFalse(insight.reasons.contains(ProxyInsightEngine.REASON_REAL_TRANSFER))
        }
    }

    @Test
    fun failedOrOfflineRoutesHaveNoCurrentUseScores() {
        for (invalid in listOf(measuredProxy().copy(isWorking = false),
            measuredProxy().copy(benchmarkFailureReason = "TELEGRAM_TRANSFER_FAILED"))) {
            val insight = ProxyInsightEngine.evaluate(invalid, "wifi-a")
            assertTrue(insight.profileScores.values.all { it == 0 })
            assertFalse(insight.reasons.contains(ProxyInsightEngine.REASON_REAL_TRANSFER))
        }
    }

    @Test
    fun anotherNetworkCannotShowCurrentTransferCapacity() {
        val proxy = measuredProxy()
        val baseline = NetworkSpeedBaseline("wifi-a", "WIFI", false, 16384, 8192,
            1000000, 1000000, 1000)
        val insight = ProxyInsightEngine.evaluate(proxy, "cell-b", baseline, 1000)
        assertNull(insight.downloadCapacityPercent)
        assertNull(insight.uploadCapacityPercent)
        assertTrue(insight.profileScores.values.all { it == 0 })
        assertFalse(insight.reasons.contains(ProxyInsightEngine.REASON_BASELINE_NORMALIZED))
    }

    private fun measuredProxy() = MtProtoProxy(
        url = "tg://proxy?server=1.1.1.1&port=443&secret=0123456789abcdef0123456789abcdef",
        server = "1.1.1.1",
        port = 443,
        secret = "0123456789abcdef0123456789abcdef",
        country = "Germany",
        countryCode = "DE",
        isWorking = true,
        latency = 90,
        successRate = 1f,
        stability = 0.94f,
        verificationStatus = MtProtoProxy.VERIFICATION_MTPROTO_VERIFIED,
        verificationConfidence = 91,
        protocolLatency = 95,
        protocolSuccessRate = 1f,
        protocolP90Latency = 120,
        benchmarkStage = 3,
        benchmarkRounds = 3,
        continuityRate = 0.96f,
        networkFingerprint = "wifi-a",
        telegramDownloadKbps = 8_192,
        telegramUploadKbps = 4_096,
        lastChecked = 1000,
        telegramTransferMeasuredAt = 1000,
        telegramTransferRounds = 3,
        telegramTransferConfidence = 94
    )
}
