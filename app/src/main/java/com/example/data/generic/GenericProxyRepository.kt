package com.example.data.generic

import android.content.Context
import android.net.ConnectivityManager
import com.example.data.network.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

data class GenericRefreshResult(val downloaded: Int, val cached: Int, val failed: Int)

class GenericProxyRepository(context: Context, val dao: GenericProxyDao) {
    private val manager = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    private val operation = Mutex()

    suspend fun refresh(progress: (Int) -> Unit): GenericRefreshResult = operation.withLock {
      withContext(Dispatchers.IO) {
        val network = UserNetworkSession.capture(manager, true) ?: throw UserNetworkUnavailableException()
        val client = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).socketFactory(network.socketFactory())
            .dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> =
                    SourceHttpPolicy.publicAddresses(network.resolve(hostname).toList())
            }).followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
            .connectTimeout(6, TimeUnit.SECONDS).callTimeout(10, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                SourceHttpPolicy.validate(chain.request().url)
                network.ensureCurrent()
                chain.proceed(chain.request())
            }.build()
        val completed = AtomicInteger()
        val semaphore = Semaphore(2)
        try {
            val results = GenericSources.feeds.map { feed -> async {
                semaphore.withPermit {
                    val now = System.currentTimeMillis()
                    val old = dao.health(feed.id)
                    if (old != null && now >= old.succeededAt && now - old.succeededAt < feed.minimumIntervalMs) {
                        progress(completed.incrementAndGet())
                        return@withPermit "CACHED"
                    }
                    try {
                        val body = SourceHttpPolicy.read(client.newCall(Request.Builder().url(feed.url)
                            .header("User-Agent", "ProxyX-Android/${com.example.BuildConfig.VERSION_NAME}")
                            .build()), 2_000_000)
                        val rows = GenericProxyParser.parse(body, feed, now)
                        check(rows.isNotEmpty()) { "EMPTY" }
                        network.ensureCurrent()
                        dao.received(feed, rows, now)
                        "DOWNLOADED"
                    } catch (e: CancellationException) { throw e }
                    catch (e: UserNetworkException) { throw e }
                    catch (e: Exception) {
                        network.ensureCurrent()
                        dao.putHealth(GenericSourceHealth(feed.id, now, old?.succeededAt ?: 0,
                            old?.accepted ?: 0, if (e.message == "EMPTY") "EMPTY" else "FETCH_FAILED"))
                        "FAILED"
                    } finally { progress(completed.incrementAndGet()) }
                }
            } }.awaitAll()
            GenericRefreshResult(results.count { it == "DOWNLOADED" }, results.count { it == "CACHED" },
                results.count { it == "FAILED" })
        } finally {
            client.dispatcher.cancelAll()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
      }
    }

    suspend fun test(node: GenericProxy): GenericProbeResult = operation.withLock {
        val network = UserNetworkSession.capture(manager, true) ?: throw UserNetworkUnavailableException()
        try {
            val result = GenericProxyProbe().test(node, network)
            val saved = dao.measured(node.id, node.fetchedAt, result.status, System.currentTimeMillis(),
                result.connectionMs, result.requestMs, result.errorCode, result.network)
            if (saved == 0) result.copy(status = "INCONCLUSIVE", errorCode = "STALE_RESULT",
                connectionMs = 0, requestMs = 0) else result
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                dao.measured(node.id, node.fetchedAt, "CANCELLED", System.currentTimeMillis(), 0, 0, "CANCELLED", network.identity.fingerprint)
            }
            throw e
        }
    }

    suspend fun clear() = operation.withLock { dao.clear() }
}
