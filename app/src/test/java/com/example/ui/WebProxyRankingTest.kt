package com.example.ui

import com.example.model.WebProxyNode
import org.junit.Assert.*
import org.junit.Test

class WebProxyRankingTest {
    private fun measured(id: String, down: Int, up: Int, net: String = "current") = WebProxyNode(
        id, "$id.example.com", "00".repeat(16), 1, testedAt = 2, status = "TRANSFER_VERIFIED", stage = 4,
        latencyMs = 100, networkFingerprint = net, downloadKbps = down, uploadKbps = up, transferBytes = 1000, transferRounds = 3)
    @Test fun balancedRealThroughputWinsOverOneDirectionalPeak() {
        val balanced = measured("balanced", 4000, 3000)
        val peak = measured("peak", 10000, 100)
        assertEquals("balanced", rankWebProxies(listOf(peak, balanced), "current").first().url)
    }
    @Test fun differentNetworkCannotWinWithOldSpeeds() {
        assertEquals("current", rankWebProxies(listOf(measured("old", 9000, 9000, "old"), measured("current", 200, 100)), "current").first().url)
    }
    @Test fun incompleteAndFailedTransfersCannotWinRanking() {
        val good = measured("good", 100, 100)
        val bad = measured("bad", 999999, 999999)
        for (invalid in listOf(bad.copy(status = "FAILED"), bad.copy(transferRounds = 1), bad.copy(transferBytes = 0))) {
            assertEquals("good", rankWebProxies(listOf(invalid, good), "current").first().url)
        }
    }
    @Test fun protocolEvidenceComesBeforeUntestedRoutes() {
        val protocol = measured("protocol", 0, 0).copy(status = "PROTOCOL_VERIFIED", stage = 3)
        val untested = measured("untested", 0, 0).copy(status = "UNTESTED", stage = 0, latencyMs = 1)
        assertEquals("protocol", rankWebProxies(listOf(untested, protocol), "current").first().url)
    }
    @Test fun staleAndFailedLatencyCannotReorderUnverifiedNodes() {
        val untested = measured("a-untested", 0, 0).copy(status = "UNTESTED", stage = 0, latencyMs = 0)
        val stale = measured("z-old", 0, 0, "old").copy(latencyMs = 1)
        val failed = measured("z-failed", 0, 0).copy(status = "FAILED", latencyMs = 1)
        assertEquals("a-untested", rankWebProxies(listOf(stale, failed, untested), "current").first().url)
    }
    @Test fun unknownNetworkCannotUseStoredLatency() {
        val a = measured("a", 0, 0).copy(latencyMs = 1000)
        val z = measured("z", 0, 0).copy(latencyMs = 1)
        assertEquals("a", rankWebProxies(listOf(z, a), "").first().url)
    }
}
