package com.example.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.telegram.TelegramAuthorizationStep
import com.example.model.WebProxyNode
import com.example.viewmodel.ProxyViewModel
import java.text.DateFormat
import java.util.Date

@Composable
fun WebProxyPanel(viewModel: ProxyViewModel, modifier: Modifier = Modifier) {
    val language by viewModel.language.collectAsStateWithLifecycle()
    val fa = isPersianLanguage(language)
    fun text(en: String, faText: String) = if (fa) faText else en
    val controller = viewModel.webProxies
    val nodes by controller.nodes.collectAsStateWithLifecycle(initialValue = emptyList())
    val state by controller.state.collectAsStateWithLifecycle()
    val telegram by viewModel.telegramTransferState.collectAsStateWithLifecycle()
    val network by viewModel.currentNetworkInfo.collectAsStateWithLifecycle()
    val signedIn = telegram.step in setOf(TelegramAuthorizationStep.READY, TelegramAuthorizationStep.TRANSFERRING)
    var importOpen by rememberSaveable { mutableStateOf(false) }
    var input by rememberSaveable { mutableStateOf("") }
    var transfer by rememberSaveable { mutableStateOf(false) }
    var pendingTest by remember { mutableStateOf<WebProxyNode?>(null) }
    var pendingConnect by remember { mutableStateOf<WebProxyNode?>(null) }
    var pendingDelete by remember { mutableStateOf<WebProxyNode?>(null) }
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(controller, lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) controller.cancel() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); controller.cancel() }
    }
    val fingerprint = network?.fingerprint.orEmpty()
    val sorted = remember(nodes, fingerprint) { rankWebProxies(nodes, fingerprint) }
    val listState = rememberLazyListState()
    LaunchedEffect(nodes.size, state.message) {
        if (state.message.startsWith("IMPORTED:") || state.message == "PUBLIC_UNVERIFIED") {
            listState.scrollToItem(0)
        }
    }

    Column(modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (state.running) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Text((if (state.stage > 0) "${state.stage}/4 · " else "") + webMessage(state.message, fa), Modifier.weight(1f).padding(horizontal = 10.dp),
                    style = MaterialTheme.typography.bodySmall)
                IconButton(onClick = controller::cancel) { Icon(Icons.Default.Stop, text("Cancel WEB test", "لغو تست WEB")) }
            }
        }
        LazyColumn(Modifier.weight(1f), state = listState, verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 16.dp)) {
            item {
                Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(text("WEB Proxies", "وب‌پروکسی‌های تلگرام") + " (${nodes.size})", style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f))
                    IconButton(onClick = controller::refreshSources, enabled = !state.running) {
                        Icon(Icons.Default.Refresh, text("Get WEB proxies", "دریافت وب‌پروکسی‌ها"))
                    }
                    IconButton(onClick = { importOpen = true }, enabled = !state.running) {
                        Icon(Icons.Default.Add, text("Add WEB links", "افزودن لینک WEB"))
                    }
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(text("Real Telegram file transfer", "انتقال واقعی فایل تلگرام"), modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium)
                        Switch(checked = transfer && signedIn, onCheckedChange = { transfer = it }, enabled = signedIn && !state.running,
                            modifier = Modifier.semantics { contentDescription = text("Real Telegram file transfer", "\u0627\u0646\u062a\u0642\u0627\u0644 \u0648\u0627\u0642\u0639\u06cc \u0641\u0627\u06cc\u0644 \u062a\u0644\u06af\u0631\u0627\u0645") })
                    }
                    if (!signedIn) Text(text("Telegram sign-in required for file transfer", "برای انتقال فایل باید وارد تلگرام شوید"),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (!state.running && state.message.isNotBlank()) item {
                Text(webMessage(state.message, fa), style = MaterialTheme.typography.bodySmall)
            }
            if (nodes.isEmpty()) item {
                Box(Modifier.fillParentMaxHeight(0.5f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(text("No WEB proxies", "وب‌پروکسی اضافه نشده است"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items(sorted, key = { it.url }) { node ->
                Card(shape = RoundedCornerShape(8.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(node.address, style = MaterialTheme.typography.titleSmall.copy(textDirection = TextDirection.Ltr),
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        val sameNetwork = fingerprint.isNotEmpty() && node.networkFingerprint == fingerprint
                        Text(if (sameNetwork) "${node.stage}/4 · ${webMessage(node.status, fa)}"
                            else text("Untested on this network", "روی این شبکه تست نشده"), style = MaterialTheme.typography.bodySmall)
                        if (sameNetwork && node.latencyMs > 0 && node.status in setOf("PROTOCOL_VERIFIED", "TRANSFER_VERIFIED")) {
                            Text("${node.latencyMs} ms · ${node.networkType}",
                                style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Ltr))
                        }
                        if (sameNetwork && node.hasRealTransfer) {
                            Text("↓ ${node.downloadKbps} KiB/s   ↑ ${node.uploadKbps} KiB/s",
                                style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Ltr))
                            Text("${node.transferRounds} " + text("verified rounds", "دور تأییدشده"),
                                style = MaterialTheme.typography.bodySmall)
                        }
                        if (node.testedAt > 0) Text(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(node.testedAt)),
                            style = MaterialTheme.typography.labelSmall.copy(textDirection = TextDirection.ContentOrLtr),
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            IconButton(onClick = { pendingTest = node }, enabled = !state.running) {
                                Icon(Icons.Default.PlayArrow, text("Test WEB proxy", "تست وب‌پروکسی"))
                            }
                            IconButton(onClick = { pendingConnect = node }, enabled = !state.running) {
                                Icon(Icons.AutoMirrored.Filled.Send, text("Open WEB proxy in Telegram", "بازکردن وب‌پروکسی در تلگرام"))
                            }
                            IconButton(onClick = { clipboard.setText(AnnotatedString(node.url)) }) {
                                Icon(Icons.Default.ContentCopy, text("Copy WEB link", "کپی لینک WEB"))
                            }
                            IconButton(onClick = { pendingDelete = node }, enabled = !state.running) {
                                Icon(Icons.Default.Delete, text("Delete WEB proxy", "حذف وب‌پروکسی"))
                            }
                        }
                    }
                }
            }
        }
    }
    if (importOpen) AlertDialog(
        onDismissRequest = { importOpen = false },
        title = { Text(text("Add WEB links", "افزودن لینک WEB")) },
        text = { OutlinedTextField(input, { input = it.take(100_000) }, label = { Text(text("tg://webproxy or t.me/webproxy", "لینک tg://webproxy یا t.me/webproxy")) },
            minLines = 3, maxLines = 7, modifier = Modifier.fillMaxWidth()) },
        confirmButton = { TextButton(onClick = { controller.importLinks(input); input = ""; importOpen = false }, enabled = input.isNotBlank()) {
            Text(text("Add", "افزودن")) } },
        dismissButton = { TextButton(onClick = { importOpen = false }) { Text(text("Cancel", "لغو")) } }
    )
    val confirmation = pendingTest ?: pendingConnect
    if (confirmation != null) AlertDialog(
        onDismissRequest = { pendingTest = null; pendingConnect = null },
        title = { Text(text("Trust this WEB provider?", "به ارائه‌دهندهٔ WEB اعتماد دارید؟")) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(confirmation.address)
            Text(text("This provider runs JavaScript in a restricted WebView. Only continue with a provider you trust. WEB requires a compatible Telegram client.",
                "ارائه‌دهنده در WebView محدودشده جاوااسکریپت اجرا می‌کند. فقط برای ارائه‌دهندهٔ مورد اعتماد ادامه دهید. اتصال WEB به نسخهٔ سازگار تلگرام نیاز دارد."))
            if (pendingTest != null && transfer && signedIn) Text(text("The test uploads and downloads three random files in Saved Messages (about 9 MiB payload, plus protocol overhead).",
                "تست، سه فایل تصادفی را در پیام‌های ذخیره‌شده آپلود و دانلود می‌کند؛ حدود ۹ مگابایت داده به‌علاوهٔ سربار شبکه."))
        } },
        confirmButton = { TextButton(onClick = {
            if (pendingTest != null) controller.test(confirmation, transfer && signedIn)
            else try { context.startActivity(Intent(Intent.ACTION_VIEW, confirmation.url.toUri())) }
            catch (_: ActivityNotFoundException) { Toast.makeText(context, text("No compatible Telegram client", "تلگرام سازگار پیدا نشد"), Toast.LENGTH_LONG).show() }
            pendingTest = null; pendingConnect = null
        }) { Text(text("Continue", "ادامه")) } },
        dismissButton = { TextButton(onClick = { pendingTest = null; pendingConnect = null }) { Text(text("Cancel", "لغو")) } }
    )
    pendingDelete?.let { node -> AlertDialog(onDismissRequest = { pendingDelete = null },
        title = { Text(text("Delete WEB proxy?", "وب‌پروکسی حذف شود؟")) }, text = { Text(node.address) },
        confirmButton = { TextButton(onClick = { controller.delete(node); pendingDelete = null }) { Text(text("Delete", "حذف")) } },
        dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text(text("Cancel", "لغو")) } }) }
}

