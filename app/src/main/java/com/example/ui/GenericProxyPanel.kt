package com.example.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import com.example.data.generic.GenericProtocol
import com.example.data.generic.GenericSources
import com.example.viewmodel.ProxyViewModel
import java.text.DateFormat
import java.util.Date

@Composable
fun GenericProxyPanel(viewModel: ProxyViewModel, modifier: Modifier = Modifier) {
    val language by viewModel.language.collectAsStateWithLifecycle()
    val fa = isPersianLanguage(language)
    fun text(en: String, persian: String) = if (fa) persian else en
    val controller = viewModel.genericProxies
    val state by controller.state.collectAsStateWithLifecycle()
    val count by controller.count.collectAsStateWithLifecycle(0)
    val health by controller.health.collectAsStateWithLifecycle(emptyList())
    val query by controller.query.collectAsStateWithLifecycle()
    val protocol by controller.protocol.collectAsStateWithLifecycle()
    val network by viewModel.currentNetworkInfo.collectAsStateWithLifecycle()
    val nodes = controller.pages.collectAsLazyPagingItems()
    var requested by rememberSaveable { mutableStateOf("20") }
    var menu by remember { mutableStateOf(false) }
    var sources by rememberSaveable { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(controller, lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) controller.cancel() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); controller.cancel() }
    }
    LazyColumn(modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("HTTP / SOCKS ($count)", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = { sources = true }) { Icon(Icons.Default.Info, text("Source health", "سلامت منابع")) }
                IconButton(onClick = controller::refresh, enabled = !state.running) { Icon(Icons.Default.Refresh, text("Get generic proxies", "دریافت پروکسی عمومی")) }
            }
        }
        item {
            OutlinedTextField(query, { controller.query.value = it.take(253) }, Modifier.fillMaxWidth(),
                singleLine = true, label = { Text(text("Search host", "جستجوی آدرس")) },
                leadingIcon = { Icon(Icons.Default.Search, null) })
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box {
                    TextButton(onClick = { menu = true }) { Text(if (protocol == "ALL") text("All protocols", "همه پروتکل‌ها") else protocol) }
                    DropdownMenu(menu, { menu = false }) {
                        (listOf("ALL") + GenericProtocol.entries.map { it.name }).forEach { value ->
                            DropdownMenuItem(text = { Text(value) }, onClick = { controller.protocol.value = value; menu = false })
                        }
                    }
                }
                OutlinedTextField(requested, { requested = it.take(10) }, Modifier.weight(1f), singleLine = true,
                    label = { Text(text("Count", "تعداد")) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                IconButton(onClick = { controller.testMany(requested) }, enabled = !state.running && count > 0) {
                    Icon(Icons.Default.PlayArrow, text("Test selected count", "تست تعداد انتخاب‌شده"))
                }
            }
        }
        if (state.running || state.message.isNotBlank()) item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(genericMessage(state.message, fa), style = MaterialTheme.typography.bodySmall)
                    if (state.total > 0) Text("${state.completed} / ${state.total} | ${text("Passed", "موفق")}: ${state.succeeded}",
                        style = MaterialTheme.typography.labelMedium.copy(textDirection = TextDirection.Ltr))
                    if (state.running) LinearProgressIndicator(
                        progress = { if (state.total > 0) (state.completed.toFloat() / state.total).coerceIn(0f, 1f) else 0f },
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                }
                if (state.running) IconButton(onClick = controller::cancel) { Icon(Icons.Default.Stop, text("Cancel generic test", "لغو تست عمومی")) }
            }
        }
        if (nodes.loadState.refresh is androidx.paging.LoadState.Error) item {
            TextButton(onClick = nodes::retry) { Text(text("Retry loading", "تلاش دوباره برای بارگذاری")) }
        }
        if (nodes.itemCount == 0 && !state.running) item { Text(text("No matching endpoints", "پروکسی مطابق فیلتر موجود نیست"), style = MaterialTheme.typography.bodyMedium) }
        items(nodes.itemCount, key = nodes.itemKey { it.id }) { index ->
            nodes[index]?.let { node ->
                Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${node.host}:${node.port}", Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Ltr))
                        IconButton(onClick = { controller.test(node) }, enabled = !state.running) {
                            Icon(Icons.Default.PlayArrow, text("Test", "تست") + " ${node.host}")
                        }
                    }
                    Text("${node.protocol} | ${text("Source country", "کشور اعلامی منبع")}: ${node.sourceCountry}",
                        style = MaterialTheme.typography.labelMedium)
                    val current = node.networkFingerprint.isNotBlank() && node.networkFingerprint == network?.fingerprint
                    Text(genericMessage(if (node.testedAt > 0 && !current) "OTHER_NETWORK" else node.status, fa),
                        color = if (node.status == "WORKING" && current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall)
                    if (node.testedAt > 0) {
                        Text(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(node.testedAt)), style = MaterialTheme.typography.labelSmall)
                        if (node.status == "WORKING") Text("${text("Connect", "اتصال")}: ${node.connectionMs} ms | ${text("Request", "درخواست")}: ${node.requestMs} ms", style = MaterialTheme.typography.labelSmall)
                    }
                    HorizontalDivider(Modifier.padding(top = 8.dp))
                }
            }
        }
    }
    if (sources) AlertDialog(onDismissRequest = { sources = false }, title = { Text(text("Generic sources", "منابع عمومی")) },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(GenericSources.feeds.size) { index ->
                    val feed = GenericSources.feeds[index]
                    val item = health.firstOrNull { it.id == feed.id }
                    Text("${feed.id}\n${item?.accepted ?: 0} | ${genericMessage(item?.errorCode?.ifBlank { "FETCHED" } ?: "UNTESTED", fa)}", style = MaterialTheme.typography.bodySmall)
                    if (item != null && item.succeededAt > 0) Text(
                        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(item.succeededAt)),
                        style = MaterialTheme.typography.labelSmall)
                }
                GenericSources.disabled.forEach { (name, reason) -> item {
                    Text("$name\n${text("Disabled", "غیرفعال")}: $reason", style = MaterialTheme.typography.bodySmall)
                } }
            }
        }, confirmButton = { TextButton(onClick = { sources = false }) { Text(text("Close", "بستن")) } })
}

