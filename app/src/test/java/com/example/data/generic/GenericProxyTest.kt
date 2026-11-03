package com.example.data.generic

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class GenericProxyTest {
    private val feed = GenericSources.feeds.first()
    @Test fun bareEndpointsRequireAnExplicitProtocol() {
        assertEquals(1, GenericProxyParser.parse("8.8.8.8:8080", feed, 1).size)
        assertTrue(GenericProxyParser.parse("8.8.8.8:8080", feed.copy(protocol = null), 1).isEmpty())
    }
    @Test fun httpsListDoesNotMeanTlsToTheProxy() {
        val row = GenericProxyParser.parse("8.8.8.8:8080", GenericSources.feeds[1], 1).single()
        assertEquals("HTTP_CONNECT", row.protocol)
        assertTrue(GenericProxyParser.parse("https://8.8.8.8:8080", feed.copy(protocol = null), 1).isEmpty())
    }
    @Test fun typedSourcesSupportOnlyUnambiguousProtocols() {
        listOf("http", "socks4", "socks4a", "socks5").forEach {
            assertEquals(1, GenericProxyParser.parse("$it://8.8.8.8:1080", feed.copy(protocol = null), 1).size)
        }
    }
    @Test fun malformedPrivateCredentialAndTelegramEntriesAreRejected() {
        listOf("127.0.0.1:80", "192.168.1.1:80", "[::1]:80", "u:p@8.8.8.8:80", "host.local:80",
            "8.8.8.8:0", "8.8.8.8:65536", "8.8.8.8:80/path", "<html>bad</html>", "{}",
            "tg://proxy?server=8.8.8.8&port=80&secret=123", "socks5://8.8.8.8:80").forEach {
            assertTrue(it, GenericProxyParser.parse(it, feed, 1).isEmpty())
        }
    }
    @Test fun parsingDeduplicatesAndNeverImportsProviderSpeed() {
        val rows = GenericProxyParser.parse("8.8.8.8:80\n8.8.8.8:80\n# tested fast", feed, 10)
        assertEquals(1, rows.size)
        assertEquals("UNTESTED", rows.single().status)
        assertEquals(0L, rows.single().requestMs)
        assertEquals("IR", rows.single().sourceCountry)
    }
    @Test fun socks5UsesRemoteDnsAndValidatesReply() {
        val output = ByteArrayOutputStream()
        GenericProxyHandshake.socks(ByteArrayInputStream(byteArrayOf(5, 0, 5, 0, 0, 1, 1, 2, 3, 4, 1, 1)),
            output, GenericProtocol.SOCKS5, "example.com", 443)
        assertArrayEquals(byteArrayOf(5, 1, 0, 5, 1, 0, 3, 11), output.toByteArray().take(8).toByteArray())
        assertTrue(output.toString("US-ASCII").contains("example.com"))
    }
    @Test fun socks5DoesNotSendCredentialsWhenAuthenticationRequired() {
        val output = ByteArrayOutputStream()
        val error = assertThrows(GenericProtocolFailure::class.java) {
            GenericProxyHandshake.socks(ByteArrayInputStream(byteArrayOf(5, 2)), output,
                GenericProtocol.SOCKS5, "example.com", 443)
        }
        assertEquals("AUTH_FAILED", error.code)
        assertEquals(3, output.size())
    }
    @Test fun socks5RejectsTruncationBadVersionAndReservedByte() {
        listOf(byteArrayOf(), byteArrayOf(4, 0), byteArrayOf(5, 0, 5, 0, 1, 1),
            byteArrayOf(5, 0, 5, 1, 0, 1), byteArrayOf(5, 0, 5, 0, 0, 7)).forEach {
            assertThrows(GenericProtocolFailure::class.java) {
                GenericProxyHandshake.socks(ByteArrayInputStream(it), ByteArrayOutputStream(), GenericProtocol.SOCKS5, "example.com", 443)
            }
        }
    }
    @Test fun socks4UsesLocallyResolvedIpv4() {
        val output = ByteArrayOutputStream()
        GenericProxyHandshake.socks(ByteArrayInputStream(byteArrayOf(0, 90, 0, 0, 0, 0, 0, 0)), output,
            GenericProtocol.SOCKS4, "example.com", 443, byteArrayOf(8, 8, 8, 8))
        assertArrayEquals(byteArrayOf(4, 1, 1, -69, 8, 8, 8, 8, 0), output.toByteArray())
    }
    @Test fun socks4aUsesRemoteHostname() {
        val output = ByteArrayOutputStream()
        GenericProxyHandshake.socks(ByteArrayInputStream(byteArrayOf(0, 90, 0, 0, 0, 0, 0, 0)), output,
            GenericProtocol.SOCKS4A, "example.com", 443)
        assertArrayEquals(byteArrayOf(0, 0, 0, 1), output.toByteArray().copyOfRange(4, 8))
        assertTrue(output.toString("US-ASCII").endsWith("example.com\u0000"))
    }
    @Test fun socks4FailureCannotBecomeSuccess() {
        listOf(91, 92, 93).forEach { code ->
            assertThrows(GenericProtocolFailure::class.java) {
                GenericProxyHandshake.socks(ByteArrayInputStream(byteArrayOf(0, code.toByte(), 0, 0, 0, 0, 0, 0)),
                    ByteArrayOutputStream(), GenericProtocol.SOCKS4A, "example.com", 443)
            }
        }
    }
    @Test fun httpStatusParserRejectsInvalidAndUnboundedHeaders() {
        assertEquals(204, GenericProxyHandshake.responseCode("HTTP/1.1 204 No Content\r\n\r\n".byteInputStream()))
        assertEquals(407, GenericProxyHandshake.responseCode("HTTP/1.0 407 Auth\r\n\r\n".byteInputStream()))
        listOf("NOT HTTP\r\n\r\n", "HTTP/1.1 200 OK\r\nX: " + "a".repeat(20_000), "HTTP/1.1").forEach {
            assertThrows(GenericProtocolFailure::class.java) { GenericProxyHandshake.responseCode(it.byteInputStream()) }
        }
    }
    @Test fun staleResultsCannotOverwriteRefreshedGenericRows() = runTest {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), GenericProxyDatabase::class.java).build()
        try {
            val dao = db.dao()
            val row = GenericProxyParser.parse("8.8.8.8:80", feed, 20).single()
            dao.received(feed, listOf(row), 20)
            assertEquals(0, dao.measured(row.id, 10, "WORKING", 30, 1, 2, "", "network"))
            assertEquals(1, dao.measured(row.id, 20, "WORKING", 30, 1, 2, "", "network"))
            assertEquals(0, dao.measured(row.id, 20, "CONNECTION_FAILED", 25, 0, 0, "", "network"))
            assertEquals("WORKING", dao.get(row.id)!!.status)
            dao.received(feed, listOf(row.copy(fetchedAt = 40)), 40)
            assertEquals("UNTESTED", dao.get(row.id)!!.status)
            assertEquals(0L, dao.get(row.id)!!.requestMs)
        } finally { db.close() }
    }
    @Test fun sourceProvenanceIsMergedWithoutDuplicatingEndpoint() = runTest {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), GenericProxyDatabase::class.java).build()
        try {
            val row = GenericProxyParser.parse("8.8.8.8:80", feed, 1).single()
            db.dao().received(feed, listOf(row), 1)
            db.dao().received(feed.copy(id = "second"), listOf(row.copy(sourceIds = "second")), 2)
            assertEquals(1, db.dao().snapshot().size)
            assertTrue(db.dao().get(row.id)!!.sourceIds.contains("second"))
        } finally { db.close() }
    }

    @Test fun refreshRemovesOnlyObsoleteMembershipOfSuccessfulSource() = runTest {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), GenericProxyDatabase::class.java).build()
        try {
            val dao = db.dao()
            val shared = GenericProxyParser.parse("8.8.8.8:80", feed, 1).single()
            val obsolete = GenericProxyParser.parse("8.8.4.4:80", feed, 1).single()
            val fresh = GenericProxyParser.parse("1.1.1.1:80", feed, 2).single()
            dao.received(feed, listOf(shared, obsolete), 1)
            dao.received(feed.copy(id = "second"), listOf(shared), 1)
            dao.received(feed, listOf(fresh), 2)
            assertEquals(null, dao.get(obsolete.id))
            assertEquals("second", dao.get(shared.id)!!.sourceIds)
            assertEquals(2, dao.snapshot().size)
        } finally { db.close() }
    }
}
