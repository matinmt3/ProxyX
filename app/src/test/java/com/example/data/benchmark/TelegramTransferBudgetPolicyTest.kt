package com.example.data.benchmark

import org.junit.Assert.assertEquals
import org.junit.Test

class TelegramTransferBudgetPolicyTest {
    @Test
    fun unlimitedBudgetIncludesEverySuccessfulFinalist() {
        assertEquals(173, TelegramTransferBudgetPolicy.allowedFinalistCount(173, 0))
    }

    @Test
    fun finiteBudgetUsesWorstCaseAdaptiveTransferWithoutExceedingTotal() {
        assertEquals(1, TelegramTransferBudgetPolicy.allowedFinalistCount(100, 64))
        assertEquals(1, TelegramTransferBudgetPolicy.allowedFinalistCount(3, 64))
    }

    @Test
    fun unsupportedBudgetFallsBackToUnlimitedSetting() {
        assertEquals(0, TelegramTransferBudgetPolicy.normalizeBudgetMb(75))
        assertEquals(256, TelegramTransferBudgetPolicy.normalizeBudgetMb(256))
    }
}
