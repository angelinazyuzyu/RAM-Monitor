package com.example.rammonitor.ui

import android.app.ActivityManager
import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.example.rammonitor.R
import com.example.rammonitor.data.database.AppInfo
import com.example.rammonitor.data.database.AppDatabase
import com.example.rammonitor.data.repository.MonitorRepository
import com.example.rammonitor.monitoring.MemoryMonitor
import com.example.rammonitor.monitoring.MonitorWorker
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.chip.ChipGroup
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Job
import com.example.rammonitor.data.database.LeakSuspect
import com.example.rammonitor.data.database.ProcessSample
import android.content.Intent
import android.provider.Settings
import com.example.rammonitor.data.usage.AppUsageStatsProvider

class MainActivity : AppCompatActivity() {
    private lateinit var repository: MonitorRepository
    private lateinit var screenContainer: android.widget.FrameLayout
    private lateinit var bottomNavigation: BottomNavigationView
    private var allApps: List<AppInfo> = emptyList()
    private var selectedFilter = 0 // 0 all, 1 user, 2 system
    private var currentScreen = R.id.nav_home

    private var selectedAnalyticsDays = 1

    private var analyticsJob: Job? = null

    private var updatingPeriodToggle = false


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Сначала загружаем разметку
        setContentView(R.layout.activity_main)

        // Затем инициализируем компоненты интерфейса
        repository = MonitorRepository(this)
        screenContainer = findViewById(R.id.screenContainer)
        bottomNavigation = findViewById(R.id.bottomNavigation)

        bottomNavigation.setOnItemSelectedListener { item ->
            showScreen(item.itemId)
            true
        }

        // Открываем главный экран
        showScreen(R.id.nav_home)

