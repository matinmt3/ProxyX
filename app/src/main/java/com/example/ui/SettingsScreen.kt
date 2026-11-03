package com.example.ui

import android.Manifest
import android.content.Context
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.EventRepeat
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.NetworkPing
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.Update
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.BuildConfig
import com.example.data.network.ProxySource
import com.example.data.network.SourceCatalogOrigin
import com.example.data.network.SourceCatalogState
import com.example.data.network.SourceFetchResult
import com.example.data.benchmark.BenchmarkAccuracyMode
import com.example.data.worker.ProxyBenchmarkScheduler
import com.example.data.telegram.TelegramAuthorizationStep
import com.example.data.telegram.TelegramCodeDeliveryMethod
import com.example.data.telegram.TelegramTransferState
import com.example.model.ProxySourceHealth
import com.example.model.SourceWindowStats
import com.example.viewmodel.ProxyViewModel
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.delay

@Composable
fun SettingsScreen(
    viewModel: ProxyViewModel,
    modifier: Modifier = Modifier
) {
    val theme by viewModel.theme.collectAsStateWithLifecycle()
    val networkRecheckEnabled by viewModel.networkRecheckEnabled.collectAsStateWithLifecycle()
    val autoRefreshInterval by viewModel.autoRefreshInterval.collectAsStateWithLifecycle()
    val autoRefreshEnabled by viewModel.autoRefreshEnabled.collectAsStateWithLifecycle()
    val timeoutMs by viewModel.timeoutMs.collectAsStateWithLifecycle()
    val parallelWorkers by viewModel.parallelWorkers.collectAsStateWithLifecycle()
    val adaptiveWorkers by viewModel.adaptiveWorkersEnabled.collectAsStateWithLifecycle()
    val benchmarkAccuracyMode by viewModel.benchmarkAccuracyMode.collectAsStateWithLifecycle()
    val autoResumeBenchmark by viewModel.autoResumeBenchmark.collectAsStateWithLifecycle()
    val scheduledBenchmarkEnabled by viewModel.scheduledBenchmarkEnabled.collectAsStateWithLifecycle()
    val scheduledBenchmarkIntervalHours by viewModel.scheduledBenchmarkIntervalHours.collectAsStateWithLifecycle()
    val scheduledBenchmarkCount by viewModel.scheduledBenchmarkCount.collectAsStateWithLifecycle()
    val unmeteredOnly by viewModel.unmeteredOnly.collectAsStateWithLifecycle()
    val notificationsEnabled by viewModel.notificationsEnabled.collectAsStateWithLifecycle()
    val language by viewModel.language.collectAsStateWithLifecycle()
    val fetchResults by viewModel.sourceFetchResults.collectAsStateWithLifecycle()
    val proxySources by viewModel.proxySources.collectAsStateWithLifecycle()
    val sourceCatalogState by viewModel.sourceCatalogState.collectAsStateWithLifecycle()
    val sourceHealth by viewModel.sourceHealth.collectAsStateWithLifecycle(initialValue = emptyList())
    val sourceStats7Days by viewModel.sourceStats7Days.collectAsStateWithLifecycle(initialValue = emptyList())
    val sourceStats30Days by viewModel.sourceStats30Days.collectAsStateWithLifecycle(initialValue = emptyList())
    val telegramTransfer by viewModel.telegramTransferState.collectAsStateWithLifecycle()
    val healthByUrl = remember(sourceHealth) { sourceHealth.associateBy(ProxySourceHealth::sourceUrl) }
    val stats7ByUrl = remember(sourceStats7Days) { sourceStats7Days.associateBy(SourceWindowStats::sourceUrl) }
    val stats30ByUrl = remember(sourceStats30Days) { sourceStats30Days.associateBy(SourceWindowStats::sourceUrl) }
    val copy = remember(language) { settingsCopy(language) }
    val isPersian = isPersianLanguage(language)
    val activeSourceUrls = remember(proxySources) {
        proxySources.filter { it.isEnabled }.map { it.url }.toSet()
    }
    val activeSourceHealth = sourceHealth.filter { it.sourceUrl in activeSourceUrls }
    val successfulSourceCount = activeSourceHealth.count(ProxySourceHealth::hasSuccessfulLatestFetch)
    val quarantinedSourceCount = activeSourceHealth.count(ProxySourceHealth::isQuarantined)
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var notificationPermissionGranted by remember {
        mutableStateOf(hasNotificationPermission(context))
    }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        notificationPermissionGranted = granted
        viewModel.setNotificationsEnabled(granted)
    }

    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                notificationPermissionGranted = hasNotificationPermission(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var showThemeDialog by remember { mutableStateOf(false) }
    var showRefreshDialog by remember { mutableStateOf(false) }
    var showTimeoutDialog by remember { mutableStateOf(false) }
    var showWorkersDialog by remember { mutableStateOf(false) }
    var showAccuracyDialog by remember { mutableStateOf(false) }
    var showScheduledIntervalDialog by remember { mutableStateOf(false) }
    var showScheduledCountDialog by remember { mutableStateOf(false) }
    var showLangDialog by remember { mutableStateOf(false) }
    var showPruneConfirmation by remember { mutableStateOf(false) }
    var showClearConfirmation by remember { mutableStateOf(false) }
    var showTelegramAuthDialog by rememberSaveable { mutableStateOf(false) }
    var showTelegramLogoutConfirmation by remember { mutableStateOf(false) }
    var showPrivacyDialog by remember { mutableStateOf(false) }
    var showSourceDetails by rememberSaveable { mutableStateOf(false) }
    var showAdvanced by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(notificationPermissionGranted, notificationsEnabled) {
        if (!notificationPermissionGranted && notificationsEnabled) {
            viewModel.setNotificationsEnabled(false)
        }
    }

    LaunchedEffect(telegramTransfer.step) {
        if (telegramTransfer.step == TelegramAuthorizationStep.READY ||
            telegramTransfer.step == TelegramAuthorizationStep.LOGGING_OUT
        ) {
            showTelegramAuthDialog = false
        }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item(key = "appearance-heading") {
            SectionTitle(if (isPersian) "ظاهر" else "Appearance")
        }
        item(key = "appearance-settings") {
            SettingsCard {
                ToggleSettingsItem(
                    icon = Icons.Default.DarkMode,
                    title = if (isPersian) "حالت تاریک" else "Dark mode",
                    subtitle = themeLabel(theme, language),
                    checked = MaterialTheme.colorScheme.background.luminance() < 0.5f,
                    onCheckedChange = { viewModel.setTheme(if (it) "dark" else "light") }
                )
                SettingsDivider()
                SettingsItem(
                    icon = Icons.Default.Translate,
                    title = copy.language,
                    subtitle = languageLabel(language, language),
                    onClick = { showLangDialog = true }
                )
            }
        }
        item(key = "network-heading") {
            SectionTitle(if (isPersian) "شبکه و تست" else "Network and tests")
        }
        item(key = "network-settings") {
            SettingsCard {
                AutoRefreshSettingsItem(
                    icon = Icons.Default.Update,
                    title = copy.backgroundRefresh,
                    subtitle = if (autoRefreshEnabled) copy.everyMinutes(autoRefreshInterval)
                        else if (isPersian) "خاموش" else "Off",
                    enabled = autoRefreshEnabled,
                    onToggle = viewModel::setAutoRefreshEnabled,
                    onIntervalClick = { showRefreshDialog = true }
                )
                SettingsDivider()
                ToggleSettingsItem(
                    icon = Icons.Default.Wifi,
                    title = copy.unmeteredOnly,
                    subtitle = if (isPersian) "پرهیز از مصرف دیتای محدود" else "Avoid metered data usage",
                    checked = unmeteredOnly,
                    onCheckedChange = viewModel::setUnmeteredOnly
                )
            }
        }

        item(key = "notifications-title") {
            SectionTitle(copy.notificationsControls)
        }
        item(key = "notifications") {
            SettingsCard {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.NotificationsActive,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(copy.pushNotifications, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                            Text(
                                copy.notifyBackgroundRefreshes,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Switch(
            colors = SwitchDefaults.colors(
                checkedTrackColor = MaterialTheme.colorScheme.secondaryContainer,
                checkedThumbColor = MaterialTheme.colorScheme.secondary,
                checkedBorderColor = MaterialTheme.colorScheme.secondary
            ),
                        modifier = Modifier.semantics { contentDescription = copy.pushNotifications },
                        checked = notificationsEnabled && notificationPermissionGranted,
                        onCheckedChange = { enabled ->
                            if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                !notificationPermissionGranted
                            ) {
                                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                viewModel.setNotificationsEnabled(enabled)
                            }
                        }
                    )
                }
            }
        }

        item(key = "telegram-transfer-title") {
            SectionTitle(telegramTransferTitle(language))
        }
        item(key = "telegram-transfer") {
            TelegramTransferSettingsCard(
                state = telegramTransfer,
                language = language,
                onInitialize = {
                    showTelegramAuthDialog = true
                    viewModel.initializeTelegramTransferMode()
                },
                onContinueLogin = { showTelegramAuthDialog = true },
                onCancelTransfer = viewModel::cancelTelegramTransfer,
                onLogout = { showTelegramLogoutConfirmation = true }
            )
        }

        item(key = "advanced-toggle") {
            OutlinedButton(
                onClick = { showAdvanced = !showAdvanced },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            ) {
                Icon(Icons.Default.Tune, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(if (showAdvanced) {
                    if (isPersian) "بستن تنظیمات پیشرفته" else "Hide advanced settings"
                } else if (isPersian) "تنظیمات پیشرفته" else "Advanced settings")
            }
        }
        if (showAdvanced) {
            item(key = "preferences-title") {
                SectionTitle(if (isPersian) "تنظیمات پیشرفته" else "Advanced settings")
            }
            item(key = "preferences") {
                SettingsCard {
                    ToggleSettingsItem(
                        icon = Icons.Default.Wifi,
                        title = if (isPersian) "بازآزمایی بهترین‌های همین شبکه" else "Recheck this network's shortlist",
                        subtitle = if (isPersian) "پس از تغییر شبکه؛ حداکثر ۶ گزینه، بدون انتقال فایل" else "After network changes; up to 6 candidates, no file transfers",
                        checked = networkRecheckEnabled,
                        onCheckedChange = viewModel::setNetworkRecheckEnabled
                    )
                    SettingsDivider()
                    SettingsItem(
                        icon = Icons.Default.BugReport,
                        title = copy.diagnosticReport,
                        subtitle = copy.diagnosticReportSubtitle,
                        onClick = viewModel::exportDiagnosticReport
                    )
                    SettingsDivider()
                    SettingsItem(
                        icon = Icons.Default.Palette,
                        title = copy.appTheme,
                        subtitle = themeLabel(theme, language),
                        onClick = { showThemeDialog = true }
                    )
                    SettingsDivider()
                    ToggleSettingsItem(
                        icon = Icons.Default.Memory,
                        title = copy.adaptiveWorkers,
                        subtitle = copy.adaptiveWorkersSubtitle,
                        checked = adaptiveWorkers,
                        onCheckedChange = viewModel::setAdaptiveWorkersEnabled
                    )
                    SettingsDivider()
                    SettingsItem(
                        icon = Icons.Default.Tune,
                        title = copy.benchmarkAccuracy,
                        subtitle = copy.benchmarkAccuracyLabel(benchmarkAccuracyMode),
                        onClick = { showAccuracyDialog = true }
                    )
                    SettingsDivider()
                    ToggleSettingsItem(
                        icon = Icons.Default.Restore,
                        title = copy.resumeInterruptedBenchmark,
                        subtitle = copy.resumeInterruptedBenchmarkSubtitle,
                        checked = autoResumeBenchmark,
                        onCheckedChange = viewModel::setAutoResumeBenchmark
                    )
                    SettingsDivider()
                    AutoRefreshSettingsItem(
                        icon = Icons.Default.EventRepeat,
                        title = copy.scheduledBenchmark,
                        subtitle = if (scheduledBenchmarkEnabled) {
                            copy.scheduledBenchmarkSummary(
                                scheduledBenchmarkIntervalHours,
                                scheduledBenchmarkCount
                            )
                        } else {
                            copy.scheduledBenchmarkOff
                        },
                        enabled = scheduledBenchmarkEnabled,
                        onToggle = viewModel::setScheduledBenchmarkEnabled,
                        onIntervalClick = { showScheduledIntervalDialog = true }
                    )
                    if (scheduledBenchmarkEnabled) {
                        SettingsDivider()
                        SettingsItem(
                            icon = Icons.Default.NetworkPing,
                            title = copy.scheduledBenchmarkSample,
                            subtitle = copy.proxySampleCount(scheduledBenchmarkCount),
                            onClick = { showScheduledCountDialog = true }
                        )
                    }
                    SettingsDivider()
                    SettingsItem(
                        icon = Icons.Default.Timer,
                        title = copy.connectionTimeout,
                        subtitle = copy.timeoutLimit(timeoutMs),
                        onClick = { showTimeoutDialog = true }
                    )
                    SettingsDivider()
                    SettingsItem(
                        icon = Icons.Default.NetworkPing,
                        title = copy.parallelWorkers,
                        subtitle = copy.testingStreams(parallelWorkers),
                        onClick = { showWorkersDialog = true }
                    )
                    SettingsDivider()
                }
            }
    
                item(key = "maintenance-title") {
                SectionTitle(copy.maintenance)
            }
            item(key = "maintenance") {
                SettingsCard {
                    SettingsItem(
                        icon = Icons.Default.CleaningServices,
                        title = copy.pruneDeadProxies,
                        subtitle = copy.pruneDeadSubtitle,
                        titleColor = MaterialTheme.colorScheme.error,
                        onClick = { showPruneConfirmation = true }
                    )
                    SettingsDivider()
                    SettingsItem(
                        icon = Icons.Default.DeleteForever,
                        title = copy.clearDatabase,
                        subtitle = copy.clearDatabaseSubtitle,
                        titleColor = MaterialTheme.colorScheme.error,
                        onClick = { showClearConfirmation = true }
                    )
                }
            }
    
            }

        item(key = "about-title") {
            SectionTitle(copy.aboutAndSupport)
        }
        item(key = "about") {
            SettingsCard {
                SettingsItem(
                    icon = Icons.Default.PrivacyTip,
                    title = copy.privacy,
                    subtitle = copy.privacySubtitle,
                    onClick = { showPrivacyDialog = true }
                )
                SettingsDivider()
                SettingsItem(
                    icon = Icons.Default.Campaign,
                    title = copy.telegramChannel,
                    subtitle = copy.telegramChannelSubtitle,
                    onClick = { openTelegramChannel(context) }
                )
                SettingsDivider()
                InfoSettingsItem(
                    icon = Icons.Default.Code,
                    title = copy.madeBy,
                    subtitle = "ProxyX v${BuildConfig.VERSION_NAME}"
                )
            }
        }

        item(key = "sources-title") {
            ReleaseUpdateSection(viewModel.releaseUpdates, isPersian)
        }
        item(key = "sources-heading") {
            SectionTitle("${copy.proxyFeedSources} (${proxySources.size})")
        }
        item(key = "sources-summary") {
            SettingsCard {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (isPersian) {
                                "$successfulSourceCount دریافت آخر موفق"
                            } else {
                                "$successfulSourceCount successful latest fetches"
                            },
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                        Text(
                            text = if (isPersian) {
                                "$quarantinedSourceCount منبع قرنطینه‌شده"
                            } else {
                                "$quarantinedSourceCount quarantined sources"
                            },
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 11.sp
                        )
                        Text(
                            text = copy.catalogStatus(sourceCatalogState),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 11.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    TextButton(onClick = { showSourceDetails = !showSourceDetails }) {
                        Text(
                            text = if (showSourceDetails) {
                                if (isPersian) "بستن" else "Hide"
                            } else {
                                if (isPersian) "جزئیات" else "Details"
                            },
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
        if (showSourceDetails) {
            items(
                items = proxySources,
                key = { it.url },
                contentType = { "proxy-source" }
            ) { source ->
                ProxySourceCard(
                    source = source,
                    result = fetchResults[source.name],
                    health = healthByUrl[source.url],
                    stats7Days = stats7ByUrl[source.url],
                    stats30Days = stats30ByUrl[source.url],
                    copy = copy
                )
            }
            item(key = "sources-hide") {
                OutlinedButton(
                    onClick = { showSourceDetails = false },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(if (isPersian) "بستن جزئیات منابع" else "Hide source details")
                }
            }
        }
    }

    if (showTelegramAuthDialog && telegramTransfer.step.requiresUserInput()) {
        TelegramAuthorizationDialog(
            state = telegramTransfer,
            language = language,
            onDismiss = { showTelegramAuthDialog = false },
            onResend = viewModel::resendTelegramCode,
            onSubmit = { primary, secondary ->
                when (telegramTransfer.step) {
                    TelegramAuthorizationStep.WAIT_PHONE ->
                        viewModel.submitTelegramPhone(primary)
                    TelegramAuthorizationStep.WAIT_EMAIL ->
                        viewModel.submitTelegramEmail(primary)
                    TelegramAuthorizationStep.WAIT_EMAIL_CODE,
                    TelegramAuthorizationStep.WAIT_CODE ->
                        viewModel.submitTelegramCode(primary)
                    TelegramAuthorizationStep.WAIT_PASSWORD ->
                        viewModel.submitTelegramPassword(primary)
                    TelegramAuthorizationStep.WAIT_REGISTRATION ->
                        viewModel.registerTelegramUser(primary, secondary)
                    else -> Unit
                }
            }
        )
    }

    if (showThemeDialog) {
        OptionDialog(
            title = copy.appTheme,
            options = themeOptions(language),
            selectedValue = theme,
            cancelText = copy.cancel,
            onDismiss = { showThemeDialog = false },
            onSelect = {
                viewModel.setTheme(it)
                showThemeDialog = false
            }
        )
    }

    if (showRefreshDialog && autoRefreshEnabled) {
        OptionDialog(
            title = copy.backgroundRefresh,
            options = listOf(
                "15" to copy.minutesDefault(15),
                "30" to copy.minutes(30),
                "60" to copy.oneHour,
                "180" to copy.threeHours
            ),
            selectedValue = autoRefreshInterval.toString(),
            cancelText = copy.cancel,
            onDismiss = { showRefreshDialog = false },
            onSelect = {
                viewModel.setAutoRefreshInterval(it.toInt())
                showRefreshDialog = false
            }
        )
    }

    if (showTimeoutDialog) {
        OptionDialog(
            title = copy.connectionTimeout,
            options = listOf(
                "1000" to copy.seconds(1),
                "2000" to copy.seconds(2),
                "3000" to copy.secondsRecommended(3),
                "5000" to copy.seconds(5)
            ),
            selectedValue = timeoutMs.toString(),
            cancelText = copy.cancel,
            onDismiss = { showTimeoutDialog = false },
            onSelect = {
                viewModel.setTimeoutMs(it.toInt())
                showTimeoutDialog = false
            }
        )
    }

    if (showWorkersDialog) {
        OptionDialog(
            title = copy.parallelWorkers,
            options = listOf(
                "4" to copy.lowSpecs,
                "8" to copy.streams(8),
                "12" to copy.streamsDefault(12),
                "16" to copy.highBandwidth
            ),
            selectedValue = parallelWorkers.toString(),
            cancelText = copy.cancel,
            onDismiss = { showWorkersDialog = false },
            onSelect = {
                viewModel.setParallelWorkers(it.toInt())
                showWorkersDialog = false
            }
        )
    }

    if (showAccuracyDialog) {
        OptionDialog(
            title = copy.benchmarkAccuracy,
            options = BenchmarkAccuracyMode.entries.map { mode ->
                mode.name to copy.benchmarkAccuracyLabel(mode)
            },
            selectedValue = benchmarkAccuracyMode.name,
            cancelText = copy.cancel,
            onDismiss = { showAccuracyDialog = false },
            onSelect = { value ->
                viewModel.setBenchmarkAccuracyMode(BenchmarkAccuracyMode.valueOf(value))
                showAccuracyDialog = false
            }
        )
    }

    if (showScheduledIntervalDialog && scheduledBenchmarkEnabled) {
        OptionDialog(
            title = copy.scheduledBenchmarkInterval,
            options = ProxyBenchmarkScheduler.allowedIntervalsHours.map { hours ->
                hours.toString() to copy.hours(hours)
            },
            selectedValue = scheduledBenchmarkIntervalHours.toString(),
            cancelText = copy.cancel,
            onDismiss = { showScheduledIntervalDialog = false },
            onSelect = { value ->
                viewModel.setScheduledBenchmarkIntervalHours(value.toInt())
                showScheduledIntervalDialog = false
            }
        )
    }

    if (showScheduledCountDialog && scheduledBenchmarkEnabled) {
        OptionDialog(
            title = copy.scheduledBenchmarkSample,
            options = listOf(25, 50, 100, 150, 250).map { count ->
                count.toString() to copy.proxySampleCount(count)
            },
            selectedValue = scheduledBenchmarkCount.toString(),
            cancelText = copy.cancel,
            onDismiss = { showScheduledCountDialog = false },
            onSelect = { value ->
                viewModel.setScheduledBenchmarkCount(value.toInt())
                showScheduledCountDialog = false
            }
        )
    }

    if (showLangDialog) {
        OptionDialog(
            title = copy.appLanguage,
            options = listOf(
                "system" to languageLabel("system", language),
                "en" to languageLabel("en", language),
                "fa" to languageLabel("fa", language)
            ),
            selectedValue = language,
            cancelText = copy.cancel,
            onDismiss = { showLangDialog = false },
            onSelect = {
                viewModel.setLanguage(it)
                showLangDialog = false
            }
        )
    }

    if (showPruneConfirmation) {
        ConfirmationDialog(
            title = copy.pruneDeadProxies,
            message = copy.pruneConfirmation,
            confirmText = copy.confirm,
            cancelText = copy.cancel,
            onDismiss = { showPruneConfirmation = false },
            onConfirm = {
                showPruneConfirmation = false
                viewModel.deleteDeadProxies()
            }
        )
    }

    if (showClearConfirmation) {
        ConfirmationDialog(
            title = copy.clearDatabase,
            message = copy.clearConfirmation,
            confirmText = copy.confirm,
            cancelText = copy.cancel,
            onDismiss = { showClearConfirmation = false },
            onConfirm = {
                showClearConfirmation = false
                viewModel.clearDatabase()
            }
        )
    }

    if (showTelegramLogoutConfirmation) {
        ConfirmationDialog(
            title = if (isPersian) "خروج از نشست تلگرام؟" else "Sign out of Telegram?",
            message = if (isPersian) {
                "نشست ذخیره‌شده این برنامه حذف می‌شود و برای استفاده دوباره باید کد ورود بگیرید."
            } else {
                "This app's saved session will be removed and a new login code will be required next time."
            },
            confirmText = if (isPersian) "خروج" else "Sign out",
            cancelText = copy.cancel,
            onDismiss = { showTelegramLogoutConfirmation = false },
            onConfirm = {
                showTelegramLogoutConfirmation = false
                viewModel.logoutTelegramTransferMode()
            }
        )
    }

    if (showPrivacyDialog) {
        AlertDialog(
            onDismissRequest = { showPrivacyDialog = false },
            icon = { Icon(Icons.Default.PrivacyTip, contentDescription = null) },
            title = { Text(copy.privacy, fontWeight = FontWeight.Bold) },
            text = { Text(copy.privacyDetail, Modifier.verticalScroll(rememberScrollState())) },
            confirmButton = {
                TextButton(onClick = { showPrivacyDialog = false }) {
                    Text(copy.confirm)
                }
            },
            shape = RoundedCornerShape(8.dp)
        )
    }
}

@Composable
private fun TelegramTransferSettingsCard(
    state: TelegramTransferState,
    language: String,
    onInitialize: () -> Unit,
    onContinueLogin: () -> Unit,
    onCancelTransfer: () -> Unit,
    onLogout: () -> Unit
) {
    val isFa = isPersianLanguage(language)
    val context = LocalContext.current
    val isBusy = state.step in setOf(
        TelegramAuthorizationStep.STARTING,
        TelegramAuthorizationStep.TRANSFERRING,
        TelegramAuthorizationStep.LOGGING_OUT
    ) || state.authorizationInProgress
    SettingsCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                if (isBusy) {
                    CircularProgressIndicator(modifier = Modifier.size(21.dp), strokeWidth = 2.dp)
                } else {
                    Icon(
                        imageVector = Icons.Default.CloudSync,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = telegramTransferStatus(state, isFa),
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    text = telegramTransferDetail(state, isFa),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (state.step == TelegramAuthorizationStep.READY) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = null,
                            tint = Color(0xFF059669),
                            modifier = Modifier.size(15.dp)
                        )
                        Text(
                            text = if (isFa) {
                                "نشست رمزگذاری‌شده روی این گوشی ذخیره است"
                            } else {
                                "Encrypted session saved on this device"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF059669),
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                state.error?.takeIf(String::isNotBlank)?.let { error ->
                    Text(
                        text = error,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                state.authorizationProxyAddress?.let { address ->
                    Text(
                        text = if (isFa) {
                            "مسیر امن ورود: $address"
                        } else {
                            "Secure sign-in route: $address"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (state.step == TelegramAuthorizationStep.TRANSFERRING) {
                    LinearProgressIndicator(
                        progress = { state.transferProgress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = transferProgressLabel(state, isFa),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                when {
                    state.step == TelegramAuthorizationStep.NOT_STARTED ||
                        state.step == TelegramAuthorizationStep.ERROR -> {
                        Button(
                            onClick = onInitialize,
                            enabled = state.canInitialize,
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Login, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(if (isFa) "شروع ورود امن" else "Start secure sign-in")
                        }
                    }
                    state.step.requiresUserInput() -> {
                        Button(
                            onClick = onContinueLogin,
                            enabled = !state.authorizationInProgress,
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Login, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(if (isFa) "ادامه ورود" else "Continue sign-in")
                        }
                    }
                    state.step == TelegramAuthorizationStep.WAIT_OTHER_DEVICE -> {
                        state.confirmationLink?.let { link ->
                            Button(
                                onClick = {
                                    runCatching {
                                        context.startActivity(
                                            Intent(Intent.ACTION_VIEW, link.toUri())
                                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        )
                                    }
                                },
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Icon(Icons.AutoMirrored.Filled.Login, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text(if (isFa) "تأیید در تلگرام" else "Confirm in Telegram")
                            }
                        }
                    }
                    state.step == TelegramAuthorizationStep.TRANSFERRING -> {
                        OutlinedButton(onClick = onCancelTransfer, shape = RoundedCornerShape(8.dp)) {
                            Text(if (isFa) "لغو انتقال" else "Cancel transfer")
                        }
                    }
                    state.step == TelegramAuthorizationStep.READY -> {
                        OutlinedButton(onClick = onLogout, shape = RoundedCornerShape(8.dp)) {
                            Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(if (isFa) "خروج از حالت انتقال" else "Sign out")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TelegramAuthorizationDialog(
    state: TelegramTransferState,
    language: String,
    onDismiss: () -> Unit,
    onResend: () -> Unit,
    onSubmit: (String, String) -> Unit
) {
    val step = state.step
    val isFa = isPersianLanguage(language)
    var primary by remember(step) { mutableStateOf("") }
    var secondary by remember(step) { mutableStateOf("") }
    val registration = step == TelegramAuthorizationStep.WAIT_REGISTRATION
    val password = step == TelegramAuthorizationStep.WAIT_PASSWORD
    val keyboardType = when (step) {
        TelegramAuthorizationStep.WAIT_PHONE -> KeyboardType.Phone
        TelegramAuthorizationStep.WAIT_EMAIL -> KeyboardType.Email
        TelegramAuthorizationStep.WAIT_CODE -> KeyboardType.Number
        else -> KeyboardType.Text
    }
    val canSubmit = primary.isNotBlank() && !state.authorizationInProgress
    var resendRemainingSeconds by remember(state.codeResendAvailableAtElapsedRealtime) {
        mutableIntStateOf(resendSecondsRemaining(state.codeResendAvailableAtElapsedRealtime))
    }
    LaunchedEffect(state.codeResendAvailableAtElapsedRealtime) {
        while (resendRemainingSeconds > 0) {
            delay(1_000L)
            resendRemainingSeconds = resendSecondsRemaining(
                state.codeResendAvailableAtElapsedRealtime
            )
        }
    }

    AlertDialog(
        onDismissRequest = { if (!state.authorizationInProgress) onDismiss() },
        icon = { Icon(Icons.AutoMirrored.Filled.Login, contentDescription = null) },
        title = {
            Text(
                text = telegramAuthorizationTitle(step, isFa),
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = primary,
                    onValueChange = { primary = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(telegramAuthorizationPrimaryLabel(step, isFa)) },
                    keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                    visualTransformation = if (password) {
                        PasswordVisualTransformation()
                    } else {
                        androidx.compose.ui.text.input.VisualTransformation.None
                    }
                )
                if (registration) {
                    OutlinedTextField(
                        value = secondary,
                        onValueChange = { secondary = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text(if (isFa) "نام خانوادگی (اختیاری)" else "Last name (optional)") }
                    )
                }
                if (step == TelegramAuthorizationStep.WAIT_CODE) {
                    telegramCodeDeliveryDetail(state, isFa)?.let { detail ->
                        Text(
                            text = detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (state.nextCodeDeliveryMethod != null) {
                        OutlinedButton(
                            onClick = onResend,
                            enabled = !state.authorizationInProgress && resendRemainingSeconds == 0,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(Icons.Default.Update, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                telegramResendLabel(
                                    method = state.nextCodeDeliveryMethod,
                                    remainingSeconds = resendRemainingSeconds,
                                    isFa = isFa
                                )
                            )
                        }
                    }
                }
                if (state.authorizationInProgress) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(
                        text = authorizationRouteProgress(state, isFa),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                state.error?.takeIf(String::isNotBlank)?.let { error ->
                    Text(
                        text = error,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSubmit(primary.trim(), secondary.trim()) },
                enabled = canSubmit
            ) {
                if (state.authorizationInProgress) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text(if (isFa) "ارسال" else "Submit")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !state.authorizationInProgress) {
                Text(if (isFa) "بعداً" else "Later")
            }
        },
        shape = RoundedCornerShape(8.dp)
    )
}

private fun TelegramAuthorizationStep.requiresUserInput(): Boolean {
    return this in setOf(
        TelegramAuthorizationStep.WAIT_PHONE,
        TelegramAuthorizationStep.WAIT_EMAIL,
        TelegramAuthorizationStep.WAIT_EMAIL_CODE,
        TelegramAuthorizationStep.WAIT_CODE,
        TelegramAuthorizationStep.WAIT_PASSWORD,
        TelegramAuthorizationStep.WAIT_REGISTRATION
    )
}

private fun telegramTransferTitle(language: String): String =
    if (isPersianLanguage(language)) "انتقال واقعی تلگرام" else "Real Telegram transfer"

private fun telegramTransferStatus(state: TelegramTransferState, isFa: Boolean): String {
    if (state.authorizationInProgress) {
        return if (state.step == TelegramAuthorizationStep.WAIT_CODE) {
            if (isFa) "در حال درخواست کد ورود جدید" else "Requesting another login code"
        } else if (isFa) {
            "در حال بررسی مسیر و درخواست امن کد"
        } else {
            "Checking route and securely requesting a code"
        }
    }
    return when (state.step) {
        TelegramAuthorizationStep.UNAVAILABLE ->
            if (isFa) "TDLib رسمی در این بیلد نصب نیست" else "Official TDLib runtime isn't bundled"
        TelegramAuthorizationStep.NEEDS_DEVELOPER_CREDENTIALS ->
            if (isFa) "شناسه توسعه‌دهنده تلگرام تنظیم نشده" else "Telegram developer credentials are missing"
        TelegramAuthorizationStep.NOT_STARTED ->
            if (isFa) "حالت اختیاری آماده فعال‌سازی است" else "Optional mode is ready to activate"
        TelegramAuthorizationStep.STARTING ->
            if (isFa) "در حال راه‌اندازی TDLib" else "Starting TDLib"
        TelegramAuthorizationStep.WAIT_PHONE ->
            if (isFa) "شماره تلفن لازم است" else "Phone number required"
        TelegramAuthorizationStep.WAIT_EMAIL ->
            if (isFa) "ایمیل لازم است" else "Email required"
        TelegramAuthorizationStep.WAIT_EMAIL_CODE ->
            if (isFa) "کد ایمیل لازم است" else "Email code required"
        TelegramAuthorizationStep.WAIT_CODE ->
            if (isFa) "کد ورود لازم است" else "Login code required"
        TelegramAuthorizationStep.WAIT_PASSWORD ->
            if (isFa) "رمز دومرحله‌ای لازم است" else "Two-step password required"
        TelegramAuthorizationStep.WAIT_REGISTRATION ->
            if (isFa) "تکمیل ثبت‌نام لازم است" else "Registration required"
        TelegramAuthorizationStep.WAIT_OTHER_DEVICE ->
            if (isFa) "تأیید در دستگاه دیگر" else "Confirm on another device"
        TelegramAuthorizationStep.READY ->
            if (isFa) "ورود به تلگرام انجام شده است" else "Signed in to Telegram"
        TelegramAuthorizationStep.TRANSFERRING ->
            if (isFa) "در حال انتقال فایل واقعی" else "Transferring a real Telegram file"
        TelegramAuthorizationStep.LOGGING_OUT ->
            if (isFa) "در حال خروج امن" else "Signing out securely"
        TelegramAuthorizationStep.ERROR ->
            if (isFa) "راه‌اندازی TDLib ناموفق بود" else "TDLib initialization failed"
    }
}

private fun telegramTransferDetail(state: TelegramTransferState, isFa: Boolean): String {
    return when (state.step) {
        TelegramAuthorizationStep.UNAVAILABLE -> if (isFa) {
            "سرعت ساختگی نمایش داده نمی‌شود. فایل native رسمی JSONJava باید همراه برنامه بیلد شود."
        } else {
            "No throughput is simulated. Build and bundle the official native JSONJava runtime."
        }
        TelegramAuthorizationStep.NEEDS_DEVELOPER_CREDENTIALS -> if (isFa) {
            "سازنده برنامه باید API ID و API Hash اختصاصی را هنگام بیلد تنظیم کند."
        } else {
            "The app owner must supply a dedicated API ID and API Hash at build time."
        }
        TelegramAuthorizationStep.READY -> if (isFa) {
            "پروکسی‌های قلب‌شده به پیام‌های ذخیره‌شده می‌روند؛ تست واقعی تطبیقی از جزئیات پروکسی اجرا می‌شود."
        } else {
            "Favorites go to Saved Messages; run the adaptive real transfer from an expanded verified proxy."
        }
        TelegramAuthorizationStep.WAIT_CODE -> if (isFa) {
            telegramCodeDeliveryDetail(state, true)
                ?: "نوع ارسال کد را در پنجره ورود بررسی کنید."
        } else {
            telegramCodeDeliveryDetail(state, false)
                ?: "Check the sign-in dialog for the code delivery method."
        }
        else -> if (isFa) {
            "این حالت اختیاری است؛ داده روی اینترنت فعلی شما منتقل می‌شود و نشست در فضای بدون پشتیبان نگهداری می‌شود."
        } else {
            "This mode is optional. Data uses the current network and session files stay outside backups."
        }
    }
}

private fun authorizationRouteProgress(state: TelegramTransferState, isFa: Boolean): String {
    if (state.step == TelegramAuthorizationStep.WAIT_CODE) {
        return if (isFa) {
            "در حال درخواست ارسال مجدد از تلگرام"
        } else {
            "Requesting code delivery again from Telegram"
        }
    }
    val attempt = state.authorizationRouteAttempt
    val total = state.authorizationRouteTotal
    return when {
        total > 0 && attempt > 0 -> if (isFa) {
            "در حال آزمون مسیر $attempt از $total روی اینترنت فعلی"
        } else {
            "Testing route $attempt of $total on the current network"
        }
        else -> if (isFa) {
            "در حال آماده‌سازی ورود امن روی اینترنت فعلی"
        } else {
            "Preparing secure sign-in on the current network"
        }
    }
}

private fun telegramCodeDeliveryDetail(state: TelegramTransferState, isFa: Boolean): String? {
    val method = state.codeDeliveryMethod ?: return null
    val lengthSuffix = state.authenticationCodeLength.takeIf { it > 0 }?.let { length ->
        if (isFa) " (کد $length کاراکتری)" else " ($length-character code)"
    }.orEmpty()
    val delivery = when (method) {
        TelegramCodeDeliveryMethod.TELEGRAM_APP -> if (isFa) {
            "کد داخل گفت‌وگوی رسمی Telegram در یکی از نشست‌های فعال ارسال شد"
        } else {
            "The code was sent to the official Telegram chat in an active session"
        }
        TelegramCodeDeliveryMethod.SMS -> if (isFa) {
            "کد با پیامک ارسال شد"
        } else {
            "The code was sent by SMS"
        }
        TelegramCodeDeliveryMethod.PHONE_CALL -> if (isFa) {
            "کد با تماس تلفنی ارسال می‌شود"
        } else {
            "The code is delivered by a phone call"
        }
        TelegramCodeDeliveryMethod.MISSED_CALL -> if (isFa) {
            "کد از طریق تماس ازدست‌رفته ارسال می‌شود"
        } else {
            "The code is delivered by a missed call"
        }
        TelegramCodeDeliveryMethod.FLASH_CALL -> if (isFa) {
            "کد از طریق Flash Call ارسال می‌شود"
        } else {
            "The code is delivered by a flash call"
        }
        TelegramCodeDeliveryMethod.FRAGMENT -> if (isFa) {
            "کد در سرویس Fragment ارسال شد"
        } else {
            "The code was sent through Fragment"
        }
        TelegramCodeDeliveryMethod.OTHER -> if (isFa) {
            "تلگرام کد را از یک روش جایگزین ارسال کرده است"
        } else {
            "Telegram used an alternative code delivery method"
        }
    }
    return delivery + lengthSuffix
}

private fun telegramResendLabel(
    method: TelegramCodeDeliveryMethod,
    remainingSeconds: Int,
    isFa: Boolean
): String {
    val methodLabel = when (method) {
        TelegramCodeDeliveryMethod.TELEGRAM_APP -> if (isFa) "تلگرام" else "Telegram"
        TelegramCodeDeliveryMethod.SMS -> if (isFa) "پیامک" else "SMS"
        TelegramCodeDeliveryMethod.PHONE_CALL -> if (isFa) "تماس" else "phone call"
        TelegramCodeDeliveryMethod.MISSED_CALL -> if (isFa) "تماس ازدست‌رفته" else "missed call"
        TelegramCodeDeliveryMethod.FLASH_CALL -> if (isFa) "Flash Call" else "flash call"
        TelegramCodeDeliveryMethod.FRAGMENT -> "Fragment"
        TelegramCodeDeliveryMethod.OTHER -> if (isFa) "روش بعدی" else "next method"
    }
    return if (remainingSeconds > 0) {
        if (isFa) {
            "ارسال مجدد با $methodLabel ($remainingSeconds ثانیه)"
        } else {
            "Resend via $methodLabel (${remainingSeconds}s)"
        }
    } else if (isFa) {
        "ارسال مجدد با $methodLabel"
    } else {
        "Resend via $methodLabel"
    }
}

private fun resendSecondsRemaining(availableAtElapsedRealtime: Long): Int {
    if (availableAtElapsedRealtime <= 0L) return 0
    val remainingMs = (availableAtElapsedRealtime - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
    return ((remainingMs + 999L) / 1_000L).toInt()
}

private fun transferProgressLabel(state: TelegramTransferState, isFa: Boolean): String {
    val completedMb = state.transferredBytes.toDouble() / (1024.0 * 1024.0)
    val totalMb = state.totalBytes.toDouble() / (1024.0 * 1024.0)
    return if (isFa) {
        "%.1f از %.1f مگابایت".format(completedMb, totalMb)
    } else {
        "%.1f of %.1f MB".format(completedMb, totalMb)
    }
}

private fun telegramAuthorizationTitle(step: TelegramAuthorizationStep, isFa: Boolean): String {
    return telegramTransferStatus(
        TelegramTransferState(step, runtimeAvailable = true, credentialsConfigured = true),
        isFa
    )
}

private fun telegramAuthorizationPrimaryLabel(
    step: TelegramAuthorizationStep,
    isFa: Boolean
): String = when (step) {
    TelegramAuthorizationStep.WAIT_PHONE -> if (isFa) "شماره با کد کشور" else "Phone with country code"
    TelegramAuthorizationStep.WAIT_EMAIL -> if (isFa) "ایمیل" else "Email"
    TelegramAuthorizationStep.WAIT_EMAIL_CODE -> if (isFa) "کد ایمیل" else "Email code"
    TelegramAuthorizationStep.WAIT_CODE -> if (isFa) "کد تلگرام" else "Telegram code"
    TelegramAuthorizationStep.WAIT_PASSWORD -> if (isFa) "رمز دومرحله‌ای" else "Two-step password"
    TelegramAuthorizationStep.WAIT_REGISTRATION -> if (isFa) "نام" else "First name"
    else -> if (isFa) "مقدار" else "Value"
}

private fun hasNotificationPermission(context: Context): Boolean {
    return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
        fontWeight = FontWeight.SemiBold,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onBackground
    )
}

@Composable
private fun ProxySourceCard(
    source: ProxySource,
    result: SourceFetchResult?,
    health: ProxySourceHealth?,
    stats7Days: SourceWindowStats?,
    stats30Days: SourceWindowStats?,
    copy: SettingsCopy
) {
    val isQuarantined = health?.isQuarantined == true
    val statusColor = when {
        isQuarantined -> Color(0xFFD97706)
        result == null -> MaterialTheme.colorScheme.onSurfaceVariant
        result.isSuccess -> Color(0xFF059669)
        else -> MaterialTheme.colorScheme.error
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.10f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Code,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(19.dp)
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = source.name,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1
                )
                Text(
                    text = source.description,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2
                )
                if (result != null) {
                    Spacer(modifier = Modifier.height(5.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(statusColor)
                        )
                        Text(
                            text = if (result.isSuccess) {
                                copy.fetched(result.count, result.durationMs)
                            } else {
                                copy.failed(result.errorMessage ?: "-")
                            },
                            fontSize = 10.sp,
                            color = statusColor,
                            maxLines = 1
                        )
                    }
                }
                if (health != null) {
                    Spacer(modifier = Modifier.height(5.dp))
                    Text(
                        text = copy.sourceQuality(health),
                        fontSize = 10.sp,
                        color = if (isQuarantined) {
                            Color(0xFFD97706)
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 2
                    )
                }
                if (stats7Days != null || stats30Days != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = copy.sourceWindowStats(stats7Days, stats30Days, health?.mirrorGroupSize ?: 1),
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Text(
                text = "GitHub",
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.secondary
            )
        }
    }
}

@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(content = content)
    }
}

@Composable
private fun SettingsDivider(alpha: Float = 0.08f) {
    HorizontalDivider(
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
        modifier = Modifier.padding(horizontal = 16.dp)
    )
}

@Composable
private fun SettingsItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    titleColor: Color = MaterialTheme.colorScheme.onSurface,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (titleColor == MaterialTheme.colorScheme.onSurface) {
                MaterialTheme.colorScheme.primary
            } else {
                titleColor
            },
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall, color = titleColor)
            Spacer(modifier = Modifier.height(2.dp))
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
private fun AutoRefreshSettingsItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    onIntervalClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 48.dp)
                .clickable(enabled = enabled, onClick = onIntervalClick)
        ) {
            Text(title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            colors = SwitchDefaults.colors(
                checkedTrackColor = MaterialTheme.colorScheme.secondaryContainer,
                checkedThumbColor = MaterialTheme.colorScheme.secondary,
                checkedBorderColor = MaterialTheme.colorScheme.secondary
            ),
            modifier = Modifier.semantics { contentDescription = title },
            checked = enabled,
            onCheckedChange = onToggle
        )
    }
}

@Composable
private fun ToggleSettingsItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            colors = SwitchDefaults.colors(
                checkedTrackColor = MaterialTheme.colorScheme.secondaryContainer,
                checkedThumbColor = MaterialTheme.colorScheme.secondary,
                checkedBorderColor = MaterialTheme.colorScheme.secondary
            ),
            modifier = Modifier.semantics { contentDescription = title },
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

@Composable
private fun InfoSettingsItem(
    icon: ImageVector,
    title: String,
    subtitle: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun OptionDialog(
    title: String,
    options: List<Pair<String, String>>,
    selectedValue: String,
    cancelText: String = "Cancel",
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = title, fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                options.forEach { (value, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(value) }
                            .padding(vertical = 12.dp, horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = value == selectedValue,
                            onClick = { onSelect(value) }
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(text = label, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(cancelText)
            }
        },
        shape = RoundedCornerShape(8.dp)
    )
}

@Composable
private fun ConfirmationDialog(
    title: String,
    message: String,
    confirmText: String,
    cancelText: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontWeight = FontWeight.Bold) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmText, color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(cancelText)
            }
        },
        shape = RoundedCornerShape(8.dp)
    )
}

private data class SettingsCopy(
    val isFa: Boolean,
    val preferences: String,
    val appTheme: String,
    val backgroundRefresh: String,
    val autoRefreshOff: String,
    val connectionTimeout: String,
    val parallelWorkers: String,
    val adaptiveWorkers: String,
    val adaptiveWorkersSubtitle: String,
    val benchmarkAccuracy: String,
    val resumeInterruptedBenchmark: String,
    val resumeInterruptedBenchmarkSubtitle: String,
    val scheduledBenchmark: String,
    val scheduledBenchmarkOff: String,
    val scheduledBenchmarkSample: String,
    val scheduledBenchmarkInterval: String,
    val unmeteredOnly: String,
    val unmeteredOnlySubtitle: String,
    val language: String,
    val notificationsControls: String,
    val pushNotifications: String,
    val notifyBackgroundRefreshes: String,
    val proxyFeedSources: String,
    val maintenance: String,
    val diagnosticReport: String,
    val diagnosticReportSubtitle: String,
    val pruneDeadProxies: String,
    val pruneDeadSubtitle: String,
    val clearDatabase: String,
    val clearDatabaseSubtitle: String,
    val aboutAndSupport: String,
    val privacy: String,
    val privacySubtitle: String,
    val privacyDetail: String,
    val telegramChannel: String,
    val telegramChannelSubtitle: String,
    val madeBy: String,
    val appLanguage: String,
    val pruneConfirmation: String,
    val clearConfirmation: String,
    val confirm: String,
    val cancel: String,
    val oneHour: String,
    val threeHours: String,
    val lowSpecs: String,
    val highBandwidth: String
) {
    fun benchmarkAccuracyLabel(mode: BenchmarkAccuracyMode): String = when (mode) {
        BenchmarkAccuracyMode.DATA_SAVER -> if (isFa) "کم‌مصرف" else "Data saver"
        BenchmarkAccuracyMode.BALANCED -> if (isFa) "متعادل (پیشنهادی)" else "Balanced (recommended)"
        BenchmarkAccuracyMode.PRECISE -> if (isFa) "دقیق و سخت‌گیرانه" else "Precise and strict"
    }
    fun scheduledBenchmarkSummary(hours: Int, count: Int): String = if (isFa) {
        "هر $hours ساعت، $count پروکسی؛ فقط هنگام شارژ و Wi-Fi نامحدود"
    } else {
        "Every $hours hours, $count proxies; charging and unmetered network only"
    }
    fun proxySampleCount(count: Int): String = if (isFa) "$count پروکسی" else "$count proxies"
    fun hours(hours: Int): String = if (isFa) "هر $hours ساعت" else "Every $hours hours"
    fun everyMinutes(minutes: Int) = if (isFa) "هر $minutes دقیقه" else "Every $minutes minutes"
    fun timeoutLimit(timeoutMs: Int) = if (isFa) "حداکثر ${timeoutMs}ms" else "${timeoutMs}ms limit"
    fun testingStreams(workers: Int) = if (isFa) "$workers جریان تست" else "$workers testing streams"
    fun fetched(count: Int, durationMs: Long) = if (isFa) {
        "$count پروکسی در ${durationMs}ms دریافت شد"
    } else {
        "Fetched $count proxies in ${durationMs}ms"
    }
    fun failed(error: String) = if (isFa) "ناموفق: $error" else "Failed: $error"
    fun sourceQuality(health: ProxySourceHealth): String {
        val verifiedPercent = (health.verifiedRate.coerceIn(0f, 1f) * 100).toInt()
        val lastSuccess = health.lastSuccessAt.takeIf { it > 0L }?.let { timestamp ->
            DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(timestamp))
        } ?: if (isFa) "هرگز" else "never"
        return if (health.isQuarantined) {
            if (isFa) {
                "قرنطینه تا ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(health.quarantineUntil))} • کیفیت ${health.qualityScore}/100"
            } else {
                "Quarantined until ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(health.quarantineUntil))} • quality ${health.qualityScore}/100"
            }
        } else if (isFa) {
            "یکتا ${health.uniqueProxyCount} • سالم $verifiedPercent٪ • کیفیت ${health.qualityScore}/100 • آخرین موفقیت $lastSuccess"
        } else {
            "${health.uniqueProxyCount} unique • $verifiedPercent% verified • quality ${health.qualityScore}/100 • last $lastSuccess"
        }
    }
    fun sourceWindowStats(
        sevenDays: SourceWindowStats?,
        thirtyDays: SourceWindowStats?,
        mirrorGroupSize: Int
    ): String {
        fun rate(stats: SourceWindowStats?): Int =
            ((stats?.successRate ?: 0f).coerceIn(0f, 1f) * 100).toInt()
        val mirror = if (mirrorGroupSize > 1) {
            if (isFa) " • آینه مشابه: $mirrorGroupSize" else " • mirror group: $mirrorGroupSize"
        } else {
            ""
        }
        return if (isFa) {
            "سلامت ۷روزه ${rate(sevenDays)}٪ • ۳۰روزه ${rate(thirtyDays)}٪$mirror"
        } else {
            "7-day health ${rate(sevenDays)}% • 30-day ${rate(thirtyDays)}%$mirror"
        }
    }
    fun catalogStatus(state: SourceCatalogState): String {
        val origin = when (state.origin) {
            SourceCatalogOrigin.BUNDLED -> if (isFa) "کاتالوگ داخلی" else "bundled catalog"
            SourceCatalogOrigin.SIGNED_CACHE -> if (isFa) "کش امضاشده" else "signed cache"
            SourceCatalogOrigin.SIGNED_REMOTE -> if (isFa) "کاتالوگ راه‌دور امضاشده" else "signed remote catalog"
        }
        val warning = if (state.error.isNotBlank()) {
            if (isFa) "؛ راه‌دور ناموفق و fallback فعال" else "; remote failed, fallback active"
        } else {
            ""
        }
        return if (isFa) "مبدأ: $origin$warning" else "Origin: $origin$warning"
    }
    fun minutes(minutes: Int) = if (isFa) "$minutes دقیقه" else "$minutes Minutes"
    fun minutesDefault(minutes: Int) = if (isFa) "$minutes دقیقه (پیش‌فرض)" else "$minutes Minutes (Default)"
    fun seconds(seconds: Int) = if (isFa) "$seconds ثانیه" else "$seconds.0 Seconds"
    fun secondsRecommended(seconds: Int) = if (isFa) "$seconds ثانیه (پیشنهادی)" else "$seconds.0 Seconds (Recommended)"
    fun streams(count: Int) = if (isFa) "$count جریان" else "$count Streams"
    fun streamsDefault(count: Int) = if (isFa) "$count جریان (پیش‌فرض)" else "$count Streams (Default)"
}

private fun settingsCopy(language: String): SettingsCopy {
    return if (isPersianLanguage(language)) {
        SettingsCopy(
            isFa = true,
            preferences = "تنظیمات",
            appTheme = "تم برنامه",
            backgroundRefresh = "به‌روزرسانی خودکار",
            autoRefreshOff = "خاموش؛ هیچ کار پس‌زمینه‌ای زمان‌بندی نمی‌شود",
            connectionTimeout = "مهلت اتصال",
            parallelWorkers = "تعداد تست هم‌زمان",
            adaptiveWorkers = "تنظیم خودکار پردازش‌گرها",
            adaptiveWorkersSubtitle = "بر اساس CPU، رم، دما، باتری و نوع شبکه",
            benchmarkAccuracy = "دقت پیش‌فرض بنچ‌مارک",
            resumeInterruptedBenchmark = "بازیابی خودکار تست قطع‌شده",
            resumeInterruptedBenchmarkSubtitle = "پس از بسته‌شدن برنامه، اجرای ناقص تا ۲۴ ساعت از نو و بدون انتقال TDLib شروع شود",
            scheduledBenchmark = "بررسی دوره‌ای کیفیت",
            scheduledBenchmarkOff = "خاموش؛ هیچ بنچ‌مارک دوره‌ای اجرا نمی‌شود",
            scheduledBenchmarkSample = "اندازه نمونهٔ دوره‌ای",
            scheduledBenchmarkInterval = "فاصلهٔ بررسی دوره‌ای",
            unmeteredOnly = "فقط اینترنت نامحدود",
            unmeteredOnlySubtitle = "بنچ‌مارک و بروزرسانی خودکار روی دیتای محدود اجرا نشود",
            language = "زبان",
            notificationsControls = "اعلان‌ها و کنترل‌ها",
            pushNotifications = "اعلان‌ها",
            notifyBackgroundRefreshes = "اطلاع‌رسانی برای به‌روزرسانی پس‌زمینه",
            proxyFeedSources = "منابع پروکسی",
            maintenance = "نگهداری",
            diagnosticReport = "گزارش عیب‌یابی بدون اطلاعات حساس",
            diagnosticReportSubtitle = "خروجی وضعیت برنامه بدون شماره، Secret یا نشست تلگرام",
            pruneDeadProxies = "حذف پروکسی‌های مرده",
            pruneDeadSubtitle = "حذف سرورهای آفلاین غیرعلاقه‌مندی",
            clearDatabase = "حذف همه داده‌های محلی",
            clearDatabaseSubtitle = "پروکسی‌ها، علاقه‌مندی‌ها، سرورهای خصوصی و نتایج تست",
            aboutAndSupport = "درباره و ارتباط",
            privacy = "حریم خصوصی و مصرف داده",
            privacySubtitle = "بدون تبلیغ، تحلیل‌گر یا ارسال مخفی اطلاعات",
            privacyDetail = "ProxyX برای دریافت منابع و انجام تست‌های واقعی از اینترنت فعلی شما استفاده می‌کند. " +
                "تست اختیاری HTTP/SOCKS یک درخواست بدون اطلاعات شخصی به مقصدهای اتصال‌سنجی gstatic.com از طریق پروکسی می‌فرستد؛ نتیجه فقط محلی ذخیره می‌شود. " +
                "حالت TDLib اختیاری است؛ نشست آن فقط روی دستگاه و خارج از پشتیبان نگهداری می‌شود. " +
                "فقط با قلب‌کردن پروکسی، لینک و اطلاعات همان پروکسی به Saved Messages خودتان ارسال می‌شود. " +
                "برنامه شماره تلفن، کلید نشست، Secret پروکسی یا محتوای تلگرام را برای سازنده ارسال نمی‌کند و هیچ تبلیغ یا تحلیل‌گر رفتاری ندارد.",
            telegramChannel = "کانال تلگرام SymexVPN",
            telegramChannelSubtitle = "به‌روزرسانی‌ها، اطلاعیه‌ها و آموزش استفاده",
            madeBy = "ساخته شده توسط Revmamad",
            appLanguage = "زبان برنامه",
            cancel = "بستن",
            oneHour = "۱ ساعت",
            threeHours = "۳ ساعت",
            pruneConfirmation = "فقط پروکسی‌های تست‌شده و آفلاین که علاقه‌مندی نیستند حذف شوند؟",
            clearConfirmation = "همه پروکسی‌ها، علاقه‌مندی‌ها، سرورهای خصوصی، تاریخچه و نتایج تست حذف شوند؟ این کار برگشت‌پذیر نیست. نشست تلگرام و تنظیمات باقی می‌مانند و پیام‌های تلگرام حذف نمی‌شوند.",
            confirm = "تأیید",
            lowSpecs = "۴ (دستگاه ضعیف)",
            highBandwidth = "۱۶ (بیشترین)"
        )
    } else {
        SettingsCopy(
            isFa = false,
            preferences = "Preferences",
            appTheme = "App Theme",
            backgroundRefresh = "Background Auto-Refresh",
            autoRefreshOff = "Off; no background refresh is scheduled",
            connectionTimeout = "Connection Timeout",
            parallelWorkers = "Parallel Workers",
            adaptiveWorkers = "Adaptive workers",
            adaptiveWorkersSubtitle = "Adjust for CPU, memory, temperature, battery and network",
            benchmarkAccuracy = "Default benchmark accuracy",
            resumeInterruptedBenchmark = "Recover interrupted benchmark",
            resumeInterruptedBenchmarkSubtitle = "Restart an incomplete run within 24 hours without TDLib transfers",
            scheduledBenchmark = "Scheduled quality check",
            scheduledBenchmarkOff = "Off; no periodic benchmark is scheduled",
            scheduledBenchmarkSample = "Scheduled sample size",
            scheduledBenchmarkInterval = "Scheduled check interval",
            unmeteredOnly = "Unmetered networks only",
            unmeteredOnlySubtitle = "Do not benchmark or auto-refresh over metered data",
            language = "Language",
            notificationsControls = "Notifications & Controls",
            pushNotifications = "Push Notifications",
            notifyBackgroundRefreshes = "Notify of background list refreshes",
            proxyFeedSources = "Proxy Feed Sources",
            maintenance = "Maintenance",
            diagnosticReport = "Redacted diagnostic report",
            diagnosticReportSubtitle = "Export app state without phone, proxy secrets or Telegram session data",
            pruneDeadProxies = "Prune Dead Proxies",
            pruneDeadSubtitle = "Remove offline non-favorite servers",
            clearDatabase = "Delete All Local Data",
            clearDatabaseSubtitle = "Proxies, favourites, private nodes and test results",
            aboutAndSupport = "About & Support",
            privacy = "Privacy and data use",
            privacySubtitle = "No ads, analytics, or hidden data uploads",
            privacyDetail = "ProxyX uses your active Internet connection to fetch feeds and run real network tests. " +
                "Optional HTTP/SOCKS tests send a request without personal payload through the proxy to gstatic.com connectivity endpoints; results stay local. " +
                "TDLib mode is optional; its session stays on this device outside backups. " +
                "Only when you favorite a proxy are that proxy link and its measurements sent to your own Saved Messages. " +
                "The app does not send your phone number, session key, proxy secret, or Telegram content to the developer, and includes no ads or behavioral analytics.",
            telegramChannel = "SymexVPN Telegram Channel",
            telegramChannelSubtitle = "Updates, announcements, and usage guides",
            madeBy = "Built by Revmamad",
            appLanguage = "App Language",
            cancel = "Cancel",
            oneHour = "1 Hour",
            threeHours = "3 Hours",
            pruneConfirmation = "Remove tested offline proxies that are not favorites?",
            clearConfirmation = "Delete all proxies, favourites, private nodes, history and test results? This cannot be undone. Your Telegram session and settings remain; Telegram messages are not deleted.",
            confirm = "Confirm",
            lowSpecs = "4 (Low specs)",
            highBandwidth = "16 (Maximum)"
        )
    }
}

private fun themeLabel(theme: String, language: String): String {
    return when (theme) {
        "light" -> if (isPersianLanguage(language)) "روشن" else "Light Theme"
        "dark" -> if (isPersianLanguage(language)) "تاریک" else "Dark Theme"
        else -> if (isPersianLanguage(language)) "پیش‌فرض سیستم" else "System Default"
    }
}

private fun themeOptions(language: String): List<Pair<String, String>> {
    return listOf(
        "system" to themeLabel("system", language),
        "light" to themeLabel("light", language),
        "dark" to themeLabel("dark", language)
    )
}

private fun languageLabel(value: String, displayLanguage: String): String {
    return when (value) {
        "en" -> "English"
        "fa" -> "فارسی"
        else -> if (isPersianLanguage(displayLanguage)) "پیش‌فرض سیستم" else "System Default"
    }
}
