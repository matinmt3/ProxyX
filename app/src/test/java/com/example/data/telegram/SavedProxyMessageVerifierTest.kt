package com.example.data.telegram

import com.example.model.MtProtoProxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SavedProxyMessageVerifierTest {
    private val proxy = MtProtoProxy(
        url = "tg://proxy?server=149.154.167.50&port=443&secret=11223344556677889900aabbccddeeff",
        server = "149.154.167.50",
        port = 443,
        secret = "11223344556677889900aabbccddeeff",
        country = MtProtoProxy.COUNTRY_UNKNOWN_NAME,
        countryCode = MtProtoProxy.COUNTRY_UNKNOWN_CODE,
        isWorking = true,
        latency = 80L,
        successRate = 1f,
        stability = 1f
    )

    @Test
    fun acceptsPersistedSavedMessageWithMatchingConnectionLink() {
        val messageId = SavedProxyMessageVerifier.requireMatchingMessage(
            message = savedMessage(proxy.url),
            expectedChatId = 42L,
            proxy = proxy
        )

        assertEquals(9001L, messageId)
    }

    @Test
    fun rejectsMessageWithoutMatchingConnectionLink() {
        assertThrows(IllegalArgumentException::class.java) {
            SavedProxyMessageVerifier.requireMatchingMessage(
                message = savedMessage("https://example.com"),
                expectedChatId = 42L,
                proxy = proxy
            )
        }
    }

    private fun savedMessage(connectionUrl: String): JsonObject = mapOf(
        "@type" to "message",
        "id" to 9001L,
        "chat_id" to 42L,
        "content" to mapOf(
            "@type" to "messageText",
            "text" to mapOf(
                "@type" to "formattedText",
                "text" to "ProxyX\nServer: ${proxy.server}\nPort: ${proxy.port}",
                "entities" to listOf(
                    mapOf(
                        "@type" to "textEntity",
                        "offset" to 0,
                        "length" to 6,
                        "type" to mapOf(
                            "@type" to "textEntityTypeTextUrl",
                            "url" to connectionUrl
                        )
                    )
                )
            )
        )
    )
}
