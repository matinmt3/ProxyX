package com.example.ui

import com.example.data.benchmark.TelegramTransferStageStatus
import com.example.data.benchmark.ThreeStageBenchmarkResult

internal fun benchmarkOutcomeText(result: ThreeStageBenchmarkResult, isFa: Boolean): String {
    val stages = if (isFa) {
        "مرحله ۱: ${result.screenedCount} تست؛ مرحله ۲: ${result.strictCount} تست؛ " +
            "مرحله ۳: ${result.finalistCount} تست، ${result.successfulFinalists.size} قبول."
    } else {
        "Stage 1: ${result.screenedCount} tested; stage 2: ${result.strictCount} tested; " +
            "stage 3: ${result.finalistCount} tested, ${result.successfulFinalists.size} passed."
    }
    val transfer = if (isFa) {
        "مرحله ۴: ${result.telegramTransferAttemptedCount} تست، " +
            "${result.telegramTransferSucceededCount} موفق، ${result.telegramTransferFailedCount} ناموفق، " +
            "${result.telegramTransferSkippedCount} اجرا نشده."
    } else {
        "Stage 4: ${result.telegramTransferAttemptedCount} tested, " +
            "${result.telegramTransferSucceededCount} passed, ${result.telegramTransferFailedCount} failed, " +
            "${result.telegramTransferSkippedCount} skipped."
    }
    val reason = when (result.telegramTransferStatus) {
        TelegramTransferStageStatus.NOT_REQUESTED -> if (isFa) "انتقال تلگرام خاموش بود." else "Telegram transfer was disabled."
        TelegramTransferStageStatus.NO_FINALISTS -> if (isFa) "هیچ پروکسی مرحله ۳ را قبول نشد." else "No proxy passed stage 3."
        TelegramTransferStageStatus.LOGIN_REQUIRED -> if (isFa) "نشست تلگرام آماده نبود." else "The Telegram session was not ready."
        TelegramTransferStageStatus.BUDGET_EXHAUSTED -> if (isFa) "بودجهٔ دیتا برای سه دور انتقال کافی نبود." else "The data budget could not cover three transfer rounds."
        TelegramTransferStageStatus.PARTIAL -> if (isFa) "برخی انتقال‌ها ناموفق یا اجرا نشده‌اند." else "Some transfers failed or were skipped."
        TelegramTransferStageStatus.COMPLETED -> if (isFa) "سه دور آپلود و دانلود واقعی تکمیل شد." else "Three real upload/download rounds completed."
    }
    val budget = if (result.telegramTransferBudgetSkippedCount > 0) {
        if (isFa) " محدودیت دیتا: ${result.telegramTransferBudgetSkippedCount}."
        else " Data-budget skips: ${result.telegramTransferBudgetSkippedCount}."
    } else ""
    return "$stages\n$transfer $reason$budget"
}
