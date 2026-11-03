package com.example.ui

import com.example.data.benchmark.BenchmarkRunStatus
import com.example.data.benchmark.ThreeStageBenchmarkResult
import com.example.data.benchmark.TelegramTransferStageStatus
import com.example.model.MtProtoProxy
import com.example.model.PrivateProxyNode
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDirection
import java.util.Locale

internal enum class StageAppearance { PENDING, ACTIVE, PAUSED, COMPLETE, STOPPED, SKIPPED }

internal fun useSideGaugeLabels(compact: Boolean, fontScale: Float): Boolean =
    compact && fontScale > 1.15f

internal fun benchmarkProgressLabel(fraction: Float): String {
    val progress = if (fraction.isFinite()) fraction.coerceIn(0f, 1f) else 0f
    val percent = progress * 100f
    return when {
        progress == 0f -> "0%"
        progress == 1f -> "100%"
        percent < 0.1f -> "<0.1%"
        percent < 1f -> String.format(Locale.US, "%.1f%%", percent)
        else -> "${percent.toInt().coerceAtMost(99)}%"
    }
}

internal fun shouldCollapseHomeAction(heightDp: Float, filtersVisible: Boolean): Boolean =
    filtersVisible || heightDp < 360f

internal fun endpointStyle(base: TextStyle): TextStyle = base.copy(textDirection = TextDirection.Ltr)

internal fun hasCurrentPrivateEvidence(node: PrivateProxyNode, network: String): Boolean =
    network.isNotBlank() && node.networkFingerprint == network &&
        node.lastChecked > 0 && node.isHealthy

internal fun stageAppearances(
    status: BenchmarkRunStatus,
    stage: Int,
    transfersRequested: Boolean,
    transferActive: Boolean,
    result: ThreeStageBenchmarkResult? = null
): List<StageAppearance> {
    val count = if (transfersRequested) 4 else 3
    val current = if (transferActive) 4 else stage.coerceIn(0, 3)
    return (1..count).map { step ->
        when {
            status == BenchmarkRunStatus.COMPLETED -> {
                val attempts = when (step) {
                    1 -> result?.screenedCount ?: 0
                    2 -> result?.strictCount ?: 0
                    3 -> result?.finalistCount ?: 0
                    else -> result?.telegramTransferAttemptedCount ?: 0
                }
                when {
                    attempts == 0 -> StageAppearance.SKIPPED
                    step == 4 && result?.telegramTransferStatus != TelegramTransferStageStatus.COMPLETED ->
                        StageAppearance.STOPPED
                    else -> StageAppearance.COMPLETE
                }
            }
            current == 0 -> StageAppearance.PENDING
            step < current -> StageAppearance.COMPLETE
            step > current -> StageAppearance.PENDING
            status == BenchmarkRunStatus.PAUSED -> StageAppearance.PAUSED
            status == BenchmarkRunStatus.RUNNING -> StageAppearance.ACTIVE
            else -> StageAppearance.STOPPED
        }
    }
}

internal fun currentProtocolLatency(proxy: MtProtoProxy?, network: String): Long? =
    proxy?.takeIf {
        network.isNotBlank() && it.networkFingerprint == network &&
            it.lastChecked > 0 && it.isTelegramVerified && it.protocolLatency > 0
    }?.protocolLatency
