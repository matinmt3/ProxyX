package com.example.data.benchmark

import com.example.model.MtProtoProxy

enum class BenchmarkStage(val value: Int) {
    IDLE(0),
    SCREENING(1),
    STRICT(2),
    FINALIST(3)
}

enum class BenchmarkAccuracyMode {
    DATA_SAVER,
    BALANCED,
    PRECISE;

    companion object {
        fun fromStoredValue(value: String?): BenchmarkAccuracyMode {
            return entries.firstOrNull { it.name == value } ?: BALANCED
        }
    }
}

data class ProxyTestProfile(
    val stage: BenchmarkStage,
    val tcpAttempts: Int,
    val dcIds: IntArray,
    val stopAfterFailedTcpAttempts: Int? = null,
    val stopAfterFailedProtocolAttempts: Int? = null
) {
    companion object {
        val SCREENING = ProxyTestProfile(
            stage = BenchmarkStage.SCREENING,
            tcpAttempts = 3,
            dcIds = intArrayOf(2, 4),
            stopAfterFailedTcpAttempts = 3,
            stopAfterFailedProtocolAttempts = 2
        )
        val STRICT = ProxyTestProfile(
            stage = BenchmarkStage.STRICT,
            tcpAttempts = 10,
            dcIds = intArrayOf(2, 4, 1, 3, 5, 2, 4, 2),
            stopAfterFailedTcpAttempts = 3,
            stopAfterFailedProtocolAttempts = 3
        )
        val FINALIST = ProxyTestProfile(
            stage = BenchmarkStage.FINALIST,
            tcpAttempts = 10,
            dcIds = intArrayOf(2, 4, 1, 3, 5, 2, 4, 2)
        )
    }
}

data class BenchmarkTestPlan(
    val mode: BenchmarkAccuracyMode,
    val screeningProfile: ProxyTestProfile,
    val strictProfile: ProxyTestProfile,
    val finalistProfile: ProxyTestProfile,
    val finalistRounds: Int,
    val maximumTelegramConfirmationBytes: Int
)

object BenchmarkTestPlans {
    fun forMode(mode: BenchmarkAccuracyMode): BenchmarkTestPlan = when (mode) {
        BenchmarkAccuracyMode.DATA_SAVER -> BenchmarkTestPlan(
            mode = mode,
            screeningProfile = ProxyTestProfile.SCREENING,
            strictProfile = ProxyTestProfile.STRICT.copy(
                tcpAttempts = 8,
                dcIds = intArrayOf(2, 4, 1, 3, 5)
            ),
            finalistProfile = ProxyTestProfile.FINALIST.copy(
                tcpAttempts = 8,
                dcIds = intArrayOf(2, 4, 1, 3, 5)
            ),
            finalistRounds = 3,
            maximumTelegramConfirmationBytes = 2 * 1024 * 1024
        )
        BenchmarkAccuracyMode.BALANCED -> BenchmarkTestPlan(
            mode = mode,
            screeningProfile = ProxyTestProfile.SCREENING,
            strictProfile = ProxyTestProfile.STRICT,
            finalistProfile = ProxyTestProfile.FINALIST,
            finalistRounds = 3,
            maximumTelegramConfirmationBytes = 4 * 1024 * 1024
        )
        BenchmarkAccuracyMode.PRECISE -> BenchmarkTestPlan(
            mode = mode,
            screeningProfile = ProxyTestProfile.SCREENING.copy(
                tcpAttempts = 4,
                dcIds = intArrayOf(2, 4, 1)
            ),
            strictProfile = ProxyTestProfile.STRICT.copy(
                tcpAttempts = 12,
                dcIds = intArrayOf(2, 4, 1, 3, 5, 2, 4, 1, 3, 5)
            ),
            finalistProfile = ProxyTestProfile.FINALIST.copy(
                tcpAttempts = 12,
                dcIds = intArrayOf(2, 4, 1, 3, 5, 2, 4, 1, 3, 5)
            ),
            finalistRounds = 5,
            maximumTelegramConfirmationBytes = 8 * 1024 * 1024
        )
    }
}

data class BenchmarkEstimate(
    val minimumDurationMs: Long,
    val maximumDurationMs: Long,
    val estimatedProbeBytes: Long,
    val maximumTransferBytes: Long,
    val estimatedFinalists: Int
)

