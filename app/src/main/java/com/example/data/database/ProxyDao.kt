package com.example.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.paging.PagingSource
import com.example.model.BenchmarkRunRecord
import com.example.model.FavoriteProxy
import com.example.model.MtProtoProxy
import com.example.model.ProxyBenchmarkCounts
import com.example.model.ProxySourceMembership
import com.example.model.ProxyTestHistory
import com.example.model.PrivateProxyNode
import com.example.model.NetworkSpeedBaseline
import kotlinx.coroutines.flow.Flow

@Dao
interface ProxyDao {
    @Query("SELECT COUNT(*) FROM mtproto_proxies")
    suspend fun countStoredProxies(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertNetworkSpeedBaseline(baseline: NetworkSpeedBaseline)

    @Query(
        "SELECT * FROM network_speed_baselines WHERE networkFingerprint = :fingerprint LIMIT 1"
    )
    fun observeNetworkSpeedBaseline(fingerprint: String): Flow<NetworkSpeedBaseline?>

    @Query(
        "SELECT networkFingerprint FROM mtproto_proxies " +
            "WHERE lastChecked > 0 AND networkFingerprint != '' " +
            "ORDER BY lastChecked DESC LIMIT 1"
    )
    fun observeLatestBenchmarkNetworkFingerprint(): Flow<String?>

    @Query("DELETE FROM network_speed_baselines WHERE measuredAt < :cutoff")
    suspend fun deleteOldNetworkSpeedBaselines(cutoff: Long): Int

    @Query("SELECT COUNT(*) FROM mtproto_proxies")
    fun observeProxyCount(): Flow<Int>

    @Query(
        """
        SELECT COUNT(*) FROM mtproto_proxies AS p
        WHERE (:query = '' OR lower(p.server) LIKE '%' || lower(:query) || '%' OR
            lower(p.country) LIKE '%' || lower(:query) || '%' OR
            lower(p.countryCode) LIKE '%' || lower(:query) || '%' OR
            CAST(p.port AS TEXT) LIKE '%' || :query || '%')
          AND (
            :status = 'ALL' OR
            (:status = 'TELEGRAM_VERIFIED'
              AND p.verificationStatus IN ('TELEGRAM_READY', 'MTPROTO_VERIFIED')
              AND p.isWorking = 1 AND p.latency > 0 AND p.protocolLatency > 0
              AND p.protocolSuccessRate > 0) OR
            (:status = 'REACHABLE_ONLY' AND p.isWorking = 1 AND p.latency > 0)
          )
        """
    )
    fun observeFilteredProxyCount(query: String, status: String): Flow<Int>

    @Query(
        """
        SELECT
          COALESCE(SUM(CASE WHEN lastChecked > 0 THEN 1 ELSE 0 END), 0) AS testedCount,
          COALESCE(SUM(CASE WHEN lastChecked > 0 AND verificationStatus = 'TELEGRAM_READY'
            THEN 1 ELSE 0 END), 0) AS readyCount,
          COALESCE(SUM(CASE WHEN lastChecked > 0 AND verificationStatus IN
            ('TELEGRAM_READY', 'MTPROTO_VERIFIED') THEN 1 ELSE 0 END), 0) AS verifiedCount,
          COALESCE(SUM(CASE WHEN lastChecked > 0 AND verificationStatus = 'TCP_OPEN'
            THEN 1 ELSE 0 END), 0) AS tcpOnlyCount
        FROM mtproto_proxies
        WHERE lastChecked >= :since
        """
    )
    fun observeBenchmarkCounts(since: Long = 0L): Flow<ProxyBenchmarkCounts>

    @Query(
        """
        SELECT p.* FROM mtproto_proxies AS p
        WHERE lastChecked > 0 AND lastChecked >= :since
        ORDER BY
        """ + TransferRankingSql.PREVIEW_PREFIX + """
          CASE
            WHEN benchmarkStage >= 3 AND benchmarkRounds >= 3
              AND benchmarkFailureReason = ''
              AND verificationStatus IN ('TELEGRAM_READY', 'MTPROTO_VERIFIED')
              AND isWorking = 1 AND latency > 0 AND protocolLatency > 0
              AND protocolSuccessRate > 0 THEN 8
            WHEN benchmarkStage >= 2
              AND benchmarkFailureReason = ''
              AND verificationStatus IN ('TELEGRAM_READY', 'MTPROTO_VERIFIED')
              AND isWorking = 1 AND latency > 0 AND protocolLatency > 0
              AND protocolSuccessRate > 0 THEN 7
            WHEN verificationStatus = 'TELEGRAM_READY'
              AND benchmarkFailureReason = ''
              AND isWorking = 1 AND latency > 0 AND protocolLatency > 0
              AND protocolSuccessRate >= 1 AND successRate >= 0.9
              AND tcpAttempts >= 10 AND tcpSuccesses * 1.0 / tcpAttempts >= 0.9
              AND protocolAttempts >= 8 AND protocolConfirmations = protocolAttempts
              AND stability >= 0.82 AND tcpP90Latency > 0 AND protocolP90Latency > 0 THEN 6
            WHEN verificationStatus = 'TELEGRAM_READY'
              AND benchmarkFailureReason = ''
              AND isWorking = 1 AND latency > 0 AND protocolLatency > 0
              AND protocolSuccessRate > 0 AND tcpAttempts >= 10
              AND tcpSuccesses * 1.0 / tcpAttempts >= 0.9
              AND protocolAttempts >= 8 AND protocolConfirmations = protocolAttempts
              AND stability >= 0.82 AND tcpP90Latency > 0 AND protocolP90Latency > 0 THEN 4
            WHEN verificationStatus IN ('TELEGRAM_READY', 'MTPROTO_VERIFIED')
              AND isWorking = 1 AND latency > 0 AND protocolLatency > 0
              AND protocolSuccessRate > 0 THEN 3
            WHEN verificationStatus = 'TCP_OPEN' THEN 2
            WHEN verificationStatus IN ('INCONCLUSIVE', 'OFFLINE', 'TELEGRAM_READY', 'MTPROTO_VERIFIED') THEN 1
            ELSE 0
          END DESC,
          CASE WHEN :networkFingerprint != ''
            AND networkFingerprint = :networkFingerprint THEN 1 ELSE 0 END DESC,
          protocolSuccessRate DESC,
          stability DESC,
          successRate DESC,
          CASE WHEN transferScore > 0 THEN transferScore ELSE score END DESC,
          CASE WHEN protocolP90Latency > 0 THEN protocolP90Latency
            ELSE 9223372036854775807 END ASC,
          CASE WHEN protocolJitter >= 0 THEN protocolJitter
            ELSE 9223372036854775807 END ASC,
          CASE WHEN tcpP90Latency > 0 THEN tcpP90Latency
            ELSE 9223372036854775807 END ASC,
          CASE WHEN tcpJitter >= 0 THEN tcpJitter
            ELSE 9223372036854775807 END ASC,
          CASE WHEN protocolLatency > 0 THEN protocolLatency
            ELSE 9223372036854775807 END ASC,
          CASE WHEN latency > 0 THEN latency ELSE 9223372036854775807 END ASC,
          url ASC
        LIMIT :limit
        """
    )
    fun observeBenchmarkPreview(limit: Int, networkFingerprint: String, since: Long = 0L): Flow<List<MtProtoProxy>>

    @Query(
        """
        SELECT * FROM mtproto_proxies
        WHERE lastChecked > 0 AND lastChecked >= :since AND isWorking = 1 AND latency BETWEEN 1 AND :maxLatency
        ORDER BY latency ASC, url ASC
        LIMIT :limit
        """
    )
    fun observeBenchmarkChart(limit: Int, maxLatency: Long, since: Long = 0L): Flow<List<MtProtoProxy>>

    @Query(
        """
        WITH country_quality AS (
          SELECT countryCode,
            SUM(CASE WHEN lastChecked > 0 THEN 1 ELSE 0 END) AS testedCount,
            SUM(CASE WHEN lastChecked > 0
              AND verificationStatus IN ('TELEGRAM_READY', 'MTPROTO_VERIFIED')
              AND isWorking = 1 AND latency > 0 AND protocolLatency > 0
              AND protocolSuccessRate > 0 AND benchmarkFailureReason = '' THEN 1 ELSE 0 END) AS verifiedCount,
            AVG(CASE WHEN lastChecked > 0
              AND verificationStatus IN ('TELEGRAM_READY', 'MTPROTO_VERIFIED')
              AND isWorking = 1 AND latency > 0 AND protocolLatency > 0
              AND protocolSuccessRate > 0 AND benchmarkFailureReason = ''
              THEN CASE WHEN transferScore > 0 THEN transferScore ELSE score END END
            ) AS averageQuality,
            AVG(CASE WHEN lastChecked > 0
              AND verificationStatus IN ('TELEGRAM_READY', 'MTPROTO_VERIFIED')
              AND isWorking = 1 AND latency > 0 AND protocolLatency > 0
              AND protocolSuccessRate > 0 AND protocolP90Latency > 0
              AND benchmarkFailureReason = ''
              THEN protocolP90Latency END
            ) AS averageProtocolP90
          FROM mtproto_proxies
          WHERE :sort = 'COUNTRY' AND countryCode != 'UN' AND length(countryCode) = 2
          GROUP BY countryCode
        )
        SELECT p.* FROM mtproto_proxies AS p
        LEFT JOIN country_quality AS cq ON cq.countryCode = p.countryCode
        WHERE (:query = '' OR lower(p.server) LIKE '%' || lower(:query) || '%' OR
            lower(p.country) LIKE '%' || lower(:query) || '%' OR
            lower(p.countryCode) LIKE '%' || lower(:query) || '%' OR
            CAST(p.port AS TEXT) LIKE '%' || :query || '%')
          AND (
            :status = 'ALL' OR
            (:status = 'TELEGRAM_VERIFIED'
              AND p.verificationStatus IN ('TELEGRAM_READY', 'MTPROTO_VERIFIED')
              AND p.isWorking = 1 AND p.latency > 0 AND p.protocolLatency > 0
              AND p.protocolSuccessRate > 0) OR
            (:status = 'REACHABLE_ONLY' AND p.isWorking = 1 AND p.latency > 0)
          )
        ORDER BY
        """ + TransferRankingSql.PAGING_PREFIX + """
          CASE WHEN :sort IN ('DOWN_UP', 'BEST') THEN 1
            WHEN p.benchmarkFailureReason = '' THEN 1 ELSE 0 END DESC,
          CASE WHEN :sort IN ('DOWNLOAD', 'UPLOAD') AND p.isWorking = 1
            AND p.protocolSuccessRate > 0 AND p.protocolLatency > 0 AND p.latency > 0
            AND p.verificationStatus IN ('TELEGRAM_READY', 'MTPROTO_VERIFIED')
            AND p.benchmarkFailureReason = '' THEN 1 ELSE 0 END DESC,
          CASE WHEN :sort IN ('DOWNLOAD', 'UPLOAD') AND :currentNetworkFingerprint != ''
            AND p.networkFingerprint = :currentNetworkFingerprint THEN 1 ELSE 0 END DESC,
          CASE WHEN :sort = 'HIGH' THEN
            CASE
              WHEN p.benchmarkFailureReason = ''
                AND p.benchmarkStage >= 3 AND p.benchmarkRounds >= 3
                AND p.verificationStatus IN ('TELEGRAM_READY', 'MTPROTO_VERIFIED')
                AND p.isWorking = 1 AND p.protocolSuccessRate > 0 THEN 2
              WHEN p.lastChecked = 0 OR p.verificationStatus IN ('UNTESTED', 'INCONCLUSIVE') THEN 1
              ELSE 0
            END ELSE 0 END DESC,
          CASE WHEN :sort = 'HIGH' AND :currentNetworkFingerprint != ''
            AND p.networkFingerprint = :currentNetworkFingerprint THEN 1 ELSE 0 END DESC,
          CASE WHEN :sort = 'HIGH' THEN p.continuityRate ELSE 0 END DESC,
          CASE WHEN :sort = 'HIGH' THEN p.stability ELSE 0 END DESC,
          CASE WHEN :sort = 'HIGH' THEN
            CASE WHEN p.transferScore > 0 THEN p.transferScore ELSE p.score END
            ELSE 0 END DESC,
          CASE WHEN :sort = 'HIGH' AND p.protocolP90Latency > 0
            THEN p.protocolP90Latency ELSE 9223372036854775807 END ASC,

          CASE WHEN :sort = 'DOWN_UP' AND :currentNetworkFingerprint != ''
            AND p.networkFingerprint = :currentNetworkFingerprint THEN 1 ELSE 0 END DESC,
          CASE WHEN :sort = 'BEST' THEN
            CASE
              WHEN p.benchmarkStage >= 3 AND p.benchmarkRounds >= 3
                AND p.benchmarkFailureReason = ''
                AND p.verificationStatus IN ('TELEGRAM_READY', 'MTPROTO_VERIFIED')
                AND p.isWorking = 1 AND p.latency > 0 AND p.protocolLatency > 0
                AND p.protocolSuccessRate > 0 THEN 8
              WHEN p.benchmarkStage >= 2
                AND p.benchmarkFailureReason = ''
                AND p.verificationStatus IN ('TELEGRAM_READY', 'MTPROTO_VERIFIED')
                AND p.isWorking = 1 AND p.latency > 0 AND p.protocolLatency > 0
                AND p.protocolSuccessRate > 0 THEN 7
              WHEN p.verificationStatus = 'TELEGRAM_READY'
                AND p.benchmarkFailureReason = ''
                AND p.isWorking = 1 AND p.latency > 0 AND p.protocolLatency > 0
                AND p.protocolSuccessRate >= 1 AND p.successRate >= 0.9
                AND p.tcpAttempts >= 10
                AND p.tcpSuccesses * 1.0 / p.tcpAttempts >= 0.9
                AND p.protocolAttempts >= 8
                AND p.protocolConfirmations = p.protocolAttempts
                AND p.stability >= 0.82 AND p.tcpP90Latency > 0
                AND p.protocolP90Latency > 0 THEN 6
              WHEN p.verificationStatus = 'TELEGRAM_READY'
                AND p.benchmarkFailureReason = ''
                AND p.isWorking = 1 AND p.latency > 0 AND p.protocolLatency > 0
                AND p.protocolSuccessRate >= 1 AND p.tcpAttempts >= 10
                AND p.tcpSuccesses * 1.0 / p.tcpAttempts >= 0.9
                AND p.protocolAttempts >= 8
                AND p.protocolConfirmations = p.protocolAttempts
                AND p.stability >= 0.82 AND p.tcpP90Latency > 0
                AND p.protocolP90Latency > 0 THEN 4
              WHEN p.verificationStatus IN ('TELEGRAM_READY', 'MTPROTO_VERIFIED')
                AND p.isWorking = 1 AND p.latency > 0 AND p.protocolLatency > 0
                AND p.protocolSuccessRate > 0 THEN 3
              WHEN p.verificationStatus = 'TCP_OPEN' THEN 2
              WHEN p.lastChecked = 0 OR p.verificationStatus IN ('UNTESTED', 'INCONCLUSIVE') THEN 1
              ELSE 0
            END ELSE 0 END DESC,
          CASE WHEN :sort = 'BEST' AND :currentNetworkFingerprint != ''
            AND p.networkFingerprint = :currentNetworkFingerprint THEN 1 ELSE 0 END DESC,
          CASE WHEN :sort = 'BEST' THEN p.protocolSuccessRate ELSE 0 END DESC,
          CASE WHEN :sort = 'BEST' THEN p.stability ELSE 0 END DESC,
          CASE WHEN :sort = 'BEST' THEN p.successRate ELSE 0 END DESC,
          CASE WHEN :sort = 'BEST' THEN
            CASE WHEN p.transferScore > 0 THEN p.transferScore ELSE p.score END
            ELSE 0 END DESC,
          CASE WHEN :sort = 'BEST' AND p.protocolP90Latency > 0
            THEN p.protocolP90Latency ELSE 9223372036854775807 END ASC,
          CASE WHEN :sort = 'BEST' AND p.protocolJitter >= 0 THEN p.protocolJitter
            ELSE 9223372036854775807 END ASC,
          CASE WHEN :sort = 'BEST' AND p.tcpP90Latency > 0
            THEN p.tcpP90Latency ELSE 9223372036854775807 END ASC,
          CASE WHEN :sort = 'BEST' AND p.tcpJitter >= 0 THEN p.tcpJitter
            ELSE 9223372036854775807 END ASC,
          CASE WHEN :sort = 'BEST' AND p.protocolLatency > 0
            THEN p.protocolLatency ELSE 9223372036854775807 END ASC,
          CASE WHEN :sort = 'BEST' AND p.latency > 0
            THEN p.latency ELSE 9223372036854775807 END ASC,

          CASE WHEN :sort IN ('LATENCY', 'PROTOCOL', 'STABILITY') THEN
            CASE
              WHEN p.verificationStatus = 'TELEGRAM_READY'
                AND p.isWorking = 1 AND p.latency > 0 AND p.protocolLatency > 0
                AND p.protocolSuccessRate >= 1 AND p.tcpAttempts >= 10
                AND p.tcpSuccesses * 1.0 / p.tcpAttempts >= 0.9
                AND p.protocolAttempts >= 8
                AND p.protocolConfirmations = p.protocolAttempts
                AND p.stability >= 0.82 AND p.tcpP90Latency > 0
                AND p.protocolP90Latency > 0 THEN 4
              WHEN p.verificationStatus IN ('TELEGRAM_READY', 'MTPROTO_VERIFIED')
                AND p.isWorking = 1 AND p.latency > 0 AND p.protocolLatency > 0
                AND p.protocolSuccessRate > 0 THEN 3
              WHEN p.verificationStatus = 'TCP_OPEN' THEN 2
              WHEN p.lastChecked = 0 OR p.verificationStatus IN ('UNTESTED', 'INCONCLUSIVE') THEN 1
              ELSE 0
            END ELSE 0 END DESC,
          CASE WHEN :sort IN ('LATENCY', 'PROTOCOL', 'STABILITY')
            AND p.verificationStatus IN ('TELEGRAM_READY', 'MTPROTO_VERIFIED')
            AND p.isWorking = 1 AND p.latency > 0 AND p.protocolLatency > 0
            AND p.protocolSuccessRate > 0 THEN 1 ELSE 0 END DESC,
          CASE WHEN :sort IN ('LATENCY', 'PROTOCOL', 'STABILITY')
            AND :currentNetworkFingerprint != ''
            AND p.networkFingerprint = :currentNetworkFingerprint THEN 1 ELSE 0 END DESC,
          CASE WHEN :sort = 'LATENCY' AND p.latency > 0 THEN p.latency
            ELSE 9223372036854775807 END ASC,
          CASE WHEN :sort = 'LATENCY' AND p.protocolLatency > 0 THEN p.protocolLatency
            ELSE 9223372036854775807 END ASC,
          CASE WHEN :sort = 'LATENCY' THEN p.successRate ELSE 0 END DESC,
          CASE WHEN :sort = 'PROTOCOL' AND p.protocolLatency > 0 THEN p.protocolLatency
            ELSE 9223372036854775807 END ASC,
          CASE WHEN :sort = 'PROTOCOL' THEN p.protocolSuccessRate ELSE 0 END DESC,
          CASE WHEN :sort = 'PROTOCOL' AND p.latency > 0 THEN p.latency
            ELSE 9223372036854775807 END ASC,
          CASE WHEN :sort = 'STABILITY' THEN p.protocolSuccessRate ELSE 0 END DESC,
          CASE WHEN :sort = 'STABILITY' THEN p.successRate ELSE 0 END DESC,
          CASE WHEN :sort = 'STABILITY' THEN p.stability ELSE 0 END DESC,
          CASE WHEN :sort = 'STABILITY' THEN p.protocolConfirmations ELSE 0 END DESC,
          CASE WHEN :sort = 'STABILITY' AND p.protocolP90Latency > 0
            THEN p.protocolP90Latency ELSE 9223372036854775807 END ASC,
          CASE WHEN :sort = 'STABILITY' AND p.protocolJitter >= 0
            THEN p.protocolJitter ELSE 9223372036854775807 END ASC,
          CASE WHEN :sort = 'STABILITY' AND p.protocolLatency > 0
            THEN p.protocolLatency ELSE 9223372036854775807 END ASC,

          CASE WHEN :sort = 'COUNTRY' AND length(p.countryCode) = 2
            AND p.countryCode != 'UN' THEN 1 ELSE 0 END DESC,
          CASE WHEN :sort = 'COUNTRY' AND COALESCE(cq.verifiedCount, 0) > 0
            THEN 1 ELSE 0 END DESC,
          CASE WHEN :sort = 'COUNTRY' THEN
            COALESCE(cq.averageQuality, 0) *
              (0.6 + 0.08 * MIN(COALESCE(cq.verifiedCount, 0), 5))
            ELSE 0 END DESC,
          CASE WHEN :sort = 'COUNTRY' AND COALESCE(cq.testedCount, 0) > 0
            THEN COALESCE(cq.verifiedCount, 0) * 1.0 / cq.testedCount
            ELSE 0 END DESC,
          CASE WHEN :sort = 'COUNTRY' THEN
            COALESCE(cq.averageProtocolP90, 9223372036854775807)
            ELSE 0 END ASC,
          CASE WHEN :sort = 'COUNTRY' THEN COALESCE(cq.verifiedCount, 0)
            ELSE 0 END DESC,
          CASE WHEN :sort = 'COUNTRY' THEN lower(p.country) ELSE '' END ASC,
          CASE WHEN :sort = 'COUNTRY' THEN
            CASE
              WHEN p.verificationStatus = 'TELEGRAM_READY'
                AND p.isWorking = 1 AND p.latency > 0 AND p.protocolLatency > 0
                AND p.protocolSuccessRate >= 1 AND p.tcpAttempts >= 10
                AND p.tcpSuccesses * 1.0 / p.tcpAttempts >= 0.9
                AND p.protocolAttempts >= 8
                AND p.protocolConfirmations = p.protocolAttempts
                AND p.stability >= 0.82 AND p.tcpP90Latency > 0
                AND p.protocolP90Latency > 0 THEN 4
              WHEN p.verificationStatus IN ('TELEGRAM_READY', 'MTPROTO_VERIFIED')
                AND p.isWorking = 1 AND p.latency > 0 AND p.protocolLatency > 0
                AND p.protocolSuccessRate > 0 THEN 3
              WHEN p.verificationStatus = 'TCP_OPEN' THEN 2
              WHEN p.lastChecked = 0 OR p.verificationStatus = 'UNTESTED' THEN 1
              ELSE 0
            END ELSE 0 END DESC,
          CASE WHEN :sort = 'COUNTRY' THEN
            CASE WHEN p.transferScore > 0 THEN p.transferScore ELSE p.score END
            ELSE 0 END DESC,
          CASE WHEN :sort = 'COUNTRY' AND p.protocolLatency > 0
            THEN p.protocolLatency ELSE 9223372036854775807 END ASC,
          p.url ASC
        """
    )
    fun pagingSource(
        query: String,
        status: String,
        sort: String,
        currentNetworkFingerprint: String
    ): PagingSource<Int, MtProtoProxy>

    @Query("SELECT * FROM mtproto_proxies WHERE url = :url LIMIT 1")
    suspend fun getProxyByUrl(url: String): MtProtoProxy?

    @Query("SELECT COUNT(*) FROM favorite_proxies WHERE isActive = 1")
    fun observeFavoriteCount(): Flow<Int>

    @Query("SELECT * FROM favorite_proxies WHERE isActive = 1 ORDER BY folder, updatedAt DESC")
    fun observeActiveFavorites(): Flow<List<FavoriteProxy>>

    @Query("SELECT * FROM favorite_proxies WHERE url = :url LIMIT 1")
    suspend fun getFavoriteByUrl(url: String): FavoriteProxy?

    @Query("SELECT * FROM favorite_proxies WHERE isActive = 1 ORDER BY updatedAt DESC")
    suspend fun getActiveFavorites(): List<FavoriteProxy>

    @Query(
        "SELECT * FROM favorite_proxies WHERE isActive = 1 AND " +
            "syncState IN ('PENDING', 'FAILED') ORDER BY updatedAt ASC"
    )
    suspend fun getFavoritesNeedingSync(): List<FavoriteProxy>

    @Query("SELECT url FROM favorite_proxies WHERE isActive = 1 AND url IN (:urls)")
    suspend fun getActiveFavoriteUrls(urls: List<String>): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFavorite(favorite: FavoriteProxy)

    @Query(
        "UPDATE favorite_proxies SET folder = :folder, tags = :tags, note = :note, " +
            "updatedAt = :updatedAt WHERE url = :url AND isActive = 1"
    )
    suspend fun updateFavoriteMetadata(
        url: String,
        folder: String,
        tags: String,
        note: String,
        updatedAt: Long
    ): Int

    @Query(
        """
        UPDATE favorite_proxies SET
          lastKnownGoodAt = :checkedAt,
          lastKnownTransferScore = :transferScore,
          lastKnownLatency = :latency,
          lastKnownDownloadKbps = CASE WHEN :downloadKbps > 0 THEN :downloadKbps
            ELSE lastKnownDownloadKbps END,
          lastKnownUploadKbps = CASE WHEN :uploadKbps > 0 THEN :uploadKbps
            ELSE lastKnownUploadKbps END,
          updatedAt = MAX(updatedAt, :checkedAt)
        WHERE url = :url AND isActive = 1
        """
    )
    suspend fun updateFavoriteLastKnownGood(
        url: String,
        checkedAt: Long,
        transferScore: Int,
        latency: Long,
        downloadKbps: Int,
        uploadKbps: Int
    ): Int

    @Query("SELECT * FROM private_proxy_nodes ORDER BY enabled DESC, updatedAt DESC")
    fun observePrivateNodes(): Flow<List<PrivateProxyNode>>

    @Query("SELECT * FROM private_proxy_nodes ORDER BY enabled DESC, updatedAt DESC")
    suspend fun getPrivateNodes(): List<PrivateProxyNode>

    @Query(
        "SELECT * FROM private_proxy_nodes WHERE enabled = 1 " +
            "ORDER BY transferScore DESC, verificationConfidence DESC, p90Latency ASC LIMIT :limit"
    )
    suspend fun getEnabledPrivateNodes(limit: Int): List<PrivateProxyNode>

    @Query("SELECT * FROM private_proxy_nodes WHERE url = :url LIMIT 1")
    suspend fun getPrivateNode(url: String): PrivateProxyNode?

    @Query("SELECT * FROM private_proxy_nodes WHERE url = :url LIMIT 1")
    fun observePrivateNode(url: String): Flow<PrivateProxyNode?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPrivateNode(node: PrivateProxyNode)

    @Query("DELETE FROM private_proxy_nodes WHERE url = :url")
    suspend fun deletePrivateNode(url: String): Int

    @Query(
        "UPDATE favorite_proxies SET savedMessageId = :messageId, syncState = 'SYNCED', " +
            "lastSyncError = '', updatedAt = :updatedAt WHERE url = :url AND isActive = 1"
    )
    suspend fun markFavoriteSynced(url: String, messageId: Long, updatedAt: Long): Int

    @Query(
        "UPDATE favorite_proxies SET syncState = 'FAILED', lastSyncError = :errorCode, " +
            "updatedAt = :updatedAt WHERE url = :url AND isActive = 1"
    )
    suspend fun markFavoriteSyncFailed(url: String, errorCode: String, updatedAt: Long): Int

    @Query(
        """
        SELECT * FROM mtproto_proxies
        WHERE lastChecked > 0
          AND isWorking = 1
          AND latency > 0
          AND protocolLatency > 0
          AND protocolSuccessRate > 0
          AND benchmarkFailureReason = ''
          AND benchmarkStage >= 2
          AND verificationStatus IN ('TELEGRAM_READY', 'MTPROTO_VERIFIED')
        ORDER BY
          CASE WHEN verificationStatus = 'TELEGRAM_READY' THEN 2 ELSE 1 END DESC,
          benchmarkStage DESC,
          benchmarkRounds DESC,
          protocolSuccessRate DESC,
          continuityRate DESC,
          stability DESC,
          successRate DESC,
          CASE WHEN transferScore > 0 THEN transferScore ELSE score END DESC,
          CASE WHEN protocolP90Latency > 0 THEN protocolP90Latency
            ELSE 9223372036854775807 END ASC,
          CASE WHEN protocolJitter >= 0 THEN protocolJitter
            ELSE 9223372036854775807 END ASC,
          lastChecked DESC,
          url ASC
        LIMIT :limit
        """
    )
    suspend fun getTelegramAuthorizationCandidates(limit: Int): List<MtProtoProxy>

    @Query(
        """
        SELECT * FROM mtproto_proxies
        ORDER BY
          CASE
            WHEN benchmarkFailureReason = '' AND benchmarkStage >= 2
              AND verificationStatus IN ('TELEGRAM_READY', 'MTPROTO_VERIFIED')
              AND isWorking = 1 AND latency > 0 AND protocolLatency > 0
              AND protocolSuccessRate > 0 THEN 5
            WHEN protocolConfirmations > 0 AND protocolLatency > 0
              AND protocolSuccessRate > 0 THEN 4
            WHEN isWorking = 1 AND latency > 0 THEN 3
            WHEN lastChecked = 0 THEN 2
            ELSE 1
          END DESC,
          CASE WHEN benchmarkFailureReason = '' THEN 1 ELSE 0 END DESC,
          protocolSuccessRate DESC,
          protocolConfirmations DESC,
          continuityRate DESC,
          stability DESC,
          successRate DESC,
          CASE WHEN protocolP90Latency > 0 THEN protocolP90Latency
            ELSE 9223372036854775807 END ASC,
          CASE WHEN latency > 0 THEN latency ELSE 9223372036854775807 END ASC,
          lastChecked DESC,
          url ASC
        LIMIT :limit
        """
    )
    suspend fun getTelegramRouteRecoveryCandidates(limit: Int): List<MtProtoProxy>

    @Query(
        """
        SELECT p.* FROM mtproto_proxies AS p
        WHERE lastChecked >= :freshAfter AND networkFingerprint = :networkFingerprint AND networkFingerprint != ''
          AND isWorking = 1
          AND latency > 0
          AND protocolLatency > 0
          AND protocolSuccessRate > 0
          AND (benchmarkFailureReason = '' OR """ + TransferRankingSql.MEASURED + """)
          AND benchmarkStage >= 2
          AND verificationStatus IN ('TELEGRAM_READY', 'MTPROTO_VERIFIED')
        ORDER BY
        """ + TransferRankingSql.PREVIEW_PREFIX + """
          CASE WHEN benchmarkStage >= 3 AND benchmarkRounds >= 3 THEN 1 ELSE 0 END DESC,
          CASE WHEN transferScore > 0 THEN transferScore ELSE score END DESC,
          continuityRate DESC,
          protocolSuccessRate DESC,
          stability DESC,
          successRate DESC,
          CASE WHEN protocolP90Latency > 0 THEN protocolP90Latency
            ELSE 9223372036854775807 END ASC,
          CASE WHEN protocolJitter >= 0 THEN protocolJitter
            ELSE 9223372036854775807 END ASC,
          lastChecked DESC,
          url ASC
        LIMIT 1
        """
    )
    fun observeSmartRecommendation(
        networkFingerprint: String,
        freshAfter: Long
    ): Flow<MtProtoProxy?>

    @Query("""
        SELECT h.* FROM proxy_test_history h
        WHERE h.networkFingerprint = :fingerprint AND :fingerprint != ''
          AND h.measuredAt >= :freshAfter AND h.failureReason = ''
          AND h.verificationStatus IN ('MTPROTO_VERIFIED', 'TELEGRAM_READY')
          AND h.id = (SELECT latest.id FROM proxy_test_history latest
            WHERE latest.proxyUrl = h.proxyUrl AND latest.networkFingerprint = :fingerprint
            ORDER BY latest.measuredAt DESC, latest.id DESC LIMIT 1)
        ORDER BY h.telegramDownloadKbps * 3 + h.telegramUploadKbps * 2 DESC,
          h.transferScore DESC, h.measuredAt DESC
        LIMIT :limit
    """)
    fun observeNetworkShortlist(fingerprint: String, freshAfter: Long, limit: Int): Flow<List<ProxyTestHistory>>

    @Query("SELECT * FROM mtproto_proxies WHERE url = :url LIMIT 1")
    fun observeProxyByUrl(url: String): Flow<MtProtoProxy?>

    @Query(
        "SELECT COUNT(*) FROM mtproto_proxies WHERE verificationStatus IN " +
            "('TELEGRAM_READY', 'MTPROTO_VERIFIED')"
    )
    suspend fun countTelegramVerified(): Int

    @Query("SELECT * FROM mtproto_proxies")
    suspend fun getAllSnapshot(): List<MtProtoProxy>

    @Query(
        """
        SELECT * FROM mtproto_proxies
        ORDER BY
            CASE WHEN isWorking = 1 AND lastChecked > 0 AND protocolConfirmations > 0
              AND protocolSuccessRate > 0 AND benchmarkFailureReason = '' THEN 0 ELSE 1 END ASC,
            CASE WHEN lastChecked = 0 OR verificationStatus = 'INCONCLUSIVE' THEN 0 ELSE 1 END ASC,
            lastChecked ASC,
            transferScore DESC,
            latency ASC,
            url ASC
        LIMIT :limit
        """
    )
    suspend fun getProxiesForBenchmark(limit: Int): List<MtProtoProxy>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertProxies(proxies: List<MtProtoProxy>): List<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTestHistories(history: List<ProxyTestHistory>)

    @Query(
        "SELECT * FROM proxy_test_history WHERE proxyUrl = :url " +
            "ORDER BY measuredAt DESC, id DESC LIMIT :limit"
    )
    fun observeTestHistory(url: String, limit: Int): Flow<List<ProxyTestHistory>>

    @Query(
        "SELECT * FROM proxy_test_history WHERE proxyUrl = :url " +
            "ORDER BY measuredAt DESC, id DESC LIMIT :limit"
    )
    suspend fun getTestHistory(url: String, limit: Int): List<ProxyTestHistory>

    @Query("DELETE FROM proxy_test_history WHERE measuredAt < :cutoff")
    suspend fun deleteHistoryOlderThan(cutoff: Long): Int

    @Query(
        "DELETE FROM proxy_test_history WHERE id NOT IN " +
            "(SELECT id FROM proxy_test_history ORDER BY measuredAt DESC, id DESC LIMIT :maxRows)"
    )
    suspend fun trimTestHistory(maxRows: Int): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBenchmarkRun(record: BenchmarkRunRecord)

    @Query("SELECT * FROM benchmark_run_state WHERE id = 1 LIMIT 1")
    fun observeBenchmarkRun(): Flow<BenchmarkRunRecord?>

    @Query("SELECT * FROM benchmark_run_state WHERE id = 1 LIMIT 1")
    suspend fun getBenchmarkRun(): BenchmarkRunRecord?

    @Query(
        "UPDATE benchmark_run_state SET status = 'INTERRUPTED', " +
            "finishedAt = :now, failure = 'PROCESS_INTERRUPTED' " +
            "WHERE id = 1 AND status IN ('RUNNING', 'PAUSED')"
    )
    suspend fun markOrphanedBenchmarkInterrupted(now: Long): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSourceMemberships(memberships: List<ProxySourceMembership>)

    @Query("DELETE FROM proxy_source_membership")
    suspend fun clearSourceMemberships()

    @Transaction
    suspend fun insertFreshChunk(
        proxies: List<MtProtoProxy>,
        memberships: List<ProxySourceMembership> = emptyList()
    ): Int {
        if (memberships.isNotEmpty()) insertSourceMemberships(memberships)
        if (proxies.isEmpty()) return 0
        val favoriteUrls = getActiveFavoriteUrls(proxies.map(MtProtoProxy::url)).toHashSet()
        return insertProxies(
            proxies.map { proxy -> proxy.asFreshSnapshot(proxy.url in favoriteUrls) }
        ).count { it != -1L }
    }

    @Transaction
    suspend fun replaceAll(
        proxies: List<MtProtoProxy>,
        memberships: List<ProxySourceMembership> = emptyList()
    ): Int {
        if (proxies.isEmpty()) return 0
        clearAll()
        clearSourceMemberships()
        clearBenchmarkRun()
        return insertFreshChunk(proxies, memberships)
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrReplace(proxy: MtProtoProxy)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrReplaceAll(proxies: List<MtProtoProxy>)

    @Transaction
    suspend fun insertOrReplaceAllPreservingFavorites(proxies: List<MtProtoProxy>) {
        if (proxies.isEmpty()) return
        val favoriteUrls = getActiveFavoriteUrls(proxies.map(MtProtoProxy::url)).toHashSet()
        insertOrReplaceAll(
            proxies.map { proxy ->
                proxy.copy(isFavorite = proxy.isFavorite || proxy.url in favoriteUrls)
            }
        )
    }

    @Transaction
    suspend fun restoreActiveFavorites(): Int {
        val active = getActiveFavorites()
        if (active.isEmpty()) return 0
        val inserted = insertProxies(active.map(FavoriteProxy::asFreshProxy)).count { it != -1L }
        applyActiveFavoriteFlags()
        return inserted
    }

    @Query(
        "UPDATE mtproto_proxies SET isFavorite = 1 WHERE url IN " +
            "(SELECT url FROM favorite_proxies WHERE isActive = 1)"
    )
    suspend fun applyActiveFavoriteFlags(): Int

    @Transaction
    suspend fun importFavorite(
        proxy: MtProtoProxy,
        folder: String,
        tags: String,
        note: String,
        now: Long
    ): Boolean {
        val previous = getFavoriteByUrl(proxy.url)
        val current = getProxyByUrl(proxy.url) ?: proxy
        val preserved = if (current.countryCode == MtProtoProxy.COUNTRY_UNKNOWN_CODE && previous != null) {
            current.copy(country = previous.country, countryCode = previous.countryCode)
        } else {
            current
        }
        val record = FavoriteProxy.fromProxy(
            proxy = preserved,
            previous = previous,
            now = now,
            syncState = previous?.syncState ?: FavoriteProxy.SYNC_LOCAL_ONLY
        ).copy(folder = folder, tags = tags, note = note, lastSyncError = previous?.lastSyncError.orEmpty())
        upsertFavorite(record)
        updateFavorite(proxy.url, true)
        return true
    }

    @Transaction
    suspend fun setFavorite(
        proxy: MtProtoProxy,
        isFavorite: Boolean,
        syncState: String,
        now: Long
    ): FavoriteProxy {
        val previous = getFavoriteByUrl(proxy.url)
        val record = if (isFavorite) {
            FavoriteProxy.fromProxy(proxy, previous, now, syncState)
        } else {
            (previous ?: FavoriteProxy.fromProxy(proxy, null, now, syncState)).copy(
                isActive = false,
                updatedAt = now,
                lastSyncError = ""
            )
        }
        upsertFavorite(record)
        updateFavorite(proxy.url, isFavorite)
        return record
    }

    @Transaction
    suspend fun persistBenchmarkResults(
        proxies: List<MtProtoProxy>,
        history: List<ProxyTestHistory>
    ) {
        if (proxies.isNotEmpty()) insertOrReplaceAllPreservingFavorites(proxies)
        if (history.isNotEmpty()) insertTestHistories(history)
    }

    @Query("UPDATE mtproto_proxies SET isFavorite = :isFavorite WHERE url = :url")
    suspend fun updateFavorite(url: String, isFavorite: Boolean)

    @Query("UPDATE mtproto_proxies SET recentlyUsed = :timestamp WHERE url = :url")
    suspend fun updateRecentlyUsed(url: String, timestamp: Long)

    @Query("UPDATE mtproto_proxies SET benchmarkFailureReason = 'TELEGRAM_TRANSFER_FAILED' " +
        "WHERE url = :url AND networkFingerprint = :networkFingerprint AND networkFingerprint != ''")
    suspend fun markTelegramTransferFailed(url: String, networkFingerprint: String): Int

    @Query("""UPDATE mtproto_proxies SET telegramDownloadKbps = 0, telegramUploadKbps = 0,
        telegramTransferMeasuredAt = 0, telegramTransferBytes = 0, telegramTransferRounds = 0,
        telegramTransferConfidence = 0, telegramNetworkUploadBytes = 0, telegramNetworkDownloadBytes = 0,
        telegramDownloadFloorKbps = 0, telegramUploadFloorKbps = 0,
        telegramDownloadPeakKbps = 0, telegramUploadPeakKbps = 0
        WHERE url = :url AND networkFingerprint = :networkFingerprint AND networkFingerprint != ''""")
    suspend fun clearTelegramTransfer(url: String, networkFingerprint: String): Int

    @Query(
        """
        UPDATE mtproto_proxies SET
          telegramDownloadKbps = :downloadKbps,
          telegramUploadKbps = :uploadKbps,
          telegramTransferBytes = :sampleBytes,
          telegramTransferMeasuredAt = :measuredAt,
          telegramTransferRounds = :rounds,
          telegramTransferConfidence = :confidence,
          telegramNetworkUploadBytes = :networkUploadBytes,
          telegramNetworkDownloadBytes = :networkDownloadBytes,
          telegramDownloadFloorKbps = :downloadFloorKbps,
          telegramUploadFloorKbps = :uploadFloorKbps,
          telegramDownloadPeakKbps = :downloadPeakKbps,
          telegramUploadPeakKbps = :uploadPeakKbps,
          testedNetworkType = :networkType,
          benchmarkFailureReason = CASE WHEN benchmarkFailureReason = 'TELEGRAM_TRANSFER_FAILED'
            THEN '' ELSE benchmarkFailureReason END
        WHERE url = :url AND networkFingerprint = :networkFingerprint AND networkFingerprint != ''
          AND :measuredAt >= lastChecked AND :measuredAt >= telegramTransferMeasuredAt
        """
    )
    suspend fun updateTelegramTransfer(
        url: String,
        downloadKbps: Int,
        uploadKbps: Int,
        sampleBytes: Long,
        measuredAt: Long,
        rounds: Int,
        confidence: Int,
        networkUploadBytes: Long,
        networkDownloadBytes: Long,
        networkFingerprint: String,
        networkType: String,
        downloadFloorKbps: Int = 0,
        uploadFloorKbps: Int = 0,
        downloadPeakKbps: Int = 0,
        uploadPeakKbps: Int = 0
    ): Int

    @Query(
        """
        DELETE FROM mtproto_proxies
        WHERE isFavorite = 0
            AND recentlyUsed = 0
            AND lastChecked > 0
            AND isWorking = 0
            AND verificationStatus = 'OFFLINE'
        """
    )
    suspend fun deleteDeadProxies(): Int

    @Query(
        """
        DELETE FROM proxy_source_membership
        WHERE NOT EXISTS (
          SELECT 1 FROM mtproto_proxies WHERE mtproto_proxies.url = proxy_source_membership.proxyUrl
        )
        """
    )
    suspend fun deleteOrphanedSourceMemberships(): Int

    @Transaction
    suspend fun pruneDeadProxies(): Int {
        val deleted = deleteDeadProxies()
        deleteOrphanedSourceMemberships()
        return deleted
    }

    @Query("DELETE FROM mtproto_proxies")
    suspend fun clearAll(): Int

    @Query("DELETE FROM proxy_source_health")
    suspend fun clearSourceHealth()

    @Query("DELETE FROM favorite_proxies")
    suspend fun clearFavorites()

    @Query("DELETE FROM proxy_test_history")
    suspend fun clearTestHistory()

    @Query("DELETE FROM benchmark_run_state")
    suspend fun clearBenchmarkRun()

    @Query("DELETE FROM private_proxy_nodes")
    suspend fun clearPrivateNodes()

    @Query("DELETE FROM source_fetch_history")
    suspend fun clearSourceFetchHistory()

    @Query("DELETE FROM network_speed_baselines")
    suspend fun clearNetworkSpeedBaselines()

    @Query("DELETE FROM web_proxy_nodes")
    suspend fun clearWebProxies()

    @Transaction
    suspend fun clearAllData(): Int {
        val deleted = clearAll()
        clearSourceMemberships()
        clearSourceHealth()
        clearFavorites()
        clearTestHistory()
        clearBenchmarkRun()
        clearPrivateNodes()
        clearSourceFetchHistory()
        clearNetworkSpeedBaselines()
        clearWebProxies()
        return deleted
    }
}

// A successful refresh is a new snapshot. Old benchmark, favorite, and usage state must never
// leak into a newly collected endpoint, including chunks appended after the first transaction.
private fun MtProtoProxy.asFreshSnapshot(isFavorite: Boolean): MtProtoProxy {
    return copy(
        isWorking = false,
        latency = 0,
        successRate = 0f,
        stability = 0f,
        score = 0,
        isFavorite = isFavorite,
        recentlyUsed = 0L,
        lastChecked = 0L,
        verificationStatus = MtProtoProxy.VERIFICATION_UNTESTED,
        verificationConfidence = 0,
        protocolLatency = 0L,
        protocolSuccessRate = 0f,
        tcpAttempts = 0,
        tcpSuccesses = 0,
        protocolAttempts = 0,
        protocolConfirmations = 0,
        tcpP90Latency = 0L,
        tcpJitter = 0L,
        protocolP90Latency = 0L,
        protocolJitter = 0L,
        transferScore = 0,
        benchmarkStage = 0,
        benchmarkRounds = 0,
        continuityRate = 0f,
        networkFingerprint = "",
        testedNetworkType = "",
        telegramDownloadKbps = 0,
        telegramUploadKbps = 0,
        telegramTransferBytes = 0,
        telegramTransferMeasuredAt = 0,
        benchmarkFailureReason = "",
        telegramTransferRounds = 0,
        telegramTransferConfidence = 0,
        telegramNetworkUploadBytes = 0L,
        telegramNetworkDownloadBytes = 0L,
        telegramDownloadFloorKbps = 0,
        telegramUploadFloorKbps = 0,
        telegramDownloadPeakKbps = 0,
        telegramUploadPeakKbps = 0
    )
}
