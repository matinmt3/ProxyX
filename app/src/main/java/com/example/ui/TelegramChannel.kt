package com.example.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri

const val TELEGRAM_CHANNEL_URL = "https://t.me/SymexVPN"
private const val TELEGRAM_CHANNEL_DEEP_LINK = "tg://resolve?domain=SymexVPN"

fun openTelegramChannel(context: Context) {
    val telegramOpened = runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, TELEGRAM_CHANNEL_DEEP_LINK.toUri()).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }.isSuccess

    if (!telegramOpened) {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, TELEGRAM_CHANNEL_URL.toUri()).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        }
    }
}

@Composable
fun TelegramChannelDialog(
    language: String,
    onJoin: () -> Unit,
    onDismiss: () -> Unit
) {
    val isFa = isPersianLanguage(language)
    val title = if (isFa) "همراه ProxyX باشید" else "Stay connected with ProxyX"
    val message = if (isFa) {
        "برای دریافت به‌روزرسانی‌ها، اطلاعیه‌ها و آموزش استفاده از برنامه، به کانال تلگرام SymexVPN بپیوندید."
    } else {
        "Join the SymexVPN Telegram channel for app updates, announcements, and usage guides."
    }
    val join = if (isFa) "عضویت در کانال" else "Join channel"
    val later = if (isFa) "بعداً" else "Later"

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Campaign,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp)
                )
            }
        },
        title = {
            Text(
                text = title,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "@SymexVPN",
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onJoin) {
                Text(join, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(later)
            }
        }
    )
}
