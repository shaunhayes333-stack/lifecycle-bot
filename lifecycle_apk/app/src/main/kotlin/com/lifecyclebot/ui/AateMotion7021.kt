package com.lifecyclebot.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View

/**
 * V5.0.7021 — three more shapes, each chosen because a specific screen already
 * computes exactly the data it needs and prints it as a line of text.
 *
 * Operator: "do the rest of the screens too. no exceptions."
 *
 * The rule I am working to after 7020: do not invent a chart. Find the screen
 * whose numbers ALREADY answer a question, and draw the answer. Every view here
 * exists because one screen was printing a shape as prose:
 *
 *   FunnelView7021   Pipeline Health prints INTAKE 803 / SAFETY 1344 / V3 2474
 *                    / FDG 750 / EXEC 53 as a list. That is a funnel. The
 *                    interesting number is not any stage, it is where the drop
 *                    happens, and a list makes you do that subtraction in your
 *                    head every time.
 *
 *   ScatterView7021  Tuning's score-band calibration asks, in its own words,
 *                    "higher bands SHOULD show higher mean PnL. If they don't,
 *                    the scorer isn't predictive." That is a scatter plot with
 *                    a trend, and it was forty lines of BLUECHIP[70-79]n=22/
 *                    mu=+103.8% text. You cannot see a correlation in a list.
 *
 *   PairedBarsView7021  The Lab runs control-vs-variant A/B. Two numbers per
 *                    row, and the only thing that matters is which is longer.
 *
 * ANR discipline unchanged from 7009/7020: one animator per view, cancelled on
 * detach; nothing allocated in onDraw; setters no-op on unchanged input; and a
 * view with no data draws NOTHING rather than an empty frame, so "no samples"
 * and "all zero" never look the same.
 */

// ─────────────────────────────────────────────────────────────────────────────
// Funnel
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Stacked horizontal stage bars, each scaled to the widest stage, with the
 * stage name, its count, and the survival percentage against the stage above.
 *
 * Reads top-down like the pipeline does, and the drop-off is the gap between
 * one bar's right edge and the next — the thing the text version made you
 * compute.
 */
class FunnelView7021 @JvmOverloads constructor(
    ctx: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(ctx, attrs, defStyle) {

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x147CC4FF }
    private val namePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    private val numPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD)
        textAlign = Paint.Align.RIGHT
    }
    private val dropPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.RIGHT
    }
    private val rect = RectF()

    private var names: Array<String> = arrayOf()
    private var counts: LongArray = LongArray(0)
    private var colours: IntArray = IntArray(0)
    private var grow = 1f
    private var anim: ValueAnimator? = null

    /** Row height in dp; the view's measured height is rows * this. */
    var rowHeightDp: Float = 34f

    init {
        val d = resources.displayMetrics.density
        namePaint.textSize = 11f * d
        namePaint.color = 0xFFE8EEFB.toInt()
        numPaint.textSize = 12f * d
        numPaint.color = 0xFFE8EEFB.toInt()
        dropPaint.textSize = 9f * d
        dropPaint.color = 0xFF6E82A8.toInt()
    }

    fun setStages(stageNames: Array<String>, stageCounts: LongArray, stageColours: IntArray) {
        if (stageNames.size != stageCounts.size) return
        if (stageNames.contentEquals(names) && stageCounts.contentEquals(counts)) return
        names = stageNames.copyOf()
        counts = stageCounts.copyOf()
        colours = stageColours.copyOf()
        requestLayout()
        anim?.cancel()
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 780L
            interpolator = android.view.animation.DecelerateInterpolator(1.7f)
            addUpdateListener { grow = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val d = resources.displayMetrics.density
        val h = (names.size.coerceAtLeast(1) * rowHeightDp * d).toInt()
        setMeasuredDimension(
            getDefaultSize(suggestedMinimumWidth, widthSpec),
            resolveSize(h, heightSpec),
        )
    }

    override fun onDraw(canvas: Canvas) {
        if (width <= 0 || height <= 0 || names.isEmpty()) return
        val d = resources.displayMetrics.density
        val rowH = rowHeightDp * d
        val barH = 9f * d
        val r = barH / 2f
        val maxCount = (counts.maxOrNull() ?: 1L).coerceAtLeast(1L)
        val numW = 62f * d
        val barRight = width - numW

        for (i in names.indices) {
            val top = i * rowH
            val textBase = top + 12f * d
            canvas.drawText(names[i], 0f, textBase, namePaint)
            canvas.drawText(counts[i].toString(), width.toFloat(), textBase, numPaint)

            // Survival against the stage above — the number the list hid.
            if (i > 0 && counts[i - 1] > 0L) {
                val pct = counts[i] * 100.0 / counts[i - 1]
                canvas.drawText("${"%.0f".format(pct)}%", width.toFloat(), top + 24f * d, dropPaint)
            }

            val barTop = top + 16f * d
            rect.set(0f, barTop, barRight, barTop + barH)
            canvas.drawRoundRect(rect, r, r, trackPaint)

            val frac = (counts[i].toFloat() / maxCount.toFloat()).coerceIn(0f, 1f) * grow
            val w = barRight * frac
            if (w > 1f) {
                barPaint.color = if (colours.isNotEmpty()) colours[i % colours.size] else 0xFF22D3EE.toInt()
                rect.set(0f, barTop, w, barTop + barH)
                canvas.drawRoundRect(rect, r, r, barPaint)
            }
        }
    }

    override fun onDetachedFromWindow() { anim?.cancel(); anim = null; super.onDetachedFromWindow() }
}

