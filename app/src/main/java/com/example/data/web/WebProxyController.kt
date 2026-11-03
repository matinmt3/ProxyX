package com.example.data.web

import android.content.Context
import android.net.ConnectivityManager
import android.os.SystemClock
import com.example.data.database.WebProxyDao
import com.example.data.network.MtProtoConnectionProbe
import com.example.data.network.MtProtoProbeStatus
import com.example.data.network.NetworkUsagePolicy
import com.example.data.network.UserNetworkSession
import com.example.data.network.SourceHttpPolicy
import com.example.data.repository.ProxyRepository
import com.example.data.repository.SettingsRepository
import com.example.data.telegram.TelegramAuthorizationStep
import com.example.data.telegram.TdlibTelegramTransferEngine
import com.example.model.WebProxyNode
import com.example.util.PublicNetworkAddressPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import java.net.InetAddress
import java.net.Socket
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap

data class WebProxyTestState(val url: String? = null, val stage: Int = 0, val message: String = "", val running: Boolean = false)

class WebProxyController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val dao: WebProxyDao,
    private val settings: SettingsRepository,
    private val repository: ProxyRepository,
    private val telegram: TdlibTelegramTransferEngine,
    private val canStart: () -> Boolean
) {
    val nodes = dao.observeAll()
    private val mutableState = MutableStateFlow(WebProxyTestState())
    val state = mutableState.asStateFlow()
    private var job: Job? = null
    private val initialized = scope.async(Dispatchers.IO) { dao.markInterrupted() }
    private val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    fun importLinks(raw: String) {
        if (raw.length > 2_000_000) { mutableState.value = state.value.copy(message = "IMPORT_TOO_LARGE"); return }
        scope.launch(Dispatchers.IO) {
            try {
                val parsed = WebProxyNode.parseList(raw)
                if (parsed.isEmpty()) {
                    mutableState.value = state.value.copy(message = "INVALID_WEB_LINK")
                    return@launch
                }
                val inserted = dao.insert(parsed).count { it != -1L }
                mutableState.value = state.value.copy(message = "IMPORTED:$inserted")
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { mutableState.value = state.value.copy(message = "STORAGE_FAILED") }
        }
    }

    fun delete(node: WebProxyNode) {
        if (state.value.running) return
        scope.launch {
            try { dao.delete(node.url) }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) { mutableState.value = state.value.copy(message = "STORAGE_FAILED") }
        }
    }

    fun cancel() { job?.cancel() }

    fun refreshSources() {
        if (job?.isActive == true || !canStart() || !repository.beginForegroundBenchmark()) {
            mutableState.value = state.value.copy(message = "BUSY"); return
        }
        mutableState.value = WebProxyTestState(message = "FETCHING", running = true)
        job = scope.launch(Dispatchers.IO) {
            try {
                NetworkUsagePolicy(context).requireAllowed(settings.unmeteredOnly.first())
                val network = UserNetworkSession.capture(manager, true) ?: error("NO_NETWORK")
                val resolvedHosts = ConcurrentHashMap<String, List<InetAddress>>()
                val client = OkHttpClient.Builder().socketFactory(network.socketFactory())
                    .proxy(java.net.Proxy.NO_PROXY)
                    .dns(object : Dns {
                        override fun lookup(hostname: String): List<InetAddress> = resolvedHosts[hostname]
                            ?: throw UnknownHostException("WEB source hostname has not been resolved")
                    }).followRedirects(false).followSslRedirects(false)
                    .callTimeout(8, TimeUnit.SECONDS).build()
                val collected = mutableListOf<WebProxyNode>()
                try {
                    for (feed in WebProxySources.feeds) {
                        network.ensureCurrent()
                        try {
                            val content = withTimeout(10_000) {
                                val host = feed.toHttpUrl().host
                                if (!resolvedHosts.containsKey(host)) {
                                    resolvedHosts[host] = SourceHttpPolicy.publicAddresses(
                                        WebDnsLookup.resolve { network.resolve(host) }.toList())
                                }
                                fetch(client, feed)
                            }
                            network.ensureCurrent()
                            collected += WebProxyNode.parseList(content).map { it.copy(source = feed) }
                        } catch (_: TimeoutCancellationException) { network.ensureCurrent() }
                        catch (error: CancellationException) { throw error }
                        catch (_: Exception) { network.ensureCurrent() }
                    }
                    network.ensureCurrent()
                    val fallback = collected.isEmpty()
                    val nodes = if (fallback) WebProxySources.bootstrap() else collected.distinctBy { it.url }
                    // A failed fetch must not erase working entries. Seed only an empty public list.
                    if (!fallback) dao.replacePublic(nodes)
                    else if (dao.observeAll().first().none { it.source != "MANUAL" }) dao.insert(nodes)
                    mutableState.value = WebProxyTestState(message = if (fallback) "PUBLIC_UNVERIFIED" else "IMPORTED:${nodes.size}")
                } finally {
                    client.dispatcher.cancelAll(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown()
                }
            } catch (error: CancellationException) {
                mutableState.value = WebProxyTestState(message = "CANCELLED"); throw error
            } catch (error: Exception) {
                val failure = classifyWebProxyFailure(error)
                mutableState.value = WebProxyTestState(message = if (failure.status == "INCONCLUSIVE") failure.code else "SOURCES_FAILED")
            }
            finally { repository.endForegroundBenchmark() }
        }
    }

    private suspend fun fetch(client: OkHttpClient, url: String): String = withTimeout(10_000) {
      suspendCancellableCoroutine { continuation ->
        val call = client.newCall(Request.Builder().url(url).build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
            override fun onResponse(call: Call, response: Response) {
                val result = runCatching { response.use {
                    check(it.isSuccessful) { "SOURCE_HTTP_FAILED" }
                    val body = it.body ?: error("SOURCE_EMPTY")
                    body.source().request(2_000_001)
                    check(body.source().buffer.size <= 2_000_000) { "SOURCE_TOO_LARGE" }
                    body.string()
                } }
                if (continuation.isActive) result.fold({ continuation.resume(it) }, { continuation.resumeWithException(it) })
            }
        })
      }
    }

    fun test(node: WebProxyNode, transfer: Boolean) {
        if (job?.isActive == true || !canStart()) {
            mutableState.value = state.value.copy(message = "BUSY"); return
        }
        if (transfer && telegram.state.value.step != TelegramAuthorizationStep.READY) {
            mutableState.value = state.value.copy(message = "SIGN_IN_REQUIRED"); return
        }
        if (!repository.beginForegroundBenchmark()) {
            mutableState.value = state.value.copy(message = "BUSY"); return
        }
        mutableState.value = WebProxyTestState(node.url, 1, "DNS", true)
        job = scope.launch(Dispatchers.IO) {
            var result = node.copy(testedAt = System.currentTimeMillis(), status = "FAILED", stage = 0,
                latencyMs = 0, downloadKbps = 0, uploadKbps = 0, transferBytes = 0, transferRounds = 0,
                networkFingerprint = "", networkType = "")
            try {
                initialized.await()
                dao.update(result.copy(status = "TESTING"))
                NetworkUsagePolicy(context).requireAllowed(settings.unmeteredOnly.first())
                val network = UserNetworkSession.capture(manager, true) ?: error("NO_NETWORK")
                result = result.copy(networkFingerprint = network.identity.fingerprint, networkType = network.identity.type)
                withTimeout(15_000) {
                    val addresses = WebDnsLookup.resolve { network.resolve(node.host) }
                    check(addresses.isNotEmpty() && addresses.all(PublicNetworkAddressPolicy::isPublic)) { "NON_PUBLIC_HOST" }
                }
                result = result.copy(stage = 1)
                mutableState.value = WebProxyTestState(node.url, 2, "BRIDGE", true)
                check(withContext(Dispatchers.Main) { WebProxyTransport.isSupported() }) { "WEBVIEW_UPDATE_REQUIRED" }
                val ready = CompletableDeferred<Unit>()
                val started = SystemClock.elapsedRealtime()
                val port = withContext(Dispatchers.Main) {
                    WebProxyTransport.startConnectionCheck(context, node.address, node.secret) { ready.complete(Unit) }
                }
                check(port > 0) { "WEBVIEW_UNAVAILABLE" }
                withTimeout(20_000) { ready.await() }
                network.ensureCurrent()
                result = result.copy(stage = 2)
                mutableState.value = WebProxyTestState(node.url, 3, "MTPROTO", true)
                val local = node.asLocalMtProto(port)
                val probe = MtProtoConnectionProbe { Socket() }
                val latencies = mutableListOf<Long>()
                for (dc in listOf(2, 4, 2)) {
                    network.ensureCurrent()
                    val verified = probe.probe(local, InetAddress.getByName("127.0.0.1"), 12_000, dc)
                    check(verified.status == MtProtoProbeStatus.CONFIRMED) { "TELEGRAM_ROUTE_FAILED" }
                    latencies += verified.roundTripMs
                    delay(400)
                }
                network.ensureCurrent()
                result = result.copy(stage = 3, status = "PROTOCOL_VERIFIED", latencyMs = latencies.sorted()[1].coerceAtLeast(1))
                if (transfer) {
                    mutableState.value = WebProxyTestState(node.url, 4, "TRANSFER", true)
                    val measurement = withTimeout(300_000) { telegram.measureAdaptiveTransfer(local, 2 * 1024 * 1024) }
                    network.ensureCurrent()
                    check(measurement.networkFingerprint == network.identity.fingerprint && measurement.rounds >= 3 &&
                        measurement.downloadKbps > 0 && measurement.uploadKbps > 0 && measurement.sampleBytes > 0) { "TRANSFER_INCOMPLETE" }
                    result = result.copy(stage = 4, status = "TRANSFER_VERIFIED", downloadKbps = measurement.downloadKbps,
                        uploadKbps = measurement.uploadKbps, transferBytes = measurement.sampleBytes, transferRounds = measurement.rounds)
                }
                check(SystemClock.elapsedRealtime() >= started)
                mutableState.value = WebProxyTestState(node.url, result.stage, result.status)
            } catch (error: CancellationException) {
                result = result.copy(status = if (error is TimeoutCancellationException) "TIMEOUT" else "CANCELLED",
                    downloadKbps = 0, uploadKbps = 0, transferBytes = 0, transferRounds = 0)
                mutableState.value = WebProxyTestState(node.url, result.stage, result.status)
                if (error !is TimeoutCancellationException) throw error
            } catch (error: Exception) {
                val failure = classifyWebProxyFailure(error)
                result = result.copy(status = failure.status, downloadKbps = 0, uploadKbps = 0, transferBytes = 0, transferRounds = 0)
                mutableState.value = WebProxyTestState(node.url, result.stage, failure.code)
            } finally {
                withContext(NonCancellable) {
                    try {
                        withContext(Dispatchers.Main) { WebProxyTransport.stopConnectionCheck() }
                        try { dao.update(result) }
                        catch (_: Exception) { mutableState.value = WebProxyTestState(node.url, result.stage, "STORAGE_FAILED") }
                    } finally { repository.endForegroundBenchmark() }
                }
            }
        }
    }
}
