

package com.example.rammonitor.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import com.example.rammonitor.data.database.ProcessSample
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

class LeakChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var samples: List<ProcessSample> = emptyList()

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(37, 99, 235)
        strokeWidth = dp(2.5f)
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(37, 99, 235)
        alpha = 30
        style = Paint.Style.FILL
    }

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(226, 232, 240)
        strokeWidth = dp(1f)
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(100, 116, 139)
        textSize = dp(10f)
    }

    private val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(37, 99, 235)
        style = Paint.Style.FILL
    }

    fun setSamples(value: List<ProcessSample>) {
        samples = value.sortedBy { it.captured_at }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (samples.size < 2) {
            canvas.drawText(
                "Недостаточно замеров для графика",
                dp(12f),
                height / 2f,
                textPaint
            )
            return
        }

        val left = dp(42f)
        val right = width - dp(12f)
        val top = dp(18f)
        val bottom = height - dp(32f)

        if (right <= left || bottom <= top) return

        val minTime = samples.first().captured_at.toFloat()
        val maxTime = samples.last().captured_at.toFloat()
        val timeRange = max(1f, maxTime - minTime)

        val minValue = samples.minOf { it.pss_kb }.toFloat() / 1024f
        val maxValue = samples.maxOf { it.pss_kb }.toFloat() / 1024f
        val valueRange = max(1f, maxValue - minValue)
        val chartMin = max(0f, minValue - valueRange * 0.15f)
        val chartMax = maxValue + valueRange * 0.15f
        val chartRange = max(1f, chartMax - chartMin)

        fun x(sample: ProcessSample): Float {
            return left + (sample.captured_at.toFloat() - minTime) / timeRange * (right - left)
        }

        fun y(sample: ProcessSample): Float {
            val value = sample.pss_kb / 1024f
            return bottom - (value - chartMin) / chartRange * (bottom - top)
        }

        // Горизонтальная сетка и подписи
        for (i in 0..4) {
            val fraction = i / 4f
            val yPosition = bottom - fraction * (bottom - top)
            canvas.drawLine(left, yPosition, right, yPosition, gridPaint)

            val value = chartMin + fraction * chartRange
            canvas.drawText(
                "%.0f".format(Locale.getDefault(), value),
                dp(2f),
                yPosition + dp(4f),
                textPaint
            )
        }

        // Линия графика
        val path = Path()
        samples.forEachIndexed { index, sample ->
            val px = x(sample)
            val py = y(sample)

            if (index == 0) path.moveTo(px, py)
            else path.lineTo(px, py)
        }

        val fillPath = Path(path).apply {
            lineTo(x(samples.last()), bottom)
            lineTo(x(samples.first()), bottom)
            close()
        }

        canvas.drawPath(fillPath, fillPaint)
        canvas.drawPath(path, linePaint)

        // Начальная и конечная точки
        canvas.drawCircle(x(samples.first()), y(samples.first()), dp(4f), pointPaint)
        canvas.drawCircle(x(samples.last()), y(samples.last()), dp(4f), pointPaint)

        // Подписи времени
        val formatter = SimpleDateFormat("HH:mm", Locale.getDefault())
        canvas.drawText(
            formatter.format(Date(samples.first().captured_at)),
            left,
            height - dp(8f),
            textPaint
        )

        val lastLabel = formatter.format(Date(samples.last().captured_at))
        val labelWidth = textPaint.measureText(lastLabel)
        canvas.drawText(
            lastLabel,
            right - labelWidth,
            height - dp(8f),
            textPaint
        )
    }

    private fun dp(value: Float): Float =
        value * resources.displayMetrics.density
}
