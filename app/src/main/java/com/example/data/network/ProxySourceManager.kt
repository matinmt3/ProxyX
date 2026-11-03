package com.example.data.network

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class ProxySource(
    val name: String,
    val url: String,
    val description: String,
    val isEnabled: Boolean = true
)

data class SourceFetchResult(
    val name: String,
    val isSuccess: Boolean,
    val count: Int,
    val uniqueCount: Int = count,
    val duplicateRate: Float = 0f,
    val verifiedRate: Float = 0f,
    val lastSuccessAt: Long = 0L,
    val qualityScore: Int = 0,
    val quarantineUntil: Long = 0L,
    val errorMessage: String? = null,
    val durationMs: Long = 0L
)

enum class SourceCatalogOrigin {
    BUNDLED,
    SIGNED_CACHE,
    SIGNED_REMOTE
}

data class SourceCatalogState(
    val origin: SourceCatalogOrigin = SourceCatalogOrigin.BUNDLED,
    val sourceCount: Int = 0,
    val issuedAt: Long = 0L,
    val expiresAt: Long = 0L,
    val lastCheckedAt: Long = 0L,
    val error: String = ""
)

object ProxySourceManager {
    private val bundledSources = listOf(
        ProxySource(
            name = "Telegram-FZ Proxy Feed",
            url = "https://raw.githubusercontent.com/Telegram-FZ-LLC/Telegram-Proxy/main/proxies.txt",
            description = "Frequently verified and refreshed MTProto proxy feed"
        ),
        ProxySource(
            name = "Kira MTProto Feed",
            url = "https://raw.githubusercontent.com/Kira00011/MTProto/main/all_proxies.txt",
            description = "Hourly refreshed MTProto proxy collection"
        ),
        ProxySource(
            name = "TGOnline Proxy Feed",
            url = "https://raw.githubusercontent.com/blog1703/tgonline/main/proxies.txt",
            description = "Small, frequently updated Telegram proxy feed"
        ),
        ProxySource(
            name = "Therealwh Verified EU MTProto",
            url = "https://raw.githubusercontent.com/Therealwh/MTPproxyLIST/main/verified/proxy_eu_verified.txt",
            description = "Verified European MTProto proxy subset"
        ),
        ProxySource(
            name = "3yed-61 MTP Collector",
            url = "https://raw.githubusercontent.com/3yed-61/MTP-Collector/main/proxy/mtproto.json",
            description = "Structured JSON feed of collected MTProto proxies"
        ),
        ProxySource(
            name = "SoliSpirit MTProto",
            url = "https://raw.githubusercontent.com/SoliSpirit/mtproto/master/all_proxies.txt",
            description = "Auto-updated and verified MTProto proxies"
        ),
        ProxySource(
            name = "ALIILAPRO MTProtoProxy",
            url = "https://raw.githubusercontent.com/ALIILAPRO/MTProtoProxy/main/mtproto.txt",
            description = "Daily high-speed MTProto proxy list"
        ),
        ProxySource(
            name = "V2RAYCONFIGSPOOL Telegram Proxy 1",
            isEnabled = false, // Repository returned HTTP 404 in the 2026-10-26 audit.
            url = "https://raw.githubusercontent.com/V2RAYCONFIGSPOOL/TELEGRAM_PROXY_SUB/refs/heads/main/telegram_proxy_no1.txt",
            description = "Continuously uploaded Telegram proxy feed"
        ),
        ProxySource(
            name = "V2RAYCONFIGSPOOL Telegram Proxy 2",
            isEnabled = false, // Repository returned HTTP 404 in the 2026-10-26 audit.
            url = "https://raw.githubusercontent.com/V2RAYCONFIGSPOOL/TELEGRAM_PROXY_SUB/refs/heads/main/telegram_proxy_no2.txt",
            description = "Continuously uploaded Telegram proxy feed"
        ),
        ProxySource(
            name = "V2RAYCONFIGSPOOL Telegram Proxy 3",
            isEnabled = false, // Repository returned HTTP 404 in the 2026-10-26 audit.
            url = "https://raw.githubusercontent.com/V2RAYCONFIGSPOOL/TELEGRAM_PROXY_SUB/refs/heads/main/telegram_proxy_no3.txt",
            description = "Continuously uploaded Telegram proxy feed"
        ),
        ProxySource(
            name = "V2RAYCONFIGSPOOL Telegram Proxy 4",
            isEnabled = false, // Repository returned HTTP 404 in the 2026-10-26 audit.
            url = "https://raw.githubusercontent.com/V2RAYCONFIGSPOOL/TELEGRAM_PROXY_SUB/refs/heads/main/telegram_proxy_no4.txt",
            description = "Continuously uploaded Telegram proxy feed"
        ),
        ProxySource(
            name = "V2RAYCONFIGSPOOL Telegram Proxy 5",
            isEnabled = false, // Repository returned HTTP 404 in the 2026-10-26 audit.
            url = "https://raw.githubusercontent.com/V2RAYCONFIGSPOOL/TELEGRAM_PROXY_SUB/refs/heads/main/telegram_proxy_no5.txt",
            description = "Continuously uploaded Telegram proxy feed"
        ),
        ProxySource(
            name = "V2RAYCONFIGSPOOL Telegram Proxy 6",
            isEnabled = false, // Repository returned HTTP 404 in the 2026-10-26 audit.
            url = "https://raw.githubusercontent.com/V2RAYCONFIGSPOOL/TELEGRAM_PROXY_SUB/refs/heads/main/telegram_proxy_no6.txt",
            description = "Continuously uploaded Telegram proxy feed"
        ),
        ProxySource(
            name = "V2RAYCONFIGSPOOL Telegram Proxy 7",
            isEnabled = false, // Repository returned HTTP 404 in the 2026-10-26 audit.
            url = "https://raw.githubusercontent.com/V2RAYCONFIGSPOOL/TELEGRAM_PROXY_SUB/refs/heads/main/telegram_proxy_no7.txt",
            description = "Continuously uploaded Telegram proxy feed"
        ),
        ProxySource(
            name = "V2RAYCONFIGSPOOL Telegram Proxy 8",
            isEnabled = false, // Repository returned HTTP 404 in the 2026-10-26 audit.
            url = "https://raw.githubusercontent.com/V2RAYCONFIGSPOOL/TELEGRAM_PROXY_SUB/refs/heads/main/telegram_proxy_no8.txt",
            description = "Continuously uploaded Telegram proxy feed"
        ),
        ProxySource(
            name = "V2RAYCONFIGSPOOL Telegram Proxy 9",
            isEnabled = false, // Repository returned HTTP 404 in the 2026-10-26 audit.
            url = "https://raw.githubusercontent.com/V2RAYCONFIGSPOOL/TELEGRAM_PROXY_SUB/refs/heads/main/telegram_proxy_no9.txt",
            description = "Continuously uploaded Telegram proxy feed"
        ),
        ProxySource(
            name = "V2RAYCONFIGSPOOL Telegram Proxy 10",
            isEnabled = false, // Repository returned HTTP 404 in the 2026-10-26 audit.
            url = "https://raw.githubusercontent.com/V2RAYCONFIGSPOOL/TELEGRAM_PROXY_SUB/refs/heads/main/telegram_proxy_no10.txt",
            description = "Continuously uploaded Telegram proxy feed"
        ),
        ProxySource(
            name = "Argh94 Telegram Proxy Scraper",
            url = "https://raw.githubusercontent.com/Argh94/telegram-proxy-scraper/main/proxy.txt",
            description = "MTProto proxy scraper output updated by GitHub Actions"
        ),
        ProxySource(
            name = "weltimistar777 MTProxy",
            url = "https://raw.githubusercontent.com/weltimistar777-crypto/MTProxy/main/proxy.txt",
            description = "Free MTProto proxies ready for use"
        ),
        ProxySource(
            name = "Argh94 Proxy List MTProto",
            url = "https://raw.githubusercontent.com/Argh94/Proxy-List/main/MTProto.txt",
            description = "Hourly refreshed categorized MTProto proxy list"
        ),
        ProxySource(
            name = "iwh3n TG Proxy",
            isEnabled = false, // Repository returned HTTP 404 in the 2026-10-26 audit.
            url = "https://raw.githubusercontent.com/iwh3n/tg-proxy/refs/heads/main/proxys/All_Proxys.txt",
            description = "Large continuously refreshed Telegram proxy feed"
        ),
        ProxySource(
            name = "shablin MTProto Valid",
            url = "https://raw.githubusercontent.com/shablin/mtproto-proxy/refs/heads/main/data/valid_proxy.json",
            description = "JSON feed containing currently valid MTProto links"
        ),
        ProxySource(
            name = "Grim1313 MTProto",
            url = "https://raw.githubusercontent.com/Grim1313/mtproto-for-telegram/refs/heads/master/all_proxies.txt",
            description = "Aggregated MTProto links with frequent refreshes"
        ),
        ProxySource(
            name = "kubiknubika TG Proxies",
            url = "https://raw.githubusercontent.com/kubiknubika/my-tg-proxies/refs/heads/main/data/proxies.json",
            description = "Updated JSON Telegram proxy feed"
        ),
        ProxySource(
            name = "kort0881 Telegram Proxy All",
            url = "https://raw.githubusercontent.com/kort0881/telegram-proxy-collector/refs/heads/main/proxy_all.txt",
            description = "Collector output with global MTProto proxies"
        ),
        ProxySource(
            name = "MustafaBaqer Vestra MTProto",
            url = "https://raw.githubusercontent.com/MustafaBaqer/VestraNet-Nodes/refs/heads/main/protocols/mtproto.txt",
            description = "Fresh VestraNet MTProto protocol nodes"
        ),
        ProxySource(
            name = "Therealwh Verified MTProto",
            url = "https://raw.githubusercontent.com/Therealwh/MTPproxyLIST/refs/heads/main/verified/proxy_all_verified.txt",
            description = "Verified MTProto proxy list"
        ),
        ProxySource(
            name = "Therealwh Verified TME",
            url = "https://raw.githubusercontent.com/Therealwh/MTPproxyLIST/refs/heads/main/verified/proxy_all_tme_verified.txt",
            description = "Verified t.me proxy links"
        ),
        ProxySource(
            name = "McDaived ProxyDaiv",
            url = "https://raw.githubusercontent.com/McDaived/ProxyDaiv/refs/heads/main/public/proxies.json",
            description = "JSON proxy database with Telegram links"
        ),
        ProxySource(
            name = "kort0881 Telegram Proxy RU",
            url = "https://raw.githubusercontent.com/kort0881/telegram-proxy-collector/refs/heads/main/proxy_ru.txt",
            description = "Regional Telegram proxy collector feed"
        ),
        ProxySource(
            name = "Surfboardv2ray TGProto",
            url = "https://raw.githubusercontent.com/Surfboardv2ray/TGProto/refs/heads/main/proxies.txt",
            description = "Fresh Telegram MTProto proxy output"
        ),
        ProxySource(
            name = "LoneKingCode Free Proxy DB",
            url = "https://raw.githubusercontent.com/LoneKingCode/free-proxy-db/refs/heads/main/proxies/all.txt",
            description = "General proxy database containing MTProto links"
        ),
        ProxySource(
            name = "Aviamasters Verified MTProto",
            url = "https://raw.githubusercontent.com/aviamastersgh/mtproto-free-russia/main/verified_proxies.txt",
            description = "Verified MTProto feed refreshed every 2 hours"
        ),
        ProxySource(
            name = "Darkvibez MTProto Auto",
            url = "https://raw.githubusercontent.com/darkvibez456/mtproto-proxy-auto/main/proxies.txt",
            description = "Automatically refreshed MTProto mirror updated about every 12 hours"
        ),
        ProxySource(
            name = "Ded Maxim Verified MTProto",
            url = "https://raw.githubusercontent.com/ded-maxim-1337/Telegram-Proxy-Base/gh-pages/proxy_all_verified.json",
            description = "Verified MTProto JSON feed refreshed about every 15 minutes"
        ),
        ProxySource(
            name = "MhdiTaheri ProxyCollector",
            url = "https://raw.githubusercontent.com/MhdiTaheri/ProxyCollector/main/proxy.txt",
            description = "Large community-maintained MTProto proxy collection"
        ),
        ProxySource(
            name = "MTProtoNexus Pages",
            url = "https://itsyebekhe.github.io/MTProtoNexus/extracted_proxies.json",
            description = "Independent GitHub Pages MTProto JSON feed"
        ),
        ProxySource(
            name = "Ch4120N MTProto",
            url = "https://raw.githubusercontent.com/Ch4120N/Ch4120N-Proxy-List/master/proxies/mtproto.txt",
            description = "Continuously aggregated MTProto proxy links"
        ),
        ProxySource(
            name = "EternityFox Verified MTProto",
            url = "https://raw.githubusercontent.com/EternityFox/ProxyTelegram/master/verified/proxy_all_verified.txt",
            description = "Verified Telegram MTProto proxy output"
        ),
        ProxySource(
            name = "kort0881 Verified MTProto All",
            url = "https://raw.githubusercontent.com/kort0881/telegram-proxy-collector/main/verified/proxy_all_verified.txt",
            description = "Large verified MTProto feed from the Telegram proxy collector"
        ),
        ProxySource(
            name = "onlymeoneme MTProto",
            url = "https://raw.githubusercontent.com/onlymeoneme/mtproto/main/all_proxies_list.txt",
            description = "Large automatically refreshed MTProto list"
        ),
        ProxySource(
            name = "Airuop MTProto Collector",
            url = "https://raw.githubusercontent.com/Airuop/MTProtoCollector/main/proxy/mtproto.json",
            description = "Structured MTProto collector JSON feed"
        ),
        ProxySource(
            name = "RUKA Verified MTProto",
            url = "https://raw.githubusercontent.com/blagobiz/ruka-mtproto-collector/main/verified/proxy_all_verified.txt",
            description = "Small independently verified MTProto pool"
        ),
        ProxySource(
            name = "whoahaow Telegram Proxies",
            url = "https://raw.githubusercontent.com/whoahaow/rjsxrd/main/source/config/tg_proxies.txt",
            description = "Telegram MTProto links from an independent configuration feed"
        ),
        ProxySource(
            name = "Surfboard TGProto Tested",
            url = "https://raw.githubusercontent.com/Surfboardv2ray/TGProto/main/proxies-tested.txt",
            description = "Tested subset of the TGProto feed"
        ),
        ProxySource(
            name = "artproxz Verified MTProto",
            url = "https://raw.githubusercontent.com/artproxz/telegram-proxy-redirect/main/proxies.json",
            description = "Machine-readable verified MTProto feed"
        ),
        ProxySource(
            name = "Therealwh Verified RU MTProto",
            url = "https://raw.githubusercontent.com/Therealwh/MTPproxyLIST/main/verified/proxy_ru_verified.txt",
            description = "Verified Russian MTProto proxy subset"
        ),
        ProxySource(
            name = "kort0881 Verified Asia MTProto",
            url = "https://raw.githubusercontent.com/kort0881/telegram-proxy-collector/main/verified/proxy_asia_verified.txt",
            description = "Verified Asian MTProto proxy subset"
        ),
        ProxySource(
            name = "kort0881 Verified EU MTProto",
            url = "https://raw.githubusercontent.com/kort0881/telegram-proxy-collector/main/verified/proxy_eu_verified.txt",
            description = "Verified European MTProto proxy subset"
        ),
        ProxySource(
            name = "kort0881 Verified US MTProto",
            url = "https://raw.githubusercontent.com/kort0881/telegram-proxy-collector/main/verified/proxy_us_verified.txt",
            description = "Verified United States MTProto proxy subset"
        )
    )

