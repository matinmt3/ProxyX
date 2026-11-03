package com.example.viewmodel

import com.example.model.MtProtoProxy
import com.example.model.ProxyVerificationLevel
import com.example.model.TransferRankingEvidence
import java.util.Locale

internal object ProxyRanking {
    private data class CountryEvidence(
        val known: Boolean,
        val verifiedCount: Int,
        val verifiedRate: Double,
        val confidenceAdjustedQuality: Double,
        val averageProtocolP90Latency: Double
    )

    fun benchmarkResults(proxies: List<MtProtoProxy>, limit: Int): List<MtProtoProxy> {
        return sort(
            proxies.filter { it.lastChecked > 0L },
            SortBy.BEST
        ).take(limit.coerceAtLeast(0))
    }

    fun sort(proxies: List<MtProtoProxy>, sort: SortBy): List<MtProtoProxy> {
        return when (sort) {
            SortBy.BEST -> proxies.sortedWith(
                compareByDescending<MtProtoProxy>(::hasRealTelegramTransfer)
                    .thenByDescending(::realTelegramTransferWeight)
                    .thenByDescending(::bestEvidenceTier)
                    .thenByDescending { safeRate(it.protocolSuccessRate) }
                    .thenByDescending { safeRate(it.stability) }
                    .thenByDescending { safeRate(it.successRate) }
                    .thenByDescending(::measuredQualityScore)
                    .thenBy(::normalizedProtocolP90Latency)
                    .thenBy(::normalizedProtocolJitter)
                    .thenBy(::normalizedTcpP90Latency)
                    .thenBy(::normalizedTcpJitter)
                    .thenBy(::normalizedProtocolLatency)
                    .thenBy(::normalizedTcpLatency)
                    .thenBy { it.url }
            )
            SortBy.HIGH -> proxies.sortedWith(
                compareByDescending<MtProtoProxy>(::hasRealTelegramTransfer)
                    .thenByDescending { if (hasRealTelegramTransfer(it)) TransferRankingEvidence.download(it) else 0L }
                    .thenByDescending { it.benchmarkFailureReason.isBlank() }
                    .thenByDescending(::highDownloadEvidenceTier)
                    .thenByDescending(TransferRankingEvidence::download)
                    .thenByDescending { safeRate(it.continuityRate) }
                    .thenByDescending { safeRate(it.stability) }
                    .thenByDescending(::measuredQualityScore)
                    .thenBy(::normalizedProtocolP90Latency)
                    .thenBy { it.url }
            )
            SortBy.DOWN_UP -> proxies.sortedWith(
                compareByDescending<MtProtoProxy>(::hasRealTelegramTransfer)
                    .thenByDescending(::realTelegramTransferWeight)
                    .thenBy { it.url }
            )
            SortBy.DOWNLOAD, SortBy.UPLOAD -> proxies.sortedWith(
                compareByDescending<MtProtoProxy>(::hasRealTelegramTransfer)
                    .thenByDescending {
                        if (!hasRealTelegramTransfer(it)) 0L
                        else if (sort == SortBy.DOWNLOAD) TransferRankingEvidence.download(it)
                        else TransferRankingEvidence.upload(it)
                    }
                    .thenByDescending(::isRankable)
                    .thenBy { it.url }
            )
            SortBy.LATENCY -> proxies.sortedWith(
                verifiedFirst()
                    .thenBy(::normalizedTcpLatency)
                    .thenBy(::normalizedProtocolLatency)
                    .thenByDescending { safeRate(it.successRate) }
                    .thenBy { it.url }
            )
            SortBy.PROTOCOL -> proxies.sortedWith(
                verifiedFirst()
                    .thenBy(::normalizedProtocolLatency)
                    .thenByDescending { safeRate(it.protocolSuccessRate) }
                    .thenBy(::normalizedTcpLatency)
                    .thenBy { it.url }
            )
            SortBy.STABILITY -> proxies.sortedWith(
                verifiedFirst()
                    .thenByDescending { safeRate(it.protocolSuccessRate) }
                    .thenByDescending { safeRate(it.successRate) }
                    .thenByDescending { safeRate(it.stability) }
                    .thenByDescending { it.protocolConfirmations.coerceAtLeast(0) }
                    .thenBy(::normalizedProtocolP90Latency)
                    .thenBy(::normalizedProtocolJitter)
                    .thenBy(::normalizedProtocolLatency)
                    .thenBy { it.url }
            )
            SortBy.COUNTRY -> sortByMeasuredCountry(proxies)
        }
    }

