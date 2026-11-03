package com.example.data.network

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class FakeTlsHandshakeTest {
    @Test
    fun clientHelloContainsAuthenticatedDomainAndValidTlsLengths() {
        val secret = hex("79e344818749bd7ac519130220c25d09")
        val timestamp = 1_754_742_400L
        val domain = "www.cloudflare.com"
        val hello = FakeTlsHandshake.createClientHello(secret, domain, timestamp)

        assertArrayEquals(hex("160301"), hello.bytes.copyOfRange(0, 3))
        assertEquals(hello.bytes.size - 5, unsignedShort(hello.bytes, 3))
        assertEquals(hello.bytes.size - 9, unsignedShort(hello.bytes, 7))
        assertTrue(hello.bytes.containsSlice(domain.toByteArray(Charsets.US_ASCII)))

        val unauthenticated = hello.bytes.copyOf().apply { fill(0, 11, 43) }
        val expected = hmac(secret, unauthenticated)
        for (index in 0 until Int.SIZE_BYTES) {
            expected[28 + index] = (expected[28 + index].toInt() xor
                ((timestamp.toInt() ushr (index * 8)) and 0xff)).toByte()
        }
        assertArrayEquals(expected, hello.authenticatedRandom)
        assertArrayEquals(expected, hello.bytes.copyOfRange(11, 43))
    }

    @Test
    fun applicationRecordReaderReturnsOnlyEncryptedMtProtoPayload() {
        val payload = ByteArray(40_000) { index -> (index and 0xff).toByte() }
        val records = FakeTlsHandshake.wrapApplicationData(payload, firstRecord = false)
        val decoded = TelegramPlainHandshake.readFully(
            FakeTlsHandshake.applicationDataInput(ByteArrayInputStream(records)),
            payload.size
        )

        assertArrayEquals(payload, decoded)
    }

    private fun ByteArray.containsSlice(needle: ByteArray): Boolean {
        if (needle.isEmpty() || needle.size > size) return false
        return (0..size - needle.size).any { offset ->
            needle.indices.all { index -> this[offset + index] == needle[index] }
        }
    }

    private fun unsignedShort(bytes: ByteArray, offset: Int): Int {
        return ((bytes[offset].toInt() and 0xff) shl 8) or
            (bytes[offset + 1].toInt() and 0xff)
    }

    private fun hmac(secret: ByteArray, data: ByteArray): ByteArray {
        return Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(secret, "HmacSHA256"))
            doFinal(data)
        }
    }

    private fun hex(value: String): ByteArray {
        return ByteArray(value.length / 2) { index ->
            value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }
}
