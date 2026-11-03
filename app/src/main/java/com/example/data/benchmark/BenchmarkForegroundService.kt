package com.example.data.benchmark

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.example.MainActivity
import com.example.ProxyXApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class BenchmarkForegroundService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var coordinator: BenchmarkCoordinator
    private var stateJob: Job? = null
    private var terminalStopJob: Job? = null
    private var startJob: Job? = null
    private var startCommandPending = false
    private var startCommandToken = 0L

    override fun onCreate() {
        super.onCreate()
        coordinator = (application as ProxyXApplication).appContainer.benchmarkCoordinator
        createNotificationChannel()
        stateJob = serviceScope.launch {
            coordinator.state.collectLatest(::handleState)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val count = intent.getIntExtra(EXTRA_COUNT, 1).coerceAtLeast(1)
                val runTelegramFinalistTransfers = intent.getBooleanExtra(
                    EXTRA_TELEGRAM_FINALIST_TRANSFER,
                    false
                )
                val accuracyMode = BenchmarkAccuracyMode.fromStoredValue(
                    intent.getStringExtra(EXTRA_ACCURACY_MODE)
                )
                terminalStopJob?.cancel()
                terminalStopJob = null
                startJob?.cancel()
                val token = ++startCommandToken
                startCommandPending = true
                // Fulfil Android's foreground-service deadline before any coroutine dispatch or
                // stale terminal state from the previous benchmark can stop this service.
                startInForeground(preparingState(count, accuracyMode))
                startJob = serviceScope.launch {
                    try {
                        val started = coordinator.start(
                            requestedCount = count,
                            enableTelegramFinalistTransfers = runTelegramFinalistTransfers,
                            accuracyMode = accuracyMode
                        )
                        if (!started) {
                            val currentState = coordinator.state.value
                            if (currentState.isActive) {
                                startInForeground(currentState)
                            } else {
                                finishAfterTerminalState(currentState)
                            }
                        }
                    } finally {
                        if (startCommandToken == token) {
                            startCommandPending = false
                            startJob = null
                        }
                    }
                }
            }
            ACTION_PAUSE -> coordinator.pause()
            ACTION_RESUME -> coordinator.resume()
            ACTION_CANCEL -> cancelPendingOrActiveBenchmark()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        terminalStopJob?.cancel()
        startJob?.cancel()
        stateJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        cancelPendingOrActiveBenchmark()
        stopSelf(startId)
    }

    private fun cancelPendingOrActiveBenchmark() {
        ++startCommandToken
        startCommandPending = false
        startJob?.cancel()
        startJob = null
        coordinator.cancel()
        val state = coordinator.state.value
        if (!state.isActive) {
            finishAfterTerminalState(
                state.copy(
                    status = BenchmarkRunStatus.CANCELLED,
                    finishedAt = System.currentTimeMillis()
                )
            )
        }
    }

    private fun handleState(state: BenchmarkRuntimeState) {
        when {
            state.isActive -> {
                terminalStopJob?.cancel()
                terminalStopJob = null
                startInForeground(state)
            }
            startCommandPending -> Unit
            state.status == BenchmarkRunStatus.IDLE -> Unit
            else -> finishAfterTerminalState(state)
        }
    }

    private fun finishAfterTerminalState(state: BenchmarkRuntimeState) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE)
            as? NotificationManager
        notificationManager?.notify(NOTIFICATION_ID, buildNotification(state))
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
        terminalStopJob?.cancel()
        terminalStopJob = serviceScope.launch {
            // A new START may arrive while Android is still delivering the previous terminal
            // StateFlow value. Keeping this stop cancellable prevents a restart race.
            delay(TERMINAL_STOP_GRACE_MS)
            if (!startCommandPending && !coordinator.state.value.isActive) stopSelf()
        }
    }

    private fun preparingState(
        count: Int,
        accuracyMode: BenchmarkAccuracyMode
    ): BenchmarkRuntimeState {
        return BenchmarkRuntimeState(
            status = BenchmarkRunStatus.RUNNING,
            requestedCount = count.coerceAtLeast(1),
            accuracyMode = accuracyMode,
            startedAt = System.currentTimeMillis()
        )
    }

    private fun startInForeground(state: BenchmarkRuntimeState) {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(state),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            }
        )
    }

    private fun buildNotification(state: BenchmarkRuntimeState): Notification {
        val progress = state.progress
        val telegramProgress = state.telegramTransferProgress
        val fraction = state.overallFraction
        val percent = (fraction * 100f).toInt().coerceIn(0, 100)
        val stage = if (telegramProgress != null) {
            "TDLib transfer"
        } else {
            progress?.stage?.name?.lowercase()?.replaceFirstChar(Char::uppercase) ?: "Preparing"
        }
        val title = when (state.status) {
            BenchmarkRunStatus.PAUSED -> "Proxy benchmark paused"
            BenchmarkRunStatus.COMPLETED -> "Proxy benchmark completed"
            BenchmarkRunStatus.CANCELLED -> "Proxy benchmark cancelled"
            BenchmarkRunStatus.FAILED -> "Proxy benchmark failed"
            else -> "Testing proxies on this network"
        }
        val text = when {
            state.status == BenchmarkRunStatus.FAILED -> failureText(state.failure)
            state.status == BenchmarkRunStatus.COMPLETED ->
                buildCompletedText(state.result)
            state.status == BenchmarkRunStatus.CANCELLED -> "Completed results were saved."
            telegramProgress != null ->
                "TDLib: ${telegramProgress.completedCount}/${telegramProgress.totalCount}, " +
                    "${telegramProgress.succeededCount} passed"
            else -> "$stage: $percent% • ${state.selectedWorkers.coerceAtLeast(1)} workers"
        }
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
            builder.setProgress(100, percent, progress == null)
            val toggleAction = if (state.status == BenchmarkRunStatus.PAUSED) {
                actionIntent(ACTION_RESUME, REQUEST_RESUME)
            } else {
                actionIntent(ACTION_PAUSE, REQUEST_PAUSE)
            }
            builder.addAction(
                0,
                if (state.status == BenchmarkRunStatus.PAUSED) "Resume" else "Pause",
                toggleAction
            )
            builder.addAction(0, "Cancel", actionIntent(ACTION_CANCEL, REQUEST_CANCEL))
        }
        return builder.build()
    }

    private fun actionIntent(action: String, requestCode: Int): PendingIntent {
        return PendingIntent.getService(
            this,
            requestCode,
            Intent(this, BenchmarkForegroundService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Proxy benchmark",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Progress and controls for long proxy benchmarks."
                setShowBadge(false)
            }
        )
    }

    private fun failureText(code: String?): String = when (code) {
        "METERED_NETWORK" -> "Waiting for an unmetered network setting change."
        "NETWORK_CHANGED" -> "The active network was lost or changed."
        else -> "The benchmark stopped after an unexpected error."
    }

    private fun buildCompletedText(result: ThreeStageBenchmarkResult?): String {
        if (result == null) return "Benchmark completed."
        return if (result.telegramTransferAttemptedCount > 0 || result.telegramTransferSkippedCount > 0) {
            "${result.screenedCount} screened, ${result.telegramTransferSucceededCount} TDLib passed"
        } else {
            "${result.screenedCount} screened, ${result.finalistCount} finalists"
        }
    }

    companion object {
        private const val CHANNEL_ID = "proxy_x_benchmark"
        private const val NOTIFICATION_ID = 202
        private const val EXTRA_COUNT = "count"
        private const val EXTRA_TELEGRAM_FINALIST_TRANSFER = "telegram_finalist_transfer"
        private const val EXTRA_ACCURACY_MODE = "accuracy_mode"
        private const val ACTION_START = "com.example.action.START_BENCHMARK"
        private const val ACTION_PAUSE = "com.example.action.PAUSE_BENCHMARK"
        private const val ACTION_RESUME = "com.example.action.RESUME_BENCHMARK"
        private const val ACTION_CANCEL = "com.example.action.CANCEL_BENCHMARK"
        private const val REQUEST_PAUSE = 11
        private const val REQUEST_RESUME = 12
        private const val REQUEST_CANCEL = 13
        private const val TERMINAL_STOP_GRACE_MS = 1_000L

        fun start(
            context: Context,
            count: Int,
            runTelegramFinalistTransfers: Boolean = false,
            accuracyMode: BenchmarkAccuracyMode = BenchmarkAccuracyMode.BALANCED
        ) {
            val intent = Intent(context, BenchmarkForegroundService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_COUNT, count.coerceAtLeast(1))
                .putExtra(EXTRA_TELEGRAM_FINALIST_TRANSFER, runTelegramFinalistTransfers)
                .putExtra(EXTRA_ACCURACY_MODE, accuracyMode.name)
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }

        fun pause(context: Context) = send(context, ACTION_PAUSE)
        fun resume(context: Context) = send(context, ACTION_RESUME)
        fun cancel(context: Context) = send(context, ACTION_CANCEL)

        private fun send(context: Context, action: String) {
            context.startService(
                Intent(context, BenchmarkForegroundService::class.java).setAction(action)
            )
        }
    }
}
