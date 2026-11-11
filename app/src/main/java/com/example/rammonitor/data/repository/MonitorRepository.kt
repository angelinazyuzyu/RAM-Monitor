package com.example.rammonitor.data.repository

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import com.example.rammonitor.data.database.AppDatabase
import com.example.rammonitor.data.database.AppInfo
import com.example.rammonitor.data.database.LeakSuspect
import com.example.rammonitor.data.database.ProcessMetric
import com.example.rammonitor.data.database.ProcessSample
import com.example.rammonitor.data.database.Snapshot

class MonitorRepository(context: Context) {
    private val appContext = context.applicationContext
    private val dao = AppDatabase.getInstance(appContext).monitorDao()

    @Suppress("DEPRECATION")
    suspend fun refreshInstalledApps(): Int {
        val pm = appContext.packageManager
        val installed = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        val now = System.currentTimeMillis()

        installed.forEach { app ->
            val packageName = app.packageName
            val old = dao.getAppByPackage(packageName)
            val packageInfo = try {
                pm.getPackageInfo(packageName, 0)
            } catch (_: Exception) {
                null
            }

            val versionCode = if (packageInfo == null) {
                0
            } else if (Build.VERSION.SDK_INT >= 28) {
                packageInfo.longVersionCode.toInt()
            } else {
                packageInfo.versionCode
            }

            val label = try {
                pm.getApplicationLabel(app).toString()
            } catch (_: Exception) {
                packageName
            }

            dao.upsertApp(
                AppInfo(
                    app_id = old?.app_id ?: 0,
                    package_name = packageName,
                    app_label = label,
                    version_name = packageInfo?.versionName,
                    version_code = versionCode,
                    is_system = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                    first_seen_at = old?.first_seen_at ?: now,
                    last_seen_at = now
                )
            )
        }
        return installed.size
    }

    suspend fun getInstalledApps(): List<AppInfo> = dao.getAllApps()

    suspend fun getLatestHistory(limit: Int = 100): List<Snapshot> =
        dao.getLatestSnapshots(limit.coerceIn(1, 1000))

    suspend fun getHistoryForLastDays(days: Int): List<Snapshot> {
        val end = System.currentTimeMillis()
        val safeDays = days.coerceAtLeast(1)
        val start = end - safeDays * 24L * 60L * 60L * 1000L
        return dao.getSnapshotsBetween(start, end)
    }

    suspend fun getProcessHistory(appId: Int): List<ProcessMetric> =
        dao.getProcessMetricsForApp(appId)

    suspend fun getProcessSamples(appId: Int): List<ProcessSample> =
        dao.getProcessSamplesForApp(appId)

    suspend fun getLeakSuspects(appId: Int): List<LeakSuspect> =
        dao.getLeakSuspectsForApp(appId)

    suspend fun getAllLeakSuspects(): List<LeakSuspect> =
        dao.getAllLeakSuspects()

    suspend fun acknowledgeLeakSuspect(suspectId: Long, acknowledged: Boolean) {
        dao.setLeakSuspectAcknowledged(suspectId, acknowledged)
    }

    suspend fun getSnapshotCount(): Int = dao.getSnapshotCount()
}