    private fun sortByMeasuredCountry(proxies: List<MtProtoProxy>): List<MtProtoProxy> {
        val evidenceByCountry = proxies
            .groupBy { it.countryCode.uppercase(Locale.ROOT) }
            .mapValues { (code, countryProxies) ->
                val tested = countryProxies.filter { it.lastChecked > 0L }
                val verified = tested.filter(::isRankable)
                CountryEvidence(
                    known = code.length == 2 && code != MtProtoProxy.COUNTRY_UNKNOWN_CODE,
                    verifiedCount = verified.size,
                    verifiedRate = if (tested.isEmpty()) {
                        0.0
                    } else {
                        verified.size.toDouble() / tested.size.toDouble()
                    },
                    confidenceAdjustedQuality = confidenceAdjustedCountryQuality(
                        verified.map(::measuredQualityScore)
                    ),
                    averageProtocolP90Latency = averageLong(
                        verified.mapNotNull { proxy ->
                            proxy.protocolP90Latency.takeIf { it > 0L }
                        }
                    )
                )
            }

        fun evidence(proxy: MtProtoProxy): CountryEvidence {
            return evidenceByCountry[proxy.countryCode.uppercase(Locale.ROOT)]
                ?: CountryEvidence(false, 0, 0.0, 0.0, Double.MAX_VALUE)
        }

        return proxies.sortedWith(
            compareByDescending<MtProtoProxy> { evidence(it).known }
                .thenByDescending { evidence(it).verifiedCount > 0 }
                .thenByDescending { evidence(it).confidenceAdjustedQuality }
                .thenByDescending { evidence(it).verifiedRate }
                .thenBy { evidence(it).averageProtocolP90Latency }
                .thenByDescending { evidence(it).verifiedCount }
                .thenBy { it.country.lowercase(Locale.ROOT) }
                .thenByDescending(::verificationRank)
                .thenByDescending(::isRankable)
                .thenByDescending(::measuredQualityScore)
                .thenBy(::normalizedProtocolLatency)
                .thenBy { it.url }
        )
    }

    private fun verifiedFirst(): Comparator<MtProtoProxy> {
        return compareByDescending<MtProtoProxy>(::isRankable)
            .thenByDescending(::verificationRank)
    }

    private fun bestEvidenceTier(proxy: MtProtoProxy): Int {
        if (proxy.benchmarkStage >= 3 && proxy.benchmarkRounds >= 3 && isRankable(proxy)) return 8
        if (proxy.benchmarkStage >= 2 && isRankable(proxy)) return 7
        if (isStrictBestCandidate(proxy)) return 6
        return verificationRank(proxy)
    }

    private fun isStrictBestCandidate(proxy: MtProtoProxy): Boolean {
        return proxy.benchmarkFailureReason.isBlank() &&
            proxy.isTelegramReady &&
            proxy.tcpAttempts >= MIN_STRICT_TCP_ATTEMPTS &&
            proxy.tcpSuccesses >= MIN_STRICT_TCP_SUCCESSES &&
            safeRate(proxy.successRate) >= MIN_STRICT_TCP_RATE &&
            proxy.protocolAttempts >= MIN_STRICT_PROTOCOL_ATTEMPTS &&
            proxy.protocolConfirmations == proxy.protocolAttempts &&
            safeRate(proxy.protocolSuccessRate) >= 1f &&
            safeRate(proxy.stability) >= MIN_STRICT_STABILITY &&
            proxy.tcpP90Latency > 0L &&
            proxy.protocolP90Latency > 0L
    }

    private fun highDownloadEvidenceTier(proxy: MtProtoProxy): Int {
        if (proxy.benchmarkStage >= 3 && proxy.benchmarkRounds >= 3 && isRankable(proxy)) return 2
        if (proxy.lastChecked == 0L ||
            proxy.verificationStatus == MtProtoProxy.VERIFICATION_UNTESTED
        ) {
            return 1
        }
        return 0
    }

