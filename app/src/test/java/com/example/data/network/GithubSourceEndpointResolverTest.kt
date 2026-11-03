package com.example.data.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GithubSourceEndpointResolverTest {
    @Test
    fun convertsStandardRawGithubUrlAndHonorsPreferredOrder() {
        val raw = "https://raw.githubusercontent.com/owner/repository/main/path/proxies.txt"
        val mirror = "https://cdn.jsdelivr.net/gh/owner/repository@main/path/proxies.txt"
        val media = "https://media.githubusercontent.com/media/owner/repository/main/path/proxies.txt"

        assertEquals(listOf(raw, mirror, media), GithubSourceEndpointResolver.candidates(raw, false))
        assertEquals(listOf(mirror, media, raw), GithubSourceEndpointResolver.candidates(raw, true))
    }

    @Test
    fun convertsExplicitRefsHeadsGithubUrl() {
        val raw =
            "https://raw.githubusercontent.com/owner/repository/refs/heads/master/data/list.json"

        assertEquals(
            "https://cdn.jsdelivr.net/gh/owner/repository@master/data/list.json",
            GithubSourceEndpointResolver.candidates(raw, true).first()
        )
    }

    @Test
    fun leavesIndependentHttpsFeedUntouched() {
        val source = "https://example.org/proxies.json"

        assertEquals(listOf(source), GithubSourceEndpointResolver.candidates(source, true))
        assertFalse(GithubSourceEndpointResolver.isMirror(source))
        assertTrue(
            GithubSourceEndpointResolver.isMirror(
                "https://cdn.jsdelivr.net/gh/owner/repository@main/proxies.txt"
            )
        )
        assertTrue(
            GithubSourceEndpointResolver.isMirror(
                "https://media.githubusercontent.com/media/owner/repository/main/proxies.txt"
            )
        )
    }
}
