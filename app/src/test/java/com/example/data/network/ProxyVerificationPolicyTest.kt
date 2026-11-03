package com.example.data.network

import com.example.model.ProxyVerificationLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProxyVerificationPolicyTest {
    @Test
    fun eightStableProtocolResponsesProduceTelegramReady() {
        val result = ProxyVerificationPolicy.assess(
            tcpSuccessRate = 1f,
            stability = 0.9f,
            tcpAttempts = 10,
            protocolAttempts = 8,
            protocolConfirmations = 8
        )

        assertEquals(ProxyVerificationLevel.TELEGRAM_READY, result.level)
        assertTrue(result.confidence >= 90)
    }

    @Test
    fun fiveOfEightProtocolResponsesAreVerifiedButNotReady() {
        val result = ProxyVerificationPolicy.assess(
            tcpSuccessRate = 1f,
            stability = 0.8f,
            tcpAttempts = 10,
            protocolAttempts = 8,
            protocolConfirmations = 5
        )

        assertEquals(ProxyVerificationLevel.MTPROTO_VERIFIED, result.level)
        assertTrue(result.confidence in 65..90)
    }

    @Test
    fun oneTransientProtocolResponseRemainsTcpOnly() {
        val result = ProxyVerificationPolicy.assess(
            tcpSuccessRate = 1f,
            stability = 0.8f,
            tcpAttempts = 10,
            protocolAttempts = 8,
            protocolConfirmations = 1
        )

        assertEquals(ProxyVerificationLevel.TCP_OPEN, result.level)
    }

    @Test
    fun fullProtocolEvidenceWithPoorStabilityIsNotReady() {
        val result = ProxyVerificationPolicy.assess(
            tcpSuccessRate = 1f,
            stability = 0.60f,
            tcpAttempts = 10,
            protocolAttempts = 8,
            protocolConfirmations = 8
        )

        assertEquals(ProxyVerificationLevel.MTPROTO_VERIFIED, result.level)
    }

    @Test
    fun reachableEndpointWithoutProtocolEvidenceIsTcpOnly() {
        val result = ProxyVerificationPolicy.assess(
            tcpSuccessRate = 0.9f,
            stability = 0.8f,
            tcpAttempts = 10,
            protocolAttempts = 0,
            protocolConfirmations = 0
        )

        assertEquals(ProxyVerificationLevel.TCP_OPEN, result.level)
        assertTrue(result.confidence in 20..55)
    }

    @Test
    fun noTcpResponseIsOfflineRegardlessOfProtocolCounters() {
        val result = ProxyVerificationPolicy.assess(
            tcpSuccessRate = 0f,
            stability = 1f,
            tcpAttempts = 10,
            protocolAttempts = 8,
            protocolConfirmations = 8
        )

        assertEquals(ProxyVerificationLevel.OFFLINE, result.level)
        assertEquals(0, result.confidence)
    }

    @Test
    fun fewerThanTenTcpSamplesCannotBeTelegramReady() {
        val result = ProxyVerificationPolicy.assess(
            tcpSuccessRate = 1f,
            stability = 0.95f,
            tcpAttempts = 9,
            protocolAttempts = 8,
            protocolConfirmations = 8
        )

        assertEquals(ProxyVerificationLevel.MTPROTO_VERIFIED, result.level)
    }

    @Test
    fun oneMissingProtocolConfirmationCannotBeTelegramReady() {
        val result = ProxyVerificationPolicy.assess(
            tcpSuccessRate = 1f,
            stability = 0.95f,
            tcpAttempts = 10,
            protocolAttempts = 8,
            protocolConfirmations = 7
        )

        assertEquals(ProxyVerificationLevel.MTPROTO_VERIFIED, result.level)
    }
}
