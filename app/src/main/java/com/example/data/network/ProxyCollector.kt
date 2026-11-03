package com.example.data.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import android.util.Log
import com.example.BuildConfig
import com.example.model.MtProtoProxy
import com.example.data.database.ProxySourceHealthDao
import com.example.model.ProxySourceHealth
import com.example.model.SourceQualityPolicy
import com.example.model.SourceFetchRecord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.runInterruptible
import okhttp3.OkHttpClient
import okhttp3.Dns
import okhttp3.Request
import java.io.IOException
import java.net.URI
import java.net.InetAddress
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.TimeUnit
import java.security.MessageDigest

class ProxyCollector(
    context: Context? = null,
    private val sourceHealthDao: ProxySourceHealthDao? = null
) {
    data class CollectedSourceBatch(
        val source: ProxySource,
        val sourceProxies: List<MtProtoProxy>,
        val globallyUniqueProxies: List<MtProtoProxy>
    )
    private val requireActiveNetwork = context != null
    private val connectivityManager = context
        ?.applicationContext
        ?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    private val contentParser = ProxyContentParser()
    private val sourceCatalogClient = SignedSourceCatalogClient(context?.applicationContext)
    private val preferGithubMirror = AtomicBoolean(false)
    private val keepMirrorFirstForNetwork = AtomicBoolean(false)

    suspend fun collectAll(
        onBatch: suspend (CollectedSourceBatch) -> Unit = {}
    ): Int = withContext(Dispatchers.IO) {
        ProxySourceManager.beginRefresh()
        val now = System.currentTimeMillis()
        val healthByUrl = sourceHealthDao?.getAll()?.associateBy(ProxySourceHealth::sourceUrl)
            .orEmpty()
        val networkSession = UserNetworkSession.capture(
            connectivityManager,
            requireActiveNetwork
        ) ?: throw UserNetworkUnavailableException()
        val client = createClient(networkSession)
        try {
            ProxySourceManager.applyCatalog(runInterruptible { sourceCatalogClient.load(client, networkSession) })
            val enabledSources = ProxySourceManager.getEnabledSources()
                .filter { source -> healthByUrl[source.url]?.quarantineUntil?.let { it <= now } != false }
                .sortedWith(
                    compareByDescending<ProxySource> { healthByUrl[it.url]?.qualityScore ?: 50 }
                        .thenByDescending { healthByUrl[it.url]?.lastSuccessAt ?: 0L }
                        .thenBy(ProxySource::name)
                )
            val mirrorFirstForNetwork = shouldPreferMirrorForActiveNetwork()
            val fetchSemaphore = Semaphore(
                if (mirrorFirstForNetwork) CELLULAR_SOURCE_REQUESTS else MAX_CONCURRENT_SOURCE_REQUESTS
            )
            val resultMutex = Mutex()
            val seenProxyKeys = HashSet<String>()
            var totalUniqueProxies = 0
            keepMirrorFirstForNetwork.set(mirrorFirstForNetwork)
            preferGithubMirror.set(mirrorFirstForNetwork)
            enabledSources.map { source ->
                async {
                    val startedAt = SystemClock.elapsedRealtime()
                    val previousHealth = healthByUrl[source.url]
                    val proxies = try {
                        val sourceProxies = fetchSemaphore.withPermit {
                            fetchAndParse(source, client, networkSession)
                        }
                        sourceProxies
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: UserNetworkException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "Unable to collect ${source.name}: ${e.message}")
                        val durationMs = SystemClock.elapsedRealtime() - startedAt
                        val failedHealth = failedHealth(
                            source = source,
                            previous = previousHealth,
                            attemptedAt = now,
                            durationMs = durationMs,
                            error = e.message ?: "Unknown network failure"
                        )
                        sourceHealthDao?.upsert(failedHealth)
                        sourceHealthDao?.insertFetchRecord(
                            SourceFetchRecord(
                                sourceUrl = source.url,
                                fetchedAt = now,
                                success = false,
                                rawProxyCount = 0,
                                uniqueProxyCount = 0,
                                duplicateRate = 1f,
                                durationMs = durationMs,
                                contentSignature = "",
                                endpointDiversity = 0,
                                networkType = networkSession.identity.type,
                                errorCode = classifySourceError(e)
                            )
                        )
                        ProxySourceManager.updateResult(failedHealth.toFetchResult())
                        return@async
                    }

                    resultMutex.withLock {
                        val uniqueBatch = proxies.filter { proxy ->
                            seenProxyKeys.add(proxyKey(proxy))
                        }
                        val durationMs = SystemClock.elapsedRealtime() - startedAt
                        val signature = contentSignature(proxies)
                        val diversity = endpointDiversity(proxies)
                        val succeededHealth = successfulHealth(
                            source = source,
                            previous = previousHealth,
                            attemptedAt = now,
                            rawCount = proxies.size,
                            uniqueCount = uniqueBatch.size,
                            durationMs = durationMs,
                            contentSignature = signature,
                            endpointDiversity = diversity
                        )
                        sourceHealthDao?.upsert(succeededHealth)
                        sourceHealthDao?.insertFetchRecord(
                            SourceFetchRecord(
                                sourceUrl = source.url,
                                fetchedAt = now,
                                success = uniqueBatch.isNotEmpty(),
                                rawProxyCount = proxies.size,
                                uniqueProxyCount = uniqueBatch.size,
                                duplicateRate = succeededHealth.duplicateRate,
                                durationMs = durationMs,
                                contentSignature = signature,
                                endpointDiversity = diversity,
                                networkType = networkSession.identity.type,
                                errorCode = succeededHealth.lastError
                            )
                        )
                        ProxySourceManager.updateResult(succeededHealth.toFetchResult())
                        if (proxies.isNotEmpty()) {
                            onBatch(
                                CollectedSourceBatch(
                                    source = source,
                                    sourceProxies = proxies,
                                    globallyUniqueProxies = uniqueBatch
                                )
                            )
                        }
                        if (uniqueBatch.isNotEmpty()) {
                            totalUniqueProxies += uniqueBatch.size
                        }
                    }
                }
            }.awaitAll()

            Log.d(TAG, "Collected $totalUniqueProxies unique proxies across all sources.")
            healthByUrl.values
                .filter { it.quarantineUntil > now }
                .forEach { health -> ProxySourceManager.updateResult(health.toFetchResult()) }
            sourceHealthDao?.refreshMirrorGroupSizes()
            sourceHealthDao?.deleteFetchHistoryOlderThan(now - SOURCE_HISTORY_RETENTION_MS)
            totalUniqueProxies
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                client.dispatcher.cancelAll()
                client.connectionPool.evictAll()
                client.dispatcher.executorService.shutdown()
            }
        }
    }

    private fun successfulHealth(
        source: ProxySource,
        previous: ProxySourceHealth?,
        attemptedAt: Long,
        rawCount: Int,
        uniqueCount: Int,
        durationMs: Long,
        contentSignature: String,
        endpointDiversity: Int
    ): ProxySourceHealth {
        val totalFetches = (previous?.totalFetches ?: 0) + 1
        val previousAverage = previous?.averageDurationMs ?: 0L
        val averageDuration = if (previous == null || previous.totalFetches <= 0) {
            durationMs
        } else {
            ((previousAverage * previous.totalFetches) + durationMs) / totalFetches
        }
        val duplicateRate = if (rawCount > 0) {
            1f - uniqueCount.toFloat().div(rawCount.toFloat())
        } else {
            1f
        }
        val emptyYield = uniqueCount == 0
        return ProxySourceHealth(
            sourceUrl = source.url,
            sourceName = source.name,
            lastAttemptAt = attemptedAt,
            lastSuccessAt = attemptedAt,
            totalFetches = totalFetches,
            successfulFetches = (previous?.successfulFetches ?: 0) + 1,
            consecutiveFailures = if (emptyYield) (previous?.consecutiveFailures ?: 0) + 1 else 0,
            rawProxyCount = rawCount,
            uniqueProxyCount = uniqueCount,
            verifiedProxyCount = previous?.verifiedProxyCount ?: 0,
            duplicateRate = duplicateRate.coerceIn(0f, 1f),
            verifiedRate = previous?.verifiedRate ?: 0f,
            averageDurationMs = averageDuration,
            quarantineUntil = if (emptyYield) {
                attemptedAt + SourceQualityPolicy.quarantineDurationMs(
                    consecutiveFailures = (previous?.consecutiveFailures ?: 0) + 1,
                    producedNoUniqueProxies = true
                )
            } else {
                0L
            },
            lastError = if (emptyYield) "No unique MTProto endpoints" else "",
            contentSignature = contentSignature,
            mirrorGroupSize = previous?.mirrorGroupSize ?: 1,
            endpointDiversity = endpointDiversity
        )
    }

    private fun failedHealth(
        source: ProxySource,
        previous: ProxySourceHealth?,
        attemptedAt: Long,
        durationMs: Long,
        error: String
    ): ProxySourceHealth {
        val failures = (previous?.consecutiveFailures ?: 0) + 1
        val totalFetches = (previous?.totalFetches ?: 0) + 1
        val averageDuration = if (previous == null || previous.totalFetches <= 0) {
            durationMs
        } else {
            ((previous.averageDurationMs * previous.totalFetches) + durationMs) / totalFetches
        }
        val quarantineDuration = SourceQualityPolicy.quarantineDurationMs(failures, false)
        return ProxySourceHealth(
            sourceUrl = source.url,
            sourceName = source.name,
            lastAttemptAt = attemptedAt,
            lastSuccessAt = previous?.lastSuccessAt ?: 0L,
            totalFetches = totalFetches,
            successfulFetches = previous?.successfulFetches ?: 0,
            consecutiveFailures = failures,
            rawProxyCount = 0,
            uniqueProxyCount = 0,
            verifiedProxyCount = previous?.verifiedProxyCount ?: 0,
            duplicateRate = previous?.duplicateRate ?: 0f,
            verifiedRate = previous?.verifiedRate ?: 0f,
            averageDurationMs = averageDuration,
            quarantineUntil = if (quarantineDuration > 0L) attemptedAt + quarantineDuration else 0L,
            lastError = error.take(MAX_STORED_ERROR_CHARS),
            contentSignature = previous?.contentSignature.orEmpty(),
            mirrorGroupSize = previous?.mirrorGroupSize ?: 1,
            endpointDiversity = previous?.endpointDiversity ?: 0
        )
    }

    private fun ProxySourceHealth.toFetchResult(): SourceFetchResult {
        return SourceFetchResult(
            name = sourceName,
            isSuccess = lastSuccessAt == lastAttemptAt && uniqueProxyCount > 0,
            count = rawProxyCount,
            uniqueCount = uniqueProxyCount,
            duplicateRate = duplicateRate,
            verifiedRate = verifiedRate,
            lastSuccessAt = lastSuccessAt,
            qualityScore = qualityScore,
            quarantineUntil = quarantineUntil,
            errorMessage = lastError.ifBlank { null },
            durationMs = averageDurationMs
        )
    }

    private suspend fun fetchAndParse(
        source: ProxySource,
        client: OkHttpClient,
        networkSession: UserNetworkSession
    ): List<MtProtoProxy> {
        val endpoints = GithubSourceEndpointResolver.candidates(
            sourceUrl = source.url,
            preferMirror = preferGithubMirror.get()
        )
        var lastFailure: IOException? = null

        endpoints.forEach { endpoint ->
            try {
                val proxies = fetchEndpoint(source, endpoint, client, networkSession)
                if (endpoints.size > 1) {
                    val usedMirror = GithubSourceEndpointResolver.isMirror(endpoint)
                    if (usedMirror || !keepMirrorFirstForNetwork.get()) {
                        preferGithubMirror.set(usedMirror)
                    }
                }
                return proxies
            } catch (e: CancellationException) {
                throw e
            } catch (e: UserNetworkException) {
                throw e
            } catch (e: IOException) {
                lastFailure = e
                Log.d(TAG, "${source.name} failed via ${hostOf(endpoint)}: ${e.message}")
            }
        }

        throw IOException(
            "Direct and fallback endpoints failed: ${lastFailure?.message ?: "unknown error"}",
            lastFailure
        )
    }

    private suspend fun fetchEndpoint(
        source: ProxySource,
        endpoint: String,
        client: OkHttpClient,
        networkSession: UserNetworkSession
    ): List<MtProtoProxy> {
        networkSession.ensureCurrent()
        val request = Request.Builder()
            .url(freshEndpoint(endpoint))
            .header("Accept", "text/plain, application/json;q=0.9, */*;q=0.5")
            .header("User-Agent", USER_AGENT)
            .header("Cache-Control", "no-cache")
            .build()
            val proxies = contentParser.parse(SourceHttpPolicy.read(client.newCall(request), MAX_SOURCE_BYTES))
            networkSession.ensureCurrent()
            if (proxies.isEmpty()) {
                throw IOException("No valid MTProto endpoints found")
            }
            Log.d(
                TAG,
                "Fetched ${proxies.size} proxies from ${source.name} via ${hostOf(endpoint)}"
            )
            return proxies
    }

    private fun createClient(networkSession: UserNetworkSession): OkHttpClient {
        return OkHttpClient.Builder()
            .proxy(java.net.Proxy.NO_PROXY)
            .followSslRedirects(false)
            .addInterceptor { chain ->
                SourceHttpPolicy.validate(chain.request().url)
                chain.proceed(chain.request())
            }
            .addNetworkInterceptor { chain ->
                SourceHttpPolicy.validate(chain.request().url)
                networkSession.ensureCurrent()
                chain.proceed(chain.request())
            }
            .socketFactory(networkSession.socketFactory())
            .dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> {
                    return SourceHttpPolicy.publicAddresses(networkSession.resolve(hostname).toList())
                }
            })
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(9, TimeUnit.SECONDS)
            .callTimeout(12, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    private fun freshEndpoint(endpoint: String): String {
        if (!GithubSourceEndpointResolver.isMirror(endpoint)) return endpoint
        val separator = if ('?' in endpoint) '&' else '?'
        return "$endpoint${separator}px=${System.currentTimeMillis() / MIRROR_CACHE_BUCKET_MS}"
    }

    private fun hostOf(url: String): String {
        return runCatching { URI(url).host }.getOrNull() ?: "unknown host"
    }

    private fun shouldPreferMirrorForActiveNetwork(): Boolean {
        val manager = connectivityManager ?: return false
        val activeNetwork = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(activeNetwork) ?: return false
        val usesFixedTransport =
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        val isMetered = !capabilities.hasCapability(
            NetworkCapabilities.NET_CAPABILITY_NOT_METERED
        )
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
            (!usesFixedTransport && isMetered)
    }

    private fun proxyKey(proxy: MtProtoProxy): String {
        return "${proxy.server.lowercase(Locale.ROOT)}:${proxy.port}:" +
            proxy.secret.lowercase(Locale.ROOT)
    }

    private fun contentSignature(proxies: List<MtProtoProxy>): String {
        if (proxies.isEmpty()) return ""
        val digest = MessageDigest.getInstance("SHA-256")
        proxies.asSequence()
            .map(::proxyKey)
            .distinct()
            .sorted()
            .forEach { key ->
                digest.update(key.toByteArray(Charsets.UTF_8))
                digest.update(0)
            }
        return digest.digest().take(16).joinToString("") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }
    }

    private fun endpointDiversity(proxies: List<MtProtoProxy>): Int {
        return proxies.asSequence().map { proxy ->
            val server = proxy.server.lowercase(Locale.ROOT).trim('[', ']')
            when {
                server.count { it == '.' } == 3 && server.split('.').all {
                    it.toIntOrNull() in 0..255
                } -> server.split('.').take(3).joinToString(".")
                ':' in server -> server.split(':').filter(String::isNotBlank).take(3).joinToString(":")
                else -> server.split('.').takeLast(2).joinToString(".")
            }
        }.filter(String::isNotBlank).distinct().count()
    }

    private fun classifySourceError(error: Exception): String = when {
        error is UserNetworkException -> "NETWORK_CHANGED"
        error.message?.contains("HTTP error code: 404", ignoreCase = true) == true -> "HTTP_404"
        error.message?.contains("HTTP error code:", ignoreCase = true) == true -> "HTTP_ERROR"
        error.message?.contains("No valid MTProto", ignoreCase = true) == true -> "EMPTY"
        error.message?.contains("too large", ignoreCase = true) == true -> "TOO_LARGE"
        else -> "NETWORK_ERROR"
    }

    private companion object {
        const val TAG = "ProxyCollector"
        const val MAX_CONCURRENT_SOURCE_REQUESTS = 8
        const val CELLULAR_SOURCE_REQUESTS = 4
        const val MAX_SOURCE_BYTES = 8L * 1024L * 1024L
        const val MIRROR_CACHE_BUCKET_MS = 5L * 60L * 1_000L
        val USER_AGENT = "ProxyX-Android/${BuildConfig.VERSION_NAME}"
        const val MAX_STORED_ERROR_CHARS = 240
        const val SOURCE_HISTORY_RETENTION_MS = 30L * 24L * 60L * 60L * 1_000L
    }
}
