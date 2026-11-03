package com.example.data.network

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import com.example.model.MtProtoProxy
import com.example.util.PublicNetworkAddressPolicy
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.Inet4Address
import java.net.InetAddress
import java.util.LinkedHashMap
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

internal data class ResolvedProxyCountry(
    val name: String,
    val code: String
)

@JsonClass(generateAdapter = true)
internal data class CountryBatchRequest(
    val ips: List<String>
)

@JsonClass(generateAdapter = true)
internal data class CountryBatchResponse(
    val results: Map<String, CountryLookup> = emptyMap()
)

@JsonClass(generateAdapter = true)
internal data class CountryLookup(
    val location: CountryLocation? = null
)

@JsonClass(generateAdapter = true)
internal data class CountryLocation(
    val country: String? = null,
    @param:Json(name = "country_code") val countryCode: String? = null
)

@JsonClass(generateAdapter = true)
internal data class HackMyIpBatchResponse(
    val success: Boolean = false,
    val data: HackMyIpBatchData? = null
)

@JsonClass(generateAdapter = true)
internal data class HackMyIpBatchData(
    val results: List<HackMyIpCountryLookup> = emptyList()
)

@JsonClass(generateAdapter = true)
internal data class HackMyIpCountryLookup(
    val ip: String? = null,
    val country: String? = null
)

internal object ProxyCountryPayloadParser {
    private val moshi = Moshi.Builder().build()
    private val responseAdapter = moshi.adapter(CountryBatchResponse::class.java)
    private val hackMyIpAdapter = moshi.adapter(HackMyIpBatchResponse::class.java)

    fun parseIpApiIo(payload: String): Map<String, ResolvedProxyCountry> {
        val response = runCatching { responseAdapter.fromJson(payload) }.getOrNull()
            ?: return emptyMap()
        return response.results.mapNotNull { (ip, lookup) ->
            val country = resolvedCountry(
                codeValue = lookup.location?.countryCode,
                nameValue = lookup.location?.country
            )
                ?: return@mapNotNull null
            ip to country
        }.toMap()
    }

    fun parseHackMyIp(payload: String): Map<String, ResolvedProxyCountry> {
        val response = runCatching { hackMyIpAdapter.fromJson(payload) }.getOrNull()
            ?.takeIf { it.success }
            ?: return emptyMap()
        return response.data
            ?.results
            .orEmpty()
            .mapNotNull { lookup ->
                val ip = lookup.ip?.trim()?.takeIf { it.isNotEmpty() }
                    ?: return@mapNotNull null
                val country = resolvedCountry(
                    codeValue = lookup.country,
                    nameValue = null
                ) ?: return@mapNotNull null
                ip to country
            }
            .toMap()
    }

    private fun resolvedCountry(
        codeValue: String?,
        nameValue: String?
    ): ResolvedProxyCountry? {
        val code = codeValue
            ?.trim()
            ?.uppercase(Locale.ROOT)
            ?.takeIf { it.matches(COUNTRY_CODE_REGEX) }
            ?: return null
        val name = nameValue
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: Locale.Builder()
                .setRegion(code)
                .build()
                .getDisplayCountry(Locale.ENGLISH)
                .takeIf { it.isNotBlank() }
            ?: return null
        return ResolvedProxyCountry(name = name, code = code)
    }

    private val COUNTRY_CODE_REGEX = Regex("^[A-Z]{2}$")
}

/**
 * Enriches proxy endpoints with country-level GeoIP data. Only endpoint addresses are sent in
 * HTTPS batches; proxy secrets and the user's own address are never included.
 */
