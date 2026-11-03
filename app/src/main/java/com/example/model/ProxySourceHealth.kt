package com.example.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "proxy_source_health")
data class ProxySourceHealth(
    @PrimaryKey val sourceUrl: String,
    val sourceName: String,
    val lastAttemptAt: Long = 0L,
    val lastSuccessAt: Long = 0L,
    val totalFetches: Int = 0,
    val successfulFetches: Int = 0,
    val consecutiveFailures: Int = 0,
    val rawProxyCount: Int = 0,
    val uniqueProxyCount: Int = 0,
    val verifiedProxyCount: Int = 0,
    val duplicateRate: Float = 0f,
    val verifiedRate: Float = 0f,
    val averageDurationMs: Long = 0L,
    val quarantineUntil: Long = 0L,
    val lastError: String = "",
    val contentSignature: String = "",
    val mirrorGroupSize: Int = 1,
    val endpointDiversity: Int = 0
) {
    val isQuarantined: Boolean
        get() = quarantineUntil > System.currentTimeMillis()

    val hasSuccessfulLatestFetch: Boolean
        get() = lastAttemptAt > 0L && lastSuccessAt >= lastAttemptAt &&
            uniqueProxyCount > 0 && consecutiveFailures == 0 && lastError.isBlank() && !isQuarantined

    val reliabilityRate: Float
        get() = if (totalFetches > 0) {
            successfulFetches.toFloat().div(totalFetches.toFloat()).coerceIn(0f, 1f)
        } else {
            0.5f
        }

    val qualityScore: Int
        get() = SourceQualityPolicy.score(this)
}

object SourceQualityPolicy {
    fun score(health: ProxySourceHealth): Int {
        val reliability = health.reliabilityRate
        val uniqueness = (1f - health.duplicateRate.coerceIn(0f, 1f))
        val verified = health.verifiedRate.coerceIn(0f, 1f)
        val yield = (health.uniqueProxyCount.coerceAtLeast(0) / 100f).coerceIn(0f, 1f)
        val speed = when {
            health.averageDurationMs <= 0L -> 0.5f
            health.averageDurationMs <= 1_500L -> 1f
            health.averageDurationMs >= 12_000L -> 0f
            else -> 1f - (health.averageDurationMs - 1_500L) / 10_500f
        }
        val penalty = (health.consecutiveFailures.coerceAtLeast(0) * 8).coerceAtMost(40)
        val mirrorPenalty = ((health.mirrorGroupSize - 1).coerceAtLeast(0) * 4).coerceAtMost(20)
        val diversity = (health.endpointDiversity.coerceAtLeast(0) / 20f).coerceIn(0f, 1f)
        return (
            reliability * 28f +
                uniqueness * 18f +
                verified * 30f +
                yield * 10f +
                speed * 7f +
                diversity * 7f -
                penalty -
                mirrorPenalty
            ).toInt().coerceIn(0, 100)
    }

    fun quarantineDurationMs(consecutiveFailures: Int, producedNoUniqueProxies: Boolean): Long {
        val failures = consecutiveFailures.coerceAtLeast(0)
        if (failures < 3 && !producedNoUniqueProxies) return 0L
        val exponent = (failures - 3).coerceIn(0, 5)
        val base = if (producedNoUniqueProxies) EMPTY_SOURCE_QUARANTINE_MS else FAILURE_QUARANTINE_MS
        return (base shl exponent).coerceAtMost(MAX_QUARANTINE_MS)
    }

    private const val FAILURE_QUARANTINE_MS = 30L * 60L * 1_000L
    private const val EMPTY_SOURCE_QUARANTINE_MS = 60L * 60L * 1_000L
    private const val MAX_QUARANTINE_MS = 24L * 60L * 60L * 1_000L
}