internal fun genericMessage(code: String, fa: Boolean): String {
    if (code.startsWith("SOURCES:")) {
        val counts = code.split(':').drop(1).mapNotNull(String::toIntOrNull)
        if (counts.size == 3) return if (fa) "دریافت: ${counts[0]}، کش: ${counts[1]}، ناموفق: ${counts[2]}"
            else "Fetched: ${counts[0]}, cached: ${counts[1]}, failed: ${counts[2]}"
    }
    val labels = mapOf(
        "UNTESTED" to ("Untested" to "تست‌نشده"), "WORKING" to ("HTTPS request verified" to "درخواست HTTPS تأیید شد"),
        "FETCHING" to ("Fetching sources" to "دریافت منابع"), "TESTING" to ("Testing on this network" to "تست با شبکه فعلی"),
        "FINISHED" to ("Tests finished" to "تست‌ها تمام شد"), "CANCELLED" to ("Cancelled" to "لغو شد"),
        "INCONCLUSIVE" to ("Network result inconclusive" to "نتیجه شبکه نامشخص"), "OTHER_NETWORK" to ("Measured on another network" to "اندازه‌گیری با شبکه دیگر"),
        "CONNECTION_FAILED" to ("TCP connection failed" to "اتصال TCP ناموفق"), "HANDSHAKE_FAILED" to ("Proxy handshake failed" to "مذاکره پروکسی ناموفق"),
        "TLS_FAILED" to ("TLS verification failed" to "تأیید TLS ناموفق"), "AUTH_FAILED" to ("Authentication required or rejected" to "نیازمند احراز هویت یا رد دسترسی"),
        "REQUEST_FAILED" to ("Test destination request failed" to "درخواست مقصد تست ناموفق"), "TIMEOUT" to ("Timed out" to "مهلت تمام شد"),
        "INVALID_COUNT" to ("Enter a positive count" to "تعداد مثبت وارد کنید"), "UNMETERED_REQUIRED" to ("Unmetered network required" to "اینترنت نامحدود لازم است"),
        "BUSY" to ("Another operation is active" to "عملیات دیگری در حال اجراست"), "EMPTY" to ("No usable entries" to "ورودی معتبر موجود نیست"),
        "FETCHED" to ("List received; not a proxy test" to "فهرست دریافت شد؛ پروکسی‌ها تست نشده‌اند"),
        "FETCH_FAILED" to ("Source request failed" to "دریافت منبع ناموفق"), "FAILED" to ("Operation failed" to "عملیات ناموفق"),
        "UNSUPPORTED" to ("Unsupported protocol" to "پروتکل پشتیبانی نمی‌شود")
    )
    return labels[code]?.let { if (fa) it.second else it.first } ?: code
}
