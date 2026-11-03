package com.example.model

/** A ranking weight, never displayed as a measured speed. */
object TransferRankingEvidence {
    fun isUsableMeasurement(proxy: MtProtoProxy): Boolean =
        proxy.isTelegramVerified && proxy.telegramTransferMeasuredAt > 0L &&
            proxy.telegramTransferMeasuredAt >= proxy.lastChecked &&
            proxy.telegramTransferRounds >= 3 &&
            proxy.telegramDownloadKbps > 0 && proxy.telegramUploadKbps > 0 &&
            (proxy.benchmarkFailureReason.isBlank() || proxy.benchmarkFailureReason == "STABILITY_LOW")

    fun download(proxy: MtProtoProxy): Long = if (isUsableMeasurement(proxy))
        weight(proxy.telegramDownloadKbps, proxy.telegramDownloadFloorKbps) else 0L
    fun upload(proxy: MtProtoProxy): Long = if (isUsableMeasurement(proxy))
        weight(proxy.telegramUploadKbps, proxy.telegramUploadFloorKbps) else 0L
    fun balanced(proxy: MtProtoProxy): Long = download(proxy) * 3L + upload(proxy) * 2L
    private fun weight(typical: Int, floor: Int): Long = if (typical <= 0) 0L else
        (if (floor > 0) minOf(floor, typical).toLong() else typical.toLong() * 6L / 10L) * 7L +
            typical.toLong() * 3L
}
