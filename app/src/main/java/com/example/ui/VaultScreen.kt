package com.example.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.automirrored.filled.CompareArrows
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.example.model.FavoriteProxy
import com.example.model.MtProtoProxy
import com.example.model.NetworkSpeedBaseline
import com.example.model.PrivateProxyNode
import com.example.model.ProxyInsightEngine
import com.example.model.ProxyUseProfile
import com.example.viewmodel.ProxyViewModel
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@Composable
fun VaultScreen(
    viewModel: ProxyViewModel,
    modifier: Modifier = Modifier,
    selectedTab: Int = 1,
    onTabSelected: (Int) -> Unit
) {
    val language by viewModel.language.collectAsStateWithLifecycle()
    val comparison by viewModel.comparisonProxies.collectAsStateWithLifecycle()
    val favorites by viewModel.activeFavorites.collectAsStateWithLifecycle()
    val privateNodes by viewModel.privateNodes.collectAsStateWithLifecycle()
    val testingNodes by viewModel.privateNodeTestingUrls.collectAsStateWithLifecycle()
    val currentNetwork by viewModel.currentNetworkInfo.collectAsStateWithLifecycle()
    val currentNetworkBaseline by viewModel.currentNetworkBaseline.collectAsStateWithLifecycle()
    val privateMonitoring by viewModel.privateNodeMonitoringEnabled.collectAsStateWithLifecycle()
    val copy = remember(language) { vaultCopy(language) }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::importVaultProfile)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf(1, 0, 2, 3, 4).forEach { index ->
                FilterChip(
                    selected = selectedTab == index,
                    onClick = { onTabSelected(index) },
                    label = { Text(copy.tabs[index], maxLines = 1) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                )
            }
        }
        when (selectedTab) {
            0 -> ComparisonPanel(
                proxies = comparison,
                currentNetworkFingerprint = currentNetwork?.fingerprint.orEmpty(),
                networkBaseline = currentNetworkBaseline,
                copy = copy,
                onRemove = viewModel::toggleComparison,
                onClear = viewModel::clearComparison,
                modifier = Modifier.weight(1f)
            )
            1 -> FavoriteVaultPanel(
                favorites = favorites,
                copy = copy,
                onUpdate = viewModel::updateFavoriteMetadata,
                onExport = viewModel::exportVaultProfile,
                onImport = { importLauncher.launch(arrayOf("application/json", "text/plain")) },
                onQr = viewModel::shareProxyQr,
                modifier = Modifier.weight(1f)
            )
            2 -> PrivateNodesPanel(
                nodes = privateNodes,
                currentNetworkFingerprint = currentNetwork?.fingerprint.orEmpty(),
                testingUrls = testingNodes,
                monitoringEnabled = privateMonitoring,
                copy = copy,
                onAdd = viewModel::addPrivateNode,
                onToggle = viewModel::setPrivateNodeEnabled,
                onTest = viewModel::testPrivateNode,
                onDelete = viewModel::deletePrivateNode,
                onCompare = viewModel::toggleComparison,
                onMonitoringChanged = viewModel::setPrivateNodeMonitoringEnabled,
                modifier = Modifier.weight(1f)
            )
            3 -> WebProxyPanel(viewModel, Modifier.weight(1f))
            else -> GenericProxyPanel(viewModel, Modifier.weight(1f))
        }
    }
}

@Composable
private fun ComparisonPanel(
    proxies: List<MtProtoProxy>,
    currentNetworkFingerprint: String,
    networkBaseline: NetworkSpeedBaseline?,
    copy: VaultCopy,
    onRemove: (MtProtoProxy) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(copy.compareTitle, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    copy.compareCount(proxies.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onClear, enabled = proxies.isNotEmpty()) {
                Icon(Icons.Default.ClearAll, contentDescription = copy.clearComparison)
            }
        }
        if (proxies.isEmpty()) {
            VaultEmptyState(Icons.AutoMirrored.Filled.CompareArrows, copy.noComparison, Modifier.weight(1f))
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 12.dp)
            ) {
                items(proxies, key = MtProtoProxy::url) { proxy ->
                    val insight = ProxyInsightEngine.evaluate(
                        proxy = proxy,
                        currentNetworkFingerprint = currentNetworkFingerprint,
                        networkBaseline = networkBaseline
                    )
                    ComparisonCard(proxy, insight.profileScores, insight.confidence, insight.currentNetworkMatch, copy) {
                        onRemove(proxy)
                    }
                }
            }
        }
    }
}