    private val _activeSources = MutableStateFlow(bundledSources)
    val activeSources = _activeSources.asStateFlow()
    val sources: List<ProxySource>
        get() = _activeSources.value

    private val _catalogState = MutableStateFlow(
        SourceCatalogState(sourceCount = bundledSources.size)
    )
    val catalogState = _catalogState.asStateFlow()

    private val _fetchResults = MutableStateFlow<Map<String, SourceFetchResult>>(emptyMap())
    val fetchResults = _fetchResults.asStateFlow()

    fun getEnabledSources(): List<ProxySource> {
        return _activeSources.value.filter { it.isEnabled }
    }

    internal fun applyCatalog(result: CatalogLoadResult) {
        val catalog = result.catalog
        if (catalog == null) {
            _activeSources.value = bundledSources
            _catalogState.value = SourceCatalogState(
                origin = SourceCatalogOrigin.BUNDLED,
                sourceCount = bundledSources.size,
                lastCheckedAt = System.currentTimeMillis(),
                error = result.error.take(240)
            )
            return
        }
        val selected = if (catalog.replaceBundled) {
            catalog.sources
        } else {
            (catalog.sources + bundledSources).distinctBy(ProxySource::url)
        }.distinctBy { it.name.lowercase(java.util.Locale.ROOT) }.take(MAX_ACTIVE_SOURCES)
        _activeSources.value = selected
        _catalogState.value = SourceCatalogState(
            origin = result.origin,
            sourceCount = selected.size,
            issuedAt = catalog.issuedAt,
            expiresAt = catalog.expiresAt,
            lastCheckedAt = System.currentTimeMillis(),
            error = result.error.take(240)
        )
    }

    fun beginRefresh() {
        _fetchResults.value = emptyMap()
    }

    fun updateResult(result: SourceFetchResult) {
        _fetchResults.update { current -> current + (result.name to result) }
    }

    private const val MAX_ACTIVE_SOURCES = 100
}
