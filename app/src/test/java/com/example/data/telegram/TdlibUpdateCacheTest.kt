package com.example.data.telegram

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TdlibUpdateCacheTest {
    @Test fun prolongedUpdatesDoNotGrowTheCacheWithoutLimit() {
        var cache = emptyMap<Int, Int>()
        repeat(10_000) { cache = TdlibUpdateCache.remember(cache, it, it) }
        assertEquals(TdlibUpdateCache.MAX_ENTRIES, cache.size)
        assertEquals(9_999, cache[9_999])
        assertFalse(cache.containsKey(0))
    }

    @Test fun activeTransferIsRetainedWhileUnrelatedFilesArrive() {
        var cache = mapOf(0 to "active")
        for (id in 1..1_000) cache = TdlibUpdateCache.remember(cache, id, "other", protectedKey = 0)
        assertEquals(TdlibUpdateCache.MAX_ENTRIES, cache.size)
        assertEquals("active", cache[0])
        assertTrue(cache.containsKey(1_000))
    }

    @Test fun latestValueRefreshesEvictionOrder() {
        var cache = (0 until TdlibUpdateCache.MAX_ENTRIES).associateWith { "old" }
        cache = TdlibUpdateCache.remember(cache, 0, "new")
        cache = TdlibUpdateCache.remember(cache, 1_000, "latest")
        assertEquals("new", cache[0])
        assertFalse(cache.containsKey(1))
    }

    @Test fun updatingDoesNotMutateThePreviousFlowSnapshot() {
        val previous = mapOf(1 to "old")
        val updated = TdlibUpdateCache.remember(previous, 1, "new")
        assertEquals("old", previous[1])
        assertEquals("new", updated[1])
    }
}
