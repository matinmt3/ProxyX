package com.example.data.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.example.data.benchmark.BenchmarkCoordinator
import com.example.data.benchmark.BenchmarkRunStatus
import com.example.data.repository.ProxyRepository
import com.example.data.repository.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

class ScheduledBenchmarkWorker(
    appContext: Context,
    workerParams: WorkerParameters,
    private val coordinator: BenchmarkCoordinator,
    private val repository: ProxyRepository,
    private val settingsRepository: SettingsRepository
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        if (!settingsRepository.scheduledBenchmarkEnabled.first()) return Result.success()
        val requested = inputData.getInt(
            KEY_PROXY_COUNT,
            settingsRepository.scheduledBenchmarkCount.first()
        )
        val count = minOf(
            ProxyBenchmarkScheduler.normalizeCount(requested),
            repository.countStoredProxies()
        )
        if (count <= 0) return Result.success()

        setForeground(benchmarkForegroundInfo(applicationContext, RECENT_CHECK_NOTIFICATION_ID))
        val requestedAt = System.currentTimeMillis()
        val started = coordinator.start(
            requestedCount = count,
            enableTelegramFinalistTransfers = false,
            accuracyMode = settingsRepository.benchmarkAccuracyMode.first()
        )
        if (!started) return Result.success()
        return try {
            val terminal = coordinator.state.first { state ->
                state.startedAt >= requestedAt &&
                    !state.isActive &&
                    state.status != BenchmarkRunStatus.IDLE
            }
            when (terminal.status) {
                BenchmarkRunStatus.COMPLETED,
                BenchmarkRunStatus.CANCELLED -> Result.success()
                BenchmarkRunStatus.FAILED,
                BenchmarkRunStatus.INTERRUPTED -> Result.retry()
                else -> Result.success()
            }
        } catch (error: CancellationException) {
            coordinator.cancel()
            throw error
        }
    }

    companion object {
        const val KEY_PROXY_COUNT = "proxy_count"
        private const val RECENT_CHECK_NOTIFICATION_ID = 304
    }
}

class BenchmarkRecoveryWorker(
    appContext: Context,
    workerParams: WorkerParameters,
    private val coordinator: BenchmarkCoordinator,
    private val repository: ProxyRepository,
    private val settingsRepository: SettingsRepository
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        if (!settingsRepository.autoResumeBenchmark.first()) return Result.success()
        coordinator.awaitOrphanRecovery()
        val interrupted = repository.getBenchmarkRunSnapshot()
            ?.takeIf { it.status == BenchmarkRunStatus.INTERRUPTED.name }
            ?: return Result.success()
        val recoveryAgeMs = System.currentTimeMillis() - interrupted.updatedAt
        if (recoveryAgeMs !in 0L..MAX_RECOVERY_AGE_MS) {
            return Result.success()
        }
        val count = minOf(interrupted.requestedCount, repository.countStoredProxies())
        if (count <= 0) return Result.success()

        setForeground(benchmarkForegroundInfo(applicationContext, RECOVERY_NOTIFICATION_ID))
        val requestedAt = System.currentTimeMillis()
        val started = coordinator.start(
            requestedCount = count,
            enableTelegramFinalistTransfers = false,
            accuracyMode = com.example.data.benchmark.BenchmarkAccuracyMode.fromStoredValue(
                interrupted.accuracyMode
            )
        )
        if (!started) return Result.retry()
        return try {
            val terminal = coordinator.state.first { state ->
                state.startedAt >= requestedAt &&
                    !state.isActive &&
                    state.status != BenchmarkRunStatus.IDLE
            }
            when (terminal.status) {
                BenchmarkRunStatus.COMPLETED,
                BenchmarkRunStatus.CANCELLED -> Result.success()
                BenchmarkRunStatus.FAILED,
                BenchmarkRunStatus.INTERRUPTED -> Result.retry()
                else -> Result.success()
            }
        } catch (error: CancellationException) {
            coordinator.cancel()
            throw error
        }
    }

    private companion object {
        const val RECOVERY_NOTIFICATION_ID = 305
        const val MAX_RECOVERY_AGE_MS = 24L * 60L * 60L * 1_000L
    }
}

internal fun benchmarkForegroundInfo(context: Context, notificationId: Int): ForegroundInfo {
    val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        manager?.createNotificationChannel(
            NotificationChannel(
                BENCHMARK_WORK_CHANNEL,
                "Scheduled proxy checks",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Charging and unmetered-network proxy verification."
                setShowBadge(false)
            }
        )
    }
    val notification = NotificationCompat.Builder(context, BENCHMARK_WORK_CHANNEL)
        .setSmallIcon(android.R.drawable.stat_sys_download)
        .setContentTitle("ProxyX scheduled verification")
        .setContentText("Testing a bounded proxy sample on the active network")
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .build()
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    } else {
        ForegroundInfo(notificationId, notification)
    }
}

private const val BENCHMARK_WORK_CHANNEL = "proxy_x_scheduled_benchmark"
