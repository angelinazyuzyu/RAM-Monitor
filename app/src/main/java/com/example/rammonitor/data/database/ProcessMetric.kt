package com.example.rammonitor.data.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "PROCESS_METRIC",
    foreignKeys = [
        ForeignKey(entity = Snapshot::class, parentColumns = ["snapshot_id"], childColumns = ["snapshot_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = AppInfo::class, parentColumns = ["app_id"], childColumns = ["app_id"], onDelete = ForeignKey.CASCADE)
    ],
    indices = [Index("snapshot_id"), Index("app_id")]
)
data class ProcessMetric(
    @PrimaryKey(autoGenerate = true) val metric_id: Long = 0,
    val snapshot_id: Long,
    val app_id: Int,
    val pid: Int,
    val pss_kb: Int,
    val rss_kb: Int,
    val java_heap_kb: Int,
    val native_heap_kb: Int,
    val code_kb: Int,
    val stack_kb: Int,
    val graphics_kb: Int,
    val private_dirty_kb: Int,
    val is_foreground: Boolean,
    val uptime_ms: Long
)
