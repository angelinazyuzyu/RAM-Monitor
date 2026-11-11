

package com.example.rammonitor.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.example.rammonitor.data.database.ProcessMetric
import kotlin.math.max
import kotlin.math.roundToInt

class ProcessMemoryChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private data class Series(
        val name: String,
        val color: Int,
        val values: List<Float>
    )

    private val density = resources.displayMetrics.density

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 11f * density
        color = 0xFF64748B.toInt()
    }

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFE3E8EF.toInt()
        strokeWidth = 1f * density
    }

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val legendPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 3f * density
        strokeCap = Paint.Cap.ROUND
    }

    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF17243A.toInt()
        strokeWidth = 1.5f * density
        style = Paint.Style.STROKE
    }

    private val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private var metrics: List<ProcessMetric> = emptyList()
    private var breakdownMode = false
    private var selectedIndex = -1

    var onMetricSelected: ((ProcessMetric?) -> Unit)? = null

    fun setMetrics(
        items: List<ProcessMetric>,
        showBreakdown: Boolean
    ) {
        metrics = items.sortedBy { it.uptime_ms }
        breakdownMode = showBreakdown
        selectedIndex = -1
        onMetricSelected?.invoke(null)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val left = paddingLeft + 42f * density
        val right = width - paddingRight - 10f * density
        val top = paddingTop + 42f * density
        val bottom = height - paddingBottom - 28f * density

        if (right <= left || bottom <= top) return

        val series = if (breakdownMode) {
            listOf(
                Series("Java", 0xFF269B78.toInt(),
                    metrics.map { it.java_heap_kb / 1024f }),
                Series("Native", 0xFF4385D1.toInt(),
                    metrics.map { it.native_heap_kb / 1024f }),
                Series("Graphics", 0xFFE59A36.toInt(),
                    metrics.map { it.graphics_kb / 1024f })
            )
        } else {
            listOf(
                Series("PSS", 0xFF269B78.toInt(),
                    metrics.map { it.pss_kb / 1024f }),
                Series("RSS", 0xFF4385D1.toInt(),
                    metrics.map { it.rss_kb / 1024f })
            )
        }

        var legendX = left
        series.forEach { item ->
            legendPaint.color = item.color
            canvas.drawLine(
                legendX, 16f * density,
                legendX + 13f * density, 16f * density,
                legendPaint
            )

            textPaint.textAlign = Paint.Align.LEFT
            canvas.drawText(
                item.name,
                legendX + 18f * density,
                20f * density,
                textPaint
            )

            legendX += (if (breakdownMode) 88f else 100f) * density
        }

        if (metrics.size < 2) {
            textPaint.textAlign = Paint.Align.CENTER
            canvas.drawText(
                "Недостаточно данных для графика",
                (left + right) / 2f,
                (top + bottom) / 2f,
                textPaint
            )
            return
        }

        val maxValue = max(
            1f,
            series.flatMap { it.values }.maxOrNull() ?: 1f
        ) * 1.15f

        for (i in 0..3) {
            val y = bottom - (bottom - top) * i / 3f
            canvas.drawLine(left, y, right, y, gridPaint)

            val value = maxValue * i / 3f
            textPaint.textAlign = Paint.Align.RIGHT
            canvas.drawText(
                "%.0f".format(java.util.Locale.getDefault(), value),
                left - 7f * density,
                y + 4f * density,
                textPaint
            )
        }

        series.forEach { item ->
            val path = Path()

            item.values.forEachIndexed { index, value ->
                val x = left + (right - left) *
                        index / (item.values.size - 1).toFloat()

                val y = bottom - (value / maxValue) * (bottom - top)

                if (index == 0) path.moveTo(x, y)
                else path.lineTo(x, y)
            }

            linePaint.color = item.color
            canvas.drawPath(path, linePaint)
        }

        // Маркер выбранного замера
        if (selectedIndex in metrics.indices) {
            val x = left + (right - left) *
                    selectedIndex / (metrics.size - 1).toFloat()

            canvas.drawLine(x, top, x, bottom, markerPaint)

            series.forEach { item ->
                val y = bottom -
                        (item.values[selectedIndex] / maxValue) * (bottom - top)

                pointPaint.color = item.color
                canvas.drawCircle(x, y, 5f * density, pointPaint)
                pointPaint.color = android.graphics.Color.WHITE
                canvas.drawCircle(x, y, 2.5f * density, pointPaint)
            }
        }

        textPaint.textAlign = Paint.Align.LEFT
        canvas.drawText("МБ", paddingLeft.toFloat(), top - 9f * density, textPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (metrics.size < 2) return false

        val left = paddingLeft + 42f * density
        val right = width - paddingRight - 10f * density

        when (event.action) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_MOVE -> {
                if (event.x < left - 20f * density ||
                    event.x > right + 20f * density
                ) return true

                val fraction = (
                        (event.x - left) / (right - left)
                        ).coerceIn(0f, 1f)

                selectedIndex = (
                        fraction * (metrics.size - 1)
                        ).roundToInt()

                onMetricSelected?.invoke(metrics[selectedIndex])
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP -> {
                performClick()
                return true
            }

            MotionEvent.ACTION_CANCEL -> return true
        }

        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
