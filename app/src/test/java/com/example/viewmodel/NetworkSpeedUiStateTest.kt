package com.example.viewmodel

import com.example.model.NetworkSpeedBaseline
import org.junit.Assert.*
import org.junit.Test

class NetworkSpeedUiStateTest {
    private val baseline = NetworkSpeedBaseline("wifi-a", "WIFI", false,
        4096, 2048, 1048576, 1048576, 1000)

    @Test
    fun aNetworkWithoutBaselineClearsThePreviousNetworksRates() {
        val previous = NetworkSpeedUiState().withBaseline(baseline)
        assertTrue(previous.hasMeasurement)
        val changed = previous.withBaseline(null)
        assertFalse(changed.hasMeasurement)
        assertEquals(0, changed.downloadKbps)
        assertEquals(0, changed.uploadKbps)
        assertEquals(0L, changed.measuredAt)
    }

    @Test
    fun invalidPersistedRatesAreNotDisplayedAsAMeasurement() {
        val previous = NetworkSpeedUiState().withBaseline(baseline)
        assertFalse(previous.withBaseline(baseline.copy(uploadKbps = 0)).hasMeasurement)
    }

    @Test
    fun aStoredBaselineCannotInterruptAnActiveMeasurement() {
        val running = NetworkSpeedUiState(isRunning = true)
        assertEquals(running, running.withBaseline(baseline))
        assertEquals(running, running.withBaseline(null))
    }
}
