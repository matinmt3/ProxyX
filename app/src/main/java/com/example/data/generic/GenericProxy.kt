package com.example.data.generic

import androidx.room.Entity
import androidx.room.PrimaryKey
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.Locale

enum class GenericProtocol { HTTP, HTTP_CONNECT, SOCKS4, SOCKS4A, SOCKS5 }

@Entity(tableName = "generic_proxies")
data class GenericProxy(
    @PrimaryKey val id: String,
    val host: String,
    val port: Int,
    val protocol: String,
    val sourceIds: String,
    val sourceCountry: String,
    val fetchedAt: Long,
    val status: String = "UNTESTED",
    val testedAt: Long = 0,
    val connectionMs: Long = 0,
    val requestMs: Long = 0,
    val errorCode: String = "",
    val networkFingerprint: String = ""
)

@Entity(tableName = "generic_source_health")
data class GenericSourceHealth(
    @PrimaryKey val id: String,
    val attemptedAt: Long,
    val succeededAt: Long = 0,
    val accepted: Int = 0,
    val errorCode: String = ""
)

data class GenericFeed(val id: String, val url: String, val protocol: GenericProtocol?,
    val country: String = "IR", val minimumIntervalMs: Long = 20 * 60_000)

object GenericSources {
    val feeds = listOf(
        GenericFeed("proxyspace-http", "https://proxyspace.pro/country/ir/http.txt", GenericProtocol.HTTP),
        // The provider's HTTPS list means CONNECT over an HTTP proxy, not TLS to that proxy.
        GenericFeed("proxyspace-connect", "https://proxyspace.pro/country/ir/https.txt", GenericProtocol.HTTP_CONNECT),
        GenericFeed("proxyspace-socks4", "https://proxyspace.pro/country/ir/socks4.txt", GenericProtocol.SOCKS4),
        GenericFeed("proxyspace-socks5", "https://proxyspace.pro/country/ir/socks5.txt", GenericProtocol.SOCKS5),
        GenericFeed("proxy-scraper", "https://maximilianfeix.github.io/proxy-scraper/country/ir/proxies.txt", null,
            minimumIntervalMs = 60 * 60_000)
    )

    // Research-only entries cannot be fetched by this registry.
    val disabled = mapOf(
        "SPYS.ONE" to "EXTRACTION_UNVERIFIED", "PremProxy" to "HTTP_404",
        "ProxyNova" to "ROBOTS_RESTRICTED", "Free-Proxy.cz" to "UNAVAILABLE",
        "HideMy.name" to "UNAVAILABLE", "Proxy Spider" to "ACCOUNT_REQUIRED",
        "ProxyStack" to "UNAVAILABLE", "ProxyDocker" to "ACCESS_UNVERIFIED",
        "FreeProxyUpdate" to "ACCESS_UNVERIFIED", "ProxyRanker" to "UNAVAILABLE",
        "FreeProxy.World" to "ACCESS_UNVERIFIED", "Litport" to "ACCOUNT_REQUIRED",
        "ProxyHub" to "ACCESS_UNVERIFIED", "ProxyLister" to "ACCESS_UNVERIFIED",
        "ProxyCheck" to "UNAVAILABLE", "ProxyVerity" to "MASKED_ENDPOINTS"
    )
}

internal object GenericProxyParser {
    fun parse(text: String, feed: GenericFeed, now: Long): List<GenericProxy> = text.lineSequence()
        .mapNotNull { parseLine(it.trim(), feed, now) }.distinctBy { it.id }.toList()

    private fun parseLine(line: String, feed: GenericFeed, now: Long): GenericProxy? {
        if (line.isBlank() || line.startsWith('#') || line.length > 512) return null
        val scheme = line.substringBefore("://", "").lowercase(Locale.ROOT)
        val protocol = if (scheme.isEmpty()) feed.protocol else when (scheme) {
            "http" -> GenericProtocol.HTTP
            "socks4" -> GenericProtocol.SOCKS4
            "socks4a" -> GenericProtocol.SOCKS4A
            "socks5" -> GenericProtocol.SOCKS5
            else -> null // Ambiguous https:// and credentials must not be guessed.
        }
        if (protocol == null || (feed.protocol != null && scheme.isNotEmpty() && protocol != feed.protocol)) return null
        val authority = if (scheme.isEmpty()) line else line.substringAfter("://")
        if (authority.any { it.isWhitespace() } || !authority.substringAfterLast(':').matches(Regex("[0-9]{1,5}"))) return null
        val url = "http://$authority".toHttpUrlOrNull() ?: return null
        if (url.username.isNotEmpty() || url.password.isNotEmpty() || url.encodedPath != "/" ||
            url.query != null || url.fragment != null || url.port !in 1..65535) return null
        val host = url.host
        if (host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local")) return null
        if (host.all { it.isDigit() || it == '.' } || ':' in host) {
            val address = runCatching { java.net.InetAddress.getByName(host) }.getOrNull() ?: return null
            if (!com.example.util.PublicNetworkAddressPolicy.isPublic(address)) return null
        }
        return GenericProxy("${protocol.name}|$host|${url.port}", host, url.port, protocol.name,
            feed.id, feed.country, now)
    }
}
