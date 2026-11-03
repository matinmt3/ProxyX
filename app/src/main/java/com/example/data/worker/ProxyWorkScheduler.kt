package com.example.data.worker

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object ProxyWorkScheduler {
    fun configure(
        context: Context,
        enabled: Boolean,
        intervalMinutes: Int,
        unmeteredOnly: Boolean = false
    ) {
        if (enabled) {
            schedule(context, intervalMinutes, unmeteredOnly)
        } else {
            cancel(context)
        }
    }

    fun schedule(
        context: Context,
        intervalMinutes: Int,
        unmeteredOnly: Boolean = false
    ) {
        val normalizedInterval = normalizeInterval(intervalMinutes)
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(
                if (unmeteredOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
            )
            .build()
        val workRequest = PeriodicWorkRequestBuilder<ProxyRefreshWorker>(
            normalizedInterval.toLong(),
            TimeUnit.MINUTES
        )
            // A freshly launched process is already creating Room, Compose and DataStore.
            // Giving the first frame a short head start prevents background source parsing from
            // competing with initial composition on memory-constrained phones.
            .setInitialDelay(INITIAL_REFRESH_DELAY_SECONDS, TimeUnit.SECONDS)
            .setConstraints(constraints)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                MIN_RETRY_BACKOFF_MINUTES,
                TimeUnit.MINUTES
            )
            .build()

        WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
            UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            workRequest
        )
        Log.d(TAG, "Scheduled proxy refresh every $normalizedInterval minutes.")
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(UNIQUE_WORK_NAME)
        Log.d(TAG, "Cancelled background proxy refresh.")
    }

    fun normalizeInterval(intervalMinutes: Int): Int {
        return intervalMinutes.coerceIn(MIN_INTERVAL_MINUTES, MAX_INTERVAL_MINUTES)
    }

    private const val TAG = "ProxyWorkScheduler"
    private const val UNIQUE_WORK_NAME = "ProxyXBackgroundRefresh"
    const val MIN_INTERVAL_MINUTES = 15
    const val MAX_INTERVAL_MINUTES = 7 * 24 * 60
    internal const val INITIAL_REFRESH_DELAY_SECONDS = 20L
    private const val MIN_RETRY_BACKOFF_MINUTES = 15L
}
