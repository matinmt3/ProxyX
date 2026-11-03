package com.example.viewmodel

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class ManualTransferTracker {
    private val active = MutableStateFlow<String?>(null)
    val activeProxyUrl = active.asStateFlow()

    suspend fun <T> track(proxyUrl: String, operation: suspend () -> T): T {
        check(active.compareAndSet(null, proxyUrl)) { "A manual transfer is already running." }
        try {
            // Preparation and inter-round cleanup are part of the cancellable operation too.
            return operation()
        } finally {
            active.value = null
        }
    }
}
