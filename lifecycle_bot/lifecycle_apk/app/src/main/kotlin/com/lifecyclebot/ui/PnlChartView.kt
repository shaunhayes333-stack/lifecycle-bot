package com.lifecyclebot.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import com.lifecyclebot.engine.PnlPoint
import kotlin.math.absoluteValue

/**
 * Custom P&L chart view.
 *
 * Draws:
 *   - Cumulative P&L line (green above zero, red below)
 *   - Zero baseline
 *   - Buy markers (▲ green triangles)
 *   - Sell markers (▼ red/green triangles depending on win/loss)
 *   - Shaded area under curve
 *   - Y-axis labels (SOL amounts)
 *   - Current P&L annotation at right edge
 */
class PnlChartView @JvmOverloads constructor(
    ctx: Context, attrs: AttributeSet? = null, def: Int = 0,
) : View(ctx, attrs, def) {

    // Scale text sizes with screen density
    private val density = ctx.resources.displayMetrics.density
    private val scaledDensity = ctx.resources.displayMetrics.scaledDensity

    var points: List<PnlPoint> = emptyList()
        set(v) { field = v; invalidate() }

    // ── paints ────────────────────────────────────────────────────────

    private val linePaintPos = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color     = 0xFF00E5A0.toInt()
        strokeWidth = 2.5f
        style     = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val linePaintNeg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color     = 0xFFFF3D5A.toInt()
        strokeWidth = 2.5f
        style     = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fillPaintPos = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x1800E5A0
        style = Paint.Style.FILL
    }
    private val fillPaintNeg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x18FF3D5A
        style = Paint.Style.FILL
    }
    private val baselinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color     = 0xFF2A3A4A.toInt()
        strokeWidth = 1f
        style     = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(8f, 6f), 0f)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color     = 0xFF4A5E70.toInt()
        textSize  = 10f * scaledDensity
        typeface  = Typeface.MONOSPACE
    }
    private val pnlLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize  = 11f * scaledDensity
        typeface  = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textAlign = Paint.Align.RIGHT
    }
    private val buyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF00E5A0.toInt()
        style = Paint.Style.FILL
    }
    private val sellWinPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF00E5A0.toInt()
        style = Paint.Style.FILL
    }
    private val sellLossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFF3D5A.toInt()
        style = Paint.Style.FILL
    }
    private val emptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color     = 0xFF4A5E70.toInt()
        textSize  = 32f
        typeface  = Typeface.MONOSPACE
        textAlign = Paint.Align.CENTER
    }

    // ── drawing ───────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w     = width.toFloat()
        val h     = height.toFloat()
        val padL  = 10f
        val padR  = 80f   // room for right-side P&L label
        val padT  = 16f
        val padB  = 28f
        val chartW = w - padL - padR
        val chartH = h - padT - padB

        if (points.isEmpty()) {
            canvas.drawText(
                "No trades yet",
                w / 2, h / 2 + 12f, emptyPaint
            )
            return
        }

        val values = points.map { it.cumulativePnlSol }
        val minVal = minOf(values.min(), 0.0).toFloat()
        val maxVal = maxOf(values.max(), 0.0).toFloat()
        val range  = (maxVal - minVal).let { if (it == 0f) 1f else it }

        fun xOf(idx: Int): Float = padL + (idx.toFloat() / (points.size - 1).coerceAtLeast(1)) * chartW
        fun yOf(v: Double): Float = padT + chartH - ((v.toFloat() - minVal) / range) * chartH
        val zeroY = yOf(0.0)

        // ── zero baseline ─────────────────────────────────────────────
        canvas.drawLine(padL, zeroY, padL + chartW, zeroY, baselinePaint)

        if (points.size < 2) {
            // Single point — just draw a dot
            canvas.drawCircle(xOf(0), yOf(points[0].cumulativePnlSol), 6f, linePaintPos)
            return
        }

        // ── build path ────────────────────────────────────────────────
        val path     = Path()
        val fillPath = Path()

        path.moveTo(xOf(0), yOf(points[0].cumulativePnlSol))
        fillPath.moveTo(xOf(0), zeroY)
        fillPath.lineTo(xOf(0), yOf(points[0].cumulativePnlSol))

        for (i in 1 until points.size) {
            val x = xOf(i)
            val y = yOf(points[i].cumulativePnlSol)
            path.lineTo(x, y)
            fillPath.lineTo(x, y)
        }
        fillPath.lineTo(xOf(points.size - 1), zeroY)
        fillPath.close()

        // ── draw fill (split above/below zero) ────────────────────────
        val clipAbove = Region(
            padL.toInt(), padT.toInt(),
            (padL + chartW).toInt(), zeroY.toInt()
        )
        val clipBelow = Region(
            padL.toInt(), zeroY.toInt(),
            (padL + chartW).toInt(), (padT + chartH).toInt()
        )

        canvas.save()
        canvas.clipRect(padL, padT, padL + chartW, zeroY)
        canvas.drawPath(fillPath, fillPaintPos)
        canvas.restore()

        canvas.save()
        canvas.clipRect(padL, zeroY, padL + chartW, padT + chartH)
        canvas.drawPath(fillPath, fillPaintNeg)
        canvas.restore()

        // ── draw line ─────────────────────────────────────────────────
        val lastVal = points.last().cumulativePnlSol
        canvas.drawPath(path, if (lastVal >= 0) linePaintPos else linePaintNeg)

        // ── buy/sell markers ──────────────────────────────────────────
        val markerSize = 5f * density
        for ((i, pt) in points.withIndex()) {
            val x = xOf(i)
            val y = yOf(pt.cumulativePnlSol)
            if (pt.isBuy) {
                drawTriangle(canvas, x, y + markerSize, markerSize, true, buyPaint)
            } else {
                val p = if (pt.isWin) sellWinPaint else sellLossPaint
                drawTriangle(canvas, x, y - markerSize, markerSize, false, p)
            }
        }

        // ── Y axis labels ─────────────────────────────────────────────
        for (frac in listOf(0.0f, 0.5f, 1.0f)) {
            val v  = minVal + range * frac
            val y  = yOf(v.toDouble())
            val lbl = if (v.absoluteValue < 0.001f) "0" else "%+.3f◎".format(v)
            canvas.drawText(lbl, w - padR + 6f, y + 9f, labelPaint)
        }

        // ── current P&L annotation ────────────────────────────────────
        val finalPnl = lastVal
        pnlLabelPaint.color = if (finalPnl >= 0) 0xFF00E5A0.toInt() else 0xFFFF3D5A.toInt()
        canvas.drawText(
            "%+.4f◎".format(finalPnl),
            w - 4f, padT + 20f,
            pnlLabelPaint
        )
    }

    private fun drawTriangle(
        canvas: Canvas, cx: Float, cy: Float,
        size: Float, pointUp: Boolean, paint: Paint,
    ) {
        val path = Path()
        if (pointUp) {
            path.moveTo(cx, cy - size)
            path.lineTo(cx - size * 0.7f, cy + size * 0.4f)
            path.lineTo(cx + size * 0.7f, cy + size * 0.4f)
        } else {
            path.moveTo(cx, cy + size)
            path.lineTo(cx - size * 0.7f, cy - size * 0.4f)
            path.lineTo(cx + size * 0.7f, cy - size * 0.4f)
        }
        path.close()
        canvas.drawPath(path, paint)
    }
}
