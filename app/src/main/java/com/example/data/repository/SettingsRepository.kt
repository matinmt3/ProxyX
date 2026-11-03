package com.example.data.repository

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.data.worker.ProxyWorkScheduler
import com.example.data.benchmark.TelegramTransferBudgetPolicy
import com.example.data.worker.PrivateNodeMonitorScheduler
import com.example.data.worker.ProxyBenchmarkScheduler
import com.example.data.benchmark.BenchmarkAccuracyMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "proxy_x_settings")

class SettingsRepository(private val context: Context) {

    private val preferences: Flow<Preferences> = context.dataStore.data.catch { error ->
        if (error is IOException) emit(emptyPreferences()) else throw error
    }

    private companion object {
        val KEY_THEME = stringPreferencesKey("theme")
        val KEY_NETWORK_RECHECK = booleanPreferencesKey("network_shortlist_recheck")
        val KEY_AUTO_REFRESH_INTERVAL = intPreferencesKey("auto_refresh_interval")
        val KEY_AUTO_REFRESH_ENABLED = booleanPreferencesKey("auto_refresh_enabled")
        val KEY_TIMEOUT = intPreferencesKey("timeout")
        val KEY_PARALLEL_WORKERS = intPreferencesKey("parallel_workers")
        val KEY_ADAPTIVE_WORKERS = booleanPreferencesKey("adaptive_workers")
        val KEY_UNMETERED_ONLY = booleanPreferencesKey("unmetered_only")
        val KEY_TELEGRAM_FINALIST_TRANSFER = booleanPreferencesKey("telegram_finalist_transfer")
        val KEY_TELEGRAM_TRANSFER_BUDGET_MB = intPreferencesKey("telegram_transfer_budget_mb")
        val KEY_NOTIFICATIONS = booleanPreferencesKey("notifications")
        val KEY_LANGUAGE = stringPreferencesKey("language")
        val KEY_CHANNEL_PROMPT_SEEN = booleanPreferencesKey("channel_prompt_seen")
        val KEY_PRIVATE_NODE_MONITORING = booleanPreferencesKey("private_node_monitoring")
        val KEY_BENCHMARK_ACCURACY = stringPreferencesKey("benchmark_accuracy")
        val KEY_AUTO_RESUME_BENCHMARK = booleanPreferencesKey("auto_resume_benchmark")
        val KEY_SCHEDULED_BENCHMARK = booleanPreferencesKey("scheduled_benchmark")
        val KEY_SCHEDULED_BENCHMARK_INTERVAL = intPreferencesKey("scheduled_benchmark_interval")
        val KEY_SCHEDULED_BENCHMARK_COUNT = intPreferencesKey("scheduled_benchmark_count")
        private const val TAG = "SettingsRepository"
        private val ALLOWED_THEMES = setOf("system", "light", "dark")
        private val ALLOWED_LANGUAGES = setOf("system", "en", "fa")
    }

    val theme: Flow<String> = preferences.map {
        it[KEY_THEME]?.takeIf(ALLOWED_THEMES::contains) ?: "dark"
    }
    val networkRecheckEnabled: Flow<Boolean> = preferences.map { it[KEY_NETWORK_RECHECK] ?: false }

