package com.example.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferScoreTest {
    @Test
    fun stableMultiRoundRouteBeatsFastButDiscontinuousRoute() {
        val stable = TransferScoreCalculator.calculate(
            tcpSuccessRate = 1f,
            protocolSuccessRate = 1f,
            stability = 0.96f,
            continuityRate = 1f,
            tcpP90Latency = 230,
            tcpJitter = 8,
            protocolP90Latency = 320,
            protocolJitter = 12,
            benchmarkStage = 3,
            benchmarkRounds = 3
        )
        val fastButUnreliable = TransferScoreCalculator.calculate(
            tcpSuccessRate = 0.70f,
            protocolSuccessRate = 0.65f,
            stability = 0.42f,
            continuityRate = 0.33f,
            tcpP90Latency = 75,
            tcpJitter = 45,
            protocolP90Latency = 110,
            protocolJitter = 70,
            benchmarkStage = 3,
            benchmarkRounds = 3
        )

        assertTrue(stable > fastButUnreliable)
    }

    @Test
    fun noMtprotoConfirmationCannotReceiveTransferScore() {
        assertEquals(
            0,
            TransferScoreCalculator.calculate(
                tcpSuccessRate = 1f,
                protocolSuccessRate = 0f,
                stability = 1f,
                continuityRate = 0f,
                tcpP90Latency = 40,
                tcpJitter = 1,
                protocolP90Latency = 0,
                protocolJitter = 0,
                benchmarkStage = 3,
                benchmarkRounds = 3
            )
        )
    }
}