@Composable
private fun ComparisonCard(
    proxy: MtProtoProxy,
    scores: Map<ProxyUseProfile, Int>,
    confidence: Int,
    networkMatch: Boolean,
    copy: VaultCopy,
    onRemove: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(proxy.server, style = endpointStyle(MaterialTheme.typography.bodyMedium), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${proxy.country} • ${proxy.port} • ${copy.confidence} $confidence/100",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (networkMatch) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error
                    )
                }
                IconButton(onClick = onRemove) {
                    Icon(Icons.Default.Close, contentDescription = copy.remove)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ProfileMetric(copy.balanced, scores[ProxyUseProfile.BALANCED] ?: 0, Modifier.weight(1f))
                ProfileMetric(copy.download, scores[ProxyUseProfile.DOWNLOAD] ?: 0, Modifier.weight(1f))
                ProfileMetric(copy.upload, scores[ProxyUseProfile.UPLOAD] ?: 0, Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ProfileMetric(copy.weakNetwork, scores[ProxyUseProfile.WEAK_NETWORK] ?: 0, Modifier.weight(1f))
                ProfileMetric(copy.messaging, scores[ProxyUseProfile.MESSAGING] ?: 0, Modifier.weight(1f))
            }
            if (!networkMatch) {
                Text(copy.otherNetwork, color = MaterialTheme.colorScheme.error, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun ProfileMetric(label: String, value: Int, modifier: Modifier = Modifier) {
    Surface(modifier, shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 7.dp)) {
            Text(label, fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            Text("$value/100", fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        }
    }
}

@Composable
private fun FavoriteVaultPanel(
    favorites: List<FavoriteProxy>,
    copy: VaultCopy,
    onUpdate: (String, String, String, String) -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
    onQr: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var editing by remember { mutableStateOf<FavoriteProxy?>(null) }
    editing?.let { favorite ->
        FavoriteMetadataDialog(favorite, copy, onDismiss = { editing = null }) { folder, tags, note ->
            onUpdate(favorite.url, folder, tags, note)
            editing = null
        }
    }
    Column(modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(copy.favoritesTitle, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            IconButton(onClick = onImport) { Icon(Icons.Default.FileDownload, contentDescription = copy.importProfile) }
            IconButton(onClick = onExport) { Icon(Icons.Default.FileUpload, contentDescription = copy.exportProfile) }
        }
        if (favorites.isEmpty()) {
            VaultEmptyState(Icons.Default.Folder, copy.noFavorites, Modifier.weight(1f))
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 12.dp)
            ) {
                items(favorites, key = FavoriteProxy::url) { favorite ->
                    FavoriteCard(
                        favorite = favorite,
                        copy = copy,
                        onQr = { onQr(favorite.url) },
                        onEdit = { editing = favorite }
                    )
                }
            }
        }
    }
}

@Composable
private fun FavoriteCard(
    favorite: FavoriteProxy,
    copy: VaultCopy,
    onQr: () -> Unit,
    onEdit: () -> Unit
) {
    val context = LocalContext.current
    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(favorite.server, style = endpointStyle(MaterialTheme.typography.bodyMedium), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${favorite.folder} • ${favorite.country}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp
                    )
                }
                IconButton(onClick = onQr) {
                    Icon(Icons.Default.QrCode2, contentDescription = copy.shareQr)
                }
                IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, contentDescription = copy.edit) }
            }
            if (favorite.tags.isNotBlank()) {
                Text(favorite.tags.split(',').joinToString("  •  "), fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
            }
            if (favorite.note.isNotBlank()) {
                Text(favorite.note, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.History, contentDescription = null, modifier = Modifier.size(16.dp))
                Text(
                    copy.lastKnown(favorite),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 4
                )
                IconButton(onClick = {
                    if (!openProxy(context, favorite.url)) {
                        Toast.makeText(context, copy.noTelegram, Toast.LENGTH_LONG).show()
                    }
                }) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = copy.connect)
                }
            }
        }
    }
}

