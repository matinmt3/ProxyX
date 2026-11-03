package com.example.data.network

import kotlin.math.abs
import kotlin.math.ceil

internal data class LatencyStatistics(
    val medianMs: Long,
    val p90Ms: Long,
    val jitterMs: Long,
    val successRate: Float,
    val stability: Float
)

internal object LatencyStatisticsCalculator {
    fun summarize(successfulLatencies: List<Long>, attempts: Int): LatencyStatistics {
        val safeAttempts = attempts.coerceAtLeast(0)
        val samples = successfulLatencies.filter { it > 0L }.sorted()
        if (safeAttempts == 0 || samples.isEmpty()) {
            return LatencyStatistics(0L, 0L, 0L, 0f, 0f)
        }

        val successRate = (samples.size.toFloat() / safeAttempts.toFloat()).coerceIn(0f, 1f)
        val median = median(samples)
        val p90 = percentile(samples, 0.90)
        val deviations = samples.map { abs(it - median) }.sorted()
        val jitter = median(deviations)
        val jitterRatio = jitter.toDouble() / (median.toDouble() + 1.0)
        val tailRatio = (p90 - median).coerceAtLeast(0L).toDouble() / (median.toDouble() + 1.0)
        val consistency = 1.0 / (1.0 + jitterRatio * JITTER_WEIGHT + tailRatio * TAIL_WEIGHT)
        val sampleConfidence = when (samples.size) {
            1 -> 0.55
            2 -> 0.75
            3 -> 0.90
            else -> 1.0
        }
        val stability = (successRate * consistency.toFloat() * sampleConfidence.toFloat())
            .coerceIn(0f, 1f)

        return LatencyStatistics(
            medianMs = median,
            p90Ms = p90,
            jitterMs = jitter,
            successRate = successRate,
            stability = stability
        )
    }

    private fun percentile(sortedValues: List<Long>, percentile: Double): Long {
        val index = (ceil(sortedValues.size * percentile).toInt() - 1)
            .coerceIn(0, sortedValues.lastIndex)
        return sortedValues[index]
    }

    private fun median(sortedValues: List<Long>): Long {
        val middle = sortedValues.size / 2
        return if (sortedValues.size % 2 == 0) {
            ((sortedValues[middle - 1].toDouble() + sortedValues[middle].toDouble()) / 2.0)
                .toLong()
        } else {
            sortedValues[middle]
        }
    }

    private const val JITTER_WEIGHT = 4.0
    private const val TAIL_WEIGHT = 2.0
}
