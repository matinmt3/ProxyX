package com.example

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import com.example.data.worker.ProxyRefreshWorker
import com.example.data.worker.ProxyWorkScheduler
import com.example.data.worker.PrivateNodeMonitorWorker
import com.example.data.worker.PrivateNodeMonitorScheduler
import com.example.data.worker.ProxyBenchmarkScheduler
import com.example.data.worker.ScheduledBenchmarkWorker
import com.example.data.worker.BenchmarkRecoveryWorker
import com.example.di.AppContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class ProxyXApplication : Application(), Configuration.Provider {
    lateinit var appContainer: AppContainer
        private set

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        appContainer = AppContainer(this)
        schedulePeriodicUpdates()
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setJobSchedulerJobIdRange(WORK_MANAGER_JOB_ID_MIN, WORK_MANAGER_JOB_ID_MAX)
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters
                ): ListenableWorker? {
                    return when (workerClassName) {
                        ProxyRefreshWorker::class.java.name -> ProxyRefreshWorker(
                            appContext = appContext,
                            workerParams = workerParameters,
                            proxyRepository = appContainer.proxyRepository,
                            settingsRepository = appContainer.settingsRepository
                        )
                        PrivateNodeMonitorWorker::class.java.name -> PrivateNodeMonitorWorker(
                            appContext = appContext,
                            workerParams = workerParameters,
                            proxyRepository = appContainer.proxyRepository,
                            settingsRepository = appContainer.settingsRepository
                        )
                        ScheduledBenchmarkWorker::class.java.name -> ScheduledBenchmarkWorker(
                            appContext = appContext,
                            workerParams = workerParameters,
                            coordinator = appContainer.benchmarkCoordinator,
                            repository = appContainer.proxyRepository,
                            settingsRepository = appContainer.settingsRepository
                        )
                        BenchmarkRecoveryWorker::class.java.name -> BenchmarkRecoveryWorker(
                            appContext = appContext,
                            workerParams = workerParameters,
                            coordinator = appContainer.benchmarkCoordinator,
                            repository = appContainer.proxyRepository,
                            settingsRepository = appContainer.settingsRepository
                        )
                        else -> null
                    }
                }
            })
            .build()

    private fun schedulePeriodicUpdates() {
        applicationScope.launch {
            try {
                val settings = combine(
                    appContainer.settingsRepository.autoRefreshEnabled,
                    appContainer.settingsRepository.autoRefreshInterval,
                    appContainer.settingsRepository.unmeteredOnly
                ) { isEnabled, minutes, unmeteredOnly ->
                    Triple(isEnabled, minutes, unmeteredOnly)
                }.first()
                ProxyWorkScheduler.configure(
                    this@ProxyXApplication,
                    settings.first,
                    settings.second,
                    settings.third
                )
            } catch (e: CancellationException) {
                throw e
            } catch (error: Exception) {
                Log.e(TAG, "Unable to schedule background proxy updates.", error)
            }
        }
        applicationScope.launch {
            try {
                ProxyBenchmarkScheduler.configure(
                    context = this@ProxyXApplication,
                    enabled = appContainer.settingsRepository.scheduledBenchmarkEnabled.first(),
                    intervalHours = appContainer.settingsRepository
                        .scheduledBenchmarkIntervalHours.first(),
                    proxyCount = appContainer.settingsRepository.scheduledBenchmarkCount.first()
                )
                ProxyBenchmarkScheduler.enqueueInterruptedRecovery(this@ProxyXApplication)
            } catch (e: CancellationException) {
                throw e
            } catch (error: Exception) {
                Log.e(TAG, "Unable to schedule the periodic benchmark.", error)
            }
        }
        applicationScope.launch {
            try {
                PrivateNodeMonitorScheduler.configure(
                    context = this@ProxyXApplication,
                    enabled = appContainer.settingsRepository.privateNodeMonitoringEnabled.first(),
                    unmeteredOnly = appContainer.settingsRepository.unmeteredOnly.first()
                )
            } catch (e: CancellationException) {
                throw e
            } catch (error: Exception) {
                Log.e(TAG, "Unable to schedule private-node monitoring.", error)
            }
        }
    }

    private companion object {
        const val TAG = "ProxyXApplication"
        const val WORK_MANAGER_JOB_ID_MIN = 10_000
        const val WORK_MANAGER_JOB_ID_MAX = 20_000
    }
}
