package com.example.data.worker

import org.junit.Assert.assertEquals
import org.junit.Test

class ProxyBenchmarkSchedulerTest {
    @Test
    fun intervalAndCountAreBounded() {
        assertEquals(24, ProxyBenchmarkScheduler.normalizeIntervalHours(7))
        assertEquals(12, ProxyBenchmarkScheduler.normalizeIntervalHours(12))
        assertEquals(25, ProxyBenchmarkScheduler.normalizeCount(-1))
        assertEquals(250, ProxyBenchmarkScheduler.normalizeCount(5_000))
    }
}
