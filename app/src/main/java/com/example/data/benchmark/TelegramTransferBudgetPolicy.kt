package com.example.data.benchmark

object TelegramTransferBudgetPolicy {
    const val BYTES_PER_FINALIST = 34L * 1024L * 1024L

    fun allowedFinalistCount(totalCount: Int, budgetMb: Int): Int {
        if (totalCount <= 0) return 0
        if (budgetMb <= 0) return totalCount
        val budgetBytes = budgetMb.toLong() * 1024L * 1024L
        return (budgetBytes / BYTES_PER_FINALIST)
            .coerceAtMost(totalCount.toLong())
            .toInt()
    }

    fun normalizeBudgetMb(value: Int): Int {
        return value.takeIf { it in ALLOWED_BUDGETS_MB } ?: 0
    }

    val allowedBudgetsMb: List<Int> = listOf(0, 64, 128, 256, 512, 1024)

    private val ALLOWED_BUDGETS_MB = allowedBudgetsMb.toSet()
}
