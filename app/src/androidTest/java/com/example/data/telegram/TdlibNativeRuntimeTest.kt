package com.example.data.telegram

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.drinkless.tdlib.JsonClient
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TdlibNativeRuntimeTest {
    @Test
    fun tdlibDatabaseKeyIsStableAcrossReads() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val keyStore = TdlibDatabaseKeyStore(context)

        val first = keyStore.getOrCreate()
        val second = keyStore.getOrCreate()

        assertEquals(32, first.size)
        assertArrayEquals(first, second)
    }

    @Test
    fun officialJsonJavaRuntimeLoadsAndExecutesARequest() {
        assertTrue("libtdjsonjava.so wasn't loaded", JsonClient.isAvailable())

        val response = requireNotNull(
            JsonClient.execute(
                """{"@type":"setLogVerbosityLevel","new_verbosity_level":0}"""
            )
        )
        val decoded = requireNotNull(TdlibJsonCodec.decode(response))

        assertEquals("ok", decoded.stringValue("@type"))
    }

    @Test
    fun officialRuntimeCreatesAClientAndReceivesItsAsynchronousResponse() {
        val clientId = JsonClient.createClientId()
        val requestId = "proxyx-native-smoke"
        JsonClient.send(
            clientId,
            """{"@type":"getOption","name":"version","@extra":"$requestId"}"""
        )

        val deadline = SystemClock.elapsedRealtime() + 10_000L
        var matchingResponse: JsonObject? = null
        while (SystemClock.elapsedRealtime() < deadline && matchingResponse == null) {
            val encoded = JsonClient.receive(0.5) ?: continue
            val response = TdlibJsonCodec.decode(encoded) ?: continue
            if (response.stringValue("@extra") == requestId) matchingResponse = response
        }

        val response = requireNotNull(matchingResponse) {
            "TDLib didn't answer getOption(version)."
        }
        assertEquals(clientId, response.intValue("@client_id"))
        assertEquals("optionValueString", response.stringValue("@type"))
        assertTrue(response.stringValue("value").orEmpty().isNotBlank())
    }

    @Test
    fun nativeRuntimeParsesSavedProxyHtmlTemplate() {
        assertTrue("libtdjsonjava.so wasn't loaded", JsonClient.isAvailable())
        val response = JsonClient.execute(
            TdlibJsonCodec.encode(
                mapOf(
                    "@type" to "parseTextEntities",
                    "text" to "<b>ProxyX</b> | <a href=\"tg://proxy?server=1.1.1.1&amp;port=443&amp;secret=00112233445566778899aabbccddeeff\">Connect</a>",
                    "parse_mode" to mapOf("@type" to "textParseModeHTML")
                )
            )
        )
        val formattedText = requireNotNull(response).let(TdlibJsonCodec::decode)

        assertEquals("formattedText", formattedText?.stringValue("@type"))
        assertTrue((formattedText?.get("entities") as? List<*>)?.isNotEmpty() == true)
        assertTrue(formattedText?.stringValue("text")?.contains("tg://") == false)
    }
}
