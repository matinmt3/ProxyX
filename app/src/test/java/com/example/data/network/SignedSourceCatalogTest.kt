package com.example.data.network

import android.util.Base64
import com.squareup.moshi.Moshi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.security.KeyPairGenerator
import java.security.Signature

@RunWith(RobolectricTestRunner::class)
class SignedSourceCatalogTest {
    private val moshi = Moshi.Builder().build()

    @Test
    fun validSignatureWithWrongCurveIsRejected() {
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(384) }.generateKeyPair()
        val payload = SourceCatalogPayload(1, 9_999, 70_000,
            sources = listOf(CatalogSource("Curve test", "https://example.com/feed", "Test")))
        assertThrows(IllegalArgumentException::class.java) {
            SignedSourceCatalogVerifier.verify(signedEnvelope(payload, pair),
                Base64.encodeToString(pair.public.encoded, Base64.NO_WRAP), 10_000)
        }
    }

    @Test
    fun validEnvelopeIsVerifiedAndParsed() {
        val now = 10_000L
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val payload = SourceCatalogPayload(
            schemaVersion = 1,
            issuedAt = now - 1,
            expiresAt = now + 60_000,
            sources = listOf(
                CatalogSource("Verified feed", "https://example.com/mtproto.txt", "Signed test")
            )
        )
        val payloadJson = requireNotNull(moshi.adapter(SourceCatalogPayload::class.java).toJson(payload))
        val payloadBytes = payloadJson.toByteArray(Charsets.UTF_8)
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(pair.private)
            update(payloadBytes)
            sign()
        }
        val envelope = SignedCatalogEnvelope(
            payload = Base64.encodeToString(payloadBytes, Base64.NO_WRAP),
            signature = Base64.encodeToString(signature, Base64.NO_WRAP)
        )
        val envelopeJson = moshi.adapter(SignedCatalogEnvelope::class.java).toJson(envelope)

        val verified = SignedSourceCatalogVerifier.verify(
            envelopeJson,
            Base64.encodeToString(pair.public.encoded, Base64.NO_WRAP),
            now
        )

        assertEquals(1, verified.sources.size)
        assertEquals("https://example.com/mtproto.txt", verified.sources.single().url)
    }

    @Test
    fun tamperedPayloadIsRejected() {
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val payload = "{\"schemaVersion\":1,\"issuedAt\":1,\"expiresAt\":9999999999999," +
            "\"sources\":[{\"name\":\"Feed one\",\"url\":\"https://example.com/a\"," +
            "\"description\":\"A\"}]}"
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(pair.private)
            update(payload.toByteArray())
            sign()
        }
        val envelope = SignedCatalogEnvelope(
            payload = Base64.encodeToString((payload + " ").toByteArray(), Base64.NO_WRAP),
            signature = Base64.encodeToString(signature, Base64.NO_WRAP)
        )

        val error = assertThrows(IllegalArgumentException::class.java) {
            SignedSourceCatalogVerifier.verify(
                moshi.adapter(SignedCatalogEnvelope::class.java).toJson(envelope),
                Base64.encodeToString(pair.public.encoded, Base64.NO_WRAP),
                now = 2L
            )
        }
        assertTrue(error.message.orEmpty().contains("signature", ignoreCase = true))
    }

    @Test
    fun privateAndMalformedIpSourcesAreRejected() {
        val now = 10_000L
        listOf(
            "https://127.0.0.1/feed",
            "https://192.168.1.4/feed",
            "https://100.64.1.4/feed",
            "https://[::1]/feed",
            "https://[fd00::1]/feed",
            "https://999.1.1.1/feed"
        ).forEach { url ->
            val pair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
            val envelopeJson = signedEnvelope(
                SourceCatalogPayload(
                    schemaVersion = 1,
                    issuedAt = now - 1,
                    expiresAt = now + 60_000,
                    sources = listOf(CatalogSource("Blocked feed", url, "Test"))
                ),
                pair
            )

            assertThrows(IllegalArgumentException::class.java) {
                SignedSourceCatalogVerifier.verify(
                    envelopeJson,
                    Base64.encodeToString(pair.public.encoded, Base64.NO_WRAP),
                    now
                )
            }
        }
    }

    @Test
    fun expiredCatalogIsRejected() {
        val now = 100_000L
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val envelopeJson = signedEnvelope(
            SourceCatalogPayload(
                schemaVersion = 1,
                issuedAt = now - 10_000,
                expiresAt = now - 1,
                sources = listOf(
                    CatalogSource("Expired feed", "https://example.com/feed", "Test")
                )
            ),
            pair
        )

        val error = assertThrows(IllegalArgumentException::class.java) {
            SignedSourceCatalogVerifier.verify(
                envelopeJson,
                Base64.encodeToString(pair.public.encoded, Base64.NO_WRAP),
                now
            )
        }
        assertTrue(error.message.orEmpty().contains("expired", ignoreCase = true))
    }

    @Test
    fun duplicateSourceNamesAreRejectedCaseInsensitively() {
        val now = 100_000L
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val envelopeJson = signedEnvelope(
            SourceCatalogPayload(
                schemaVersion = 1,
                issuedAt = now - 1,
                expiresAt = now + 60_000,
                sources = listOf(
                    CatalogSource("Primary feed", "https://example.com/a", "A"),
                    CatalogSource("primary FEED", "https://example.org/b", "B")
                )
            ),
            pair
        )

        val error = assertThrows(IllegalArgumentException::class.java) {
            SignedSourceCatalogVerifier.verify(
                envelopeJson,
                Base64.encodeToString(pair.public.encoded, Base64.NO_WRAP),
                now
            )
        }
        assertTrue(error.message.orEmpty().contains("names", ignoreCase = true))
    }

    private fun signedEnvelope(
        payload: SourceCatalogPayload,
        pair: java.security.KeyPair
    ): String {
        val payloadBytes = moshi.adapter(SourceCatalogPayload::class.java)
            .toJson(payload)
            .toByteArray(Charsets.UTF_8)
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(pair.private)
            update(payloadBytes)
            sign()
        }
        return moshi.adapter(SignedCatalogEnvelope::class.java).toJson(
            SignedCatalogEnvelope(
                payload = Base64.encodeToString(payloadBytes, Base64.NO_WRAP),
                signature = Base64.encodeToString(signature, Base64.NO_WRAP)
            )
        )
    }
}
