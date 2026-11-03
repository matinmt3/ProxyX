package com.example.viewmodel

import com.example.model.MtProtoProxy
import org.junit.Assert.assertEquals
import org.junit.Test

class BenchmarkSessionStateTest {
    @Test
    fun largeSessionCountsEveryResultButRetainsOnlySmallUiPreviews() {
        var state = BenchmarkSessionState(totalCount = 1_005)

        repeat(1_005) { index ->
            state = appendBenchmarkResult(
                previous = state,
                completed = index + 1,
                total = 1_005,
                proxy = readyProxy(index)
            )
        }

        assertEquals(1_005, state.completedCount)
        assertEquals(1_005, state.totalCount)
        assertEquals(1_005, state.readyCount)
        assertEquals(1_005, state.verifiedCount)
        assertEquals(0, state.tcpOnlyCount)
        assertEquals(12, state.previewRows.size)
        assertEquals(10, state.chartRows.size)
    }

    @Test
    fun reachableTcpResultAppearsInTheMeasuredLatencyChart() {
        val tcpOnly = readyProxy(1).copy(
            verificationStatus = MtProtoProxy.VERIFICATION_TCP_OPEN,
            verificationConfidence = 50,
            protocolLatency = 0,
            protocolSuccessRate = 0f,
            protocolAttempts = 2,
            protocolConfirmations = 0
        )

        val state = appendBenchmarkResult(
            previous = BenchmarkSessionState(totalCount = 1),
            completed = 1,
            total = 1,
            proxy = tcpOnly
        )

        assertEquals(listOf(tcpOnly.url), state.chartRows.map { it.url })
        assertEquals(1, state.tcpOnlyCount)
        assertEquals(0, state.verifiedCount)
    }

    private fun readyProxy(index: Int): MtProtoProxy {
        val latency = 50L + index
        return MtProtoProxy(
            url = "tg://proxy/session-$index",
            server = "session-$index.example.com",
            port = 443,
            secret = "dd79e344818749bd7ac519130220c25d09",
            country = "Global",
            countryCode = "UN",
            isWorking = true,
            latency = latency,
            successRate = 1f,
            stability = 0.95f,
            score = 90,
            lastChecked = index.toLong() + 1L,
            verificationStatus = MtProtoProxy.VERIFICATION_TELEGRAM_READY,
            verificationConfidence = 95,
            protocolLatency = latency,
            protocolSuccessRate = 1f,
            tcpAttempts = 10,
            tcpSuccesses = 10,
            protocolAttempts = 8,
            protocolConfirmations = 8,
            tcpP90Latency = latency + 10,
            tcpJitter = 2,
            protocolP90Latency = latency + 20,
            protocolJitter = 4
        )
    }
}
