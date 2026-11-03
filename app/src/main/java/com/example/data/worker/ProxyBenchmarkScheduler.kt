package com.example.data.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object ProxyBenchmarkScheduler {
    val allowedIntervalsHours = listOf(6, 12, 24, 48)

    fun normalizeIntervalHours(value: Int): Int {
        return value.takeIf(allowedIntervalsHours::contains) ?: DEFAULT_INTERVAL_HOURS
    }

    fun normalizeCount(value: Int): Int = value.coerceIn(MIN_PROXY_COUNT, MAX_PROXY_COUNT)

    fun configure(
        context: Context,
        enabled: Boolean,
        intervalHours: Int,
        proxyCount: Int
    ) {
        val workManager = WorkManager.getInstance(context.applicationContext)
        if (!enabled) {
            workManager.cancelUniqueWork(PERIODIC_WORK_NAME)
            return
        }
        val interval = normalizeIntervalHours(intervalHours)
        val request = PeriodicWorkRequestBuilder<ScheduledBenchmarkWorker>(
            interval.toLong(),
            TimeUnit.HOURS
        )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.UNMETERED)
                    .setRequiresCharging(true)
                    .setRequiresBatteryNotLow(true)
                    .build()
            )
            .setInputData(
                androidx.work.workDataOf(
                    ScheduledBenchmarkWorker.KEY_PROXY_COUNT to normalizeCount(proxyCount)
                )
            )
            .addTag(PERIODIC_WORK_NAME)
            .build()
        workManager.enqueueUniquePeriodicWork(
            PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    fun enqueueInterruptedRecovery(context: Context) {
        val request = OneTimeWorkRequestBuilder<BenchmarkRecoveryWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresBatteryNotLow(true)
                    .build()
            )
            .addTag(RECOVERY_WORK_NAME)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            RECOVERY_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request
        )
    }

    const val DEFAULT_INTERVAL_HOURS = 24
    const val DEFAULT_PROXY_COUNT = 100
    const val MIN_PROXY_COUNT = 25
    const val MAX_PROXY_COUNT = 250
    private const val PERIODIC_WORK_NAME = "proxy_x_scheduled_benchmark"
    private const val RECOVERY_WORK_NAME = "proxy_x_interrupted_benchmark_recovery"
}
