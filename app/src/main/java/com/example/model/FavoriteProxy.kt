package com.example.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "favorite_proxies",
    indices = [Index(value = ["isActive"]), Index(value = ["updatedAt"])]
)
data class FavoriteProxy(
    @PrimaryKey val url: String,
    val server: String,
    val port: Int,
    val secret: String,
    val country: String,
    val countryCode: String,
    val isActive: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    val savedMessageId: Long = 0L,
    val syncState: String = SYNC_LOCAL_ONLY,
    val lastSyncError: String = "",
    val folder: String = DEFAULT_FOLDER,
    val tags: String = "",
    val note: String = "",
    val lastKnownGoodAt: Long = 0L,
    val lastKnownTransferScore: Int = 0,
    val lastKnownLatency: Long = 0L,
    val lastKnownDownloadKbps: Int = 0,
    val lastKnownUploadKbps: Int = 0
) {
    fun asFreshProxy(): MtProtoProxy {
        return MtProtoProxy(
            url = url,
            server = server,
            port = port,
            secret = secret,
            country = country,
            countryCode = countryCode,
            isWorking = false,
            latency = 0L,
            successRate = 0f,
            stability = 0f,
            isFavorite = true
        )
    }

    companion object {
        const val DEFAULT_FOLDER = "Favorites"
        const val SYNC_LOCAL_ONLY = "LOCAL_ONLY"
        const val SYNC_PENDING = "PENDING"
        const val SYNC_SYNCED = "SYNCED"
        const val SYNC_FAILED = "FAILED"

        fun fromProxy(
            proxy: MtProtoProxy,
            previous: FavoriteProxy?,
            now: Long,
            syncState: String
        ): FavoriteProxy {
            val hasTransferMeasurement = TransferRankingEvidence.isUsableMeasurement(proxy)
            val checkedAt = if (hasTransferMeasurement) {
                maxOf(proxy.lastChecked, proxy.telegramTransferMeasuredAt)
            } else proxy.lastChecked
            return FavoriteProxy(
                url = proxy.url,
                server = proxy.server,
                port = proxy.port,
                secret = proxy.secret,
                country = proxy.country,
                countryCode = proxy.countryCode,
                isActive = true,
                createdAt = previous?.createdAt ?: now,
                updatedAt = now,
                savedMessageId = previous?.savedMessageId ?: 0L,
                syncState = syncState,
                lastSyncError = "",
                folder = previous?.folder ?: DEFAULT_FOLDER,
                tags = previous?.tags.orEmpty(),
                note = previous?.note.orEmpty(),
                lastKnownGoodAt = if (proxy.isTelegramVerified && proxy.lastChecked > 0L) {
                    maxOf(checkedAt, previous?.lastKnownGoodAt ?: 0L)
                } else {
                    previous?.lastKnownGoodAt ?: 0L
                },
                lastKnownTransferScore = if (proxy.isTelegramVerified) {
                    proxy.transferScore
                } else {
                    previous?.lastKnownTransferScore ?: 0
                },
                lastKnownLatency = if (proxy.isTelegramVerified) {
                    proxy.protocolLatency.takeIf { it > 0L } ?: proxy.latency
                } else {
                    previous?.lastKnownLatency ?: 0L
                },
                lastKnownDownloadKbps = proxy.telegramDownloadKbps.takeIf { hasTransferMeasurement }
                    ?: previous?.lastKnownDownloadKbps
                    ?: 0,
                lastKnownUploadKbps = proxy.telegramUploadKbps.takeIf { hasTransferMeasurement }
                    ?: previous?.lastKnownUploadKbps
                    ?: 0
            )
        }
    }
}
