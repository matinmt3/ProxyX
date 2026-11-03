package com.example.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "network_speed_baselines",
    indices = [Index(value = ["measuredAt"])]
)
data class NetworkSpeedBaseline(
    @PrimaryKey val networkFingerprint: String,
    val networkType: String,
    val isMetered: Boolean,
    val downloadKbps: Int,
    val uploadKbps: Int,
    val downloadSampleBytes: Int,
    val uploadSampleBytes: Int,
    val measuredAt: Long
) {
    val isUsable: Boolean
        get() = networkFingerprint.isNotBlank() &&
            downloadKbps > 0 && uploadKbps > 0 && measuredAt > 0L
}
