package com.example.data.web

import kotlinx.coroutines.suspendCancellableCoroutine
import java.net.InetAddress
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal object WebDnsLookup {
    // Android DNS can block inside native code. Keep at most one live lookup and
    // one pending lookup without forcing the cancelling coroutine to wait for it.
    private val executor = ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS,
        ArrayBlockingQueue(1), { task -> Thread(task, "ProxyX-WEB-DNS").apply { isDaemon = true } }).apply {
        allowCoreThreadTimeOut(true)
    }

    suspend fun resolve(lookup: () -> Array<InetAddress>): Array<InetAddress> = suspendCancellableCoroutine { continuation ->
        try {
            val future = executor.submit {
                try {
                    val addresses = lookup()
                    if (continuation.isActive) continuation.resume(addresses)
                } catch (error: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            }
            continuation.invokeOnCancellation { future.cancel(true); executor.purge() }
        } catch (error: Exception) {
            if (continuation.isActive) continuation.resumeWithException(error)
        }
    }
}
