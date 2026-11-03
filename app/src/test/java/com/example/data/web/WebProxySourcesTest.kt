package com.example.data.web

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WebProxySourcesTest {
    @Test fun starterLinksAreValidDistinctAndNeverClaimToBeVerified() {
        val nodes = WebProxySources.bootstrap()
        assertEquals(7, nodes.size)
        assertEquals(7, nodes.distinctBy { it.url }.size)
        nodes.forEach {
            assertEquals("UNTESTED", it.status)
            assertEquals(0, it.stage)
            assertEquals(0L, it.testedAt)
            assertFalse(it.hasRealTransfer)
            assertEquals("PUBLIC_BOOTSTRAP", it.source)
        }
    }
    @Test fun sourcesUseHttpsWithoutCredentialOrQueryInjection() {
        assertEquals(3, WebProxySources.feeds.size)
        assertTrue(WebProxySources.feeds.all { it.matches(Regex("https://t\\.me/s/[a-zA-Z0-9_]+")) })
    }
}