    private fun isRankable(proxy: MtProtoProxy): Boolean {
        return proxy.isTelegramVerified &&
            proxy.latency > 0L &&
            proxy.protocolLatency > 0L &&
            proxy.benchmarkFailureReason.isBlank()
    }

    private fun hasRealTelegramTransfer(proxy: MtProtoProxy): Boolean {
        return TransferRankingEvidence.isUsableMeasurement(proxy)
    }

    private fun realTelegramTransferWeight(proxy: MtProtoProxy): Long {
        if (!hasRealTelegramTransfer(proxy)) return 0L
        return TransferRankingEvidence.balanced(proxy)
    }

    private fun verificationRank(proxy: MtProtoProxy): Int {
        return when (proxy.verificationLevel) {
            ProxyVerificationLevel.TELEGRAM_READY -> 4
            ProxyVerificationLevel.MTPROTO_VERIFIED -> 3
            ProxyVerificationLevel.TCP_OPEN -> 2
            ProxyVerificationLevel.INCONCLUSIVE -> 1
            ProxyVerificationLevel.OFFLINE -> 0
            ProxyVerificationLevel.UNTESTED -> 1
        }
    }

    private fun normalizedTcpLatency(proxy: MtProtoProxy): Long {
        return proxy.latency.takeIf { it > 0L } ?: Long.MAX_VALUE
    }

    private fun normalizedProtocolLatency(proxy: MtProtoProxy): Long {
        return proxy.protocolLatency.takeIf { it > 0L } ?: Long.MAX_VALUE
    }

    private fun normalizedTcpP90Latency(proxy: MtProtoProxy): Long {
        return proxy.tcpP90Latency.takeIf { it > 0L } ?: Long.MAX_VALUE
    }

    private fun normalizedProtocolP90Latency(proxy: MtProtoProxy): Long {
        return proxy.protocolP90Latency.takeIf { it > 0L } ?: Long.MAX_VALUE
    }

    private fun normalizedTcpJitter(proxy: MtProtoProxy): Long {
        return proxy.tcpJitter.takeIf { it >= 0L } ?: Long.MAX_VALUE
    }

    private fun normalizedProtocolJitter(proxy: MtProtoProxy): Long {
        return proxy.protocolJitter.takeIf { it >= 0L } ?: Long.MAX_VALUE
    }

    private fun safeRate(value: Float): Float {
        return if (value.isFinite()) value.coerceIn(0f, 1f) else 0f
    }

    private fun measuredQualityScore(proxy: MtProtoProxy): Int {
        if (proxy.transferScore > 0) return proxy.transferScore.coerceIn(0, 100)
        if (proxy.score > 0) return proxy.score.coerceIn(0, 100)
        return MtProtoProxy.calculateScore(
            latency = proxy.latency,
            successRate = proxy.successRate,
            stability = proxy.stability,
            protocolLatency = proxy.protocolLatency,
            protocolSuccessRate = proxy.protocolSuccessRate,
            tcpP90Latency = proxy.tcpP90Latency,
            tcpJitter = proxy.tcpJitter,
            protocolP90Latency = proxy.protocolP90Latency,
            protocolJitter = proxy.protocolJitter
        )
    }

    private fun confidenceAdjustedCountryQuality(values: List<Int>): Double {
        if (values.isEmpty()) return 0.0
        val average = values.average()
        val confidence = 0.6 + 0.08 * values.size.coerceAtMost(5)
        return average * confidence
    }

    private fun averageLong(values: List<Long>): Double {
        return if (values.isEmpty()) Double.MAX_VALUE else values.average()
    }

    private const val MIN_STRICT_TCP_ATTEMPTS = 10
    private const val MIN_STRICT_TCP_SUCCESSES = 9
    private const val MIN_STRICT_TCP_RATE = 0.90f
    private const val MIN_STRICT_PROTOCOL_ATTEMPTS = 8
    private const val MIN_STRICT_STABILITY = 0.82f
}
