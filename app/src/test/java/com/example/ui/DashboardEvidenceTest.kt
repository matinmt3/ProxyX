package com.example.ui

import com.example.model.MtProtoProxy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardEvidenceTest {
    private fun measured() = MtProtoProxy(
        url = "fixture", server = "example.org", port = 443, secret = "fixture",
        country = "Global", countryCode = "", isWorking = true, latency = 100,
        successRate = 1f, stability = 1f, lastChecked = 1_000,
        verificationStatus = MtProtoProxy.VERIFICATION_TCP_OPEN, networkFingerprint = "wifi-a"
    )

    @Test
    fun anotherNetworkCannotUseAStoredReachabilityBadge() {
        assertTrue(hasHistoricalDashboardEvidence(measured(), "cell-b"))
    }

    @Test
    fun unknownNetworkAndLegacyUntaggedEvidenceAreNotCurrent() {
        assertTrue(hasHistoricalDashboardEvidence(measured(), ""))
        assertTrue(hasHistoricalDashboardEvidence(measured().copy(networkFingerprint = ""), "wifi-a"))
    }

    @Test
    fun matchingNetworkPreservesItsMeasuredStatus() {
        assertFalse(hasHistoricalDashboardEvidence(measured(), "wifi-a"))
    }

    @Test
    fun freshUntestedRowsAndEmptySelectionAreNotHistoricalMeasurements() {
        val fresh = measured().copy(lastChecked = 0, verificationStatus = MtProtoProxy.VERIFICATION_UNTESTED)
        assertFalse(hasHistoricalDashboardEvidence(fresh, "cell-b"))
        assertFalse(hasHistoricalDashboardEvidence(null, "wifi-a"))
    }
}
