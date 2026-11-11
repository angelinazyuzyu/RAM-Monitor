package com.example.rammonitor.data.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "DEVICE_METRIC",
    foreignKeys = [ForeignKey(entity = Snapshot::class, parentColumns = ["snapshot_id"], childColumns = ["snapshot_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("snapshot_id")]
)
data class DeviceMetric(
    @PrimaryKey(autoGenerate = true) val device_metric_id: Long = 0,
    val snapshot_id: Long,
    val total_pss_kb: Int,
    val cached_kb: Int,
    val free_kb: Int,
    val zram_kb: Int,
    val memory_pressure: Float
)
