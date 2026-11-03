package com.example

import com.example.model.MtProtoProxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MtProtoProxyTest {

    @Test
    fun qualityScoreUsesOnlyMeasuredConnectionEvidence() {
        val ready = MtProtoProxy.calculateScore(
            latency = 90,
            successRate = 1f,
            stability = 0.95f,
            protocolLatency = 130,
            protocolSuccessRate = 1f
        )
        val partial = MtProtoProxy.calculateScore(
            latency = 90,
            successRate = 1f,
            stability = 0.95f,
            protocolLatency = 130,
            protocolSuccessRate = 0.5f
        )
        val tcpOnly = MtProtoProxy.calculateScore(
            latency = 90,
            successRate = 1f,
            stability = 0.95f,
            protocolLatency = 0,
            protocolSuccessRate = 0f
        )

        assertTrue(ready > partial)
        assertTrue(partial > tcpOnly)
    }

    @Test
    fun qualityScorePenalizesTailLatencyAndJitter() {
        val consistent = MtProtoProxy.calculateScore(
            latency = 90,
            successRate = 1f,
            stability = 0.95f,
            protocolLatency = 130,
            protocolSuccessRate = 1f,
            tcpP90Latency = 110,
            tcpJitter = 4,
            protocolP90Latency = 160,
            protocolJitter = 7
        )
        val spiky = MtProtoProxy.calculateScore(
            latency = 90,
            successRate = 1f,
            stability = 0.75f,
            protocolLatency = 130,
            protocolSuccessRate = 1f,
            tcpP90Latency = 1_100,
            tcpJitter = 90,
            protocolP90Latency = 2_000,
            protocolJitter = 180
        )

        assertTrue(consistent > spiky)
    }

    @Test
    fun parseFromUrlNormalizesHexAndBase64Secrets() {
        val valid = MtProtoProxy.parseFromUrl(
            "tg://proxy?server=1.2.3.4&port=443&secret=dd79e344818749bd7ac519130220c25d09"
        )
        val base64 = MtProtoProxy.parseFromUrl(
            "https://t.me/proxy?server=1.2.3.4&port=443&secret=eeNEgYdJvXrFGRMCIMJdCQ"
        )
        val invalid = MtProtoProxy.parseFromUrl(
            "https://t.me/proxy?server=1.2.3.4&port=443&secret=not-a-valid-secret"
        )

        assertNotNull(valid)
        assertNotNull(base64)
        assertEquals("79e344818749bd7ac519130220c25d09", base64?.secret)
        assertEquals("tg://proxy?server=1.2.3.4&port=443&secret=79e344818749bd7ac519130220c25d09", base64?.url)
        assertNull(invalid)
    }

    @Test
    fun parseFromUrlValidatesLinkAndUsesStableEndpointIdentity() {
        val iran = MtProtoProxy.parseFromUrl(
            "https://t.me/proxy?server=Proxy.Example.com&port=443&secret=dd79e344818749bd7ac519130220c25d09#IR"
        )
        val misleadingFragment = MtProtoProxy.parseFromUrl(
            "tg://proxy?server=friendly.example.com&port=443&secret=dd79e344818749bd7ac519130220c25d09#FRIEND"
        )
        val wrongHost = MtProtoProxy.parseFromUrl(
            "https://example.com/proxy?server=proxy.example.com&port=443&secret=dd79e344818749bd7ac519130220c25d09"
        )
        val invalidServer = MtProtoProxy.parseFromUrl(
            "tg://proxy?server=bad%20host&port=443&secret=dd79e344818749bd7ac519130220c25d09"
        )

        assertEquals("UN", iran?.countryCode)
        assertEquals("proxy.example.com", iran?.server)
        assertEquals(
            "tg://proxy?server=proxy.example.com&port=443&secret=dd79e344818749bd7ac519130220c25d09",
            iran?.url
        )
        assertEquals("UN", misleadingFragment?.countryCode)
        assertNull(wrongHost)
        assertNull(invalidServer)
    }

    @Test
    fun rejectsTelegramSocksLinks() {
        assertNull(
            MtProtoProxy.parseFromUrl(
                "https://t.me/socks?server=proxy.example.com&port=1080"
            )
        )
        assertNull(MtProtoProxy.parseFromUrl("tg://socks?server=8.8.8.8&port=1080"))
    }

    @Test
    fun rejectsSecretsThatAreNotSixteenBytesOrOnePrefixedByte() {
        val tooLong = MtProtoProxy.parseFromUrl(
            "tg://proxy?server=proxy.example.com&port=443&secret=" + "ab".repeat(18)
        )

        assertNull(tooLong)
    }

    @Test
    fun acceptsExtendedFakeTlsSecretWithSniPayload() {
        val fakeTls = MtProtoProxy.parseFromUrl(
            "https://t.me/proxy?server=fake-tls.example.com&port=443&secret=" +
                "7jK5IN_7UWQwKOL2uHjU6sFkbXl3ZWIuY2xvdWRmcm9udC5uZXQ"
        )

        assertNotNull(fakeTls)
        assertTrue(fakeTls?.secret?.startsWith("ee") == true)
    }

    @Test
    fun rejectsMalformedHostNamesAndOutOfRangeIpv4Addresses() {
        val malformedHost = MtProtoProxy.parseFromUrl(
            "tg://proxy?server=-bad.example.com&port=443&secret=dd79e344818749bd7ac519130220c25d09"
        )
        val invalidIpv4 = MtProtoProxy.parseFromUrl(
            "tg://proxy?server=999.2.3.4&port=443&secret=dd79e344818749bd7ac519130220c25d09"
        )

        assertNull(malformedHost)
        assertNull(invalidIpv4)
    }

    @Test
    fun acceptsBracketedIpv6AndRejectsLocalEndpoints() {
        val ipv6 = MtProtoProxy.parseFromUrl(
            "tg://proxy?server=%5B2606%3A4700%3A4700%3A%3A1111%5D&port=443&secret=dd79e344818749bd7ac519130220c25d09"
        )
        val loopback = MtProtoProxy.parseFromUrl(
            "tg://proxy?server=127.0.0.1&port=443&secret=dd79e344818749bd7ac519130220c25d09"
        )
        val localhost = MtProtoProxy.parseFromUrl(
            "tg://proxy?server=localhost&port=443&secret=dd79e344818749bd7ac519130220c25d09"
        )

        assertEquals("2606:4700:4700::1111", ipv6?.server)
        assertNull(loopback)
        assertNull(localhost)
    }

    @Test
    fun rejectsCarrierGradeNatAndReservedIpv6Endpoints() {
        val secret = "dd79e344818749bd7ac519130220c25d09"

        assertNull(MtProtoProxy.parseFromUrl("tg://proxy?server=100.64.1.2&port=443&secret=$secret"))
        assertNull(
            MtProtoProxy.parseFromUrl(
                "tg://proxy?server=%5Bfd00%3A%3A1%5D&port=443&secret=$secret"
            )
        )
        assertNull(
            MtProtoProxy.parseFromUrl(
                "tg://proxy?server=%5B2001%3Adb8%3A%3A1%5D&port=443&secret=$secret"
            )
        )
    }

    @Test
    fun rejectsUnknownPrefixedSecretInsteadOfTreatingItAsAValidKey() {
        val invalid = MtProtoProxy.parseFromUrl(
            "tg://proxy?server=proxy.example.com&port=443&secret=ab79e344818749bd7ac519130220c25d09"
        )

        assertNull(invalid)
    }

    @Test
    fun rejectsFakeTlsSecretWithoutValidSniDomain() {
        val invalid = MtProtoProxy.parseFromUrl(
            "tg://proxy?server=proxy.example.com&port=443&secret=" +
                "ee79e344818749bd7ac519130220c25d09" + "00010203"
        )

        assertNull(invalid)
    }

    @Test
    fun staleVerifiedStateIsNotReportedAsTelegramReady() {
        val proxy = requireNotNull(
            MtProtoProxy.parseFromUrl(
                "tg://proxy?server=proxy.example.com&port=443&secret=dd79e344818749bd7ac519130220c25d09"
            )
        ).copy(
            isWorking = false,
            latency = 120,
            verificationStatus = MtProtoProxy.VERIFICATION_TELEGRAM_READY
        )

        assertEquals(com.example.model.ProxyVerificationLevel.OFFLINE, proxy.verificationLevel)
        assertFalse(proxy.isTelegramReady)
    }

    @Test
    fun verifiedStateWithoutProtocolEvidenceIsOffline() {
        val proxy = requireNotNull(
            MtProtoProxy.parseFromUrl(
                "tg://proxy?server=proxy.example.com&port=443&secret=dd79e344818749bd7ac519130220c25d09"
            )
        ).copy(
            isWorking = true,
            latency = 120,
            verificationStatus = MtProtoProxy.VERIFICATION_MTPROTO_VERIFIED
        )

        assertEquals(com.example.model.ProxyVerificationLevel.OFFLINE, proxy.verificationLevel)
        assertFalse(proxy.isTelegramVerified)
    }

    @Test
    fun readyStateWithOneRecordedRouteIsDowngradedToVerified() {
        val proxy = requireNotNull(
            MtProtoProxy.parseFromUrl(
                "tg://proxy?server=proxy.example.com&port=443&secret=dd79e344818749bd7ac519130220c25d09"
            )
        ).copy(
            isWorking = true,
            latency = 120,
            verificationStatus = MtProtoProxy.VERIFICATION_TELEGRAM_READY,
            protocolLatency = 80,
            protocolSuccessRate = 0.5f
        )

        assertEquals(com.example.model.ProxyVerificationLevel.MTPROTO_VERIFIED, proxy.verificationLevel)
        assertTrue(proxy.isTelegramVerified)
        assertFalse(proxy.isTelegramReady)
    }

    @Test
    fun entityDoesNotStoreEstimatedProxyThroughput() {
        val fieldNames = MtProtoProxy::class.java.declaredFields.mapTo(mutableSetOf()) { it.name }

        assertFalse("downloadKbps" in fieldNames)
        assertFalse("uploadKbps" in fieldNames)
        assertFalse("speedCategory" in fieldNames)
    }
}
