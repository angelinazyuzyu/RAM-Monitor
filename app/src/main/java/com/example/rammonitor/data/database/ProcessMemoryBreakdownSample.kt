package com.example.rammonitor.data.database

data class ProcessMemoryBreakdownSample(
    val captured_at: Long,
    val pid: Int,
    val uptime_ms: Long,
    val pss_kb: Int,
    val java_heap_kb: Int,
    val native_heap_kb: Int,
    val graphics_kb: Int,
    val private_dirty_kb: Int
)