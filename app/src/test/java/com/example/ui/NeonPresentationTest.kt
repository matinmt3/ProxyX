package com.example.ui

import com.example.data.benchmark.BenchmarkRunStatus
import com.example.data.benchmark.TelegramTransferStageStatus
import com.example.data.benchmark.ThreeStageBenchmarkResult
import com.example.model.MtProtoProxy
import org.junit.Assert.*
import org.junit.Test

class NeonPresentationTest {
    @Test fun largerTextMovesCompactGaugeLabelsOutsideTheArc() {
        assertFalse(useSideGaugeLabels(true, 1f))
        assertFalse(useSideGaugeLabels(true, 1.15f))
        assertTrue(useSideGaugeLabels(true, 1.3f))
        assertTrue(useSideGaugeLabels(true, 2f))
        assertFalse(useSideGaugeLabels(false, 1.3f))
    }

    @Test fun progressDistinguishesSmallWorkFromZeroAndIncompleteFromComplete() {
        assertEquals("0%", benchmarkProgressLabel(0f))
        assertEquals("<0.1%", benchmarkProgressLabel(0.00001f))
        assertEquals("0.8%", benchmarkProgressLabel(37f / 1100f * 0.25f))
        assertEquals("99%", benchmarkProgressLabel(0.99999f))
        assertEquals("100%", benchmarkProgressLabel(1f))
    }

    @Test fun progressRejectsNonFiniteAndClampsOutOfRangeValues() {
        assertEquals("0%", benchmarkProgressLabel(Float.NaN))
        assertEquals("0%", benchmarkProgressLabel(Float.POSITIVE_INFINITY))
        assertEquals("0%", benchmarkProgressLabel(-1f))
        assertEquals("100%", benchmarkProgressLabel(2f))
    }

    private fun verified() = MtProtoProxy(
        url = "fixture", server = "example.org", port = 443, secret = "fixture",
        country = "Global", countryCode = "", isWorking = true, latency = 80,
        successRate = 1f, stability = 1f, lastChecked = 1_000,
        verificationStatus = MtProtoProxy.VERIFICATION_MTPROTO_VERIFIED,
        protocolLatency = 90, protocolSuccessRate = 1f, networkFingerprint = "wifi-a"
    )

    @Test fun idleHasNoCompletedStages() {
        assertEquals(List(3) { StageAppearance.PENDING },
            stageAppearances(BenchmarkRunStatus.IDLE, 0, false, false))
    }

    @Test fun activeStageNeverMarksFutureStagesComplete() {
        assertEquals(listOf(StageAppearance.COMPLETE, StageAppearance.ACTIVE, StageAppearance.PENDING),
            stageAppearances(BenchmarkRunStatus.RUNNING, 2, false, false))
    }

    @Test fun pausedStageRemainsIncomplete() {
        assertEquals(StageAppearance.PAUSED,
            stageAppearances(BenchmarkRunStatus.PAUSED, 2, false, false)[1])
    }

    @Test fun cancellationStopsOnlyTheCurrentStage() {
        assertEquals(listOf(StageAppearance.STOPPED, StageAppearance.PENDING, StageAppearance.PENDING),
            stageAppearances(BenchmarkRunStatus.CANCELLED, 1, false, false))
    }

    @Test fun optionalTransferIsNotMarkedCompleteByProtocolProgress() {
        assertEquals(StageAppearance.PENDING,
            stageAppearances(BenchmarkRunStatus.RUNNING, 3, true, false)[3])
    }

    @Test fun actualTransferHasItsOwnActiveStage() {
        assertEquals(listOf(StageAppearance.COMPLETE, StageAppearance.COMPLETE,
            StageAppearance.COMPLETE, StageAppearance.ACTIVE),
            stageAppearances(BenchmarkRunStatus.RUNNING, 3, true, true))
    }

