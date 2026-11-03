package com.example.data.benchmark

import android.util.Log
import com.example.data.network.MeteredNetworkNotAllowedException
import com.example.data.network.NetworkUsagePolicy
import com.example.data.network.UserNetworkException
import com.example.data.repository.ProxyRepository
import com.example.data.repository.SettingsRepository
import com.example.data.telegram.TdlibTelegramTransferEngine
import com.example.data.telegram.TelegramAuthorizationStep
import com.example.model.MtProtoProxy
import com.example.model.BenchmarkRunRecord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class BenchmarkRunStatus {
    IDLE, RUNNING, PAUSED, COMPLETED, CANCELLED, FAILED, INTERRUPTED
}

data class BenchmarkRuntimeState(
    val status: BenchmarkRunStatus = BenchmarkRunStatus.IDLE,
    val requestedCount: Int = 0,
    val accuracyMode: BenchmarkAccuracyMode = BenchmarkAccuracyMode.BALANCED,
    val selectedWorkers: Int = 0,
    val progress: ThreeStageProgress? = null,
    val result: ThreeStageBenchmarkResult? = null,
    val telegramTransferProgress: TelegramFinalistTransferProgress? = null,
    val startedAt: Long = 0L,
    val finishedAt: Long = 0L,
    val failure: String? = null,
    val telegramTransfersRequested: Boolean = false
) {
    val isActive: Boolean
        get() = status == BenchmarkRunStatus.RUNNING || status == BenchmarkRunStatus.PAUSED

    val showsScreeningPreview: Boolean
        get() = isActive && telegramTransferProgress == null &&
            (progress == null || progress.stage == BenchmarkStage.SCREENING)

    val overallFraction: Float
        get() = if (status == BenchmarkRunStatus.COMPLETED) 1f else if (telegramTransfersRequested) {
            (progress?.fraction ?: 0f) * 0.75f + (telegramTransferProgress?.fraction ?: 0f) * 0.25f
        } else progress?.fraction ?: 0f

    fun withPauseState(paused: Boolean): BenchmarkRuntimeState {
        val expected = if (paused) BenchmarkRunStatus.RUNNING else BenchmarkRunStatus.PAUSED
        if (status != expected) return this
        return copy(status = if (paused) BenchmarkRunStatus.PAUSED else BenchmarkRunStatus.RUNNING)
    }

    fun forFreshProxySnapshot(): BenchmarkRuntimeState =
        if (isActive) this else BenchmarkRuntimeState(accuracyMode = accuracyMode)
}

data class BenchmarkProgressEvent(
    val progress: ThreeStageProgress,
    val proxy: MtProtoProxy
)

