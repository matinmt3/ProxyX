package com.example.viewmodel

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.example.data.benchmark.BenchmarkCoordinator
import com.example.data.benchmark.BenchmarkAccuracyMode
import com.example.data.benchmark.BenchmarkExecution
import com.example.data.benchmark.BenchmarkRunStatus
import com.example.data.benchmark.BenchmarkStage
import com.example.data.network.UserNetworkException
import com.example.data.network.NetworkUsagePolicy
import com.example.data.network.MeteredNetworkNotAllowedException
import com.example.data.repository.ProxyRepository
import com.example.data.repository.SettingsRepository
import com.example.data.repository.CurrentNetworkInfo
import com.example.data.repository.ProxyProfileCodec
import com.example.data.telegram.TdlibTelegramTransferEngine
import com.example.data.telegram.TelegramAuthorizationStep
import com.example.BuildConfig
import com.example.ui.ProxyQrSharer
import com.example.model.MtProtoProxy
import com.example.model.NetworkSpeedBaseline
import com.example.model.FavoriteProxy
import com.example.model.BenchmarkRunRecord
import com.example.model.ProxyVerificationLevel
import com.example.model.ProxyTestHistory
import com.example.model.PrivateProxyNode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class StatusFilter {
    ALL, TELEGRAM_VERIFIED, REACHABLE_ONLY
}

enum class SortBy {
    BEST, HIGH, DOWN_UP, DOWNLOAD, UPLOAD, LATENCY, PROTOCOL, STABILITY, COUNTRY
}

private data class ProxyListFilters(
    val query: String,
    val status: StatusFilter,
    val sort: SortBy,
    val currentNetworkFingerprint: String,
    val refreshRevision: Int
)

data class UserMessage(
    val english: String,
    val persian: String
)

data class BenchmarkHistorySnapshot(
    val testedCount: Int = 0,
    val readyCount: Int = 0,
    val verifiedCount: Int = 0,
    val tcpOnlyCount: Int = 0,
    val previewRows: List<MtProtoProxy> = emptyList(),
    val chartRows: List<MtProtoProxy> = emptyList()
)

data class BenchmarkSessionState(
    val completedCount: Int = 0,
    val totalCount: Int = 0,
    val readyCount: Int = 0,
    val verifiedCount: Int = 0,
    val tcpOnlyCount: Int = 0,
    val previewRows: List<MtProtoProxy> = emptyList(),
    val chartRows: List<MtProtoProxy> = emptyList()
)

data class NetworkSpeedUiState(
    val isRunning: Boolean = false,
    val downloadKbps: Int = 0,
    val uploadKbps: Int = 0,
    val measuredAt: Long = 0L,
    val downloadSampleBytes: Int = 0,
    val uploadSampleBytes: Int = 0,
    val failed: Boolean = false
) {
    val hasMeasurement: Boolean
        get() = measuredAt > 0L && downloadKbps > 0 && uploadKbps > 0

    internal fun withBaseline(baseline: NetworkSpeedBaseline?): NetworkSpeedUiState {
        if (isRunning) return this
        return baseline?.takeIf { it.isUsable }?.let {
            NetworkSpeedUiState(downloadKbps = it.downloadKbps, uploadKbps = it.uploadKbps,
                measuredAt = it.measuredAt, downloadSampleBytes = it.downloadSampleBytes,
                uploadSampleBytes = it.uploadSampleBytes)
        } ?: NetworkSpeedUiState()
    }
}

internal fun buildBenchmarkHistorySnapshot(
    proxies: List<MtProtoProxy>
): BenchmarkHistorySnapshot {
    var testedCount = 0
    var readyCount = 0
    var verifiedCount = 0
    var tcpOnlyCount = 0
    proxies.forEach { proxy ->
        if (proxy.lastChecked <= 0L) return@forEach
        testedCount += 1
        if (proxy.isTelegramReady) readyCount += 1
        if (proxy.isTelegramVerified) verifiedCount += 1
        if (proxy.verificationLevel == ProxyVerificationLevel.TCP_OPEN) tcpOnlyCount += 1
    }
    return BenchmarkHistorySnapshot(
        testedCount = testedCount,
        readyCount = readyCount,
        verifiedCount = verifiedCount,
        tcpOnlyCount = tcpOnlyCount,
        previewRows = ProxyRanking.benchmarkResults(proxies, BENCHMARK_PREVIEW_LIMIT),
        chartRows = selectBenchmarkChartRows(proxies)
    )
}

internal fun appendBenchmarkResult(
    previous: BenchmarkSessionState,
    completed: Int,
    total: Int,
    proxy: MtProtoProxy
): BenchmarkSessionState {
    val candidates = previous.previewRows + proxy
    return previous.copy(
        completedCount = completed.coerceIn(0, total.coerceAtLeast(0)),
        totalCount = total.coerceAtLeast(0),
        readyCount = previous.readyCount + if (proxy.isTelegramReady) 1 else 0,
        verifiedCount = previous.verifiedCount + if (proxy.isTelegramVerified) 1 else 0,
        tcpOnlyCount = previous.tcpOnlyCount +
            if (proxy.verificationLevel == ProxyVerificationLevel.TCP_OPEN) 1 else 0,
        previewRows = ProxyRanking.benchmarkResults(candidates, BENCHMARK_PREVIEW_LIMIT),
        chartRows = selectBenchmarkChartRows(previous.chartRows + proxy)
    )
}

