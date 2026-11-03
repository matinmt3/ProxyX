package com.example.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.MtProtoProxy
import java.util.Locale

object UiUtils {
    fun getFlagEmoji(countryCode: String): String {
        if (countryCode.length != 2) return "🌐"
        val codeUpper = countryCode.uppercase(Locale.ROOT)
        if (codeUpper == "UN" || codeUpper.any { it !in 'A'..'Z' }) return "🌐"
        val firstChar = codeUpper[0].code - 'A'.code + 0x1F1E6
        val secondChar = codeUpper[1].code - 'A'.code + 0x1F1E6
        return String(Character.toChars(firstChar)) + String(Character.toChars(secondChar))
    }

}

internal fun isUsableProxy(proxy: MtProtoProxy): Boolean {
    return proxy.isTelegramVerified && proxy.latency > 0
}

internal fun isReachableProxy(proxy: MtProtoProxy): Boolean {
    return proxy.isTcpReachable
}

internal fun formatKbps(value: Int): String {
    return when {
        value <= 0 -> "---"
        value >= 1024 -> String.format(Locale.US, "%.2f MiB/s", value / 1024.0)
        else -> "$value KiB/s"
    }
}

@Composable
fun PanelCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            content = content
        )
    }
}

@Composable
fun LatencyDistributionChart(
    proxies: List<MtProtoProxy>,
    testedCount: Int,
    verifiedCount: Int,
    language: String,
    modifier: Modifier = Modifier
) {
    val workingProxies = proxies
        .filter(::isReachableProxy)
        .filter { it.latency <= MAX_DISPLAY_LATENCY_MS }
        .sortedBy { it.latency }
        .take(10)
    val primaryColor = MaterialTheme.colorScheme.primary
    val secondaryColor = MaterialTheme.colorScheme.secondary
    val isFa = isPersianLanguage(language)

    if (workingProxies.isEmpty()) {
        Box(
            modifier = modifier.background(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f),
                shape = RoundedCornerShape(8.dp)
            ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = when {
                    testedCount <= 0 && isFa -> "برای نمایش نمودار، تست را اجرا کنید"
                    testedCount <= 0 -> "Run a benchmark to generate the chart"
                    isFa -> "در این اجرا هیچ مسیر TCP پاسخ نداد"
                    else -> "No TCP route responded in this run"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium
            )
        }
        return
    }

    val maxLatency = workingProxies.maxOf { it.latency }.toFloat()
    val minLatency = workingProxies.minOf { it.latency }.toFloat()
    Column(modifier = modifier) {
        Text(
            text = if (isFa) {
                "تأخیر واقعی TCP (${workingProxies.size} مسیر، $verifiedCount تأیید تلگرام)"
            } else {
                "Measured TCP latency (${workingProxies.size} routes, $verifiedCount Telegram verified)"
            },
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(vertical = 8.dp)
        ) {
            val width = size.width
            val height = size.height

            val points = workingProxies.mapIndexed { index, proxy ->
                val x = if (workingProxies.size > 1) {
                    index.toFloat() / (workingProxies.size - 1) * width
                } else {
                    width / 2f
                }
                val normalizedVal = if (maxLatency > minLatency) {
                    (proxy.latency - minLatency) / (maxLatency - minLatency)
                } else {
                    0.5f
                }
                val y = normalizedVal * (height - 30f) + 15f
                Offset(x, y)
            }

            if (points.size > 1) {
                val fillPath = Path().apply {
                    moveTo(0f, height)
                    points.forEach { point -> lineTo(point.x, point.y) }
                    lineTo(width, height)
                    close()
                }

                drawPath(
                    path = fillPath,
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            primaryColor.copy(alpha = 0.4f),
                            primaryColor.copy(alpha = 0.0f)
                        )
                    )
                )

                val strokePath = Path().apply {
                    points.forEachIndexed { index, point ->
                        if (index == 0) {
                            moveTo(point.x, point.y)
                        } else {
                            lineTo(point.x, point.y)
                        }
                    }
                }

                drawPath(
                    path = strokePath,
                    color = primaryColor,
                    style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
                )
            } else {
                val y = points.single().y
                drawLine(
                    color = primaryColor.copy(alpha = 0.35f),
                    start = Offset(0f, y),
                    end = Offset(width, y),
                    strokeWidth = 1.dp.toPx()
                )
            }

            points.forEach { point ->
                drawCircle(
                    color = secondaryColor,
                    radius = 4.dp.toPx(),
                    center = point
                )
                drawCircle(
                    color = Color.White,
                    radius = 2.dp.toPx(),
                    center = point
                )
            }
        }
        
        if (workingProxies.size == 1) {
            Text(
                text = if (isFa) {
                    "${minLatency.toInt()}ms (تنها مسیر پاسخ‌گو)"
                } else {
                    "${minLatency.toInt()}ms (only responding route)"
                },
                modifier = Modifier.align(Alignment.CenterHorizontally),
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = if (isFa) "${minLatency.toInt()}ms (بهترین)" else "${minLatency.toInt()}ms (best)",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = if (isFa) "${maxLatency.toInt()}ms (بیشترین)" else "${maxLatency.toInt()}ms (highest)",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private const val MAX_DISPLAY_LATENCY_MS = 60_000L
