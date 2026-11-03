package com.example.data.telegram

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TdlibJsonCodecTest {
    @Test
    fun requestRoundTripPreservesNestedTdlibObjectsAndInt53Values() {
        val request = mapOf(
            "@type" to "sendMessage",
            "chat_id" to 9_007_199_254_740_000L,
            "input_message_content" to mapOf(
                "@type" to "inputMessageDocument",
                "document" to mapOf(
                    "@type" to "inputDocument",
                    "document" to mapOf("@type" to "inputFileLocal", "path" to "/tmp/sample.bin"),
                    "thumbnail" to null,
                    "disable_content_type_detection" to true
                )
            )
        )

        val decoded = requireNotNull(TdlibJsonCodec.decode(TdlibJsonCodec.encode(request)))
        val document = decoded.objectValue("input_message_content")
            ?.objectValue("document")

        assertEquals("sendMessage", decoded.stringValue("@type"))
        assertEquals(9_007_199_254_740_000L, decoded.longValue("chat_id"))
        assertEquals("inputDocument", document?.stringValue("@type"))
        assertTrue(document?.booleanValue("disable_content_type_detection") == true)
        assertNull(document?.get("thumbnail"))
    }

    @Test
    fun typedAccessorsRejectWrongTypesWithoutFabricatingValues() {
        val value: JsonObject = mapOf("string" to 1, "bool" to "true")

        assertNull(value.stringValue("string"))
        assertFalse(value.booleanValue("bool"))
    }

    @Test
    fun responsePayloadWritesNestedEntityOffsetsBackAsIntegers() {
        val formattedText = requireNotNull(
            TdlibJsonCodec.decode(
                """{"@type":"formattedText","text":"Proxy","entities":[{"@type":"textEntity","offset":0,"length":5,"type":{"@type":"textEntityTypeBold"}}],"score":1.25,"@extra":"request-id"}"""
            )
        )

        val encoded = TdlibJsonCodec.encode(
            mapOf(
                "@type" to "inputMessageText",
                "text" to formattedText.asTdlibRequestPayload()
            )
        )

        assertTrue(encoded.contains("\"offset\":0"))
        assertTrue(encoded.contains("\"length\":5"))
        assertFalse(encoded.contains("\"offset\":0.0"))
        assertFalse(encoded.contains("\"length\":5.0"))
        assertFalse(encoded.contains("@extra"))
        assertTrue(encoded.contains("\"score\":1.25"))
    }

    @Test
    fun successfulMessageUpdateReturnsTheFinalDeliveredMessage() {
        val event: JsonObject = mapOf(
            "@type" to "updateMessageSendSucceeded",
            "old_message_id" to -42L,
            "message" to mapOf("@type" to "message", "id" to 123L)
        )

        assertEquals(123L, event.requireDeliveredMessage().longValue("id"))
    }

    @Test
    fun failedMessageUpdatePreservesTheTdlibError() {
        val event: JsonObject = mapOf(
            "@type" to "updateMessageSendFailed",
            "old_message_id" to -42L,
            "error" to mapOf("@type" to "error", "code" to 429, "message" to "Too Many Requests")
        )

        try {
            event.requireDeliveredMessage()
            fail("Expected a TDLib request failure")
        } catch (error: TdlibRequestException) {
            assertEquals(429, error.code)
            assertEquals("Too Many Requests", error.message)
        }
    }
}
