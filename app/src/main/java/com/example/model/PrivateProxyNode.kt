package com.example.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "private_proxy_nodes",
    indices = [Index(value = ["enabled"]), Index(value = ["lastChecked"])]
)
data class PrivateProxyNode(
    @PrimaryKey val url: String,
    val label: String,
    val server: String,
    val port: Int,
    val secret: String,
    val enabled: Boolean = true,
    val createdAt: Long,
    val updatedAt: Long,
    val lastChecked: Long = 0L,
    val verificationStatus: String = MtProtoProxy.VERIFICATION_UNTESTED,
    val verificationConfidence: Int = 0,
    val latency: Long = 0L,
    val p90Latency: Long = 0L,
    val jitter: Long = 0L,
    val stability: Float = 0f,
    val transferScore: Int = 0,
    val networkFingerprint: String = "",
    val networkType: String = "",
    val lastError: String = ""
) {
    val isHealthy: Boolean get() = lastError.isBlank() && asProxy().isTelegramVerified

    fun asProxy(): MtProtoProxy = MtProtoProxy(
        url = url,
        server = server,
        port = port,
        secret = secret,
        country = MtProtoProxy.COUNTRY_UNKNOWN_NAME,
        countryCode = MtProtoProxy.COUNTRY_UNKNOWN_CODE,
        isWorking = verificationStatus != MtProtoProxy.VERIFICATION_OFFLINE && latency > 0L,
        latency = latency,
        successRate = if (lastChecked > 0L && latency > 0L) 1f else 0f,
        stability = stability,
        verificationStatus = verificationStatus,
        verificationConfidence = verificationConfidence,
        protocolLatency = latency,
        protocolSuccessRate = if (
            verificationStatus == MtProtoProxy.VERIFICATION_TELEGRAM_READY ||
            verificationStatus == MtProtoProxy.VERIFICATION_MTPROTO_VERIFIED
        ) 1f else 0f,
        protocolP90Latency = p90Latency,
        protocolJitter = jitter,
        transferScore = transferScore,
        networkFingerprint = networkFingerprint,
        testedNetworkType = networkType,
        lastChecked = lastChecked,
        benchmarkFailureReason = lastError
    )

    companion object {
        fun fromProxy(proxy: MtProtoProxy, label: String, now: Long): PrivateProxyNode {
            return PrivateProxyNode(
                url = proxy.url,
                label = label.trim().take(48).ifBlank { proxy.server },
                server = proxy.server,
                port = proxy.port,
                secret = proxy.secret,
                createdAt = now,
                updatedAt = now
            )
        }
    }
}
