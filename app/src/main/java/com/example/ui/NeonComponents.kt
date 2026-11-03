package com.example.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

@Composable
internal fun NeonTelegramAction(
    enabled: Boolean,
    label: String,
    onClick: () -> Unit,
    compact: Boolean = false
) {
    val colors = MaterialTheme.colorScheme
    val ring = remember(colors.primary, colors.secondary, enabled) {
        Brush.sweepGradient(
            if (enabled) listOf(colors.primary, colors.primary, colors.secondary, colors.primary)
            else listOf(colors.outline, colors.outline)
        )
    }
    Box(
        modifier = Modifier
            .size(if (compact) 96.dp else 128.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label }
            .testTag("telegram-open"),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val radius = size.minDimension / 2f - 9.dp.toPx()
            drawCircle(colors.surface, radius)
            drawCircle(ring, radius, alpha = 0.12f, style = Stroke(18.dp.toPx()))
            drawCircle(ring, radius, style = Stroke(6.dp.toPx()))
            drawCircle(colors.primary.copy(alpha = 0.25f), radius + 7.dp.toPx(),
                style = Stroke(1.dp.toPx()))
        }
        Icon(
            Icons.AutoMirrored.Outlined.Send,
            contentDescription = null,
            tint = if (enabled) colors.primary else colors.outline,
            modifier = Modifier.size(if (compact) 36.dp else 46.dp)
        )
    }
}

@Composable
internal fun NeonLatencyGauge(
    latencyMs: Long?,
    isFa: Boolean,
    modifier: Modifier = Modifier,
    compact: Boolean = false
) {
    val colors = MaterialTheme.colorScheme
    val sideLabels = useSideGaugeLabels(compact, LocalDensity.current.fontScale)
    val diameterDp = if (sideLabels) 96.dp else if (compact) 140.dp else 176.dp
    val gradient = remember(colors.primary, colors.secondary) {
        Brush.sweepGradient(listOf(colors.secondary, colors.primary, colors.primary, colors.secondary))
    }
    Row(modifier.testTag("protocol-latency-gauge"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (sideLabels) 16.dp else 0.dp)) {
    Box(
        modifier = Modifier.size(diameterDp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val inset = 12.dp.toPx()
            val diameter = size.minDimension - 2 * inset
            val center = Offset(size.width / 2, size.height / 2)
            drawArc(gradient, 150f, 240f, false, Offset(inset, inset),
                Size(diameter, diameter), alpha = if (latencyMs == null) 0.35f else 1f,
                style = Stroke(7.dp.toPx(), cap = StrokeCap.Round))
            // Compact ticks stay outside the arc, leaving the caption unobstructed.
            val radius = diameter / 2 + if (compact) 11.dp.toPx() else -10.dp.toPx()
            repeat(33) { tick ->
                val angle = Math.toRadians((150 + tick * 7.5).toDouble())
                val inner = radius - if (tick % 4 == 0) 7.dp.toPx() else 3.dp.toPx()
                drawLine(
                    colors.outline.copy(alpha = 0.7f),
                    Offset(center.x + cos(angle).toFloat() * inner,
                        center.y + sin(angle).toFloat() * inner),
                    Offset(center.x + cos(angle).toFloat() * radius,
                        center.y + sin(angle).toFloat() * radius),
                    strokeWidth = 1.dp.toPx()
                )
            }
        }
        if (sideLabels) {
            Text(latencyMs?.toString() ?: "\u2014",
                style = endpointStyle(MaterialTheme.typography.headlineSmall),
                fontWeight = FontWeight.Bold, color = colors.onSurface)
        } else Column(horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.width(if (compact) 110.dp else 130.dp)) {
            Text(if (isFa) "پینگ MTProto" else "MTProto latency",
                style = MaterialTheme.typography.labelMedium,
                color = colors.onSurfaceVariant, maxLines = 2,
                textAlign = TextAlign.Center)
            Text(latencyMs?.toString() ?: "—",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold, color = colors.onSurface)
            Text(if (latencyMs == null) {
                if (isFa) "اندازه‌گیری نشده" else "Not measured"
            } else if (isFa) "میلی‌ثانیه" else "milliseconds",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant, textAlign = TextAlign.Center)
        }
    }
    if (sideLabels) {
        Column(Modifier.widthIn(max = 180.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(if (isFa) "\u067e\u06cc\u0646\u06af MTProto" else "MTProto latency",
                style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
            Text(if (latencyMs == null) {
                if (isFa) "\u0627\u0646\u062f\u0627\u0632\u0647\u200c\u06af\u06cc\u0631\u06cc \u0646\u0634\u062f\u0647" else "Not measured"
            } else if (isFa) "\u0645\u06cc\u0644\u06cc\u200c\u062b\u0627\u0646\u06cc\u0647" else "milliseconds",
                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
    }
    }
}

@Composable
internal fun NeonStageStrip(stages: List<StageAppearance>, isFa: Boolean) {
    val labels = if (isFa) listOf("بررسی اولیه", "تست دقیق", "پایداری", "انتقال")
        else listOf("Screening", "Protocol", "Stability", "Transfer")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        stages.forEachIndexed { index, state ->
            val color = when (state) {
                StageAppearance.COMPLETE -> MaterialTheme.colorScheme.primary
                StageAppearance.ACTIVE, StageAppearance.PAUSED -> MaterialTheme.colorScheme.secondary
                StageAppearance.STOPPED -> MaterialTheme.colorScheme.error
                StageAppearance.PENDING, StageAppearance.SKIPPED -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(32.dp).clip(CircleShape).background(color.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center) {
                    when (state) {
                        StageAppearance.COMPLETE -> Icon(Icons.Default.Check, null,
                            tint = color, modifier = Modifier.size(18.dp))
                        StageAppearance.ACTIVE -> Icon(Icons.Default.MoreHoriz, null,
                            tint = color, modifier = Modifier.size(20.dp))
                        StageAppearance.PAUSED -> Icon(Icons.Default.Pause, null,
                            tint = color, modifier = Modifier.size(18.dp))
                        StageAppearance.STOPPED -> Icon(Icons.Default.Close, null,
                            tint = color, modifier = Modifier.size(18.dp))
                        StageAppearance.PENDING, StageAppearance.SKIPPED -> Text((index + 1).toString(),
                            style = MaterialTheme.typography.labelMedium, color = color)
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(labels[index], style = MaterialTheme.typography.labelMedium,
                    color = color, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center)
                Text(when (state) {
                    StageAppearance.COMPLETE -> if (isFa) "انجام شد" else "Done"
                    StageAppearance.ACTIVE -> if (isFa) "در جریان" else "Active"
                    StageAppearance.PAUSED -> if (isFa) "مکث" else "Paused"
                    StageAppearance.STOPPED -> if (isFa) "متوقف" else "Stopped"
                    StageAppearance.PENDING -> if (isFa) "در انتظار" else "Pending"
                    StageAppearance.SKIPPED -> if (isFa) "اجرا نشد" else "Not run"
                }, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
