package com.example.viewmodel

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardFilterStateTest {
    @Test fun basicSortsWithNoFiltersRemainCompact() {
        listOf(SortBy.BEST, SortBy.DOWNLOAD, SortBy.UPLOAD).forEach {
            assertFalse(shouldShowAdvancedFilters("", StatusFilter.ALL, it))
        }
    }

    @Test fun aSavedSearchMustBeVisibleAfterReturningHome() {
        assertTrue(shouldShowAdvancedFilters("1.hide", StatusFilter.ALL, SortBy.BEST))
    }

    @Test fun aStatusFilterMustNotBeHiddenOnReturn() {
        StatusFilter.entries.filter { it != StatusFilter.ALL }.forEach {
            assertTrue(shouldShowAdvancedFilters("", it, SortBy.BEST))
        }
    }

    @Test fun advancedSortsRemainVisibleOnReturn() {
        SortBy.entries.filter { it !in setOf(SortBy.BEST, SortBy.DOWNLOAD, SortBy.UPLOAD) }.forEach {
            assertTrue(shouldShowAdvancedFilters("", StatusFilter.ALL, it))
        }
    }
}
