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

    /**
     * V5.0.6936 — animated reveal.
     *
     * The chart used to snap to its final shape the instant data arrived. The
     * reference design reads as a live instrument, so the curve now sweeps in
     * left-to-right over [REVEAL_MS] whenever the series changes.
     *
     * Deliberately cheap and interruptible: one ValueAnimator, cancelled and
     * restarted on every set, driving a single 0..1 fraction that clips how
     * much of the path is drawn. No per-frame allocation, no object animators
     * on child views, and if the animator never runs the fraction stays at 1f
     * so the chart still renders complete. Animation must never be the reason
     * a number is invisible.
     */
    private var revealFraction = 1f
    private var revealAnimator: android.animation.ValueAnimator? = null

    var points: List<PnlPoint> = emptyList()
        set(v) {
            val hadData = field.isNotEmpty()
            field = v
            if (v.size >= 2) startReveal(fromScratch = !hadData) else { revealFraction = 1f; invalidate() }
        }

    private fun startReveal(fromScratch: Boolean) {
        revealAnimator?.cancel()
        // A refresh of an already-drawn chart should not replay the whole
        // sweep on every tick — that would make a live dashboard flicker.
        if (!fromScratch) { revealFraction = 1f; invalidate(); return }
        revealFraction = 0f
        revealAnimator = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = REVEAL_MS
            interpolator = android.view.animation.DecelerateInterpolator(1.6f)
            addUpdateListener { revealFraction = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        revealAnimator?.cancel(); revealAnimator = null
        super.onDetachedFromWindow()
    }

    private companion object { const val REVEAL_MS = 620L }

    // ── paints ────────────────────────────────────────────────────────

    private val linePaintPos = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color     = AateUi.GREEN
        strokeWidth = 2.5f
        style     = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val linePaintNeg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color     = AateUi.RED
        strokeWidth = 2.5f
        style     = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    /**
     * V5.0.6936 — the reference design fades the area fill out as it falls
     * away from the curve rather than using one flat wash. Shaders are built
     * in onSizeChanged because a LinearGradient needs the real view height,
     * and rebuilding one per frame in onDraw would allocate on every tick.
     */
    private val fillPaintPos = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = 0x2816E6A1 }
    private val fillPaintNeg = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = 0x28FF4D6D }

    /** Soft neon bloom under the curve, matching the lit-from-behind look. */
    private val glowPaintPos = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x5516E6A1; strokeWidth = 6f; style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        maskFilter = BlurMaskFilter(7f, BlurMaskFilter.Blur.NORMAL)
    }
    private val glowPaintNeg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x55FF4D6D; strokeWidth = 6f; style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        maskFilter = BlurMaskFilter(7f, BlurMaskFilter.Blur.NORMAL)
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        if (h <= 0) return
        val fh = h.toFloat()
        fillPaintPos.shader = LinearGradient(
            0f, 0f, 0f, fh,
            intArrayOf(0x5516E6A1, 0x1416E6A1, 0x0016E6A1),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP,
        )
        fillPaintNeg.shader = LinearGradient(
            0f, fh, 0f, 0f,
            intArrayOf(0x55FF4D6D, 0x14FF4D6D, 0x00FF4D6D),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP,
        )
        // Hardware layers cannot render a BlurMaskFilter; without this the
        // glow silently disappears on most devices instead of erroring.
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }
    private val baselinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color     = AateUi.STROKE_SOFT
        strokeWidth = 1f
        style     = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(8f, 6f), 0f)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color     = AateUi.TEXT_MUTED
        textSize  = 10f * scaledDensity
        typeface  = Typeface.MONOSPACE
    }
    private val pnlLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize  = 11f * scaledDensity
        typeface  = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textAlign = Paint.Align.RIGHT
    }
    private val buyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AateUi.GREEN
        style = Paint.Style.FILL
    }
    private val sellWinPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AateUi.GREEN
        style = Paint.Style.FILL
    }
    private val sellLossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AateUi.RED
        style = Paint.Style.FILL
    }
    private val emptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color     = AateUi.TEXT_MUTED
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

        // V5.0.6936 — the reveal sweep clips everything to a growing x, so the
        // curve and its fill draw in from the left together.
        val revealRight = padL + chartW * revealFraction

        canvas.save()
        canvas.clipRect(padL, padT, revealRight, zeroY)
        canvas.drawPath(fillPath, fillPaintPos)
        canvas.restore()

        canvas.save()
        canvas.clipRect(padL, zeroY, revealRight, padT + chartH)
        canvas.drawPath(fillPath, fillPaintNeg)
        canvas.restore()

        canvas.save()
        canvas.clipRect(padL, padT, revealRight, padT + chartH)

        // ── draw line, with the V5.0.6936 neon bloom beneath it ───────
        val lastVal = points.last().cumulativePnlSol
        val up = lastVal >= 0
        canvas.drawPath(path, if (up) glowPaintPos else glowPaintNeg)
        canvas.drawPath(path, if (up) linePaintPos else linePaintNeg)
        canvas.restore()   // closes the reveal clip opened before the fills

        // ── buy/sell markers ──────────────────────────────────────────
        // V5.0.6936 — markers appear as the sweep reaches them, so the chart
        // draws itself in one direction instead of popping fully formed.
        val markerSize = 5f * density
        val revealX = padL + chartW * revealFraction
        for ((i, pt) in points.withIndex()) {
            val x = xOf(i)
            if (x > revealX) break
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
        pnlLabelPaint.color = if (finalPnl >= 0) AateUi.GREEN else AateUi.RED
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
