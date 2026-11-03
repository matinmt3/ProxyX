package com.example.data.repository

import android.util.Log
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import com.example.data.benchmark.BenchmarkStage
import com.example.data.benchmark.BenchmarkAccuracyMode
import com.example.data.benchmark.BenchmarkTestPlans
import com.example.data.benchmark.BenchmarkFailureReason
import com.example.data.benchmark.ProxyTestProfile
import com.example.data.benchmark.ThreeStageBenchmarkPolicy
import com.example.data.benchmark.ThreeStageBenchmarkResult
import com.example.data.benchmark.ThreeStageProgress
import com.example.data.database.ProxyDao
import com.example.data.database.ProxySourceHealthDao
import com.example.data.network.ProxyCollector
import com.example.data.network.ProxyCountryResolver
import com.example.data.network.ProxyVerifier
import com.example.data.network.UserNetworkException
import com.example.data.network.NetworkSpeedMeter
import com.example.data.network.NetworkSpeedResult
import com.example.model.MtProtoProxy
import com.example.model.BenchmarkRunRecord
import com.example.model.FavoriteProxy
import com.example.model.ProxySourceMembership
import com.example.model.ProxyBenchmarkCounts
import com.example.model.ProxyTestHistory
import com.example.model.PrivateProxyNode
import com.example.model.SourceWindowStats
import com.example.model.NetworkSpeedBaseline
import com.example.model.TransferRankingEvidence
import com.example.data.telegram.TelegramTransferResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

data class ProxyRefreshResult(
    val proxyCount: Int,
    val cacheReplaced: Boolean,
    val completed: Boolean = true
)

data class CurrentNetworkInfo(
    val fingerprint: String,
    val type: String,
    val isMetered: Boolean
)

