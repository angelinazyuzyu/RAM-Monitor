package com.example.rammonitor.monitoring

import android.content.Context
import android.os.Debug
import android.os.Process
import android.os.SystemClock
import com.example.rammonitor.data.database.AppDatabase
import com.example.rammonitor.data.database.AppInfo
import com.example.rammonitor.data.database.ProcessMetric

/**
 * Собирает метрики только собственного процесса приложения.
 * Android ограничивает доступ обычных приложений к подробным метрикам
 * процессов других UID.
 */
class ProcessMetricsCollector(context: Context) {
    private val appContext = context.applicationContext
    private val dao = AppDatabase.getInstance(appContext).monitorDao()

    @Suppress("DEPRECATION")
    suspend fun collectOwnProcess(snapshotId: Long, isForeground: Boolean) {
        val packageName = appContext.packageName
        val now = System.currentTimeMillis()

        var app = dao.getAppByPackage(packageName)
        if (app == null) {
            val pm = appContext.packageManager
            val appInfo = pm.getApplicationInfo(packageName, 0)
            val packageInfo = pm.getPackageInfo(packageName, 0)
            val label = pm.getApplicationLabel(appInfo).toString()
            val versionCode = if (android.os.Build.VERSION.SDK_INT >= 28) {
                packageInfo.longVersionCode.toInt()
            } else {
                packageInfo.versionCode
            }

            dao.insertAppIfMissing(
                AppInfo(
                    package_name = packageName,
                    app_label = label,
                    version_name = packageInfo.versionName,
                    version_code = versionCode,
                    is_system = false,
                    first_seen_at = now,
                    last_seen_at = now
                )
            )
            app = dao.getAppByPackage(packageName)
        }

        val appId = app?.app_id ?: return
        val memoryInfo = Debug.MemoryInfo()
        Debug.getMemoryInfo(memoryInfo)

        // Android reports these values in KB.
        val stats = memoryInfo.memoryStats
        fun statKb(key: String, fallback: Int = 0): Int =
            stats[key]?.toIntOrNull()?.coerceAtLeast(0) ?: fallback

        val javaHeap = statKb("summary.java-heap", memoryInfo.dalvikPss)
        val nativeHeap = statKb("summary.native-heap", memoryInfo.nativePss)
        val code = statKb("summary.code")
        val stack = statKb("summary.stack")
        val graphics = statKb("summary.graphics")
        val privateDirty = memoryInfo.totalPrivateDirty.coerceAtLeast(0)
        // Debug.MemoryInfo не предоставляет totalRss во всех поддерживаемых SDK.
        // Для собственного процесса читаем VmRSS из /proc/self/status.
        val rss = readOwnProcessRssKb()

        dao.insertProcessMetric(
            ProcessMetric(
                snapshot_id = snapshotId,
                app_id = appId,
                pid = Process.myPid(),
                pss_kb = memoryInfo.totalPss.coerceAtLeast(0),
                rss_kb = rss,
                java_heap_kb = javaHeap,
                native_heap_kb = nativeHeap,
                code_kb = code,
                stack_kb = stack,
                graphics_kb = graphics,
                private_dirty_kb = privateDirty,
                is_foreground = isForeground,
                uptime_ms = SystemClock.elapsedRealtime()
            )
        )
    }

    /**
     * Возвращает Resident Set Size (VmRSS) собственного процесса в КБ.
     * Если ОС не разрешила прочитать значение, возвращает 0 (нет данных).
     */
    private fun readOwnProcessRssKb(): Int {
        return try {
            val line = java.io.File("/proc/self/status")
                .useLines { lines -> lines.firstOrNull { it.startsWith("VmRSS:") } }
                ?: return 0
            line.substringAfter("VmRSS:")
                .trim()
                .substringBefore(" ")
                .toIntOrNull()
                ?.coerceAtLeast(0) ?: 0
        } catch (_: Exception) {
            0
        }
    }
}
