package com.example.data.network

import android.content.Context
import android.net.ConnectivityManager
import android.os.SystemClock
import android.util.Log
import com.example.data.benchmark.ProxyTestProfile
import com.example.model.MtProtoProxy
import com.example.model.TransferScoreCalculator
import com.example.util.PublicNetworkAddressPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class ProxyVerifier(context: Context? = null) {
    private val requireActiveNetwork = context != null
    private val connectivityManager = context
        ?.applicationContext
        ?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    internal fun currentNetworkIdentityOrNull(): UserNetworkIdentity? {
        return UserNetworkSession.capture(connectivityManager, true)?.identity
    }

    data class VerificationResult(
        val isWorking: Boolean,
        val latency: Long,
        val successRate: Float,
        val stability: Float,
        val score: Int,
        val verificationStatus: String,
        val verificationConfidence: Int,
        val protocolLatency: Long,
        val protocolSuccessRate: Float,
        val tcpP90Latency: Long,
        val tcpJitter: Long,
        val protocolP90Latency: Long,
        val protocolJitter: Long,
        val tcpAttempts: Int,
        val tcpSuccesses: Int,
        val protocolAttempts: Int,
        val protocolConfirmations: Int,
        val benchmarkStage: Int,
        val benchmarkRounds: Int,
        val continuityRate: Float,
        val transferScore: Int,
        val networkFingerprint: String,
        val testedNetworkType: String,
        val tcpLatencySamples: List<Long>,
        val protocolLatencySamples: List<Long>
    )

    private data class TcpProbeSample(
        val latency: Long,
        val address: InetAddress
    )

    private data class ProtocolVerificationSummary(
        val attempts: Int,
        val confirmations: Int,
        val medianLatency: Long,
        val p90Latency: Long,
        val jitter: Long,
        val stability: Float,
        val confirmedLatencies: List<Long>
    ) {
        val successRate: Float
            get() = if (attempts > 0) confirmations.toFloat() / attempts.toFloat() else 0f
    }

    suspend fun verifySingle(
        proxy: MtProtoProxy,
        timeoutMs: Int = DEFAULT_TEST_TIMEOUT_MS,
        attempts: Int = DEFAULT_TEST_ATTEMPTS
    ): VerificationResult {
        if (!hasUsableEndpoint(proxy)) {
            return failedResult(profile = ProxyTestProfile.STRICT, inconclusive = true)
        }
        return verifySingleInternal(
            proxy = proxy,
            timeoutMs = timeoutMs,
            profile = ProxyTestProfile.STRICT.copy(tcpAttempts = attempts),
            networkSession = captureUserNetworkSession()
        )
    }

    suspend fun verifySingle(
        proxy: MtProtoProxy,
        timeoutMs: Int,
        profile: ProxyTestProfile,
        checkpoint: suspend () -> Unit = {}
    ): VerificationResult {
        if (!hasUsableEndpoint(proxy)) return failedResult(profile = profile, inconclusive = true)
        return verifySingleInternal(
            proxy = proxy,
            timeoutMs = timeoutMs,
            profile = profile,
            networkSession = captureUserNetworkSession(),
            checkpoint = checkpoint
        )
    }

    suspend fun verifyBatch(
        proxies: List<MtProtoProxy>,
        parallelWorkers: Int = DEFAULT_PARALLEL_WORKERS,
        timeoutMs: Int = DEFAULT_TEST_TIMEOUT_MS,
        profile: ProxyTestProfile = ProxyTestProfile.STRICT,
        checkpoint: suspend () -> Unit = {},
        onProgress: suspend (Int, MtProtoProxy) -> Unit = { _, _ -> }
    ): Int = verifyBatchDetailed(
        proxies = proxies,
        parallelWorkers = parallelWorkers,
        timeoutMs = timeoutMs,
        profile = profile,
        checkpoint = checkpoint,
        onProgress = { completed, proxy, _ -> onProgress(completed, proxy) }
    )

    suspend fun verifyBatchDetailed(
        proxies: List<MtProtoProxy>,
        parallelWorkers: Int = DEFAULT_PARALLEL_WORKERS,
        timeoutMs: Int = DEFAULT_TEST_TIMEOUT_MS,
        profile: ProxyTestProfile = ProxyTestProfile.STRICT,
        checkpoint: suspend () -> Unit = {},
        onProgress: suspend (Int, MtProtoProxy, VerificationResult) -> Unit = { _, _, _ -> }
    ): Int = withContext(Dispatchers.Default) {
        if (proxies.isEmpty()) return@withContext 0
        val networkSession = captureUserNetworkSession()

        // The channel bounds queued work without imposing a proxy-count limit or retaining a
        // second list of completed results in memory.
        val workerCount = parallelWorkers
            .coerceIn(1, MAX_PARALLEL_WORKERS)
            .coerceAtMost(proxies.size)
        val workChannel = Channel<Int>(capacity = workerCount)
        val progressMutex = Mutex()
        var completedCount = 0

        val workers = List(workerCount) {
            launch {
                for (index in workChannel) {
                    checkpoint()
                    val proxy = proxies[index]
                    val result = try {
                        verifySingleInternal(
                            proxy = proxy,
                            timeoutMs = timeoutMs,
                            profile = profile,
                            networkSession = networkSession,
                            checkpoint = checkpoint
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: UserNetworkException) {
                        throw e
                    } catch (e: Exception) {
                        // A malformed endpoint or an unexpected socket error must not abort
                        // the whole benchmark. It is represented as a normal failed result.
                        Log.w(TAG, "Unable to verify ${proxy.server}:${proxy.port}", e)
                        failedResult(
                            profile = profile,
                            networkSession = networkSession,
                            inconclusive = true
                        )
                    }
                    val updatedProxy = result.applyTo(proxy)

                    progressMutex.withLock {
                        completedCount += 1
                        onProgress(completedCount, updatedProxy, result)
                    }
                }
            }
        }

        try {
            for (index in proxies.indices) {
                workChannel.send(index)
            }
        } finally {
            workChannel.close()
        }
        workers.joinAll()
        completedCount
    }

    fun aggregateFinalistResults(
        proxy: MtProtoProxy,
        results: List<VerificationResult>
    ): MtProtoProxy {
        require(results.isNotEmpty()) { "At least one measured round is required." }
        val fingerprints = results.map(VerificationResult::networkFingerprint)
            .filter(String::isNotBlank)
            .distinct()
        if (fingerprints.size > 1) throw UserNetworkChangedException()

        val tcpAttempts = results.sumOf(VerificationResult::tcpAttempts)
        val protocolAttempts = results.sumOf(VerificationResult::protocolAttempts)
        val tcpStatistics = LatencyStatisticsCalculator.summarize(
            successfulLatencies = results.flatMap(VerificationResult::tcpLatencySamples),
            attempts = tcpAttempts
        )
        val protocolStatistics = LatencyStatisticsCalculator.summarize(
            successfulLatencies = results.flatMap(VerificationResult::protocolLatencySamples),
            attempts = protocolAttempts
        )
        val continuousRounds = results.count { result ->
            result.tcpAttempts > 0 &&
                result.tcpSuccesses.toFloat() / result.tcpAttempts.toFloat() >=
                FINALIST_MIN_TCP_RATE &&
                result.protocolAttempts > 0 &&
                result.protocolConfirmations.toFloat() / result.protocolAttempts.toFloat() >=
                FINALIST_MIN_PROTOCOL_RATE
        }
        val roundContinuity = continuousRounds.toFloat() / results.size.toFloat()
        val continuityRate = minOf(
            tcpStatistics.successRate,
            protocolStatistics.successRate,
            roundContinuity
        )
        val stability = minOf(tcpStatistics.stability, protocolStatistics.stability) *
            (FINALIST_STABILITY_BASE + FINALIST_STABILITY_CONTINUITY * roundContinuity)
        val assessment = ProxyVerificationPolicy.assess(
            tcpSuccessRate = tcpStatistics.successRate,
            stability = stability,
            tcpAttempts = tcpAttempts,
            protocolAttempts = protocolAttempts,
            protocolConfirmations = results.sumOf(VerificationResult::protocolConfirmations)
        )
        val rawScore = TransferScoreCalculator.calculate(
            tcpSuccessRate = tcpStatistics.successRate,
            protocolSuccessRate = protocolStatistics.successRate,
            stability = stability,
            continuityRate = continuityRate,
            tcpP90Latency = tcpStatistics.p90Ms,
            tcpJitter = tcpStatistics.jitterMs,
            protocolP90Latency = protocolStatistics.p90Ms,
            protocolJitter = protocolStatistics.jitterMs,
            benchmarkStage = ProxyTestProfile.FINALIST.stage.value,
            benchmarkRounds = results.size
        )
        val transferScore = ProxyVerificationPolicy.adjustScore(rawScore, assessment.level)

        return proxy.copy(
            isWorking = tcpStatistics.successRate > 0f,
            latency = tcpStatistics.medianMs,
            successRate = tcpStatistics.successRate,
            stability = stability.coerceIn(0f, 1f),
            score = transferScore,
            verificationStatus = ProxyVerificationPolicy.storedValue(assessment.level),
            verificationConfidence = assessment.confidence,
            protocolLatency = protocolStatistics.medianMs,
            protocolSuccessRate = protocolStatistics.successRate,
            tcpP90Latency = tcpStatistics.p90Ms,
            tcpJitter = tcpStatistics.jitterMs,
            protocolP90Latency = protocolStatistics.p90Ms,
            protocolJitter = protocolStatistics.jitterMs,
            tcpAttempts = tcpAttempts,
            tcpSuccesses = results.sumOf(VerificationResult::tcpSuccesses),
            protocolAttempts = protocolAttempts,
            protocolConfirmations = results.sumOf(VerificationResult::protocolConfirmations),
            transferScore = transferScore,
            benchmarkStage = ProxyTestProfile.FINALIST.stage.value,
            benchmarkRounds = results.size,
            continuityRate = continuityRate,
            networkFingerprint = fingerprints.singleOrNull().orEmpty(),
            testedNetworkType = results.firstNotNullOfOrNull { result ->
                result.testedNetworkType.takeIf(String::isNotBlank)
            }.orEmpty(),
            telegramDownloadKbps = 0,
            telegramUploadKbps = 0,
            telegramTransferBytes = 0L,
            telegramTransferMeasuredAt = 0L,
            telegramTransferRounds = 0,
            telegramTransferConfidence = 0,
            telegramNetworkUploadBytes = 0L,
            telegramNetworkDownloadBytes = 0L,
            telegramDownloadFloorKbps = 0,
            telegramUploadFloorKbps = 0,
            telegramDownloadPeakKbps = 0,
            telegramUploadPeakKbps = 0,
            lastChecked = System.currentTimeMillis()
        )
    }

    private suspend fun verifySingleInternal(
        proxy: MtProtoProxy,
        timeoutMs: Int,
        profile: ProxyTestProfile,
        networkSession: UserNetworkSession,
        checkpoint: suspend () -> Unit = {}
    ): VerificationResult = withContext(Dispatchers.IO) {
        checkpoint()
        if (!hasUsableEndpoint(proxy)) {
            return@withContext failedResult(
                profile = profile,
                networkSession = networkSession,
                inconclusive = true
            )
        }
        networkSession.ensureCurrent()
        val addresses = resolveEndpointAddresses(proxy.server, networkSession)
        if (addresses.isEmpty()) {
            return@withContext failedResult(
                profile = profile,
                networkSession = networkSession,
                inconclusive = true
            )
        }

        val boundedAttempts = profile.tcpAttempts.coerceIn(1, MAX_TEST_ATTEMPTS)
        val tcpSamples = ArrayList<TcpProbeSample>(boundedAttempts)
        var tcpAttemptCount = 0

        for (index in 0 until boundedAttempts) {
            checkpoint()
            val address = addresses[index % addresses.size]
            val measuredLatency = probeTcpConnect(
                address,
                proxy.port,
                timeoutMs,
                networkSession
            )
            tcpAttemptCount += 1
            networkSession.ensureCurrent()
            measuredLatency?.let {
                tcpSamples += TcpProbeSample(measuredLatency, address)
            }
            if (profile.stopAfterFailedTcpAttempts == tcpAttemptCount && tcpSamples.isEmpty()) {
                return@withContext failedResult(
                    tcpAttempts = tcpAttemptCount,
                    profile = profile,
                    networkSession = networkSession
                )
            }
            if (index < boundedAttempts - 1) delay(PROBE_PAUSE_MS)
        }

        if (tcpSamples.isEmpty()) {
            return@withContext failedResult(
                tcpAttempts = tcpAttemptCount,
                profile = profile,
                networkSession = networkSession
            )
        }

        val tcpStatistics = LatencyStatisticsCalculator.summarize(
            successfulLatencies = tcpSamples.map(TcpProbeSample::latency),
            attempts = tcpAttemptCount
        )
        val protocolAddresses = tcpSamples
            .groupBy { it.address.hostAddress }
            .values
            .sortedWith(
                compareByDescending<List<TcpProbeSample>> { it.size }
                    .thenBy { samples ->
                        LatencyStatisticsCalculator.summarize(
                            samples.map(TcpProbeSample::latency),
                            samples.size
                        ).medianMs
                    }
            )
            .map { it.first().address }

        val protocolSummary = verifyTelegramRoute(
            proxy = proxy,
            addresses = protocolAddresses,
            timeoutMs = timeoutMs.coerceIn(MIN_PROTOCOL_TIMEOUT_MS, MAX_PROTOCOL_TIMEOUT_MS),
            networkSession = networkSession,
            profile = profile,
            checkpoint = checkpoint
        )
        val stability = if (protocolSummary.attempts > 0) {
            minOf(tcpStatistics.stability, protocolSummary.stability)
        } else {
            tcpStatistics.stability * TCP_ONLY_STABILITY_FACTOR
        }
        val assessment = ProxyVerificationPolicy.assess(
            tcpSuccessRate = tcpStatistics.successRate,
            stability = stability,
            tcpAttempts = tcpAttemptCount,
            protocolAttempts = protocolSummary.attempts,
            protocolConfirmations = protocolSummary.confirmations
        )
        val continuityRate = minOf(tcpStatistics.successRate, protocolSummary.successRate)
        val rawScore = TransferScoreCalculator.calculate(
            tcpSuccessRate = tcpStatistics.successRate,
            protocolSuccessRate = protocolSummary.successRate,
            stability = stability,
            continuityRate = continuityRate,
            tcpP90Latency = tcpStatistics.p90Ms,
            tcpJitter = tcpStatistics.jitterMs,
            protocolP90Latency = protocolSummary.p90Latency,
            protocolJitter = protocolSummary.jitter,
            benchmarkStage = profile.stage.value,
            benchmarkRounds = 1
        )
        val score = ProxyVerificationPolicy.adjustScore(rawScore, assessment.level)

        VerificationResult(
            isWorking = true,
            latency = tcpStatistics.medianMs,
            successRate = tcpStatistics.successRate,
            stability = stability,
            score = score,
            verificationStatus = ProxyVerificationPolicy.storedValue(assessment.level),
            verificationConfidence = assessment.confidence,
            protocolLatency = protocolSummary.medianLatency,
            protocolSuccessRate = protocolSummary.successRate,
            tcpP90Latency = tcpStatistics.p90Ms,
            tcpJitter = tcpStatistics.jitterMs,
            protocolP90Latency = protocolSummary.p90Latency,
            protocolJitter = protocolSummary.jitter,
            tcpAttempts = tcpAttemptCount,
            tcpSuccesses = tcpSamples.size,
            protocolAttempts = protocolSummary.attempts,
            protocolConfirmations = protocolSummary.confirmations,
            benchmarkStage = profile.stage.value,
            benchmarkRounds = 1,
            continuityRate = continuityRate,
            transferScore = score,
            networkFingerprint = networkSession.identity.fingerprint,
            testedNetworkType = networkSession.identity.type,
            tcpLatencySamples = tcpSamples.map(TcpProbeSample::latency),
            protocolLatencySamples = protocolSummary.confirmedLatencies
        )
    }

    private suspend fun verifyTelegramRoute(
        proxy: MtProtoProxy,
        addresses: List<InetAddress>,
        timeoutMs: Int,
        networkSession: UserNetworkSession,
        profile: ProxyTestProfile,
        checkpoint: suspend () -> Unit
    ): ProtocolVerificationSummary {
        if (addresses.isEmpty()) {
            return ProtocolVerificationSummary(0, 0, 0, 0, 0, 0f, emptyList())
        }
        val mtProtoProbe = MtProtoConnectionProbe(networkSession::createSocket)
        val confirmedLatencies = ArrayList<Long>(profile.dcIds.size)
        var attempts = 0

        for (index in profile.dcIds.indices) {
            checkpoint()
            val dcId = profile.dcIds[index]
            val result = mtProtoProbe.probe(
                proxy = proxy,
                address = addresses[index % addresses.size],
                timeoutMs = timeoutMs,
                dcId = dcId
            )
            networkSession.ensureCurrent()
            if (result.status == MtProtoProbeStatus.UNSUPPORTED) {
                return ProtocolVerificationSummary(
                    attempts = 0,
                    confirmations = 0,
                    medianLatency = 0,
                    p90Latency = 0,
                    jitter = 0,
                    stability = 0f,
                    confirmedLatencies = emptyList()
                )
            }

            attempts += 1
            if (result.status == MtProtoProbeStatus.CONFIRMED) {
                confirmedLatencies += result.roundTripMs
            }
            if (profile.stopAfterFailedProtocolAttempts == attempts && confirmedLatencies.isEmpty()) {
                break
            }
            if (index < profile.dcIds.lastIndex) delay(PROTOCOL_PROBE_PAUSE_MS)
        }

        val statistics = LatencyStatisticsCalculator.summarize(
            successfulLatencies = confirmedLatencies,
            attempts = attempts
        )
        return ProtocolVerificationSummary(
            attempts = attempts,
            confirmations = confirmedLatencies.size,
            medianLatency = statistics.medianMs,
            p90Latency = statistics.p90Ms,
            jitter = statistics.jitterMs,
            stability = statistics.stability,
            confirmedLatencies = confirmedLatencies.toList()
        )
    }

    private suspend fun probeTcpConnect(
        address: InetAddress,
        port: Int,
        timeoutMs: Int,
        networkSession: UserNetworkSession
    ): Long? {
        return withContext(Dispatchers.IO) {
            suspendCancellableCoroutine { continuation ->
                val startedAt = SystemClock.elapsedRealtimeNanos()
                val socket = try {
                    networkSession.createSocket()
                } catch (error: UserNetworkException) {
                    continuation.resumeWithException(error)
                    return@suspendCancellableCoroutine
                } catch (_: Exception) {
                    continuation.resume(null)
                    return@suspendCancellableCoroutine
                }
                continuation.invokeOnCancellation {
                    runCatching { socket.close() }
                }

                try {
                    socket.use {
                        socket.tcpNoDelay = true
                        socket.connect(
                            InetSocketAddress(address, port),
                            timeoutMs.coerceIn(MIN_TCP_TIMEOUT_MS, MAX_TCP_TIMEOUT_MS)
                        )
                    }
                    if (continuation.isActive) {
                        continuation.resume(
                            nanosToMillis(SystemClock.elapsedRealtimeNanos() - startedAt)
                        )
                    }
                } catch (_: Exception) {
                    if (continuation.isActive) continuation.resume(null)
                }
            }
        }
    }

    private suspend fun resolveEndpointAddresses(
        server: String,
        networkSession: UserNetworkSession
    ): List<InetAddress> {
        return withContext(Dispatchers.IO) {
            try {
                kotlinx.coroutines.runInterruptible { networkSession.resolve(server) }
                    .asSequence()
                    .filter(PublicNetworkAddressPolicy::isPublic)
                    .distinctBy(InetAddress::getHostAddress)
                    .sortedBy { if (it is Inet4Address) 0 else 1 }
                    .toList()
            } catch (error: UserNetworkException) {
                throw error
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                emptyList()
            }
        }
    }

    private fun captureUserNetworkSession(): UserNetworkSession {
        return UserNetworkSession.capture(connectivityManager, requireActiveNetwork)
            ?: throw UserNetworkUnavailableException()
    }

    private fun hasUsableEndpoint(proxy: MtProtoProxy): Boolean {
        if (proxy.server.isBlank() || proxy.port !in 1..65535) return false
        return MtProtoProxy.normalizeSecret(proxy.secret) != null
    }

    private fun nanosToMillis(nanos: Long): Long {
        return (nanos / 1_000_000L).coerceAtLeast(1L)
    }

    private fun failedResult(
        tcpAttempts: Int = 0,
        profile: ProxyTestProfile,
        networkSession: UserNetworkSession? = null,
        inconclusive: Boolean = false
    ): VerificationResult {
        return VerificationResult(
            isWorking = false,
            latency = 0,
            successRate = 0f,
            stability = 0f,
            score = 0,
            verificationStatus = if (inconclusive) {
                MtProtoProxy.VERIFICATION_INCONCLUSIVE
            } else {
                MtProtoProxy.VERIFICATION_OFFLINE
            },
            verificationConfidence = 0,
            protocolLatency = 0,
            protocolSuccessRate = 0f,
            tcpP90Latency = 0,
            tcpJitter = 0,
            protocolP90Latency = 0,
            protocolJitter = 0,
            tcpAttempts = tcpAttempts,
            tcpSuccesses = 0,
            protocolAttempts = 0,
            protocolConfirmations = 0,
            benchmarkStage = profile.stage.value,
            benchmarkRounds = 1,
            continuityRate = 0f,
            transferScore = 0,
            networkFingerprint = networkSession?.identity?.fingerprint.orEmpty(),
            testedNetworkType = networkSession?.identity?.type.orEmpty(),
            tcpLatencySamples = emptyList(),
            protocolLatencySamples = emptyList()
        )
    }

    fun VerificationResult.applyTo(proxy: MtProtoProxy): MtProtoProxy {
        return proxy.copy(
            isWorking = isWorking,
            latency = latency,
            successRate = successRate,
            stability = stability,
            score = transferScore,
            verificationStatus = verificationStatus,
            verificationConfidence = verificationConfidence,
            protocolLatency = protocolLatency,
            protocolSuccessRate = protocolSuccessRate,
            tcpP90Latency = tcpP90Latency,
            tcpJitter = tcpJitter,
            protocolP90Latency = protocolP90Latency,
            protocolJitter = protocolJitter,
            tcpAttempts = tcpAttempts,
            tcpSuccesses = tcpSuccesses,
            protocolAttempts = protocolAttempts,
            protocolConfirmations = protocolConfirmations,
            transferScore = transferScore,
            benchmarkStage = benchmarkStage,
            benchmarkRounds = benchmarkRounds,
            continuityRate = continuityRate,
            networkFingerprint = networkFingerprint,
            testedNetworkType = testedNetworkType,
            telegramDownloadKbps = 0,
            telegramUploadKbps = 0,
            telegramTransferBytes = 0,
            telegramTransferMeasuredAt = 0,
            telegramTransferRounds = 0,
            telegramTransferConfidence = 0,
            telegramNetworkUploadBytes = 0L,
            telegramNetworkDownloadBytes = 0L,
            telegramDownloadFloorKbps = 0,
            telegramUploadFloorKbps = 0,
            telegramDownloadPeakKbps = 0,
            telegramUploadPeakKbps = 0,
            lastChecked = System.currentTimeMillis()
        )
    }

    companion object {
        private const val TAG = "ProxyVerifier"
        const val STRICT_TCP_ATTEMPTS = 10
        const val STRICT_PROTOCOL_ATTEMPTS = 8
        private const val DEFAULT_TEST_ATTEMPTS = STRICT_TCP_ATTEMPTS
        private const val MAX_TEST_ATTEMPTS = 12
        private const val DEFAULT_TEST_TIMEOUT_MS = 3_000
        private const val MIN_TCP_TIMEOUT_MS = 750
        private const val MAX_TCP_TIMEOUT_MS = 10_000
        private const val MIN_PROTOCOL_TIMEOUT_MS = 1_000
        private const val MAX_PROTOCOL_TIMEOUT_MS = 5_000
        private const val DEFAULT_PARALLEL_WORKERS = 12
        private const val MAX_PARALLEL_WORKERS = 16
        private const val PROBE_PAUSE_MS = 120L
        private const val PROTOCOL_PROBE_PAUSE_MS = 120L
        private const val TCP_ONLY_STABILITY_FACTOR = 0.40f
        private const val FINALIST_MIN_TCP_RATE = 0.80f
        private const val FINALIST_MIN_PROTOCOL_RATE = 0.75f
        private const val FINALIST_STABILITY_BASE = 0.65f
        private const val FINALIST_STABILITY_CONTINUITY = 0.35f
    }
}
