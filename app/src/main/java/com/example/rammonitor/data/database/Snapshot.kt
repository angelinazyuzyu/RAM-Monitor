package com.example.rammonitor.data.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "SNAPSHOT")
data class Snapshot(
    @PrimaryKey(autoGenerate = true) val snapshot_id: Long = 0,
    val captured_at: Long,
    val trigger_type: String,
    val total_ram_mb: Int,
    val avail_ram_mb: Int,
    val used_ram_mb: Int,
    val low_memory_flag: Int,
    val threshold_mb: Int
)
