package com.example.rammonitor.data.database

/**
 * Срез временного ряда процесса. Значения памяти указаны в КБ,
 * captured_at — Unix time в миллисекундах.
 */
data class ProcessSample(
    val captured_at: Long,
    val pss_kb: Int,
    val private_dirty_kb: Int,
    val pid: Int,
    val uptime_ms: Long
)