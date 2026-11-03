package com.example.data.benchmark

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobParameters
import android.app.job.JobService
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.ProxyXApplication
import com.example.data.worker.ProxyBenchmarkScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class BenchmarkJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var activeJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartJob(params: JobParameters): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return false
        // A killed process can leave a UIDT job scheduled without calling onStopJob.
        // Recovery must honor the user's setting and never repeat Telegram transfers.
        if (!BenchmarkExecution.isCurrentProcessRequest(
                params.extras.getString(BenchmarkExecution.EXTRA_PROCESS_SESSION_TOKEN)
            )
        ) {
            ProxyBenchmarkScheduler.enqueueInterruptedRecovery(applicationContext)
            return false
        }
        val count = params.extras.getInt(BenchmarkExecution.EXTRA_COUNT, 1).coerceAtLeast(1)
        val telegram = params.extras.getBoolean(BenchmarkExecution.EXTRA_TELEGRAM_TRANSFER, false)
        val accuracyMode = BenchmarkAccuracyMode.fromStoredValue(
            params.extras.getString(BenchmarkExecution.EXTRA_ACCURACY_MODE)
        )
        val coordinator = (application as ProxyXApplication).appContainer.benchmarkCoordinator
        setNotification(
            params,
            NOTIFICATION_ID,
            notification(BenchmarkRuntimeState(status = BenchmarkRunStatus.RUNNING), indeterminate = true),
            JOB_END_NOTIFICATION_POLICY_REMOVE
        )
        activeJob?.cancel()
        activeJob = scope.launch {
            val requestedAt = System.currentTimeMillis()
            val started = coordinator.start(count, telegram, accuracyMode)
            if (!started) {
                jobFinished(params, false)
                return@launch
            }
            var lastNotificationAt = 0L
            val monitor = launch {
                coordinator.state.collect { state ->
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastNotificationAt >= NOTIFICATION_UPDATE_INTERVAL_MS || !state.isActive) {
                        setNotification(
                            params,
                            NOTIFICATION_ID,
                            notification(state, indeterminate = state.progress == null),
                            JOB_END_NOTIFICATION_POLICY_REMOVE
                        )
                        lastNotificationAt = now
                    }
                }
            }
            coordinator.state.first { state ->
                state.startedAt >= requestedAt &&
                    !state.isActive &&
                    state.status != BenchmarkRunStatus.IDLE
            }
            monitor.cancelAndJoin()
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        (application as? ProxyXApplication)?.appContainer?.benchmarkCoordinator?.cancel()
        activeJob?.cancel()
        activeJob = null
        // This is a user-initiated run. Re-enqueuing after app cancellation or a system stop
        // can unexpectedly restart a benchmark the user just cancelled; recovery is handled
        // explicitly by BenchmarkRecoveryWorker instead.
        return false
    }

    override fun onDestroy() {
        activeJob?.cancel()
        activeJob = null
        scope.cancel()
        super.onDestroy()
    }

    private fun notification(state: BenchmarkRuntimeState, indeterminate: Boolean): Notification {
        val progress = state.overallFraction
        val percent = (progress.coerceIn(0f, 1f) * 100f).toInt()
        val title = when (state.status) {
            BenchmarkRunStatus.PAUSED -> "Proxy benchmark paused"
            BenchmarkRunStatus.COMPLETED -> "Proxy benchmark completed"
            BenchmarkRunStatus.CANCELLED -> "Proxy benchmark cancelled"
            BenchmarkRunStatus.FAILED -> "Proxy benchmark failed"
            else -> "Testing proxies on this network"
        }
        val text = state.telegramTransferProgress?.let {
            "TDLib ${it.completedCount}/${it.totalCount} • ${it.succeededCount} passed"
        } ?: state.progress?.let {
            "Stage ${it.stage.value} • ${it.stageCompleted}/${it.stageTotal} • $percent%"
        } ?: "Preparing current-network benchmark"
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(state.isActive)
            .setContentIntent(openIntent)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        if (state.isActive) {
            builder.setProgress(100, percent, indeterminate)
            builder.addAction(
                0,
                if (state.status == BenchmarkRunStatus.PAUSED) "Resume" else "Pause",
                controlIntent(
                    if (state.status == BenchmarkRunStatus.PAUSED) {
                        BenchmarkControlReceiver.ACTION_RESUME
                    } else {
                        BenchmarkControlReceiver.ACTION_PAUSE
                    },
                    REQUEST_TOGGLE
                )
            )
            builder.addAction(
                0,
                "Cancel",
                controlIntent(BenchmarkControlReceiver.ACTION_CANCEL, REQUEST_CANCEL)
            )
        }
        return builder.build()
    }

    private fun controlIntent(action: String, requestCode: Int): PendingIntent {
        return PendingIntent.getBroadcast(
            this,
            requestCode,
            Intent(this, BenchmarkControlReceiver::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NOTIFICATION_SERVICE) as? NotificationManager ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Proxy benchmark", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Progress and controls for user-initiated proxy benchmarks."
                setShowBadge(false)
            }
        )
    }

    private companion object {
        const val CHANNEL_ID = "proxy_x_benchmark_uidt"
        const val NOTIFICATION_ID = 203
        const val REQUEST_TOGGLE = 21
        const val REQUEST_CANCEL = 22
        const val NOTIFICATION_UPDATE_INTERVAL_MS = 750L
    }
}
