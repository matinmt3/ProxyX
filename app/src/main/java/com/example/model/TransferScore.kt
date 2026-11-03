package com.example.model

import kotlin.math.roundToInt

/**
 * Scores evidence that is relevant to sustained Telegram transfers.
 *
 * This is not a throughput estimate. No byte-per-second value is inferred from latency. Actual
 * Telegram throughput is stored separately only after an authenticated TDLib file transfer.
 */
object TransferScoreCalculator {
    fun calculate(
        tcpSuccessRate: Float,
        protocolSuccessRate: Float,
        stability: Float,
        continuityRate: Float,
        tcpP90Latency: Long,
        tcpJitter: Long,
        protocolP90Latency: Long,
        protocolJitter: Long,
        benchmarkStage: Int,
        benchmarkRounds: Int
    ): Int {
        val tcpRate = safeRate(tcpSuccessRate)
        val protocolRate = safeRate(protocolSuccessRate)
        val stableRate = safeRate(stability)
        val continuity = safeRate(continuityRate)
        if (tcpRate <= 0.0 || protocolRate <= 0.0 || protocolP90Latency <= 0L) return 0

        val protocolTailQuality = latencyQuality(
            protocolP90Latency,
            idealMs = 180L,
            poorMs = 2_800L
        )
        val tcpTailQuality = latencyQuality(
            tcpP90Latency,
            idealMs = 110L,
            poorMs = 1_800L
        )
        val protocolJitterQuality = jitterQuality(protocolJitter, protocolP90Latency)
        val evidenceQuality = evidenceQuality(benchmarkStage, benchmarkRounds)

        val score =
            protocolRate * 28.0 +
                continuity * 24.0 +
                stableRate * 18.0 +
                protocolTailQuality * 10.0 +
                protocolJitterQuality * 8.0 +
                tcpRate * 5.0 +
                tcpTailQuality * 3.0 +
                evidenceQuality * 4.0
        return score.roundToInt().coerceIn(0, 100)
    }

    private fun evidenceQuality(stage: Int, rounds: Int): Double {
        val stageEvidence = when (stage) {
            in 3..Int.MAX_VALUE -> 1.0
            2 -> 0.82
            1 -> 0.42
            else -> 0.0
        }
        val roundEvidence = when {
            rounds >= 3 -> 1.0
            rounds == 2 -> 0.82
            rounds == 1 -> 0.62
            else -> 0.0
        }
        return stageEvidence * roundEvidence
    }

    private fun safeRate(value: Float): Double {
        return value.takeIf(Float::isFinite)?.coerceIn(0f, 1f)?.toDouble() ?: 0.0
    }

    private fun latencyQuality(valueMs: Long, idealMs: Long, poorMs: Long): Double {
        if (valueMs <= 0L || poorMs <= idealMs) return 0.0
        if (valueMs <= idealMs) return 1.0
        if (valueMs >= poorMs) return 0.0
        return (poorMs - valueMs).toDouble() / (poorMs - idealMs).toDouble()
    }

    private fun jitterQuality(jitterMs: Long, referenceLatencyMs: Long): Double {
        if (referenceLatencyMs <= 0L || jitterMs < 0L) return 0.0
        val ratio = jitterMs.toDouble() / (referenceLatencyMs.toDouble() + 1.0)
        return (1.0 / (1.0 + ratio * 10.0)).coerceIn(0.0, 1.0)
    }
}
