package com.example.data.network

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import java.security.MessageDigest
import javax.net.SocketFactory

internal data class UserNetworkIdentity(
    val fingerprint: String,
    val type: String,
    val isMetered: Boolean
)

/** A single active Android network captured for the lifetime of one measurement. */
internal class UserNetworkSession private constructor(
    private val connectivityManager: ConnectivityManager?,
    internal val network: Network?,
    val identity: UserNetworkIdentity
) {
    fun createSocket(): Socket {
        ensureCurrent()
        return network?.socketFactory?.createSocket() ?: Socket()
    }

    fun resolve(hostname: String): Array<InetAddress> {
        ensureCurrent()
        val addresses = dns.resolve {
            network?.getAllByName(hostname) ?: InetAddress.getAllByName(hostname)
        }
        ensureCurrent()
        return addresses
    }

    fun socketFactory(): SocketFactory {
        ensureCurrent()
        return network?.socketFactory ?: SocketFactory.getDefault()
    }

    fun ensureCurrent() {
        val manager = connectivityManager ?: return
        val capturedNetwork = network ?: throw UserNetworkUnavailableException()
        if (manager.activeNetwork != capturedNetwork ||
            capture(manager, true)?.identity != identity) {
            throw UserNetworkChangedException()
        }
    }

    companion object {
        private val dns = BoundedDnsLookup()
        fun capture(
            connectivityManager: ConnectivityManager?,
            requireActiveNetwork: Boolean
        ): UserNetworkSession? {
            if (connectivityManager == null) {
                return if (requireActiveNetwork) null else UserNetworkSession(
                    connectivityManager = null,
                    network = null,
                    identity = UserNetworkIdentity("unbound-test-network", "TEST", false)
                )
            }

            val activeNetwork = connectivityManager.activeNetwork ?: return null
            if (!connectivityManager.hasInternetCapability(activeNetwork)) return null
            val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return null
            val linkProperties = connectivityManager.getLinkProperties(activeNetwork)
            val networkType = when {
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "CELLULAR"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ETHERNET"
                else -> "OTHER"
            }
            val fingerprintMaterial = buildString {
                append(networkType)
                append('|').append(linkProperties?.interfaceName.orEmpty())
                append('|').append(
                    linkProperties?.dnsServers
                        ?.map { it.hostAddress.orEmpty() }
                        ?.sorted()
                        ?.joinToString(",")
                        .orEmpty()
                )
                append('|').append(
                    linkProperties?.routes
                        ?.filter { it.isDefaultRoute }
                        ?.mapNotNull { it.gateway?.hostAddress }
                        ?.sorted()
                        ?.joinToString(",")
                        .orEmpty()
                )
                append('|').append(
                    linkProperties?.linkAddresses
                        ?.map { linkAddress ->
                            "${linkAddress.address.hostAddress.orEmpty().substringBefore('%')}/" +
                                linkAddress.prefixLength
                        }
                        ?.sorted()
                        ?.joinToString(",")
                        .orEmpty()
                )
                append('|').append(
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                )
                val privateDnsServer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    linkProperties?.privateDnsServerName.orEmpty()
                } else {
                    ""
                }
                append('|').append(privateDnsServer)
            }
            val fingerprint = MessageDigest.getInstance("SHA-256")
                .digest(fingerprintMaterial.toByteArray(Charsets.UTF_8))
                .take(12)
                .joinToString("") { byte ->
                    (byte.toInt() and 0xff).toString(16).padStart(2, '0')
                }
            return UserNetworkSession(
                connectivityManager = connectivityManager,
                network = activeNetwork,
                identity = UserNetworkIdentity(
                    fingerprint = fingerprint,
                    type = networkType,
                    isMetered = !capabilities.hasCapability(
                        NetworkCapabilities.NET_CAPABILITY_NOT_METERED
                    )
                )
            )
        }

        private fun ConnectivityManager.hasInternetCapability(network: Network): Boolean {
            val capabilities = getNetworkCapabilities(network) ?: return false
            return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
    }
}

open class UserNetworkException(message: String) : IOException(message)

class UserNetworkUnavailableException : UserNetworkException(
    "No active user network with Internet capability is available."
)

class UserNetworkChangedException : UserNetworkException(
    "The active user network changed during measurement."
)
