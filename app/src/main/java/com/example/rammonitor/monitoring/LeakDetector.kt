
package com.example.rammonitor.monitoring

import com.example.rammonitor.data.database.AppDatabase
import com.example.rammonitor.data.database.LeakSuspect
import com.example.rammonitor.data.database.ProcessSample
import kotlin.math.abs

/**
 * Эвристический детектор устойчивого роста памяти.
 * Результат — подозрение, а не доказательство утечки.
 */
class LeakDetector(database: AppDatabase) {

    private val dao = database.monitorDao()

    suspend fun analyzeApp(appId: Int): LeakSuspect? {
        val all = dao.getProcessSamplesForApp(appId)

        if (all.isEmpty()) return null

        // Анализируем только последний запуск процесса.
        val latestPid = all.last().pid

        val samples = all
            .filter { it.pid == latestPid }
            .takeLast(MAX_SAMPLES)

        if (samples.size < MIN_SAMPLES) return null

        val first = samples.first()
        val last = samples.last()

        // Проверяем последовательность времени работы процесса.
        if (samples.zipWithNext().any { (a, b) ->
                b.uptime_ms <= a.uptime_ms
            }
        ) return null

        val uptimeDifference = last.uptime_ms - first.uptime_ms
        val wallClockDifference = last.captured_at - first.captured_at

        if (abs(wallClockDifference - uptimeDifference) > MAX_TIME_DRIFT_MS) {
            return null
        }

        if (wallClockDifference < MIN_WINDOW_MS) return null

        // PSS.
        val firstPssMb = first.pss_kb / 1024.0
        val lastPssMb = last.pss_kb / 1024.0

        val pssGrowthMb = lastPssMb - firstPssMb

        val pssSlope = calculateSlopeMbPerHour(samples) {
            it.pss_kb
        }

        val pssMonotonicity = calculateMonotonicity(samples) {
            it.pss_kb
        }

        if (pssGrowthMb < MIN_NET_GROWTH_MB) return null
        if (pssSlope < MIN_SLOPE_MB_PER_HOUR) return null
        if (pssMonotonicity < MIN_MONOTONICITY) return null

        // Private Dirty.
        val firstDirtyMb = first.private_dirty_kb / 1024.0
        val lastDirtyMb = last.private_dirty_kb / 1024.0

        val dirtyGrowthMb = lastDirtyMb - firstDirtyMb

        val dirtySlope = calculateSlopeMbPerHour(samples) {
            it.private_dirty_kb
        }

        val dirtyMonotonicity = calculateMonotonicity(samples) {
            it.private_dirty_kb
        }

        // Дополнительное подтверждение роста приватной памяти.
        val dirtyCorroborates =
            dirtyGrowthMb >= MIN_PRIVATE_DIRTY_GROWTH_MB &&
                    dirtySlope >= MIN_PRIVATE_DIRTY_SLOPE &&
                    dirtyMonotonicity >= MIN_PRIVATE_DIRTY_MONOTONICITY

        // Альтернативный путь: очень сильный устойчивый рост PSS.
        val strongPssGrowth =
            pssGrowthMb >= STRONG_PSS_GROWTH_MB &&
                    pssSlope >= STRONG_PSS_SLOPE &&
                    pssMonotonicity >= STRONG_PSS_MONOTONICITY

        // Не создаём подозрение без дополнительного подтверждения.
        if (!dirtyCorroborates && !strongPssGrowth) return null

        val now = System.currentTimeMillis()

        if (dao.hasRecentLeakSuspect(appId, now - DEDUP_WINDOW_MS)) {
            return null
        }

        val confidence = calculateConfidence(
            pssSlope = pssSlope,
            pssMonotonicity = pssMonotonicity,
            sampleCount = samples.size,
            pssGrowthMb = pssGrowthMb,
            dirtyGrowthMb = dirtyGrowthMb,
            dirtyMonotonicity = dirtyMonotonicity
        )

        val suspect = LeakSuspect(
            app_id = appId,
            detected_at = now,
            window_start = first.captured_at,
            window_end = last.captured_at,
            samples_count = samples.size,
            slope_mb_per_hour = pssSlope.toFloat(),
            monotonicity = pssMonotonicity.toFloat(),
            confidence = confidence.toFloat(),
            verdict = "SUSPECTED_SUSTAINED_GROWTH",
            acknowledged = false
        )

        dao.insertLeakSuspect(suspect)

        return suspect
    }

