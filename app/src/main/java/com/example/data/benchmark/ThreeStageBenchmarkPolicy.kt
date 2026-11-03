package com.example.data.benchmark

import com.example.model.MtProtoProxy

object ThreeStageBenchmarkPolicy {
    fun passesScreening(proxy: MtProtoProxy): Boolean {
        return screeningFailureReason(proxy).isEmpty()
    }

    fun passesStrict(proxy: MtProtoProxy): Boolean {
        return strictFailureReason(proxy).isEmpty()
    }

    fun screeningFailureReason(proxy: MtProtoProxy): String {
        val tcpRate = rate(proxy.tcpSuccesses, proxy.tcpAttempts)
        val protocolRate = rate(proxy.protocolConfirmations, proxy.protocolAttempts)
        return when {
            proxy.verificationStatus == MtProtoProxy.VERIFICATION_INCONCLUSIVE ->
                BenchmarkFailureReason.INSUFFICIENT_EVIDENCE
            !proxy.isWorking -> BenchmarkFailureReason.TCP_UNREACHABLE
            proxy.tcpAttempts < 3 -> BenchmarkFailureReason.TCP_INCOMPLETE
            tcpRate < SCREENING_TCP_RATE -> BenchmarkFailureReason.TCP_SUCCESS_RATE_LOW
            proxy.protocolAttempts < 1 -> BenchmarkFailureReason.MTPROTO_NOT_ATTEMPTED
            proxy.protocolConfirmations < 1 || protocolRate < SCREENING_PROTOCOL_RATE ->
                BenchmarkFailureReason.MTPROTO_UNCONFIRMED
            else -> ""
        }
    }

    fun strictFailureReason(proxy: MtProtoProxy): String {
        val tcpRate = rate(proxy.tcpSuccesses, proxy.tcpAttempts)
        val protocolRate = rate(proxy.protocolConfirmations, proxy.protocolAttempts)
        return when {
            proxy.verificationStatus == MtProtoProxy.VERIFICATION_INCONCLUSIVE ->
                BenchmarkFailureReason.INSUFFICIENT_EVIDENCE
            !proxy.isWorking -> BenchmarkFailureReason.TCP_UNREACHABLE
            proxy.tcpAttempts < 8 -> BenchmarkFailureReason.TCP_INCOMPLETE
            tcpRate < STRICT_TCP_RATE -> BenchmarkFailureReason.TCP_SUCCESS_RATE_LOW
            proxy.protocolAttempts < 5 -> BenchmarkFailureReason.MTPROTO_NOT_ATTEMPTED
            proxy.protocolConfirmations < 5 || protocolRate < STRICT_PROTOCOL_RATE ->
                BenchmarkFailureReason.MTPROTO_UNCONFIRMED
            // Jitter is a quality signal, not proof that file transfer is impossible.
            !proxy.stability.isFinite() || proxy.stability !in 0f..1f ->
                BenchmarkFailureReason.STABILITY_LOW
            proxy.protocolP90Latency <= 0L -> BenchmarkFailureReason.P90_MISSING
            proxy.transferScore <= 0 -> BenchmarkFailureReason.TRANSFER_SCORE_ZERO
            else -> ""
        }
    }

    fun selectFinalists(strictResults: List<MtProtoProxy>): List<MtProtoProxy> {
        val qualified = strictResults.filter(::passesStrict)
        return qualified.distinctBy { it.url }.sortedWith(finalistComparator)
    }

    fun finalistCount(qualifiedCount: Int): Int {
        return qualifiedCount.coerceAtLeast(0)
    }

    fun passesFinalist(proxy: MtProtoProxy, requiredRounds: Int = FINALIST_REQUIRED_ROUNDS): Boolean {
        return finalistFailureReason(proxy, requiredRounds).isEmpty()
    }

    fun finalistFailureReason(
        proxy: MtProtoProxy,
        requiredRounds: Int = FINALIST_REQUIRED_ROUNDS
    ): String {
        return when {
            proxy.benchmarkStage < BenchmarkStage.FINALIST.value ||
                proxy.benchmarkRounds < requiredRounds.coerceAtLeast(FINALIST_REQUIRED_ROUNDS) ->
                BenchmarkFailureReason.FINALIST_INCOMPLETE
            !proxy.isTelegramVerified -> BenchmarkFailureReason.MTPROTO_UNCONFIRMED
            !proxy.isWorking -> BenchmarkFailureReason.TCP_UNREACHABLE
            !proxy.continuityRate.isFinite() || proxy.continuityRate < FINALIST_CONTINUITY_RATE ->
                BenchmarkFailureReason.CONTINUITY_LOST
            !proxy.protocolSuccessRate.isFinite() ||
                proxy.protocolSuccessRate < FINALIST_PROTOCOL_RATE ->
                BenchmarkFailureReason.MTPROTO_UNCONFIRMED
            !proxy.stability.isFinite() || proxy.stability !in 0f..1f ->
                BenchmarkFailureReason.STABILITY_LOW
            proxy.protocolP90Latency <= 0L -> BenchmarkFailureReason.P90_MISSING
            proxy.transferScore <= 0 -> BenchmarkFailureReason.TRANSFER_SCORE_ZERO
            else -> ""
        }
    }

    private fun rate(successes: Int, attempts: Int): Float {
        return if (attempts > 0) {
            successes.coerceIn(0, attempts).toFloat() / attempts.toFloat()
        } else {
            0f
        }
    }

    private val finalistComparator =
        compareByDescending<MtProtoProxy> { it.transferScore }
            .thenByDescending { it.continuityRate }
            .thenByDescending { it.protocolSuccessRate }
            .thenBy { it.protocolP90Latency.takeIf { value -> value > 0L } ?: Long.MAX_VALUE }
            .thenBy { it.protocolJitter }
            .thenBy { it.url }

    private const val SCREENING_TCP_RATE = 2f / 3f
    private const val SCREENING_PROTOCOL_RATE = 0.5f
    private const val STRICT_TCP_RATE = 0.80f
    private const val STRICT_PROTOCOL_RATE = 0.625f
    private const val FINALIST_REQUIRED_ROUNDS = 3
    private const val FINALIST_CONTINUITY_RATE = 0.80f
    private const val FINALIST_PROTOCOL_RATE = 0.75f
}

object BenchmarkFailureReason {
    const val INSUFFICIENT_EVIDENCE = "INSUFFICIENT_EVIDENCE"
    const val TCP_UNREACHABLE = "TCP_UNREACHABLE"
    const val TCP_INCOMPLETE = "TCP_INCOMPLETE"
    const val TCP_SUCCESS_RATE_LOW = "TCP_SUCCESS_RATE_LOW"
    const val MTPROTO_NOT_ATTEMPTED = "MTPROTO_NOT_ATTEMPTED"
    const val MTPROTO_UNCONFIRMED = "MTPROTO_UNCONFIRMED"
    const val STABILITY_LOW = "STABILITY_LOW"
    const val P90_MISSING = "P90_MISSING"
    const val TRANSFER_SCORE_ZERO = "TRANSFER_SCORE_ZERO"
    const val NOT_SELECTED_FINALIST = "NOT_SELECTED_FINALIST"
    const val FINALIST_INCOMPLETE = "FINALIST_INCOMPLETE"
    const val CONTINUITY_LOST = "CONTINUITY_LOST"
}
