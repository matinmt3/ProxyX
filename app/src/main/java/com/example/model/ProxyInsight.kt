package com.example.model

import kotlin.math.ln
import kotlin.math.roundToInt

enum class ProxyUseProfile { BALANCED, DOWNLOAD, UPLOAD, WEAK_NETWORK, MESSAGING }

data class ProxyInsight(
    val confidence: Int,
    val currentNetworkMatch: Boolean,
    val reasons: List<String>,
    val profileScores: Map<ProxyUseProfile, Int>,
    val downloadCapacityPercent: Int? = null,
    val uploadCapacityPercent: Int? = null,
    val measurementAgeMs: Long? = null
)

object ProxyInsightEngine {
    fun evaluate(
        proxy: MtProtoProxy,
        currentNetworkFingerprint: String = "",
        networkBaseline: NetworkSpeedBaseline? = null,
        now: Long = System.currentTimeMillis()
    ): ProxyInsight {
        val networkMatch = currentNetworkFingerprint.isNotBlank() &&
            proxy.networkFingerprint.isNotBlank() &&
            proxy.networkFingerprint == currentNetworkFingerprint
        val validTransfer = TransferRankingEvidence.isUsableMeasurement(proxy)
        val currentTransfer = validTransfer && networkMatch
        val currentRoute = networkMatch && proxy.isTelegramVerified &&
            proxy.benchmarkFailureReason in setOf("", "STABILITY_LOW")
        val measuredAt = maxOf(proxy.lastChecked, if (validTransfer) proxy.telegramTransferMeasuredAt else 0L)
            .takeIf { it > 0L }
        val measurementAgeMs = measuredAt?.let { (now - it).coerceAtLeast(0L) }
        val ageMultiplier = confidenceAgeMultiplier(measurementAgeMs)
        val usableBaseline = networkBaseline?.takeIf { baseline ->
            currentTransfer && baseline.isUsable &&
                baseline.networkFingerprint == proxy.networkFingerprint
        }
        var confidence = proxy.verificationConfidence.coerceIn(0, 100)
        if (proxy.benchmarkStage >= 3 && proxy.benchmarkRounds >= 3) confidence += 5
        if (validTransfer) {
            confidence = maxOf(confidence, proxy.telegramTransferConfidence)
        }
        if (!networkMatch) confidence -= 30
        if (proxy.benchmarkFailureReason.isNotBlank()) confidence -= 25
        if (!proxy.isTelegramVerified) confidence = minOf(confidence, 55)
        confidence = (confidence * ageMultiplier).roundToInt()

        val reasons = buildList {
            if (!networkMatch) add(REASON_DIFFERENT_NETWORK)
            if (measurementAgeMs != null && measurementAgeMs >= STALE_RESULT_AGE_MS) {
                add(REASON_STALE_RESULT)
            }
            if (usableBaseline != null) add(REASON_BASELINE_NORMALIZED)
            if (currentTransfer) add(REASON_REAL_TRANSFER)
            if (proxy.benchmarkStage >= 3 && proxy.benchmarkRounds >= 3) add(REASON_MULTI_ROUND)
            if (proxy.continuityRate >= 0.9f && proxy.stability >= 0.85f) add(REASON_STABLE)
            if (proxy.protocolP90Latency in 1..450) add(REASON_LOW_TAIL)
            if (proxy.benchmarkFailureReason.isNotBlank()) add(REASON_FAILED_GATE)
            if (isEmpty()) add(REASON_NEEDS_MORE_EVIDENCE)
        }

        val download = transferRateScore(
            if (currentTransfer) proxy.telegramDownloadKbps else 0,
            usableBaseline?.downloadKbps
        )
        val upload = transferRateScore(
            if (currentTransfer) proxy.telegramUploadKbps else 0,
            usableBaseline?.uploadKbps
        )
        val stability = if (currentRoute) (safeRate(proxy.stability) * 100f).roundToInt() else 0
        val continuity = if (currentRoute) (safeRate(proxy.continuityRate) * 100f).roundToInt() else 0
        val latency = if (currentRoute) latencyScore(
            proxy.protocolP90Latency.takeIf { it > 0L }
                ?: proxy.protocolLatency.takeIf { it > 0L }
                ?: proxy.latency
        ) else 0
        val weightedDownload = download
        val weightedUpload = upload
        val balanced = weighted(
            weightedDownload to 0.27f,
            weightedUpload to 0.23f,
            stability to 0.20f,
            continuity to 0.20f,
            latency to 0.10f
        )
        return ProxyInsight(
            confidence = confidence.coerceIn(0, 100),
            currentNetworkMatch = networkMatch,
            reasons = reasons,
            profileScores = mapOf(
                ProxyUseProfile.BALANCED to balanced,
                ProxyUseProfile.DOWNLOAD to weighted(
                    weightedDownload to 0.62f,
                    stability to 0.16f,
                    continuity to 0.17f,
                    latency to 0.05f
                ),
                ProxyUseProfile.UPLOAD to weighted(
                    weightedUpload to 0.62f,
                    stability to 0.16f,
                    continuity to 0.17f,
                    latency to 0.05f
                ),
                ProxyUseProfile.WEAK_NETWORK to weighted(
                    continuity to 0.38f,
                    stability to 0.34f,
                    latency to 0.18f,
                    (if (currentRoute) proxy.transferScore.coerceIn(0, 100) else 0) to 0.10f
                ),
                ProxyUseProfile.MESSAGING to weighted(
                    latency to 0.52f,
                    continuity to 0.28f,
                    stability to 0.20f
                )
            ),
            downloadCapacityPercent = capacityPercent(
                if (currentTransfer) proxy.telegramDownloadKbps else 0,
                usableBaseline?.downloadKbps
            ),
            uploadCapacityPercent = capacityPercent(
                if (currentTransfer) proxy.telegramUploadKbps else 0,
                usableBaseline?.uploadKbps
            ),
            measurementAgeMs = measurementAgeMs
        )
    }