// ─────────────────────────────────────────────────────────────────────────────
// Scatter with a zero line
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Points on an x/y field with a zero rule and a least-squares trend line.
 *
 * Built for Tuning's calibration question — does a higher score band actually
 * produce a higher mean return — where the answer is the SLOPE and the text
 * version required reading forty rows and holding them in your head. Dot area
 * carries sample size, so a band with n=22 does not argue as loudly as one with
 * n=1.
 */
class ScatterView7021 @JvmOverloads constructor(
    ctx: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(ctx, attrs, defStyle) {

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val zeroPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 1f
        color = 0x387CC4FF
    }
    private val trendPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 9f
        color = 0xFF6E82A8.toInt()
    }

    private var xs: FloatArray = FloatArray(0)
    private var ys: FloatArray = FloatArray(0)
    private var weights: FloatArray = FloatArray(0)
    private var fade = 1f
    private var anim: ValueAnimator? = null

    /** Drawn when the trend slopes the right way; the caller decides the colour. */
    var trendUpColor: Int = 0xFF34D399.toInt()
    var trendDownColor: Int = 0xFFFB5E6D.toInt()

    init {
        val d = resources.displayMetrics.density
        trendPaint.strokeWidth = 2f * d
        axisPaint.textSize = 9f * d
    }

    /**
     * [x] and [y] are in their own natural units; both axes are auto-scaled.
     * [w] is a per-point weight (sample count) — pass all 1s if unweighted.
     */
    fun setPoints(x: FloatArray, y: FloatArray, w: FloatArray) {
        if (x.size != y.size || x.size != w.size) return
        if (x.contentEquals(xs) && y.contentEquals(ys) && w.contentEquals(weights)) return
        xs = x.copyOf(); ys = y.copyOf(); weights = w.copyOf()
        anim?.cancel()
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 620L
            addUpdateListener { fade = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (width <= 0 || height <= 0 || xs.size < 2) return
        val d = resources.displayMetrics.density
        val padL = 4f * d
        val padR = 4f * d
        val padT = 6f * d
        val padB = 6f * d
        val w = width - padL - padR
        val h = height - padT - padB

        var xlo = xs[0]; var xhi = xs[0]
        var ylo = ys[0]; var yhi = ys[0]
        for (i in xs.indices) {
            if (xs[i] < xlo) xlo = xs[i]; if (xs[i] > xhi) xhi = xs[i]
            if (ys[i] < ylo) ylo = ys[i]; if (ys[i] > yhi) yhi = ys[i]
        }
        // Always include zero on Y so "above water" is a position, not a value
        // you have to read off a label.
        if (ylo > 0f) ylo = 0f
        if (yhi < 0f) yhi = 0f
        val xr = (xhi - xlo).takeIf { it > 1e-6f } ?: 1f
        val yr = (yhi - ylo).takeIf { it > 1e-6f } ?: 1f

        fun px(v: Float) = padL + (v - xlo) / xr * w
        fun py(v: Float) = padT + h - (v - ylo) / yr * h

        val zeroY = py(0f)
        canvas.drawLine(padL, zeroY, padL + w, zeroY, zeroPaint)

        // Least squares over the weighted points.
        var sw = 0f; var sx = 0f; var sy = 0f; var sxx = 0f; var sxy = 0f
        for (i in xs.indices) {
            val wi = weights[i].coerceAtLeast(0.0001f)
            sw += wi; sx += wi * xs[i]; sy += wi * ys[i]
            sxx += wi * xs[i] * xs[i]; sxy += wi * xs[i] * ys[i]
        }
        val denom = sw * sxx - sx * sx
        if (kotlin.math.abs(denom) > 1e-6f) {
            val slope = (sw * sxy - sx * sy) / denom
            val icept = (sy - slope * sx) / sw
            trendPaint.color = if (slope >= 0f) trendUpColor else trendDownColor
            trendPaint.alpha = (200 * fade).toInt().coerceIn(0, 255)
            canvas.drawLine(px(xlo), py(icept + slope * xlo), px(xhi), py(icept + slope * xhi), trendPaint)
        }

        val maxW = weights.maxOrNull()?.coerceAtLeast(1f) ?: 1f
        for (i in xs.indices) {
            val rel = (weights[i] / maxW).coerceIn(0.12f, 1f)
            val rad = (3f + 5f * kotlin.math.sqrt(rel)) * d
            dotPaint.color = if (ys[i] >= 0f) trendUpColor else trendDownColor
            dotPaint.alpha = (215 * fade).toInt().coerceIn(0, 255)
            canvas.drawCircle(px(xs[i]), py(ys[i]), rad, dotPaint)
        }
    }

    override fun onDetachedFromWindow() { anim?.cancel(); anim = null; super.onDetachedFromWindow() }
}

