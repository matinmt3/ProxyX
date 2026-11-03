package com.example.data.benchmark

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BenchmarkExecutionTest {
    @Test
    fun freshUserRequestBelongsToCurrentProcess() {
        assertTrue(BenchmarkExecution.isCurrentProcessRequest(BenchmarkExecution.processSessionToken))
    }

    @Test
    fun interruptedAndLegacyRequestsRequireControlledRecovery() {
        assertFalse(BenchmarkExecution.isCurrentProcessRequest("previous-process"))
        assertFalse(BenchmarkExecution.isCurrentProcessRequest(null))
    }
}
