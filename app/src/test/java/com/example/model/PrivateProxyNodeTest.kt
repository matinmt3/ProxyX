package com.example.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivateProxyNodeTest {
    @Test
    fun aStrictFailureCannotBeAHealthyRecoveryCandidate() {
        val failed = node().copy(lastError = "MTPROTO_UNCONFIRMED")
        assertFalse(failed.isHealthy)
        assertEquals("MTPROTO_UNCONFIRMED", failed.asProxy().benchmarkFailureReason)
        assertTrue(ProxyInsightEngine.evaluate(failed.asProxy(), "network").profileScores.values.all { it == 0 })
    }

    @Test
    fun onlyVerifiedNodesWithoutFailureAreHealthy() {
        assertTrue(node().isHealthy)
        assertFalse(node().copy(verificationStatus = MtProtoProxy.VERIFICATION_OFFLINE).isHealthy)
        assertFalse(node().copy(latency = 0).isHealthy)
    }

    private fun node() = PrivateProxyNode(
        url = "route", label = "Test", server = "1.1.1.1", port = 443,
        secret = "0123456789abcdef0123456789abcdef", createdAt = 1, updatedAt = 1,
        lastChecked = 1, verificationStatus = MtProtoProxy.VERIFICATION_MTPROTO_VERIFIED,
        latency = 100, networkFingerprint = "network"
    )
}
