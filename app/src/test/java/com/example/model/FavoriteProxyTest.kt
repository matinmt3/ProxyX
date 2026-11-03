package com.example.model

import org.junit.Assert.assertEquals
import org.junit.Test

class FavoriteProxyTest {
    @Test fun favoriteUsesTheActualNativeTransferTimestamp() {
        val favorite = from(measured())
        assertEquals(200L, favorite.lastKnownGoodAt)
        assertEquals(2500, favorite.lastKnownDownloadKbps)
        assertEquals(1500, favorite.lastKnownUploadKbps)
    }

    @Test fun incompleteStaleOrFailedRatesNeverReplaceFavoriteHistory() {
        val previous = from(measured())
        listOf(measured().copy(telegramTransferRounds = 1),
            measured().copy(telegramTransferMeasuredAt = 50),
            measured().copy(benchmarkFailureReason = "CONTINUITY_LOST"),
            measured().copy(isWorking = false)).forEach {
            val invalid = it.copy(telegramDownloadKbps = 9999, telegramUploadKbps = 9999)
            val favorite = from(invalid, previous)
            assertEquals(200L, favorite.lastKnownGoodAt)
            assertEquals(2500, favorite.lastKnownDownloadKbps)
            assertEquals(1500, favorite.lastKnownUploadKbps)
        }
    }

    @Test fun aNewFavoriteWithoutUsableNativeEvidenceDoesNotInventRates() {
        val favorite = from(measured().copy(telegramTransferRounds = 1))
        assertEquals(100L, favorite.lastKnownGoodAt)
        assertEquals(0, favorite.lastKnownDownloadKbps)
        assertEquals(0, favorite.lastKnownUploadKbps)
    }

    private fun from(proxy: MtProtoProxy, previous: FavoriteProxy? = null) =
        FavoriteProxy.fromProxy(proxy, previous, 300, FavoriteProxy.SYNC_LOCAL_ONLY)

    private fun measured() = MtProtoProxy("route", "1.1.1.1", 443,
        "0123456789abcdef0123456789abcdef", "Unknown", "XX", true, 100, 1f, 1f,
        lastChecked = 100, protocolLatency = 120, protocolSuccessRate = 1f,
        verificationStatus = MtProtoProxy.VERIFICATION_MTPROTO_VERIFIED,
        telegramTransferMeasuredAt = 200, telegramTransferRounds = 3,
        telegramDownloadKbps = 2500, telegramUploadKbps = 1500)
}
