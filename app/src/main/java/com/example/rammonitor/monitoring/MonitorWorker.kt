package com.example.rammonitor.monitoring

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class MonitorWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        MemoryMonitor(applicationContext).capture("periodic")
        Result.success()
    } catch (_: Exception) {
        Result.retry()
    }
}
