package com.example.data.worker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProxyRefreshWorkerPolicyTest {
    @Test
    fun backgroundVerificationKeepsResourceUsageBounded() {
        assertEquals(1, backgroundParallelWorkers(0))
        assertEquals(1, backgroundParallelWorkers(1))
        assertEquals(BACKGROUND_MAX_PARALLEL_WORKERS, backgroundParallelWorkers(16))
        assertTrue(BACKGROUND_BENCHMARK_LIMIT in 1..24)
    }
}