@Composable
private fun FavoriteMetadataDialog(
    favorite: FavoriteProxy,
    copy: VaultCopy,
    onDismiss: () -> Unit,
    onSave: (String, String, String) -> Unit
) {
    var folder by remember(favorite.url) { mutableStateOf(favorite.folder) }
    var tags by remember(favorite.url) { mutableStateOf(favorite.tags) }
    var note by remember(favorite.url) { mutableStateOf(favorite.note) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(copy.editFavorite) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(folder, { folder = it.take(32) }, label = { Text(copy.folder) }, singleLine = true)
                OutlinedTextField(tags, { tags = it.take(120) }, label = { Text(copy.tags) }, singleLine = true)
                OutlinedTextField(note, { note = it.take(240) }, label = { Text(copy.note) }, minLines = 2, maxLines = 4)
            }
        },
        confirmButton = { TextButton(onClick = { onSave(folder, tags, note) }) { Text(copy.save) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(copy.cancel) } },
        shape = RoundedCornerShape(8.dp)
    )
}

@Composable
private fun PrivateNodesPanel(
    nodes: List<PrivateProxyNode>,
    currentNetworkFingerprint: String,
    testingUrls: Set<String>,
    monitoringEnabled: Boolean,
    copy: VaultCopy,
    onAdd: (String, String) -> Unit,
    onToggle: (String, Boolean) -> Unit,
    onTest: (String) -> Unit,
    onDelete: (String) -> Unit,
    onCompare: (MtProtoProxy) -> Unit,
    onMonitoringChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    var showAdd by rememberSaveable { mutableStateOf(false) }
    if (showAdd) {
        AddPrivateNodeDialog(copy, onDismiss = { showAdd = false }) { url, label ->
            onAdd(url, label)
            showAdd = false
        }
    }
    Column(modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(copy.privateTitle, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(copy.privateCount(nodes.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            FilledTonalButton(onClick = { showAdd = true }, shape = RoundedCornerShape(8.dp)) {
                Icon(Icons.Default.Add, contentDescription = null)
                Text(copy.add)
            }
        }
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(copy.monitorPrivate, modifier = Modifier.weight(1f), fontSize = 12.sp, fontWeight = FontWeight.Medium)
                Switch(checked = monitoringEnabled, onCheckedChange = onMonitoringChanged,
                    modifier = Modifier.semantics { contentDescription = copy.monitorPrivate })
            }
        }
        if (nodes.isEmpty()) {
            VaultEmptyState(Icons.Default.Lock, copy.noPrivateNodes, Modifier.weight(1f))
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 12.dp)
            ) {
                items(nodes, key = PrivateProxyNode::url) { node ->
                    PrivateNodeCard(
                        node = node,
                        currentNetworkFingerprint = currentNetworkFingerprint,
                        testing = node.url in testingUrls,
                        copy = copy,
                        onToggle = { onToggle(node.url, it) },
                        onTest = { onTest(node.url) },
                        onDelete = { onDelete(node.url) },
                        onCompare = { onCompare(node.asProxy()) }
                    )
                }
            }
        }
    }
}

