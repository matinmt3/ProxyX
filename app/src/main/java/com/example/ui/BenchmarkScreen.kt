package com.example.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.automirrored.filled.CompareArrows
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DataUsage
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.model.MtProtoProxy
import com.example.data.benchmark.BenchmarkStage
import com.example.data.benchmark.BenchmarkAccuracyMode
import com.example.data.benchmark.BenchmarkEstimate
import com.example.data.benchmark.BenchmarkEstimateCalculator
import com.example.data.benchmark.BenchmarkTestPlans
import com.example.data.benchmark.TelegramTransferBudgetPolicy
import com.example.data.telegram.TelegramAuthorizationStep
import com.example.viewmodel.NetworkSpeedUiState
import com.example.viewmodel.ProxyViewModel
import java.util.Locale

@Composable
fun BenchmarkScreen(
    viewModel: ProxyViewModel,
    modifier: Modifier = Modifier,
    onOpenVault: () -> Unit = {}
) {
    val isVerifying by viewModel.isVerifying.collectAsStateWithLifecycle()
    val isPaused by viewModel.isVerificationPaused.collectAsStateWithLifecycle()
    val runtimeState by viewModel.benchmarkRuntimeState.collectAsStateWithLifecycle()
    val progress by viewModel.verificationProgress.collectAsStateWithLifecycle()
    val completedCount by viewModel.verificationCompletedCount.collectAsStateWithLifecycle()
    val totalCount by viewModel.verificationTotalCount.collectAsStateWithLifecycle()
    val currentProxy by viewModel.currentVerifyingProxy.collectAsStateWithLifecycle()
    val benchmarkHistory by viewModel.benchmarkHistory.collectAsStateWithLifecycle()
    val benchmarkSession by viewModel.benchmarkSession.collectAsStateWithLifecycle()
    val benchmarkLimit by viewModel.benchmarkLimit.collectAsStateWithLifecycle()
    val totalProxyCount by viewModel.totalProxyCount.collectAsStateWithLifecycle()
    val isCollecting by viewModel.isCollecting.collectAsStateWithLifecycle()
    val liveTestingProxyUrl by viewModel.liveTestingProxyUrl.collectAsStateWithLifecycle()
    val networkSpeed by viewModel.networkSpeed.collectAsStateWithLifecycle()
    val telegramTransfer by viewModel.telegramTransferState.collectAsStateWithLifecycle()
    val telegramFinalistTransferEnabled by viewModel.telegramFinalistTransferEnabled
        .collectAsStateWithLifecycle()
    val telegramTransferDataBudgetMb by viewModel.telegramTransferDataBudgetMb
        .collectAsStateWithLifecycle()
    val benchmarkAccuracyMode by viewModel.benchmarkAccuracyMode.collectAsStateWithLifecycle()
    val parallelWorkers by viewModel.parallelWorkers.collectAsStateWithLifecycle()
    val timeoutMs by viewModel.timeoutMs.collectAsStateWithLifecycle()
    val benchmarkNetworkMismatch by viewModel.benchmarkNetworkMismatch.collectAsStateWithLifecycle()
    val persistedRun by viewModel.persistedBenchmarkRun.collectAsStateWithLifecycle()
    val language by viewModel.language.collectAsStateWithLifecycle()
    val copy = remember(language) { benchmarkCopy(language) }
    val currentNetwork by viewModel.currentNetworkInfo.collectAsStateWithLifecycle()
    var advanced by rememberSaveable { mutableStateOf(false) }
    val telegramTransferProgress = runtimeState.telegramTransferProgress
    val telegramSessionReady = telegramTransfer.step == TelegramAuthorizationStep.READY ||
        (isVerifying && telegramTransfer.step == TelegramAuthorizationStep.TRANSFERRING)
    val displayedCurrentProxy = telegramTransferProgress?.currentProxy ?: currentProxy
    val serverTextAlign = if (LocalLayoutDirection.current == LayoutDirection.Rtl) {
        TextAlign.End
    } else {
        TextAlign.Start
    }

    val scrollState = rememberScrollState()
    // Keep rapid IME edits local. Publishing every digit through the ViewModel needlessly
    // recomposes every benchmark observer on slower devices.
    var limitText by rememberSaveable { mutableStateOf(benchmarkLimit.toString()) }
    val requestedCount = parseBenchmarkCount(limitText) ?: 0
    val selectedCount = minOf(requestedCount, totalProxyCount)
    val hasCurrentSession = benchmarkSession.totalCount > 0 && runtimeState.showsScreeningPreview
    val tableRows = if (hasCurrentSession) {
        benchmarkSession.previewRows
    } else {
        benchmarkHistory.previewRows
    }
    val chartRows = if (hasCurrentSession) {
        benchmarkSession.chartRows
    } else {
        benchmarkHistory.chartRows
    }
    val displayedTestedCount = if (hasCurrentSession) {
        benchmarkSession.completedCount
    } else {
        benchmarkHistory.testedCount
    }
    val displayedReadyCount = if (hasCurrentSession) {
        benchmarkSession.readyCount
    } else {
        benchmarkHistory.readyCount
    }
    val displayedVerifiedCount = if (hasCurrentSession) {
        benchmarkSession.verifiedCount
    } else {
        benchmarkHistory.verifiedCount
    }
    val displayedTcpOnlyCount = if (hasCurrentSession) {
        benchmarkSession.tcpOnlyCount
    } else {
        benchmarkHistory.tcpOnlyCount
    }
    val animatedProgress = animateFloatAsState(
        targetValue = runtimeState.overallFraction.coerceIn(0f, 1f),
        animationSpec = tween(180),
        label = "benchmark-progress"
    )
    val estimate = remember(
        selectedCount,
        parallelWorkers,
        timeoutMs,
        benchmarkAccuracyMode,
        telegramFinalistTransferEnabled,
        telegramSessionReady,
        telegramTransferDataBudgetMb
    ) {
        BenchmarkEstimateCalculator.calculate(
            proxyCount = selectedCount,
            workers = parallelWorkers,
            timeoutMs = timeoutMs,
            mode = benchmarkAccuracyMode,
            telegramTransfers = telegramFinalistTransferEnabled && telegramSessionReady,
            telegramBudgetMb = telegramTransferDataBudgetMb
        )
    }

    BoxWithConstraints(modifier.fillMaxSize()) {
    val compactGauge = maxHeight < 560.dp
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(scrollState),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (!isVerifying && persistedRun?.status == "INTERRUPTED") {
            InterruptedBenchmarkPanel(
                requestedCount = persistedRun?.requestedCount ?: 0,
                copy = copy,
                onRestart = viewModel::restartInterruptedBenchmark
            )
        }

        if (benchmarkNetworkMismatch) {
            BenchmarkNetworkMismatchPanel(copy)
        }

        PanelCard(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = limitText,
                    onValueChange = { value ->
                        val digits = normalizeBenchmarkDigits(value)
                            .take(MAX_BENCHMARK_INPUT_DIGITS)
                        limitText = digits
                    },
                    label = { Text(copy.proxyCount + " / " + totalProxyCount) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Speed,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    enabled = !isVerifying &&
                        !isCollecting &&
                        liveTestingProxyUrl == null &&
                        !networkSpeed.isRunning,
                    modifier = Modifier.weight(1f)
                )

                TextButton(
                    onClick = { limitText = totalProxyCount.toString() },
                    enabled = !isVerifying && !isCollecting &&
                        liveTestingProxyUrl == null && !networkSpeed.isRunning
                ) { Text(if (copy.isFa) "همه" else "All") }
            }
        }

        val networkId = currentNetwork?.fingerprint.orEmpty()
        val gaugeProxy = remember(tableRows, networkId, isVerifying, runtimeState.startedAt) {
            tableRows.lastOrNull {
                currentProtocolLatency(it, networkId) != null &&
                    (!isVerifying || it.lastChecked >= runtimeState.startedAt)
            }
        }
        Text(
            (if (copy.isFa) "شبکهٔ فعلی: " else "Current network: ") +
                (currentNetwork?.type ?: if (copy.isFa) "در دسترس نیست" else "Unavailable"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        NeonLatencyGauge(
            if (benchmarkNetworkMismatch) null else currentProtocolLatency(gaugeProxy, networkId),
            copy.isFa, compact = compactGauge
        )
        NeonStageStrip(
            stageAppearances(
                runtimeState.status, runtimeState.progress?.stage?.value ?: 0,
                runtimeState.telegramTransfersRequested, telegramTransferProgress != null,
                runtimeState.result
            ), copy.isFa
        )

        if (isVerifying) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = {
                        if (isPaused) viewModel.resumeVerification()
                        else viewModel.pauseVerification()
                    },
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(56.dp)
                ) {
                    Icon(
                        imageVector = if (isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                        contentDescription = null
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isPaused) copy.resumeBenchmark else copy.pauseBenchmark,
                        fontWeight = FontWeight.Bold
                    )
                }
                Button(
                    onClick = viewModel::cancelVerification,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(56.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Icon(Icons.Default.Close, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(copy.cancelBenchmark, fontWeight = FontWeight.Bold)
                }
            }
        } else {
            Button(
                onClick = {
                    viewModel.updateBenchmarkLimit(requestedCount)
                    viewModel.startVerification()
                },
                enabled = !isCollecting &&
                    !networkSpeed.isRunning &&
                    liveTestingProxyUrl == null &&
                    totalProxyCount > 0 &&
                    selectedCount > 0,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    disabledContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                )
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = copy.startBenchmark(selectedCount),
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            }
        }

        AnimatedVisibility(
            visible = isVerifying,
            enter = fadeIn(tween(150)) + expandVertically(tween(220)),
            exit = fadeOut(tween(100)) + shrinkVertically(tween(180))
        ) {
            PanelCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = copy.stageLabel(
                        stage = runtimeState.progress?.stage,
                        paused = isPaused,
                        telegramTransferActive = telegramTransferProgress != null
                    ),
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 15.sp
                )
                Spacer(modifier = Modifier.height(12.dp))
                LinearProgressIndicator(
                    progress = { animatedProgress.value },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp)),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                )
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = if (runtimeState.selectedWorkers > 0) {
                        copy.workerCount(runtimeState.selectedWorkers)
                    } else {
                        copy.selectingWorkers
                    },
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = if (telegramTransferProgress != null) {
                            copy.telegramTransferCompleted(
                                completed = telegramTransferProgress.completedCount,
                                total = telegramTransferProgress.totalCount,
                                succeeded = telegramTransferProgress.succeededCount
                            ) + " • " + (if (copy.isFa) "دور" else "Round") + " ${telegramTransferProgress.round}/3"
                        } else {
                            copy.verified(completedCount, totalCount)
                        },
                        modifier = Modifier.weight(1f).padding(end = 8.dp),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = (if (copy.isFa) "\u06a9\u0644: " else "Overall: ") +
                            "\u2066${benchmarkProgressLabel(runtimeState.overallFraction)}\u2069",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = displayedCurrentProxy?.let { UiUtils.getFlagEmoji(it.countryCode) } ?: "",
                        fontSize = 20.sp,
                        modifier = Modifier
                            .width(32.dp)
                            .padding(end = 8.dp)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = displayedCurrentProxy?.server ?: copy.preparingRoutes,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = serverTextAlign,
                            style = LocalTextStyle.current.copy(
                                textDirection = TextDirection.Ltr
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            text = when {
                                displayedCurrentProxy == null -> copy.waitingForResult
                                telegramTransferProgress != null -> copy.checkingTelegramTransfer
                                else -> copy.checkingCurrent(
                                    mode = runtimeState.accuracyMode,
                                    stage = runtimeState.progress?.stage ?: BenchmarkStage.SCREENING
                                )
                            },
                            fontSize = 10.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        TelegramFinalistTransferOption(
            checked = telegramFinalistTransferEnabled && telegramSessionReady,
            enabled = !isVerifying && telegramTransfer.step == TelegramAuthorizationStep.READY,
            sessionReady = telegramSessionReady,
            copy = copy,
            onCheckedChange = viewModel::setTelegramFinalistTransferEnabled
        )

        TextButton(
            onClick = { advanced = !advanced },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (copy.isFa) "تنظیمات تست و مصرف داده" else "Test and data settings")
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Default.ExpandMore, contentDescription = null)
        }
        if (advanced) {
            BenchmarkPlanningCard(
                selectedMode = benchmarkAccuracyMode,
                estimate = estimate,
                enabled = !isVerifying,
                copy = copy,
                onModeSelected = viewModel::setBenchmarkAccuracyMode
            )

            AnimatedVisibility(
                visible = telegramFinalistTransferEnabled && telegramSessionReady,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                TelegramTransferBudgetOption(
                    budgetMb = telegramTransferDataBudgetMb,
                    enabled = !isVerifying,
                    copy = copy,
                    onBudgetSelected = viewModel::setTelegramTransferDataBudgetMb
                )
            }

        }

        NetworkSpeedCard(
            state = networkSpeed,
            otherOperationRunning = isVerifying || isCollecting || liveTestingProxyUrl != null,
            copy = copy,
            onStart = viewModel::measureCurrentNetworkSpeed,
            onCancel = viewModel::cancelNetworkSpeedTest
        )

        runtimeState.result?.let { result ->
            Text(
                text = benchmarkOutcomeText(result, copy.isFa),
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Latency Waveform Chart Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(240.dp),
            shape = RoundedCornerShape(8.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
            )
        ) {
            LatencyDistributionChart(
                proxies = chartRows,
                testedCount = displayedTestedCount,
                verifiedCount = displayedVerifiedCount,
                language = language,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp)
            )
        }

        BenchmarkResultTable(
            rows = tableRows,
            totalTested = displayedTestedCount,
            readyCount = displayedReadyCount,
            verifiedCount = displayedVerifiedCount,
            tcpOnlyCount = displayedTcpOnlyCount,
            copy = copy
        )

        OutlinedButton(
            onClick = {
                viewModel.compareTopRecommendations()
                onOpenVault()
            },
            enabled = tableRows.count { it.isTelegramVerified } >= 2,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp)
        ) {
            Icon(Icons.AutoMirrored.Filled.CompareArrows, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(copy.compareTopThree, fontWeight = FontWeight.Bold)
        }
    }
}
}

