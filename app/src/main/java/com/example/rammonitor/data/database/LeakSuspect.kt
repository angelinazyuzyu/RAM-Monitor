package com.example.rammonitor.data.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "LEAK_SUSPECT",
    foreignKeys = [ForeignKey(entity = AppInfo::class, parentColumns = ["app_id"], childColumns = ["app_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("app_id")]
)
data class LeakSuspect(
    @PrimaryKey(autoGenerate = true) val suspect_id: Long = 0,
    val app_id: Int,
    val detected_at: Long,
    val window_start: Long,
    val window_end: Long,
    val samples_count: Int,
    val slope_mb_per_hour: Float,
    val monotonicity: Float,
    val confidence: Float,
    val verdict: String,
    val acknowledged: Boolean
)
