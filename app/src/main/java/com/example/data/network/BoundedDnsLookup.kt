package com.example.data.network

import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Native DNS can ignore interrupts; bound both waiting time and abandoned native work. */
internal class BoundedDnsLookup(workers: Int = 4, queued: Int = 32) : AutoCloseable {
    private val executor = ThreadPoolExecutor(workers, workers, 30, TimeUnit.SECONDS,
        ArrayBlockingQueue(queued), { task -> Thread(task, "ProxyX-DNS").apply { isDaemon = true } })
        .apply { allowCoreThreadTimeOut(true) }

    fun resolve(timeoutMs: Long = 5_000, lookup: () -> Array<InetAddress>): Array<InetAddress> {
        val future = try { executor.submit<Array<InetAddress>> { lookup() } }
        catch (_: java.util.concurrent.RejectedExecutionException) {
            throw UnknownHostException("DNS lookup capacity exhausted")
        }
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            throw UnknownHostException("DNS lookup timed out")
        } catch (error: ExecutionException) {
            throw (error.cause as? Exception ?: UnknownHostException("DNS lookup failed"))
        } finally {
            future.cancel(true)
            executor.purge()
        }
    }

    override fun close() { executor.shutdownNow() }
}
