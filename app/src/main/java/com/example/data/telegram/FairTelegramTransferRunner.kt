package com.example.data.telegram

import com.example.data.network.UserNetworkChangedException
import com.example.data.network.UserNetworkException
import com.example.model.MtProtoProxy
import kotlinx.coroutines.CancellationException

data class FairTransferProgress(
    val total: Int, val completed: Int, val succeeded: Int, val failed: Int,
    val completedSamples: Int, val totalSamples: Int, val round: Int,
    val proxy: MtProtoProxy? = null
)

data class FairTransferSummary(
    val attempted: Int, val succeeded: Int, val failed: Int, val skipped: Int,
    val reservedPayloadBytes: Long,
    val budgetSkipped: Int = 0,
    val authorizationSkipped: Int = 0
)

/** No concurrent bandwidth tests. Each candidate receives equal-sized confirmation rounds. */
class FairTelegramTransferRunner {
    suspend fun run(
        finalists: List<MtProtoProxy>,
        confirmationBytes: Int,
        budgetMb: Int,
        networkFingerprint: String,
        ready: () -> Boolean,
        checkpoint: suspend () -> Unit,
        measure: suspend (MtProtoProxy, Int) -> TelegramTransferResult,
        persist: suspend (TelegramTransferResult) -> Unit,
        progress: suspend (FairTransferProgress) -> Unit,
        onFailure: suspend (MtProtoProxy) -> Unit = {}
    ): FairTransferSummary {
        require(networkFingerprint.isNotBlank()) { "No current network for the transfer benchmark." }
        val sizes = sampleSizes(confirmationBytes)
        val cost = sizes.sumOf { it.toLong() * 2L }
        val unique = finalists.distinctBy { it.url }
        val count = if (budgetMb <= 0) unique.size else
            minOf(unique.size.toLong(), budgetMb.toLong() * 1024L * 1024L / cost).toInt()
        val selected = unique.take(count)
        val samples = selected.associate { it.url to mutableListOf<TelegramTransferResult>() }
        val attempted = mutableSetOf<String>()
        val failed = mutableSetOf<String>()
        val skipped = mutableSetOf<String>()
        val succeeded = mutableSetOf<String>()
        var completedSamples = 0
        var reserved = 0L
        suspend fun report(round: Int, proxy: MtProtoProxy? = null) = progress(
            FairTransferProgress(count, succeeded.size + failed.size + skipped.size,
                succeeded.size, failed.size, completedSamples, count * sizes.size, round, proxy)
        )
        for ((round, bytes) in sizes.withIndex()) {
            val ordered = when (round) {
                1 -> selected.reversed()
                2 -> selected.drop(count / 2) + selected.take(count / 2)
                else -> selected
            }
            for (proxy in ordered) {
                if (proxy.url in failed || proxy.url in skipped) continue
                checkpoint()
                if (!ready()) {
                    skipped += selected.map { it.url }.filter { it !in failed && it !in succeeded }
                    report(round + 1)
                    return FairTransferSummary(attempted.size, succeeded.size, failed.size,
                        unique.size - count + skipped.size, reserved,
                        budgetSkipped = unique.size - count, authorizationSkipped = skipped.size)
                }
                attempted += proxy.url
                report(round + 1, proxy)
                // Reserve both directions even if a request fails part-way; do not overspend later.
                reserved += bytes.toLong() * 2L
                try {
                    val result = measure(proxy, bytes)
                    if (result.networkFingerprint != networkFingerprint) throw UserNetworkChangedException()
                    require(result.sampleBytes == bytes.toLong() && result.proxyUrl == proxy.url)
                    require(result.downloadKbps > 0 && result.uploadKbps > 0 &&
                        result.downloadDurationMs > 0L && result.uploadDurationMs > 0L) {
                        "Incomplete Telegram transfer measurement."
                    }
                    samples.getValue(proxy.url) += result
                    if (round == sizes.lastIndex) {
                        persist(TransferRoundStatistics.summarize(samples.getValue(proxy.url)))
                        succeeded += proxy.url
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: UserNetworkException) {
                    throw error
                } catch (_: Exception) {
                    failed += proxy.url
                    onFailure(proxy)
                    completedSamples += sizes.lastIndex - round
                }
                completedSamples++
                report(round + 1)
            }
        }
        report(sizes.size)
        return FairTransferSummary(attempted.size, succeeded.size, failed.size,
            unique.size - count + skipped.size, reserved, budgetSkipped = unique.size - count)
    }

    companion object {
        fun sampleSizes(confirmationBytes: Int): List<Int> = listOf(
            TelegramAdaptiveTransferPolicy.PROBE_BYTES,
            confirmationBytes.coerceIn(TelegramAdaptiveTransferPolicy.MIN_CONFIRMATION_BYTES,
                TelegramAdaptiveTransferPolicy.MAX_CONFIRMATION_BYTES),
            confirmationBytes.coerceIn(TelegramAdaptiveTransferPolicy.MIN_CONFIRMATION_BYTES,
                TelegramAdaptiveTransferPolicy.MAX_CONFIRMATION_BYTES)
        )
    }
}
