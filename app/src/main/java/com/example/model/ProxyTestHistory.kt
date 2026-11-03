package com.example.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "proxy_test_history",
    indices = [
        Index(value = ["proxyUrl", "measuredAt"]),
        Index(value = ["networkFingerprint", "measuredAt"]),
        Index(value = ["measuredAt"])
    ]
)
data class ProxyTestHistory(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val proxyUrl: String,
    val measuredAt: Long,
    val benchmarkStage: Int,
    val networkFingerprint: String,
    val networkType: String,
    val verificationStatus: String,
    val failureReason: String,
    val latency: Long,
    val tcpP90Latency: Long,
    val tcpJitter: Long,
    val protocolLatency: Long,
    val protocolP90Latency: Long,
    val protocolJitter: Long,
    val successRate: Float,
    val protocolSuccessRate: Float,
    val stability: Float,
    val continuityRate: Float,
    val verificationConfidence: Int,
    val transferScore: Int,
    val telegramDownloadKbps: Int,
    val telegramUploadKbps: Int,
    val telegramTransferRounds: Int = 0,
    val telegramTransferConfidence: Int = 0,
    val telegramNetworkUploadBytes: Long = 0L,
    val telegramNetworkDownloadBytes: Long = 0L
) {
    companion object {
        fun fromProxy(proxy: MtProtoProxy, measuredAt: Long = proxy.lastChecked): ProxyTestHistory {
            return ProxyTestHistory(
                proxyUrl = proxy.url,
                measuredAt = measuredAt.takeIf { it > 0L } ?: System.currentTimeMillis(),
                benchmarkStage = proxy.benchmarkStage,
                networkFingerprint = proxy.networkFingerprint,
                networkType = proxy.testedNetworkType,
                verificationStatus = proxy.verificationStatus,
                failureReason = proxy.benchmarkFailureReason,
                latency = proxy.latency,
                tcpP90Latency = proxy.tcpP90Latency,
                tcpJitter = proxy.tcpJitter,
                protocolLatency = proxy.protocolLatency,
                protocolP90Latency = proxy.protocolP90Latency,
                protocolJitter = proxy.protocolJitter,
                successRate = proxy.successRate,
                protocolSuccessRate = proxy.protocolSuccessRate,
                stability = proxy.stability,
                continuityRate = proxy.continuityRate,
                verificationConfidence = proxy.verificationConfidence,
                transferScore = proxy.transferScore,
                telegramDownloadKbps = proxy.telegramDownloadKbps,
                telegramUploadKbps = proxy.telegramUploadKbps,
                telegramTransferRounds = proxy.telegramTransferRounds,
                telegramTransferConfidence = proxy.telegramTransferConfidence,
                telegramNetworkUploadBytes = proxy.telegramNetworkUploadBytes,
                telegramNetworkDownloadBytes = proxy.telegramNetworkDownloadBytes
            )
        }
    }
}
