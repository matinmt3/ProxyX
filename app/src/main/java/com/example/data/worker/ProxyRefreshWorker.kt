package com.example.data.worker

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.data.repository.ProxyRepository
import com.example.data.repository.SettingsRepository
import com.example.data.network.NetworkUsagePolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

class ProxyRefreshWorker(
    appContext: Context,
    workerParams: WorkerParameters,
    private val proxyRepository: ProxyRepository,
    private val settingsRepository: SettingsRepository
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            Log.d("ProxyRefreshWorker", "Background refresh started.")
            if (!settingsRepository.autoRefreshEnabled.first()) {
                Log.d(TAG, "Background refresh is disabled; finishing without network work.")
                return Result.success()
            }
            val parallelWorkers = backgroundParallelWorkers(
                settingsRepository.parallelWorkers.first()
            )
            val timeoutMs = settingsRepository.timeoutMs.first()
            val notificationsEnabled = settingsRepository.notificationsEnabled.first()
            NetworkUsagePolicy(applicationContext).requireAllowed(
                settingsRepository.unmeteredOnly.first()
            )

            val refreshResult = proxyRepository.collectAndCacheProxies()
            if (!refreshResult.cacheReplaced) {
                Log.w(TAG, "No fresh source data; retaining the previous cache and retrying later.")
                return Result.retry()
            }
            if (!refreshResult.completed) {
                Log.w(TAG, "Refresh stopped after a partial commit; retrying without discarding it.")
                return Result.retry()
            }

            val verifiedCount = proxyRepository.verifyStoredProxies(
                parallelWorkers = parallelWorkers,
                timeoutMs = timeoutMs,
                limit = BACKGROUND_BENCHMARK_LIMIT
            )

            if (notificationsEnabled) {
                val workingCount = proxyRepository.countTelegramVerified()
                showNotification(refreshResult.proxyCount, verifiedCount, workingCount)
            }

            Log.d(TAG, "Background refresh completed successfully.")
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Background refresh failed.", e)
            Result.retry()
        }
    }

    private fun showNotification(refreshedCount: Int, verifiedCount: Int, workingTotal: Int) {
        val channelId = "proxy_x_updates"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            androidx.core.content.ContextCompat.checkSelfPermission(
                applicationContext,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE)
            as? NotificationManager ?: return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "ProxyX Updates",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notifies about background proxy updates and performance benchmarks."
            }
            notificationManager.createNotificationChannel(channel)
        }

        val message = "Refreshed $refreshedCount proxies, tested $verifiedCount, " +
            "$workingTotal currently reachable."

        val builder = NotificationCompat.Builder(applicationContext, channelId)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("ProxyX List Refreshed")
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)

        notificationManager.notify(101, builder.build())
    }

    private companion object {
        const val TAG = "ProxyRefreshWorker"
    }
}

internal const val BACKGROUND_BENCHMARK_LIMIT = 12
internal const val BACKGROUND_MAX_PARALLEL_WORKERS = 2

internal fun backgroundParallelWorkers(configuredWorkers: Int): Int {
    return configuredWorkers.coerceIn(1, BACKGROUND_MAX_PARALLEL_WORKERS)
}