    @Test fun noFinalistsDoesNotInventCompletedLaterTests() {
        val result = ThreeStageBenchmarkResult(100, 100, 0, 0, 100)
        assertEquals(listOf(StageAppearance.COMPLETE, StageAppearance.SKIPPED,
            StageAppearance.SKIPPED, StageAppearance.SKIPPED),
            stageAppearances(BenchmarkRunStatus.COMPLETED, 3, true, false, result))
    }

    @Test fun incompleteTransferDoesNotBecomeACompletedTransferBadge() {
        val result = ThreeStageBenchmarkResult(100, 100, 20, 5, 100,
            telegramTransferAttemptedCount = 3,
            telegramTransferStatus = TelegramTransferStageStatus.PARTIAL)
        assertEquals(StageAppearance.STOPPED,
            stageAppearances(BenchmarkRunStatus.COMPLETED, 3, true, true, result)[3])
    }

    @Test fun verifiedCurrentNetworkLatencyIsShown() {
        assertEquals(90L, currentProtocolLatency(verified(), "wifi-a"))
    }

    @Test fun staleUnknownAndUntaggedNetworkLatencyIsHidden() {
        assertNull(currentProtocolLatency(verified(), "cell-b"))
        assertNull(currentProtocolLatency(verified(), ""))
        assertNull(currentProtocolLatency(verified().copy(networkFingerprint = ""), "wifi-a"))
    }

    @Test fun tcpAndUntestedProxiesCannotPopulateTheProtocolGauge() {
        assertNull(currentProtocolLatency(verified().copy(
            verificationStatus = MtProtoProxy.VERIFICATION_TCP_OPEN), "wifi-a"))
        assertNull(currentProtocolLatency(verified().copy(lastChecked = 0), "wifi-a"))
        assertNull(currentProtocolLatency(null, "wifi-a"))
    }

    @Test fun compactPhoneKeepsItsPrimaryRing() {
        assertFalse(shouldCollapseHomeAction(448f, false))
        assertFalse(shouldCollapseHomeAction(600f, false))
    }

    @Test fun keyboardSizedWindowOrSearchUsesCompactActions() {
        assertTrue(shouldCollapseHomeAction(320f, false))
        assertTrue(shouldCollapseHomeAction(600f, true))
    }

    @Test fun endpointsHaveAnExplicitLtrParagraphBeforeEllipsizing() {
        val base = androidx.compose.ui.text.TextStyle(
            textDirection = androidx.compose.ui.text.style.TextDirection.Rtl,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
        val result = endpointStyle(base)
        assertEquals(androidx.compose.ui.text.style.TextDirection.Ltr, result.textDirection)
        assertEquals(base.fontWeight, result.fontWeight)
    }

    @Test fun privateEvidenceIsBoundToItsNetworkAndActualStoredResult() {
        val node = com.example.model.PrivateProxyNode(
            url = "fixture", label = "Test", server = "example.org", port = 443,
            secret = "fixture", createdAt = 1, updatedAt = 1, lastChecked = 1_000,
            latency = 80, verificationStatus = MtProtoProxy.VERIFICATION_MTPROTO_VERIFIED,
            networkFingerprint = "wifi-a")
        assertTrue(hasCurrentPrivateEvidence(node, "wifi-a"))
        assertFalse(hasCurrentPrivateEvidence(node, "cell-b"))
        assertFalse(hasCurrentPrivateEvidence(node, ""))
        assertFalse(hasCurrentPrivateEvidence(node.copy(networkFingerprint = ""), "wifi-a"))
    }

    @Test fun failedOrUntestedPrivateNodesDoNotGetAHealthyBadge() {
        val node = com.example.model.PrivateProxyNode(
            url = "fixture", label = "Test", server = "example.org", port = 443,
            secret = "fixture", createdAt = 1, updatedAt = 1, networkFingerprint = "wifi-a")
        assertFalse(hasCurrentPrivateEvidence(node, "wifi-a"))
        assertFalse(hasCurrentPrivateEvidence(node.copy(
            lastChecked = 1_000, latency = 80,
            verificationStatus = MtProtoProxy.VERIFICATION_MTPROTO_VERIFIED,
            lastError = "FAILURE"), "wifi-a"))
    }
}
