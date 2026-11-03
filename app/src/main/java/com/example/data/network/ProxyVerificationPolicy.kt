package com.example.data.network

import com.example.model.MtProtoProxy
import com.example.model.ProxyVerificationLevel
import kotlin.math.roundToInt

internal data class VerificationAssessment(
    val level: ProxyVerificationLevel,
    val confidence: Int
)

internal object ProxyVerificationPolicy {
    fun assess(
        tcpSuccessRate: Float,
        stability: Float,
        tcpAttempts: Int,
        protocolAttempts: Int,
        protocolConfirmations: Int
    ): VerificationAssessment {
        val safeTcpRate = tcpSuccessRate.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 0f
        val safeStability = stability.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 0f
        if (safeTcpRate <= 0f) {
            return VerificationAssessment(ProxyVerificationLevel.OFFLINE, 0)
        }

        val safeAttempts = protocolAttempts.coerceAtLeast(0)
        val confirmations = protocolConfirmations.coerceIn(0, safeAttempts)
        val confirmationRate = if (safeAttempts > 0) {
            confirmations.toFloat() / safeAttempts.toFloat()
        } else {
            0f
        }

        if (tcpAttempts >= MIN_READY_TCP_ATTEMPTS &&
            safeAttempts >= MIN_READY_PROTOCOL_ATTEMPTS &&
            confirmations >= MIN_READY_CONFIRMATIONS &&
            confirmationRate >= MIN_READY_PROTOCOL_RATE &&
            safeTcpRate >= MIN_READY_TCP_RATE &&
            safeStability >= MIN_READY_STABILITY
        ) {
            val confidence = 84f + safeTcpRate * 5f + confirmationRate * 5f + safeStability * 6f
            return VerificationAssessment(
                level = ProxyVerificationLevel.TELEGRAM_READY,
                confidence = confidence.roundToInt().coerceIn(85, 100)
            )
        }

        if (safeAttempts >= MIN_VERIFIED_ATTEMPTS &&
            confirmations >= MIN_VERIFIED_CONFIRMATIONS &&
            confirmationRate >= MIN_VERIFIED_PROTOCOL_RATE
        ) {
            val confidence = 58f + confirmationRate * 17f + safeTcpRate * 8f + safeStability * 7f
            return VerificationAssessment(
                level = ProxyVerificationLevel.MTPROTO_VERIFIED,
                confidence = confidence.roundToInt().coerceIn(65, 90)
            )
        }

        val confidence = 18f + safeTcpRate * 18f + safeStability * 10f
        return VerificationAssessment(
            level = ProxyVerificationLevel.TCP_OPEN,
            confidence = confidence.roundToInt().coerceIn(20, 55)
        )
    }

    fun adjustScore(rawScore: Int, level: ProxyVerificationLevel): Int {
        val multiplier = when (level) {
            ProxyVerificationLevel.TELEGRAM_READY -> 1.0
            ProxyVerificationLevel.MTPROTO_VERIFIED -> 0.82
            ProxyVerificationLevel.TCP_OPEN -> 0.35
            ProxyVerificationLevel.INCONCLUSIVE -> 0.0
            ProxyVerificationLevel.OFFLINE,
            ProxyVerificationLevel.UNTESTED -> 0.0
        }
        return (rawScore.coerceIn(0, 100) * multiplier).roundToInt().coerceIn(0, 100)
    }

    fun storedValue(level: ProxyVerificationLevel): String {
        return when (level) {
            ProxyVerificationLevel.UNTESTED -> MtProtoProxy.VERIFICATION_UNTESTED
            ProxyVerificationLevel.INCONCLUSIVE -> MtProtoProxy.VERIFICATION_INCONCLUSIVE
            ProxyVerificationLevel.OFFLINE -> MtProtoProxy.VERIFICATION_OFFLINE
            ProxyVerificationLevel.TCP_OPEN -> MtProtoProxy.VERIFICATION_TCP_OPEN
            ProxyVerificationLevel.MTPROTO_VERIFIED -> MtProtoProxy.VERIFICATION_MTPROTO_VERIFIED
            ProxyVerificationLevel.TELEGRAM_READY -> MtProtoProxy.VERIFICATION_TELEGRAM_READY
        }
    }

    private const val MIN_READY_TCP_ATTEMPTS = ProxyVerifier.STRICT_TCP_ATTEMPTS
    private const val MIN_READY_PROTOCOL_ATTEMPTS = ProxyVerifier.STRICT_PROTOCOL_ATTEMPTS
    private const val MIN_READY_CONFIRMATIONS = ProxyVerifier.STRICT_PROTOCOL_ATTEMPTS
    private const val MIN_READY_PROTOCOL_RATE = 1f
    private const val MIN_READY_TCP_RATE = 0.90f
    private const val MIN_READY_STABILITY = 0.82f
    private const val MIN_VERIFIED_ATTEMPTS = 5
    private const val MIN_VERIFIED_CONFIRMATIONS = 5
    private const val MIN_VERIFIED_PROTOCOL_RATE = 0.625f
}
