package com.example.data.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LatencyStatisticsTest {
    @Test
    fun reportsMedianP90JitterAndFullSuccessFromRealSamples() {
        val result = LatencyStatisticsCalculator.summarize(
            successfulLatencies = listOf(100, 101, 99, 102, 100, 130),
            attempts = 6
        )

        assertEquals(100L, result.medianMs)
        assertEquals(130L, result.p90Ms)
        assertEquals(1L, result.jitterMs)
        assertEquals(1f, result.successRate)
        assertTrue(result.stability in 0.60f..0.80f)
    }

    @Test
    fun failedAttemptsAndLargeTailReduceStability() {
        val stable = LatencyStatisticsCalculator.summarize(
            successfulLatencies = listOf(100, 101, 99, 100, 102, 98),
            attempts = 6
        )
        val unstable = LatencyStatisticsCalculator.summarize(
            successfulLatencies = listOf(90, 100, 900),
            attempts = 6
        )

        assertTrue(stable.stability > unstable.stability)
        assertEquals(900L, unstable.p90Ms)
        assertEquals(0.5f, unstable.successRate)
    }
}
