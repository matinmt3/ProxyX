package com.example.data.network

import com.example.util.PublicNetworkAddressPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class PublicNetworkAddressPolicyTest {
    @Test
    fun acceptsPublicIpv4AndIpv6Addresses() {
        assertTrue(isPublic("1.1.1.1"))
        assertTrue(isPublic("8.8.8.8"))
        assertTrue(isPublic("2606:4700:4700::1111"))
    }

    @Test
    fun rejectsPrivateCarrierLocalAndDocumentationRanges() {
        listOf(
            "0.0.0.0",
            "10.0.0.1",
            "100.64.0.1",
            "127.0.0.1",
            "169.254.1.1",
            "172.16.0.1",
            "192.168.1.1",
            "198.18.0.1",
            "192.0.2.1",
            "198.51.100.1",
            "203.0.113.1",
            "224.0.0.1",
            "::1",
            "fd00::1",
            "fe80::1",
            "2001:db8::1"
        ).forEach { address ->
            assertFalse("Expected $address to be blocked", isPublic(address))
        }
    }

    private fun isPublic(value: String): Boolean {
        return PublicNetworkAddressPolicy.isPublic(InetAddress.getByName(value))
    }
}
