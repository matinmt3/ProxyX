package com.example.data.generic

import android.content.Context
import androidx.paging.PagingSource
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface GenericProxyDao {
    @Query("SELECT * FROM generic_proxies WHERE (:protocol = 'ALL' OR protocol = :protocol) " +
        "AND (host LIKE '%' || :query || '%') ORDER BY " +
        "CASE WHEN status = 'WORKING' AND networkFingerprint = :network AND :network != '' THEN 0 ELSE 1 END, " +
        "CASE WHEN requestMs > 0 THEN requestMs ELSE 9223372036854775807 END, id")
    fun paging(query: String, protocol: String, network: String): PagingSource<Int, GenericProxy>

    @Query("SELECT * FROM generic_proxies ORDER BY id")
    suspend fun snapshot(): List<GenericProxy>
    @Query("SELECT * FROM generic_proxies WHERE id = :id")
    suspend fun get(id: String): GenericProxy?
    @Query("SELECT COUNT(*) FROM generic_proxies")
    fun count(): Flow<Int>
    @Query("SELECT * FROM generic_source_health ORDER BY id")
    fun health(): Flow<List<GenericSourceHealth>>
    @Query("SELECT * FROM generic_source_health WHERE id = :id")
    suspend fun health(id: String): GenericSourceHealth?
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(rows: List<GenericProxy>)
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putHealth(row: GenericSourceHealth)
    @Query("DELETE FROM generic_proxies WHERE id = :id") suspend fun delete(id: String)

    @Transaction
    suspend fun received(feed: GenericFeed, rows: List<GenericProxy>, now: Long) {
        require(rows.isNotEmpty())
        val incoming = rows.map { it.id }.toHashSet()
        snapshot().filter { it.id !in incoming && feed.id in it.sourceIds.split(',') }.forEach { old ->
            val remaining = old.sourceIds.split(',').filter { it != feed.id }
            if (remaining.isEmpty()) delete(old.id) else put(listOf(old.copy(sourceIds = remaining.joinToString(","))))
        }
        put(rows.map { row ->
            val old = get(row.id)
            row.copy(sourceIds = (old?.sourceIds.orEmpty().split(',') + feed.id)
                .filter(String::isNotBlank).distinct().sorted().joinToString(","))
        })
        putHealth(GenericSourceHealth(feed.id, now, now, rows.size))
    }

    @Query("UPDATE generic_proxies SET status = :status, testedAt = :testedAt, connectionMs = :connectionMs, " +
        "requestMs = :requestMs, errorCode = :error, networkFingerprint = :network " +
        "WHERE id = :id AND fetchedAt = :generation AND testedAt <= :testedAt")
    suspend fun measured(id: String, generation: Long, status: String, testedAt: Long,
        connectionMs: Long, requestMs: Long, error: String, network: String): Int

    @Query("DELETE FROM generic_proxies") suspend fun clearNodes()
    @Query("DELETE FROM generic_source_health") suspend fun clearHealth()
    @Transaction suspend fun clear() { clearNodes(); clearHealth() }
}

/** Independent storage: no migration or access to the user's Telegram database is needed. */
@Database(entities = [GenericProxy::class, GenericSourceHealth::class], version = 1, exportSchema = true)
abstract class GenericProxyDatabase : RoomDatabase() {
    abstract fun dao(): GenericProxyDao
    companion object {
        fun open(context: Context): GenericProxyDatabase = Room.databaseBuilder(context.applicationContext,
            GenericProxyDatabase::class.java, "generic_proxy_audit_database").build()
    }
}
