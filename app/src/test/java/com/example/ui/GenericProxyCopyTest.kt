package com.example.ui

import org.junit.Assert.*
import org.junit.Test

class GenericProxyCopyTest {
    @Test fun fetchedListsAreNotPresentedAsVerifiedProxies() {
        assertTrue(genericMessage("FETCHED", false).contains("not a proxy test"))
        assertNotEquals(genericMessage("WORKING", false), genericMessage("FETCHED", false))
        assertNotEquals(genericMessage("WORKING", true), genericMessage("UNTESTED", true))
    }
    @Test fun sourceProgressPreservesDownloadedCachedAndFailedCounts() {
        assertEquals("Fetched: 2, cached: 1, failed: 2", genericMessage("SOURCES:2:1:2", false))
        assertTrue(genericMessage("SOURCES:2:1:2", true).contains("ناموفق: 2"))
    }
    @Test fun cancelledOrOtherNetworkResultsCannotUseSuccessLabel() {
        listOf("CANCELLED", "OTHER_NETWORK", "INCONCLUSIVE", "TLS_FAILED", "AUTH_FAILED").forEach {
            assertNotEquals(genericMessage("WORKING", false), genericMessage(it, false))
            assertNotEquals(it, genericMessage(it, true))
        }
    }
}
