package com.example.model

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "proxy_source_membership",
    primaryKeys = ["sourceUrl", "proxyUrl"],
    indices = [Index(value = ["proxyUrl"])]
)
data class ProxySourceMembership(
    val sourceUrl: String,
    val proxyUrl: String
)

data class SourceVerificationYield(
    val sourceUrl: String,
    val testedCount: Int,
    val verifiedCount: Int
) {
    val verifiedRate: Float
        get() = if (testedCount > 0) {
            verifiedCount.toFloat().div(testedCount.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }
}