object BenchmarkEstimateCalculator {
    fun calculate(
        proxyCount: Int,
        workers: Int,
        timeoutMs: Int,
        mode: BenchmarkAccuracyMode,
        telegramTransfers: Boolean,
        telegramBudgetMb: Int
    ): BenchmarkEstimate {
        val count = proxyCount.coerceAtLeast(0)
        if (count == 0) return BenchmarkEstimate(0L, 0L, 0L, 0L, 0)
        val plan = BenchmarkTestPlans.forMode(mode)
        val strictCandidates = (count * EXPECTED_SCREENING_PASS_RATE).toInt()
            .coerceIn(1, count)
        val finalists = ThreeStageBenchmarkPolicy.finalistCount(
            qualifiedCount = strictCandidates
        )
        val probeUnits = count.toLong() * (
            plan.screeningProfile.tcpAttempts + plan.screeningProfile.dcIds.size
            ) + strictCandidates.toLong() * (
            plan.strictProfile.tcpAttempts + plan.strictProfile.dcIds.size
            ) + finalists.toLong() * plan.finalistRounds * (
            plan.finalistProfile.tcpAttempts + plan.finalistProfile.dcIds.size
            )
        val safeWorkers = workers.coerceAtLeast(1)
        val boundedTimeout = timeoutMs.coerceIn(750, 10_000)
        val minimumDuration = probeUnits.toLong() * MINIMUM_PROBE_TIME_MS / safeWorkers
        val maximumDuration = probeUnits.toLong() * boundedTimeout / safeWorkers
        val estimatedProbeBytes = count.toLong() * ESTIMATED_ROUTE_BYTES_PER_PROXY
        val transferBytesPerFinalist = com.example.data.telegram.FairTelegramTransferRunner
            .sampleSizes(plan.maximumTelegramConfirmationBytes).sumOf { it.toLong() * 2L }
        val maximumFinalists = ThreeStageBenchmarkPolicy.finalistCount(count)
        val uncappedTransferBytes = if (telegramTransfers) {
            maximumFinalists.toLong() * transferBytesPerFinalist
        } else {
            0L
        }
        val budgetBytes = telegramBudgetMb.takeIf { it > 0 }
            ?.toLong()
            ?.times(1024L * 1024L)
        return BenchmarkEstimate(
            minimumDurationMs = minimumDuration.coerceIn(1_000L, MAXIMUM_ESTIMATE_MS),
            maximumDurationMs = maximumDuration
                .coerceAtLeast(minimumDuration)
                .coerceAtMost(MAXIMUM_ESTIMATE_MS),
            estimatedProbeBytes = estimatedProbeBytes,
            maximumTransferBytes = budgetBytes?.let { minOf(it, uncappedTransferBytes) }
                ?: uncappedTransferBytes,
            estimatedFinalists = finalists
        )
    }

    private const val EXPECTED_SCREENING_PASS_RATE = 0.20
    private const val MINIMUM_PROBE_TIME_MS = 90L
    private const val ESTIMATED_ROUTE_BYTES_PER_PROXY = 64L * 1024L
    private const val MAXIMUM_ESTIMATE_MS = 7L * 24L * 60L * 60L * 1_000L
}

data class ThreeStageProgress(
    val stage: BenchmarkStage,
    val stageCompleted: Int,
    val stageTotal: Int,
    val completedWork: Int,
    val totalWork: Int,
    val currentProxy: MtProtoProxy? = null
) {
    val fraction: Float
        get() {
            val stageFraction = if (stageTotal > 0) {
                stageCompleted.toFloat().div(stageTotal.toFloat()).coerceIn(0f, 1f)
            } else {
                1f
            }
            return when (stage) {
                BenchmarkStage.IDLE -> if (completedWork > 0) 1f else 0f
                BenchmarkStage.SCREENING -> stageFraction * 0.25f
                BenchmarkStage.STRICT -> 0.25f + stageFraction * 0.45f
                BenchmarkStage.FINALIST -> 0.70f + stageFraction * 0.30f
            }.coerceIn(0f, 1f)
    }
}

data class TelegramFinalistTransferProgress(
    val totalCount: Int,
    val completedCount: Int,
    val succeededCount: Int,
    val failedCount: Int,
    val currentProxy: MtProtoProxy? = null,
    val currentTransferFraction: Float = 0f,
    val completedSamples: Int = 0,
    val totalSamples: Int = 0,
    val round: Int = 1
) {
    val fraction: Float
        get() = if (totalSamples > 0) {
            ((completedSamples + currentTransferFraction.coerceIn(0f, 1f)) / totalSamples)
                .coerceIn(0f, 1f)
        } else if (totalCount > 0) {
            (completedCount + currentTransferFraction.coerceIn(0f, 1f))
                .div(totalCount.toFloat())
                .coerceIn(0f, 1f)
        } else {
            1f
        }
}

data class ThreeStageBenchmarkResult(
    val requestedCount: Int,
    val screenedCount: Int,
    val strictCount: Int,
    val finalistCount: Int,
    val completedCount: Int,
    val cancelled: Boolean = false,
    val successfulFinalists: List<MtProtoProxy> = emptyList(),
    val telegramTransferAttemptedCount: Int = 0,
    val telegramTransferSucceededCount: Int = 0,
    val telegramTransferFailedCount: Int = 0,
    val telegramTransferSkippedCount: Int = 0,
    val telegramTransferBudgetSkippedCount: Int = 0,
    val telegramTransferAuthorizationSkippedCount: Int = 0,
    val telegramTransferStatus: TelegramTransferStageStatus = TelegramTransferStageStatus.NOT_REQUESTED,
    val networkFingerprint: String = ""
)

enum class TelegramTransferStageStatus {
    NOT_REQUESTED, NO_FINALISTS, LOGIN_REQUIRED, BUDGET_EXHAUSTED, COMPLETED, PARTIAL
}

internal fun ThreeStageBenchmarkResult.withTelegramTransferSummary(
    summary: com.example.data.telegram.FairTransferSummary
): ThreeStageBenchmarkResult = copy(
    telegramTransferAttemptedCount = summary.attempted,
    telegramTransferSucceededCount = summary.succeeded,
    telegramTransferFailedCount = summary.failed,
    telegramTransferSkippedCount = summary.skipped,
    telegramTransferBudgetSkippedCount = summary.budgetSkipped,
    telegramTransferAuthorizationSkippedCount = summary.authorizationSkipped,
    telegramTransferStatus = when {
        summary.authorizationSkipped > 0 -> TelegramTransferStageStatus.LOGIN_REQUIRED
        summary.attempted == 0 && summary.budgetSkipped > 0 -> TelegramTransferStageStatus.BUDGET_EXHAUSTED
        summary.skipped > 0 || summary.failed > 0 -> TelegramTransferStageStatus.PARTIAL
        else -> TelegramTransferStageStatus.COMPLETED
    }
)