    private fun transferRateScore(kbps: Int, baselineKbps: Int?): Int {
        if (kbps <= 0) return 0
        if (baselineKbps != null && baselineKbps > 0) {
            return (kbps.toDouble() / baselineKbps.toDouble() * 100.0)
                .roundToInt()
                .coerceIn(1, 100)
        }
        val normalized = ln(1.0 + kbps.toDouble()) / ln(1.0 + 16_384.0)
        return (normalized * 100.0).roundToInt().coerceIn(1, 100)
    }

    private fun capacityPercent(kbps: Int, baselineKbps: Int?): Int? {
        if (kbps <= 0 || baselineKbps == null || baselineKbps <= 0) return null
        return (kbps.toDouble() / baselineKbps.toDouble() * 100.0)
            .roundToInt()
            .coerceIn(0, MAX_CAPACITY_PERCENT)
    }

    private fun confidenceAgeMultiplier(ageMs: Long?): Float = when {
        ageMs == null || ageMs <= FRESH_RESULT_AGE_MS -> 1f
        ageMs <= STALE_RESULT_AGE_MS -> 0.95f
        ageMs <= THREE_DAY_AGE_MS -> 0.82f
        ageMs <= SEVEN_DAY_AGE_MS -> 0.65f
        else -> 0.45f
    }

    private fun latencyScore(latencyMs: Long): Int = when {
        latencyMs <= 0L -> 0
        latencyMs <= 100L -> 100
        latencyMs >= 2_000L -> 0
        else -> (100f - (latencyMs - 100L) * 100f / 1_900f).roundToInt().coerceIn(0, 100)
    }

    private fun weighted(vararg values: Pair<Int, Float>): Int {
        return values.sumOf { (value, weight) -> value.coerceIn(0, 100) * weight.toDouble() }
            .roundToInt()
            .coerceIn(0, 100)
    }

    private fun safeRate(value: Float): Float = if (value.isFinite()) value.coerceIn(0f, 1f) else 0f

    const val REASON_DIFFERENT_NETWORK = "DIFFERENT_NETWORK"
    const val REASON_STALE_RESULT = "STALE_RESULT"
    const val REASON_BASELINE_NORMALIZED = "BASELINE_NORMALIZED"
    const val REASON_REAL_TRANSFER = "REAL_TRANSFER"
    const val REASON_MULTI_ROUND = "MULTI_ROUND"
    const val REASON_STABLE = "STABLE"
    const val REASON_LOW_TAIL = "LOW_TAIL"
    const val REASON_FAILED_GATE = "FAILED_GATE"
    const val REASON_NEEDS_MORE_EVIDENCE = "NEEDS_MORE_EVIDENCE"
    private const val MAX_CAPACITY_PERCENT = 999
    private const val FRESH_RESULT_AGE_MS = 6L * 60L * 60L * 1_000L
    private const val STALE_RESULT_AGE_MS = 24L * 60L * 60L * 1_000L
    private const val THREE_DAY_AGE_MS = 3L * 24L * 60L * 60L * 1_000L
    private const val SEVEN_DAY_AGE_MS = 7L * 24L * 60L * 60L * 1_000L
}
