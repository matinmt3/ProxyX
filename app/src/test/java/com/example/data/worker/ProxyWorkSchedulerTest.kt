package com.example.data.worker

import org.junit.Assert.assertEquals
import org.junit.Test

class ProxyWorkSchedulerTest {
    @Test
    fun refreshIntervalIsBoundedToWorkManagerSafeRange() {
        assertEquals(ProxyWorkScheduler.MIN_INTERVAL_MINUTES, ProxyWorkScheduler.normalizeInterval(1))
        assertEquals(60, ProxyWorkScheduler.normalizeInterval(60))
        assertEquals(
            ProxyWorkScheduler.MAX_INTERVAL_MINUTES,
            ProxyWorkScheduler.normalizeInterval(Int.MAX_VALUE)
        )
    }
}