    private fun calculateSlopeMbPerHour(
        samples: List<ProcessSample>,
        selector: (ProcessSample) -> Int
    ): Double {
        val origin = samples.first().captured_at

        val xs = samples.map {
            (it.captured_at - origin) / 3_600_000.0
        }

        val ys = samples.map {
            selector(it) / 1024.0
        }

        val meanX = xs.average()
        val meanY = ys.average()

        var numerator = 0.0
        var denominator = 0.0

        for (i in xs.indices) {
            numerator += (xs[i] - meanX) * (ys[i] - meanY)
            denominator += (xs[i] - meanX) * (xs[i] - meanX)
        }

        return if (denominator == 0.0) {
            0.0
        } else {
            numerator / denominator
        }
    }

    private fun calculateMonotonicity(
        samples: List<ProcessSample>,
        selector: (ProcessSample) -> Int
    ): Double {
        var positive = 0
        var comparable = 0

        for (i in 1 until samples.size) {
            val delta = selector(samples[i]) - selector(samples[i - 1])

            // Игнорируем колебания меньше 256 КБ.
            if (abs(delta) >= 256) {
                comparable++
                if (delta > 0) positive++
            }
        }

        return if (comparable == 0) {
            0.0
        } else {
            positive.toDouble() / comparable
        }
    }

    private fun calculateConfidence(
        pssSlope: Double,
        pssMonotonicity: Double,
        sampleCount: Int,
        pssGrowthMb: Double,
        dirtyGrowthMb: Double,
        dirtyMonotonicity: Double
    ): Double {

        val slopeScore =
            (pssSlope / 20.0).coerceIn(0.0, 1.0)

        val sampleScore =
            ((sampleCount - MIN_SAMPLES + 1) / 8.0)
                .coerceIn(0.0, 1.0)

        val growthScore =
            (pssGrowthMb / 30.0).coerceIn(0.0, 1.0)

        val dirtyGrowthScore =
            (dirtyGrowthMb / 10.0).coerceIn(0.0, 1.0)

        val dirtyScore =
            (dirtyGrowthScore + dirtyMonotonicity.coerceIn(0.0, 1.0)) / 2.0

        return (
                0.35 * pssMonotonicity +
                        0.25 * slopeScore +
                        0.15 * sampleScore +
                        0.15 * growthScore +
                        0.10 * dirtyScore
                ).coerceIn(0.0, 1.0)
    }

    companion object {
        private const val MIN_SAMPLES = 5
        private const val MAX_SAMPLES = 12

        private const val MIN_WINDOW_MS = 30L * 60L * 1000L
        private const val MAX_TIME_DRIFT_MS = 2L * 60L * 1000L

        private const val MIN_NET_GROWTH_MB = 5.0
        private const val MIN_SLOPE_MB_PER_HOUR = 5.0
        private const val MIN_MONOTONICITY = 0.75

        private const val MIN_PRIVATE_DIRTY_GROWTH_MB = 2.0
        private const val MIN_PRIVATE_DIRTY_SLOPE = 2.0
        private const val MIN_PRIVATE_DIRTY_MONOTONICITY = 0.60

        private const val STRONG_PSS_GROWTH_MB = 15.0
        private const val STRONG_PSS_SLOPE = 10.0
        private const val STRONG_PSS_MONOTONICITY = 0.85

        private const val DEDUP_WINDOW_MS = 6L * 60L * 60L * 1000L
    }
}
