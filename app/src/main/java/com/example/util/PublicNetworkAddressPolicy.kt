package com.example.util

import java.net.InetAddress

internal object PublicNetworkAddressPolicy {
    fun isPublic(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress ||
            address.isLoopbackAddress ||
            address.isLinkLocalAddress ||
            address.isSiteLocalAddress ||
            address.isMulticastAddress
        ) {
            return false
        }

        val bytes = address.address
        return when (bytes.size) {
            IPV4_BYTES -> isPublicIpv4(bytes)
            IPV6_BYTES -> mappedIpv4(bytes)?.let(::isPublicIpv4) ?: isPublicIpv6(bytes)
            else -> false
        }
    }

    private fun isPublicIpv4(bytes: ByteArray): Boolean {
        val first = bytes[0].toInt() and 0xff
        val second = bytes[1].toInt() and 0xff
        val third = bytes[2].toInt() and 0xff
        return when {
            first == 0 -> false
            first == 10 -> false
            first == 100 && second in 64..127 -> false
            first == 127 -> false
            first == 169 && second == 254 -> false
            first == 172 && second in 16..31 -> false
            first == 192 && second == 0 && third == 0 -> false
            first == 192 && second == 0 && third == 2 -> false
            first == 192 && second == 88 && third == 99 -> false
            first == 192 && second == 168 -> false
            first == 198 && second in 18..19 -> false
            first == 198 && second == 51 && third == 100 -> false
            first == 203 && second == 0 && third == 113 -> false
            first >= 224 -> false
            else -> true
        }
    }

    private fun isPublicIpv6(bytes: ByteArray): Boolean {
        val first = bytes[0].toInt() and 0xff
        val second = bytes[1].toInt() and 0xff
        if ((first and 0xfe) == 0xfc) return false
        if (first == 0x20 && second == 0x01 &&
            (bytes[2].toInt() and 0xff) == 0x0d &&
            (bytes[3].toInt() and 0xff) == 0xb8
        ) {
            return false
        }
        return true
    }

    private fun mappedIpv4(bytes: ByteArray): ByteArray? {
        if (bytes.take(10).any { it.toInt() != 0 }) return null
        if ((bytes[10].toInt() and 0xff) != 0xff || (bytes[11].toInt() and 0xff) != 0xff) {
            return null
        }
        return bytes.copyOfRange(12, 16)
    }

    private const val IPV4_BYTES = 4
    private const val IPV6_BYTES = 16
}