@Composable
private fun PrivateNodeCard(
    node: PrivateProxyNode,
    currentNetworkFingerprint: String,
    testing: Boolean,
    copy: VaultCopy,
    onToggle: (Boolean) -> Unit,
    onTest: () -> Unit,
    onDelete: () -> Unit,
    onCompare: () -> Unit
) {
    val passed = hasCurrentPrivateEvidence(node, currentNetworkFingerprint)
    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (passed) Icons.Default.CheckCircle else Icons.Default.Lock,
                    contentDescription = null,
                    tint = if (passed) Color(0xFF059669) else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.size(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(node.label, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${node.server}:${node.port}", style = endpointStyle(MaterialTheme.typography.bodySmall),
                        fontFamily = FontFamily.Monospace)
                }
                Switch(checked = node.enabled, onCheckedChange = onToggle,
                    modifier = Modifier.semantics {
                        contentDescription = (if (copy.isFa) "\u0641\u0639\u0627\u0644\u200c\u0628\u0648\u062f\u0646 " else "Enable ") + node.label
                    })
            }
            Text(
                copy.privateStatus(node, currentNetworkFingerprint),
                fontSize = 11.sp,
                color = if (passed) Color(0xFF059669) else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onTest, enabled = !testing, modifier = Modifier.weight(1f), shape = RoundedCornerShape(8.dp)) {
                    if (testing) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Default.Speed, contentDescription = null, modifier = Modifier.size(17.dp))
                    Text(copy.test, maxLines = 1)
                }
                IconButton(onClick = onCompare, enabled = node.lastChecked > 0L) {
                    Icon(Icons.AutoMirrored.Filled.CompareArrows, contentDescription = copy.compare)
                }
                IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, contentDescription = copy.delete) }
            }
        }
    }
}

@Composable
private fun AddPrivateNodeDialog(
    copy: VaultCopy,
    onDismiss: () -> Unit,
    onAdd: (String, String) -> Unit
) {
    var label by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(copy.addPrivate) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(label, { label = it.take(48) }, label = { Text(copy.label) }, singleLine = true)
                OutlinedTextField(url, { url = it.take(900) }, label = { Text(copy.mtprotoLink) }, minLines = 2, maxLines = 4)
            }
        },
        confirmButton = {
            TextButton(onClick = { onAdd(url.trim(), label.trim()) }, enabled = MtProtoProxy.parseFromUrl(url) != null) {
                Text(copy.add)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(copy.cancel) } },
        shape = RoundedCornerShape(8.dp)
    )
}

