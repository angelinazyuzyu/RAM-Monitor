package com.example.rammonitor.monitoring

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import com.example.rammonitor.data.database.AppDatabase
import com.example.rammonitor.data.database.DeviceMetric
import com.example.rammonitor.data.database.Snapshot

class MemoryMonitor(context: Context) {
    private val appContext = context.applicationContext
    private val dao = AppDatabase.getInstance(appContext).monitorDao()

    suspend fun capture(
        triggerType: String = "periodic",
        isForeground: Boolean = false
    ): Long {
        val manager = appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        manager.getMemoryInfo(info)

        val totalBytes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) info.totalMem else 0L
        val availableBytes = info.availMem
        val totalMb = (totalBytes / (1024L * 1024L)).toInt()
        val availableMb = (availableBytes / (1024L * 1024L)).toInt()
        val usedMb = (totalMb - availableMb).coerceAtLeast(0)
        val thresholdMb = 512

        val snapshot = Snapshot(
            captured_at = System.currentTimeMillis(),
            trigger_type = triggerType,
            total_ram_mb = totalMb,
            avail_ram_mb = availableMb,
            used_ram_mb = usedMb,
            low_memory_flag = if (info.lowMemory || availableMb < thresholdMb) 1 else 0,
            threshold_mb = thresholdMb
        )

        val deviceMetric = DeviceMetric(
            snapshot_id = 0L, // DAO подставит реальный ID созданного SNAPSHOT.
            total_pss_kb = 0,
            cached_kb = 0,
            free_kb = (availableBytes / 1024L).toInt(),
            zram_kb = 0,
            memory_pressure = if (totalMb > 0) usedMb.toFloat() / totalMb else 0f
        )

        val snapshotId = dao.saveSnapshotWithDeviceMetric(snapshot, deviceMetric)

        // Подробные метрики собираем для собственного процесса приложения.
        ProcessMetricsCollector(appContext).collectOwnProcess(snapshotId, isForeground)

        // После сохранения нового образца проверяем временной ряд нашего приложения.
        val ownApp = dao.getAppByPackage(appContext.packageName)
        if (ownApp != null) {
            LeakDetector(AppDatabase.getInstance(appContext)).analyzeApp(ownApp.app_id)
        }
        return snapshotId
    }
}
