package com.example.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.automirrored.filled.CompareArrows
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemContentType
import androidx.paging.compose.itemKey
import com.example.model.MtProtoProxy
import com.example.model.ProxyVerificationLevel
import com.example.model.ProxyInsightEngine
import com.example.model.TransferRankingEvidence
import com.example.data.telegram.TelegramAuthorizationStep
import com.example.viewmodel.ProxyViewModel
import com.example.viewmodel.SortBy
import com.example.viewmodel.StatusFilter
import com.example.viewmodel.shouldShowAdvancedFilters
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(viewModel: ProxyViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val proxies = viewModel.pagedProxies.collectAsLazyPagingItems()
    val count by viewModel.filteredProxyCount.collectAsStateWithLifecycle()
    val selectedProxy by viewModel.selectedProxy.collectAsStateWithLifecycle()
    val recommendation by viewModel.smartRecommendation.collectAsStateWithLifecycle()
    val search by viewModel.searchQuery.collectAsStateWithLifecycle()
    val status by viewModel.statusFilter.collectAsStateWithLifecycle()
    val sort by viewModel.sortBy.collectAsStateWithLifecycle()
    val revision by viewModel.benchmarkRevision.collectAsStateWithLifecycle()
    val collecting by viewModel.isCollecting.collectAsStateWithLifecycle()
    val verifying by viewModel.isVerifying.collectAsStateWithLifecycle()
    val networkSpeed by viewModel.networkSpeed.collectAsStateWithLifecycle()
    val testingUrl by viewModel.liveTestingProxyUrl.collectAsStateWithLifecycle()
    val transfer by viewModel.telegramTransferState.collectAsStateWithLifecycle()
    val transferUrl by viewModel.manualTransferProxyUrl.collectAsStateWithLifecycle()
    val syncing by viewModel.favoriteSyncingProxyUrls.collectAsStateWithLifecycle()
    val selectedUrl by viewModel.selectedProxyUrl.collectAsStateWithLifecycle()
    val language by viewModel.language.collectAsStateWithLifecycle()
    val comparisons by viewModel.comparisonProxies.collectAsStateWithLifecycle()
    val network by viewModel.currentNetworkInfo.collectAsStateWithLifecycle()
    val shortlist by viewModel.networkShortlist.collectAsStateWithLifecycle()
    val rechecking by viewModel.networkRechecking.collectAsStateWithLifecycle()
    val copy = remember(language) { dashboardCopy(language) }
    var filters by rememberSaveable { mutableStateOf(shouldShowAdvancedFilters(search, status, sort)) }
    var showShortlist by rememberSaveable { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val showTop by remember { derivedStateOf { listState.firstVisibleItemIndex > 2 } }
    val networkId = network?.fingerprint.orEmpty()
    val active = selectedProxy ?: recommendation
        ?: proxies.itemSnapshotList.items.firstOrNull(::isUsableProxy)
        ?: proxies.itemSnapshotList.items.firstOrNull()
    val busy = collecting || verifying || networkSpeed.isRunning || transferUrl != null
    val canTest = !busy && testingUrl == null
    val favoriteBlocked = busy || testingUrl != null || syncing.isNotEmpty() ||
        transfer.authorizationInProgress || transfer.step == TelegramAuthorizationStep.STARTING ||
        transfer.step == TelegramAuthorizationStep.TRANSFERRING ||
        transfer.step == TelegramAuthorizationStep.LOGGING_OUT
    var appliedRevision by rememberSaveable { mutableIntStateOf(0) }

    LaunchedEffect(sort, status, search) { listState.scrollToItem(0) }
    LaunchedEffect(revision, proxies.loadState.refresh) {
        if (revision > appliedRevision && proxies.loadState.refresh is LoadState.NotLoading) {
            listState.requestScrollToItem(0)
            withFrameNanos { }
            listState.requestScrollToItem(0)
            appliedRevision = revision
        }
    }
    fun test(proxy: MtProtoProxy) {
        viewModel.updateStatusFilter(StatusFilter.ALL)
        viewModel.verifyProxyOnCurrentNetwork(proxy) { result ->
            Toast.makeText(context, when {
                result == null -> copy.testFailed
                result.isTelegramReady -> copy.testReady
                result.isTelegramVerified -> copy.testVerified
                result.isTcpReachable -> copy.testTcpOnly
                else -> copy.testFailed
            }, Toast.LENGTH_SHORT).show()
        }
    }
    fun open(proxy: MtProtoProxy) {
        viewModel.selectProxy(proxy)
        if (connectToProxy(context, proxy)) {
            viewModel.markProxyAsConnected(proxy)
            Toast.makeText(context, copy.openedTelegram, Toast.LENGTH_SHORT).show()
        } else Toast.makeText(context, copy.telegramUnavailable, Toast.LENGTH_LONG).show()
    }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val hideRing = shouldCollapseHomeAction(maxHeight.value, filters)
        val smallRing = maxHeight < 560.dp
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            if (filters) {
                OutlinedTextField(value = search, onValueChange = viewModel::updateSearchQuery,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    label = { Text(if (copy.isFa) "جست‌وجوی پروکسی" else "Search proxies") },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    trailingIcon = {
                        IconButton(onClick = {
                            if (search.isNotBlank()) viewModel.updateSearchQuery("") else filters = false
                        }) { Icon(Icons.Default.Close, copy.clearSearch) }
                    }, singleLine = true,
                    textStyle = LocalTextStyle.current.copy(textDirection = TextDirection.ContentOrLtr))
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(StatusFilter.ALL, StatusFilter.TELEGRAM_VERIFIED, StatusFilter.REACHABLE_ONLY)
                        .forEach { value ->
                            FilterChip(selected = status == value,
                                onClick = { viewModel.updateStatusFilter(value) },
                                label = { Text(when (value) {
                                    StatusFilter.TELEGRAM_VERIFIED -> copy.telegramVerifiedOnly
                                    StatusFilter.REACHABLE_ONLY -> copy.reachableOnly
                                    else -> copy.allPools
                                }) })
                        }
                }
            }
            Box(Modifier.weight(1f)) {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(top = 4.dp, bottom = 80.dp)) {
                    item(key = "selected-proxy", contentType = "summary") {
                        ActiveProxyHeroCard(
                            active, copy, hideRing, networkId, smallRing = smallRing,
                            testRunning = active != null && testingUrl == active.url,
                            testEnabled = canTest, onOpen = { active?.let(::open) },
                            onTest = {
                                if (testingUrl == active?.url && testingUrl != null) viewModel.cancelLiveTest()
                                else active?.let(::test)
                            })
                    }
                    item(key = "sort-controls", contentType = "controls") {
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                            listOf(SortBy.BEST, SortBy.DOWNLOAD, SortBy.UPLOAD)
                                .forEachIndexed { index, value ->
                                    val physicalIndex = if (copy.isFa) 2 - index else index
                                    SegmentedButton(selected = sort == value,
                                        onClick = { viewModel.updateSortBy(value) },
                                        shape = androidx.compose.foundation.shape.AbsoluteRoundedCornerShape(
                                            topLeft = if (physicalIndex == 0) 8.dp else 0.dp,
                                            bottomLeft = if (physicalIndex == 0) 8.dp else 0.dp,
                                            topRight = if (physicalIndex == 2) 8.dp else 0.dp,
                                            bottomRight = if (physicalIndex == 2) 8.dp else 0.dp
                                        ), icon = {},
                                        colors = SegmentedButtonDefaults.colors(
                                            activeContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                                            activeContentColor = MaterialTheme.colorScheme.onSecondaryContainer)) {
                                        Text(when (value) {
                                            SortBy.DOWNLOAD -> if (copy.isFa) "دانلود" else "Download"
                                            SortBy.UPLOAD -> if (copy.isFa) "آپلود" else "Upload"
                                            else -> if (copy.isFa) "بهترین" else "Best"
                                        }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                        }
                    }
                    item(key = "proxy-heading", contentType = "heading") {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(copy.proxyList + " (" + count + ")", Modifier.weight(1f),
                                style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            IconToggleButton(checked = filters, onCheckedChange = { filters = it }) {
                                Icon(Icons.Default.Search, if (copy.isFa) "جست‌وجو و فیلتر" else "Search and filters")
                            }
                            IconButton(onClick = { showShortlist = true }) {
                                Icon(Icons.Default.Wifi, if (copy.isFa) "سابقهٔ این شبکه" else "Network shortlist")
                            }
                            Box {
                                IconButton(onClick = { sortMenu = true }) {
                                    Icon(Icons.AutoMirrored.Filled.Sort, copy.sortProxies)
                                }
                                DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                                    listOf(
                                        Triple(SortBy.BEST, copy.sortByBest, Icons.Default.AutoAwesome),
                                        Triple(SortBy.HIGH, copy.sortByHigh, Icons.Default.Download),
                                        Triple(SortBy.DOWN_UP, copy.sortByDownUp, Icons.Default.SwapVert),
                                        Triple(SortBy.LATENCY, copy.sortByLatency, Icons.Default.Speed),
                                        Triple(SortBy.PROTOCOL, copy.sortByProtocol, Icons.Default.Hub),
                                        Triple(SortBy.STABILITY, copy.sortByStability, Icons.Default.Shield),
                                        Triple(SortBy.COUNTRY, copy.sortByCountry, Icons.Default.Public)
                                    ).forEach { (mode, label, icon) ->
                                        SortMenuItem(label, icon, sort == mode) {
                                            viewModel.updateSortBy(mode)
                                            sortMenu = false
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (proxies.itemCount == 0) {
                        item(key = "list-state", contentType = "state") {
                            Column(Modifier.fillMaxWidth().padding(vertical = 24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                if (collecting || proxies.loadState.refresh is LoadState.Loading) {
                                    CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
                                    Text(copy.fetchingServers, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                } else {
                                    Text(if (proxies.loadState.refresh is LoadState.Error) {
                                        if (copy.isFa) "بارگذاری فهرست ناموفق بود" else "Unable to load proxies"
                                    } else copy.noProxiesFound,
                                        style = MaterialTheme.typography.titleMedium)
                                    Button(onClick = {
                                        when {
                                            proxies.loadState.refresh is LoadState.Error -> proxies.retry()
                                            search.isNotBlank() || status != StatusFilter.ALL -> {
                                                viewModel.updateSearchQuery("")
                                                viewModel.updateStatusFilter(StatusFilter.ALL)
                                            }
                                            else -> viewModel.collectProxies()
                                        }
                                    }, enabled = !busy && testingUrl == null) {
                                        Icon(Icons.Default.Refresh, null)
                                        Spacer(Modifier.width(8.dp))
                                        Text(if (search.isNotBlank() || status != StatusFilter.ALL) {
                                            if (copy.isFa) "پاک‌کردن فیلترها" else "Clear filters"
                                        } else copy.collectNow)
                                    }
                                }
                            }
                        }
                    }
                    items(count = proxies.itemCount, key = proxies.itemKey { it.url },
                        contentType = proxies.itemContentType { "proxy-row" }) { index ->
                        val proxy = proxies[index]
                        if (proxy == null) ProxyItemPlaceholder()
                        else ProxyItemRow(
                            proxy, networkId, selectedUrl == proxy.url, testingUrl == proxy.url,
                            canTest, !favoriteBlocked, proxy.url in syncing,
                            comparisons.any { it.url == proxy.url },
                            transfer.step == TelegramAuthorizationStep.READY && proxy.isTelegramVerified &&
                                !hasHistoricalDashboardEvidence(proxy, networkId),
                            transferUrl == proxy.url,
                            transferUrl != null || transfer.step == TelegramAuthorizationStep.TRANSFERRING,
                            sort == SortBy.LATENCY, copy,
                            onSelect = { viewModel.selectProxy(proxy) },
                            onFavoriteToggle = { viewModel.toggleFavorite(proxy) },
                            onCompareToggle = { viewModel.toggleComparison(proxy) },
                            onTestClick = { test(proxy) }, onCancelTest = viewModel::cancelLiveTest,
                            onRealTransferClick = { viewModel.runRealTelegramTransfer(proxy) },
                            onCancelRealTransfer = viewModel::cancelTelegramTransfer,
                            onConnectClick = { open(proxy) })
                    }
                    if (proxies.loadState.append is LoadState.Loading) {
                        item(key = "paging-loader", contentType = "state") {
                            Box(Modifier.fillMaxWidth().height(48.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                            }
                        }
                    } else if (proxies.loadState.append is LoadState.Error) {
                        item(key = "paging-retry", contentType = "state") {
                            TextButton(onClick = proxies::retry, modifier = Modifier.fillMaxWidth()) {
                                Text(if (copy.isFa) "تلاش دوباره" else "Retry")
                            }
                        }
                    }
                }
                if (showTop) {
                    SmallFloatingActionButton(
                        onClick = { scope.launch { listState.animateScrollToItem(0) } },
                        modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    ) { Icon(Icons.Default.KeyboardArrowUp, copy.backToTop) }
                }
            }
        }
    }
    if (showShortlist) {
        AlertDialog(onDismissRequest = { showShortlist = false },
            title = { Text(if (copy.isFa) "سابقهٔ این شبکه" else "Network shortlist") },
            text = {
                Column(Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (shortlist.isEmpty()) Text(if (copy.isFa) "هنوز گزینه‌ای ذخیره نشده است."
                        else "No previous candidates for this network.")
                    shortlist.forEach {
                        Text(it.proxyUrl.toUri().getQueryParameter("server").orEmpty(),
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = LocalTextStyle.current.copy(textDirection = TextDirection.ContentOrLtr))
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = rechecking || shortlist.isNotEmpty(), onClick = {
                    if (rechecking) viewModel.cancelLiveTest() else viewModel.recheckNetworkCandidates()
                }) { Text(if (rechecking) { if (copy.isFa) "لغو" else "Cancel" }
                    else if (copy.isFa) "بازآزمایی" else "Recheck") }
            },
            dismissButton = { TextButton(onClick = { showShortlist = false }) {
                Text(if (copy.isFa) "بستن" else "Close")
            } })
    }
}

@Composable
private fun SortMenuItem(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit
) {
    DropdownMenuItem(
        text = { Text(label) },
        onClick = onClick,
        leadingIcon = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        trailingIcon = {
            if (selected) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    )
}

@Composable
private fun ActiveProxyHeroCard(
    activeProxy: MtProtoProxy?,
    copy: DashboardCopy,
    compact: Boolean,
    currentNetworkFingerprint: String,
    smallRing: Boolean,
    testRunning: Boolean,
    testEnabled: Boolean,
    onOpen: () -> Unit,
    onTest: () -> Unit
) {
    val statusColor = verificationColor(activeProxy, currentNetworkFingerprint)
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        if (!compact) {
            NeonTelegramAction(activeProxy != null,
                if (copy.isFa) "بازکردن در تلگرام" else "Open in Telegram", onOpen, compact = smallRing)
            Text(if (copy.isFa) "بازکردن در تلگرام" else "Open in Telegram",
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 2.dp, bottom = 12.dp))
        }
        Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surface,
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Default.Dns, null, tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f)) {
                    Text(copy.selectedProxy, style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(activeProxy?.server ?: copy.noSelection,
                        style = endpointStyle(MaterialTheme.typography.titleSmall),
                        fontWeight = FontWeight.SemiBold, maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = if (copy.isFa) TextAlign.End else TextAlign.Start,
                        modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.onSurface)
                    Text(copy.statusLabel(activeProxy, currentNetworkFingerprint),
                        style = MaterialTheme.typography.bodySmall, color = statusColor)
                }
                if (compact) {
                    FilledIconButton(onClick = onOpen, enabled = activeProxy != null) {
                        Icon(Icons.AutoMirrored.Filled.Send, copy.telegram)
                    }
                } else {
                    currentProtocolLatency(activeProxy, currentNetworkFingerprint)?.let {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("MTProto", style = endpointStyle(MaterialTheme.typography.labelSmall),
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(it.toString() + " ms", style = endpointStyle(MaterialTheme.typography.labelLarge), color = statusColor)
                        }
                    }
                }
            }
        }
        OutlinedButton(onClick = onTest,
            enabled = activeProxy != null && (testEnabled || testRunning),
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).heightIn(min = 48.dp)) {
            Icon(if (testRunning) Icons.Default.Close else Icons.Default.Speed, null)
            Spacer(Modifier.width(8.dp))
            Text(if (testRunning) copy.cancelTest
                else if (copy.isFa) "تست این پروکسی" else "Test this proxy")
        }
    }
}

@Composable
private fun ProxyItemPlaceholder() {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(80.dp),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        ),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outline.copy(alpha = 0.12f)
        )
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
            )
        }
    }
}

