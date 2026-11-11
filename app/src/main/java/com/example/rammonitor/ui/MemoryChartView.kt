package com.example.rammonitor.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import com.example.rammonitor.data.database.Snapshot
import kotlin.math.max

class MemoryChartView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF269B78.toInt(); strokeWidth = 5f; style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFE3E8EF.toInt(); strokeWidth = 1.5f
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x33269B78; style = Paint.Style.FILL
    }
    private var values: List<Int> = emptyList()

    fun setSnapshots(items: List<Snapshot>) {
        values = items.map { it.used_ram_mb }.filter { it >= 0 }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val left = paddingLeft + 8f
        val right = width - paddingRight - 8f
        val top = paddingTop + 12f
        val bottom = height - paddingBottom - 16f
        if (right <= left || bottom <= top) return
        repeat(4) { i ->
            val y = top + (bottom - top) * i / 3f
            canvas.drawLine(left, y, right, y, gridPaint)
        }
        if (values.size < 2) return
        val minValue = (values.minOrNull() ?: 0).toFloat()
        val maxValue = max((values.maxOrNull() ?: 1).toFloat(), minValue + 1f)
        val path = Path()
        values.forEachIndexed { index, value ->
            val x = left + (right - left) * index / (values.size - 1).toFloat()
            val y = bottom - (value - minValue) / (maxValue - minValue) * (bottom - top)
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        val area = Path(path).apply { lineTo(right, bottom); lineTo(left, bottom); close() }
        canvas.drawPath(area, fillPaint)
        canvas.drawPath(path, linePaint)
    }
}
