package com.example.data.benchmark

import com.example.model.MtProtoProxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThreeStageBenchmarkPolicyTest {
    @Test
    fun screeningRejectsTcpOnlyEndpoint() {
        val tcpOnly = proxy("tcp").copy(
            isWorking = true,
            tcpAttempts = 3,
            tcpSuccesses = 3,
            protocolAttempts = 2,
            protocolConfirmations = 0
        )

        assertFalse(ThreeStageBenchmarkPolicy.passesScreening(tcpOnly))
        assertEquals(
            BenchmarkFailureReason.MTPROTO_UNCONFIRMED,
            ThreeStageBenchmarkPolicy.screeningFailureReason(tcpOnly)
        )
    }

    @Test
    fun finalistSelectionIncludesEveryQualifiedProxyWithoutASilentCap() {
        val qualified = (1..100).map { index -> strictProxy(index) }
        val weak = proxy("weak").copy(
            isWorking = true,
            tcpAttempts = 10,
            tcpSuccesses = 10,
            protocolAttempts = 8,
            protocolConfirmations = 2,
            transferScore = 100
        )
        val finalists = ThreeStageBenchmarkPolicy.selectFinalists(qualified + weak)

        assertEquals(100, finalists.size)
        assertFalse(weak in finalists)
        assertEquals(100, finalists.first().transferScore)
        assertTrue(finalists.all(ThreeStageBenchmarkPolicy::passesStrict))
    }

    @Test
    fun finalistPassRequiresThreeRoundsAndVerifiedProtocolEvidence() {
        val passed = strictProxy(1).copy(
            verificationStatus = MtProtoProxy.VERIFICATION_MTPROTO_VERIFIED,
            benchmarkStage = 3,
            benchmarkRounds = 3
        )

        assertTrue(ThreeStageBenchmarkPolicy.passesFinalist(passed))
        assertFalse(ThreeStageBenchmarkPolicy.passesFinalist(passed.copy(benchmarkRounds = 2)))
        assertEquals(
            BenchmarkFailureReason.FINALIST_INCOMPLETE,
            ThreeStageBenchmarkPolicy.finalistFailureReason(passed.copy(benchmarkRounds = 2))
        )
        assertFalse(
            ThreeStageBenchmarkPolicy.passesFinalist(
                passed.copy(verificationStatus = MtProtoProxy.VERIFICATION_TCP_OPEN)
            )
        )
    }

    @Test
    fun preciseFinalistRequiresEveryConfiguredRound() {
        val candidate = strictProxy(5).copy(
            verificationStatus = MtProtoProxy.VERIFICATION_MTPROTO_VERIFIED,
            benchmarkStage = 3,
            benchmarkRounds = 4
        )

        assertFalse(ThreeStageBenchmarkPolicy.passesFinalist(candidate, requiredRounds = 5))
        assertTrue(
            ThreeStageBenchmarkPolicy.passesFinalist(
                candidate.copy(benchmarkRounds = 5),
                requiredRounds = 5
            )
        )
    }

    @Test
    fun latencyVariationDoesNotPreventAnActualTransferTest() {
        val unstable = strictProxy(2).copy(stability = 0.20f)

        assertTrue(ThreeStageBenchmarkPolicy.passesStrict(unstable))
        assertTrue(ThreeStageBenchmarkPolicy.passesFinalist(unstable.copy(
            verificationStatus = MtProtoProxy.VERIFICATION_MTPROTO_VERIFIED,
            benchmarkStage = 3, benchmarkRounds = 3)))
        assertFalse(ThreeStageBenchmarkPolicy.passesStrict(unstable.copy(stability = Float.NaN)))
    }

    @Test
    fun failedRoundsAndProtocolFailuresStillBlockFinalists() {
        val candidate = strictProxy(1).copy(
            verificationStatus = MtProtoProxy.VERIFICATION_MTPROTO_VERIFIED,
            benchmarkStage = 3, benchmarkRounds = 3)
        assertFalse(ThreeStageBenchmarkPolicy.passesFinalist(candidate.copy(continuityRate = 0.66f)))
        assertFalse(ThreeStageBenchmarkPolicy.passesFinalist(candidate.copy(protocolSuccessRate = 0.5f)))
    }

    @Test
    fun moreThanOneThousandQualifiedProxiesAreRetained() {
        val qualified = (1..1800).map { strictProxy(it).copy(transferScore = 80) }
        assertEquals(1800, ThreeStageBenchmarkPolicy.selectFinalists(qualified + qualified.first()).size)
    }

    private fun strictProxy(index: Int): MtProtoProxy = proxy("p$index").copy(
        isWorking = true,
        latency = 100L + index,
        successRate = 1f,
        stability = 0.95f,
        protocolLatency = 150L + index,
        protocolSuccessRate = 1f,
        tcpAttempts = 10,
        tcpSuccesses = 10,
        protocolAttempts = 8,
        protocolConfirmations = 8,
        tcpP90Latency = 130L + index,
        protocolP90Latency = 190L + index,
        transferScore = index,
        continuityRate = 1f
    )

    private fun proxy(name: String): MtProtoProxy = MtProtoProxy(
        url = "tg://proxy/$name",
        server = "$name.example.com",
        port = 443,
        secret = "dd79e344818749bd7ac519130220c25d09",
        country = MtProtoProxy.COUNTRY_UNKNOWN_NAME,
        countryCode = MtProtoProxy.COUNTRY_UNKNOWN_CODE,
        isWorking = false,
        latency = 0L,
        successRate = 0f,
        stability = 0f
    )
}
