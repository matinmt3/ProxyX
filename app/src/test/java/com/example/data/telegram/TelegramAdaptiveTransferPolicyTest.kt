package com.example.data.telegram

import com.example.model.MtProtoProxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TelegramAdaptiveTransferPolicyTest {
    @Test
    fun meteredNetworkCapsConfirmationAtTwoMegabytes() {
        val bytes = TelegramAdaptiveTransferPolicy.confirmationBytes(
            proxy = strongProxy(),
            probe = result(
                sampleBytes = TelegramAdaptiveTransferPolicy.PROBE_BYTES.toLong(),
                uploadKbps = 4_096,
                downloadKbps = 8_192,
                isMetered = true
            )
        )

        assertEquals(TelegramAdaptiveTransferPolicy.MIN_CONFIRMATION_BYTES, bytes)
    }

    @Test
    fun strongUnmeteredRouteUsesMaximumConfirmationSize() {
        val bytes = TelegramAdaptiveTransferPolicy.confirmationBytes(
            proxy = strongProxy(),
            probe = result(
                sampleBytes = TelegramAdaptiveTransferPolicy.PROBE_BYTES.toLong(),
                uploadKbps = 4_096,
                downloadKbps = 8_192
            )
        )

        assertEquals(TelegramAdaptiveTransferPolicy.MAX_CONFIRMATION_BYTES, bytes)
    }

    @Test
    fun combineUsesActualBytesAndDurationsAcrossBothRounds() {
        val combined = TelegramAdaptiveTransferPolicy.combine(
            first = result(
                sampleBytes = 512L * 1_024L,
                uploadKbps = 512,
                downloadKbps = 1_024,
                uploadDurationMs = 1_000,
                downloadDurationMs = 500,
                networkUploadBytes = 530_000,
                networkDownloadBytes = 540_000
            ),
            second = result(
                sampleBytes = 2L * 1_024L * 1_024L,
                uploadKbps = 1_024,
                downloadKbps = 2_048,
                uploadDurationMs = 2_000,
                downloadDurationMs = 1_000,
                networkUploadBytes = 2_100_000,
                networkDownloadBytes = 2_200_000
            )
        )

        assertEquals(853, combined.uploadKbps)
        assertEquals(1_707, combined.downloadKbps)
        assertEquals(2, combined.rounds)
        assertEquals(90, combined.confidence)
        assertEquals(2_630_000, combined.networkUploadBytes)
        assertEquals(2_740_000, combined.networkDownloadBytes)
        assertTrue(combined.sampleBytes > TelegramAdaptiveTransferPolicy.MIN_CONFIRMATION_BYTES)
    }

    @Test(expected = IllegalArgumentException::class)
    fun combineRejectsNetworkChangeBetweenRounds() {
        TelegramAdaptiveTransferPolicy.combine(
            result(sampleBytes = 512L, networkFingerprint = "wifi-a"),
            result(sampleBytes = 512L, networkFingerprint = "cell-b")
        )
    }

    private fun strongProxy() = MtProtoProxy(
        url = "tg://proxy?server=1.1.1.1&port=443&secret=0123456789abcdef0123456789abcdef",
        server = "1.1.1.1",
        port = 443,
        secret = "0123456789abcdef0123456789abcdef",
        country = "Global",
        countryCode = "UN",
        isWorking = true,
        latency = 120,
        successRate = 1f,
        stability = 0.95f,
        verificationConfidence = 94,
        benchmarkStage = 3,
        benchmarkRounds = 3,
        continuityRate = 0.95f
    )

    private fun result(
        sampleBytes: Long,
        uploadKbps: Int = 1_024,
        downloadKbps: Int = 2_048,
        uploadDurationMs: Long = 500,
        downloadDurationMs: Long = 250,
        networkFingerprint: String = "wifi-a",
        isMetered: Boolean = false,
        networkUploadBytes: Long = 0,
        networkDownloadBytes: Long = 0
    ) = TelegramTransferResult(
        proxyUrl = "proxy-url",
        uploadKbps = uploadKbps,
        downloadKbps = downloadKbps,
        sampleBytes = sampleBytes,
        uploadDurationMs = uploadDurationMs,
        downloadDurationMs = downloadDurationMs,
        measuredAt = 1_000,
        networkFingerprint = networkFingerprint,
        networkType = "Wi-Fi",
        isMetered = isMetered,
        networkUploadBytes = networkUploadBytes,
        networkDownloadBytes = networkDownloadBytes
    )
}
