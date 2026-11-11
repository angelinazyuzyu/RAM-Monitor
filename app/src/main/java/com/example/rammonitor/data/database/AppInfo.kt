package com.example.rammonitor.data.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "APP_INFO", indices = [Index(value = ["package_name"], unique = true)])
data class AppInfo(
    @PrimaryKey(autoGenerate = true) val app_id: Int = 0,
    val package_name: String,
    val app_label: String,
    val version_name: String?,
    val version_code: Int,
    val is_system: Boolean,
    val first_seen_at: Long,
    val last_seen_at: Long
)
