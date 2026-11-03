package com.example.data.telegram

import com.example.data.network.UserNetworkChangedException
import com.example.model.MtProtoProxy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class FairTelegramTransferRunnerTest {
    private fun proxy(name: String) = MtProtoProxy(name, "1.1.1.1", 443, "00".repeat(16),
        "Unknown", "UN", true, 100, 1f, 1f)
    private fun sample(proxy: MtProtoProxy, bytes: Int, rate: Int = 1000, network: String = "net") =
        TelegramTransferResult(proxy.url, rate, rate, bytes.toLong(), 1000, 1000, 1000,
            network, "WIFI")

    @Test fun roundRobinUsesEqualSamplesAndPersistsOnlyCompleteResults() = runTest {
        val a = proxy("a"); val b = proxy("b")
        val calls = mutableListOf<Pair<String, Int>>()
        val saved = mutableListOf<TelegramTransferResult>()
        val result = FairTelegramTransferRunner().run(listOf(a, b), 2 * 1024 * 1024, 0, "net",
            ready = { true }, checkpoint = {}, measure = { p, size ->
                calls += p.url to size; sample(p, size)
            }, persist = { saved += it }, progress = {})
        assertEquals(listOf("a", "b", "b", "a", "b", "a"), calls.map { it.first })
        assertEquals(2, result.succeeded)
        assertEquals(3, saved.first().rounds)
        assertEquals(1000, saved.first().downloadFloorKbps)
        assertEquals(18L * 1024 * 1024, result.reservedPayloadBytes)
    }

    @Test fun unauthenticatedAndInsufficientBudgetDoNotSendAnyFiles() = runTest {
        for ((ready, budget) in listOf(false to 0, true to 1)) {
            val result = FairTelegramTransferRunner().run(listOf(proxy("a")), 2 * 1024 * 1024,
                budget, "net", ready = { ready }, checkpoint = {},
                measure = { _, _ -> error("Must not transfer") }, persist = {}, progress = {})
            assertEquals(0, result.attempted)
            assertEquals(1, result.skipped)
        }
    }

    @Test fun failuresDoNotStopOtherFinalistsOrPublishPartialSuccess() = runTest {
        val saved = mutableListOf<String>()
        val failed = mutableListOf<String>()
        val result = FairTelegramTransferRunner().run(listOf(proxy("a"), proxy("b")),
            2 * 1024 * 1024, 0, "net", ready = { true }, checkpoint = {},
            measure = { p, bytes -> if (p.url == "a") error("broken route") else sample(p, bytes) },
            persist = { saved += it.proxyUrl }, progress = {}, onFailure = { failed += it.url })
        assertEquals(listOf("b"), saved)
        assertEquals(1, result.failed)
        assertEquals(1, result.succeeded)
        assertEquals(listOf("a"), failed)
    }

    @Test fun cancellationAndNetworkChangePropagate() = runTest {
        var caught = false
        try {
            FairTelegramTransferRunner().run(listOf(proxy("a")), 2 * 1024 * 1024, 0, "net",
                ready = { true }, checkpoint = { throw CancellationException() },
                measure = { p, n -> sample(p, n) }, persist = { fail("No partial result") }, progress = {})
        } catch (_: CancellationException) { caught = true }
        assertTrue(caught)
        caught = false
        try {
            FairTelegramTransferRunner().run(listOf(proxy("a")), 2 * 1024 * 1024, 0, "net",
                ready = { true }, checkpoint = {},
                measure = { p, n -> sample(p, n, network = "other") },
                persist = { fail("No mixed-network result") }, progress = {})
        } catch (_: UserNetworkChangedException) { caught = true }
        assertTrue(caught)
    }

    @Test fun probeBurstDoesNotDetermineTypicalOrFloorSpeed() {
        val p = proxy("a")
        val result = TransferRoundStatistics.summarize(listOf(
            sample(p, 512 * 1024, 10_000), sample(p, 2 * 1024 * 1024, 1500),
            sample(p, 2 * 1024 * 1024, 500)))
        assertEquals(1000, result.downloadKbps)
        assertEquals(500, result.downloadFloorKbps)
        assertEquals(1500, result.downloadPeakKbps)
        assertTrue(result.confidence < 90)
    }

    @Test fun slowButSuccessfulCandidatesReceiveEveryRound() = runTest {
        val calls = mutableListOf<String>()
        val saved = mutableListOf<TelegramTransferResult>()
        val result = FairTelegramTransferRunner().run(listOf(proxy("fast"), proxy("middle"), proxy("slow")),
            2 * 1024 * 1024, 0, "net", ready = { true }, checkpoint = {},
            measure = { p, bytes ->
                calls += p.url
                sample(p, bytes, if (p.url == "slow") 10 else 2000)
            }, persist = { saved += it }, progress = {})
        assertEquals(3, result.succeeded)
        assertEquals(0, result.skipped)
        assertEquals(3, calls.count { it == "slow" })
        assertTrue(saved.all { it.rounds == 3 })
    }

    @Test fun budgetAndAuthorizationSkipsAreReportedSeparately() = runTest {
        val result = FairTelegramTransferRunner().run(listOf(proxy("a"), proxy("b")),
            2 * 1024 * 1024, 9, "net", ready = { false }, checkpoint = {},
            measure = { _, _ -> error("Must not transfer") }, persist = {}, progress = {})
        assertEquals(2, result.skipped)
        assertEquals(1, result.budgetSkipped)
        assertEquals(1, result.authorizationSkipped)
    }

    @Test fun zeroRateCannotBePersistedAsASuccessfulTransfer() = runTest {
        val result = FairTelegramTransferRunner().run(listOf(proxy("a")),
            2 * 1024 * 1024, 0, "net", ready = { true }, checkpoint = {},
            measure = { p, bytes -> sample(p, bytes, 0) },
            persist = { fail("Invalid transfer was saved") }, progress = {})
        assertEquals(1, result.failed)
        assertEquals(0, result.succeeded)
    }
}
