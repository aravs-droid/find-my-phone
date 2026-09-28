package net.media.wakeword

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.view.View

/**
 * Last ~12s of ticks: raw score (blue), the rule's compared value when it differs from
 * raw (orange, e.g. the moving average), cutoff (dashed red), fires (green bars).
 * Tap to toggle the y-axis between 0–1 and 0.9–1 (where the cutoffs live).
 */
class ScoreGraphView(context: Context) : View(context) {
    var zoomed = false
        private set

    private val density = resources.displayMetrics.density
    private val bg = Paint().apply { color = Color.parseColor("#F5F5F5") }
    private val grid = Paint().apply { color = Color.parseColor("#E0E0E0"); strokeWidth = density }
    private val raw = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1565C0"); style = Paint.Style.STROKE; strokeWidth = 1.5f * density
    }
    private val level = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#EF6C00"); style = Paint.Style.STROKE; strokeWidth = 2f * density
    }
    private val cutoff = Paint().apply {
        color = Color.parseColor("#C62828"); strokeWidth = 1.5f * density
        pathEffect = DashPathEffect(floatArrayOf(6 * density, 4 * density), 0f)
    }
    private val fire = Paint().apply { color = Color.parseColor("#2E7D32"); strokeWidth = 3f * density }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#616161"); textSize = 11f * density
    }
    private val path = Path()

    init {
        setOnClickListener { zoomed = !zoomed; invalidate() }
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawRect(0f, 0f, w, h, bg)

        val lo = if (zoomed) 0.9f else 0f
        fun y(v: Float) = h - ((v.coerceIn(lo, 1f) - lo) / (1f - lo)) * h
        for (g in 1..3) canvas.drawLine(0f, h * g / 4, w, h * g / 4, grid)

        val n = WakeState.HISTORY
        val ticks = WakeState.ticks
        val count = minOf(ticks, n.toLong()).toInt()
        val dx = w / (n - 1)
        val first = n - count  // left-pad so the newest tick is always at the right edge

        fun idx(k: Int) = ((ticks - count + k) % n).toInt()

        for (k in 0 until count) if (WakeState.fireHistory[idx(k)]) {
            val x = (first + k) * dx
            canvas.drawLine(x, 0f, x, h, fire)
        }

        val trig = WakeState.trigger
        val cy = y(trig.cutoff)
        canvas.drawLine(0f, cy, w, cy, cutoff)

        if (count > 1) {
            drawSeries(canvas, WakeState.rawHistory, count, first, dx, ::idx, ::y, raw)
            if (trig.mode == RuleMode.MOVAVG) {
                drawSeries(canvas, WakeState.levelHistory, count, first, dx, ::idx, ::y, level)
            }
        }

        canvas.drawText(if (zoomed) "1.00" else "1.0", 4 * density, 12 * density, label)
        canvas.drawText(if (zoomed) "0.90  (tap: 0–1)" else "0  (tap: zoom 0.9–1)", 4 * density, h - 4 * density, label)
    }

    private inline fun drawSeries(
        canvas: Canvas, data: FloatArray, count: Int, first: Int, dx: Float,
        idx: (Int) -> Int, y: (Float) -> Float, paint: Paint,
    ) {
        path.reset()
        for (k in 0 until count) {
            val x = (first + k) * dx
            val yy = y(data[idx(k)])
            if (k == 0) path.moveTo(x, yy) else path.lineTo(x, yy)
        }
        canvas.drawPath(path, paint)
    }
}
