package com.example.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BenchmarkInputTest {
    @Test
    fun normalizesPersianAndArabicDigitsForTheBenchmarkField() {
        assertEquals("120", normalizeBenchmarkDigits("۱۲0"))
        assertEquals("45", normalizeBenchmarkDigits("٤٥"))
        assertEquals("", normalizeBenchmarkDigits("abc -"))
    }

    @Test
    fun acceptsCountsAboveTheOldOneThousandProxyLimit() {
        assertEquals(1_001, parseBenchmarkCount("1001"))
        assertEquals(5_000, parseBenchmarkCount("۵۰۰۰"))
    }

    @Test
    fun clampsOnlyAtTheIntegerStorageBoundary() {
        assertEquals(Int.MAX_VALUE, parseBenchmarkCount("2147483648"))
        assertEquals(Int.MAX_VALUE, parseBenchmarkCount("9999999999"))
    }

    @Test
    fun rejectsEmptyAndNonPositiveCounts() {
        assertNull(parseBenchmarkCount(""))
        assertNull(parseBenchmarkCount("0"))
        assertNull(parseBenchmarkCount("abc"))
    }
}
