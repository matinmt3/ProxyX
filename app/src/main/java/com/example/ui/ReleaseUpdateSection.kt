package com.example.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.update.ReleaseUpdateController
import com.example.data.update.UpdatePhase

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReleaseUpdateSection(controller: ReleaseUpdateController, isFa: Boolean) {
    val state by controller.state.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(if (isFa) "به‌روزرسانی برنامه" else "App Updates", style = MaterialTheme.typography.titleMedium)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            listOf("stable", "beta").forEachIndexed { index, channel ->
                SegmentedButton(selected = state.channel == channel,
                    enabled = !state.busy,
                    onClick = { controller.selectChannel(channel) },
                    shape = SegmentedButtonDefaults.itemShape(index, 2)) {
                    Text(if (channel == "stable") { if (isFa) "پایدار" else "Stable" }
                        else { if (isFa) "آزمایشی" else "Beta" })
                }
            }
        }
        val label = when (state.phase) {
            UpdatePhase.UNCONFIGURED -> if (isFa) "نشانی انتشار آنلاین هنوز توسط سازنده تنظیم نشده است." else "The publisher has not configured an online release feed."
            UpdatePhase.IDLE -> if (isFa) "آمادهٔ بررسی" else "Ready to check"
            UpdatePhase.CHECKING -> if (isFa) "بررسی امضای انتشار…" else "Checking signed release…"
            UpdatePhase.CURRENT -> if (isFa) "نسخهٔ جدیدتری در این کانال منتشر نشده است." else "No newer release in this channel."
            UpdatePhase.AVAILABLE -> if (isFa) "نسخهٔ ${state.release?.versionName} آماده است." else "Version ${state.release?.versionName} available."
            UpdatePhase.DOWNLOADING -> if (isFa) "دریافت بستهٔ به‌روزرسانی" else "Downloading update"
            UpdatePhase.READY -> if (isFa) "هش، شناسه و امضای بسته تأیید شد." else "APK checksum, identity and signing certificate verified."
            UpdatePhase.INSTALL_PERMISSION -> if (isFa) "پس از تأیید مجوز نصب، دوباره نصب را بزنید." else "After granting install permission, tap Install again."
            UpdatePhase.FAILED -> if (isFa) "به‌روزرسانی تأیید نشد. ${state.error}" else "Update could not be verified. ${state.error}"
        }
        Text(label, style = MaterialTheme.typography.bodySmall)
        state.release?.notes?.takeIf(String::isNotBlank)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        if (state.phase == UpdatePhase.DOWNLOADING) {
            LinearProgressIndicator(progress = { (state.downloadedBytes.toFloat() /
                (state.release?.sizeBytes ?: 1L)).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                state.busy -> TextButton(onClick = controller::cancel) { Text(if (isFa) "لغو" else "Cancel") }
                state.phase in listOf(UpdatePhase.READY, UpdatePhase.INSTALL_PERMISSION) -> Button(onClick = controller::install) {
                    Icon(Icons.Default.SystemUpdate, null); Spacer(Modifier.width(6.dp)); Text(if (isFa) "نصب" else "Install")
                }
                state.phase == UpdatePhase.AVAILABLE -> Button(onClick = controller::download) {
                    Icon(Icons.Default.Download, null); Spacer(Modifier.width(6.dp)); Text(if (isFa) "دریافت" else "Download")
                }
                else -> OutlinedButton(onClick = controller::check, enabled = state.phase != UpdatePhase.UNCONFIGURED) {
                    Icon(Icons.Default.Refresh, null); Spacer(Modifier.width(6.dp)); Text(if (isFa) "بررسی به‌روزرسانی" else "Check for Updates")
                }
            }
        }
    }
}
