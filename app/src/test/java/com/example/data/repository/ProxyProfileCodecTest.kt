package com.example.data.repository

import com.example.model.FavoriteProxy
import com.example.model.PrivateProxyNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class ProxyProfileCodecTest {
    @Test
    fun roundTripKeepsOnlyPortableVaultData() {
        val encoded = ProxyProfileCodec.encode(listOf(favorite()), listOf(privateNode()))
        val decoded = ProxyProfileCodec.decode(encoded)

        assertEquals(1, decoded.favorites.size)
        assertEquals("Archive", decoded.favorites.single().folder)
        assertEquals("personal", decoded.privateNodes.single().label)
        assertTrue(decoded.privateNodes.single().enabled)
        assertFalse(encoded.contains("apiHash", ignoreCase = true))
        assertFalse(encoded.contains("phoneNumber", ignoreCase = true))
        assertFalse(encoded.contains("tdlibDatabase", ignoreCase = true))
    }

    @Test
    fun modifiedPayloadFailsIntegrityCheck() {
        val encoded = ProxyProfileCodec.encode(listOf(favorite()), listOf(privateNode()))
        val modified = encoded.replace("original note", "tampered note")

        assertThrows(IllegalArgumentException::class.java) {
            ProxyProfileCodec.decode(modified)
        }
    }

    @Test
    fun exportedUnicodeProfileCanBeImportedFromAStream() {
        val encoded = ProxyProfileCodec.encode(
            listOf(favorite().copy(note = "\u0646\u0648\u062f \u0634\u062e\u0635\u06cc")),
            listOf(privateNode())
        )
        val decoded = ByteArrayInputStream(encoded.toByteArray(Charsets.UTF_8)).use {
            ProxyProfileCodec.decode(it)
        }
        assertEquals("\u0646\u0648\u062f \u0634\u062e\u0635\u06cc", decoded.favorites.single().note)
    }

    @Test
    fun exportRejectsMoreItemsThanTheImporterCanAccept() {
        assertThrows(IllegalArgumentException::class.java) {
            ProxyProfileCodec.encode(List(1_001) { favorite().copy(url = "$proxyUrl&item=$it") }, emptyList())
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProxyProfileCodec.encode(emptyList(), List(1_001) { privateNode().copy(url = "$proxyUrl&item=$it") })
        }
    }

    @Test
    fun maximumItemCountRoundTripsWithoutSilentlyDroppingEntries() {
        val favorites = List(1_000) { favorite().copy(url = "$proxyUrl&item=$it") }
        val encoded = ProxyProfileCodec.encode(favorites, emptyList())
        assertEquals(1_000, ProxyProfileCodec.decode(encoded).favorites.size)
    }

    @Test
    fun exportRejectsUtf8PayloadLargerThanImportLimit() {
        assertThrows(IllegalArgumentException::class.java) {
            ProxyProfileCodec.encode(listOf(favorite().copy(note = "\u062a".repeat(600_000))), emptyList())
        }
    }

    @Test
    fun streamingImportStopsReadingWhenByteLimitIsReached() {
        val stream = ByteArrayInputStream(ByteArray(2 * 1_048_576) { 32 })
        assertThrows(IllegalArgumentException::class.java) { ProxyProfileCodec.decode(stream) }
        assertTrue(stream.available() >= 1_048_576 - 8_192)
    }

    @Test
    fun inactiveFavoritesDoNotConsumeTheExportItemLimit() {
        val encoded = ProxyProfileCodec.encode(
            List(1_001) { favorite().copy(isActive = false) } + favorite(), emptyList()
        )
        assertEquals(1, ProxyProfileCodec.decode(encoded).favorites.size)
    }

    private fun favorite() = FavoriteProxy(
        url = proxyUrl,
        server = "1.1.1.1",
        port = 443,
        secret = secret,
        country = "Global",
        countryCode = "UN",
        isActive = true,
        createdAt = 100,
        updatedAt = 200,
        folder = "Archive",
        tags = "fast,tested",
        note = "original note"
    )

    private fun privateNode() = PrivateProxyNode(
        url = proxyUrl,
        label = "personal",
        server = "1.1.1.1",
        port = 443,
        secret = secret,
        createdAt = 100,
        updatedAt = 200
    )

    private companion object {
        const val secret = "0123456789abcdef0123456789abcdef"
        const val proxyUrl = "tg://proxy?server=1.1.1.1&port=443&secret=$secret"
    }
}
