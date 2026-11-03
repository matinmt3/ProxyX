package com.example.data.benchmark

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.PersistableBundle
import java.util.UUID

object BenchmarkExecution {
    internal val processSessionToken: String = UUID.randomUUID().toString()

    internal fun isCurrentProcessRequest(token: String?): Boolean = token == processSessionToken

    fun start(
        context: Context,
        count: Int,
        runTelegramFinalistTransfers: Boolean,
        accuracyMode: BenchmarkAccuracyMode = BenchmarkAccuracyMode.BALANCED,
        workers: Int = 12,
        timeoutMs: Int = 3_000,
        telegramBudgetMb: Int = 0
    ): Boolean {
        val safeCount = count.coerceAtLeast(1)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            BenchmarkForegroundService.start(
                context = context,
                count = safeCount,
                runTelegramFinalistTransfers = runTelegramFinalistTransfers,
                accuracyMode = accuracyMode
            )
            return true
        }
        val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler
            ?: return false
        val extras = PersistableBundle().apply {
            putInt(EXTRA_COUNT, safeCount)
            putBoolean(EXTRA_TELEGRAM_TRANSFER, runTelegramFinalistTransfers)
            putString(EXTRA_ACCURACY_MODE, accuracyMode.name)
            putString(EXTRA_PROCESS_SESSION_TOKEN, processSessionToken)
        }
        val estimate = BenchmarkEstimateCalculator.calculate(
            proxyCount = safeCount,
            workers = workers,
            timeoutMs = timeoutMs,
            mode = accuracyMode,
            telegramTransfers = runTelegramFinalistTransfers,
            telegramBudgetMb = telegramBudgetMb
        )
        val estimatedNetworkBytes = (estimate.estimatedProbeBytes + estimate.maximumTransferBytes)
            .coerceAtLeast(1L)
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val job = JobInfo.Builder(
            USER_INITIATED_JOB_ID,
            ComponentName(context, BenchmarkJobService::class.java)
        )
            .setUserInitiated(true)
            .setRequiredNetwork(request)
            .setEstimatedNetworkBytes(
                estimatedNetworkBytes / 2L,
                estimatedNetworkBytes - estimatedNetworkBytes / 2L
            )
            .setExtras(extras)
            .build()
        return scheduler.schedule(job) == JobScheduler.RESULT_SUCCESS
    }

    fun cancel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            (context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler)
                ?.cancel(USER_INITIATED_JOB_ID)
        } else {
            BenchmarkForegroundService.cancel(context)
        }
    }

    fun cancelScheduled(context: Context) = cancel(context)

    internal const val EXTRA_COUNT = "count"
    internal const val EXTRA_TELEGRAM_TRANSFER = "telegram_transfer"
    internal const val EXTRA_ACCURACY_MODE = "accuracy_mode"
    internal const val EXTRA_PROCESS_SESSION_TOKEN = "process_session_token"
    private const val USER_INITIATED_JOB_ID = 4_270
}
