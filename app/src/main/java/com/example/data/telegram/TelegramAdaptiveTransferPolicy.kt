package com.example.data.telegram

import com.example.model.MtProtoProxy
import kotlin.math.abs
import kotlin.math.roundToInt

object TelegramAdaptiveTransferPolicy {
    const val PROBE_BYTES = 512 * 1024
    const val MIN_CONFIRMATION_BYTES = 2 * 1024 * 1024
    const val DEFAULT_CONFIRMATION_BYTES = 4 * 1024 * 1024
    const val MAX_CONFIRMATION_BYTES = 8 * 1024 * 1024

    fun confirmationBytes(
        proxy: MtProtoProxy,
        probe: TelegramTransferResult,
        maximumBytes: Int = MAX_CONFIRMATION_BYTES
    ): Int {
        val maximum = maximumBytes.coerceIn(MIN_CONFIRMATION_BYTES, MAX_CONFIRMATION_BYTES)
        if (probe.uploadKbps <= 0 || probe.downloadKbps <= 0) return 0
        if (probe.isMetered) return minOf(MIN_CONFIRMATION_BYTES, maximum)
        val strongRoute = proxy.benchmarkStage >= 3 &&
            proxy.benchmarkRounds >= 3 &&
            proxy.verificationConfidence >= 85 &&
            proxy.stability >= 0.82f &&
            proxy.continuityRate >= 0.82f
        return when {
            strongRoute && minOf(probe.downloadKbps, probe.uploadKbps) >= 2_048 -> maximum
            minOf(probe.downloadKbps, probe.uploadKbps) >= 768 ->
                minOf(DEFAULT_CONFIRMATION_BYTES, maximum)
            else -> minOf(MIN_CONFIRMATION_BYTES, maximum)
        }
    }

    fun combine(first: TelegramTransferResult, second: TelegramTransferResult): TelegramTransferResult {
        require(first.proxyUrl == second.proxyUrl) { "Transfer rounds belong to different proxies." }
        require(first.networkFingerprint == second.networkFingerprint) {
            "The active network changed between transfer rounds."
        }
        val totalBytes = first.sampleBytes + second.sampleBytes
        val uploadDuration = first.uploadDurationMs + second.uploadDurationMs
        val downloadDuration = first.downloadDurationMs + second.downloadDurationMs
        val uploadKbps = throughputKbps(totalBytes, uploadDuration)
        val downloadKbps = throughputKbps(totalBytes, downloadDuration)
        val rateConsistency = minOf(
            consistency(first.uploadKbps, second.uploadKbps),
            consistency(first.downloadKbps, second.downloadKbps)
        )
        val hasNetworkAccounting = first.networkUploadBytes + second.networkUploadBytes > 0L &&
            first.networkDownloadBytes + second.networkDownloadBytes > 0L
        val confidence = (
            72f + rateConsistency * 20f + if (hasNetworkAccounting) 8f else 0f
        ).roundToInt().coerceIn(0, 100)
        return TelegramTransferResult(
            proxyUrl = first.proxyUrl,
            uploadKbps = uploadKbps,
            downloadKbps = downloadKbps,
            sampleBytes = totalBytes,
            uploadDurationMs = uploadDuration,
            downloadDurationMs = downloadDuration,
            measuredAt = maxOf(first.measuredAt, second.measuredAt),
            networkFingerprint = first.networkFingerprint,
            networkType = second.networkType,
            isMetered = first.isMetered || second.isMetered,
            rounds = first.rounds + second.rounds,
            confidence = confidence,
            networkUploadBytes = first.networkUploadBytes + second.networkUploadBytes,
            networkDownloadBytes = first.networkDownloadBytes + second.networkDownloadBytes
        )
    }

    fun singleRoundConfidence(result: TelegramTransferResult): Int {
        val accounted = result.networkUploadBytes > 0L && result.networkDownloadBytes > 0L
        return if (accounted) 68 else 58
    }

    private fun consistency(first: Int, second: Int): Float {
        if (first <= 0 || second <= 0) return 0f
        val largest = maxOf(first, second).toFloat()
        return (1f - abs(first - second) / largest).coerceIn(0f, 1f)
    }

    private fun throughputKbps(bytes: Long, durationMs: Long): Int {
        if (bytes <= 0L || durationMs <= 0L) return 0
        return (bytes.toDouble() * 1_000.0 / durationMs.toDouble() / 1_024.0)
            .roundToInt()
            .coerceAtLeast(1)
    }
}