// ─────────────────────────────────────────────────────────────────────────────
// Paired bars
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Two bars per row sharing one scale — control against variant — with the
 * winner's bar lit and the loser's muted.
 *
 * For the Lab's A/B rows, where the only question is which arm is longer and
 * the text form made you compare two signed percentages per line.
 */
class PairedBarsView7021 @JvmOverloads constructor(
    ctx: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(ctx, attrs, defStyle) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    private val tagPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    private var labels: Array<String> = arrayOf()
    private var ctrl: FloatArray = FloatArray(0)
    private var vari: FloatArray = FloatArray(0)
    private var grow = 1f
    private var anim: ValueAnimator? = null

    var controlColor: Int = 0xFF6E82A8.toInt()
    var variantColor: Int = 0xFF8B5CF6.toInt()
    var rowHeightDp: Float = 40f

    init {
        val d = resources.displayMetrics.density
        labelPaint.textSize = 11f * d
        labelPaint.color = 0xFFE8EEFB.toInt()
        tagPaint.textSize = 9f * d
        tagPaint.color = 0xFF8FA3C8.toInt()
    }

    fun setRows(rowLabels: Array<String>, control: FloatArray, variant: FloatArray) {
        if (rowLabels.size != control.size || rowLabels.size != variant.size) return
        if (rowLabels.contentEquals(labels) && control.contentEquals(ctrl) && variant.contentEquals(vari)) return
        labels = rowLabels.copyOf(); ctrl = control.copyOf(); vari = variant.copyOf()
        requestLayout()
        anim?.cancel()
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 700L
            interpolator = android.view.animation.DecelerateInterpolator(1.6f)
            addUpdateListener { grow = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val d = resources.displayMetrics.density
        val h = (labels.size.coerceAtLeast(1) * rowHeightDp * d).toInt()
        setMeasuredDimension(
            getDefaultSize(suggestedMinimumWidth, widthSpec),
            resolveSize(h, heightSpec),
        )
    }

    override fun onDraw(canvas: Canvas) {
        if (width <= 0 || height <= 0 || labels.isEmpty()) return
        val d = resources.displayMetrics.density
        val rowH = rowHeightDp * d
        val barH = 7f * d
        val r = barH / 2f
        // One shared scale across both arms, or the comparison is meaningless.
        var maxAbs = 0f
        for (i in labels.indices) {
            maxAbs = maxOf(maxAbs, kotlin.math.abs(ctrl[i]), kotlin.math.abs(vari[i]))
        }
        if (maxAbs <= 0f) maxAbs = 1f

        for (i in labels.indices) {
            val top = i * rowH
            canvas.drawText(labels[i], 0f, top + 11f * d, labelPaint)
            val variantWins = vari[i] > ctrl[i]

            // control
            paint.color = controlColor
            paint.alpha = if (variantWins) 110 else 235
            var frac = (kotlin.math.abs(ctrl[i]) / maxAbs).coerceIn(0f, 1f) * grow
            rect.set(0f, top + 16f * d, (width * frac).coerceAtLeast(2f * d), top + 16f * d + barH)
            canvas.drawRoundRect(rect, r, r, paint)

            // variant
            paint.color = variantColor
            paint.alpha = if (variantWins) 235 else 110
            frac = (kotlin.math.abs(vari[i]) / maxAbs).coerceIn(0f, 1f) * grow
            rect.set(0f, top + 26f * d, (width * frac).coerceAtLeast(2f * d), top + 26f * d + barH)
            canvas.drawRoundRect(rect, r, r, paint)
            paint.alpha = 255
        }
    }

    override fun onDetachedFromWindow() { anim?.cancel(); anim = null; super.onDetachedFromWindow() }
}
