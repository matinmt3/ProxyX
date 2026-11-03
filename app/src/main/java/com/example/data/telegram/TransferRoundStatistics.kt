package com.example.data.telegram

import kotlin.math.roundToInt

/** Typical rate and conservative observed floor, not a predicted success probability. */
object TransferRoundStatistics {
    fun summarize(rounds: List<TelegramTransferResult>): TelegramTransferResult {
        require(rounds.isNotEmpty())
        val first = rounds.first()
        require(first.networkFingerprint.isNotBlank()) { "Transfer network is unknown." }
        require(rounds.all {
            it.proxyUrl == first.proxyUrl && it.networkFingerprint == first.networkFingerprint &&
                it.uploadKbps > 0 && it.downloadKbps > 0 && it.sampleBytes > 0 &&
                it.uploadDurationMs > 0 && it.downloadDurationMs > 0
        }) { "Transfer samples are incomplete or belong to different routes." }
        // The short screening probe does not determine the sustained rate when confirmations exist.
        val largest = rounds.maxOf { it.sampleBytes }
        val comparable = rounds.filter { it.sampleBytes == largest }
        val down = comparable.map { it.downloadKbps }.sorted()
        val up = comparable.map { it.uploadKbps }.sorted()
        val consistency = minOf(down.first().toDouble() / down.last(), up.first().toDouble() / up.last())
        val evidenceScore = ((if (comparable.size >= 2) 65 else 40) + consistency * 25).roundToInt()
        return rounds.last().copy(
            uploadKbps = median(up), downloadKbps = median(down),
            uploadFloorKbps = up.first(), downloadFloorKbps = down.first(),
            uploadPeakKbps = up.last(), downloadPeakKbps = down.last(),
            sampleBytes = rounds.sumOf { it.sampleBytes },
            uploadDurationMs = rounds.sumOf { it.uploadDurationMs },
            downloadDurationMs = rounds.sumOf { it.downloadDurationMs },
            measuredAt = rounds.maxOf { it.measuredAt }, rounds = rounds.size,
            confidence = evidenceScore.coerceIn(0, 90),
            networkUploadBytes = rounds.sumOf { it.networkUploadBytes },
            networkDownloadBytes = rounds.sumOf { it.networkDownloadBytes }
        )
    }

    private fun median(sorted: List<Int>): Int = if (sorted.size % 2 == 1) sorted[sorted.size / 2]
    else ((sorted[sorted.size / 2 - 1].toLong() + sorted[sorted.size / 2]) / 2).toInt()
}
