package com.example.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "benchmark_run_state")
data class BenchmarkRunRecord(
    @PrimaryKey val id: Int = SINGLETON_ID,
    val status: String,
    val requestedCount: Int,
    val selectedWorkers: Int,
    val stage: Int,
    val stageCompleted: Int,
    val stageTotal: Int,
    val completedWork: Int,
    val totalWork: Int,
    val telegramTotal: Int,
    val telegramCompleted: Int,
    val telegramSucceeded: Int,
    val telegramFailed: Int,
    val enableTelegramTransfers: Boolean,
    val startedAt: Long,
    val updatedAt: Long,
    val finishedAt: Long,
    val failure: String,
    val accuracyMode: String = "BALANCED"
) {
    companion object {
        const val SINGLETON_ID = 1
    }
}
