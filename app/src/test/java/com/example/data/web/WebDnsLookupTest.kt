package com.example.data.web

import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.*
import org.junit.Test

class WebDnsLookupTest {
    @Test fun completedLookupReturnsActualAddresses() = runBlocking {
        val address = InetAddress.getByAddress(byteArrayOf(8, 8, 8, 8))
        assertArrayEquals(arrayOf(address), WebDnsLookup.resolve { arrayOf(address) })
    }
    @Test(expected = UnknownHostException::class) fun lookupFailureIsNotConvertedToASuccess() = runBlocking {
        WebDnsLookup.resolve { throw UnknownHostException("test fixture") }
        Unit
    }
    @Test fun cancellationDoesNotWaitForAStuckNativeResolver() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val exited = CountDownLatch(1)
        val job = async(Dispatchers.Default) {
            WebDnsLookup.resolve {
                entered.countDown()
                try {
                    while (release.count > 0) {
                        try { release.await() } catch (_: InterruptedException) { /* Emulate uninterruptible native DNS. */ }
                    }
                    emptyArray()
                } finally { exited.countDown() }
            }
        }
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val start = System.nanoTime()
            job.cancelAndJoin()
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 1000)
            assertTrue(job.isCancelled)
        } finally {
            release.countDown()
            assertTrue(exited.await(2, TimeUnit.SECONDS))
        }
    }
}
