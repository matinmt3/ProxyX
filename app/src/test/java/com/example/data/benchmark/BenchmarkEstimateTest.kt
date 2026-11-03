package com.example.data.benchmark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BenchmarkEstimateTest {
    @Test
    fun transferEstimateIncludesAllThreeRoundsAndLargeCountsDoNotOverflow() {
        val estimate = BenchmarkEstimateCalculator.calculate(1, 1, 3000, BenchmarkAccuracyMode.PRECISE, true, 0)
        assertEquals(33L * 1024 * 1024, estimate.maximumTransferBytes)
        val large = BenchmarkEstimateCalculator.calculate(Int.MAX_VALUE, 1, 10000, BenchmarkAccuracyMode.PRECISE, false, 0)
        assertTrue(large.minimumDurationMs > 0)
        assertTrue(large.maximumDurationMs > 0)
        assertTrue(large.minimumDurationMs <= large.maximumDurationMs)
    }
    @Test
    fun preciseModeEstimatesMoreWorkWithoutDroppingSaverFinalists() {
        val saver = estimate(BenchmarkAccuracyMode.DATA_SAVER)
        val precise = estimate(BenchmarkAccuracyMode.PRECISE)

        assertTrue(precise.maximumDurationMs > saver.maximumDurationMs)
        assertEquals(precise.estimatedFinalists, saver.estimatedFinalists)
        assertEquals(saver.estimatedProbeBytes, precise.estimatedProbeBytes)
    }

    @Test
    fun telegramBudgetCapsWorstCaseTransferEstimate() {
        val estimate = BenchmarkEstimateCalculator.calculate(
            proxyCount = 1_000,
            workers = 8,
            timeoutMs = 3_000,
            mode = BenchmarkAccuracyMode.PRECISE,
            telegramTransfers = true,
            telegramBudgetMb = 64
        )

        assertTrue(estimate.maximumTransferBytes <= 64L * 1024L * 1024L)
    }

    @Test
    fun storedModeFallsBackSafely() {
        assertEquals(BenchmarkAccuracyMode.BALANCED, BenchmarkAccuracyMode.fromStoredValue("bad"))
        assertEquals(BenchmarkAccuracyMode.PRECISE, BenchmarkAccuracyMode.fromStoredValue("PRECISE"))
    }

    private fun estimate(mode: BenchmarkAccuracyMode) = BenchmarkEstimateCalculator.calculate(
        proxyCount = 1_000,
        workers = 8,
        timeoutMs = 3_000,
        mode = mode,
        telegramTransfers = false,
        telegramBudgetMb = 0
    )
}