    suspend fun setNetworkRecheckEnabled(enabled: Boolean) {
        context.dataStore.edit { it[KEY_NETWORK_RECHECK] = enabled }
    }
    val autoRefreshInterval: Flow<Int> = preferences.map {
        ProxyWorkScheduler.normalizeInterval(it[KEY_AUTO_REFRESH_INTERVAL] ?: 15)
    }
    val autoRefreshEnabled: Flow<Boolean> = preferences.map {
        it[KEY_AUTO_REFRESH_ENABLED] ?: true
    }
    val timeoutMs: Flow<Int> = preferences.map {
        (it[KEY_TIMEOUT] ?: 3000).coerceIn(750, 10_000)
    }
    val parallelWorkers: Flow<Int> = preferences.map {
        (it[KEY_PARALLEL_WORKERS] ?: 12).coerceIn(1, 16)
    }
    val adaptiveWorkersEnabled: Flow<Boolean> = preferences.map {
        it[KEY_ADAPTIVE_WORKERS] ?: true
    }
    val unmeteredOnly: Flow<Boolean> = preferences.map {
        it[KEY_UNMETERED_ONLY] ?: false
    }
    val telegramFinalistTransferEnabled: Flow<Boolean> = preferences.map {
        it[KEY_TELEGRAM_FINALIST_TRANSFER] ?: false
    }
    val telegramTransferDataBudgetMb: Flow<Int> = preferences.map {
        TelegramTransferBudgetPolicy.normalizeBudgetMb(
            it[KEY_TELEGRAM_TRANSFER_BUDGET_MB] ?: 0
        )
    }
    val notificationsEnabled: Flow<Boolean> = preferences.map { it[KEY_NOTIFICATIONS] ?: true }
    val language: Flow<String> = preferences.map {
        it[KEY_LANGUAGE]?.takeIf(ALLOWED_LANGUAGES::contains) ?: "system"
    }
    val channelPromptSeen: Flow<Boolean> = preferences.map {
        it[KEY_CHANNEL_PROMPT_SEEN] ?: false
    }
    val privateNodeMonitoringEnabled: Flow<Boolean> = preferences.map {
        it[KEY_PRIVATE_NODE_MONITORING] ?: false
    }
    val benchmarkAccuracyMode: Flow<BenchmarkAccuracyMode> = preferences.map {
        BenchmarkAccuracyMode.fromStoredValue(it[KEY_BENCHMARK_ACCURACY])
    }
    val autoResumeBenchmark: Flow<Boolean> = preferences.map {
        it[KEY_AUTO_RESUME_BENCHMARK] ?: true
    }
    val scheduledBenchmarkEnabled: Flow<Boolean> = preferences.map {
        it[KEY_SCHEDULED_BENCHMARK] ?: false
    }
    val scheduledBenchmarkIntervalHours: Flow<Int> = preferences.map {
        ProxyBenchmarkScheduler.normalizeIntervalHours(
            it[KEY_SCHEDULED_BENCHMARK_INTERVAL]
                ?: ProxyBenchmarkScheduler.DEFAULT_INTERVAL_HOURS
        )
    }
    val scheduledBenchmarkCount: Flow<Int> = preferences.map {
        ProxyBenchmarkScheduler.normalizeCount(
            it[KEY_SCHEDULED_BENCHMARK_COUNT]
                ?: ProxyBenchmarkScheduler.DEFAULT_PROXY_COUNT
        )
    }

    suspend fun setTheme(theme: String) {
        context.dataStore.edit { it[KEY_THEME] = theme.takeIf(ALLOWED_THEMES::contains) ?: "system" }
    }