class BenchmarkCoordinator(
    private val repository: ProxyRepository,
    private val settingsRepository: SettingsRepository,
    private val workerSelector: AdaptiveWorkerSelector,
    private val networkUsagePolicy: NetworkUsagePolicy,
    private val telegramTransferEngine: TdlibTelegramTransferEngine
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val operationMutex = Mutex()
    private val journalMutex = Mutex()
    private val resumeGate = MutableStateFlow(true)
    private var benchmarkJob: Job? = null
    private val orphanRecoveryJob: Job
    private var currentTelegramTransfersEnabled = false
    private var currentAccuracyMode = BenchmarkAccuracyMode.BALANCED
    @Volatile private var lastJournalWriteAt = 0L

    private val _state = MutableStateFlow(BenchmarkRuntimeState())
    val state: StateFlow<BenchmarkRuntimeState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<BenchmarkProgressEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<BenchmarkProgressEvent> = _events.asSharedFlow()

    val persistedRun = repository.observeBenchmarkRun()

    init {
        orphanRecoveryJob = scope.launch { repository.markOrphanedBenchmarkInterrupted() }
    }

    suspend fun start(
        requestedCount: Int,
        enableTelegramFinalistTransfers: Boolean = false,
        accuracyMode: BenchmarkAccuracyMode? = null
    ): Boolean = operationMutex.withLock {
        if (benchmarkJob?.isActive == true || _state.value.isActive) return false
        orphanRecoveryJob.join()
        if (!repository.beginForegroundBenchmark()) return false
        try {
            val normalizedCount = requestedCount.coerceAtLeast(1)
            val selectedAccuracyMode = accuracyMode
                ?: settingsRepository.benchmarkAccuracyMode.first()

            resumeGate.value = true
            currentTelegramTransfersEnabled = enableTelegramFinalistTransfers
            currentAccuracyMode = selectedAccuracyMode
            lastJournalWriteAt = 0L
            _state.value = BenchmarkRuntimeState(
                status = BenchmarkRunStatus.RUNNING,
                requestedCount = normalizedCount,
                accuracyMode = selectedAccuracyMode,
                telegramTransfersRequested = enableTelegramFinalistTransfers,
                startedAt = System.currentTimeMillis()
            )
            persistRunState()
            currentCoroutineContext().ensureActive()
            benchmarkJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                val configuredWorkers = settingsRepository.parallelWorkers.first()
                val adaptive = settingsRepository.adaptiveWorkersEnabled.first()
                val unmeteredOnly = settingsRepository.unmeteredOnly.first()
                networkUsagePolicy.requireAllowed(unmeteredOnly)
                val selectedWorkers = workerSelector.select(configuredWorkers, adaptive)
                val timeout = settingsRepository.timeoutMs.first()
                _state.update { it.copy(selectedWorkers = selectedWorkers) }
                persistRunState()
                val benchmarkResult = repository.runThreeStageBenchmark(
                    parallelWorkers = selectedWorkers,
                    timeoutMs = timeout,
                    limit = normalizedCount,
                    accuracyMode = selectedAccuracyMode,
                    checkpoint = ::awaitRunning,
                    onStarted = { total ->
                        _state.update { it.copy(requestedCount = total) }
                    },
                    onProgress = { progress, proxy ->
                        _state.update { it.copy(progress = progress) }
                        _events.emit(BenchmarkProgressEvent(progress, proxy))
                        maybePersistRunState(progress)
                    }
                )
                val result = if (enableTelegramFinalistTransfers) {
                    runTelegramFinalistTransfers(
                        benchmarkResult = benchmarkResult,
                        budgetMb = settingsRepository.telegramTransferDataBudgetMb.first(),
                        maximumConfirmationBytes = BenchmarkTestPlans
                            .forMode(selectedAccuracyMode)
                            .maximumTelegramConfirmationBytes
                    )
                } else {
                    benchmarkResult
                }
                _state.update { it.copy(
                    status = BenchmarkRunStatus.COMPLETED,
                    result = result,
                    finishedAt = System.currentTimeMillis(),
                    failure = null
                ) }
                persistRunState()
                } catch (error: CancellationException) {
                    _state.update { it.copy(
                        status = BenchmarkRunStatus.CANCELLED,
                        finishedAt = System.currentTimeMillis()
                    ) }
                    persistRunState()
                } catch (error: MeteredNetworkNotAllowedException) {
                    fail("METERED_NETWORK")
                } catch (error: UserNetworkException) {
                    fail("NETWORK_CHANGED")
                } catch (error: Exception) {
                    Log.e(TAG, "Benchmark execution failed.", error)
                    fail("UNEXPECTED")
                } finally {
                    resumeGate.value = true
                    repository.endForegroundBenchmark()
                }
            }
            true
        } catch (error: CancellationException) {
            repository.endForegroundBenchmark()
            if (_state.value.isActive) {
                _state.update { it.copy(
                    status = BenchmarkRunStatus.CANCELLED,
                    finishedAt = System.currentTimeMillis()
                ) }
                persistRunState()
            }
            throw error
        } catch (error: Exception) {
            Log.e(TAG, "Benchmark startup failed.", error)
            _state.value = BenchmarkRuntimeState(
                status = BenchmarkRunStatus.FAILED,
                requestedCount = requestedCount.coerceAtLeast(1),
                accuracyMode = accuracyMode ?: BenchmarkAccuracyMode.BALANCED,
                startedAt = System.currentTimeMillis(),
                finishedAt = System.currentTimeMillis(),
                failure = "STARTUP_FAILED"
            )
            persistRunState()
            repository.endForegroundBenchmark()
            false
        }
    }

    fun pause() {
        if (_state.value.status != BenchmarkRunStatus.RUNNING) return
        resumeGate.value = false
        _state.update { it.withPauseState(paused = true) }
        scope.launch { persistRunState() }
    }

    fun resume() {
        if (_state.value.status != BenchmarkRunStatus.PAUSED) return
        _state.update { it.withPauseState(paused = false) }
        resumeGate.value = true
        scope.launch { persistRunState() }
    }

    fun cancel() {
        val requestedRun = _state.value.startedAt
        scope.launch {
            operationMutex.withLock {
                if (_state.value.startedAt == requestedRun) benchmarkJob?.cancel()
            }
        }
    }

    suspend fun clearFinishedRun(): Boolean = operationMutex.withLock {
        if (_state.value.isActive) return@withLock false
        benchmarkJob?.join()
        journalMutex.withLock {
            _state.value = _state.value.forFreshProxySnapshot()
            repository.clearBenchmarkRun()
        }
        true
    }

    private suspend fun awaitRunning() {
        currentCoroutineContext().ensureActive()
        resumeGate.first { it }
        currentCoroutineContext().ensureActive()
    }

    private suspend fun runTelegramFinalistTransfers(
        benchmarkResult: ThreeStageBenchmarkResult,
        budgetMb: Int,
        maximumConfirmationBytes: Int
    ): ThreeStageBenchmarkResult {
        awaitRunning()
        if (benchmarkResult.successfulFinalists.isEmpty()) return benchmarkResult.copy(
            telegramTransferStatus = TelegramTransferStageStatus.NO_FINALISTS
        )
        if (telegramTransferEngine.state.value.step != TelegramAuthorizationStep.READY) {
            telegramTransferEngine.initialize()
        }
        awaitRunning()
        val network = repository.currentNetworkInfo()
            ?: throw com.example.data.network.UserNetworkUnavailableException()
        if (benchmarkResult.networkFingerprint != network.fingerprint) {
            throw com.example.data.network.UserNetworkChangedException()
        }
        val confirmationBytes = if (network.isMetered) {
            minOf(maximumConfirmationBytes, com.example.data.telegram.TelegramAdaptiveTransferPolicy.MIN_CONFIRMATION_BYTES)
        } else maximumConfirmationBytes
        val summary = com.example.data.telegram.FairTelegramTransferRunner().run(
            finalists = benchmarkResult.successfulFinalists,
            confirmationBytes = confirmationBytes,
            budgetMb = budgetMb,
            networkFingerprint = network.fingerprint,
            ready = { telegramTransferEngine.state.value.step == TelegramAuthorizationStep.READY },
            checkpoint = ::awaitRunning,
            measure = { proxy, bytes ->
                networkUsagePolicy.requireAllowed(settingsRepository.unmeteredOnly.first())
                if (repository.currentNetworkInfo()?.fingerprint != network.fingerprint) {
                    throw com.example.data.network.UserNetworkChangedException()
                }
                coroutineScope {
                    val observer = launch {
                        telegramTransferEngine.state.collect { transfer ->
                            if (transfer.activeProxyUrl == proxy.url) {
                                _state.update { state -> state.copy(telegramTransferProgress =
                                    state.telegramTransferProgress?.copy(currentTransferFraction = transfer.transferProgress)) }
                            }
                        }
                    }
                    try {
                        telegramTransferEngine.measureTransfer(proxy, bytes)
                    } finally {
                        observer.cancelAndJoin()
                    }
                }
            },
            persist = { measured ->
                check(repository.persistTelegramTransfer(measured)) {
                    "The proxy or its verified network changed before the transfer was saved."
                }
            },
            onFailure = { proxy -> repository.markTelegramTransferFailed(proxy) },
            progress = { progress ->
                _state.update { it.copy(telegramTransferProgress =
                    TelegramFinalistTransferProgress(
                        totalCount = progress.total,
                        completedCount = progress.completed,
                        succeededCount = progress.succeeded,
                        failedCount = progress.failed,
                        currentProxy = progress.proxy,
                        completedSamples = progress.completedSamples,
                        totalSamples = progress.totalSamples,
                        round = progress.round
                    )) }
                if (System.currentTimeMillis() - lastJournalWriteAt >= JOURNAL_INTERVAL_MS ||
                    progress.completed >= progress.total) persistRunState()
            }
        )
        return benchmarkResult.withTelegramTransferSummary(summary)
    }

    private suspend fun fail(code: String) {
        _state.update { it.copy(
            status = BenchmarkRunStatus.FAILED,
            finishedAt = System.currentTimeMillis(),
            failure = code
        ) }
        persistRunState()
    }

    private suspend fun maybePersistRunState(progress: ThreeStageProgress) {
        val now = System.currentTimeMillis()
        if (now - lastJournalWriteAt >= JOURNAL_INTERVAL_MS ||
            progress.stageCompleted == progress.stageTotal
        ) {
            persistRunState()
        }
    }

    private suspend fun persistRunState() {
        withContext(NonCancellable) {
            // Capture after acquiring the writer lock so queued UI writes cannot revive an old run.
            journalMutex.withLock {
                val now = System.currentTimeMillis()
                val snapshot = _state.value
                if (snapshot.status == BenchmarkRunStatus.IDLE) return@withLock
                val progress = snapshot.progress
                val telegram = snapshot.telegramTransferProgress
                try {
                    repository.saveBenchmarkRun(
                        BenchmarkRunRecord(
                            status = snapshot.status.name,
                            requestedCount = snapshot.requestedCount,
                            selectedWorkers = snapshot.selectedWorkers,
                            stage = progress?.stage?.value ?: 0,
                            stageCompleted = progress?.stageCompleted ?: 0,
                            stageTotal = progress?.stageTotal ?: 0,
                            completedWork = progress?.completedWork ?: 0,
                            totalWork = progress?.totalWork ?: 0,
                            telegramTotal = telegram?.totalCount ?: 0,
                            telegramCompleted = telegram?.completedCount ?: 0,
                            telegramSucceeded = telegram?.succeededCount ?: 0,
                            telegramFailed = telegram?.failedCount ?: 0,
                            enableTelegramTransfers = currentTelegramTransfersEnabled,
                            startedAt = snapshot.startedAt,
                            updatedAt = now,
                            finishedAt = snapshot.finishedAt,
                            failure = snapshot.failure.orEmpty(),
                            accuracyMode = snapshot.accuracyMode.name
                        )
                    )
                } catch (error: Exception) {
                    Log.w(TAG, "Unable to persist benchmark recovery state.", error)
                }
                lastJournalWriteAt = now
            }
        }
    }

    private companion object {
        const val TAG = "BenchmarkCoordinator"
        const val JOURNAL_INTERVAL_MS = 750L
    }

    suspend fun awaitOrphanRecovery() {
        orphanRecoveryJob.join()
    }
}