        lifecycleScope.launch {
            val count = withContext(Dispatchers.IO) {
                repository.refreshInstalledApps()
            }

            allApps = withContext(Dispatchers.IO) {
                repository.getInstalledApps()
            }

            scheduleMonitoring()

            // Первый снимок после синхронизации приложений
            runCatching {
                withContext(Dispatchers.IO) {
                    MemoryMonitor(applicationContext).capture(
                        "app_start",
                        isForeground = true
                    )
                }
            }

            if (currentScreen == R.id.nav_home) {
                renderHome()
            }

            if (currentScreen == R.id.nav_apps) {
                renderApps()
            }
        }
    }


    override fun onDestroy() {
        super.onDestroy()
    }

    private fun scheduleMonitoring() {
        val request = PeriodicWorkRequestBuilder<MonitorWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(applicationContext).enqueueUniquePeriodicWork(
            "ram_monitor_periodic", ExistingPeriodicWorkPolicy.KEEP, request
        )
    }

    private fun showScreen(id: Int) {
        currentScreen = id
        screenContainer.removeAllViews()

        val layout = when (id) {
            R.id.nav_apps -> R.layout.screen_apps
            R.id.nav_analytics -> R.layout.screen_analytics
            R.id.nav_leaks -> R.layout.screen_leaks
            else -> R.layout.screen_home
        }

        screenContainer.addView(
            LayoutInflater.from(this)
                .inflate(layout, screenContainer, false)
        )

        when (id) {
            R.id.nav_apps -> renderApps()

            R.id.nav_analytics -> {
                setupAnalyticsPeriodToggle()
                renderAnalytics(1)
            }

            R.id.nav_leaks -> renderLeaks()

            else -> renderHome()
        }
    }

    private fun renderHome() {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        manager.getMemoryInfo(info)
        val total = info.totalMem / 1_048_576L
        val available = info.availMem / 1_048_576L
        val used = (total - available).coerceAtLeast(0)
        val percent = if (total > 0) (used * 100 / total).toInt() else 0
        screenContainer.findViewById<TextView>(R.id.tvUsedRam).text = "Используется: $used МБ ($percent%)"
        screenContainer.findViewById<TextView>(R.id.tvAvailableRam).text = "Свободно: $available МБ"
        screenContainer.findViewById<TextView>(R.id.tvTotalRam).text = "Всего: $total МБ"
        screenContainer.findViewById<ProgressBar>(R.id.ramProgress).progress = percent
        screenContainer.findViewById<TextView>(R.id.tvAppCount).text = allApps.size.toString()
        screenContainer.findViewById<TextView>(R.id.tvSnapshotCount).text = "…"
        lifecycleScope.launch {
            val pair = withContext(Dispatchers.IO) { repository.getSnapshotCount() to repository.getLatestHistory(1).firstOrNull() }
            if (currentScreen != R.id.nav_home) return@launch
            screenContainer.findViewById<TextView>(R.id.tvSnapshotCount).text = pair.first.toString()
            screenContainer.findViewById<TextView>(R.id.tvLastSnapshot).text = pair.second?.let {
                "${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it.captured_at))}\n" +
                    "Использовано ${it.used_ram_mb} МБ из ${it.total_ram_mb} МБ"
            } ?: "История пока не накоплена"
        }
        screenContainer.findViewById<View>(R.id.btnCapture).setOnClickListener { button ->
            button.isEnabled = false
            button.alpha = 0.6f

            lifecycleScope.launch {
                val result = runCatching {
                    withContext(Dispatchers.IO) {
                        MemoryMonitor(applicationContext).capture(
                            "manual",
                            isForeground = true
                        )
                    }
                }

                if (currentScreen != R.id.nav_home) return@launch

                result.onSuccess {
                    renderHome()
                }.onFailure { error ->
                    button.isEnabled = true
                    button.alpha = 1f

                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Ошибка измерения")
                        .setMessage(error.message ?: "Не удалось сохранить снимок памяти.")
                        .setPositiveButton("ОК", null)
                        .show()
                }
            }
        }
    }

    private fun renderApps() {
        val recycler = screenContainer.findViewById<RecyclerView>(R.id.appsRecyclerView)
        val adapter = AppAdapter(::showAppDetails)
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter
        val search = screenContainer.findViewById<TextInputEditText>(R.id.etAppSearch)
        val filters = screenContainer.findViewById<ChipGroup>(R.id.appFilters)
        fun applyFilter() {
            val query = search.text?.toString()?.trim().orEmpty()
            val filtered = allApps.filter { app ->
                val typeMatches = when (selectedFilter) { 1 -> !app.is_system; 2 -> app.is_system; else -> true }
                typeMatches && (query.isEmpty() || app.app_label.contains(query, true) || app.package_name.contains(query, true))
            }
            adapter.submitList(filtered)
            screenContainer.findViewById<TextView>(R.id.tvAppsResultCount).text = "Найдено: ${filtered.size}"
        }

        filters.setOnCheckedStateChangeListener { _, checked ->
            selectedFilter = when (checked.firstOrNull()) { R.id.chipUser -> 1; R.id.chipSystem -> 2; else -> 0 }
            applyFilter()
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = applyFilter()
            override fun afterTextChanged(s: Editable?) {}
        })
        applyFilter()
    }

    private fun showAppDetails(app: AppInfo) {

        if (!AppUsageStatsProvider.hasPermission(this)) {

            AlertDialog.Builder(this)
                .setTitle("Доступ к статистике использования")
                .setMessage(
                    "Чтобы RAM Monitor мог показывать время использования " +
                            "приложений, необходимо предоставить специальный доступ Android.\n\n" +
                            "На следующем экране найди RAM Monitor и разреши доступ."
                )
                .setNegativeButton("Позже", null)
                .setPositiveButton("Открыть настройки") { _, _ ->
                    try {
                        startActivity(
                            Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                        )
                    } catch (e: Exception) {
                        AlertDialog.Builder(this)
                            .setMessage("Не удалось открыть настройки доступа.")
                            .setPositiveButton("Закрыть", null)
                            .show()
                    }
                }
                .show()

            return
        }

        lifecycleScope.launch {

            val result = withContext(Dispatchers.IO) {

                val dao = AppDatabase
                    .getInstance(applicationContext)
                    .monitorDao()

                val metrics = dao
                    .getProcessMetricsForApp(app.app_id)
                    .lastOrNull()

                val usage = AppUsageStatsProvider.getUsageForLastDays(
                    applicationContext,
                    app.package_name,
                    7
                )

                Pair(metrics, usage)
            }

            val metrics = result.first
            val usage = result.second

            val details = buildString {

                append("Пакет: ${app.package_name}\n")
                append(
                    "Тип: ${if (app.is_system) "Системное" else "Пользовательское"}\n"
                )
                append("Версия: ${app.version_name ?: "неизвестна"}\n\n")

                append("СТАТИСТИКА ИСПОЛЬЗОВАНИЯ\n")
                append("Период: последние 7 дней\n")

                if (usage != null) {

                    val hours = usage.totalTimeMs / 3_600_000
                    val minutes = (usage.totalTimeMs / 60_000) % 60

                    append("Время использования: ${hours} ч ${minutes} мин\n")

                    if (usage.lastTimeUsed > 0L) {
                        val date = DateFormat
                            .getDateTimeInstance()
                            .format(usage.lastTimeUsed)

                        append("Последнее использование: $date\n")
                    } else {
                        append("Последнее использование: нет данных\n")
                    }

                } else {
                    append("Статистика пока отсутствует\n")
                }

                append("\nМЕТРИКИ ПАМЯТИ\n")

                if (metrics != null) {
                    append("PSS: ${metrics.pss_kb / 1024f} МБ\n")
                    append("RSS: ${metrics.rss_kb / 1024f} МБ\n")
                    append("Java Heap: ${metrics.java_heap_kb / 1024f} МБ\n")
                    append("Native Heap: ${metrics.native_heap_kb / 1024f} МБ\n")
                    append("Private Dirty: ${metrics.private_dirty_kb / 1024f} МБ")
                } else {
                    append(
                        "Метрики памяти чужого процесса недоступны. " +
                                "Android ограничивает их получение."
                    )
                }
            }

            AlertDialog.Builder(this@MainActivity)
                .setTitle(app.app_label)
                .setMessage(details)
                .setPositiveButton("Закрыть", null)
                .show()
        }
    }

    private fun setupAnalyticsPeriodToggle() {

        val periodToggle =
            screenContainer.findViewById<MaterialButtonToggleGroup>(
                R.id.periodToggle
            )

        periodToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->

            if (!isChecked || updatingPeriodToggle) {
                return@addOnButtonCheckedListener
            }

            val selectedDays = when (checkedId) {
                R.id.periodWeek -> 7
                R.id.periodMonth -> 30
                else -> 1
            }

            if (selectedDays == selectedAnalyticsDays) {
                return@addOnButtonCheckedListener
            }

            selectedAnalyticsDays = selectedDays

            loadAnalyticsData(selectedDays)
        }
    }

    private fun renderAnalytics(days: Int) {

        selectedAnalyticsDays = days

        val periodToggle =
            screenContainer.findViewById<MaterialButtonToggleGroup>(
                R.id.periodToggle
            )

        val selectedButton = when (days) {
            7 -> R.id.periodWeek
            30 -> R.id.periodMonth
            else -> R.id.periodDay
        }

        if (periodToggle.checkedButtonId != selectedButton) {

            updatingPeriodToggle = true

            periodToggle.check(selectedButton)

            updatingPeriodToggle = false
        }

        loadAnalyticsData(days)
    }

    private fun loadAnalyticsData(days: Int) {

        analyticsJob?.cancel()

        analyticsJob = lifecycleScope.launch {

            val result = withContext(Dispatchers.IO) {
                val history = repository.getHistoryForLastDays(days)

                val dao = AppDatabase.getInstance(applicationContext).monitorDao()

                val app = dao.getAppByPackage(packageName)

                val now = System.currentTimeMillis()
                val from = now - days.toLong() * 24L * 60L * 60L * 1000L

                val allMetrics = if (app != null) {
                    dao.getProcessMetricsForAppInPeriod(
                        app.app_id,
                        from,
                        now
                    )
                } else {
                    emptyList()
                }

                // Оставляем только последний PID, чтобы не смешивать
                // показатели разных запусков приложения.
                val latestPid = allMetrics.lastOrNull()?.pid

                val processMetrics = allMetrics
                    .filter { it.pid == latestPid }
                    .sortedBy { it.uptime_ms }

                Triple(history, processMetrics, app != null)
            }

            if (currentScreen != R.id.nav_analytics) {
                return@launch
            }

            val history = result.first
            val processMetrics = result.second

            // График общей RAM устройства
            screenContainer
                .findViewById<MemoryChartView>(R.id.memoryChart)
                .setSnapshots(history)

            val hint = screenContainer.findViewById<TextView>(
                R.id.tvChartHint
            )

            hint.text = if (history.size < 2) {
                "Недостаточно измерений: ${history.size}. Оставьте мониторинг включённым."
            } else {
                "Измерений: ${history.size}"
            }

            // График PSS и RSS
            val processChart = screenContainer
                .findViewById<ProcessMemoryChartView>(R.id.processMemoryChart)

            val processSelected = screenContainer
                .findViewById<TextView>(R.id.tvProcessSelected)

            processChart.onMetricSelected = { metric ->
                processSelected.text = metric?.let {
                    "PSS: %.1f МБ\nRSS: %.1f МБ\nPrivate Dirty: %.1f МБ\nPID: %d".format(
                        java.util.Locale.getDefault(),
                        it.pss_kb / 1024f,
                        it.rss_kb / 1024f,
                        it.private_dirty_kb / 1024f,
                        it.pid
                    )
                } ?: "Коснитесь графика, чтобы посмотреть значения"
            }

            processChart.setMetrics(processMetrics, false)

            screenContainer.findViewById<TextView>(
                R.id.tvProcessChartHint
            ).text = if (processMetrics.size < 2) {
                "Недостаточно замеров процесса: ${processMetrics.size}"
            } else {
                "Замеров: ${processMetrics.size} · PSS и RSS, МБ"
            }

            // График Java Heap, Native Heap и Graphics
            val breakdownChart = screenContainer
                .findViewById<ProcessMemoryChartView>(R.id.processBreakdownChart)

            val breakdownSelected = screenContainer
                .findViewById<TextView>(R.id.tvBreakdownSelected)

            breakdownChart.onMetricSelected = { metric ->
                breakdownSelected.text = metric?.let {
                    "Java Heap: %.1f МБ\nNative Heap: %.1f МБ\nGraphics: %.1f МБ\nPrivate Dirty: %.1f МБ".format(
                        java.util.Locale.getDefault(),
                        it.java_heap_kb / 1024f,
                        it.native_heap_kb / 1024f,
                        it.graphics_kb / 1024f,
                        it.private_dirty_kb / 1024f
                    )
                } ?: "Коснитесь графика, чтобы посмотреть значения"
            }

            breakdownChart.setMetrics(processMetrics, true)

            screenContainer.findViewById<TextView>(
                R.id.tvBreakdownChartHint
            ).text = if (processMetrics.size < 2) {
                "Недостаточно замеров для анализа структуры памяти"
            } else {
                "Java Heap · Native Heap · Graphics, МБ"
            }

            // Сводка общей памяти устройства
            val avg = if (history.isEmpty()) {
                0
            } else {
                history.map { it.used_ram_mb }.average().toInt()
            }

            val maxValue = history.maxOfOrNull {
                it.used_ram_mb
            } ?: 0

            val minValue = history.minOfOrNull {
                it.used_ram_mb
            } ?: 0

            // Сводка собственного процесса
            val avgPss = processMetrics
                .map { it.pss_kb }
                .averageOrNullKb()

            val maxPss = processMetrics
                .maxOfOrNull { it.pss_kb } ?: 0

            val avgJava = processMetrics
                .map { it.java_heap_kb }
                .averageOrNullKb()

            val avgNative = processMetrics
                .map { it.native_heap_kb }
                .averageOrNullKb()

            val avgGraphics = processMetrics
                .map { it.graphics_kb }
                .averageOrNullKb()

            screenContainer
                .findViewById<TextView>(R.id.tvAnalyticsSummary)
                .text = buildString {

                append("ОБЩАЯ ПАМЯТЬ УСТРОЙСТВА\n")
                append("Количество снимков: ${history.size}\n")
                append("Среднее использование: $avg МБ\n")
                append("Минимальное использование: $minValue МБ\n")
                append("Максимальное использование: $maxValue МБ\n\n")

                append("ПРОЦЕСС RAM MONITOR\n")
                append("Количество замеров: ${processMetrics.size}\n")

                if (processMetrics.isNotEmpty()) {
                    append("Средний PSS: $avgPss МБ\n")
                    append("Максимальный PSS: ${maxPss / 1024f} МБ\n")
                    append("Средний Java Heap: $avgJava МБ\n")
                    append("Средний Native Heap: $avgNative МБ\n")
                    append("Средний Graphics: $avgGraphics МБ")
                } else {
                    append("Метрики процесса пока отсутствуют.")
                }
            }
        }
    }

    private fun List<Int>.averageOrNullKb(): String {
        if (isEmpty()) return "—"

        return "%.1f".format(
            java.util.Locale.getDefault(),
            average() / 1024.0
        )
    }

    private suspend fun renderLeakMonitoringStatus(
        container: LinearLayout
    ) {
        val dao = AppDatabase.getInstance(applicationContext).monitorDao()

        // Находим запись собственного приложения
        val monitoringData = withContext(Dispatchers.IO) {
            val app = dao.getAppByPackage(packageName)

            if (app == null) {
                null
            } else {
                val samples = dao.getProcessSamplesForApp(app.app_id)
                app to samples
            }
        }

        if (currentScreen != R.id.nav_leaks) return

        val app = monitoringData?.first
        val allSamples = monitoringData?.second.orEmpty()

        // Выбираем только последний PID
        val latestPid = allSamples
            .maxByOrNull { it.captured_at }
            ?.pid

        val samples = allSamples
            .filter { it.pid == latestPid }
            .sortedBy { it.captured_at }
            .takeLast(12)

        val sampleCount = samples.size
        val firstTime = samples.firstOrNull()?.captured_at ?: 0L
        val lastTime = samples.lastOrNull()?.captured_at ?: 0L

        val durationMs = (lastTime - firstTime).coerceAtLeast(0L)
        val durationMinutes = durationMs / 60_000L

        val minimumSamples = 5
        val minimumDurationMs = 30L * 60L * 1000L

        val enoughSamples = sampleCount >= minimumSamples
        val enoughDuration = durationMs >= minimumDurationMs
        val ready = enoughSamples && enoughDuration

        val progress = (sampleCount * 100 / minimumSamples)
            .coerceIn(0, 100)

        val card = com.google.android.material.card.MaterialCardView(this).apply {
            radius = 18f
            cardElevation = 2f
            setCardBackgroundColor(android.graphics.Color.WHITE)
        }

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18, 16, 18, 16)
        }

        body.addView(TextView(this).apply {
            text = "Состояние мониторинга"
            textSize = 17f
            setTextColor(0xFF17243A.toInt())
            setTypeface(null, android.graphics.Typeface.BOLD)
        })

        body.addView(TextView(this).apply {
            text = if (ready) {
                "Данные готовы к анализу"
            } else {
                "Сбор данных продолжается"
            }
            textSize = 13f
            setTextColor(
                if (ready) 0xFF15803D.toInt()
                else 0xFFD97706.toInt()
            )
            setPadding(0, 6, 0, 12)
        })

        body.addView(TextView(this).apply {
            text = "Приложение: ${app?.app_label ?: "RAM Monitor"}\n" +
                    "PID процесса: ${latestPid ?: "ожидание первого замера"}\n" +
                    "Замеров текущего процесса: $sampleCount из $minimumSamples"
            textSize = 13f
            setTextColor(0xFF526176.toInt())
            setLineSpacing(4f, 1f)
        })

        val progressBar = ProgressBar(
            this,
            null,
            android.R.attr.progressBarStyleHorizontal
        ).apply {
            max = 100
            this.progress = progress
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (7 * resources.displayMetrics.density).toInt()
            ).apply {
                topMargin = (12 * resources.displayMetrics.density).toInt()
                bottomMargin = (12 * resources.displayMetrics.density).toInt()
            }
        }

        body.addView(progressBar)

        val durationText = when {
            sampleCount < 2 -> "недостаточно данных"
            durationMinutes < 60 -> "$durationMinutes мин."
            else -> {
                val hours = durationMinutes / 60
                val minutes = durationMinutes % 60
                if (minutes == 0L) "$hours ч."
                else "$hours ч. $minutes мин."
            }
        }

        body.addView(TextView(this).apply {
            text = "Период наблюдения: $durationText"
            textSize = 13f
            setTextColor(0xFF526176.toInt())
        })

        val explanation = when {
            app == null ->
                "Запись приложения пока отсутствует. Выполните сбор метрик."

            sampleCount == 0 ->
                "Замеры собственного процесса ещё не сохранены. Выполните ручной снимок памяти на главном экране."

            !enoughSamples ->
                "Нужно ещё ${minimumSamples - sampleCount} замеров для первичного анализа."

            !enoughDuration ->
                "Количество замеров достаточное, но период наблюдения должен составить не менее 30 минут."

            else ->
                "Минимальные условия выполнены. Алгоритм проверяет рост PSS, скорость изменения и устойчивость показателей. Отсутствие подозрений означает, что критерии обнаружения пока не выполнены."
        }

        body.addView(TextView(this).apply {
            text = explanation
            textSize = 12f
            setTextColor(0xFF64748B.toInt())
            setPadding(0, 10, 0, 0)
            setLineSpacing(3f, 1f)
        })

        card.addView(body)

        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            bottomMargin = (16 * resources.displayMetrics.density).toInt()
        }

        container.addView(card, 0, params)
    }

    private fun renderLeaks() {
        lifecycleScope.launch {
            val suspects = withContext(Dispatchers.IO) {
                repository.getAllLeakSuspects()
            }

            if (currentScreen != R.id.nav_leaks) return@launch

            val container = screenContainer.findViewById<LinearLayout>(R.id.leaksContainer)
            val empty = screenContainer.findViewById<TextView>(R.id.tvLeakEmpty)

            container.removeAllViews()
            empty.visibility = if (suspects.isEmpty()) View.VISIBLE else View.GONE

// Показываем состояние мониторинга даже при отсутствии подозрений
            renderLeakMonitoringStatus(container)

            if (currentScreen != R.id.nav_leaks) return@launch

            val dao = AppDatabase.getInstance(applicationContext).monitorDao()

            suspects.forEach { suspect ->
                // Получаем приложение и его замеры
                val data = withContext(Dispatchers.IO) {
                    val app = dao.getAppById(suspect.app_id)
                    val samples = dao.getProcessSamplesForApp(suspect.app_id)

                    val breakdown = dao.getMemoryBreakdownForAppInPeriod(
                        suspect.app_id,
                        suspect.window_start,
                        suspect.window_end
                    )

                    Triple(app, samples, breakdown)
                }

                if (currentScreen != R.id.nav_leaks) return@launch

                val app = data.first
                val allSamples = data.second
                val allBreakdownSamples = data.third

                // Берём замеры только из периода подозрения
                val windowSamples = allSamples
                    .filter {
                        it.captured_at >= suspect.window_start &&
                                it.captured_at <= suspect.window_end
                    }
                    .groupBy { it.pid }
                    .values
                    .maxByOrNull { it.size }
                    .orEmpty()
                    .sortedBy { it.uptime_ms }

                val selectedPid = windowSamples.firstOrNull()?.pid

                val breakdownSamples = allBreakdownSamples
                    .filter { it.pid == selectedPid }
                    .sortedBy { it.uptime_ms }

                // Рассчитываем фактическое изменение PSS
                val growthMb = if (windowSamples.size >= 2) {
                    (
                            windowSamples.last().pss_kb -
                                    windowSamples.first().pss_kb
                            ) / 1024f
                } else {
                    null
                }

                val durationMs = (
                        suspect.window_end - suspect.window_start
                        ).coerceAtLeast(0L)

                val durationMinutes = durationMs / 60_000L
                val durationText = when {
                    durationMinutes < 60 -> "$durationMinutes мин."
                    else -> {
                        val hours = durationMinutes / 60
                        val minutes = durationMinutes % 60
                        if (minutes == 0L) "$hours ч."
                        else "$hours ч. $minutes мин."
                    }
                }

                val card = com.google.android.material.card.MaterialCardView(this@MainActivity).apply {
                    radius = 18f
                    cardElevation = 2f
                    setCardBackgroundColor(android.graphics.Color.WHITE)
                }

                val body = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(18, 16, 18, 16)
                }

                // Название приложения
                body.addView(TextView(this@MainActivity).apply {
                    text = app?.app_label ?: "Неизвестное приложение"
                    textSize = 17f
                    setTextColor(0xFF17243A.toInt())
                    setTypeface(null, android.graphics.Typeface.BOLD)
                })

                // Статус
                body.addView(TextView(this@MainActivity).apply {
                    text = if (suspect.acknowledged) {
                        "ПОДОЗРЕНИЕ ПРОСМОТРЕНО"
                    } else {
                        "НОВОЕ ПОДОЗРЕНИЕ"
                    }
                    textSize = 11f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setTextColor(
                        if (suspect.acknowledged) 0xFF64748B.toInt()
                        else 0xFFD97706.toInt()
                    )
                    setPadding(0, 8, 0, 8)
                })

                // Основные показатели
                val growthText = growthMb?.let {
                    "%+.1f МБ".format(java.util.Locale.getDefault(), it)
                } ?: "Недостаточно данных"

                body.addView(TextView(this@MainActivity).apply {
                    text = buildString {
                        append("Изменение PSS: $growthText\n")
                        append(
                            "Скорость роста: %.1f МБ/ч\n".format(
                                java.util.Locale.getDefault(),
                                suspect.slope_mb_per_hour
                            )
                        )
                        append("Период наблюдения: $durationText\n")
                        append("Количество замеров: ${suspect.samples_count}\n")
                        append(
                            "Монотонность: %.0f%%\n".format(
                                java.util.Locale.getDefault(),
                                suspect.monotonicity * 100
                            )
                        )
                        append(
                            "Уверенность алгоритма: %.0f%%".format(
                                java.util.Locale.getDefault(),
                                suspect.confidence * 100
                            )
                        )
                    }
                    textSize = 13f
                    setTextColor(0xFF526176.toInt())
                    setLineSpacing(5f, 1f)
                })

                // Время обнаружения
                body.addView(TextView(this@MainActivity).apply {
                    text = "Обнаружено: " + java.text.SimpleDateFormat(
                        "dd.MM.yyyy HH:mm",
                        java.util.Locale.getDefault()
                    ).format(java.util.Date(suspect.detected_at))

                    textSize = 11f
                    setTextColor(0xFF94A3B8.toInt())
                    setPadding(0, 10, 0, 10)
                })

                // Кнопка отметки
                val button = com.google.android.material.button.MaterialButton(
                    this@MainActivity
                ).apply {
                    text = if (suspect.acknowledged) {
                        "Снять отметку"
                    } else {
                        "Отметить просмотренным"
                    }

                    setOnClickListener {
                        lifecycleScope.launch {
                            withContext(Dispatchers.IO) {
                                repository.acknowledgeLeakSuspect(
                                    suspect.suspect_id,
                                    !suspect.acknowledged
                                )
                            }

                            renderLeaks()
                        }
                    }
                }

                body.addView(button)
                card.addView(body)

// Нажатие на карточку открывает подробности.
// Кнопка внутри карточки продолжает работать отдельно.
                card.setOnClickListener {
                    showLeakDetails(
                        appLabel = app?.app_label ?: "Неизвестное приложение",
                        suspect = suspect,
                        samples = windowSamples,
                        breakdownSamples = breakdownSamples
                    )
                }


                val params = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = 12
                }

                container.addView(card, params)
            }
        }
    }

    private fun showLeakDetails(
        appLabel: String,
        suspect: com.example.rammonitor.data.database.LeakSuspect,
        samples: List<com.example.rammonitor.data.database.ProcessSample>,
        breakdownSamples: List<com.example.rammonitor.data.database.ProcessMemoryBreakdownSample>
    ) {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 12, 20, 8)
        }

        content.addView(TextView(this).apply {
            text = "Динамика PSS"
            textSize = 16f
            setTextColor(0xFF17243A.toInt())
            setTypeface(null, android.graphics.Typeface.BOLD)
        })

        val chart = LeakChartView(this).apply {
            setSamples(samples)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (220 * resources.displayMetrics.density).toInt()
            )
        }
        content.addView(chart)

        val first = samples.firstOrNull()
        val last = samples.lastOrNull()

        val details = buildString {
            append("Приложение: $appLabel\n")
            append("Замеров в графике: ${samples.size}\n")

            if (first != null && last != null) {
                val startMb = first.pss_kb / 1024f
                val endMb = last.pss_kb / 1024f
                val difference = endMb - startMb

                append("Начальный PSS: %.1f МБ\n".format(
                    java.util.Locale.getDefault(), startMb
                ))
                append("Конечный PSS: %.1f МБ\n".format(
                    java.util.Locale.getDefault(), endMb
                ))
                append("Изменение: %+.1f МБ\n".format(
                    java.util.Locale.getDefault(), difference
                ))
            }

            append("Скорость роста: %.1f МБ/ч\n".format(
                java.util.Locale.getDefault(), suspect.slope_mb_per_hour
            ))
            append("Монотонность: %.0f%%\n".format(
                java.util.Locale.getDefault(), suspect.monotonicity * 100
            ))
            append("Уверенность алгоритма: %.0f%%".format(
                java.util.Locale.getDefault(), suspect.confidence * 100
            ))
        }


        content.addView(TextView(this).apply {
            text = "Диагностика причин роста памяти"
            textSize = 16f
            setTextColor(0xFF17243A.toInt())
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 16, 0, 8)
        })

        val diagnosis = diagnoseMemoryGrowth(samples)

        content.addView(TextView(this).apply {
            text = "Анализ областей памяти"
            textSize = 16f
            setTextColor(0xFF17243A.toInt())
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 18, 0, 8)
        })

        content.addView(TextView(this).apply {
            text = buildMemoryBreakdownDiagnosis(breakdownSamples)
            textSize = 13f
            setTextColor(0xFF526176.toInt())
            setLineSpacing(5f, 1f)
            setPadding(0, 0, 0, 12)
        })

        content.addView(TextView(this).apply {
            text = diagnosis
            textSize = 13f
            setTextColor(0xFF526176.toInt())
            setLineSpacing(5f, 1f)
            setPadding(0, 0, 0, 12)
        })


        AlertDialog.Builder(this)
            .setTitle("Подробности подозрения")
            .setView(content)
            .setPositiveButton("Закрыть", null)
            .show()
    }

    private fun diagnoseMemoryGrowth(
        samples: List<com.example.rammonitor.data.database.ProcessSample>
    ): String {
        val data = samples.sortedBy { it.uptime_ms }

        if (data.size < 5) {
            return """
            Недостаточно данных

            Для диагностики необходимо не менее 5 замеров.
            Продолжите мониторинг, чтобы алгоритм мог определить характер изменения памяти.
        """.trimIndent()
        }

        val first = data.first()
        val last = data.last()

        val pssGrowthMb = (last.pss_kb - first.pss_kb) / 1024f
        val dirtyGrowthMb =
            (last.private_dirty_kb - first.private_dirty_kb) / 1024f

        val pssValues = data.map { it.pss_kb }
        val dirtyValues = data.map { it.private_dirty_kb }

        val pssPeakMb = (pssValues.maxOrNull()!! - first.pss_kb) / 1024f
        val pssEndFromPeakMb =
            (pssValues.maxOrNull()!! - last.pss_kb) / 1024f

        fun calculateMonotonicity(values: List<Int>): Float {
            var increases = 0
            var decreases = 0

            for (i in 1 until values.size) {
                val difference = values[i] - values[i - 1]

                if (difference > 256) increases++
                else if (difference < -256) decreases++
            }

            val significantChanges = increases + decreases

            return if (significantChanges == 0) {
                0.5f
            } else {
                increases.toFloat() / significantChanges
            }
        }

        val pssMonotonicity = calculateMonotonicity(pssValues)
        val dirtyMonotonicity = calculateMonotonicity(dirtyValues)

        val durationHours =
            (last.captured_at - first.captured_at) / 3_600_000f

        val pssSlope = if (durationHours > 0f) {
            pssGrowthMb / durationHours
        } else {
            0f
        }

        val dirtySlope = if (durationHours > 0f) {
            dirtyGrowthMb / durationHours
        } else {
            0f
        }

        return when {
            // Память выросла, но затем заметно освободилась
            pssPeakMb >= 5f &&
                    pssEndFromPeakMb >= pssPeakMb * 0.4f &&
                    pssGrowthMb < pssPeakMb * 0.6f -> {
                """
                Обнаружен временный скачок памяти

                PSS достигал значения на ${"%.1f".format(java.util.Locale.getDefault(), pssPeakMb)} МБ выше начального,
                но к последнему замеру часть памяти освободилась.

                Возможное объяснение:
                • временные объекты;
                • загрузка изображений или данных;
                • работа сборщика мусора;
                • кратковременные операции с большими буферами.

                Рекомендация: продолжить наблюдение и проверить, повторяются ли такие скачки.
            """.trimIndent()
            }

            // Растут оба показателя
            pssGrowthMb >= 5f &&
                    dirtyGrowthMb >= 2f &&
                    dirtyMonotonicity >= 0.6f -> {
                """
                Устойчивый рост частной памяти

                PSS увеличился на ${"%.1f".format(java.util.Locale.getDefault(), pssGrowthMb)} МБ,
                Private Dirty — на ${"%.1f".format(java.util.Locale.getDefault(), dirtyGrowthMb)} МБ.

                Возможные причины:
                • объекты остаются достижимыми и не освобождаются;
                • накапливаются данные в коллекциях или кэшах;
                • увеличивается объём native-аллокаций;
                • ресурсы не освобождаются после использования.

                Скорость роста Private Dirty:
                ${"%.1f".format(java.util.Locale.getDefault(), dirtySlope)} МБ/ч.

                Рекомендация: проверить жизненный цикл объектов, кэши,
                обработку ресурсов и native-код.
            """.trimIndent()
            }

            // PSS растёт, Private Dirty почти не меняется
            pssGrowthMb >= 5f && dirtyGrowthMb < 2f -> {
                """
                Рост PSS без сопоставимого роста Private Dirty

                PSS увеличился на ${"%.1f".format(java.util.Locale.getDefault(), pssGrowthMb)} МБ,
                а Private Dirty изменился на ${"%.1f".format(java.util.Locale.getDefault(), dirtyGrowthMb)} МБ.

                Возможные объяснения:
                • изменение объёма разделяемой памяти;
                • отображённые в память файлы и библиотеки;
                • графические или другие системные ресурсы;
                • изменение состава разделяемых страниц.

                Это не обязательно означает утечку частной памяти.

                Рекомендация: проверить графику, большие файлы,
                отображённые области памяти и характер дальнейшего роста.
            """.trimIndent()
            }

            // Private Dirty растёт, но общий PSS пока не показывает сильного роста
            dirtyGrowthMb >= 2f && pssGrowthMb < 5f -> {
                """
                Наблюдается рост Private Dirty

                Private Dirty увеличился на ${"%.1f".format(java.util.Locale.getDefault(), dirtyGrowthMb)} МБ,
                PSS — на ${"%.1f".format(java.util.Locale.getDefault(), pssGrowthMb)} МБ.

                Возможные причины:
                • накопление частных страниц памяти;
                • рост Java или Native Heap;
                • увеличение объёма изменённых страниц.

                Рекомендация: продолжить наблюдение.
                Пока недостаточно оснований утверждать, что происходит утечка.
            """.trimIndent()
            }

            pssGrowthMb <= -2f && dirtyGrowthMb <= 0f -> {
                """
                Наблюдается снижение использования памяти
        
                PSS уменьшился на ${"%.1f".format(java.util.Locale.getDefault(), -pssGrowthMb)} МБ.
        
                Это может быть связано с освобождением объектов,
                очисткой кэша или работой сборщика мусора.
        
                Признаков продолжающегося роста по этим замерам нет.
            """.trimIndent()
            }

            // Слишком мало устойчивости в изменениях
            pssMonotonicity < 0.5f -> {
                """
                Нестабильное использование памяти

                Показатель PSS часто менялся в обе стороны.
                Монотонность роста: ${"%.0f".format(java.util.Locale.getDefault(), pssMonotonicity * 100)}%.

                Возможные причины:
                • периодические загрузки и освобождения данных;
                • работа сборщика мусора;
                • временные буферы и кэши;
                • изменение нагрузки приложения.

                Рекомендация: увеличить период наблюдения и проверить,
                повторяется ли рост при одинаковых сценариях использования.
            """.trimIndent()
            }

            else -> {
                """
                Явная причина роста не определена

                PSS изменился на ${"%.1f".format(java.util.Locale.getDefault(), pssGrowthMb)} МБ,
                Private Dirty — на ${"%.1f".format(java.util.Locale.getDefault(), dirtyGrowthMb)} МБ.

                Текущих данных недостаточно для выбора конкретного сценария.

                Рекомендация: продолжить мониторинг и сравнить показатели
                при повторном использовании приложения.
            """.trimIndent()
            }
        }
    }


    private fun buildMemoryBreakdownDiagnosis(
        samples: List<com.example.rammonitor.data.database.ProcessMemoryBreakdownSample>
    ): String {
        if (samples.size < 2) {
            return "Недостаточно замеров для сравнения областей памяти."
        }

        val first = samples.first()
        val last = samples.last()

        fun toMb(value: Int): Float = value / 1024f

        val javaGrowth = toMb(last.java_heap_kb - first.java_heap_kb)
        val nativeGrowth = toMb(last.native_heap_kb - first.native_heap_kb)
        val graphicsGrowth = toMb(last.graphics_kb - first.graphics_kb)
        val dirtyGrowth = toMb(last.private_dirty_kb - first.private_dirty_kb)
        val pssGrowth = toMb(last.pss_kb - first.pss_kb)

        val changes = listOf(
            "Java Heap" to javaGrowth,
            "Native Heap" to nativeGrowth,
            "Graphics" to graphicsGrowth,
            "Private Dirty" to dirtyGrowth
        )

        val largest = changes.maxByOrNull { it.second }
        val positiveChanges = changes.filter { it.second >= 1f }

        val result = buildString {
            append("Изменение показателей за период:\n\n")
            append("Java Heap: %+.1f МБ\n".format(java.util.Locale.getDefault(), javaGrowth))
            append("Native Heap: %+.1f МБ\n".format(java.util.Locale.getDefault(), nativeGrowth))
            append("Graphics: %+.1f МБ\n".format(java.util.Locale.getDefault(), graphicsGrowth))
            append("Private Dirty: %+.1f МБ\n".format(java.util.Locale.getDefault(), dirtyGrowth))
            append("PSS: %+.1f МБ\n\n".format(java.util.Locale.getDefault(), pssGrowth))

            when {
                pssGrowth < 1f && positiveChanges.isEmpty() -> {
                    append("Заметного роста отдельных областей памяти не обнаружено.")
                }

                largest == null || largest.second < 1f -> {
                    append("Рост отдельных областей памяти незначителен.")
                }

                else -> {
                    append("Наибольший положительный прирост: ${largest.first} ")
                    append("(+%.1f МБ).\n\n".format(
                        java.util.Locale.getDefault(),
                        largest.second
                    ))

                    when (largest.first) {
                        "Java Heap" -> append(
                            "Основной прирост наблюдается в Java Heap. " +
                                    "Стоит проверить накопление объектов, коллекции, " +
                                    "кэши и ссылки на Activity или Context."
                        )

                        "Native Heap" -> append(
                            "Основной прирост наблюдается в Native Heap. " +
                                    "Стоит проверить JNI, нативные библиотеки, " +
                                    "буферы и освобождение native-ресурсов."
                        )

                        "Graphics" -> append(
                            "Основной прирост наблюдается в Graphics. " +
                                    "Стоит проверить Bitmap, текстуры, графические " +
                                    "буферы и своевременное освобождение ресурсов."
                        )

                        "Private Dirty" -> append(
                            "Наибольший прирост наблюдается в Private Dirty. " +
                                    "Это указывает на увеличение частных физических " +
                                    "страниц, но не определяет конкретный источник."
                        )
                    }
                }
            }

            append("\n\nЭто эвристическая оценка по двум конечным замерам. " +
                    "Она не доказывает утечку и не определяет конкретный объект-виновник.")
        }

        return result
    }

}