@Composable
private fun BenchmarkNetworkMismatchPanel(copy: BenchmarkCopy) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(Icons.Default.WarningAmber, null,
            tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(20.dp))
        Text(if (copy.isFa) "نتایج قبلی روی این شبکه تأیید نشده‌اند؛ دوباره تست کنید."
            else "Previous results are not verified on this network. Retest.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.tertiary)
    }
}

@Composable
private fun BenchmarkPlanningCard(
    selectedMode: BenchmarkAccuracyMode,
    estimate: BenchmarkEstimate,
    enabled: Boolean,
    copy: BenchmarkCopy,
    onModeSelected: (BenchmarkAccuracyMode) -> Unit
) {
    val plan = remember(selectedMode) { BenchmarkTestPlans.forMode(selectedMode) }
    PanelCard(modifier = Modifier.fillMaxWidth()) {
        Text(copy.accuracyTitle, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            copy.accuracyDetail(selectedMode, plan.finalistRounds),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(12.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            BenchmarkAccuracyMode.entries.forEach { mode ->
                FilterChip(
                    selected = selectedMode == mode,
                    onClick = { onModeSelected(mode) },
                    enabled = enabled,
                    label = { Text(copy.accuracyModeLabel(mode), maxLines = 1) }
                )
            }
        }
        Spacer(modifier = Modifier.height(14.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.14f))
        Spacer(modifier = Modifier.height(12.dp))
        Text(copy.runEstimate, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            copy.estimateDetail(estimate),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun InterruptedBenchmarkPanel(
    requestedCount: Int,
    copy: BenchmarkCopy,
    onRestart: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.25f))
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = copy.interruptedBenchmark,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = copy.interruptedBenchmarkDetail(requestedCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            FilledTonalIconButton(onClick = onRestart) {
                Icon(Icons.Default.RestartAlt, contentDescription = copy.restartBenchmark)
            }
        }
    }
}

@Composable
private fun TelegramTransferBudgetOption(
    budgetMb: Int,
    enabled: Boolean,
    copy: BenchmarkCopy,
    onBudgetSelected: (Int) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.22f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.16f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = Icons.Default.DataUsage,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(copy.transferDataBudget, fontWeight = FontWeight.Bold)
                Text(
                    text = copy.transferDataBudgetDetail(budgetMb),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Box {
                OutlinedButton(
                    onClick = { expanded = true },
                    enabled = enabled,
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text(copy.budgetLabel(budgetMb), maxLines = 1)
                    Icon(Icons.Default.ExpandMore, contentDescription = null)
                }
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    TelegramTransferBudgetPolicy.allowedBudgetsMb.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(copy.budgetLabel(option)) },
                            onClick = {
                                expanded = false
                                onBudgetSelected(option)
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TelegramFinalistTransferOption(
    checked: Boolean,
    enabled: Boolean,
    sessionReady: Boolean,
    copy: BenchmarkCopy,
    onCheckedChange: (Boolean) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Icon(
                imageVector = Icons.Default.CloudSync,
                contentDescription = null,
                tint = if (sessionReady) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(24.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = copy.telegramFinalistTransfer,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = copy.telegramFinalistTransferDetail(sessionReady),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                enabled = enabled,
                modifier = Modifier.semantics {
                    contentDescription = copy.telegramFinalistTransfer
                }
            )
        }
    }
}

@Composable
private fun NetworkSpeedCard(
    state: NetworkSpeedUiState,
    otherOperationRunning: Boolean,
    copy: BenchmarkCopy,
    onStart: () -> Unit,
    onCancel: () -> Unit
) {
    PanelCard(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = copy.internetSpeed,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(3.dp))
        Text(
            text = copy.directInternet,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(14.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            NetworkSpeedMetric(
                icon = Icons.Default.Download,
                label = copy.download,
                value = if (state.hasMeasurement) formatKbps(state.downloadKbps) else "---",
                modifier = Modifier.weight(1f)
            )
            NetworkSpeedMetric(
                icon = Icons.Default.Upload,
                label = copy.upload,
                value = if (state.hasMeasurement) formatKbps(state.uploadKbps) else "---",
                modifier = Modifier.weight(1f)
            )
        }

        if (state.hasMeasurement) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = copy.sampleSize(
                    state.downloadSampleBytes,
                    state.uploadSampleBytes
                ),
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else if (state.failed) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = copy.internetTestFailed,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.error
            )
        }

        Spacer(modifier = Modifier.height(14.dp))
        OutlinedButton(
            onClick = if (state.isRunning) onCancel else onStart,
            enabled = state.isRunning || !otherOperationRunning,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp)
        ) {
            if (state.isRunning) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp
                )
            } else {
                Icon(Icons.Default.Speed, contentDescription = null)
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (state.isRunning) copy.cancelInternetTest else copy.startInternetTest,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun NetworkSpeedMetric(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp)
        )
        Column {
            Text(
                text = label,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = value,
                fontSize = 16.sp,
                style = endpointStyle(MaterialTheme.typography.titleMedium),
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun BenchmarkResultTable(
    rows: List<MtProtoProxy>,
    totalTested: Int,
    readyCount: Int,
    verifiedCount: Int,
    tcpOnlyCount: Int,
    copy: BenchmarkCopy
) {
    PanelCard(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = copy.results,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = copy.resultsSummary(totalTested, rows.size, readyCount, verifiedCount, tcpOnlyCount),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(12.dp))

        if (rows.isEmpty()) {
            Text(
                text = copy.noResults,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 12.dp)
            )
            return@PanelCard
        }

        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val compactTable = maxWidth < BENCHMARK_TABLE_WIDTH
            val tableWidth = if (compactTable) maxWidth else BENCHMARK_TABLE_WIDTH
            val rankWidth = if (compactTable) COMPACT_RANK_WIDTH else BENCHMARK_RANK_WIDTH
            val valueWidth = if (compactTable) COMPACT_VALUE_WIDTH else BENCHMARK_VALUE_WIDTH
            val tableScrollState = rememberScrollState()

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(tableScrollState)
            ) {
                Column(modifier = Modifier.width(tableWidth)) {
                    BenchmarkTableHeader(copy, rankWidth, valueWidth)
                    rows.forEachIndexed { index, proxy ->
                        key(proxy.url) {
                            AnimatedVisibility(
                                visible = true,
                                enter = fadeIn(tween(180)) + expandVertically(tween(180))
                            ) {
                                Column {
                                    BenchmarkTableRow(index + 1, proxy, copy, rankWidth, valueWidth)
                                    if (index < rows.lastIndex) {
                                        HorizontalDivider(
                                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.10f)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BenchmarkTableHeader(
    copy: BenchmarkCopy,
    rankWidth: androidx.compose.ui.unit.Dp,
    valueWidth: androidx.compose.ui.unit.Dp
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
            .padding(horizontal = 8.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "#",
            modifier = Modifier.width(rankWidth),
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = copy.server,
            modifier = Modifier.weight(1f),
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
        TableHeaderText(copy.tcpRtt, valueWidth)
        TableHeaderText(copy.mtprotoRtt, valueWidth)
        TableHeaderText(copy.evidence, valueWidth)
        TableHeaderText(copy.quality, valueWidth)
    }
}

@Composable
private fun TableHeaderText(
    text: String,
    valueWidth: androidx.compose.ui.unit.Dp
) {
    Text(
        text = text,
        modifier = Modifier.width(valueWidth),
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

@Composable
private fun BenchmarkTableRow(
    rank: Int,
    proxy: MtProtoProxy,
    copy: BenchmarkCopy,
    rankWidth: androidx.compose.ui.unit.Dp,
    valueWidth: androidx.compose.ui.unit.Dp
) {
    val tested = proxy.lastChecked > 0L
    val serverTextAlign = if (LocalLayoutDirection.current == LayoutDirection.Rtl) {
        TextAlign.End
    } else {
        TextAlign.Start
    }
    val valueColor = if (proxy.isTelegramVerified) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.60f)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = rank.toString(),
            modifier = Modifier.width(rankWidth),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = proxy.server,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = valueColor,
                textAlign = serverTextAlign,
                style = LocalTextStyle.current.copy(
                    textDirection = TextDirection.Ltr
                ),
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = "${proxy.country} • ${copy.statusLabel(proxy)}",
                fontSize = 9.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        TableValue(
            if (proxy.isTcpReachable) {
                formatLatencyDistribution(proxy.latency, proxy.tcpP90Latency)
            } else {
                "---"
            },
            valueColor,
            valueWidth
        )
        TableValue(
            if (proxy.protocolLatency > 0L) {
                formatLatencyDistribution(proxy.protocolLatency, proxy.protocolP90Latency)
            } else {
                "---"
            },
            valueColor,
            valueWidth
        )
        TableValue(
            if (proxy.protocolAttempts > 0) {
                "${proxy.protocolConfirmations}/${proxy.protocolAttempts}"
            } else {
                "---"
            },
            valueColor,
            valueWidth
        )
        TableValue(
            if (tested) proxy.transferScore.coerceIn(0, 100).toString() else "---",
            valueColor,
            valueWidth
        )
    }
}

@Composable
private fun TableValue(
    value: String,
    color: Color,
    valueWidth: androidx.compose.ui.unit.Dp
) {
    Text(
        text = value,
        modifier = Modifier.width(valueWidth),
        style = endpointStyle(MaterialTheme.typography.bodySmall),
        fontSize = 10.sp,
        fontWeight = FontWeight.Medium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        color = color
    )
}

private data class BenchmarkCopy(
    val proxyCount: String,
    val networkBenchmark: String,
    val internetSpeed: String,
    val directInternet: String,
    val download: String,
    val upload: String,
    val startInternetTest: String,
    val cancelInternetTest: String,
    val internetTestFailed: String,
    val measuringQuality: String,
    val preparingRoutes: String,
    val waitingForResult: String,
    val selectingWorkers: String,
    val cancelBenchmark: String,
    val results: String,
    val server: String,
    val tcpRtt: String,
    val mtprotoRtt: String,
    val evidence: String,
    val quality: String,
    val noResults: String,
    val isFa: Boolean
) {
    val accuracyTitle: String
        get() = if (isFa) "دقت بنچ‌مارک" else "Benchmark accuracy"
    val runEstimate: String
        get() = if (isFa) "برآورد پیش از اجرا" else "Pre-run estimate"
    val compareTopThree: String
        get() = if (isFa) "مقایسهٔ سه نتیجهٔ برتر" else "Compare top three results"
    val networkChangedTitle: String
        get() = if (isFa) "نتایج متعلق به شبکهٔ دیگری‌اند" else "Results belong to another network"
    val networkChangedDetail: String
        get() = if (isFa) {
            "برای رتبه‌بندی قابل اتکا روی اینترنت فعلی، بنچ‌مارک را دوباره اجرا کنید."
        } else {
            "Run the benchmark again on the current connection for a reliable ranking."
        }

    fun accuracyModeLabel(mode: BenchmarkAccuracyMode): String = when (mode) {
        BenchmarkAccuracyMode.DATA_SAVER -> if (isFa) "کم‌مصرف" else "Data saver"
        BenchmarkAccuracyMode.BALANCED -> if (isFa) "متعادل" else "Balanced"
        BenchmarkAccuracyMode.PRECISE -> if (isFa) "دقیق" else "Precise"
    }

    fun accuracyDetail(mode: BenchmarkAccuracyMode, rounds: Int): String = when (mode) {
        BenchmarkAccuracyMode.DATA_SAVER -> if (isFa) {
            "غربال کوتاه‌تر، $rounds دور نهایی و فایل‌های آزمایشی کوچک‌تر."
        } else {
            "Shorter screening, $rounds finalist rounds and smaller transfer samples."
        }
        BenchmarkAccuracyMode.BALANCED -> if (isFa) {
            "$rounds دور نهایی با تعادل میان دقت، زمان و مصرف دیتا."
        } else {
            "$rounds finalist rounds balanced for accuracy, time and data use."
        }
        BenchmarkAccuracyMode.PRECISE -> if (isFa) {
            "$rounds دور نهایی، نمونه‌های بیشتر و سخت‌گیری بالاتر؛ زمان و دیتای بیشتری مصرف می‌کند."
        } else {
            "$rounds finalist rounds, more samples and stricter gates; uses more time and data."
        }
    }

    fun estimateDetail(estimate: BenchmarkEstimate): String {
        if (estimate.estimatedFinalists <= 0) {
            return if (isFa) "برای محاسبه، تعداد معتبر وارد کنید." else "Enter a valid count to calculate."
        }
        val time = formatDurationRange(estimate.minimumDurationMs, estimate.maximumDurationMs, isFa)
        val routeData = formatEstimatedBytes(estimate.estimatedProbeBytes)
        val transferData = formatEstimatedBytes(estimate.maximumTransferBytes)
        return if (isFa) {
            "زمان تقریبی تست مسیر، بدون انتقال فایل: $time • فینالیست برآوردی: ${estimate.estimatedFinalists} • " +
                "دادهٔ مسیر: حدود $routeData • حداکثر حجم فایل TDLib: $transferData؛ سربار جداست"
        } else {
            "Estimated route-test time, excluding file transfer: $time • estimated finalists: ${estimate.estimatedFinalists} • " +
                "route data: about $routeData • maximum TDLib file payload: $transferData; overhead extra"
        }
    }

    val pauseBenchmark: String
        get() = if (isFa) "مکث" else "Pause"
    val resumeBenchmark: String
        get() = if (isFa) "ادامه" else "Resume"

    fun workerCount(count: Int): String = if (isFa) {
        "$count پردازش‌گر تطبیقی"
    } else {
        "$count adaptive workers"
    }

    val telegramFinalistTransfer: String
        get() = if (isFa) "انتقال واقعی TDLib برای فینالیست‌ها" else "Real TDLib transfer for finalists"

    val checkingTelegramTransfer: String
        get() = if (isFa) "آپلود و دانلود واقعی از Saved Messages" else "Real Saved Messages upload and download"

    fun telegramFinalistTransferDetail(sessionReady: Boolean): String {
        return if (!sessionReady) {
            if (isFa) {
                "برای فعال‌سازی، ابتدا از تنظیمات وارد تلگرام شوید."
            } else {
                "Sign in to Telegram from Settings to enable this option."
            }
        } else if (isFa) {
            "سه دور انتقال واقعی برای تمام قبول‌شده‌های مرحله ۳، تا سقف دیتای انتخاب‌شده."
        } else {
            "Three real transfer rounds for every stage-3 pass, within the selected data budget."
        }
    }

    fun telegramTransferCompleted(completed: Int, total: Int, succeeded: Int): String {
        return if (isFa) {
            "انتقال واقعی: $completed از $total • موفق: $succeeded"
        } else {
            "Real transfer: $completed / $total • passed: $succeeded"
        }
    }

    fun stageLabel(
        stage: BenchmarkStage?,
        paused: Boolean,
        telegramTransferActive: Boolean = false
    ): String {
        if (paused) return if (isFa) "بنچ‌مارک متوقف شده است" else "Benchmark paused"
        if (telegramTransferActive) {
            return if (isFa) "مرحله ۴: انتقال واقعی فینالیست‌ها" else "Stage 4: real finalist transfer"
        }
        return when (stage) {
            BenchmarkStage.SCREENING -> if (isFa) "مرحله ۱: غربال اولیه" else "Stage 1: route screening"
            BenchmarkStage.STRICT -> if (isFa) "مرحله ۲: آزمون سخت‌گیرانه" else "Stage 2: strict verification"
            BenchmarkStage.FINALIST -> if (isFa) "مرحله ۳: پایداری فینالیست‌ها" else "Stage 3: finalist continuity"
            else -> measuringQuality
        }
    }

    fun ofTotal(total: Int) = if (isFa) "از $total" else "of $total"
    fun verified(completed: Int, total: Int) = if (isFa) {
        "تست‌شده: $completed از $total"
    } else {
        "Tested: $completed / $total"
    }
    fun startBenchmark(count: Int) = if (isFa) "شروع تست ($count)" else "Start benchmark ($count)"
    fun targetCount(count: Int) = if (isFa) "$count پروکسی در صف تست" else "$count proxies queued"
    fun sampleSize(downloadBytes: Int, uploadBytes: Int) = if (isFa) {
        "حجم نمونه واقعی: ${formatSampleBytes(downloadBytes)} دانلود • " +
            "${formatSampleBytes(uploadBytes)} آپلود"
    } else {
        "Measured payload: ${formatSampleBytes(downloadBytes)} down • " +
            "${formatSampleBytes(uploadBytes)} up"
    }

    fun resultsSummary(total: Int, shown: Int, ready: Int, verified: Int, tcpOnly: Int) = if (isFa) {
        "$shown نتیجه از $total تست • آماده: $ready • تأییدشده: $verified • فقط TCP: $tcpOnly"
    } else {
        "Showing $shown of $total tested • ready: $ready • verified: $verified • TCP only: $tcpOnly"
    }

    fun statusLabel(proxy: MtProtoProxy): String {
        failureReasonLabel(proxy.benchmarkFailureReason)?.let { return it }
        return when (proxy.verificationLevel) {
            com.example.model.ProxyVerificationLevel.TELEGRAM_READY -> if (isFa) "آماده" else "Ready"
            com.example.model.ProxyVerificationLevel.MTPROTO_VERIFIED -> if (isFa) "تأییدشده" else "Verified"
            com.example.model.ProxyVerificationLevel.TCP_OPEN -> if (isFa) "TCP" else "TCP"
            com.example.model.ProxyVerificationLevel.INCONCLUSIVE -> if (isFa) "شواهد ناکافی" else "Inconclusive"
            com.example.model.ProxyVerificationLevel.OFFLINE -> if (isFa) "آفلاین" else "Offline"
            com.example.model.ProxyVerificationLevel.UNTESTED -> if (isFa) "تست‌نشده" else "Untested"
        }
    }

    private fun failureReasonLabel(reason: String): String? {
        if (reason.isBlank()) return null
        return when (reason) {
            "TCP_UNREACHABLE" -> if (isFa) "TCP در دسترس نیست" else "TCP unreachable"
            "INSUFFICIENT_EVIDENCE" -> if (isFa) "شواهد ناکافی" else "Insufficient evidence"
            "TCP_INCOMPLETE" -> if (isFa) "نمونه TCP ناکافی" else "Incomplete TCP proof"
            "TCP_SUCCESS_RATE_LOW" -> if (isFa) "نرخ TCP ضعیف" else "Low TCP pass rate"
            "MTPROTO_NOT_ATTEMPTED" -> if (isFa) "بدون آزمون MTProto" else "No MTProto proof"
            "MTPROTO_UNCONFIRMED" -> if (isFa) "MTProto تأیید نشد" else "MTProto unconfirmed"
            "STABILITY_LOW" -> if (isFa) "پایداری کم" else "Low stability"
            "P90_MISSING" -> if (isFa) "P90 نامعتبر" else "Invalid P90"
            "TRANSFER_SCORE_ZERO" -> if (isFa) "انتقال نامعتبر" else "Invalid transfer score"
            "FINALIST_INCOMPLETE" -> if (isFa) "مرحله نهایی ناقص" else "Incomplete finalist run"
            "CONTINUITY_LOST" -> if (isFa) "مسیر قطع شد" else "Route continuity lost"
            "TELEGRAM_TRANSFER_FAILED" -> if (isFa) "انتقال واقعی تلگرام ناموفق" else "Telegram transfer failed"
            else -> if (isFa) "ردشده" else "Rejected"
        }
    }

    val transferDataBudget: String
        get() = if (isFa) "سقف حجم فایل‌های تست" else "Test file payload budget"

    val interruptedBenchmark: String
        get() = if (isFa) "بنچ‌مارک قبلی نیمه‌کاره ماند" else "Previous benchmark was interrupted"

    val restartBenchmark: String
        get() = if (isFa) "شروع دوباره" else "Restart"

    fun interruptedBenchmarkDetail(count: Int): String = if (isFa) {
        "اجرای امن دوباره برای $count پروکسی"
    } else {
        "Safely restart the run for $count proxies"
    }

    fun budgetLabel(budgetMb: Int): String = if (budgetMb <= 0) {
        if (isFa) "بدون محدودیت" else "Unlimited"
    } else {
        "$budgetMb MB"
    }

    fun transferDataBudgetDetail(budgetMb: Int): String {
        if (budgetMb <= 0) {
            return if (isFa) "تمام فینالیست‌های موفق تست می‌شوند" else "All successful finalists are tested"
        }
        return if (isFa) "حجم رفت‌وبرگشت فایل‌ها حداکثر $budgetMb MiB؛ سربار شبکه جداست. سه دور نوبتی با حجم یکسان." else {
            "File payloads up to $budgetMb MiB; network overhead is extra. Three interleaved, equal-size rounds."
        }
    }

    fun checkingCurrent(mode: BenchmarkAccuracyMode, stage: BenchmarkStage): String {
        val plan = BenchmarkTestPlans.forMode(mode)
        val profile = when (stage) {
            BenchmarkStage.STRICT -> plan.strictProfile
            BenchmarkStage.FINALIST -> plan.finalistProfile
            else -> plan.screeningProfile
        }
        return if (isFa) {
            "در حال بررسی ${profile.tcpAttempts} اتصال TCP و ${profile.dcIds.size} پاسخ واقعی تلگرام..."
        } else {
            "Checking ${profile.tcpAttempts} TCP connections and ${profile.dcIds.size} real Telegram responses..."
        }
    }
}

private fun benchmarkCopy(language: String): BenchmarkCopy {
    return if (isPersianLanguage(language)) {
        BenchmarkCopy(
            proxyCount = "تعداد پروکسی",
            networkBenchmark = "آزمون سخت‌گیرانه مسیر تلگرام",
            internetSpeed = "سرعت واقعی اینترنت",
            directInternet = "اندازه‌گیری مستقیم اینترنت؛ مستقل از پروکسی‌های تلگرام",
            download = "دانلود",
            upload = "آپلود",
            startInternetTest = "تست سرعت اینترنت",
            cancelInternetTest = "لغو تست سرعت",
            internetTestFailed = "اندازه‌گیری ناموفق بود؛ عدد تخمینی جایگزین نشد.",
            measuringQuality = "در حال سنجش کیفیت پروکسی‌ها...",
            preparingRoutes = "آماده‌سازی مسیرهای آزمون",
            waitingForResult = "منتظر نخستین نتیجه...",
            selectingWorkers = "انتخاب تعداد پردازش‌گر متناسب با دستگاه...",
            cancelBenchmark = "لغو تست",
            results = "نتایج بنچمارک",
            server = "سرور",
            tcpRtt = "TCP 50/90",
            mtprotoRtt = "TG 50/90",
            evidence = "پاسخ",
            quality = "کیفیت",
            noResults = "هنوز نتیجه‌ی معتبری ثبت نشده است.",
            isFa = true
        )
    } else {
        BenchmarkCopy(
            proxyCount = "Proxy count",
            networkBenchmark = "Strict Telegram route verification",
            internetSpeed = "Actual Internet speed",
            directInternet = "Direct Internet measurement, separate from Telegram proxies",
            download = "Download",
            upload = "Upload",
            startInternetTest = "Test Internet speed",
            cancelInternetTest = "Cancel speed test",
            internetTestFailed = "Measurement failed; no estimated value was substituted.",
            measuringQuality = "Measuring proxy quality...",
            preparingRoutes = "Preparing benchmark routes",
            waitingForResult = "Waiting for the first result...",
            selectingWorkers = "Selecting workers for this device...",
            cancelBenchmark = "Cancel benchmark",
            results = "Benchmark results",
            server = "Server",
            tcpRtt = "TCP 50/90",
            mtprotoRtt = "TG 50/90",
            evidence = "Proof",
            quality = "Quality",
            noResults = "No benchmark result has been recorded yet.",
            isFa = false
        )
    }
}

private fun formatSampleBytes(bytes: Int): String {
    if (bytes <= 0) return "---"
    return if (bytes >= 1024 * 1024) {
        String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
    } else {
        String.format(Locale.US, "%.0f KB", bytes / 1024.0)
    }
}

private fun formatLatencyDistribution(medianMs: Long, p90Ms: Long): String {
    if (medianMs <= 0L) return "---"
    val safeP90 = p90Ms.takeIf { it >= medianMs } ?: medianMs
    return "$medianMs/$safeP90"
}

private fun formatDurationRange(minimumMs: Long, maximumMs: Long, isFa: Boolean): String {
    fun compact(valueMs: Long): String {
        val seconds = (valueMs / 1_000L).coerceAtLeast(1L)
        return when {
            seconds < 60L -> if (isFa) "$seconds ثانیه" else "${seconds}s"
            seconds < 3_600L -> if (isFa) "${(seconds + 59L) / 60L} دقیقه" else "${(seconds + 59L) / 60L}m"
            else -> if (isFa) "${(seconds + 3_599L) / 3_600L} ساعت" else "${(seconds + 3_599L) / 3_600L}h"
        }
    }
    return "${compact(minimumMs)} - ${compact(maximumMs)}"
}

private fun formatEstimatedBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 MB"
    return if (bytes >= 1024L * 1024L * 1024L) {
        String.format(Locale.US, "%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0))
    } else {
        String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
    }
}

private val BENCHMARK_TABLE_WIDTH = 540.dp
private val BENCHMARK_RANK_WIDTH = 32.dp
private val BENCHMARK_VALUE_WIDTH = 64.dp
private val COMPACT_RANK_WIDTH = 24.dp
private val COMPACT_VALUE_WIDTH = 52.dp
