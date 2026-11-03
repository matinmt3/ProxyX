package com.example.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Transaction
import com.example.model.WebProxyNode
import kotlinx.coroutines.flow.Flow

@Dao
interface WebProxyDao {
    @Query("SELECT * FROM web_proxy_nodes ORDER BY createdAt DESC, url")
    fun observeAll(): Flow<List<WebProxyNode>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(nodes: List<WebProxyNode>): List<Long>

    @Update
    suspend fun update(node: WebProxyNode)

    @Query("DELETE FROM web_proxy_nodes WHERE url = :url")
    suspend fun delete(url: String)

    @Query("DELETE FROM web_proxy_nodes WHERE source != 'MANUAL'")
    suspend fun clearPublic()

    @Query("UPDATE web_proxy_nodes SET status = 'INTERRUPTED', downloadKbps = 0, uploadKbps = 0, transferBytes = 0, transferRounds = 0 WHERE status = 'TESTING'")
    suspend fun markInterrupted()

    @Transaction
    suspend fun replacePublic(nodes: List<WebProxyNode>) {
        clearPublic()
        insert(nodes)
    }
}