private fun selectBenchmarkChartRows(proxies: List<MtProtoProxy>): List<MtProtoProxy> {
    return proxies.asSequence()
        .filter { it.isTcpReachable && it.latency in 1..MAX_CHART_LATENCY_MS }
        .sortedBy { it.latency }
        .take(BENCHMARK_CHART_LIMIT)
        .toList()
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ProxyViewModel(
    private val appContext: Context,
    private val proxyRepository: ProxyRepository,
    private val settingsRepository: SettingsRepository,
    private val benchmarkCoordinator: BenchmarkCoordinator,
    private val telegramTransferEngine: TdlibTelegramTransferEngine
) : ViewModel() {
    private val networkUsagePolicy = NetworkUsagePolicy(appContext)
    val genericProxies by lazy {
        GenericProxyController(appContext, viewModelScope,
            (appContext.applicationContext as com.example.ProxyXApplication).appContainer.genericRepository,
            settingsRepository, proxyRepository, currentNetworkInfo.map { it?.fingerprint.orEmpty() },
            canStart = { !isProxyDataBusy() })
    }
    val webProxies = com.example.data.web.WebProxyController(
        appContext, viewModelScope,
        com.example.data.database.ProxyDatabase.getDatabase(appContext).webProxyDao(),
        settingsRepository, proxyRepository, telegramTransferEngine,
        canStart = { !isProxyDataBusy() }
    )
    val releaseUpdates = com.example.data.update.ReleaseUpdateController(
        appContext, viewModelScope,
        canUseNetwork = { !isProxyDataBusy() },
        reserveNetwork = proxyRepository::beginForegroundBenchmark,
        releaseNetwork = proxyRepository::endForegroundBenchmark
    )

    // Filtering, Search and Sort States
    private val _searchQuery = MutableStateFlow("")
    val searchQuery = _searchQuery.asStateFlow()

    private val _statusFilter = MutableStateFlow(StatusFilter.ALL)
    val statusFilter = _statusFilter.asStateFlow()

    private val _sortBy = MutableStateFlow(SortBy.BEST)
    val sortBy = _sortBy.asStateFlow()

    private val _benchmarkRevision = MutableStateFlow(0)
    val benchmarkRevision = _benchmarkRevision.asStateFlow()

    private val _selectedProxyUrl = MutableStateFlow<String?>(null)
    val selectedProxyUrl = _selectedProxyUrl.asStateFlow()

    private val _currentNetworkInfo = MutableStateFlow<CurrentNetworkInfo?>(null)
    val currentNetworkInfo = _currentNetworkInfo.asStateFlow()
    val networkShortlist = _currentNetworkInfo.map { it?.fingerprint.orEmpty() }
        .distinctUntilChanged().flatMapLatest(proxyRepository::networkShortlist)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val networkRecheckEnabled = settingsRepository.networkRecheckEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    private val _networkRechecking = MutableStateFlow(false)
    val networkRechecking = _networkRechecking.asStateFlow()
    private var lastAutomaticNetworkRecheckAt = 0L

    private val proxyListFilters = combine(
        _searchQuery,
        _statusFilter,
        _sortBy,
        _currentNetworkInfo.map { it?.fingerprint.orEmpty() }.distinctUntilChanged(),
        _benchmarkRevision
    ) { query, status, sort, fingerprint, revision ->
        ProxyListFilters(query, status, sort, fingerprint, revision)
    }

    val pagedProxies: Flow<PagingData<MtProtoProxy>> = proxyListFilters
        .flatMapLatest { filters ->
            proxyRepository.pagedProxies(
                query = filters.query,
                status = filters.status.name,
                sort = filters.sort.name,
                currentNetworkFingerprint = filters.currentNetworkFingerprint
            )
        }
        .cachedIn(viewModelScope)

    val totalProxyCount: StateFlow<Int> = proxyRepository.totalProxyCount
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val filteredProxyCount: StateFlow<Int> = proxyListFilters
        .flatMapLatest { filters ->
            proxyRepository.filteredProxyCount(
                query = filters.query,
                status = filters.status.name
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val selectedProxy: StateFlow<MtProtoProxy?> = _selectedProxyUrl
        .flatMapLatest { selectedUrl ->
            selectedUrl?.let(proxyRepository::observeProxy) ?: flowOf(null)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val selectedProxyHistory: StateFlow<List<ProxyTestHistory>> = _selectedProxyUrl
        .flatMapLatest { selectedUrl ->
            selectedUrl?.let(proxyRepository::observeTestHistory) ?: flowOf(emptyList())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val currentNetworkBaseline: StateFlow<NetworkSpeedBaseline?> = _currentNetworkInfo
        .map { it?.fingerprint.orEmpty() }
        .distinctUntilChanged()
        .flatMapLatest { fingerprint ->
            if (fingerprint.isBlank()) flowOf(null)
            else proxyRepository.observeNetworkSpeedBaseline(fingerprint)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val benchmarkNetworkMismatch: StateFlow<Boolean> = combine(
        _currentNetworkInfo,
        proxyRepository.latestBenchmarkNetworkFingerprint
    ) { current, testedFingerprint ->
        current?.fingerprint?.isNotBlank() == true &&
            !testedFingerprint.isNullOrBlank() &&
            current.fingerprint != testedFingerprint
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val smartRecommendation: StateFlow<MtProtoProxy?> = _currentNetworkInfo
        .map { it?.fingerprint.orEmpty() }
        .distinctUntilChanged()
        .flatMapLatest(proxyRepository::smartRecommendationFor)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val benchmarkHistory: StateFlow<BenchmarkHistorySnapshot> = combine(
        _currentNetworkInfo.map { it?.fingerprint.orEmpty() }.distinctUntilChanged(),
        benchmarkCoordinator.state.map { it.startedAt }.distinctUntilChanged()
    ) { fingerprint, since -> fingerprint to since }
        .flatMapLatest { (fingerprint, since) ->
            combine(
                proxyRepository.benchmarkCounts(since),
                proxyRepository.benchmarkPreview(fingerprint, since),
                proxyRepository.benchmarkChart(since)
            ) { counts, preview, chart ->
                BenchmarkHistorySnapshot(
                    testedCount = counts.testedCount,
                    readyCount = counts.readyCount,
                    verifiedCount = counts.verifiedCount,
                    tcpOnlyCount = counts.tcpOnlyCount,
                    previewRows = preview,
                    chartRows = chart
                )
            }
    }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            BenchmarkHistorySnapshot()
        )

    // Settings States
    val theme = settingsRepository.theme.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "dark")
    val autoRefreshInterval = settingsRepository.autoRefreshInterval.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 15)
    val autoRefreshEnabled = settingsRepository.autoRefreshEnabled.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    val timeoutMs = settingsRepository.timeoutMs.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 3000)
    val parallelWorkers = settingsRepository.parallelWorkers.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 12)
    val adaptiveWorkersEnabled = settingsRepository.adaptiveWorkersEnabled.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    val unmeteredOnly = settingsRepository.unmeteredOnly.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val telegramFinalistTransferEnabled = settingsRepository.telegramFinalistTransferEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val telegramTransferDataBudgetMb = settingsRepository.telegramTransferDataBudgetMb
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)
    val notificationsEnabled = settingsRepository.notificationsEnabled.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    val language = settingsRepository.language.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "system")
    val channelPromptSeen: StateFlow<Boolean?> = settingsRepository.channelPromptSeen
        .map<Boolean, Boolean?> { it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val privateNodeMonitoringEnabled = settingsRepository.privateNodeMonitoringEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    val benchmarkAccuracyMode = settingsRepository.benchmarkAccuracyMode
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            BenchmarkAccuracyMode.BALANCED
        )
    val autoResumeBenchmark = settingsRepository.autoResumeBenchmark
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)
    val scheduledBenchmarkEnabled = settingsRepository.scheduledBenchmarkEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    val scheduledBenchmarkIntervalHours = settingsRepository.scheduledBenchmarkIntervalHours
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 24)
    val scheduledBenchmarkCount = settingsRepository.scheduledBenchmarkCount
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 100)

    // UI Operation States
    val isCollecting = proxyRepository.refreshInProgress

    private val _isVerifying = MutableStateFlow(false)
    val isVerifying = _isVerifying.asStateFlow()

    private val _isVerificationPaused = MutableStateFlow(false)
    val isVerificationPaused = _isVerificationPaused.asStateFlow()

    val benchmarkRuntimeState = benchmarkCoordinator.state
    val persistedBenchmarkRun: StateFlow<BenchmarkRunRecord?> = benchmarkCoordinator.persistedRun
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val telegramTransferState = telegramTransferEngine.state

    private val _verificationProgress = MutableStateFlow(0f)
    val verificationProgress = _verificationProgress.asStateFlow()

    private val _verificationCompletedCount = MutableStateFlow(0)
    val verificationCompletedCount = _verificationCompletedCount.asStateFlow()

    private val _verificationTotalCount = MutableStateFlow(0)
    val verificationTotalCount = _verificationTotalCount.asStateFlow()

    private val _benchmarkLimit = MutableStateFlow(100)
    val benchmarkLimit = _benchmarkLimit.asStateFlow()

    private val _benchmarkSession = MutableStateFlow(BenchmarkSessionState())
    val benchmarkSession = _benchmarkSession.asStateFlow()

    private val _currentVerifyingProxy = MutableStateFlow<MtProtoProxy?>(null)
    val currentVerifyingProxy = _currentVerifyingProxy.asStateFlow()

    private val _liveTestingProxyUrl = MutableStateFlow<String?>(null)
    val liveTestingProxyUrl = _liveTestingProxyUrl.asStateFlow()

    private val _networkSpeed = MutableStateFlow(NetworkSpeedUiState())
    val networkSpeed = _networkSpeed.asStateFlow()

    private val _favoriteSyncingProxyUrls = MutableStateFlow<Set<String>>(emptySet())
    val favoriteSyncingProxyUrls = _favoriteSyncingProxyUrls.asStateFlow()

    private val _messages = MutableSharedFlow<UserMessage>(extraBufferCapacity = 1)
    val messages = _messages.asSharedFlow()

    private var collectionJob: Job? = null
    private var liveTestingJob: Job? = null
    private var networkSpeedJob: Job? = null
    private var telegramOperationJob: Job? = null
    private val manualTransferTracker = ManualTransferTracker()
    val manualTransferProxyUrl = manualTransferTracker.activeProxyUrl

    val sourceFetchResults = com.example.data.network.ProxySourceManager.fetchResults
    val proxySources = com.example.data.network.ProxySourceManager.activeSources
    val sourceCatalogState = com.example.data.network.ProxySourceManager.catalogState
    val sourceHealth = proxyRepository.sourceHealth
    val sourceStats7Days = proxyRepository.sourceStats7Days
    val sourceStats30Days = proxyRepository.sourceStats30Days
    val activeFavorites = proxyRepository.activeFavorites
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val privateNodes = proxyRepository.privateNodes
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _comparisonUrls = MutableStateFlow<List<String>>(emptyList())
    val comparisonProxies = observeComparedProxies(
        _comparisonUrls, proxyRepository::observeComparisonProxy
    ).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _privateNodeTestingUrls = MutableStateFlow<Set<String>>(emptySet())
    val privateNodeTestingUrls = _privateNodeTestingUrls.asStateFlow()

    init {
        observeBenchmarkRuntime()
        observeRefreshCommits()
        observeListOrderCommits()
        observePendingFavoriteSync()
        initializeTelegramTransferMode()
        observeCurrentNetwork()
        observeNetworkSpeedBaseline()
    }

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun updateStatusFilter(filter: StatusFilter) {
        _statusFilter.value = filter
    }

    fun updateSortBy(sort: SortBy) {
        _sortBy.value = sort
    }

    fun updateBenchmarkLimit(limit: Int) {
        _benchmarkLimit.value = limit.coerceAtLeast(1)
    }

    fun selectProxy(proxy: MtProtoProxy) {
        _selectedProxyUrl.value = proxy.url
    }

    fun toggleComparison(proxy: MtProtoProxy) {
        val current = _comparisonUrls.value
        _comparisonUrls.value = if (proxy.url in current) {
            current.filterNot { it == proxy.url }
        } else {
            (current + proxy.url).takeLast(MAX_COMPARISON_PROXIES)
        }
    }

    fun clearComparison() {
        _comparisonUrls.value = emptyList()
    }

    fun compareTopRecommendations() {
        val top = benchmarkHistory.value.previewRows
            .asSequence()
            .filter { it.lastChecked > 0L && it.isTelegramVerified }
            .take(3)
            .toList()
        _comparisonUrls.value = top.map(MtProtoProxy::url)
        if (top.isEmpty()) {
            _messages.tryEmit(
                UserMessage(
                    english = "No verified benchmark result is ready for comparison.",
                    persian = "هنوز نتیجهٔ تأییدشده‌ای برای مقایسه آماده نیست."
                )
            )
        }
    }

    fun updateFavoriteMetadata(url: String, folder: String, tags: String, note: String) {
        launchOperation("update favorite metadata") {
            check(proxyRepository.updateFavoriteMetadata(url, folder, tags, note)) {
                "The favorite is no longer available."
            }
        }
    }

    fun exportVaultProfile() {
        launchOperation(
            "export vault profile",
            dispatcher = Dispatchers.IO,
            failureMessage = UserMessage(
                "Couldn't export the Vault. A profile supports up to 1,000 entries per type and 1 MiB.",
                "خروجی گنجینه ساخته نشد. هر فایل حداکثر ۱۰۰۰ مورد از هر نوع و ۱ مگابایت را پشتیبانی می‌کند."
            )
        ) {
            val json = ProxyProfileCodec.encode(
                favorites = proxyRepository.getActiveFavoritesSnapshot(),
                privateNodes = proxyRepository.getPrivateNodesSnapshot()
            )
            val exportDirectory = File(appContext.cacheDir, "profile-exports").apply { mkdirs() }
            exportDirectory.listFiles()?.forEach { file ->
                if (System.currentTimeMillis() - file.lastModified() > PROFILE_EXPORT_RETENTION_MS) {
                    file.delete()
                }
            }
            val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val file = File.createTempFile("ProxyX-profile-$timestamp-", ".json", exportDirectory)
            file.writeText(json, Charsets.UTF_8)
            val uri = FileProvider.getUriForFile(
                appContext,
                "${appContext.packageName}.files",
                file
            )
            val share = Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "application/json"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    clipData = android.content.ClipData.newRawUri("ProxyX profile", uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },
                "Export ProxyX profile"
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            withContext(Dispatchers.Main.immediate) { appContext.startActivity(share) }
        }
    }

    fun importVaultProfile(uri: Uri) {
        launchOperation(
            "import vault profile",
            dispatcher = Dispatchers.IO,
            failureMessage = UserMessage(
                "Couldn't import the Vault. Select an intact ProxyX profile of at most 1 MiB and retry.",
                "ورود گنجینه انجام نشد. یک فایل سالم خروجی ProxyX با حجم حداکثر ۱ مگابایت انتخاب کنید."
            )
        ) {
            val profile = appContext.contentResolver.openInputStream(uri)
                ?.use { ProxyProfileCodec.decode(it) }
                ?: error("The selected profile could not be opened.")
            var imported = 0
            profile.favorites.forEach { favorite ->
                if (proxyRepository.importFavorite(favorite)) imported += 1
            }
            profile.privateNodes.forEach { node ->
                try {
                    proxyRepository.addPrivateNode(node.url, node.label)
                    proxyRepository.setPrivateNodeEnabled(node.url, node.enabled)
                    imported += 1
                } catch (_: IllegalArgumentException) {
                    // Invalid links are skipped; cancellation and storage failures must propagate.
                }
            }
            _messages.emit(
                UserMessage(
                    english = "Imported $imported local Vault entries. Telegram session data was not included.",
                    persian = "$imported مورد محلی وارد گنجینه شد؛ اطلاعات نشست تلگرام در فایل وجود نداشت."
                )
            )
        }
    }

    fun exportDiagnosticReport() {
        launchOperation("export diagnostic report", dispatcher = Dispatchers.IO) {
            val counts = proxyRepository.benchmarkCounts.first()
            val activeSourceUrls = proxySources.value.filter { it.isEnabled }.map { it.url }.toSet()
            val sources = proxyRepository.sourceHealth.first().filter { it.sourceUrl in activeSourceUrls }
            val network = proxyRepository.currentNetworkInfo()
            val benchmark = persistedBenchmarkRun.value
            val telegram = telegramTransferState.value
            val report = buildString {
                appendLine("ProxyX redacted diagnostic report")
                appendLine("version=${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                appendLine("android=${Build.VERSION.SDK_INT}")
                appendLine("device=${Build.MANUFACTURER.take(40)} ${Build.MODEL.take(60)}")
                appendLine("network=${network?.type ?: "UNAVAILABLE"}")
                appendLine("metered=${network?.isMetered ?: true}")
                appendLine("proxies=${totalProxyCount.value}")
                appendLine("tested=${counts.testedCount}")
                appendLine("telegramReady=${counts.readyCount}")
                appendLine("telegramVerified=${counts.verifiedCount}")
                appendLine("tcpOnly=${counts.tcpOnlyCount}")
                appendLine("sources=${sources.size}")
                appendLine("sourcesSuccessfulLatestFetch=${sources.count { it.hasSuccessfulLatestFetch }}")
                appendLine("sourcesQuarantined=${sources.count { it.isQuarantined }}")
                appendLine("sourceAverageQuality=${sources.map { it.qualityScore }.average().takeIf(Double::isFinite) ?: 0.0}")
                appendLine("benchmarkStatus=${benchmark?.status ?: "NONE"}")
                appendLine("benchmarkFailure=${benchmark?.failure.orEmpty().take(80)}")
                appendLine("tdlibRuntime=${telegram.runtimeAvailable}")
                appendLine("tdlibConfigured=${telegram.credentialsConfigured}")
                appendLine("tdlibState=${telegram.step}")
                appendLine("redaction=No proxy secret, phone number, API hash, TDLib database or session file is included.")
            }
            val directory = File(appContext.cacheDir, "diagnostics").apply { mkdirs() }
            val file = File.createTempFile("ProxyX-diagnostic-", ".txt", directory)
            file.writeText(report, Charsets.UTF_8)
            val uri = FileProvider.getUriForFile(appContext, "${appContext.packageName}.files", file)
            val share = Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    clipData = android.content.ClipData.newRawUri("ProxyX diagnostics", uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },
                "Share ProxyX diagnostics"
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            withContext(Dispatchers.Main.immediate) { appContext.startActivity(share) }
        }
    }

    fun shareProxyQr(url: String) {
        launchOperation("share proxy QR") {
            val proxy = requireNotNull(MtProtoProxy.parseFromUrl(url)) { "Invalid MTProto proxy link." }
            ProxyQrSharer.share(appContext, proxy.url)
        }
    }

    fun addPrivateNode(url: String, label: String) {
        launchOperation("add private proxy") {
            proxyRepository.addPrivateNode(url, label)
            _messages.emit(
                UserMessage(
                    english = "Private MTProto node added locally.",
                    persian = "نود خصوصی MTProto به‌صورت محلی اضافه شد."
                )
            )
        }
    }

    fun setPrivateNodeEnabled(url: String, enabled: Boolean) {
        launchOperation("update private proxy") {
            proxyRepository.setPrivateNodeEnabled(url, enabled)
        }
    }

    fun setPrivateNodeMonitoringEnabled(enabled: Boolean) {
        launchOperation("update private node monitoring") {
            settingsRepository.setPrivateNodeMonitoringEnabled(enabled)
        }
    }

    fun deletePrivateNode(url: String) {
        launchOperation("delete private proxy") {
            proxyRepository.deletePrivateNode(url)
        }
    }

    fun testPrivateNode(url: String) {
        if (isProxyDataBusy()) { emitBusyMessage(); return }
        if (url in _privateNodeTestingUrls.value) return
        _privateNodeTestingUrls.value += url
        viewModelScope.launch {
            try {
                requireAllowedNetwork()
                val result = proxyRepository.testPrivateNode(url, timeoutMs.value)
                val passed = result?.verificationStatus in setOf(
                    MtProtoProxy.VERIFICATION_TELEGRAM_READY,
                    MtProtoProxy.VERIFICATION_MTPROTO_VERIFIED
                )
                _messages.emit(
                    UserMessage(
                        english = if (passed) "Private node verified on this network."
                        else "Private node did not pass strict MTProto verification.",
                        persian = if (passed) "نود خصوصی روی این اینترنت تأیید شد."
                        else "نود خصوصی در آزمون سخت‌گیرانه MTProto تأیید نشد."
                    )
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: UserNetworkException) {
                emitNetworkMeasurementError(error)
            } catch (error: Exception) {
                Log.e(TAG, "Unable to test private node.", error)
                emitUnexpectedError()
            } finally {
                _privateNodeTestingUrls.value -= url
            }
        }
    }

    private fun observeCurrentNetwork() {
        viewModelScope.launch {
            while (true) {
                val previous = _currentNetworkInfo.value?.fingerprint
                val current = proxyRepository.currentNetworkInfo()
                if (previous != current?.fingerprint) {
                    _networkSpeed.update { NetworkSpeedUiState(isRunning = it.isRunning) }
                }
                _currentNetworkInfo.value = current
                val now = android.os.SystemClock.elapsedRealtime()
                if (!previous.isNullOrBlank() && current != null && current.fingerprint != previous &&
                    settingsRepository.networkRecheckEnabled.first() && !isProxyDataBusy() &&
                    now - lastAutomaticNetworkRecheckAt > 60_000L) {
                    lastAutomaticNetworkRecheckAt = now
                    recheckNetworkCandidates()
                }
                delay(NETWORK_IDENTITY_REFRESH_MS)
            }
        }
    }

    fun setNetworkRecheckEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setNetworkRecheckEnabled(enabled) }
    }

    fun recheckNetworkCandidates() {
        if (isProxyDataBusy()) { emitBusyMessage(); return }
        val network = _currentNetworkInfo.value ?: return
        if (unmeteredOnly.value && network.isMetered) {
            _messages.tryEmit(UserMessage("Unmetered network required.", "شبکه نامحدود لازم است."))
            return
        }
        _networkRechecking.value = true
        _liveTestingProxyUrl.value = "network-shortlist"
        liveTestingJob = viewModelScope.launch {
            try {
                val count = proxyRepository.recheckNetworkShortlist(network.fingerprint, timeoutMs.value) {
                    _liveTestingProxyUrl.value = it.url
                }
                _messages.emit(UserMessage("Rechecked $count saved candidates on this network.",
                    "$count گزینه از سابقه همین شبکه دوباره بررسی شد."))
            } catch (error: kotlinx.coroutines.TimeoutCancellationException) {
                _messages.emit(UserMessage("Shortlist check reached its time limit.", "زمان بررسی کوتاه شبکه تمام شد."))
            } catch (error: CancellationException) {
                throw error
            } catch (error: UserNetworkException) {
                emitNetworkMeasurementError()
            } catch (error: Exception) {
                Log.w(TAG, "Network shortlist recheck failed.", error)
                emitUnexpectedError()
            } finally {
                _networkRechecking.value = false
                _liveTestingProxyUrl.value = null
                liveTestingJob = null
            }
        }
    }

    private fun observeNetworkSpeedBaseline() {
        viewModelScope.launch {
            currentNetworkBaseline.collect { baseline ->
                _networkSpeed.update { it.withBaseline(baseline) }
            }
        }
    }

    // Operations
    fun collectProxies() {
        if (isProxyDataBusy() || collectionJob?.isActive == true) {
            emitBusyMessage()
            return
        }
        collectionJob = viewModelScope.launch {
            try {
                requireAllowedNetwork()
                val result = proxyRepository.collectAndCacheProxies { insertedCount ->
                    Log.d(TAG, "Displayed $insertedCount fresh proxies during refresh.")
                }
                if (result.cacheReplaced) {
                    val englishMessage = if (result.completed) {
                        "Loaded ${result.proxyCount} fresh proxies."
                    } else {
                        "Kept ${result.proxyCount} fresh proxies before refresh stopped."
                    }
                    val persianMessage = if (result.completed) {
                        "${result.proxyCount} پروکسی تازه دریافت شد."
                    } else {
                        "پیش از توقف دریافت، ${result.proxyCount} پروکسی تازه حفظ شد."
                    }
                    _messages.emit(
                        UserMessage(
                            english = englishMessage,
                            persian = persianMessage
                        )
                    )
                } else {
                    _messages.emit(
                        UserMessage(
                            english = "No source returned a valid proxy. The previous list was kept.",
                            persian = "هیچ منبعی پروکسی معتبر نداد؛ فهرست قبلی حفظ شد."
                        )
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: UserNetworkException) {
                Log.w(TAG, "Active network changed during source refresh.", e)
                emitNetworkMeasurementError(e)
            } catch (e: Exception) {
                Log.e(TAG, "Unable to refresh proxies.", e)
                emitUnexpectedError()
            } finally {
                collectionJob = null
            }
        }
    }

    fun startVerification(
        accuracyOverride: BenchmarkAccuracyMode? = null,
        allowTelegramTransfers: Boolean = true
    ) {
        if (isProxyDataBusy()) {
            emitBusyMessage()
            return
        }
        val selectedCount = minOf(_benchmarkLimit.value, totalProxyCount.value)
        if (selectedCount <= 0) {
            _messages.tryEmit(
                UserMessage(
                    english = "Collect at least one proxy before starting a benchmark.",
                    persian = "پیش از شروع بنچ‌مارک حداقل یک پروکسی دریافت کنید."
                )
            )
            return
        }
        _isVerifying.value = true
        _isVerificationPaused.value = false
        _verificationProgress.value = 0f
        _verificationCompletedCount.value = 0
        _verificationTotalCount.value = selectedCount
        _benchmarkSession.value = BenchmarkSessionState(totalCount = _verificationTotalCount.value)
        try {
            val selectedAccuracyMode = accuracyOverride ?: benchmarkAccuracyMode.value
            check(BenchmarkExecution.start(
                context = appContext,
                count = selectedCount,
                runTelegramFinalistTransfers = allowTelegramTransfers &&
                    telegramFinalistTransferEnabled.value,
                accuracyMode = selectedAccuracyMode,
                workers = parallelWorkers.value,
                timeoutMs = timeoutMs.value,
                telegramBudgetMb = telegramTransferDataBudgetMb.value
            )) { "Android did not schedule the benchmark." }
        } catch (error: RuntimeException) {
            Log.e(TAG, "Unable to start benchmark foreground service.", error)
            _isVerifying.value = false
            _isVerificationPaused.value = false
            _verificationProgress.value = 0f
            _verificationCompletedCount.value = 0
            _verificationTotalCount.value = 0
            _benchmarkSession.value = BenchmarkSessionState()
            _messages.tryEmit(
                UserMessage(
                    english = "Android could not start the benchmark service. Keep ProxyX open and retry.",
                    persian = "اندروید نتوانست سرویس بنچ‌مارک را اجرا کند؛ ProxyX را باز نگه دارید و دوباره تلاش کنید."
                )
            )
        }
    }

    fun cancelVerification() {
        if (!_isVerifying.value) return
        val wasOnlyQueued = !benchmarkCoordinator.state.value.isActive
        BenchmarkExecution.cancel(appContext)
        benchmarkCoordinator.cancel()
        if (wasOnlyQueued) {
            _isVerifying.value = false
            _isVerificationPaused.value = false
            _verificationProgress.value = 0f
            _verificationCompletedCount.value = 0
            _verificationTotalCount.value = 0
            _currentVerifyingProxy.value = null
            _benchmarkSession.value = BenchmarkSessionState()
        }
    }

    fun pauseVerification() {
        if (_isVerifying.value && !_isVerificationPaused.value) {
            benchmarkCoordinator.pause()
        }
    }

    fun resumeVerification() {
        if (_isVerifying.value && _isVerificationPaused.value) {
            benchmarkCoordinator.resume()
        }
    }

    fun toggleFavorite(proxy: MtProtoProxy) {
        if (isProxyDataBusy()) {
            emitBusyMessage()
            return
        }
        if (proxy.url in _favoriteSyncingProxyUrls.value) return
        _favoriteSyncingProxyUrls.value += proxy.url
        viewModelScope.launch {
            try {
                val storedProxy = proxyRepository.getProxy(proxy.url)
                    ?: throw IllegalStateException("The proxy is no longer in the active list.")
                val shouldFavorite = !storedProxy.isFavorite
                if (shouldFavorite) {
                    addFavorite(storedProxy.copy(isFavorite = true))
                } else {
                    proxyRepository.setFavorite(
                        proxy = storedProxy,
                        isFavorite = false,
                        syncState = FavoriteProxy.SYNC_LOCAL_ONLY
                    )
                    _messages.emit(
                        UserMessage(
                            english = "Removed from local favorites. Its existing Saved Messages copy was kept.",
                            persian = "از علاقه‌مندی‌های محلی حذف شد؛ نسخه قبلی در پیام‌های ذخیره‌شده باقی ماند."
                        )
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.e(TAG, "Unable to toggle favorite.", error)
                emitUnexpectedError()
            } finally {
                _favoriteSyncingProxyUrls.value -= proxy.url
            }
        }
    }

    private suspend fun addFavorite(proxy: MtProtoProxy) {
        if (telegramTransferState.value.step != TelegramAuthorizationStep.READY) {
            proxyRepository.setFavorite(
                proxy = proxy,
                isFavorite = true,
                syncState = FavoriteProxy.SYNC_LOCAL_ONLY
            )
            _messages.emit(
                UserMessage(
                    english = "Saved locally. Sign in to Telegram transfer mode to also save it in Saved Messages.",
                    persian = "محلی ذخیره شد؛ برای ارسال به پیام‌های ذخیره‌شده، یک‌بار وارد حالت تلگرام شوید."
                )
            )
            return
        }
        val favorite = proxyRepository.setFavorite(
            proxy = proxy,
            isFavorite = true,
            syncState = FavoriteProxy.SYNC_PENDING
        )
        val routeCandidates = proxyRepository.getTelegramRouteRecoveryCandidates(
            TELEGRAM_AUTHORIZATION_CANDIDATE_LIMIT
        )
        val messageId = try {
            telegramTransferEngine.saveFavoriteProxy(
                proxy = proxy,
                isPersian = usesPersianLanguage(),
                existingMessageId = favorite.savedMessageId,
                routeCandidates = routeCandidates
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.e(TAG, "Unable to sync favorite to Telegram Saved Messages.", error)
            proxyRepository.markFavoriteSyncFailed(proxy.url, "DELIVERY_NOT_VERIFIED")
            _messages.emit(
                UserMessage(
                    english = "Saved locally. Telegram delivery was not verified and will be retried safely.",
                    persian = "محلی ذخیره شد؛ تحویل تلگرام تأیید نشد و بعداً بدون ارسال تکراری دوباره تلاش می‌شود."
                )
            )
            return
        }
        proxyRepository.markFavoriteSynced(proxy.url, messageId)
        Log.i(TAG, "Favorite delivery verified in Telegram Saved Messages.")
        _messages.emit(
            UserMessage(
                english = "Favorite saved locally and delivered to Telegram Saved Messages.",
                persian = "پروکسی هم محلی و هم در پیام‌های ذخیره‌شده تلگرام ذخیره شد."
            )
        )
    }

    private fun usesPersianLanguage(): Boolean {
        return when (language.value) {
            "fa" -> true
            "en" -> false
            else -> appContext.resources.configuration.locales[0].language == "fa"
        }
    }

    private fun observePendingFavoriteSync() {
        viewModelScope.launch {
            telegramTransferState
                .map { it.step }
                .distinctUntilChanged()
                .collect { step ->
                    if (step == TelegramAuthorizationStep.READY) syncPendingFavorites()
                }
        }
    }

    private suspend fun syncPendingFavorites() {
        val pending = proxyRepository.getFavoritesNeedingSync()
        val routeCandidates = if (pending.isEmpty()) {
            emptyList()
        } else {
            proxyRepository.getTelegramRouteRecoveryCandidates(
                TELEGRAM_AUTHORIZATION_CANDIDATE_LIMIT
            )
        }
        var syncedCount = 0
        pending.forEach { favorite ->
            if (telegramTransferState.value.step != TelegramAuthorizationStep.READY) return@forEach
            _favoriteSyncingProxyUrls.value += favorite.url
            try {
                val messageId = telegramTransferEngine.saveFavoriteProxy(
                    proxy = proxyRepository.getProxy(favorite.url) ?: favorite.asFreshProxy(),
                    isPersian = usesPersianLanguage(),
                    existingMessageId = favorite.savedMessageId,
                    routeCandidates = routeCandidates
                )
                if (proxyRepository.markFavoriteSynced(favorite.url, messageId)) syncedCount += 1
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(TAG, "Pending favorite delivery remains queued.", error)
                proxyRepository.markFavoriteSyncFailed(favorite.url, "DELIVERY_NOT_VERIFIED")
            } finally {
                _favoriteSyncingProxyUrls.value -= favorite.url
            }
        }
        if (syncedCount > 0) {
            _messages.emit(
                UserMessage(
                    english = "$syncedCount queued favorites were delivered to Saved Messages.",
                    persian = "$syncedCount علاقه‌مندی در صف به پیام‌های ذخیره‌شده ارسال شد."
                )
            )
        }
    }

    fun verifyProxyOnCurrentNetwork(
        proxy: MtProtoProxy,
        onResult: (MtProtoProxy?) -> Unit
    ) {
        if (isProxyDataBusy() || liveTestingJob?.isActive == true) {
            emitBusyMessage()
            onResult(null)
            return
        }

        selectProxy(proxy)
        _liveTestingProxyUrl.value = proxy.url
        liveTestingJob = viewModelScope.launch {
            try {
                requireAllowedNetwork()
                val timeout = timeoutMs.value
                val verifiedProxy = proxyRepository.verifySingleProxy(
                    url = proxy.url,
                    timeoutMs = timeout,
                    attempts = com.example.data.network.ProxyVerifier.STRICT_TCP_ATTEMPTS
                )
                verifiedProxy?.let { selectProxy(it) }
                onResult(verifiedProxy)
            } catch (e: CancellationException) {
                throw e
            } catch (e: UserNetworkException) {
                Log.w(TAG, "Active network changed during proxy test.", e)
                emitNetworkMeasurementError(e)
                onResult(null)
            } catch (e: Exception) {
                Log.e(TAG, "Unable to test ${proxy.server}.", e)
                onResult(null)
            } finally {
                _liveTestingProxyUrl.value = null
                liveTestingJob = null
            }
        }
    }

    fun cancelLiveTest() {
        val job = liveTestingJob
        if (job?.isActive == true) {
            job.cancel()
            _messages.tryEmit(
                UserMessage(
                    english = "Proxy test cancelled.",
                    persian = "تست پروکسی لغو شد."
                )
            )
        }
    }

    fun measureCurrentNetworkSpeed() {
        if (isProxyDataBusy() || networkSpeedJob?.isActive == true) {
            emitBusyMessage()
            return
        }
        _networkSpeed.value = _networkSpeed.value.copy(isRunning = true, failed = false)
        networkSpeedJob = viewModelScope.launch {
            try {
                requireAllowedNetwork()
                val result = proxyRepository.measureCurrentNetworkSpeed()
                _networkSpeed.value = if (result == null) {
                    _networkSpeed.value.copy(isRunning = false, failed = true)
                } else {
                    NetworkSpeedUiState(
                        isRunning = false,
                        downloadKbps = result.downloadKbps,
                        uploadKbps = result.uploadKbps,
                        measuredAt = result.measuredAt,
                        downloadSampleBytes = result.downloadSampleBytes,
                        uploadSampleBytes = result.uploadSampleBytes,
                        failed = false
                    )
                }
                if (result == null) {
                    _messages.emit(
                        UserMessage(
                            english = "The direct Internet speed test failed. No fallback value was used.",
                            persian = "تست مستقیم سرعت اینترنت ناموفق بود؛ هیچ مقدار جایگزین یا تخمینی نمایش داده نشد."
                        )
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: UserNetworkException) {
                _networkSpeed.value = _networkSpeed.value.copy(isRunning = false, failed = true)
                emitNetworkMeasurementError(e)
            } catch (e: Exception) {
                Log.e(TAG, "Unable to measure the current network.", e)
                _networkSpeed.value = _networkSpeed.value.copy(isRunning = false, failed = true)
                emitUnexpectedError()
            } finally {
                _networkSpeed.value = _networkSpeed.value.copy(isRunning = false)
                networkSpeedJob = null
            }
        }
    }

    fun cancelNetworkSpeedTest() {
        val job = networkSpeedJob
        if (job?.isActive == true) {
            job.cancel()
            _messages.tryEmit(
                UserMessage(
                    english = "Internet speed test cancelled.",
                    persian = "تست سرعت اینترنت لغو شد."
                )
            )
        }
    }

    fun markProxyAsConnected(proxy: MtProtoProxy) {
        launchOperation("mark proxy used") {
            proxyRepository.markProxyUsed(proxy.url)
        }
    }

    fun clearDatabase() {
        if (isProxyDataBusy()) {
            emitBusyMessage()
            return
        }
        launchOperation("clear proxy database") {
            val deletedCount = proxyRepository.clearAllData()
            genericProxies.clear()
            resetForFreshProxySnapshot()
            _benchmarkRevision.value += 1
            _messages.emit(
                UserMessage(
                    english = "Removed $deletedCount stored proxies.",
                    persian = "$deletedCount پروکسی ذخیره‌شده حذف شد."
                )
            )
        }
    }

    fun deleteDeadProxies() {
        if (isProxyDataBusy()) {
            emitBusyMessage()
            return
        }
        launchOperation("prune offline proxies") {
            val deletedCount = proxyRepository.deleteDeadProxies()
            _selectedProxyUrl.value = null
            _messages.emit(
                UserMessage(
                    english = "Removed $deletedCount tested offline proxies.",
                    persian = "$deletedCount پروکسی آفلاینِ تست‌شده حذف شد."
                )
            )
        }
    }

    // Settings modifiers
    fun setTheme(themeVal: String) {
        launchOperation("save theme") { settingsRepository.setTheme(themeVal) }
    }

    fun setAutoRefreshInterval(minutes: Int) {
        launchOperation("save refresh interval") {
            settingsRepository.setAutoRefreshInterval(minutes)
        }
    }

    fun setAutoRefreshEnabled(enabled: Boolean) {
        launchOperation("save auto refresh state") {
            settingsRepository.setAutoRefreshEnabled(enabled)
        }
    }

    fun setTimeoutMs(timeout: Int) {
        launchOperation("save timeout") { settingsRepository.setTimeoutMs(timeout) }
    }

    fun setParallelWorkers(workers: Int) {
        launchOperation("save parallel workers") {
            settingsRepository.setParallelWorkers(workers)
        }
    }

    fun setAdaptiveWorkersEnabled(enabled: Boolean) {
        launchOperation("save adaptive worker state") {
            settingsRepository.setAdaptiveWorkersEnabled(enabled)
        }
    }

    fun setUnmeteredOnly(enabled: Boolean) {
        launchOperation("save network usage policy") {
            settingsRepository.setUnmeteredOnly(enabled)
        }
    }

    fun setTelegramFinalistTransferEnabled(enabled: Boolean) {
        if (enabled && telegramTransferState.value.step != TelegramAuthorizationStep.READY) {
            _messages.tryEmit(
                UserMessage(
                    english = "Sign in to Telegram transfer mode before enabling finalist transfers.",
                    persian = "برای فعال‌کردن انتقال واقعی فینالیست‌ها ابتدا وارد حالت تلگرام شوید."
                )
            )
            return
        }
        launchOperation("save Telegram finalist transfer preference") {
            settingsRepository.setTelegramFinalistTransferEnabled(enabled)
        }
    }

    fun setTelegramTransferDataBudgetMb(budgetMb: Int) {
        launchOperation("save Telegram transfer data budget") {
            settingsRepository.setTelegramTransferDataBudgetMb(budgetMb)
        }
    }

    fun setBenchmarkAccuracyMode(mode: BenchmarkAccuracyMode) {
        launchOperation("save benchmark accuracy") {
            settingsRepository.setBenchmarkAccuracyMode(mode)
        }
    }

    fun setAutoResumeBenchmark(enabled: Boolean) {
        launchOperation("save benchmark recovery setting") {
            settingsRepository.setAutoResumeBenchmark(enabled)
        }
    }

    fun setScheduledBenchmarkEnabled(enabled: Boolean) {
        launchOperation("save scheduled benchmark setting") {
            settingsRepository.setScheduledBenchmarkEnabled(enabled)
        }
    }

    fun setScheduledBenchmarkIntervalHours(hours: Int) {
        launchOperation("save scheduled benchmark interval") {
            settingsRepository.setScheduledBenchmarkIntervalHours(hours)
        }
    }

    fun setScheduledBenchmarkCount(count: Int) {
        launchOperation("save scheduled benchmark count") {
            settingsRepository.setScheduledBenchmarkCount(count)
        }
    }

    fun restartInterruptedBenchmark() {
        val interrupted = persistedBenchmarkRun.value
            ?.takeIf { it.status == BenchmarkRunStatus.INTERRUPTED.name }
            ?: return
        updateBenchmarkLimit(interrupted.requestedCount)
        startVerification(
            accuracyOverride = BenchmarkAccuracyMode.fromStoredValue(interrupted.accuracyMode),
            allowTelegramTransfers = false
        )
    }

    fun initializeTelegramTransferMode() {
        launchTelegramOperation("initialize Telegram transfer mode") {
            telegramTransferEngine.initialize()
        }
    }

    fun submitTelegramPhone(phone: String) {
        launchTelegramOperation("submit Telegram phone number") {
            val selectedUrl = _selectedProxyUrl.value
            val selected = if (selectedUrl == null) {
                null
            } else {
                proxyRepository.getProxy(selectedUrl)
                    ?.takeIf(MtProtoProxy::isTelegramVerified)
            }
            val rankedCandidates = proxyRepository.getTelegramAuthorizationCandidates(
                TELEGRAM_AUTHORIZATION_CANDIDATE_LIMIT
            )
            val candidates = buildList {
                selected?.let(::add)
                addAll(rankedCandidates)
            }.distinctBy(MtProtoProxy::url)
            telegramTransferEngine.submitPhoneNumber(phone, candidates)
        }
    }

    fun submitTelegramEmail(email: String) {
        launchTelegramOperation("submit Telegram email") {
            telegramTransferEngine.submitEmailAddress(email)
        }
    }

    fun submitTelegramCode(code: String) {
        launchTelegramOperation("submit Telegram login code") {
            when (telegramTransferState.value.step) {
                TelegramAuthorizationStep.WAIT_EMAIL_CODE ->
                    telegramTransferEngine.submitEmailCode(code)
                else -> telegramTransferEngine.submitAuthenticationCode(code)
            }
        }
    }

    fun resendTelegramCode() {
        launchTelegramOperation("resend Telegram login code") {
            telegramTransferEngine.resendAuthenticationCode()
        }
    }

    fun submitTelegramPassword(password: String) {
        launchTelegramOperation("submit Telegram password") {
            telegramTransferEngine.submitPassword(password)
        }
    }

    fun registerTelegramUser(firstName: String, lastName: String) {
        launchTelegramOperation("register Telegram user") {
            telegramTransferEngine.register(firstName, lastName)
        }
    }

    fun runRealTelegramTransfer(proxy: MtProtoProxy) {
        if (isProxyDataBusy() || telegramOperationJob?.isActive == true) {
            emitBusyMessage()
            return
        }
        selectProxy(proxy)
        launchTelegramOperation("run real Telegram transfer") {
            val result = manualTransferTracker.track(proxy.url) {
                check(proxyRepository.beginForegroundBenchmark()) { "Another proxy operation is running." }
                try {
                    requireAllowedNetwork()
                    val current = proxyRepository.currentNetworkInfo()
                        ?: error("No active Internet connection.")
                    check(!unmeteredOnly.value || !current.isMetered) { "Unmetered network required." }
                    val stored = proxyRepository.getProxy(proxy.url)
                        ?: error("The proxy is no longer in the current list.")
                    val candidate = if (stored.networkFingerprint != current.fingerprint) {
                        proxyRepository.verifySingleProxy(proxy.url, timeoutMs.value)
                            ?: error("The proxy is no longer in the current list.")
                    } else stored
                    check(proxyRepository.clearTelegramTransfer(candidate) == 1) {
                        "The proxy or network changed. Retest on the current network."
                    }
                    val measured = try {
                        telegramTransferEngine.measureAdaptiveTransfer(candidate)
                    } catch (error: kotlinx.coroutines.CancellationException) {
                        throw error
                    } catch (error: com.example.data.network.UserNetworkException) {
                        throw error
                    } catch (error: Exception) {
                        proxyRepository.markTelegramTransferFailed(candidate)
                        throw error
                    }
                    measured.also {
                        check(proxyRepository.persistTelegramTransfer(it)) {
                            "The proxy or network changed. Retest on the current network."
                        }
                    }
                } finally { proxyRepository.endForegroundBenchmark() }
            }
            _messages.emit(
                UserMessage(
                    english = "Real Telegram transfer: ${result.downloadKbps} KiB/s down, ${result.uploadKbps} KiB/s up.",
                    persian = "انتقال واقعی تلگرام: دانلود ${result.downloadKbps} و آپلود ${result.uploadKbps} کیلوبایت‌برثانیه."
                )
            )
        }
    }

    fun logoutTelegramTransferMode() {
        launchTelegramOperation("log out of Telegram transfer mode") {
            settingsRepository.setTelegramFinalistTransferEnabled(false)
            telegramTransferEngine.logout()
        }
    }

    fun cancelTelegramTransfer() {
        if (manualTransferProxyUrl.value != null ||
            telegramTransferState.value.step == TelegramAuthorizationStep.TRANSFERRING
        ) {
            telegramOperationJob?.cancel()
        }
    }

    private fun launchTelegramOperation(name: String, operation: suspend () -> Unit) {
        if (telegramOperationJob?.isActive == true) {
            emitBusyMessage()
            return
        }
        telegramOperationJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                operation()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.e(TAG, "Unable to $name.", error)
                _messages.emit(
                    UserMessage(
                        english = error.message ?: "Telegram transfer operation failed.",
                        persian = error.message?.let { "عملیات تلگرام ناموفق بود: $it" }
                            ?: "عملیات انتقال واقعی تلگرام ناموفق بود."
                    )
                )
            } finally {
                telegramOperationJob = null
            }
        }
    }

    fun setNotificationsEnabled(enabled: Boolean) {
        launchOperation("save notification preference") {
            settingsRepository.setNotificationsEnabled(enabled)
        }
    }

    fun setLanguage(lang: String) {
        launchOperation("save language") { settingsRepository.setLanguage(lang) }
    }

    fun markChannelPromptSeen() {
        launchOperation("save channel prompt state") {
            settingsRepository.markChannelPromptSeen()
        }
    }

    private fun launchOperation(
        name: String,
        dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
        failureMessage: UserMessage? = null,
        operation: suspend () -> Unit
    ) {
        viewModelScope.launch(dispatcher) {
            try {
                operation()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Unable to $name.", e)
                if (failureMessage != null) _messages.emit(failureMessage) else emitUnexpectedError()
            }
        }
    }

    private suspend fun emitUnexpectedError() {
        _messages.emit(
            UserMessage(
                english = "The operation failed. Check your connection and try again.",
                persian = "عملیات ناموفق بود؛ اتصال اینترنت را بررسی و دوباره تلاش کنید."
            )
        )
    }

    private suspend fun requireAllowedNetwork() {
        networkUsagePolicy.requireAllowed(settingsRepository.unmeteredOnly.first())
    }

    private suspend fun emitNetworkMeasurementError(error: UserNetworkException? = null) {
        if (error is MeteredNetworkNotAllowedException) {
            _messages.emit(UserMessage("This operation requires an unmetered network.",
                "این عملیات فقط روی اینترنت نامحدود مجاز است."))
            return
        }
        _messages.emit(
            UserMessage(
                english = "The active network was unavailable or changed. Keep one connection active and retry.",
                persian = "شبکهٔ فعال در دسترس نبود یا حین تست تغییر کرد؛ یک اتصال را فعال نگه دارید و دوباره تست بگیرید."
            )
        )
    }

    private fun isProxyDataBusy(): Boolean {
        return releaseUpdates.state.value.busy || isCollecting.value ||
            _isVerifying.value ||
            _liveTestingProxyUrl.value != null ||
            _networkSpeed.value.isRunning ||
            _favoriteSyncingProxyUrls.value.isNotEmpty() ||
            _privateNodeTestingUrls.value.isNotEmpty() ||
            proxyRepository.isForegroundOperationActive ||
            telegramOperationJob?.isActive == true
    }

    private suspend fun resetForFreshProxySnapshot() {
        benchmarkCoordinator.clearFinishedRun()
        _selectedProxyUrl.value = null
        _searchQuery.value = ""
        _statusFilter.value = StatusFilter.ALL
        _sortBy.value = SortBy.BEST
        _verificationProgress.value = 0f
        _verificationCompletedCount.value = 0
        _verificationTotalCount.value = 0
        _currentVerifyingProxy.value = null
        _benchmarkSession.value = BenchmarkSessionState()
        _comparisonUrls.value = emptyList()
    }

    private fun observeRefreshCommits() {
        val initialRevision = proxyRepository.refreshRevision.value
        viewModelScope.launch {
            proxyRepository.refreshRevision.collect { revision ->
                if (revision <= initialRevision) return@collect
                resetForFreshProxySnapshot()
                _benchmarkRevision.value += 1
            }
        }
    }

    private fun observeListOrderCommits() {
        val initialRevision = proxyRepository.listOrderRevision.value
        viewModelScope.launch {
            proxyRepository.listOrderRevision.collect { revision ->
                if (revision <= initialRevision) return@collect
                _benchmarkRevision.value += 1
            }
        }
    }

    private fun emitBusyMessage() {
        _messages.tryEmit(
            UserMessage(
                english = "Finish or cancel the current proxy operation first.",
                persian = "ابتدا عملیات فعلی پروکسی را تمام یا لغو کنید."
            )
        )
    }

    private fun observeBenchmarkRuntime() {
        viewModelScope.launch {
            var lastFinishedAt = 0L
            benchmarkCoordinator.state.collect { state ->
                _isVerifying.value = state.isActive
                _isVerificationPaused.value = state.status == BenchmarkRunStatus.PAUSED
                state.progress?.let { progress ->
                    _verificationProgress.value = progress.fraction
                    _verificationCompletedCount.value = progress.stageCompleted
                    _verificationTotalCount.value = progress.stageTotal
                    _currentVerifyingProxy.value = progress.currentProxy
                }
                state.telegramTransferProgress?.let { transfer ->
                    _verificationProgress.value = transfer.fraction
                    _verificationCompletedCount.value = transfer.completedCount
                    _verificationTotalCount.value = transfer.totalCount
                    _currentVerifyingProxy.value = transfer.currentProxy
                }
                if (state.finishedAt <= 0L || state.finishedAt == lastFinishedAt) {
                    return@collect
                }
                lastFinishedAt = state.finishedAt
                _currentVerifyingProxy.value = null
                when (state.status) {
                    BenchmarkRunStatus.COMPLETED -> {
                        _verificationProgress.value = 1f
                        _benchmarkSession.value = BenchmarkSessionState()
                        _selectedProxyUrl.value = null
                        val tested = state.result?.screenedCount ?: 0
                        val finalists = state.result?.finalistCount ?: 0
                        val telegramPassed = state.result?.telegramTransferSucceededCount ?: 0
                        val telegramAttempted = state.result?.telegramTransferAttemptedCount ?: 0
                        _messages.emit(
                            UserMessage(
                                english = if (telegramAttempted > 0) {
                                    "Benchmark completed: $tested tested, $finalists finalists, " +
                                        "$telegramPassed/$telegramAttempted real TDLib transfers passed."
                                } else {
                                    "Three-stage benchmark completed: $tested tested, $finalists finalists."
                                },
                                persian = if (telegramAttempted > 0) {
                                    "بنچ‌مارک تمام شد: $tested تست، $finalists فینالیست و " +
                                        "$telegramPassed از $telegramAttempted انتقال واقعی موفق."
                                } else {
                                    "بنچ‌مارک سه‌مرحله‌ای تمام شد: $tested تست و $finalists فینالیست."
                                }
                            )
                        )
                    }
                    BenchmarkRunStatus.CANCELLED -> _messages.emit(
                        UserMessage(
                            english = "Benchmark cancelled; completed measurements were saved.",
                            persian = "بنچ‌مارک لغو شد؛ اندازه‌گیری‌های کامل‌شده ذخیره شدند."
                        )
                    )
                    BenchmarkRunStatus.FAILED -> {
                        val message = when (state.failure) {
                            "METERED_NETWORK" -> UserMessage(
                                "This benchmark is restricted to unmetered networks.",
                                "این بنچ‌مارک فقط روی اینترنت نامحدود مجاز است."
                            )
                            "NETWORK_CHANGED" -> UserMessage(
                                "The active network was lost or changed during the benchmark.",
                                "شبکه فعال هنگام بنچ‌مارک قطع شد یا تغییر کرد."
                            )
                            else -> UserMessage(
                                "The benchmark stopped after an unexpected error.",
                                "بنچ‌مارک به‌دلیل یک خطای غیرمنتظره متوقف شد."
                            )
                        }
                        _messages.emit(message)
                    }
                    else -> Unit
                }
            }
        }
        viewModelScope.launch {
            benchmarkCoordinator.events.collect { event ->
                _currentVerifyingProxy.value = event.proxy
                if (event.progress.stage == BenchmarkStage.SCREENING) {
                    _benchmarkSession.value = appendBenchmarkResult(
                        previous = _benchmarkSession.value,
                        completed = event.progress.stageCompleted,
                        total = event.progress.stageTotal,
                        proxy = event.proxy
                    )
                }
            }
        }
    }

    private companion object {
        const val TAG = "ProxyViewModel"
        const val TELEGRAM_AUTHORIZATION_CANDIDATE_LIMIT = 12
        const val MAX_COMPARISON_PROXIES = 4
        const val NETWORK_IDENTITY_REFRESH_MS = 5_000L
        const val PROFILE_EXPORT_RETENTION_MS = 24L * 60L * 60L * 1_000L
    }
}

private const val BENCHMARK_PREVIEW_LIMIT = 12
private const val BENCHMARK_CHART_LIMIT = 10
private const val MAX_CHART_LATENCY_MS = 60_000L

class ProxyViewModelFactory(
    private val appContext: Context,
    private val proxyRepository: ProxyRepository,
    private val settingsRepository: SettingsRepository,
    private val benchmarkCoordinator: BenchmarkCoordinator,
    private val telegramTransferEngine: TdlibTelegramTransferEngine
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ProxyViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return ProxyViewModel(
                appContext,
                proxyRepository,
                settingsRepository,
                benchmarkCoordinator,
                telegramTransferEngine
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
