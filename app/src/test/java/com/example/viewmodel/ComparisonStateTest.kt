package com.example.viewmodel

import com.example.model.MtProtoProxy
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ComparisonStateTest {
    @Test
    fun retestingUpdatesSelectedProxyWithoutReselectingIt() = runTest {
        val selected = MutableStateFlow(listOf("route"))
        val stored = MutableStateFlow<MtProtoProxy?>(proxy("route"))
        var latest = emptyList<MtProtoProxy>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            observeComparedProxies(selected) { stored }.collect { latest = it }
        }
        stored.value = stored.value!!.copy(latency = 321, benchmarkFailureReason = "TCP_UNREACHABLE")
        assertEquals(321L, latest.single().latency)
        assertEquals("TCP_UNREACHABLE", latest.single().benchmarkFailureReason)
    }

    @Test
    fun deletedProxyDoesNotLeaveAStaleComparisonCard() = runTest {
        val selected = MutableStateFlow(listOf("route"))
        val stored = MutableStateFlow<MtProtoProxy?>(proxy("route"))
        var latest = emptyList<MtProtoProxy>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            observeComparedProxies(selected) { stored }.collect { latest = it }
        }
        assertEquals(1, latest.size)
        stored.value = null
        assertEquals(emptyList<MtProtoProxy>(), latest)
    }

    @Test
    fun selectionOrderIsPreservedAndMissingRowsAreExcluded() = runTest {
        val result = observeComparedProxies(MutableStateFlow(listOf("b", "missing", "a"))) {
            MutableStateFlow(if (it == "missing") null else proxy(it))
        }.first()
        assertEquals(listOf("b", "a"), result.map(MtProtoProxy::url))
    }

    @Test
    fun clearingSelectionEmitsAnEmptyList() = runTest {
        val selected = MutableStateFlow(listOf("route"))
        var latest = emptyList<MtProtoProxy>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            observeComparedProxies(selected) { MutableStateFlow(proxy(it)) }.collect { latest = it }
        }
        selected.value = emptyList()
        assertEquals(emptyList<MtProtoProxy>(), latest)
    }

    private fun proxy(url: String) = MtProtoProxy(
        url = url, server = "1.1.1.1", port = 443,
        secret = "0123456789abcdef0123456789abcdef", country = "Unknown", countryCode = "XX",
        isWorking = false, latency = 0, successRate = 0f, stability = 0f
    )
}