internal fun rankWebProxies(nodes: List<WebProxyNode>, fingerprint: String): List<WebProxyNode> = nodes.sortedWith(
    compareByDescending<WebProxyNode> { fingerprint.isNotEmpty() && it.networkFingerprint == fingerprint && it.hasRealTransfer }
        .thenByDescending { if (it.networkFingerprint == fingerprint && it.hasRealTransfer)
            2.0 * it.downloadKbps * it.uploadKbps / (it.downloadKbps.toDouble() + it.uploadKbps) else 0.0 }
        .thenByDescending { fingerprint.isNotEmpty() && it.networkFingerprint == fingerprint && it.status == "PROTOCOL_VERIFIED" }
        .thenBy { if (fingerprint.isNotEmpty() && it.networkFingerprint == fingerprint &&
            it.status in setOf("PROTOCOL_VERIFIED", "TRANSFER_VERIFIED") && it.latencyMs > 0)
            it.latencyMs else Long.MAX_VALUE }.thenBy { it.url }
)

private fun webMessage(code: String, fa: Boolean): String {
    if (code.startsWith("IMPORTED:")) return if (fa) "${code.substringAfter(':')} لینک جدید ذخیره شد" else "${code.substringAfter(':')} new links saved"
    val messages = mapOf(
        "UNTESTED" to ("Untested" to "تست نشده"), "TESTING" to ("Testing" to "در حال تست"),
        "DNS" to ("DNS validation" to "بررسی DNS"), "BRIDGE" to ("Secure WEB session" to "نشست امن WEB"),
        "MTPROTO" to ("Telegram route checks" to "بررسی مسیر تلگرام"), "TRANSFER" to ("Real file transfer" to "انتقال واقعی فایل"),
        "PROTOCOL_VERIFIED" to ("Telegram protocol verified; file speed unmeasured" to "پروتکل تلگرام تأیید شد؛ سرعت فایل اندازه‌گیری نشده"),
        "TRANSFER_VERIFIED" to ("Real Telegram transfer verified" to "انتقال واقعی تلگرام تأیید شد"),
        "FAILED" to ("Test failed" to "تست ناموفق"), "TEST_FAILED" to ("Test failed" to "تست ناموفق"),
        "INCONCLUSIVE" to ("Inconclusive: local network issue" to "نامشخص: مشکل شبکهٔ دستگاه"),
        "TIMEOUT" to ("Test timed out" to "مهلت تست تمام شد"), "CANCELLED" to ("Test cancelled" to "تست لغو شد"),
        "INTERRUPTED" to ("Previous test was interrupted" to "تست قبلی نیمه‌کاره متوقف شد"),
        "CLIENT_UNAVAILABLE" to ("WEB test unavailable: update Android System WebView" to "تست WEB در دسترس نیست؛ Android System WebView را به‌روز کنید"),
        "BUSY" to ("Another network operation is running" to "عملیات شبکهٔ دیگری در حال اجراست"),
        "INVALID_WEB_LINK" to ("No valid WEB link found" to "لینک WEB معتبر پیدا نشد"),
        "IMPORT_TOO_LARGE" to ("WEB link list is too large" to "فهرست لینک‌های WEB بیش از حد بزرگ است"),
        "SIGN_IN_REQUIRED" to ("Telegram sign-in required" to "ورود به تلگرام لازم است"),
        "NO_NETWORK" to ("No active Internet connection" to "اتصال اینترنت فعال نیست"),
        "NETWORK_CHANGED" to ("Network changed; test again" to "شبکه تغییر کرد؛ دوباره تست بگیرید"),
        "UNMETERED_REQUIRED" to ("An unmetered connection is required" to "اتصال اینترنت نامحدود لازم است"),
        "DNS_FAILED" to ("DNS lookup failed; proxy availability is unknown" to "DNS ناموفق بود؛ وضعیت پروکسی مشخص نیست"),
        "DNS_BUSY" to ("DNS resolver busy; try again later" to "DNS مشغول است؛ کمی بعد دوباره تلاش کنید"),
        "WEBVIEW_UPDATE_REQUIRED" to ("Update Android System WebView to test WEB proxies" to "برای تست، Android System WebView را به‌روز کنید"),
        "NON_PUBLIC_HOST" to ("Private or unsafe server address rejected" to "نشانی خصوصی یا ناامن رد شد"),
        "TELEGRAM_ROUTE_FAILED" to ("The WEB relay did not confirm the Telegram route" to "پل WEB مسیر تلگرام را تأیید نکرد"),
        "FETCHING" to ("Fetching WEB sources" to "دریافت منابع WEB"),
        "PUBLIC_UNVERIFIED" to ("Sources unreachable. Public starter links are unverified; test them before use." to "منابع در دسترس نبودند. لینک‌های اولیه عمومی تست نشده‌اند؛ قبل از استفاده تست بگیرید."),
        "SOURCES_FAILED" to ("Unable to fetch WEB sources" to "دریافت منابع WEB ناموفق بود"),
        "STORAGE_FAILED" to ("Unable to save local WEB data" to "ذخیرهٔ اطلاعات محلی WEB ناموفق بود")
    )
    return messages[code]?.let { if (fa) it.second else it.first } ?: if (fa) "تست کامل نشد ($code)" else "Test incomplete ($code)"
}
