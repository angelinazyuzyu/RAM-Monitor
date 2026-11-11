
package com.example.rammonitor.data.usage

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process

data class AppUsageStats(
    val totalTimeMs: Long,
    val lastTimeUsed: Long
)

object AppUsageStatsProvider {

    fun hasPermission(context: Context): Boolean {
        val appOps = context.getSystemService(
            Context.APP_OPS_SERVICE
        ) as AppOpsManager

        val mode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName
        )

        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun getUsageForLastDays(
        context: Context,
        packageName: String,
        days: Int = 7
    ): AppUsageStats? {

        if (!hasPermission(context)) return null

        val manager = context.getSystemService(
            Context.USAGE_STATS_SERVICE
        ) as UsageStatsManager

        val endTime = System.currentTimeMillis()
        val startTime = endTime - days * 24L * 60L * 60L * 1000L

        val stats = manager.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY,
            startTime,
            endTime
        )

        val appStats = stats
            .orEmpty()
            .filter { it.packageName == packageName }

        if (appStats.isEmpty()) return null

        return AppUsageStats(
            totalTimeMs = appStats.sumOf {
                it.totalTimeInForeground
            },
            lastTimeUsed = appStats.maxOfOrNull {
                it.lastTimeUsed
            } ?: 0L
        )
    }
}
