package com.example.data.benchmark

import com.example.data.network.LatencyStatisticsCalculator
import com.example.data.network.ProxyVerifier
import com.example.data.telegram.FairTelegramTransferRunner
import com.example.data.telegram.TelegramTransferResult
import com.example.model.MtProtoProxy
import com.example.ui.benchmarkOutcomeText
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** Deterministic pipeline regression fixtures, not live network measurements. */
class FourStageWorkflowTest {
    @Test fun protocolSuccessWithVariableLatencyReachesAllThreeNativeRounds() = runTest {
        val verifier = ProxyVerifier()
        val plan = BenchmarkTestPlans.forMode(BenchmarkAccuracyMode.BALANCED)
        val input = (1..20).map { proxy(it) }
        val screened = input.map { verifier.run { round(plan.screeningProfile).applyTo(it) } }
            .filter(ThreeStageBenchmarkPolicy::passesScreening)
        val strict = screened.map { verifier.run { round(plan.strictProfile).applyTo(it) } }
        val finalists = ThreeStageBenchmarkPolicy.selectFinalists(strict)
        assertEquals(input.size, finalists.size)
        val completed = finalists.map {
            verifier.aggregateFinalistResults(it, List(plan.finalistRounds) { round(plan.finalistProfile) })
        }.filter { ThreeStageBenchmarkPolicy.passesFinalist(it, plan.finalistRounds) }
        assertEquals(input.size, completed.size)
        val calls = mutableListOf<String>()
        val saved = mutableListOf<TelegramTransferResult>()
        val summary = FairTelegramTransferRunner().run(completed, 2 * 1024 * 1024, 0, "network",
            ready = { true }, checkpoint = {}, measure = { p, bytes ->
                calls += p.url
                TelegramTransferResult(p.url, 100, 200, bytes.toLong(), 2000, 1000,
                    System.currentTimeMillis(), "network", "WIFI")
            }, persist = { saved += it }, progress = {})
        assertEquals(input.size * 3, calls.size)
        assertEquals(input.size, saved.size)
        assertTrue(saved.all { it.rounds == 3 })
        val result = ThreeStageBenchmarkResult(20, 20, 20, 20, 100,
            successfulFinalists = completed).withTelegramTransferSummary(summary)
        assertEquals(TelegramTransferStageStatus.COMPLETED, result.telegramTransferStatus)
        assertEquals(20, result.telegramTransferSucceededCount)
        assertTrue(benchmarkOutcomeText(result, false).contains("Stage 4: 20 tested, 20 passed"))
    }

    @Test fun authorizationAndBudgetFailuresAreNotReportedAsCompletedTransfer() {
        val base = ThreeStageBenchmarkResult(10, 10, 5, 3, 24)
        val budget = base.withTelegramTransferSummary(com.example.data.telegram.FairTransferSummary(
            0, 0, 0, 3, 0, budgetSkipped = 3))
        assertEquals(TelegramTransferStageStatus.BUDGET_EXHAUSTED, budget.telegramTransferStatus)
        assertTrue(benchmarkOutcomeText(budget, false).contains("budget could not cover"))
        val auth = base.withTelegramTransferSummary(com.example.data.telegram.FairTransferSummary(
            1, 0, 1, 2, 1024, authorizationSkipped = 2))
        assertEquals(TelegramTransferStageStatus.LOGIN_REQUIRED, auth.telegramTransferStatus)
        assertEquals(1, auth.telegramTransferFailedCount)
    }

    private fun proxy(id: Int) = MtProtoProxy("tg://fixture/$id", "fixture$id.example.com", 443,
        "00".repeat(16), "Global", "UN", false, 0, 0f, 0f)

    private fun round(profile: ProxyTestProfile): ProxyVerifier.VerificationResult {
        val tcpSamples = List(profile.tcpAttempts) { 100L + it * 50 }
        val protocolSamples = List(profile.dcIds.size) { 150L + it * 150 }
        val tcp = LatencyStatisticsCalculator.summarize(tcpSamples, tcpSamples.size)
        val protocol = LatencyStatisticsCalculator.summarize(protocolSamples, protocolSamples.size)
        return ProxyVerifier.VerificationResult(true, tcp.medianMs, 1f,
            minOf(tcp.stability, protocol.stability), 70, MtProtoProxy.VERIFICATION_MTPROTO_VERIFIED,
            80, protocol.medianMs, 1f, tcp.p90Ms, tcp.jitterMs, protocol.p90Ms, protocol.jitterMs,
            tcpSamples.size, tcpSamples.size, protocolSamples.size, protocolSamples.size,
            profile.stage.value, 1, 1f, 70, "network", "WIFI", tcpSamples, protocolSamples)
    }
}
