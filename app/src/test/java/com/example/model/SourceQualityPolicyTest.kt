package com.example.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class SourceQualityPolicyTest {
    @Test fun anOlderSuccessDoesNotMakeTheLatestFailureSuccessful() {
        val health = ProxySourceHealth("source", "Source", lastAttemptAt = 200,
            lastSuccessAt = 100, uniqueProxyCount = 40)
        assertFalse(health.hasSuccessfulLatestFetch)
        assertFalse(health.copy(lastSuccessAt = 201, lastError = "HTTP_404").hasSuccessfulLatestFetch)
    }

    @Test fun anEmptyDuplicateOrQuarantinedFetchIsNotASuccessfulSource() {
        val health = ProxySourceHealth("source", "Source", lastAttemptAt = 100,
            lastSuccessAt = 101, uniqueProxyCount = 0)
        assertFalse(health.hasSuccessfulLatestFetch)
        assertFalse(health.copy(uniqueProxyCount = 40, consecutiveFailures = 1).hasSuccessfulLatestFetch)
        assertFalse(health.copy(uniqueProxyCount = 40, quarantineUntil = Long.MAX_VALUE).hasSuccessfulLatestFetch)
        assertFalse(health.copy(lastAttemptAt = 0, uniqueProxyCount = 40).hasSuccessfulLatestFetch)
    }

    @Test fun theLatestNonEmptySuccessfulFetchIsCounted() {
        assertTrue(ProxySourceHealth("source", "Source", lastAttemptAt = 100,
            lastSuccessAt = 101, uniqueProxyCount = 40).hasSuccessfulLatestFetch)
    }

    @Test
    fun transientFailureDoesNotImmediatelyQuarantineHealthySource() {
        assertEquals(0L, SourceQualityPolicy.quarantineDurationMs(1, false))
        assertEquals(0L, SourceQualityPolicy.quarantineDurationMs(2, false))
        assertTrue(SourceQualityPolicy.quarantineDurationMs(3, false) > 0L)
    }

    @Test
    fun verifiedUniqueSourceScoresAboveDuplicateUnverifiedSource() {
        val strong = ProxySourceHealth(
            sourceUrl = "strong",
            sourceName = "strong",
            totalFetches = 10,
            successfulFetches = 10,
            uniqueProxyCount = 100,
            duplicateRate = 0.05f,
            verifiedRate = 0.70f,
            averageDurationMs = 1_000L
        )
        val weak = ProxySourceHealth(
            sourceUrl = "weak",
            sourceName = "weak",
            totalFetches = 10,
            successfulFetches = 8,
            uniqueProxyCount = 4,
            duplicateRate = 0.95f,
            verifiedRate = 0f,
            averageDurationMs = 10_000L
        )

        assertTrue(strong.qualityScore > weak.qualityScore)
    }
}
