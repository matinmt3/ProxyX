package com.example.data.telegram

import com.example.model.MtProtoProxy
import com.example.model.ProxyVerificationLevel
import com.example.model.TransferRankingEvidence
import java.util.Locale

internal object SavedProxyMessageFormatter {
    fun format(proxy: MtProtoProxy, isPersian: Boolean): String {
        val country = buildCountryLabel(proxy)
        val stability = (proxy.stability.coerceIn(0f, 1f) * 100f).toInt()
        val protocolLatency = proxy.protocolLatency.takeIf { it > 0L }
        val tcpLatency = proxy.latency.takeIf { it > 0L }
        val realTransfer = buildRealTransferLabel(proxy, isPersian)
        val title = if (isPersian) "ProxyX | پروکسی ذخیره‌شده" else "ProxyX | Saved MTProto Proxy"
        val connectLabel = if (isPersian) {
            "اتصال به پروکسی در تلگرام"
        } else {
            "Connect to this proxy in Telegram"
        }

        return buildString {
            append("<b>").append(escapeHtml(title)).appendLine("</b>")
            appendLine(SEPARATOR)
            appendLabel(
                if (isPersian) "کشور" else "Country",
                country
            )
            appendLabel(
                if (isPersian) "وضعیت" else "Status",
                verificationLabel(proxy.verificationLevel, isPersian)
            )
            protocolLatency?.let {
                appendLabel(if (isPersian) "پینگ تلگرام" else "Telegram RTT", "$it ms")
            }
            tcpLatency?.let {
                appendLabel(if (isPersian) "پینگ TCP" else "TCP RTT", "$it ms")
            }
            appendLabel(if (isPersian) "پایداری" else "Stability", "$stability%")
            appendLabel(
                if (isPersian) "امتیاز انتقال" else "Transfer score",
                "${proxy.transferScore.coerceIn(0, 100)}/100"
            )
            realTransfer?.let {
                appendLabel(if (isPersian) "انتقال واقعی" else "Real transfer", it)
            }
            appendLine(SEPARATOR)
            append("<b>")
                .append(if (isPersian) "سرور:" else "Server:")
                .append("</b> <code>")
                .append(escapeHtml(proxy.server))
                .appendLine("</code>")
            append("<b>")
                .append(if (isPersian) "پورت:" else "Port:")
                .append("</b> <code>")
                .append(proxy.port)
                .appendLine("</code>")
            appendLine()
            append("<a href=\"")
                .append(escapeHtml(proxy.url))
                .append("\">")
                .append(escapeHtml(connectLabel))
                .appendLine("</a>")
            append("<i>")
                .append(if (isPersian) "ذخیره‌شده با ProxyX" else "Saved with ProxyX")
                .append("</i>")
        }
    }

    private fun StringBuilder.appendLabel(label: String, value: String) {
        append("<b>")
            .append(escapeHtml(label))
            .append(":</b> ")
            .appendLine(escapeHtml(value))
    }

    private fun buildCountryLabel(proxy: MtProtoProxy): String {
        val country = proxy.country.takeUnless {
            it.isBlank() || it.equals(MtProtoProxy.COUNTRY_UNKNOWN_NAME, ignoreCase = true)
        }
        val countryCode = proxy.countryCode.takeUnless {
            it.isBlank() || it.equals(MtProtoProxy.COUNTRY_UNKNOWN_CODE, ignoreCase = true)
        }
        return when {
            country != null && countryCode != null -> "$country ($countryCode)"
            country != null -> country
            countryCode != null -> countryCode
            else -> MtProtoProxy.COUNTRY_UNKNOWN_NAME
        }
    }

    private fun verificationLabel(level: ProxyVerificationLevel, isPersian: Boolean): String {
        return if (isPersian) {
            when (level) {
                ProxyVerificationLevel.TELEGRAM_READY -> "آماده تلگرام"
                ProxyVerificationLevel.MTPROTO_VERIFIED -> "تأییدشده MTProto"
                ProxyVerificationLevel.TCP_OPEN -> "فقط TCP باز"
                ProxyVerificationLevel.INCONCLUSIVE -> "شواهد ناکافی"
                ProxyVerificationLevel.OFFLINE -> "آفلاین"
                ProxyVerificationLevel.UNTESTED -> "تست‌نشده"
            }
        } else {
            when (level) {
                ProxyVerificationLevel.TELEGRAM_READY -> "Telegram ready"
                ProxyVerificationLevel.MTPROTO_VERIFIED -> "MTProto verified"
                ProxyVerificationLevel.TCP_OPEN -> "TCP open only"
                ProxyVerificationLevel.INCONCLUSIVE -> "Insufficient evidence"
                ProxyVerificationLevel.OFFLINE -> "Offline"
                ProxyVerificationLevel.UNTESTED -> "Untested"
            }
        }
    }

    private fun buildRealTransferLabel(proxy: MtProtoProxy, isPersian: Boolean): String? {
        if (!TransferRankingEvidence.isUsableMeasurement(proxy)) return null
        val download = formatRate(proxy.telegramDownloadKbps)
        val upload = formatRate(proxy.telegramUploadKbps)
        return if (isPersian) {
            "دانلود $download | آپلود $upload"
        } else {
            "$download down | $upload up"
        }
    }

    private fun formatRate(kbps: Int): String {
        return if (kbps >= 1024) {
            String.format(Locale.US, "%.2f MiB/s", kbps / 1024.0)
        } else {
            "$kbps KiB/s"
        }
    }

    private fun escapeHtml(value: String): String {
        return buildString(value.length) {
            value.forEach { character ->
                append(
                    when (character) {
                        '&' -> "&amp;"
                        '<' -> "&lt;"
                        '>' -> "&gt;"
                        '"' -> "&quot;"
                        '\'' -> "&#39;"
                        else -> character
                    }
                )
            }
        }
    }

    private const val SEPARATOR = "━━━━━━━━━━━━━━━━━━"
}