class ProxyRepository(
    private val proxyDao: ProxyDao,
    private val collector: ProxyCollector,
    private val verifier: ProxyVerifier,
    private val countryResolver: ProxyCountryResolver,
    private val networkSpeedMeter: NetworkSpeedMeter,
    private val sourceHealthDao: ProxySourceHealthDao
) {
    val totalProxyCount: Flow<Int> = proxyDao.observeProxyCount()
    val favoriteCount: Flow<Int> = proxyDao.observeFavoriteCount()
    val benchmarkCounts: Flow<ProxyBenchmarkCounts> = proxyDao.observeBenchmarkCounts()
    fun benchmarkCounts(since: Long): Flow<ProxyBenchmarkCounts> = proxyDao.observeBenchmarkCounts(since)
    fun benchmarkPreview(networkFingerprint: String, since: Long = 0L): Flow<List<MtProtoProxy>> =
        proxyDao.observeBenchmarkPreview(BENCHMARK_PREVIEW_LIMIT, networkFingerprint, since)
    fun benchmarkChart(since: Long): Flow<List<MtProtoProxy>> = proxyDao.observeBenchmarkChart(
        limit = BENCHMARK_CHART_LIMIT,
        maxLatency = MAX_CHART_LATENCY_MS,
        since = since
    )
    val sourceHealth = sourceHealthDao.observeAll()
    val sourceStats7Days: Flow<List<SourceWindowStats>> = sourceHealthDao.observeWindowStats(
        System.currentTimeMillis() - SOURCE_STATS_7_DAY_WINDOW_MS
    )
    val sourceStats30Days: Flow<List<SourceWindowStats>> = sourceHealthDao.observeWindowStats(
        System.currentTimeMillis() - SOURCE_STATS_30_DAY_WINDOW_MS
    )
    val activeFavorites: Flow<List<FavoriteProxy>> = proxyDao.observeActiveFavorites()
    val privateNodes: Flow<List<PrivateProxyNode>> = proxyDao.observePrivateNodes()

    fun smartRecommendationFor(networkFingerprint: String): Flow<MtProtoProxy?> {
        return proxyDao.observeSmartRecommendation(
            networkFingerprint = networkFingerprint,
            freshAfter = System.currentTimeMillis() - SMART_RECOMMENDATION_FRESHNESS_MS
        )
    }

    private val dataOperationMutex = Mutex()
    private val foregroundBenchmarkActive = AtomicBoolean(false)
    val isForegroundOperationActive: Boolean get() = foregroundBenchmarkActive.get()
    private val _refreshInProgress = MutableStateFlow(false)
    val refreshInProgress: StateFlow<Boolean> = _refreshInProgress.asStateFlow()
    private val _refreshRevision = MutableStateFlow(0L)
    val refreshRevision: StateFlow<Long> = _refreshRevision.asStateFlow()
    private val _listOrderRevision = MutableStateFlow(0L)
    val listOrderRevision: StateFlow<Long> = _listOrderRevision.asStateFlow()

    fun pagedProxies(
        query: String,
        status: String,
        sort: String,
        currentNetworkFingerprint: String
    ): Flow<PagingData<MtProtoProxy>> {
        return Pager(
            config = PagingConfig(
                pageSize = PROXY_PAGE_SIZE,
                prefetchDistance = PROXY_PREFETCH_DISTANCE,
                initialLoadSize = PROXY_INITIAL_LOAD_SIZE,
                enablePlaceholders = false
            ),
            pagingSourceFactory = {
                proxyDao.pagingSource(query.trim(), status, sort, currentNetworkFingerprint)
            }
        ).flow
    }

    fun filteredProxyCount(query: String, status: String): Flow<Int> {
        return proxyDao.observeFilteredProxyCount(query.trim(), status)
    }

    fun observeProxy(url: String): Flow<MtProtoProxy?> = proxyDao.observeProxyByUrl(url)

    fun observeComparisonProxy(url: String): Flow<MtProtoProxy?> = combine(
        proxyDao.observeProxyByUrl(url), proxyDao.observePrivateNode(url)
    ) { publicProxy, privateNode ->
        val publicMeasuredAt = publicProxy?.let {
            if (TransferRankingEvidence.isUsableMeasurement(it)) {
                maxOf(it.lastChecked, it.telegramTransferMeasuredAt)
            } else it.lastChecked
        } ?: 0L
        if (privateNode != null && (publicProxy == null || privateNode.lastChecked > publicMeasuredAt)) {
            privateNode.asProxy()
        } else publicProxy
    }.distinctUntilChanged()

    fun observeTestHistory(url: String, limit: Int = TEST_HISTORY_PER_PROXY_LIMIT) =
        proxyDao.observeTestHistory(url, limit.coerceIn(1, TEST_HISTORY_PER_PROXY_LIMIT))

    fun networkShortlist(fingerprint: String): Flow<List<ProxyTestHistory>> =
        proxyDao.observeNetworkShortlist(fingerprint,
            System.currentTimeMillis() - 7L * 24L * 60L * 60L * 1000L, 6)

    suspend fun recheckNetworkShortlist(
        fingerprint: String,
        timeoutMs: Int,
        onProxy: (MtProtoProxy) -> Unit
    ): Int {
        check(beginForegroundBenchmark()) { "Another benchmark is already running." }
        try {
            val previous = networkShortlist(fingerprint).first()
            var checked = 0
            kotlinx.coroutines.withTimeout(90_000L) {
                for (entry in previous) {
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    if (currentNetworkInfo()?.fingerprint != fingerprint) throw com.example.data.network.UserNetworkChangedException()
                    val proxy = getProxy(entry.proxyUrl) ?: continue
                    onProxy(proxy)
                    verifySingleProxy(proxy.url, timeoutMs)
                    checked++
                }
            }
            return checked
        } finally {
            endForegroundBenchmark()
        }
    }

    fun observeNetworkSpeedBaseline(fingerprint: String): Flow<NetworkSpeedBaseline?> {
        return proxyDao.observeNetworkSpeedBaseline(fingerprint)
    }

    val latestBenchmarkNetworkFingerprint: Flow<String?> =
        proxyDao.observeLatestBenchmarkNetworkFingerprint()

    suspend fun getProxy(url: String): MtProtoProxy? = proxyDao.getProxyByUrl(url)

    suspend fun getTelegramAuthorizationCandidates(limit: Int): List<MtProtoProxy> {
        return proxyDao.getTelegramAuthorizationCandidates(limit.coerceIn(1, 20))
            .filter(MtProtoProxy::isTelegramVerified)
    }

    suspend fun getTelegramRouteRecoveryCandidates(limit: Int): List<MtProtoProxy> {
        val safeLimit = limit.coerceIn(1, 20)
        val privateCandidates = proxyDao.getEnabledPrivateNodes(safeLimit)
            .filter(PrivateProxyNode::isHealthy)
            .map(PrivateProxyNode::asProxy)
        val publicCandidates = proxyDao.getTelegramRouteRecoveryCandidates(safeLimit)
        return (privateCandidates + publicCandidates)
            .distinctBy(MtProtoProxy::url)
            .sortedWith(
                compareByDescending<MtProtoProxy> { it.transferScore }
                    .thenByDescending { it.verificationConfidence }
                    .thenBy {
                        it.protocolP90Latency.takeIf { value -> value > 0L } ?: Long.MAX_VALUE
                    }
            )
            .take(safeLimit)
    }

    fun currentNetworkInfo(): CurrentNetworkInfo? {
        return verifier.currentNetworkIdentityOrNull()?.let { identity ->
            CurrentNetworkInfo(identity.fingerprint, identity.type, identity.isMetered)
        }
    }

    suspend fun countTelegramVerified(): Int = proxyDao.countTelegramVerified()

    suspend fun countStoredProxies(): Int = proxyDao.countStoredProxies()

    suspend fun getBenchmarkRunSnapshot(): BenchmarkRunRecord? = proxyDao.getBenchmarkRun()

    suspend fun markTelegramTransferFailed(proxy: MtProtoProxy) = dataOperationMutex.withLock {
        proxyDao.markTelegramTransferFailed(proxy.url, proxy.networkFingerprint)
    }

    suspend fun clearTelegramTransfer(proxy: MtProtoProxy) = dataOperationMutex.withLock {
        proxyDao.clearTelegramTransfer(proxy.url, proxy.networkFingerprint)
    }

    suspend fun persistTelegramTransfer(result: TelegramTransferResult): Boolean {
        return dataOperationMutex.withLock {
            val updated = proxyDao.updateTelegramTransfer(
                url = result.proxyUrl,
                downloadKbps = result.downloadKbps,
                uploadKbps = result.uploadKbps,
                sampleBytes = result.sampleBytes,
                measuredAt = result.measuredAt,
                rounds = result.rounds,
                confidence = result.confidence,
                networkUploadBytes = result.networkUploadBytes,
                networkDownloadBytes = result.networkDownloadBytes,
                networkFingerprint = result.networkFingerprint,
                networkType = result.networkType,
                downloadFloorKbps = result.downloadFloorKbps,
                uploadFloorKbps = result.uploadFloorKbps,
                downloadPeakKbps = result.downloadPeakKbps,
                uploadPeakKbps = result.uploadPeakKbps
            ) == 1
            if (updated) {
                proxyDao.getProxyByUrl(result.proxyUrl)?.let { proxy ->
                    proxyDao.insertTestHistories(
                        listOf(ProxyTestHistory.fromProxy(proxy, result.measuredAt))
                    )
                    if (proxy.isTelegramVerified) updateFavoriteLastKnownGood(proxy)
                }
                pruneTestHistory()
            }
            updated
        }
    }

    suspend fun collectAndCacheProxies(
        onProgress: (Int) -> Unit = {}
    ): ProxyRefreshResult = dataOperationMutex.withLock {
        if (foregroundBenchmarkActive.get()) {
            Log.d(TAG, "Refresh deferred until the foreground benchmark finishes.")
            return@withLock ProxyRefreshResult(
                proxyCount = 0,
                cacheReplaced = false,
                completed = false
            )
        }
        var insertedCount = 0
        var cacheReplaced = false
        val freshProxiesByUrl = LinkedHashMap<String, MtProtoProxy>()
        _refreshInProgress.value = true
        try {
            val collectedCount = collector.collectAll { freshBatch ->
                freshBatch.sourceProxies.forEach { proxy ->
                    freshProxiesByUrl[proxy.url] = proxy
                }
                val memberships = freshBatch.sourceProxies
                    .asSequence()
                    .map { proxy ->
                        ProxySourceMembership(
                            sourceUrl = freshBatch.source.url,
                            proxyUrl = proxy.url
                        )
                    }
                    .distinct()
                    .toList()
                val chunks = freshBatch.globallyUniqueProxies.chunked(PROXY_INSERT_CHUNK_SIZE)
                if (chunks.isEmpty() && cacheReplaced && memberships.isNotEmpty()) {
                    proxyDao.insertFreshChunk(emptyList(), memberships)
                }
                chunks.forEachIndexed { index, chunk ->
                    val chunkMemberships = if (index == 0) memberships else emptyList()
                    val replacesPreviousCache = !cacheReplaced
                    val addedCount = if (replacesPreviousCache) {
                        proxyDao.replaceAll(chunk, chunkMemberships)
                    } else {
                        proxyDao.insertFreshChunk(chunk, chunkMemberships)
                    }
                    if (addedCount > 0) {
                        cacheReplaced = true
                        if (replacesPreviousCache) {
                            _refreshRevision.value += 1L
                        }
                        insertedCount += addedCount
                        onProgress(insertedCount)
                        delay(PROXY_INSERT_PAUSE_MS)
                    }
                }
            }
            if (!cacheReplaced) {
                Log.w(TAG, "No fresh proxies were collected; keeping the existing cache.")
                return@withLock ProxyRefreshResult(proxyCount = 0, cacheReplaced = false)
            }

            val countriesCompleted = withTimeoutOrNull(COUNTRY_ENRICHMENT_BUDGET_MS) {
                enrichFreshCountries(freshProxiesByUrl)
                true
            } ?: false
            if (!countriesCompleted) {
                Log.w(TAG, "Country enrichment reached its time budget; keeping fresh proxies.")
            }
            val restoredFavorites = proxyDao.restoreActiveFavorites()
            if (restoredFavorites > 0) {
                Log.d(TAG, "Restored $restoredFavorites user favorites with fresh test state.")
            }
            Log.d(
                TAG,
                "Progressively replaced proxy cache with $insertedCount of " +
                    "$collectedCount collected proxies."
            )
            _listOrderRevision.value += 1L
            ProxyRefreshResult(proxyCount = insertedCount, cacheReplaced = true)
        } catch (e: CancellationException) {
            // The first successful replace is an atomic commit. Keep those fresh rows if
            // WorkManager stops while optional metadata is still being enriched.
            if (cacheReplaced) Log.w(TAG, "Refresh cancelled; keeping $insertedCount fresh proxies.")
            throw e
        } catch (e: UserNetworkException) {
            if (cacheReplaced) {
                Log.w(TAG, "Network changed; keeping $insertedCount fresh proxies already stored.")
            }
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error during collection and caching", e)
            ProxyRefreshResult(
                proxyCount = insertedCount,
                cacheReplaced = cacheReplaced,
                completed = false
            )
        } finally {
            _refreshInProgress.value = false
        }
    }

    private suspend fun enrichFreshCountries(
        freshProxiesByUrl: Map<String, MtProtoProxy>
    ) {
        if (freshProxiesByUrl.isEmpty()) return
        try {
            val enriched = countryResolver.enrich(freshProxiesByUrl.values.toList())
            val changed = enriched.filter { proxy ->
                val original = freshProxiesByUrl[proxy.url]
                original != null &&
                    (original.country != proxy.country ||
                        original.countryCode != proxy.countryCode)
            }
            changed.chunked(COUNTRY_UPDATE_CHUNK_SIZE).forEach { chunk ->
                proxyDao.insertOrReplaceAllPreservingFavorites(chunk)
            }
            Log.d(TAG, "Persisted country data for ${changed.size} fresh proxies.")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Country enrichment is best-effort; a temporary metadata service failure must not
            // discard an otherwise successful fresh proxy snapshot.
            Log.w(TAG, "Unable to enrich fresh proxy countries.", e)
        }
    }

    suspend fun measureCurrentNetworkSpeed(): NetworkSpeedResult? {
        return dataOperationMutex.withLock {
            val result = networkSpeedMeter.measure() ?: return@withLock null
            proxyDao.upsertNetworkSpeedBaseline(
                NetworkSpeedBaseline(
                    networkFingerprint = result.networkFingerprint,
                    networkType = result.networkType,
                    isMetered = result.isMetered,
                    downloadKbps = result.downloadKbps,
                    uploadKbps = result.uploadKbps,
                    downloadSampleBytes = result.downloadSampleBytes,
                    uploadSampleBytes = result.uploadSampleBytes,
                    measuredAt = result.measuredAt
                )
            )
            proxyDao.deleteOldNetworkSpeedBaselines(
                result.measuredAt - NETWORK_BASELINE_RETENTION_MS
            )
            result
        }
    }

    suspend fun verifyStoredProxies(
        parallelWorkers: Int,
        timeoutMs: Int,
        limit: Int? = null,
        onStarted: (Int) -> Unit = {},
        onProgress: (Int, Int, MtProtoProxy) -> Unit = { _, _, _ -> }
    ): Int {
        val result = runThreeStageBenchmark(
            parallelWorkers = parallelWorkers,
            timeoutMs = timeoutMs,
            limit = limit,
            onStarted = onStarted,
            onProgress = { progress, proxy ->
                if (progress.stage == BenchmarkStage.SCREENING) {
                    onProgress(progress.stageCompleted, progress.stageTotal, proxy)
                }
            }
        )
        return result.screenedCount
    }

    suspend fun runThreeStageBenchmark(
        parallelWorkers: Int,
        timeoutMs: Int,
        limit: Int? = null,
        accuracyMode: BenchmarkAccuracyMode = BenchmarkAccuracyMode.BALANCED,
        checkpoint: suspend () -> Unit = {},
        onStarted: (Int) -> Unit = {},
        onProgress: suspend (ThreeStageProgress, MtProtoProxy) -> Unit = { _, _ -> }
    ): ThreeStageBenchmarkResult = dataOperationMutex.withLock {
        val requestedLimit = limit?.coerceAtLeast(1) ?: Int.MAX_VALUE
        val testPlan = BenchmarkTestPlans.forMode(accuracyMode)
        val proxiesToVerify = proxyDao.getProxiesForBenchmark(requestedLimit)
        if (proxiesToVerify.isEmpty()) {
            return@withLock ThreeStageBenchmarkResult(0, 0, 0, 0, 0)
        }

        onStarted(proxiesToVerify.size)
        Log.d(TAG, "Starting three-stage verification of ${proxiesToVerify.size} proxies.")
        val pendingResults = ArrayList<MtProtoProxy>(BENCHMARK_PERSIST_CHUNK_SIZE)
        val pendingHistory = ArrayList<ProxyTestHistory>(BENCHMARK_PERSIST_CHUNK_SIZE)
        val screeningCandidates = ArrayList<MtProtoProxy>()
        val strictResults = ArrayList<MtProtoProxy>()
        var completedWork = 0
        var expectedWork = proxiesToVerify.size
        var benchmarkNetworkFingerprint: String? = null

        suspend fun flushPendingResults() {
            if (pendingResults.isEmpty()) return
            val chunk = pendingResults.toList()
            val historyChunk = pendingHistory.toList()
            proxyDao.persistBenchmarkResults(chunk, historyChunk)
            pendingResults.clear()
            pendingHistory.clear()
        }

        suspend fun persist(updatedProxy: MtProtoProxy) {
            pendingResults += updatedProxy
            pendingHistory += ProxyTestHistory.fromProxy(updatedProxy)
            if (pendingResults.size >= BENCHMARK_PERSIST_CHUNK_SIZE) flushPendingResults()
        }

        fun requireBenchmarkNetwork(fingerprint: String) {
            if (fingerprint.isBlank()) return
            val expected = benchmarkNetworkFingerprint
            if (expected == null) {
                benchmarkNetworkFingerprint = fingerprint
            } else if (expected != fingerprint) {
                throw com.example.data.network.UserNetworkChangedException()
            }
        }

        suspend fun report(
            stage: BenchmarkStage,
            stageCompleted: Int,
            stageTotal: Int,
            proxy: MtProtoProxy
        ) {
            onProgress(
                ThreeStageProgress(
                    stage = stage,
                    stageCompleted = stageCompleted,
                    stageTotal = stageTotal,
                    completedWork = completedWork,
                    totalWork = expectedWork,
                    currentProxy = proxy
                ),
                proxy
            )
        }

        try {
            verifier.verifyBatchDetailed(
                proxies = proxiesToVerify,
                parallelWorkers = parallelWorkers,
                timeoutMs = timeoutMs,
                profile = testPlan.screeningProfile,
                checkpoint = checkpoint,
                onProgress = { completed, updatedProxy, result ->
                    requireBenchmarkNetwork(result.networkFingerprint)
                    val assessedProxy = updatedProxy.copy(
                        benchmarkFailureReason =
                            ThreeStageBenchmarkPolicy.screeningFailureReason(updatedProxy)
                    )
                    if (ThreeStageBenchmarkPolicy.passesScreening(assessedProxy)) {
                        screeningCandidates += assessedProxy
                    }
                    completedWork += 1
                    persist(assessedProxy)
                    report(
                        BenchmarkStage.SCREENING,
                        completed,
                        proxiesToVerify.size,
                        assessedProxy
                    )
                }
            )
            flushPendingResults()
            checkpoint()

            expectedWork = completedWork + screeningCandidates.size
            verifier.verifyBatchDetailed(
                proxies = screeningCandidates,
                parallelWorkers = parallelWorkers,
                timeoutMs = timeoutMs,
                profile = testPlan.strictProfile,
                checkpoint = checkpoint,
                onProgress = { completed, updatedProxy, result ->
                    requireBenchmarkNetwork(result.networkFingerprint)
                    val assessedProxy = updatedProxy.copy(
                        benchmarkFailureReason =
                            ThreeStageBenchmarkPolicy.strictFailureReason(updatedProxy)
                    )
                    strictResults += assessedProxy
                    completedWork += 1
                    persist(assessedProxy)
                    report(
                        BenchmarkStage.STRICT,
                        completed,
                        screeningCandidates.size,
                        assessedProxy
                    )
                }
            )
            flushPendingResults()
            checkpoint()

            val finalists = ThreeStageBenchmarkPolicy.selectFinalists(strictResults)
            val finalistRounds = LinkedHashMap<String, MutableList<ProxyVerifier.VerificationResult>>()
            finalists.forEach { proxy ->
                finalistRounds[proxy.url] = ArrayList(testPlan.finalistRounds)
            }
            val finalistWork = finalists.size * testPlan.finalistRounds
            expectedWork = completedWork + finalistWork
            var completedFinalistWork = 0

            repeat(testPlan.finalistRounds) { round ->
                if (round > 0 && finalists.isNotEmpty()) {
                    delay(FINALIST_ROUND_GAP_MS)
                    checkpoint()
                }
                verifier.verifyBatchDetailed(
                    proxies = finalists,
                    parallelWorkers = parallelWorkers,
                    timeoutMs = timeoutMs,
                    profile = testPlan.finalistProfile,
                    checkpoint = checkpoint,
                    onProgress = { _, measuredProxy, result ->
                        requireBenchmarkNetwork(result.networkFingerprint)
                        finalistRounds.getValue(measuredProxy.url) += result
                        completedWork += 1
                        completedFinalistWork += 1
                        report(
                            BenchmarkStage.FINALIST,
                            completedFinalistWork,
                            finalistWork,
                            measuredProxy
                        )
                    }
                )
            }

            val successfulFinalists = ArrayList<MtProtoProxy>(finalists.size)
            finalists.forEach { finalist ->
                val aggregate = verifier.aggregateFinalistResults(
                    proxy = finalist,
                    results = finalistRounds.getValue(finalist.url)
                )
                val assessedProxy = aggregate.copy(
                    benchmarkFailureReason =
                        ThreeStageBenchmarkPolicy.finalistFailureReason(
                            aggregate,
                            testPlan.finalistRounds
                        )
                )
                persist(assessedProxy)
                if (ThreeStageBenchmarkPolicy.passesFinalist(
                        assessedProxy,
                        testPlan.finalistRounds
                    )
                ) {
                    successfulFinalists += assessedProxy
                }
            }
            flushPendingResults()
            pruneTestHistory()
            updateSourceVerificationYields()

            ThreeStageBenchmarkResult(
                requestedCount = proxiesToVerify.size,
                screenedCount = proxiesToVerify.size,
                strictCount = screeningCandidates.size,
                finalistCount = finalists.size,
                completedCount = completedWork,
                successfulFinalists = successfulFinalists,
                networkFingerprint = benchmarkNetworkFingerprint.orEmpty()
            )
        } catch (e: CancellationException) {
            persistPendingResults(pendingResults)
            withContext(NonCancellable) { updateSourceVerificationYields() }
            throw e
        } catch (e: Exception) {
            persistPendingResults(pendingResults)
            withContext(NonCancellable) { updateSourceVerificationYields() }
            throw e
        } finally {
            if (completedWork > 0) _listOrderRevision.value += 1L
        }
    }

    private suspend fun persistPendingResults(results: List<MtProtoProxy>) {
        if (results.isEmpty()) return
        withContext(NonCancellable) {
            try {
                proxyDao.persistBenchmarkResults(
                    proxies = results,
                    history = results.map(ProxyTestHistory::fromProxy)
                )
                results.filter(MtProtoProxy::isTelegramVerified).forEach { proxy ->
                    updateFavoriteLastKnownGood(proxy)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Unable to persist partial benchmark results.", e)
            }
        }
    }

    suspend fun verifySingleProxy(
        url: String,
        timeoutMs: Int = 3000,
        attempts: Int = ProxyVerifier.STRICT_TCP_ATTEMPTS
    ): MtProtoProxy? = dataOperationMutex.withLock {
        val existing = proxyDao.getProxyByUrl(url) ?: return@withLock null
        val result = verifier.verifySingle(existing, timeoutMs, attempts)
        val measured = verifier.run { result.applyTo(existing) }
        val updated = measured.copy(
            benchmarkFailureReason = ThreeStageBenchmarkPolicy.strictFailureReason(measured)
        )
        proxyDao.persistBenchmarkResults(
            proxies = listOf(updated),
            history = listOf(ProxyTestHistory.fromProxy(updated))
        )
        if (updated.isTelegramVerified) updateFavoriteLastKnownGood(updated)
        pruneTestHistory()
        updateSourceVerificationYields()
        _listOrderRevision.value += 1L
        updated
    }

    private suspend fun updateSourceVerificationYields() {
        sourceHealthDao.getVerificationYields().forEach { sourceYield ->
            sourceHealthDao.updateVerifiedYield(
                url = sourceYield.sourceUrl,
                verified = sourceYield.verifiedCount,
                rate = sourceYield.verifiedRate
            )
        }
    }

    suspend fun setFavorite(
        proxy: MtProtoProxy,
        isFavorite: Boolean,
        syncState: String
    ): FavoriteProxy {
        return dataOperationMutex.withLock {
            proxyDao.setFavorite(
                proxy = proxy,
                isFavorite = isFavorite,
                syncState = syncState,
                now = System.currentTimeMillis()
            )
        }
    }

    suspend fun getFavorite(url: String): FavoriteProxy? = proxyDao.getFavoriteByUrl(url)

    suspend fun getActiveFavoritesSnapshot(): List<FavoriteProxy> = proxyDao.getActiveFavorites()

    suspend fun updateFavoriteMetadata(
        url: String,
        folder: String,
        tags: String,
        note: String
    ): Boolean = dataOperationMutex.withLock {
        proxyDao.updateFavoriteMetadata(
            url = url,
            folder = folder.trim().take(32).ifBlank { FavoriteProxy.DEFAULT_FOLDER },
            tags = tags.split(',')
                .map(String::trim)
                .filter(String::isNotBlank)
                .distinct()
                .take(8)
                .joinToString(","),
            note = note.trim().take(240),
            updatedAt = System.currentTimeMillis()
        ) == 1
    }

    suspend fun importFavorite(entry: ProxyProfileFavorite): Boolean {
        val proxy = MtProtoProxy.parseFromUrl(entry.url) ?: return false
        return dataOperationMutex.withLock {
            proxyDao.importFavorite(
                proxy = proxy,
                folder = entry.folder.trim().take(32).ifBlank { FavoriteProxy.DEFAULT_FOLDER },
                tags = entry.tags.trim().take(160),
                note = entry.note.trim().take(240),
                now = System.currentTimeMillis()
            )
        }
    }

    suspend fun addPrivateNode(rawUrl: String, label: String): PrivateProxyNode = dataOperationMutex.withLock {
        val proxy = requireNotNull(MtProtoProxy.parseFromUrl(rawUrl)) {
            "Enter a valid Telegram MTProto proxy link."
        }
        val now = System.currentTimeMillis()
        val previous = proxyDao.getPrivateNode(proxy.url)
        val node = PrivateProxyNode.fromProxy(proxy, label, now).copy(
            enabled = previous?.enabled ?: true,
            createdAt = previous?.createdAt ?: now,
            lastChecked = previous?.lastChecked ?: 0L,
            verificationStatus = previous?.verificationStatus ?: MtProtoProxy.VERIFICATION_UNTESTED,
            verificationConfidence = previous?.verificationConfidence ?: 0,
            latency = previous?.latency ?: 0L,
            p90Latency = previous?.p90Latency ?: 0L,
            jitter = previous?.jitter ?: 0L,
            stability = previous?.stability ?: 0f,
            transferScore = previous?.transferScore ?: 0,
            networkFingerprint = previous?.networkFingerprint.orEmpty(),
            networkType = previous?.networkType.orEmpty(),
            lastError = previous?.lastError.orEmpty()
        )
        proxyDao.upsertPrivateNode(node)
        node
    }

    suspend fun setPrivateNodeEnabled(url: String, enabled: Boolean): Boolean {
        return dataOperationMutex.withLock {
            val existing = proxyDao.getPrivateNode(url) ?: return@withLock false
            proxyDao.upsertPrivateNode(
                existing.copy(enabled = enabled, updatedAt = System.currentTimeMillis())
            )
            true
        }
    }

    suspend fun deletePrivateNode(url: String): Boolean = dataOperationMutex.withLock {
        proxyDao.deletePrivateNode(url) == 1
    }

    suspend fun testPrivateNode(url: String, timeoutMs: Int): PrivateProxyNode? {
        check(beginForegroundBenchmark()) { "Another network measurement is running." }
        try {
            return dataOperationMutex.withLock {
                val node = proxyDao.getPrivateNode(url) ?: return@withLock null
                val original = node.asProxy()
                val verification = verifier.verifySingle(
                    original,
                    timeoutMs = timeoutMs,
                    attempts = ProxyVerifier.STRICT_TCP_ATTEMPTS
                )
                val measured = verifier.run { verification.applyTo(original) }
                val now = System.currentTimeMillis()
                val updated = node.copy(
                    updatedAt = now,
                    lastChecked = now,
                    verificationStatus = measured.verificationStatus,
                    verificationConfidence = measured.verificationConfidence,
                    latency = measured.protocolLatency.takeIf { it > 0L } ?: measured.latency,
                    p90Latency = measured.protocolP90Latency.takeIf { it > 0L }
                        ?: measured.tcpP90Latency,
                    jitter = measured.protocolJitter.takeIf { it >= 0L } ?: measured.tcpJitter,
                    stability = measured.stability,
                    transferScore = measured.transferScore,
                    networkFingerprint = measured.networkFingerprint,
                    networkType = measured.testedNetworkType,
                    lastError = ThreeStageBenchmarkPolicy.strictFailureReason(measured)
                )
                proxyDao.upsertPrivateNode(updated)
                updated
            }
        } finally {
            endForegroundBenchmark()
        }
    }

    suspend fun getPrivateNodesSnapshot(): List<PrivateProxyNode> = proxyDao.getPrivateNodes()

    suspend fun getFavoritesNeedingSync(): List<FavoriteProxy> =
        proxyDao.getFavoritesNeedingSync()

    suspend fun markFavoriteSynced(url: String, messageId: Long): Boolean {
        return dataOperationMutex.withLock {
            proxyDao.markFavoriteSynced(url, messageId, System.currentTimeMillis()) == 1
        }
    }

    suspend fun markFavoriteSyncFailed(url: String, errorCode: String): Boolean {
        return dataOperationMutex.withLock {
            proxyDao.markFavoriteSyncFailed(
                url = url,
                errorCode = errorCode.take(MAX_FAVORITE_ERROR_CODE_LENGTH),
                updatedAt = System.currentTimeMillis()
            ) == 1
        }
    }

    fun observeBenchmarkRun(): Flow<BenchmarkRunRecord?> = proxyDao.observeBenchmarkRun()

    suspend fun saveBenchmarkRun(record: BenchmarkRunRecord) {
        proxyDao.upsertBenchmarkRun(record)
    }

    suspend fun clearBenchmarkRun() {
        proxyDao.clearBenchmarkRun()
    }

    suspend fun markOrphanedBenchmarkInterrupted(): Boolean {
        return proxyDao.markOrphanedBenchmarkInterrupted(System.currentTimeMillis()) == 1
    }

    fun beginForegroundBenchmark(): Boolean {
        return foregroundBenchmarkActive.compareAndSet(false, true)
    }

    fun endForegroundBenchmark() {
        foregroundBenchmarkActive.set(false)
    }

    suspend fun markProxyUsed(url: String) {
        dataOperationMutex.withLock {
            proxyDao.updateRecentlyUsed(url, System.currentTimeMillis())
        }
    }

    suspend fun deleteDeadProxies(): Int {
        return dataOperationMutex.withLock { proxyDao.pruneDeadProxies() }
    }

    suspend fun clearAllData(): Int {
        return dataOperationMutex.withLock { proxyDao.clearAllData() }
    }

    private suspend fun pruneTestHistory() {
        val cutoff = System.currentTimeMillis() - TEST_HISTORY_RETENTION_MS
        proxyDao.deleteHistoryOlderThan(cutoff)
        proxyDao.trimTestHistory(MAX_TEST_HISTORY_ROWS)
    }

    private suspend fun updateFavoriteLastKnownGood(proxy: MtProtoProxy) {
        val hasTransferMeasurement = TransferRankingEvidence.isUsableMeasurement(proxy)
        proxyDao.updateFavoriteLastKnownGood(
            url = proxy.url,
            checkedAt = if (hasTransferMeasurement) {
                maxOf(proxy.lastChecked, proxy.telegramTransferMeasuredAt)
            } else proxy.lastChecked,
            transferScore = proxy.transferScore,
            latency = proxy.protocolLatency.takeIf { it > 0L } ?: proxy.latency,
            downloadKbps = if (hasTransferMeasurement) proxy.telegramDownloadKbps else 0,
            uploadKbps = if (hasTransferMeasurement) proxy.telegramUploadKbps else 0
        )
    }

    private companion object {
        const val TAG = "ProxyRepository"
        const val PROXY_INSERT_CHUNK_SIZE = 64
        const val PROXY_INSERT_PAUSE_MS = 45L
        const val COUNTRY_ENRICHMENT_BUDGET_MS = 45_000L
        const val BENCHMARK_PERSIST_CHUNK_SIZE = 24
        const val COUNTRY_UPDATE_CHUNK_SIZE = 100
        const val FINALIST_ROUND_GAP_MS = 1_200L
        const val PROXY_PAGE_SIZE = 24
        const val PROXY_PREFETCH_DISTANCE = 8
        const val PROXY_INITIAL_LOAD_SIZE = 24
        const val BENCHMARK_PREVIEW_LIMIT = 12
        const val BENCHMARK_CHART_LIMIT = 10
        const val MAX_CHART_LATENCY_MS = 60_000L
        const val TEST_HISTORY_PER_PROXY_LIMIT = 20
        const val MAX_TEST_HISTORY_ROWS = 10_000
        const val TEST_HISTORY_RETENTION_MS = 30L * 24L * 60L * 60L * 1_000L
        const val MAX_FAVORITE_ERROR_CODE_LENGTH = 80
        const val SMART_RECOMMENDATION_FRESHNESS_MS = 24L * 60L * 60L * 1_000L
        const val SOURCE_STATS_7_DAY_WINDOW_MS = 7L * 24L * 60L * 60L * 1_000L
        const val SOURCE_STATS_30_DAY_WINDOW_MS = 30L * 24L * 60L * 60L * 1_000L
        const val NETWORK_BASELINE_RETENTION_MS = 14L * 24L * 60L * 60L * 1_000L
    }
}