class ProxyCountryResolver(context: Context? = null) {
    private val connectivityManager = context
        ?.applicationContext
        ?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    private val resolverMutex = Mutex()
    private val offlineDatabase = context?.applicationContext
        ?.let(::OfflineProxyCountryDatabase)
    private val countryCache = object : LinkedHashMap<String, ResolvedProxyCountry>(
        COUNTRY_CACHE_SIZE,
        0.75f,
        true
    ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, ResolvedProxyCountry>?
        ): Boolean = size > COUNTRY_CACHE_SIZE
    }
    private val requestAdapter by lazy {
        Moshi.Builder()
            .build()
            .adapter(CountryBatchRequest::class.java)
    }
    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .callTimeout(14, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    suspend fun enrich(proxies: List<MtProtoProxy>): List<MtProtoProxy> {
        if (proxies.isEmpty()) return proxies
        return resolverMutex.withLock {
            val candidates = proxies

            val ipByServer = resolveEndpointIps(candidates.mapTo(linkedSetOf()) { it.server })
            val endpointIps = ipByServer.values
                .distinct()
                .filterNot(countryCache::containsKey)
            offlineDatabase
                ?.resolve(endpointIps)
                .orEmpty()
                .forEach { (ip, country) ->
                    countryCache[ip] = country
                }
            val missingIps = endpointIps.filterNot(countryCache::containsKey)
            fetchCountries(missingIps).forEach { (ip, country) ->
                countryCache[ip] = country
            }

            var changedCount = 0
            val enriched = proxies.map { proxy ->
                val country = ipByServer[proxy.server]?.let(countryCache::get)
                if (country == null ||
                    (proxy.country == country.name && proxy.countryCode == country.code)
                ) {
                    proxy
                } else {
                    changedCount += 1
                    proxy.copy(country = country.name, countryCode = country.code)
                }
            }
            Log.d(
                TAG,
                "Country enrichment resolved $changedCount of ${candidates.size} candidates."
            )
            enriched
        }
    }

    private suspend fun resolveEndpointIps(
        servers: Set<String>
    ): Map<String, String> {
        val result = ConcurrentHashMap<String, String>()
        val domains = ArrayList<String>()
        servers.forEach { server ->
            val literal = parsePublicLiteral(server)
            if (literal != null) {
                result[server] = literal
            } else {
                domains += server
            }
        }
        if (domains.isEmpty()) return result

        val completed = withTimeoutOrNull(DNS_PHASE_TIMEOUT_MS) {
            coroutineScope {
                val channel = Channel<String>(capacity = DNS_QUEUE_CAPACITY)
                val workers = List(minOf(DNS_WORKERS, domains.size)) {
                    launch(Dispatchers.IO) {
                        for (server in channel) {
                            val address = withTimeoutOrNull(DNS_LOOKUP_TIMEOUT_MS) {
                                runInterruptible { resolveDomain(server) }
                            }
                            address?.let { result[server] = it }
                        }
                    }
                }
                try {
                    domains.forEach { channel.send(it) }
                } finally {
                    channel.close()
                }
                workers.joinAll()
            }
            true
        } ?: false
        if (!completed) {
            Log.w(
                TAG,
                "DNS country phase reached its ${DNS_PHASE_TIMEOUT_MS}ms budget; " +
                    "keeping ${result.size} resolved endpoints."
            )
        }
        return result
    }

    private fun resolveDomain(server: String): String? {
        val addresses = runCatching {
            connectivityManager
                ?.activeNetwork
                ?.getAllByName(server)
                ?: InetAddress.getAllByName(server)
        }.getOrNull() ?: return null
        // IPv4 has the most consistent support across free bulk GeoIP providers. Keep IPv6 as
        // the fallback when an endpoint does not publish an A record.
        return (
            addresses.firstOrNull { it is Inet4Address && PublicNetworkAddressPolicy.isPublic(it) }
                ?: addresses.firstOrNull(PublicNetworkAddressPolicy::isPublic)
            )
            ?.hostAddress
            ?.substringBefore('%')
    }

    private fun parsePublicLiteral(server: String): String? {
        if (!isAddressLiteral(server)) return null
        return runCatching { InetAddress.getByName(server) }
            .getOrNull()
            ?.takeIf(PublicNetworkAddressPolicy::isPublic)
            ?.hostAddress
            ?.substringBefore('%')
    }

    private fun isAddressLiteral(server: String): Boolean {
        return ':' in server || IPV4_REGEX.matches(server)
    }

    private suspend fun fetchCountries(
        ips: List<String>
    ): Map<String, ResolvedProxyCountry> {
        if (ips.isEmpty()) return emptyMap()
        val primary = fetchInBatches(
            ips = ips,
            batchSize = HACK_MY_IP_BATCH_SIZE,
            pauseMs = HACK_MY_IP_BATCH_PAUSE_MS,
            providerName = "HackMyIP",
            lookup = ::lookupHackMyIpBatch
        )
        val unresolved = ips.filterNot(primary::containsKey)
        if (unresolved.isEmpty()) return primary

        val fallback = fetchInBatches(
            ips = unresolved,
            batchSize = IP_API_IO_BATCH_SIZE,
            pauseMs = IP_API_IO_BATCH_PAUSE_MS,
            providerName = "ip-api.io",
            lookup = ::lookupIpApiIoBatch
        )
        return primary + fallback
    }

    private suspend fun fetchInBatches(
        ips: List<String>,
        batchSize: Int,
        pauseMs: Long,
        providerName: String,
        lookup: suspend (List<String>) -> Map<String, ResolvedProxyCountry>
    ): Map<String, ResolvedProxyCountry> {
        val resolved = LinkedHashMap<String, ResolvedProxyCountry>()
        var consecutiveFailures = 0
        for (batch in ips.chunked(batchSize)) {
            val batchResult = try {
                lookup(batch)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "$providerName GeoIP batch lookup failed.", e)
                emptyMap()
            }
            if (batchResult.isEmpty()) {
                consecutiveFailures += 1
                if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                    Log.w(TAG, "Stopping $providerName after repeated empty responses.")
                    break
                }
            } else {
                consecutiveFailures = 0
                resolved.putAll(batchResult)
            }
            delay(pauseMs)
        }
        return resolved
    }

    private suspend fun lookupHackMyIpBatch(
        ips: List<String>
    ): Map<String, ResolvedProxyCountry> {
        val payload = requestAdapter.toJson(CountryBatchRequest(ips))
        val request = Request.Builder()
            .url(HACK_MY_IP_BATCH_URL)
            .header("Accept", "application/json")
            .header("Cache-Control", "no-store")
            .header("User-Agent", USER_AGENT)
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        val responsePayload = execute(request) ?: return emptyMap()
        return ProxyCountryPayloadParser.parseHackMyIp(responsePayload)
    }

    private suspend fun lookupIpApiIoBatch(
        ips: List<String>
    ): Map<String, ResolvedProxyCountry> {
        val payload = requestAdapter.toJson(CountryBatchRequest(ips))
        val request = Request.Builder()
            .url(IP_API_IO_BATCH_URL)
            .header("Accept", "application/json")
            .header("Cache-Control", "no-store")
            .header("User-Agent", USER_AGENT)
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        val responsePayload = execute(request) ?: return emptyMap()
        return ProxyCountryPayloadParser.parseIpApiIo(responsePayload)
    }

    private suspend fun execute(request: Request): String? = withContext(Dispatchers.IO) {
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            try {
                val payload = call.execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.w(TAG, "GeoIP endpoint returned HTTP ${response.code}.")
                        null
                    } else {
                        response.body?.string()?.takeIf { it.length <= MAX_RESPONSE_CHARS }
                    }
                }
                if (continuation.isActive) continuation.resume(payload)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (continuation.isActive) continuation.resume(null)
            }
        }
    }

    private companion object {
        const val TAG = "ProxyCountryResolver"
        const val HACK_MY_IP_BATCH_URL = "https://hackmyip.com/api/bulk"
        const val IP_API_IO_BATCH_URL = "https://ip-api.io/api/v1/ip/batch"
        const val USER_AGENT = "ProxyX-Android/2"
        const val HACK_MY_IP_BATCH_SIZE = 50
        const val IP_API_IO_BATCH_SIZE = 100
        const val HACK_MY_IP_BATCH_PAUSE_MS = 1_050L
        const val IP_API_IO_BATCH_PAUSE_MS = 120L
        const val MAX_CONSECUTIVE_FAILURES = 5
        const val MAX_RESPONSE_CHARS = 2_000_000
        const val COUNTRY_CACHE_SIZE = 10_000
        const val DNS_WORKERS = 16
        const val DNS_QUEUE_CAPACITY = 64
        const val DNS_LOOKUP_TIMEOUT_MS = 2_000L
        const val DNS_PHASE_TIMEOUT_MS = 10_000L
        val IPV4_REGEX = Regex(
            "^(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)" +
                "(?:\\.(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)){3}$"
        )
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
