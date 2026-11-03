package com.example.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.model.ProxySourceHealth
import com.example.model.ProxySourceMembership
import com.example.model.SourceVerificationYield
import com.example.model.SourceFetchRecord
import com.example.model.SourceWindowStats
import kotlinx.coroutines.flow.Flow

@Dao
interface ProxySourceHealthDao {
    @Query("SELECT * FROM proxy_source_health ORDER BY quarantineUntil ASC, lastSuccessAt DESC")
    fun observeAll(): Flow<List<ProxySourceHealth>>

    @Query("SELECT * FROM proxy_source_health")
    suspend fun getAll(): List<ProxySourceHealth>

    @Query("SELECT * FROM proxy_source_health WHERE sourceUrl = :url LIMIT 1")
    suspend fun get(url: String): ProxySourceHealth?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(health: ProxySourceHealth)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(health: List<ProxySourceHealth>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMemberships(memberships: List<ProxySourceMembership>)

    @Query("SELECT * FROM proxy_source_membership")
    suspend fun getAllMemberships(): List<ProxySourceMembership>

    @Query("DELETE FROM proxy_source_membership")
    suspend fun clearMemberships()

    @Query(
        """
        SELECT m.sourceUrl AS sourceUrl,
          SUM(CASE WHEN p.lastChecked > 0 THEN 1 ELSE 0 END) AS testedCount,
          SUM(CASE WHEN p.verificationStatus IN ('TELEGRAM_READY', 'MTPROTO_VERIFIED')
            THEN 1 ELSE 0 END) AS verifiedCount
        FROM proxy_source_membership m
        INNER JOIN mtproto_proxies p ON p.url = m.proxyUrl
        GROUP BY m.sourceUrl
        """
    )
    suspend fun getVerificationYields(): List<SourceVerificationYield>

    @Query(
        "UPDATE proxy_source_health SET verifiedProxyCount = :verified, " +
            "verifiedRate = :rate WHERE sourceUrl = :url"
    )
    suspend fun updateVerifiedYield(url: String, verified: Int, rate: Float)

    @Insert
    suspend fun insertFetchRecord(record: SourceFetchRecord)

    @Query(
        """
        SELECT sourceUrl AS sourceUrl,
          COUNT(*) AS fetchCount,
          COALESCE(SUM(CASE WHEN success = 1 THEN 1 ELSE 0 END), 0) AS successfulFetchCount,
          COALESCE(SUM(uniqueProxyCount), 0) AS totalUniqueProxies,
          COALESCE(AVG(uniqueProxyCount), 0.0) AS averageUniqueProxies,
          COALESCE(AVG(duplicateRate), 0.0) AS averageDuplicateRate,
          COALESCE(AVG(durationMs), 0.0) AS averageDurationMs,
          COALESCE(MAX(fetchedAt), 0) AS lastFetchAt
        FROM source_fetch_history
        WHERE fetchedAt >= :cutoff
        GROUP BY sourceUrl
        """
    )
    fun observeWindowStats(cutoff: Long): Flow<List<SourceWindowStats>>

    @Query(
        """
        UPDATE proxy_source_health
        SET mirrorGroupSize = CASE
          WHEN contentSignature = '' THEN 1
          ELSE (
            SELECT COUNT(*) FROM proxy_source_health AS peers
            WHERE peers.contentSignature = proxy_source_health.contentSignature
              AND peers.contentSignature != ''
          )
        END
        """
    )
    suspend fun refreshMirrorGroupSizes()

    @Query("DELETE FROM source_fetch_history WHERE fetchedAt < :cutoff")
    suspend fun deleteFetchHistoryOlderThan(cutoff: Long): Int

    @Query("DELETE FROM source_fetch_history")
    suspend fun clearFetchHistory()

    @Query("DELETE FROM proxy_source_health")
    suspend fun clear()
}
