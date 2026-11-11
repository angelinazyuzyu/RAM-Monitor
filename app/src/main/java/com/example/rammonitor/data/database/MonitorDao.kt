package com.example.rammonitor.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert

@Dao
interface MonitorDao {

    // Обновляет существующую запись без удаления связанных измерений.
    @Upsert
    suspend fun upsertApp(app: AppInfo)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAppIfMissing(app: AppInfo): Long

    @Query("SELECT * FROM APP_INFO WHERE package_name = :packageName LIMIT 1")
    suspend fun getAppByPackage(packageName: String): AppInfo?

    @Query("SELECT * FROM APP_INFO ORDER BY app_label COLLATE NOCASE")
    suspend fun getAllApps(): List<AppInfo>

    @Query("SELECT * FROM APP_INFO WHERE app_id = :appId LIMIT 1")
    suspend fun getAppById(appId: Int): AppInfo?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSnapshot(snapshot: Snapshot): Long

    @Query("SELECT * FROM SNAPSHOT ORDER BY captured_at DESC LIMIT :limit")
    suspend fun getLatestSnapshots(limit: Int): List<Snapshot>

    @Query("""
        SELECT * FROM SNAPSHOT
        WHERE captured_at BETWEEN :fromMillis AND :toMillis
        ORDER BY captured_at ASC
    """)
    suspend fun getSnapshotsBetween(fromMillis: Long, toMillis: Long): List<Snapshot>

    @Query("SELECT * FROM SNAPSHOT ORDER BY captured_at DESC LIMIT 1")
    suspend fun getLatestSnapshot(): Snapshot?

    @Query("SELECT COUNT(*) FROM SNAPSHOT")
    suspend fun getSnapshotCount(): Int

    @Insert
    suspend fun insertProcessMetric(metric: ProcessMetric)

    @Query("SELECT * FROM PROCESS_METRIC WHERE app_id = :appId ORDER BY uptime_ms ASC")
    suspend fun getProcessMetricsForApp(appId: Int): List<ProcessMetric>

    @Query("""
        SELECT pm.* FROM PROCESS_METRIC pm
        INNER JOIN SNAPSHOT s ON s.snapshot_id = pm.snapshot_id
        WHERE pm.app_id = :appId
          AND s.captured_at BETWEEN :fromMillis AND :toMillis
        ORDER BY s.captured_at ASC
    """)
    suspend fun getProcessMetricsForAppInPeriod(
        appId: Int,
        fromMillis: Long,
        toMillis: Long
    ): List<ProcessMetric>

    @Query("""
    SELECT
        s.captured_at AS captured_at,
        pm.pss_kb AS pss_kb,
        pm.private_dirty_kb AS private_dirty_kb,
        pm.pid AS pid,
        pm.uptime_ms AS uptime_ms
    FROM PROCESS_METRIC pm
    INNER JOIN SNAPSHOT s ON s.snapshot_id = pm.snapshot_id
    WHERE pm.app_id = :appId
    ORDER BY s.captured_at ASC
""")
    suspend fun getProcessSamplesForApp(
        appId: Int
    ): List<ProcessSample>

    @Query("""
        SELECT EXISTS(
            SELECT 1 FROM LEAK_SUSPECT
            WHERE app_id = :appId AND detected_at >= :sinceMillis
        )
    """)
    suspend fun hasRecentLeakSuspect(appId: Int, sinceMillis: Long): Boolean

    @Query("SELECT * FROM PROCESS_METRIC WHERE snapshot_id = :snapshotId")
    suspend fun getProcessMetricsForSnapshot(snapshotId: Long): List<ProcessMetric>

    @Insert
    suspend fun insertDeviceMetric(metric: DeviceMetric)

    @Query("SELECT * FROM DEVICE_METRIC WHERE snapshot_id = :snapshotId LIMIT 1")
    suspend fun getDeviceMetricForSnapshot(snapshotId: Long): DeviceMetric?

    @Insert
    suspend fun insertLeakSuspect(suspect: LeakSuspect): Long

    @Query("SELECT * FROM LEAK_SUSPECT WHERE app_id = :appId ORDER BY detected_at DESC")
    suspend fun getLeakSuspectsForApp(appId: Int): List<LeakSuspect>

    @Query("SELECT * FROM LEAK_SUSPECT ORDER BY detected_at DESC")
    suspend fun getAllLeakSuspects(): List<LeakSuspect>

    @Query("UPDATE LEAK_SUSPECT SET acknowledged = :acknowledged WHERE suspect_id = :suspectId")
    suspend fun setLeakSuspectAcknowledged(suspectId: Long, acknowledged: Boolean)

    @Query("SELECT COUNT(*) FROM PROCESS_METRIC WHERE app_id = :appId")
    suspend fun getProcessSampleCount(appId: Int): Int

    // SNAPSHOT и DEVICE_METRIC сохраняются атомарно.
    @Transaction
    suspend fun saveSnapshotWithDeviceMetric(
        snapshot: Snapshot,
        deviceMetric: DeviceMetric
    ): Long {
        val newSnapshotId = insertSnapshot(snapshot)
        insertDeviceMetric(deviceMetric.copy(snapshot_id = newSnapshotId))
        return newSnapshotId
    }

    @Query("""
    SELECT
        s.captured_at AS captured_at,
        pm.pid AS pid,
        pm.uptime_ms AS uptime_ms,
        pm.pss_kb AS pss_kb,
        pm.java_heap_kb AS java_heap_kb,
        pm.native_heap_kb AS native_heap_kb,
        pm.graphics_kb AS graphics_kb,
        pm.private_dirty_kb AS private_dirty_kb
    FROM PROCESS_METRIC pm
    INNER JOIN SNAPSHOT s
        ON s.snapshot_id = pm.snapshot_id
    WHERE pm.app_id = :appId
      AND s.captured_at BETWEEN :fromMillis AND :toMillis
    ORDER BY s.captured_at ASC
""")
    suspend fun getMemoryBreakdownForAppInPeriod(
        appId: Int,
        fromMillis: Long,
        toMillis: Long
    ): List<ProcessMemoryBreakdownSample>
}
