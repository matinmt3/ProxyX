package com.example.data.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProxySourceManagerTest {
    @Test
    fun knownMissingRepositoriesAreRetainedButNotFetched() {
        val unavailable = ProxySourceManager.sources.filter {
            it.url.contains("/V2RAYCONFIGSPOOL/") || it.url.contains("/iwh3n/")
        }
        assertEquals(11, unavailable.size)
        assertTrue(unavailable.none { it.isEnabled })
        assertTrue(ProxySourceManager.getEnabledSources().none { it in unavailable })
    }

    @Test
    fun mergedCatalogCannotOverwriteHealthByReusingABundledName() {
        val bundled = ProxySourceManager.sources.first()
        try {
            ProxySourceManager.applyCatalog(CatalogLoadResult(
                VerifiedSourceCatalog(listOf(bundled.copy(url = "https://example.com/new-feed")),
                    false, 1, Long.MAX_VALUE), SourceCatalogOrigin.SIGNED_REMOTE))
            val sources = ProxySourceManager.sources
            assertEquals(sources.size, sources.map { it.name.lowercase() }.distinct().size)
        } finally {
            ProxySourceManager.applyCatalog(CatalogLoadResult(null, SourceCatalogOrigin.BUNDLED))
        }
    }

    @Test
    fun sourceCatalogUsesUniqueSecureEndpoints() {
        val sources = ProxySourceManager.sources

        assertTrue(sources.isNotEmpty())
        assertEquals(sources.size, sources.map { it.name }.distinct().size)
        assertEquals(sources.size, sources.map { it.url }.distinct().size)
        assertTrue(sources.all { it.url.startsWith("https://") })
        assertTrue(sources.all { it.description.isNotBlank() })
        assertEquals(50, sources.size)
        assertTrue(sources.none { it.name.contains("socks", ignoreCase = true) })
        assertTrue(sources.none { it.url.contains("socks", ignoreCase = true) })
    }

    @Test
    fun highCadenceVerifiedFeedsRemainInCatalog() {
        val sourceUrls = ProxySourceManager.sources.map { it.url }.toSet()

        assertTrue(
            sourceUrls.contains(
                "https://raw.githubusercontent.com/aviamastersgh/mtproto-free-russia/" +
                    "main/verified_proxies.txt"
            )
        )
        assertTrue(
            sourceUrls.contains(
                "https://raw.githubusercontent.com/darkvibez456/mtproto-proxy-auto/" +
                    "main/proxies.txt"
            )
        )
        assertTrue(
            sourceUrls.contains(
                "https://raw.githubusercontent.com/ded-maxim-1337/Telegram-Proxy-Base/" +
                    "gh-pages/proxy_all_verified.json"
            )
        )
    }

    @Test
    fun requestedAndLiveVerifiedMtProtoFeedsArePresentOnce() {
        val requested = setOf(
            "https://raw.githubusercontent.com/MustafaBaqer/VestraNet-Nodes/refs/heads/main/protocols/mtproto.txt",
            "https://raw.githubusercontent.com/Surfboardv2ray/TGProto/refs/heads/main/proxies.txt",
            "https://raw.githubusercontent.com/Argh94/Proxy-List/main/MTProto.txt",
            "https://raw.githubusercontent.com/MhdiTaheri/ProxyCollector/main/proxy.txt",
            "https://itsyebekhe.github.io/MTProtoNexus/extracted_proxies.json",
            "https://raw.githubusercontent.com/Ch4120N/Ch4120N-Proxy-List/master/proxies/mtproto.txt",
            "https://raw.githubusercontent.com/EternityFox/ProxyTelegram/master/verified/proxy_all_verified.txt",
            "https://raw.githubusercontent.com/kort0881/telegram-proxy-collector/main/verified/proxy_all_verified.txt",
            "https://raw.githubusercontent.com/onlymeoneme/mtproto/main/all_proxies_list.txt",
            "https://raw.githubusercontent.com/Airuop/MTProtoCollector/main/proxy/mtproto.json",
            "https://raw.githubusercontent.com/blagobiz/ruka-mtproto-collector/main/verified/proxy_all_verified.txt",
            "https://raw.githubusercontent.com/whoahaow/rjsxrd/main/source/config/tg_proxies.txt",
            "https://raw.githubusercontent.com/Surfboardv2ray/TGProto/main/proxies-tested.txt",
            "https://raw.githubusercontent.com/artproxz/telegram-proxy-redirect/main/proxies.json",
            "https://raw.githubusercontent.com/Therealwh/MTPproxyLIST/main/verified/proxy_eu_verified.txt",
            "https://raw.githubusercontent.com/Therealwh/MTPproxyLIST/main/verified/proxy_ru_verified.txt",
            "https://raw.githubusercontent.com/kort0881/telegram-proxy-collector/main/verified/proxy_asia_verified.txt",
            "https://raw.githubusercontent.com/kort0881/telegram-proxy-collector/main/verified/proxy_eu_verified.txt",
            "https://raw.githubusercontent.com/kort0881/telegram-proxy-collector/main/verified/proxy_us_verified.txt"
        )

        requested.forEach { url ->
            val matches = ProxySourceManager.sources.filter { it.url == url }
            assertEquals("Expected exactly one source for $url", 1, matches.size)
        }
    }
}
