package com.example.data.benchmark

import org.junit.Assert.assertEquals
import org.junit.Test

class AdaptiveWorkerPolicyTest {
    @Test
    fun hotPhoneReducesConcurrencyEvenWithHighConfiguredMaximum() {
        val workers = AdaptiveWorkerPolicy.select(
            configuredMaximum = 16,
            workload = DeviceWorkload(
                cpuCores = 8,
                memoryClassMb = 512,
                batteryTemperatureC = 46.5f,
                isCharging = true,
                networkType = "WIFI",
                isMetered = false
            )
        )

        assertEquals(2, workers)
    }

    @Test
    fun meteredCellularCapsConcurrencyAndHonorsUserMaximum() {
        val workers = AdaptiveWorkerPolicy.select(
            configuredMaximum = 4,
            workload = DeviceWorkload(
                cpuCores = 12,
                memoryClassMb = 512,
                batteryTemperatureC = 31f,
                isCharging = false,
                networkType = "CELLULAR",
                isMetered = true
            )
        )

        assertEquals(4, workers)
    }
}
