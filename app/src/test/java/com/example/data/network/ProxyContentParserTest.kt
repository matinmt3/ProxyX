package com.example.data.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ProxyContentParserTest {
    private val parser = ProxyContentParser()
    private val secret = "dd79e344818749bd7ac519130220c25d09"

    @Test
    fun parsesPlainHtmlAndEscapedJsonLinks() {
        val content = """
            tg://proxy?server=one.example.com&amp;port=443&amp;secret=$secret#DE
            {"items":[{"link":"https:\/\/t.me\/proxy?server=two.example.com&port=8443&secret=$secret"}]}
        """.trimIndent()

        val proxies = parser.parse(content)

        assertEquals(2, proxies.size)
        assertEquals(setOf("one.example.com", "two.example.com"), proxies.map { it.server }.toSet())
    }

    @Test
    fun deduplicatesSameEndpointAcrossFragmentsAndSchemes() {
        val content = """
            tg://proxy?server=proxy.example.com&port=443&secret=$secret#US
            https://t.me/proxy?server=PROXY.EXAMPLE.COM&port=443&secret=$secret#Germany
        """.trimIndent()

        val proxies = parser.parse(content)

        assertEquals(1, proxies.size)
        assertTrue(proxies.single().url.endsWith("secret=$secret"))
    }

    @Test
    fun ignoresMalformedOrUnrelatedUrls() {
        val content = """
            https://example.com/proxy?server=one.example.com&port=443&secret=$secret
            tg://proxy?server=bad host&port=443&secret=$secret
            tg://proxy?server=one.example.com&port=70000&secret=$secret
        """.trimIndent()

        assertTrue(parser.parse(content).isEmpty())
    }

    @Test
    fun acceptsCommonHtmlNumericEntitiesAroundQuerySeparators() {
        val content =
            "tg://proxy?server=entity.example.com&#38;port=443&#38;secret=$secret"

        assertEquals("entity.example.com", parser.parse(content).single().server)
    }

    @Test
    fun parsesBracketedIpv6LinksWithoutCapturingMarkdownClosingBracket() {
        val content =
            "[tg://proxy?server=[2606:4700:4700::1111]&port=443&secret=$secret]"

        val proxy = parser.parse(content).single()

        assertEquals("2606:4700:4700::1111", proxy.server)
    }

    @Test
    fun rejectsDocumentationAddressesInProxyLinks() {
        val content = "tg://proxy?server=[2001:db8::1]&port=443&secret=$secret"

        assertTrue(parser.parse(content).isEmpty())
    }

    @Test
    fun ignoresNonMtProtoEndpoints() {
        val content = """
            8.8.8.8:1080
            socks5://8.8.8.8:1080
            tg://socks?server=one.example.com&port=443
            unrelated: text
        """.trimIndent()

        assertTrue(parser.parse(content).isEmpty())
    }
}
