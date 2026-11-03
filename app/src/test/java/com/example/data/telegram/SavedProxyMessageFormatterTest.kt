package com.example.data.telegram

import com.example.model.MtProtoProxy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SavedProxyMessageFormatterTest {
    @Test
    fun englishTemplateContainsMeasuredEvidenceAndEscapedConnectionLink() {
        val proxy = readyProxy().copy(
            telegramDownloadKbps = 5_120,
            telegramUploadKbps = 1_536,
            telegramTransferMeasuredAt = 123_456L,
            telegramTransferRounds = 3
        )

        val message = SavedProxyMessageFormatter.format(proxy, isPersian = false)

        assertTrue(message.contains("<b>ProxyX | Saved MTProto Proxy</b>"))
        assertTrue(message.contains("<b>Status:</b> Telegram ready"))
        assertTrue(message.contains("<b>Transfer score:</b> 94/100"))
        assertTrue(message.contains("5.00 MiB/s down | 1.50 MiB/s up"))
        assertTrue(message.contains("server=149.154.167.50&amp;port=443&amp;secret="))
        assertTrue(message.contains("Connect to this proxy in Telegram"))
        assertFalse(message.contains("server=149.154.167.50&port=443"))
    }

    @Test
    fun persianTemplateEscapesDynamicValues() {
        val proxy = readyProxy().copy(
            server = "host<unsafe.example",
            country = "A&B"
        )

        val message = SavedProxyMessageFormatter.format(proxy, isPersian = true)

        assertTrue(message.contains("ProxyX | پروکسی ذخیره‌شده"))
        assertTrue(message.contains("A&amp;B (DE)"))
        assertTrue(message.contains("<code>host&lt;unsafe.example</code>"))
        assertFalse(message.contains("host<unsafe.example"))
    }

    @Test
    fun incompleteStaleAndFailedRatesAreNotSentAsCompletedTransfers() {
        val measured = readyProxy().copy(lastChecked = 100,
            telegramDownloadKbps = 5120, telegramUploadKbps = 1536,
            telegramTransferMeasuredAt = 200, telegramTransferRounds = 3)
        listOf(measured.copy(telegramTransferRounds = 1),
            measured.copy(telegramTransferMeasuredAt = 50),
            measured.copy(benchmarkFailureReason = "TELEGRAM_TRANSFER_FAILED"),
            measured.copy(isWorking = false)).forEach { proxy ->
            assertFalse(SavedProxyMessageFormatter.format(proxy, false).contains("Real transfer"))
            assertFalse(SavedProxyMessageFormatter.format(proxy, true).contains("انتقال واقعی"))
        }
    }

    private fun readyProxy() = MtProtoProxy(
        url = "tg://proxy?server=149.154.167.50&port=443&secret=00112233445566778899aabbccddeeff",
        server = "149.154.167.50",
        port = 443,
        secret = "00112233445566778899aabbccddeeff",
        country = "Germany",
        countryCode = "DE",
        isWorking = true,
        latency = 88L,
        successRate = 1f,
        stability = 0.97f,
        verificationStatus = MtProtoProxy.VERIFICATION_TELEGRAM_READY,
        verificationConfidence = 100,
        protocolLatency = 102L,
        protocolSuccessRate = 1f,
        tcpAttempts = 10,
        tcpSuccesses = 10,
        protocolAttempts = 8,
        protocolConfirmations = 8,
        tcpP90Latency = 96L,
        protocolP90Latency = 114L,
        transferScore = 94,
        benchmarkStage = 3,
        benchmarkRounds = 3
    )
}
