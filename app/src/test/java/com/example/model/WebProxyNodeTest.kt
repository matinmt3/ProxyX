package com.example.model

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WebProxyNodeTest {
    private val secret = "000102030405060708090a0b0c0d0e0f"
    private fun link(host: String = "proxy.example.com", key: String = secret) = "tg://webproxy?server=$host&secret=$key"

    @Test fun directLinkRoundTrips() {
        val node = WebProxyNode.parse(link(), 123)!!
        assertEquals("proxy.example.com", node.address)
        assertEquals(secret, node.secret)
        assertEquals(123L, node.createdAt)
        assertEquals(node.url, WebProxyNode.parse(node.url)!!.url)
    }
    @Test fun httpsLinkCanonicalizesToDirectScheme() {
        assertEquals(WebProxyNode.parse(link())!!.url, WebProxyNode.parse(link().replace("tg://", "https://t.me/"))!!.url)
    }
    @Test fun knownRootCapabilityMatchesTelegramVector() {
        assertEquals("https://proxy.example.com/?bridge=MHLEY5PmW1GWqJkSrlmJpvJUiLhBH_QKy6yKg8a0JPk", WebProxyNode.parse(link())!!.bridgeUrl())
    }
    @Test fun knownPaddingCapabilityMatchesTelegramVector() {
        assertEquals("https://proxy.example.com/?bridge=IpJrt3e7sKtzPyoXy6w-Zj6GGEvsvclN66JzQEfPYLA", WebProxyNode.parse(link(key = "dd$secret"))!!.bridgeUrl())
    }
    @Test fun markedBasePathPreservesAddressAndSecret() {
        val node = WebProxyNode.parse(link("proxy.example.com%2Fphcf2vfe7zgbrslg", "cIVhlEBk_HMMv6RHNWLY7Fk"))!!
        assertEquals("8561944064fc730cbfa4473562d8ec59", node.secret)
        assertEquals("phcf2vfe7zgbrslg", node.path)
        assertEquals(node.url, WebProxyNode.parse(node.url)!!.url)
        assertNotEquals(WebProxyNode.parse(link(key = node.secret))!!.bridgeUrl(), node.bridgeUrl())
    }
    @Test fun unmarkedPathIsRejected() { assertNull(WebProxyNode.parse(link("proxy.example.com%2Fpath"))) }
    @Test fun invalidPathsAreRejected() {
        listOf("%2F", "%2F..", "%2Fa%2F%2Fb", "%2Fa.b", "%2F-b", "%2Fa%3Fq=1").forEach {
            assertNull(WebProxyNode.parse(link("proxy.example.com$it", "cIVhlEBk_HMMv6RHNWLY7Fk")))
        }
    }
    @Test fun portAndSocksAndNormalMtprotoLinksAreRejected() {
        assertNull(WebProxyNode.parse(link() + "&port=443"))
        assertNull(WebProxyNode.parse(link().replace("webproxy", "socks")))
        assertNull(WebProxyNode.parse(link().replace("webproxy", "proxy")))
    }
    @Test fun duplicateParametersAndAmbiguousAliasAreRejected() {
        assertNull(WebProxyNode.parse(link() + "&secret=$secret"))
        assertNull(WebProxyNode.parse(link() + "&host=other.example.com"))
        assertNull(WebProxyNode.parse(link() + "&server=other.example.com"))
    }
    @Test fun legacyHostAliasIsCanonicalized() {
        assertEquals(WebProxyNode.parse(link())!!.url, WebProxyNode.parse(link().replace("server=", "host="))!!.url)
    }
    @Test fun unsafeHostnameFormsAreRejected() {
        listOf("127.0.0.1", "localhost", "a.local", "a.localhost", "evil@proxy.example.com", "proxy.example.com:443", "https://proxy.example.com", "-x.example.com").forEach {
            assertNull(it, WebProxyNode.parse(link(it)))
        }
    }
    @Test fun idnHostAndCaseNormalize() {
        assertEquals("xn--bcher-kva.example", WebProxyNode.parse(link("BÜCHER.example"))!!.address)
        assertEquals("proxy.example.com", WebProxyNode.parse(link("PROXY.example.COM."))!!.address)
    }
    @Test fun linkHostCredentialsFragmentAndHttpAreRejected() {
        assertNull(WebProxyNode.parse(link().replace("tg://", "https://evil@t.me/")))
        assertNull(WebProxyNode.parse(link() + "#ignored"))
        assertNull(WebProxyNode.parse(link().replace("tg://", "http://t.me/")))
        assertNull(WebProxyNode.parse(link().replace("tg://", "https://evil.example/")))
    }
    @Test fun invalidOrFakeTlsSecretsAreRejected() {
        listOf("", "abcd", "00".repeat(17), "ee$secret" + "6578616d706c652e636f6d", "!".repeat(32)).forEach {
            assertNull(WebProxyNode.parse(link(key = it)))
        }
    }
    @Test fun parseListDeduplicatesAndIgnoresUnrelatedProxies() {
        val content = "${link()}\n${link().replace("tg://", "https://t.me/")}\n${link().replace("webproxy", "proxy")}"
        assertEquals(1, WebProxyNode.parseList(content).size)
    }
    @Test fun channelAttributionIsNotIncludedInConnectionIdentity() {
        assertEquals(WebProxyNode.parse(link())!!.url, WebProxyNode.parse(link() + "&bot=@ExampleBot&channel=@ExampleChannel")!!.url)
    }
    @Test fun protocolOnlyAndIncompleteResultsHaveNoRealSpeed() {
        val node = WebProxyNode.parse(link())!!
        assertFalse(node.copy(status = "PROTOCOL_VERIFIED", stage = 3, downloadKbps = 999).hasRealTransfer)
        val measured = node.copy(status = "TRANSFER_VERIFIED", testedAt = 1, networkFingerprint = "current", stage = 4,
            transferRounds = 3, downloadKbps = 500, uploadKbps = 200, transferBytes = 100)
        assertTrue(measured.hasRealTransfer)
        assertFalse(measured.copy(transferRounds = 2).hasRealTransfer)
        assertFalse(measured.copy(uploadKbps = 0).hasRealTransfer)
        assertFalse(measured.copy(status = "FAILED").hasRealTransfer)
        assertFalse(measured.copy(testedAt = 0).hasRealTransfer)
        assertFalse(measured.copy(networkFingerprint = "").hasRealTransfer)
    }
    @Test fun localAdapterKeepsWebIdentityWithoutChangingSharedAddress() {
        val node = WebProxyNode.parse(link())!!
        val local = node.asLocalMtProto(12345)
        assertEquals("127.0.0.1", local.server)
        assertEquals(12345, local.port)
        assertEquals(node.url, local.url)
        assertEquals(node.secret, local.secret)
        assertEquals("proxy.example.com", node.address)
    }
}
