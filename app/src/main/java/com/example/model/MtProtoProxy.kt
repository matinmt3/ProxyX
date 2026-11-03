package com.example.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import android.net.Uri
import android.util.Base64
import androidx.core.net.toUri
import com.example.util.PublicNetworkAddressPolicy
import java.net.IDN
import java.net.Inet6Address
import java.net.InetAddress
import java.util.Locale

@Entity(
    tableName = "mtproto_proxies",
    indices = [
        Index(value = ["lastChecked"]),
        Index(value = ["isWorking", "latency"]),
        Index(
            value = [
                "verificationStatus",
                "benchmarkStage",
                "benchmarkRounds",
                "transferScore"
            ]
        ),
        Index(value = ["countryCode", "transferScore"])
    ]
)
data class MtProtoProxy(
    @PrimaryKey val url: String,
    val server: String,
    val port: Int,
    val secret: String,
    val country: String,
    val countryCode: String,
    val isWorking: Boolean,
    val latency: Long,
    val successRate: Float,
    val stability: Float,
    val score: Int = 0,
    val isFavorite: Boolean = false,
    val recentlyUsed: Long = 0L,
    val lastChecked: Long = 0L,
    val verificationStatus: String = VERIFICATION_UNTESTED,
    val verificationConfidence: Int = 0,
    val protocolLatency: Long = 0L,
    val protocolSuccessRate: Float = 0f,
    val tcpAttempts: Int = 0,
    val tcpSuccesses: Int = 0,
    val protocolAttempts: Int = 0,
    val protocolConfirmations: Int = 0,
    val tcpP90Latency: Long = 0L,
    val tcpJitter: Long = 0L,
    val protocolP90Latency: Long = 0L,
    val protocolJitter: Long = 0L,
    val transferScore: Int = 0,
    val benchmarkStage: Int = 0,
    val benchmarkRounds: Int = 0,
    val continuityRate: Float = 0f,
    val networkFingerprint: String = "",
    val testedNetworkType: String = "",
    val telegramDownloadKbps: Int = 0,
    val telegramUploadKbps: Int = 0,
    val telegramTransferBytes: Long = 0L,
    val telegramTransferMeasuredAt: Long = 0L,
    val benchmarkFailureReason: String = "",
    val telegramTransferRounds: Int = 0,
    val telegramTransferConfidence: Int = 0,
    val telegramNetworkUploadBytes: Long = 0L,
    val telegramNetworkDownloadBytes: Long = 0L,
    @androidx.room.ColumnInfo(defaultValue = "0") val telegramDownloadFloorKbps: Int = 0,
    @androidx.room.ColumnInfo(defaultValue = "0") val telegramUploadFloorKbps: Int = 0,
    @androidx.room.ColumnInfo(defaultValue = "0") val telegramDownloadPeakKbps: Int = 0,
    @androidx.room.ColumnInfo(defaultValue = "0") val telegramUploadPeakKbps: Int = 0
) {
    val verificationLevel: ProxyVerificationLevel
        get() {
            val stored = ProxyVerificationLevel.fromStoredValue(verificationStatus)
            if (stored.rank < ProxyVerificationLevel.MTPROTO_VERIFIED.rank) return stored
            if (!isWorking || latency <= 0 || !hasProtocolEvidence()) {
                return ProxyVerificationLevel.OFFLINE
            }

            // Partial protocol evidence is useful, but it must never be shown as fully ready.
            if (stored == ProxyVerificationLevel.TELEGRAM_READY && !hasReadyEvidence()) {
                return ProxyVerificationLevel.MTPROTO_VERIFIED
            }
            return stored
        }

    val isTelegramVerified: Boolean
        get() = verificationLevel.rank >= ProxyVerificationLevel.MTPROTO_VERIFIED.rank

    val isTelegramReady: Boolean
        get() = verificationLevel == ProxyVerificationLevel.TELEGRAM_READY

    val isTcpReachable: Boolean
        get() = isWorking && latency > 0 &&
            verificationLevel != ProxyVerificationLevel.UNTESTED &&
            verificationLevel != ProxyVerificationLevel.OFFLINE

    private fun hasProtocolEvidence(): Boolean {
        return protocolLatency > 0 &&
            protocolSuccessRate.isFinite() &&
            protocolSuccessRate > 0f
    }

    private fun hasReadyEvidence(): Boolean {
        val tcpRate = if (tcpAttempts > 0) tcpSuccesses.toFloat() / tcpAttempts.toFloat() else 0f
        return protocolAttempts >= 8 &&
            protocolConfirmations == protocolAttempts &&
            protocolSuccessRate.isFinite() &&
            protocolSuccessRate >= 1f &&
            tcpAttempts >= 10 &&
            tcpRate >= 0.90f &&
            stability.isFinite() &&
            stability >= 0.82f &&
            tcpP90Latency > 0L &&
            protocolP90Latency > 0L
    }

    companion object {
        const val VERIFICATION_UNTESTED = "UNTESTED"
        const val VERIFICATION_INCONCLUSIVE = "INCONCLUSIVE"
        const val VERIFICATION_OFFLINE = "OFFLINE"
        const val VERIFICATION_TCP_OPEN = "TCP_OPEN"
        const val VERIFICATION_MTPROTO_VERIFIED = "MTPROTO_VERIFIED"
        const val VERIFICATION_TELEGRAM_READY = "TELEGRAM_READY"

        const val COUNTRY_UNKNOWN_NAME = "Global"
        const val COUNTRY_UNKNOWN_CODE = "UN"

        // Standard secrets are 16 bytes with an optional transport marker. Fake-TLS secrets can
        // additionally carry an SNI hostname after an 0xee marker, so their validated form is
        // intentionally longer.
        private val HEX_SECRET_REGEX = Regex("^[0-9a-fA-F]{32,544}$")
        private val BASE64_SECRET_REGEX = Regex("^[A-Za-z0-9_\\-+/]+={0,2}$")

        fun calculateScore(
            latency: Long,
            successRate: Float,
            stability: Float,
            protocolLatency: Long,
            protocolSuccessRate: Float,
            tcpP90Latency: Long = latency,
            tcpJitter: Long = 0L,
            protocolP90Latency: Long = protocolLatency,
            protocolJitter: Long = 0L
        ): Int {
            if (latency <= 0 ||
                !successRate.isFinite() ||
                !stability.isFinite() ||
                !protocolSuccessRate.isFinite() ||
                successRate <= 0f
            ) return 0

            val safeProtocolRate = if (protocolLatency > 0L) protocolSuccessRate else 0f
            return TransferScoreCalculator.calculate(
                tcpSuccessRate = successRate,
                protocolSuccessRate = safeProtocolRate,
                stability = stability,
                continuityRate = minOf(successRate, safeProtocolRate),
                tcpP90Latency = tcpP90Latency,
                tcpJitter = tcpJitter,
                protocolP90Latency = protocolP90Latency,
                protocolJitter = protocolJitter,
                benchmarkStage = 2,
                benchmarkRounds = 1
            )
        }

        fun parseFromUrl(rawUrl: String): MtProtoProxy? {
            val cleanUrl = HTML_AMPERSAND_REGEX.replace(rawUrl.trim(), "&")
            try {
                val uri = cleanUrl.toUri()
                if (!isSupportedProxyUri(uri)) return null

                val rawServer = uri.getQueryParameter("server")
                    ?.trim()
                    ?.trimEnd('.')
                    ?.removeSurrounding("[", "]")
                    ?: return null
                val server = normalizeServer(rawServer) ?: return null
                val portStr = uri.getQueryParameter("port") ?: return null
                val port = portStr.toIntOrNull() ?: return null
                if (port !in 1..65535) return null
                val secret = normalizeSecret(
                    uri.getQueryParameter("secret") ?: return null
                ) ?: return null
                return createProxy(
                    url = buildMtProtoUrl(server, port, secret),
                    server = server,
                    port = port,
                    secret = secret
                )
            } catch (_: Exception) {
                return null
            }
        }

        private fun isSupportedProxyUri(uri: Uri): Boolean {
            val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return false
            val host = uri.host?.lowercase(Locale.ROOT) ?: return false
            return when (scheme) {
                "tg" -> host == "proxy"
                "http", "https" -> host == "t.me" &&
                    uri.pathSegments.firstOrNull()?.equals("proxy", ignoreCase = true) == true
                else -> false
            }
        }

        private fun normalizeServer(rawServer: String): String? {
            if (rawServer.length !in 1..253 ||
                rawServer.any { it.isWhitespace() || it.isISOControl() } ||
                rawServer.any { it == '/' || it == '?' || it == '#' || it == '%' }
            ) {
                return null
            }

            if (':' in rawServer) {
                val ipv6 = runCatching { InetAddress.getByName(rawServer) }.getOrNull()
                return rawServer
                    .lowercase(Locale.ROOT)
                    .takeIf { ipv6 is Inet6Address && PublicNetworkAddressPolicy.isPublic(ipv6) }
            }

            val server = runCatching {
                IDN.toASCII(rawServer, IDN.USE_STD3_ASCII_RULES)
                    .lowercase(Locale.ROOT)
            }.getOrNull() ?: return null
            if (server.length !in 1..253) return null

            val labels = server.split('.')
            if (labels.any { label ->
                    label.isEmpty() ||
                        label.length > 63 ||
                        label.startsWith('-') ||
                        label.endsWith('-')
                }
            ) {
                return null
            }

            if (labels.all { label -> label.all { it in '0'..'9' } }) {
                if (labels.size != 4) return null
                if (labels.any { label -> label.toIntOrNull()?.let { it in 0..255 } != true }) {
                    return null
                }
                if (!PublicNetworkAddressPolicy.isPublic(InetAddress.getByName(server))) return null
            }
            if (server == "localhost" ||
                server.endsWith(".localhost") ||
                server.endsWith(".local")
            ) {
                return null
            }
            return server
        }

        fun normalizeSecret(rawSecret: String): String? {
            val secret = rawSecret.trim()
            if (secret.length % 2 == 0 && secret.matches(HEX_SECRET_REGEX)) {
                val decodedHex = decodeHexSecret(secret) ?: return null
                return secret.lowercase(Locale.ROOT).takeIf { isValidSecretBytes(decodedHex) }
            }
            val decoded = decodeBase64Secret(secret) ?: return null
            if (!isValidSecretBytes(decoded)) return null
            return decoded.toHexString()
        }

        private fun isValidSecretBytes(bytes: ByteArray): Boolean {
            return bytes.size == 16 ||
                (bytes.size == 17 && bytes.firstOrNull() == PADDED_SECRET_PREFIX) ||
                (bytes.size in 18..MAX_SECRET_BYTES &&
                    bytes.firstOrNull() == FAKE_TLS_PREFIX &&
                    isValidFakeTlsDomain(bytes.copyOfRange(17, bytes.size)))
        }

        private fun isValidFakeTlsDomain(domainBytes: ByteArray): Boolean {
            if (domainBytes.isEmpty() || domainBytes.size > 253 ||
                domainBytes.any { byte -> (byte.toInt() and 0xff) !in 0x21..0x7e }
            ) {
                return false
            }

            val rawDomain = domainBytes.toString(Charsets.US_ASCII).trimEnd('.')
            if (rawDomain.isBlank() || ':' in rawDomain) return false
            val asciiDomain = runCatching {
                IDN.toASCII(rawDomain, IDN.USE_STD3_ASCII_RULES).lowercase(Locale.ROOT)
            }.getOrNull() ?: return false
            val labels = asciiDomain.split('.')
            return labels.size >= 2 &&
                asciiDomain.length <= 253 &&
                labels.none { label ->
                    label.isEmpty() ||
                        label.length > 63 ||
                        label.startsWith('-') ||
                        label.endsWith('-')
                }
        }

        private fun decodeHexSecret(secret: String): ByteArray? {
            return runCatching {
                ByteArray(secret.length / 2) { index ->
                    secret.substring(index * 2, index * 2 + 2).toInt(16).toByte()
                }
            }.getOrNull()
        }

        private fun decodeBase64Secret(secret: String): ByteArray? {
            if (!secret.matches(BASE64_SECRET_REGEX)) return null
            val padded = secret.padEnd(secret.length + ((4 - secret.length % 4) % 4), '=')
            return runCatching {
                Base64.decode(padded, Base64.URL_SAFE or Base64.NO_WRAP)
            }.recoverCatching {
                Base64.decode(padded, Base64.NO_WRAP)
            }.getOrNull()
        }

        private fun ByteArray.toHexString(): String {
            return joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
        }

        private const val MAX_SECRET_BYTES = 272
        private val PADDED_SECRET_PREFIX = 0xdd.toByte()
        private val FAKE_TLS_PREFIX = 0xee.toByte()
        private val HTML_AMPERSAND_REGEX = Regex("&(?:amp|#38|#x26);", RegexOption.IGNORE_CASE)

        private fun createProxy(
            url: String,
            server: String,
            port: Int,
            secret: String
        ): MtProtoProxy {
            return MtProtoProxy(
                url = url,
                server = server,
                port = port,
                secret = secret,
                country = COUNTRY_UNKNOWN_NAME,
                countryCode = COUNTRY_UNKNOWN_CODE,
                isWorking = false,
                latency = 0,
                successRate = 0f,
                stability = 0f
            )
        }

        private fun buildMtProtoUrl(
            server: String,
            port: Int,
            secret: String
        ): String {
            return Uri.Builder()
                .scheme("tg")
                .authority("proxy")
                .appendQueryParameter("server", server)
                .appendQueryParameter("port", port.toString())
                .appendQueryParameter("secret", secret)
                .build()
                .toString()
        }

    }
}

enum class ProxyVerificationLevel(val rank: Int) {
    UNTESTED(0),
    INCONCLUSIVE(1),
    OFFLINE(2),
    TCP_OPEN(3),
    MTPROTO_VERIFIED(4),
    TELEGRAM_READY(5);

    companion object {
        fun fromStoredValue(value: String): ProxyVerificationLevel {
            return entries.firstOrNull { it.name == value } ?: UNTESTED
        }
    }
}
