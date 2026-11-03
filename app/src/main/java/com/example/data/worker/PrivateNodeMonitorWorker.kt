package com.example.data.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.data.repository.ProxyRepository
import com.example.data.repository.SettingsRepository
import com.example.data.network.NetworkUsagePolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

class PrivateNodeMonitorWorker(
    appContext: Context,
    workerParams: WorkerParameters,
    private val proxyRepository: ProxyRepository,
    private val settingsRepository: SettingsRepository
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        if (!settingsRepository.privateNodeMonitoringEnabled.first()) return Result.success()
        return try {
            val timeout = settingsRepository.timeoutMs.first()
            val networkPolicy = NetworkUsagePolicy(applicationContext)
            val failedLabels = buildList {
                proxyRepository.getPrivateNodesSnapshot()
                    .asSequence()
                    .filter { it.enabled }
                    .take(MAX_NODES_PER_RUN)
                    .forEach { node ->
                        networkPolicy.requireAllowed(settingsRepository.unmeteredOnly.first())
                        val wasHealthy = node.isHealthy
                        val updated = proxyRepository.testPrivateNode(node.url, timeout)
                        val isHealthy = updated?.isHealthy == true
                        if (wasHealthy && !isHealthy) add(node.label)
                    }
            }
            if (failedLabels.isNotEmpty() && settingsRepository.notificationsEnabled.first()) {
                notifyFailures(failedLabels)
            }
            Result.success()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            Result.retry()
        }
    }

    private fun notifyFailures(labels: List<String>) {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE)
            as? NotificationManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Private proxy health", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
        val summary = labels.take(3).joinToString(", ")
        manager.notify(
            NOTIFICATION_ID,
            NotificationCompat.Builder(applicationContext, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle("Private MTProto node degraded")
                .setContentText(summary)
                .setStyle(NotificationCompat.BigTextStyle().bigText(summary))
                .setAutoCancel(true)
                .build()
        )
    }

    private companion object {
        const val MAX_NODES_PER_RUN = 5
        const val CHANNEL_ID = "proxy_x_private_health"
        const val NOTIFICATION_ID = 206
    }
}