    suspend fun setAutoRefreshInterval(minutes: Int) {
        val normalizedMinutes = ProxyWorkScheduler.normalizeInterval(minutes)
        context.dataStore.edit { it[KEY_AUTO_REFRESH_INTERVAL] = normalizedMinutes }
        try {
            ProxyWorkScheduler.configure(
                context = context,
                enabled = autoRefreshEnabled.first(),
                intervalMinutes = normalizedMinutes,
                unmeteredOnly = unmeteredOnly.first()
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Unable to reschedule background refresh.", e)
        }
    }

    suspend fun setAutoRefreshEnabled(enabled: Boolean) {
        context.dataStore.edit { it[KEY_AUTO_REFRESH_ENABLED] = enabled }
        try {
            ProxyWorkScheduler.configure(
                context = context,
                enabled = enabled,
                intervalMinutes = autoRefreshInterval.first(),
                unmeteredOnly = unmeteredOnly.first()
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Unable to update background refresh state.", e)
        }
    }

    suspend fun setTimeoutMs(timeoutMs: Int) {
        context.dataStore.edit { it[KEY_TIMEOUT] = timeoutMs.coerceIn(750, 10_000) }
    }

    suspend fun setParallelWorkers(workers: Int) {
        context.dataStore.edit { it[KEY_PARALLEL_WORKERS] = workers.coerceIn(1, 16) }
    }

    suspend fun setAdaptiveWorkersEnabled(enabled: Boolean) {
        context.dataStore.edit { it[KEY_ADAPTIVE_WORKERS] = enabled }
    }

    suspend fun setUnmeteredOnly(enabled: Boolean) {
        context.dataStore.edit { it[KEY_UNMETERED_ONLY] = enabled }
        try {
            ProxyWorkScheduler.configure(
                context = context,
                enabled = autoRefreshEnabled.first(),
                intervalMinutes = autoRefreshInterval.first(),
                unmeteredOnly = enabled
            )
            PrivateNodeMonitorScheduler.configure(
                context = context,
                enabled = privateNodeMonitoringEnabled.first(),
                unmeteredOnly = enabled
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Unable to update network usage policy.", e)
        }
    }

    suspend fun setTelegramFinalistTransferEnabled(enabled: Boolean) {
        context.dataStore.edit { it[KEY_TELEGRAM_FINALIST_TRANSFER] = enabled }
    }

    suspend fun setTelegramTransferDataBudgetMb(budgetMb: Int) {
        context.dataStore.edit {
            it[KEY_TELEGRAM_TRANSFER_BUDGET_MB] =
                TelegramTransferBudgetPolicy.normalizeBudgetMb(budgetMb)
        }
    }

    suspend fun setNotificationsEnabled(enabled: Boolean) {
        context.dataStore.edit { it[KEY_NOTIFICATIONS] = enabled }
    }

    suspend fun setLanguage(language: String) {
        context.dataStore.edit {
            it[KEY_LANGUAGE] = language.takeIf(ALLOWED_LANGUAGES::contains) ?: "system"
        }
    }

    suspend fun markChannelPromptSeen() {
        context.dataStore.edit { it[KEY_CHANNEL_PROMPT_SEEN] = true }
    }

    suspend fun setPrivateNodeMonitoringEnabled(enabled: Boolean) {
        context.dataStore.edit { it[KEY_PRIVATE_NODE_MONITORING] = enabled }
        PrivateNodeMonitorScheduler.configure(
            context = context,
            enabled = enabled,
            unmeteredOnly = unmeteredOnly.first()
        )
    }

    suspend fun setBenchmarkAccuracyMode(mode: BenchmarkAccuracyMode) {
        context.dataStore.edit { it[KEY_BENCHMARK_ACCURACY] = mode.name }
    }

    suspend fun setAutoResumeBenchmark(enabled: Boolean) {
        context.dataStore.edit { it[KEY_AUTO_RESUME_BENCHMARK] = enabled }
        if (enabled) ProxyBenchmarkScheduler.enqueueInterruptedRecovery(context)
    }

    suspend fun setScheduledBenchmarkEnabled(enabled: Boolean) {
        context.dataStore.edit { it[KEY_SCHEDULED_BENCHMARK] = enabled }
        configureScheduledBenchmark(enabled)
    }

    suspend fun setScheduledBenchmarkIntervalHours(hours: Int) {
        val normalized = ProxyBenchmarkScheduler.normalizeIntervalHours(hours)
        context.dataStore.edit { it[KEY_SCHEDULED_BENCHMARK_INTERVAL] = normalized }
        configureScheduledBenchmark(scheduledBenchmarkEnabled.first())
    }

    suspend fun setScheduledBenchmarkCount(count: Int) {
        val normalized = ProxyBenchmarkScheduler.normalizeCount(count)
        context.dataStore.edit { it[KEY_SCHEDULED_BENCHMARK_COUNT] = normalized }
        configureScheduledBenchmark(scheduledBenchmarkEnabled.first())
    }

    private suspend fun configureScheduledBenchmark(enabled: Boolean) {
        ProxyBenchmarkScheduler.configure(
            context = context,
            enabled = enabled,
            intervalHours = scheduledBenchmarkIntervalHours.first(),
            proxyCount = scheduledBenchmarkCount.first()
        )
    }

}
