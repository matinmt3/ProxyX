package com.example.data.network

import com.example.model.MtProtoProxy
import kotlinx.coroutines.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import java.net.SocketAddress
import java.net.UnknownHostException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class NetworkAuditRegressionTest {
    @Test fun dnsChangeOnSameAndroidNetworkInvalidatesMeasurement() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val manager = context.getSystemService(android.net.ConnectivityManager::class.java)
        val shadow = org.robolectric.Shadows.shadowOf(manager)
        val network = requireNotNull(manager.activeNetwork)
        val capabilities = org.robolectric.shadows.ShadowNetworkCapabilities.newInstance()
        org.robolectric.Shadows.shadowOf(capabilities).addTransportType(android.net.NetworkCapabilities.TRANSPORT_WIFI)
        org.robolectric.Shadows.shadowOf(capabilities).addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
        shadow.setNetworkCapabilities(network, capabilities)
        shadow.setLinkProperties(network, android.net.LinkProperties().apply {
            interfaceName = "test0"
            org.robolectric.util.ReflectionHelpers.callInstanceMethod<Boolean>(this, "addDnsServer",
                org.robolectric.util.ReflectionHelpers.ClassParameter.from(InetAddress::class.java, address("8.8.8.8")))
        })
        val session = requireNotNull(UserNetworkSession.capture(manager, true))
        session.ensureCurrent()
        shadow.setLinkProperties(network, android.net.LinkProperties().apply {
            interfaceName = "test0"
            org.robolectric.util.ReflectionHelpers.callInstanceMethod<Boolean>(this, "addDnsServer",
                org.robolectric.util.ReflectionHelpers.ClassParameter.from(InetAddress::class.java, address("1.1.1.1")))
        })
        assertThrows(UserNetworkChangedException::class.java) { session.ensureCurrent() }
    }

    @Test fun cancellingSourceReadCancelsTheActualHttpCall() = runBlocking {
        val server = java.net.ServerSocket(0, 1, address("127.0.0.1"))
        val bodyStarted = CountDownLatch(1)
        val release = CountDownLatch(1)
        val serving = launch(Dispatchers.IO) {
            server.accept().use { socket ->
                val input = socket.getInputStream().bufferedReader()
                while (!input.readLine().isNullOrEmpty()) { }
                socket.getOutputStream().apply {
                    write("HTTP/1.1 200 OK\r\nContent-Length: 10000\r\n\r\nx".toByteArray()); flush()
                }
                bodyStarted.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
        }
        val client = okhttp3.OkHttpClient.Builder().proxy(java.net.Proxy.NO_PROXY).build()
        val call = client.newCall(okhttp3.Request.Builder().url("http://127.0.0.1:${server.localPort}/").build())
        try {
            val reading = async { SourceHttpPolicy.read(call, 20_000) }
            withContext(Dispatchers.IO) { assertTrue(bodyStarted.await(3, TimeUnit.SECONDS)) }
            withTimeout(1000) { reading.cancelAndJoin() }
            assertTrue(call.isCanceled())
        } finally {
            release.countDown(); server.close(); serving.join()
            client.dispatcher.cancelAll(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown()
        }
    }

    @Test fun sourceRejectsInsecureCredentialAndLocalUrls() {
        listOf("http://example.com/feed", "https://u:p@example.com/feed",
            "https://localhost/feed", "https://server.local/feed", "https://example.com:444/feed",
            "https://example.com/feed#fragment").forEach { url ->
            assertThrows(IOException::class.java) { SourceHttpPolicy.validate(url.toHttpUrl()) }
        }
        SourceHttpPolicy.validate("https://example.com/feed".toHttpUrl())
    }

    @Test fun sourceRejectsEmptyAndMixedPublicPrivateDns() {
        assertThrows(UnknownHostException::class.java) { SourceHttpPolicy.publicAddresses(emptyList()) }
        assertThrows(UnknownHostException::class.java) {
            SourceHttpPolicy.publicAddresses(listOf(address("8.8.8.8"), address("127.0.0.1")))
        }
        assertEquals(1, SourceHttpPolicy.publicAddresses(listOf(address("8.8.8.8"))).size)
    }

    @Test fun sourceLimitCountsBytesNotCharacters() {
        val body = "\u00e9\u00e9\u00e9".toResponseBody()
        assertThrows(IOException::class.java) { body.use { SourceHttpPolicy.readBody(it, 5) } }
        "valid".toResponseBody().use { assertEquals("valid", SourceHttpPolicy.readBody(it, 5)) }
    }

    @Test fun stalledNativeDnsDoesNotBlockCallerIndefinitely() {
        BoundedDnsLookup(1, 1).use { resolver ->
            val release = CountDownLatch(1)
            try {
                val started = System.nanoTime()
                assertThrows(UnknownHostException::class.java) {
                    resolver.resolve(100) {
                        while (release.count > 0) try { release.await() } catch (_: InterruptedException) { }
                        emptyArray()
                    }
                }
                assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 1500)
                assertThrows(UnknownHostException::class.java) { resolver.resolve(50) { emptyArray() } }
            } finally { release.countDown() }
        }
    }

    @Test fun dnsExceptionsRetainClassification() {
        BoundedDnsLookup(1, 1).use { resolver ->
            assertThrows(UserNetworkChangedException::class.java) {
                resolver.resolve { throw UserNetworkChangedException() }
            }
        }
    }

    @Test fun wholeProtocolDeadlineClosesAStalledSocket() = runBlocking {
        val socket = StalledSocket()
        val result = withTimeout(6_000) {
            MtProtoConnectionProbe { socket }.probe(proxy(), address("8.8.8.8"), 1000)
        }
        assertEquals(MtProtoProbeStatus.FAILED, result.status)
        assertTrue(socket.closed.await(1, TimeUnit.SECONDS))
    }

    @Test fun parentCancellationIsNotReportedAsProxyFailure() = runBlocking {
        val socket = StalledSocket()
        val job = async { MtProtoConnectionProbe { socket }.probe(proxy(), address("8.8.8.8"), 5000) }
        withContext(Dispatchers.IO) { assertTrue(socket.readStarted.await(2, TimeUnit.SECONDS)) }
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertTrue(socket.closed.await(1, TimeUnit.SECONDS))
    }

    private fun proxy() = requireNotNull(MtProtoProxy.parseFromUrl(
        "tg://proxy?server=example.com&port=443&secret=dd79e344818749bd7ac519130220c25d09"))
    private fun address(ip: String) = InetAddress.getByName(ip)

    private class StalledSocket : Socket() {
        val closed = CountDownLatch(1)
        val readStarted = CountDownLatch(1)
        override fun connect(endpoint: SocketAddress?, timeout: Int) = Unit
        override fun setTcpNoDelay(on: Boolean) = Unit
        override fun setSoTimeout(timeout: Int) = Unit
        override fun getOutputStream() = ByteArrayOutputStream()
        override fun getInputStream() = object : InputStream() {
            override fun read(): Int {
                readStarted.countDown()
                closed.await(8, TimeUnit.SECONDS)
                throw IOException("closed")
            }
        }
        override fun close() { closed.countDown() }
    }
}