@Composable
private fun VaultEmptyState(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(42.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun openProxy(context: Context, rawUrl: String): Boolean {
    val proxy = MtProtoProxy.parseFromUrl(rawUrl) ?: return false
    val candidates = listOf(
        proxy.url.toUri(),
        Uri.Builder().scheme("https").authority("t.me").appendPath("proxy")
            .appendQueryParameter("server", proxy.server)
            .appendQueryParameter("port", proxy.port.toString())
            .appendQueryParameter("secret", proxy.secret)
            .build()
    )
    return candidates.any { uri ->
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }
}

private data class VaultCopy(
    val isFa: Boolean,
    val tabs: List<String>,
    val compareTitle: String,
    val clearComparison: String,
    val noComparison: String,
    val noFavorites: String,
    val favoritesTitle: String,
    val importProfile: String,
    val exportProfile: String,
    val shareQr: String,
    val privateTitle: String,
    val noPrivateNodes: String,
    val monitorPrivate: String,
    val confidence: String,
    val balanced: String,
    val download: String,
    val upload: String,
    val weakNetwork: String,
    val messaging: String,
    val otherNetwork: String,
    val remove: String,
    val edit: String,
    val editFavorite: String,
    val folder: String,
    val tags: String,
    val note: String,
    val save: String,
    val cancel: String,
    val connect: String,
    val noTelegram: String,
    val add: String,
    val addPrivate: String,
    val label: String,
    val mtprotoLink: String,
    val test: String,
    val compare: String,
    val delete: String
) {
    fun compareCount(count: Int) = if (isFa) "$count از ۴ انتخاب" else "$count of 4 selected"
    fun privateCount(count: Int) = if (isFa) "$count نود محلی" else "$count local nodes"

    fun lastKnown(favorite: FavoriteProxy): String {
        if (favorite.lastKnownGoodAt <= 0L) return if (isFa) "هنوز نتیجهٔ موفقی ذخیره نشده"
            else "No successful result saved yet"
        val date = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
            .format(Date(favorite.lastKnownGoodAt))
        val latency = if (favorite.lastKnownLatency > 0) favorite.lastKnownLatency.toString() + " ms" else "—"
        val rates = if (favorite.lastKnownDownloadKbps > 0 || favorite.lastKnownUploadKbps > 0)
            " • " + formatRate(favorite.lastKnownDownloadKbps) + " / " + formatRate(favorite.lastKnownUploadKbps)
            else ""
        val savedLabel = if (isFa) "نتیجهٔ قبلی؛ نیازمند بازآزمایی" else "Saved result; retest on this network"
        return savedLabel + "\n\u2066" + date + " • " + latency + " • " +
            favorite.lastKnownTransferScore + "/100" + rates + "\u2069"
    }

    fun privateStatus(node: PrivateProxyNode, network: String): String {
        if (node.lastChecked <= 0L) return if (isFa) "تست‌نشده روی شبکه فعلی" else "Not tested on the current network"
        if (network.isBlank() || node.networkFingerprint != network) return if (isFa)
            "نتیجهٔ شبکهٔ دیگر؛ بازآزمایی کنید" else "Other network result; retest"
        return if (isFa) {
            "${node.verificationStatus} • ${node.latency}ms • شواهد ${node.verificationConfidence}/100"
        } else {
            "${node.verificationStatus} • ${node.latency}ms • evidence ${node.verificationConfidence}/100"
        }
    }
}

private fun vaultCopy(language: String): VaultCopy {
    val fa = isPersianLanguage(language)
    return if (fa) VaultCopy(
        true, listOf("مقایسه", "علاقه‌مندی‌ها", "خصوصی", "WEB", "HTTP / SOCKS"), "مقایسه سناریوها", "پاک‌کردن مقایسه",
        "برای مقایسه، از کنار پروکسی‌ها گزینه مقایسه را بزنید.", "هنوز پروکسی علاقه‌مندی ذخیره نشده است.",
        "پروکسی‌های ذخیره‌شده", "ورود فایل", "خروجی فایل", "اشتراک QR",
        "نودهای خصوصی", "نود خصوصی اضافه نشده است.", "پایش دوره‌ای نودهای فعال", "شواهد", "متعادل", "دانلود", "آپلود",
        "شبکه ضعیف", "پیام‌رسانی", "این نتیجه روی شبکهٔ فعلی تأیید نشده است.", "حذف", "ویرایش",
        "ویرایش علاقه‌مندی", "پوشه", "برچسب‌ها با ویرگول", "یادداشت", "ذخیره", "لغو", "اتصال",
        "تلگرام برای بازکردن لینک پیدا نشد", "افزودن", "افزودن نود خصوصی", "نام", "لینک MTProto",
        "تست", "مقایسه", "حذف"
    ) else VaultCopy(
        false, listOf("Compare", "Favorites", "Private", "WEB", "HTTP / SOCKS"), "Scenario comparison", "Clear comparison",
        "Select proxies for comparison from the proxy list.", "No favorite proxy has been saved yet.",
        "Saved proxies", "Import profile", "Export profile", "Share QR",
        "Private nodes", "No private node has been added.", "Monitor enabled nodes", "Evidence", "Balanced", "Download", "Upload",
        "Weak network", "Messaging", "Not verified on the current network.", "Remove", "Edit",
        "Edit favorite", "Folder", "Comma-separated tags", "Note", "Save", "Cancel", "Connect",
        "Telegram could not open the proxy link", "Add", "Add private node", "Label", "MTProto link",
        "Test", "Compare", "Delete"
    )
}

private fun formatRate(kbps: Int): String = formatKbps(kbps)