@Composable
private fun ProxyItemRow(
    proxy: MtProtoProxy,
    currentNetworkFingerprint: String,
    isSelected: Boolean,
    isTesting: Boolean,
    isTestEnabled: Boolean,
    isFavoriteEnabled: Boolean,
    isFavoriteSyncing: Boolean,
    isCompared: Boolean,
    isRealTransferAvailable: Boolean,
    isRealTransferRunning: Boolean,
    isAnyRealTransferRunning: Boolean,
    displayTcpLatency: Boolean,
    copy: DashboardCopy,
    onSelect: () -> Unit,
    onFavoriteToggle: () -> Unit,
    onCompareToggle: () -> Unit,
    onTestClick: () -> Unit,
    onCancelTest: () -> Unit,
    onRealTransferClick: () -> Unit,
    onCancelRealTransfer: () -> Unit,
    onConnectClick: () -> Unit
) {
    var expanded by remember(proxy.url) { mutableStateOf(false) }
    val historical = hasHistoricalDashboardEvidence(proxy, currentNetworkFingerprint)
    val statusColor = verificationColor(proxy, currentNetworkFingerprint)
    val statusLabel = copy.statusLabel(proxy, currentNetworkFingerprint)
    val protocolLatency = if (displayTcpLatency) null
        else currentProtocolLatency(proxy, currentNetworkFingerprint)
    Card(
        modifier = Modifier.fillMaxWidth().clickable {
            onSelect()
            expanded = !expanded
        },
        colors = CardDefaults.cardColors(containerColor =
            if (isSelected) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.35f)
            else MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp,
            if (isSelected) MaterialTheme.colorScheme.secondary
            else MaterialTheme.colorScheme.outlineVariant),
        shape = RoundedCornerShape(8.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(UiUtils.getFlagEmoji(proxy.countryCode), fontSize = 22.sp)
                Column(Modifier.weight(1f)) {
                    Text(proxy.server, fontWeight = FontWeight.SemiBold,
                        style = endpointStyle(MaterialTheme.typography.bodyMedium),
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        textAlign = if (copy.isFa) TextAlign.End else TextAlign.Start,
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.onSurface)
                    Text(statusLabel, color = statusColor,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                if (!historical && isReachableProxy(proxy)) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(if (protocolLatency != null) "MTProto" else "TCP",
                            style = endpointStyle(MaterialTheme.typography.labelSmall),
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text((protocolLatency ?: proxy.latency).toString() + " ms",
                            style = endpointStyle(MaterialTheme.typography.labelMedium), color = statusColor)
                    }
                }
                if (!expanded) {
                    IconButton(onClick = onFavoriteToggle, enabled = isFavoriteEnabled) {
                        if (isFavoriteSyncing) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(if (proxy.isFavorite) Icons.Default.Favorite else Icons.Outlined.FavoriteBorder,
                                if (proxy.isFavorite) copy.removeFavorite else copy.favorite,
                                tint = if (proxy.isFavorite) MaterialTheme.colorScheme.secondary
                                else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (copy.isFa) "جزئیات پروکسی" else "Proxy details",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp))
            }
            if (expanded || isTesting || isRealTransferRunning) {
                Spacer(Modifier.height(8.dp))
                ProxyActionRow(
                    proxy, isTesting, isTestEnabled, isFavoriteEnabled, isFavoriteSyncing,
                    isCompared, copy, onFavoriteToggle, onCompareToggle, onTestClick,
                    onCancelTest, onConnectClick
                )
            }

            // Expanded detail details
            if (expanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp)
                ) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = "${copy.secret}: ${proxy.secret}",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = copy.countryAndPort(proxy.country, proxy.port),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (proxy.lastChecked > 0) {
                        val sdf = SimpleDateFormat("MMM dd, HH:mm:ss", Locale.getDefault())
                        val lastCheckedStr = sdf.format(Date(proxy.lastChecked))
                        Text(
                            text = copy.lastVerified(
                                lastCheckedStr,
                                (proxy.stability.coerceIn(0f, 1f) * 100).toInt(),
                                proxy.verificationConfidence
                            ),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        text = copy.verificationDetails(
                            status = statusLabel,
                            confidence = proxy.verificationConfidence,
                            protocolLatency = proxy.protocolLatency
                        ),
                        fontSize = 11.sp,
                        color = statusColor.copy(alpha = 0.86f)
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = copy.evidenceDetails(
                            tcpSuccesses = proxy.tcpSuccesses,
                            tcpAttempts = proxy.tcpAttempts,
                            protocolConfirmations = proxy.protocolConfirmations,
                            protocolAttempts = proxy.protocolAttempts,
                            tcpP90Latency = proxy.tcpP90Latency,
                            tcpJitter = proxy.tcpJitter,
                            protocolP90Latency = proxy.protocolP90Latency,
                            protocolJitter = proxy.protocolJitter
                        ),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (!historical && TransferRankingEvidence.isUsableMeasurement(proxy)) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = copy.actualTelegramTransfer(
                                downloadKbps = proxy.telegramDownloadKbps,
                                uploadKbps = proxy.telegramUploadKbps,
                                sampleBytes = proxy.telegramTransferBytes,
                                networkType = proxy.testedNetworkType
                            ),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        if (proxy.telegramDownloadFloorKbps > 0 && proxy.telegramUploadFloorKbps > 0) {
                            Text(
                                text = (if (copy.isFa) "بازهٔ مشاهده‌شده" else "Observed range") +
                                    ": ↓ ${formatTransferRate(proxy.telegramDownloadFloorKbps)} - ${formatTransferRate(proxy.telegramDownloadPeakKbps)}" +
                                    " • ↑ ${formatTransferRate(proxy.telegramUploadFloorKbps)} - ${formatTransferRate(proxy.telegramUploadPeakKbps)}" +
                                    " • ${proxy.telegramTransferRounds} " + (if (copy.isFa) "دور" else "rounds"),
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    if (isRealTransferAvailable || isRealTransferRunning) {
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedButton(
                            onClick = if (isRealTransferRunning) {
                                onCancelRealTransfer
                            } else {
                                onRealTransferClick
                            },
                            enabled = isRealTransferRunning ||
                                (isTestEnabled && !isAnyRealTransferRunning),
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            if (isRealTransferRunning) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.CloudSync,
                                    contentDescription = null,
                                    modifier = Modifier.size(17.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (isRealTransferRunning) {
                                    copy.cancelRealTransfer
                                } else {
                                    copy.realTelegramTransfer
                                },
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProxyActionRow(
    proxy: MtProtoProxy,
    isTesting: Boolean,
    isTestEnabled: Boolean,
    isFavoriteEnabled: Boolean,
    isFavoriteSyncing: Boolean,
    isCompared: Boolean,
    copy: DashboardCopy,
    onFavoriteToggle: () -> Unit,
    onCompareToggle: () -> Unit,
    onTestClick: () -> Unit,
    onCancelTest: () -> Unit,
    onConnectClick: () -> Unit
) {
    val favoriteScale by animateFloatAsState(
        targetValue = if (proxy.isFavorite) 1.08f else 1f,
        animationSpec = spring(dampingRatio = 0.62f, stiffness = 520f),
        label = "favorite-scale"
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = onFavoriteToggle,
            enabled = isFavoriteEnabled,
            modifier = Modifier.size(48.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(
                        if (proxy.isFavorite) {
                            MaterialTheme.colorScheme.secondary.copy(alpha = 0.11f)
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (isFavoriteSyncing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                } else {
                    Icon(
                        imageVector = if (proxy.isFavorite) {
                            Icons.Default.Favorite
                        } else {
                            Icons.Outlined.FavoriteBorder
                        },
                        contentDescription = if (proxy.isFavorite) {
                            copy.removeFavorite
                        } else {
                            copy.favorite
                        },
                        tint = if (proxy.isFavorite) {
                            Color(0xFFEF4444)
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier
                            .size(21.dp)
                            .graphicsLayer {
                                scaleX = favoriteScale
                                scaleY = favoriteScale
                            }
                    )
                }
            }
        }

        IconButton(
            onClick = onCompareToggle,
            modifier = Modifier.size(48.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(
                        if (isCompared) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.CompareArrows,
                    contentDescription = if (isCompared) copy.removeCompare else copy.compare,
                    tint = if (isCompared) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        OutlinedButton(
            onClick = if (isTesting) onCancelTest else onTestClick,
            enabled = isTestEnabled || isTesting,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 48.dp),
            contentPadding = PaddingValues(horizontal = 8.dp)
        ) {
            if (isTesting) {
                Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(16.dp))
            } else {
                Icon(Icons.Default.Speed, contentDescription = null, modifier = Modifier.size(16.dp))
            }
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = if (isTesting) copy.cancelTest else copy.test,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        TooltipBox(
            positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
            tooltip = { PlainTooltip { Text(copy.telegram) } },
            state = rememberTooltipState()
        ) {
            FilledIconButton(
                onClick = onConnectClick,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.size(48.dp)
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = copy.telegram,
                    modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun verificationColor(proxy: MtProtoProxy?, currentNetwork: String? = null): Color {
    val colors = MaterialTheme.colorScheme
    if (currentNetwork != null && hasHistoricalDashboardEvidence(proxy, currentNetwork)) return colors.onSurfaceVariant
    if (proxy?.benchmarkFailureReason?.isNotBlank() == true) return colors.error
    return when (proxy?.verificationLevel) {
        ProxyVerificationLevel.TELEGRAM_READY -> if (colors.background.luminance() < 0.5f)
            Color(0xFF69E7B1) else Color(0xFF006B46)
        ProxyVerificationLevel.MTPROTO_VERIFIED -> colors.primary
        ProxyVerificationLevel.TCP_OPEN -> colors.tertiary
        ProxyVerificationLevel.INCONCLUSIVE -> colors.secondary
        ProxyVerificationLevel.OFFLINE -> colors.error
        ProxyVerificationLevel.UNTESTED, null -> colors.onSurfaceVariant
    }
}

private data class DashboardCopy(
    val isFa: Boolean,
    val selectedProxy: String,
    val noSelection: String,
    val verified: String,
    val mtprotoVerified: String,
    val ping: String,
    val mtprotoRtt: String,
    val stability: String,
    val score: String,
    val confidence: String,
    val runBenchmarkToRank: String,
    val searchPlaceholder: String,
    val search: String,
    val clearSearch: String,
    val allPools: String,
    val telegramVerifiedOnly: String,
    val reachableOnly: String,
    val sortByBest: String,
    val sortByHigh: String,
    val sortByDownUp: String,
    val sortByLatency: String,
    val sortByProtocol: String,
    val sortByStability: String,
    val sortByCountry: String,
    val sortProxies: String,
    val proxyList: String,
    val fetchingServers: String,
    val noProxiesFound: String,
    val noProxiesHint: String,
    val collectNow: String,
    val backToTop: String,
    val testFailed: String,
    val testReady: String,
    val testVerified: String,
    val testTcpOnly: String,
    val openedTelegram: String,
    val telegramUnavailable: String,
    val offline: String,
    val tcpOpen: String,
    val untested: String,
    val secret: String,
    val favorite: String,
    val removeFavorite: String,
    val compare: String,
    val removeCompare: String,
    val test: String,
    val cancelTest: String,
    val realTelegramTransfer: String,
    val cancelRealTransfer: String,
    val telegram: String
) {
    val differentNetworkResult: String
        get() = if (isFa) {
            "این نتیجه روی شبکهٔ فعلی تأیید نشده است؛ دوباره تست کنید"
        } else {
            "Not verified on the current network; retest before trusting this rank"
        }

    fun insightSummary(proxy: MtProtoProxy, insight: com.example.model.ProxyInsight): String {
        val score = proxy.transferScore.coerceIn(0, 100)
        return when {
            ProxyInsightEngine.REASON_REAL_TRANSFER in insight.reasons -> if (isFa) {
                "انتقال واقعی ${proxy.telegramTransferRounds} مرحله‌ای • کیفیت $score/100"
            } else {
                "${proxy.telegramTransferRounds}-round real transfer • quality $score/100"
            }
            ProxyInsightEngine.REASON_MULTI_ROUND in insight.reasons -> if (isFa) {
                "فینالیست چندمرحله‌ای با امتیاز شواهد ${insight.confidence}/100"
            } else {
                "Multi-stage finalist with evidence score ${insight.confidence}/100"
            }
            else -> scoreSummary(score)
        }
    }
    val recommendedProxy: String
        get() = if (isFa) "پیشنهاد هوشمند" else "SMART RECOMMENDATION"

    val recommendationSummary: String
        get() = if (isFa) {
            "بر پایه انتقال واقعی، پایداری، تازگی و شبکه فعلی"
        } else {
            "Ranked by real transfer, stability, freshness and current network"
        }

    fun historySummary(testCount: Int, networkCount: Int): String = if (isFa) {
        "$testCount نتیجه واقعی روی $networkCount شبکه"
    } else {
        "$testCount real results across $networkCount networks"
    }

    fun refreshStatus(enabled: Boolean, minutes: Int) = if (!enabled) {
        if (isFa) "رفرش خودکار خاموش" else "Auto-refresh off"
    } else if (isFa) {
        "رفرش هر $minutes دقیقه"
    } else {
        "${minutes}m refresh"
    }

    fun scoreSummary(value: Int) = if (isFa) "امتیاز $value از ۱۰۰" else "Score $value/100"

    fun actualTelegramTransfer(
        downloadKbps: Int,
        uploadKbps: Int,
        sampleBytes: Long,
        networkType: String
    ): String {
        val sampleMb = sampleBytes.toDouble() / (1024.0 * 1024.0)
        val network = networkType.ifBlank { if (isFa) "شبکه فعلی" else "current network" }
        return if (isFa) {
            "TDLib واقعی: دانلود ${formatTransferRate(downloadKbps)} • " +
                "آپلود ${formatTransferRate(uploadKbps)} • %.1f MiB • $network".format(sampleMb)
        } else {
            "Real TDLib: ${formatTransferRate(downloadKbps)} down • " +
                "${formatTransferRate(uploadKbps)} up • %.1f MiB • $network".format(sampleMb)
        }
    }

    fun serverPort(port: Int, country: String) = if (isFa) {
        "پورت سرور: $port • $country"
    } else {
        "Server port: $port • $country"
    }

    fun countryAndPort(country: String, port: Int) = if (isFa) {
        "کشور: $country • پورت: $port"
    } else {
        "Country: $country • Port: $port"
    }

    fun lastVerified(date: String, stability: Int, confidence: Int) = if (isFa) {
        "آخرین تست: $date (پایداری: $stability% • امتیاز شواهد: $confidence/100)"
    } else {
        "Last tested: $date (stability: $stability% • evidence: $confidence/100)"
    }

    fun statusLabel(proxy: MtProtoProxy?, currentNetwork: String? = null): String {
        if (currentNetwork != null && hasHistoricalDashboardEvidence(proxy, currentNetwork)) {
            return if (isFa) "شبکهٔ دیگر" else "OTHER NETWORK"
        }
        proxy?.benchmarkFailureReason?.let(::failureReasonLabel)?.let { return it }
        return when (proxy?.verificationLevel) {
            ProxyVerificationLevel.TELEGRAM_READY -> verified
            ProxyVerificationLevel.MTPROTO_VERIFIED -> mtprotoVerified
            ProxyVerificationLevel.TCP_OPEN -> tcpOpen
            ProxyVerificationLevel.INCONCLUSIVE -> if (isFa) "شواهد ناکافی" else "Inconclusive"
            ProxyVerificationLevel.OFFLINE -> offline
            ProxyVerificationLevel.UNTESTED, null -> untested
        }
    }

    private fun failureReasonLabel(reason: String): String? = when (reason) {
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
        else -> null
    }

    fun verificationDetails(
        status: String,
        confidence: Int,
        protocolLatency: Long
    ) = if (isFa) {
        "اعتبار: $status • امتیاز شواهد: $confidence/100 • پاسخ MTProto: " +
            if (protocolLatency > 0) "${protocolLatency}ms" else "تأیید نشده"
    } else {
        "Verification: $status • evidence: $confidence/100 • MTProto RTT: " +
            if (protocolLatency > 0) "${protocolLatency}ms" else "not confirmed"
    }

    fun tcpEvidence(successes: Int, attempts: Int) = if (isFa) {
        "TCP $successes/$attempts"
    } else {
        "TCP $successes/$attempts"
    }

    fun protocolEvidence(confirmations: Int, attempts: Int) =
        "MTProto $confirmations/$attempts"

    fun evidenceDetails(
        tcpSuccesses: Int,
        tcpAttempts: Int,
        protocolConfirmations: Int,
        protocolAttempts: Int,
        tcpP90Latency: Long,
        tcpJitter: Long,
        protocolP90Latency: Long,
        protocolJitter: Long
    ) = if (isFa) {
        "شواهد واقعی: TCP $tcpSuccesses/$tcpAttempts • " +
            "MTProto $protocolConfirmations/$protocolAttempts • " +
            "P90/جیتر TCP: ${metricPair(tcpP90Latency, tcpJitter)} • " +
            "تلگرام: ${metricPair(protocolP90Latency, protocolJitter)}"
    } else {
        "Measured evidence: TCP $tcpSuccesses/$tcpAttempts • " +
            "MTProto $protocolConfirmations/$protocolAttempts • " +
            "TCP p90/jitter: ${metricPair(tcpP90Latency, tcpJitter)} • " +
            "Telegram: ${metricPair(protocolP90Latency, protocolJitter)}"
    }

    private fun metricPair(p90Latency: Long, jitter: Long): String {
        return if (p90Latency > 0L) "${p90Latency}ms/${jitter.coerceAtLeast(0L)}ms" else "---"
    }
}

private fun dashboardCopy(language: String): DashboardCopy {
    return if (isPersianLanguage(language)) {
        DashboardCopy(
            isFa = true,
            selectedProxy = "پروکسی انتخاب‌شده",
            noSelection = "هنوز انتخاب نشده",
            verified = "آماده‌ی تلگرام",
            mtprotoVerified = "تلگرام تأییدشده",
            ping = "TCP",
            mtprotoRtt = "تلگرام",
            stability = "پایداری",
            score = "کیفیت",
            confidence = "شواهد",
            runBenchmarkToRank = "برای رتبه‌بندی، تست را اجرا کنید",
            searchPlaceholder = "جستجو بر اساس کشور یا سرور...",
            search = "جستجو",
            clearSearch = "پاک کردن جستجو",
            allPools = "همه",
            telegramVerifiedOnly = "تأییدشده",
            reachableOnly = "فقط قابل‌اتصال‌ها",
            sortByBest = "بهترین برای انتقال پایدار",
            sortByHigh = "High برای دانلود فایل حجیم",
            sortByDownUp = "بالاترین دانلود / آپلود",
            sortByLatency = "کمترین تأخیر TCP",
            sortByProtocol = "کمترین تأخیر تلگرام",
            sortByStability = "بیشترین پایداری",
            sortByCountry = "کشورها با نتایج واقعی",
            sortProxies = "مرتب‌سازی پروکسی‌ها",
            proxyList = "فهرست پروکسی‌ها",
            fetchingServers = "در حال دریافت پروکسی‌های تلگرام...",
            noProxiesFound = "پروکسی‌ای پیدا نشد",
            noProxiesHint = "از دکمه دریافت پروکسی استفاده کن یا فیلترها را تغییر بده.",
            collectNow = "دریافت پروکسی",
            backToTop = "رفتن به بالا",
            testFailed = "تست با اینترنت فعلی شما ناموفق بود",
            testReady = "مسیر تلگرام در هشت تست واقعی و پایدار تأیید شد",
            testVerified = "پاسخ واقعی تلگرام دریافت شد؛ پروکسی تأیید شد",
            testTcpOnly = "پورت باز است، اما پاسخ تلگرام تأیید نشد",
            openedTelegram = "لینک پروکسی باز شد",
            telegramUnavailable = "برنامه‌ای برای باز کردن لینک تلگرام پیدا نشد",
            offline = "آفلاین",
            tcpOpen = "فقط TCP باز",
            untested = "تست‌نشده",
            secret = "Secret",
            favorite = "علاقه‌مندی",
            removeFavorite = "حذف از علاقه‌مندی‌ها",
            compare = "افزودن به مقایسه",
            removeCompare = "حذف از مقایسه",
            test = "تست",
            cancelTest = "لغو تست",
            realTelegramTransfer = "تست انتقال واقعی با TDLib",
            cancelRealTransfer = "لغو انتقال واقعی",
            telegram = "تلگرام"
        )
    } else {
        DashboardCopy(
            isFa = false,
            selectedProxy = "Selected proxy",
            noSelection = "No proxy selected",
            verified = "Telegram ready",
            mtprotoVerified = "Telegram verified",
            ping = "TCP",
            mtprotoRtt = "Telegram",
            stability = "Stability",
            score = "Quality",
            confidence = "Evidence",
            runBenchmarkToRank = "RUN BENCHMARK TO RANK",
            searchPlaceholder = "Search by country or server...",
            search = "Search",
            clearSearch = "Clear search",
            allPools = "All",
            telegramVerifiedOnly = "Verified",
            reachableOnly = "Reachable",
            sortByBest = "Best for Reliable Transfer",
            sortByHigh = "High for Large Downloads",
            sortByDownUp = "Highest Download / Upload",
            sortByLatency = "Lowest TCP RTT",
            sortByProtocol = "Lowest Telegram RTT",
            sortByStability = "Most Stable",
            sortByCountry = "Countries by Results",
            sortProxies = "Sort proxies",
            proxyList = "Proxies",
            fetchingServers = "Fetching public Telegram proxies...",
            noProxiesFound = "No proxies found",
            noProxiesHint = "Try tapping the collect button in the toolbar or updating your filters.",
            collectNow = "Collect Proxies Now",
            backToTop = "Back to top",
            testFailed = "Test failed on your current network",
            testReady = "Telegram route confirmed by eight stable live checks",
            testVerified = "Real Telegram response received; proxy verified",
            testTcpOnly = "TCP port is open, but Telegram was not confirmed",
            openedTelegram = "Proxy link opened",
            telegramUnavailable = "No app can open the Telegram link",
            offline = "Offline",
            tcpOpen = "TCP OPEN ONLY",
            untested = "Untested",
            secret = "Secret",
            favorite = "Favorite",
            removeFavorite = "Remove from favorites",
            compare = "Add to comparison",
            removeCompare = "Remove from comparison",
            test = "Test",
            cancelTest = "Cancel test",
            realTelegramTransfer = "Run real TDLib transfer",
            cancelRealTransfer = "Cancel real transfer",
            telegram = "Telegram"
        )
    }
}

private fun formatTransferRate(kbps: Int): String {
    return if (kbps >= 1024) {
        "%.2f MiB/s".format(Locale.US, kbps / 1024.0)
    } else {
        "$kbps KiB/s"
    }
}

private fun connectToProxy(context: Context, proxy: MtProtoProxy): Boolean {
    val deepLink = proxy.url.toUri()
    if (launchProxyUri(context, deepLink)) return true

    val webLink = Uri.Builder()
        .scheme("https")
        .authority("t.me")
        .path("proxy")
        .appendQueryParameter("server", proxy.server.trim())
        .appendQueryParameter("port", proxy.port.toString())
        .appendQueryParameter("secret", proxy.secret.trim())
        .build()
    return launchProxyUri(context, webLink)
}

private fun launchProxyUri(context: Context, uri: Uri): Boolean {
    return try {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }
}
