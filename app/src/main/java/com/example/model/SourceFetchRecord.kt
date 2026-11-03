package com.example.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "source_fetch_history",
    indices = [
        Index(value = ["sourceUrl", "fetchedAt"]),
        Index(value = ["fetchedAt"]),
        Index(value = ["contentSignature", "fetchedAt"])
    ]
)
data class SourceFetchRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val sourceUrl: String,
    val fetchedAt: Long,
    val success: Boolean,
    val rawProxyCount: Int,
    val uniqueProxyCount: Int,
    val duplicateRate: Float,
    val durationMs: Long,
    val contentSignature: String,
    val endpointDiversity: Int,
    val networkType: String,
    val errorCode: String = ""
)

data class SourceWindowStats(
    val sourceUrl: String,
    val fetchCount: Int,
    val successfulFetchCount: Int,
    val totalUniqueProxies: Int,
    val averageUniqueProxies: Double,
    val averageDuplicateRate: Double,
    val averageDurationMs: Double,
    val lastFetchAt: Long
) {
    val successRate: Float
        get() = if (fetchCount > 0) {
            successfulFetchCount.toFloat() / fetchCount.toFloat()
        } else {
            0f
        }
}
