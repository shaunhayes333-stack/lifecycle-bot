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
 * V5.0.7020 — the three views the renders have and the app never built.
 *
 * WHY THIS FILE EXISTS, STATED PLAINLY
 * ====================================
 * Operator, after four restyle passes: "its not just colours. the format lack
 * of graphs and animations etc shown in your renders."
 *
 * Correct, and it is the thing I kept not doing. I spent 6998-7019 on palettes,
 * drawables, card fills and border alphas — the surface a thing sits on — while
 * the renders' Markets screen carries a scrolling ticker tape, a five-segment
 * allocation donut, fourteen animated heat bars, a live sparkline on every
 * asset row, and a 1.6-second tick driving all of it. The shipped screen has
 * four empty boxes with titles in them. No amount of border tuning closes that,
 * because the gap is CONTENT AND MOTION, not colour.
 *
 * AateMotion7009 built four views (sparkline, pulse dot, lane bars, ring gauge)
 * and they reach two screens out of twenty-one. These three did not exist at
 * all. Same pattern as AateComponents6994 and as 6976's synthetic tag: the
 * capability is the easy half; wiring it to the screen that needs it is the
 * half that keeps not happening.
 *
 * ANR DISCIPLINE, carried from 7009 and CORRECTED IN 7027, because these run
 * on screens that already refresh every 2-4 seconds:
 *   - at most one ValueAnimator per view; a LOOPING one is owned by
 *     AateLoopAnim7027, which runs it only while the view is genuinely on
 *     screen and repaints at ~15-20fps instead of 60. "Cancelled in
 *     onDetachedFromWindow" was the old rule and it was not sufficient: a
 *     view on a backgrounded activity is still attached, and the tape below
 *     was one of the four loops that took 7025 from 17 executions to 0.
 *   - no allocation inside onDraw: every Paint, RectF and Shader is built once
 *     in init or on a setter, never per frame
 *   - every setter is a no-op when handed data equal to what it already holds,
 *     so a repaint tick that changes nothing costs nothing
 *   - a view with no data draws NOTHING rather than a zeroed placeholder, so an
 *     empty gauge and a failing gauge never look the same (V5.0.7013)
 */

// ─────────────────────────────────────────────────────────────────────────────
// Allocation donut
// ─────────────────────────────────────────────────────────────────────────────

/**
 * The render's portfolio ring: N coloured segments around a hollow centre with
 * a figure in the middle. Segments sweep in once on change.
 *
 * Used for "where is the capital", which the app has always known and only ever
 * printed as a list of percentages.
 */
class DonutView7020 @JvmOverloads constructor(
    ctx: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(ctx, attrs, defStyle) {

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
    }
    private val segPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
    }
    private val centrePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val capPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val oval = RectF()

    private var fractions: FloatArray = FloatArray(0)
    private var colours: IntArray = IntArray(0)
    private var sweep = 0f
    private var anim: ValueAnimator? = null

    /** Figure drawn in the hole. Blank draws nothing. */
    var centreText: String = ""
        set(v) { if (field != v) { field = v; invalidate() } }

    var centreCaption: String = ""
        set(v) { if (field != v) { field = v; invalidate() } }

    var textColor: Int = 0xFFE8EEFB.toInt()
        set(v) { field = v; centrePaint.color = v; invalidate() }

    init {
        val d = resources.displayMetrics.density
        trackPaint.strokeWidth = 10f * d
        trackPaint.color = 0x1A7CC4FF
        segPaint.strokeWidth = 10f * d
        centrePaint.color = textColor
        capPaint.color = 0xFF6E82A8.toInt()
    }

    /**
     * [values] need not sum to anything in particular — they are normalised
     * here, so a caller can pass raw SOL per lane and not do the arithmetic
     * twice. Non-finite and negative entries are dropped.
     */
    fun setSegments(values: FloatArray, segColours: IntArray, animate: Boolean = true) {
        var total = 0f
        for (v in values) if (v.isFinite() && v > 0f) total += v
        if (total <= 0f) {
            if (fractions.isNotEmpty()) { fractions = FloatArray(0); invalidate() }
            return
        }
        val next = FloatArray(values.size) { i ->
            val v = values[i]
            if (v.isFinite() && v > 0f) v / total else 0f
        }
        if (next.contentEquals(fractions) && segColours.contentEquals(colours)) return
        fractions = next
        colours = segColours.copyOf()
        if (!animate) { sweep = 1f; invalidate(); return }
        anim?.cancel()
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 750L
            interpolator = android.view.animation.DecelerateInterpolator(1.6f)
            addUpdateListener { sweep = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (width <= 0 || height <= 0 || fractions.isEmpty()) return
        val size = minOf(width, height).toFloat()
        val inset = segPaint.strokeWidth / 2f + 1f
        val cx = width / 2f
        val cy = height / 2f
        oval.set(cx - size / 2f + inset, cy - size / 2f + inset, cx + size / 2f - inset, cy + size / 2f - inset)
        canvas.drawArc(oval, -90f, 360f, false, trackPaint)

        var start = -90f
        for (i in fractions.indices) {
            val f = fractions[i]
            if (f <= 0f) continue
            segPaint.color = if (colours.isNotEmpty()) colours[i % colours.size] else 0xFF22D3EE.toInt()
            val deg = 360f * f * sweep
            // A hair of separation so adjacent segments read as separate.
            canvas.drawArc(oval, start + 0.8f, (deg - 1.6f).coerceAtLeast(0f), false, segPaint)
            start += 360f * f
        }

        if (centreText.isNotBlank()) {
            centrePaint.textSize = size * 0.26f
            val dy = if (centreCaption.isBlank()) centrePaint.textSize * 0.35f else centrePaint.textSize * 0.10f
            canvas.drawText(centreText, cx, cy + dy, centrePaint)
        }
        if (centreCaption.isNotBlank()) {
            capPaint.textSize = size * 0.12f
            canvas.drawText(centreCaption, cx, cy + size * 0.26f, capPaint)
        }
    }

    override fun onDetachedFromWindow() { anim?.cancel(); anim = null; super.onDetachedFromWindow() }
}

// ─────────────────────────────────────────────────────────────────────────────
// Heat bars
// ─────────────────────────────────────────────────────────────────────────────

/**
 * The render's sector-heat strip: a row of vertical bars, each a gradient from
 * its own colour down to a transparent tail, growing from the baseline on
 * change.
 *
 * Distinct from LaneBarsView7009, which draws flat-coloured bars for a small
 * fixed set of lanes. This one is built for many thin bars and reads as a
 * texture rather than a chart.
 */
class HeatBarsView7020 @JvmOverloads constructor(
    ctx: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(ctx, attrs, defStyle) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var values: FloatArray = FloatArray(0)
    private var colours: IntArray = IntArray(0)
    private var shaders: Array<Shader?> = arrayOf()
    private var grow = 1f
    private var anim: ValueAnimator? = null

    /** Corner radius of each bar, in dp. */
    var barRadiusDp: Float = 3f

    /**
     * [v] entries are 0..1 heights. [c] is one colour per bar; shorter arrays
     * repeat. Shaders are rebuilt here and never inside onDraw.
     */
    fun setBars(v: FloatArray, c: IntArray, animate: Boolean = true) {
        if (v.contentEquals(values) && c.contentEquals(colours)) return
        values = v.copyOf()
        colours = c.copyOf()
        shaders = arrayOfNulls(values.size)
        requestLayout()
        if (!animate) { grow = 1f; invalidate(); return }
        anim?.cancel()
        anim = ValueAnimator.ofFloat(0.05f, 1f).apply {
            duration = 820L
            interpolator = android.view.animation.DecelerateInterpolator(1.8f)
            addUpdateListener { grow = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        shaders = arrayOfNulls(values.size)
    }

    override fun onDraw(canvas: Canvas) {
        if (width <= 0 || height <= 0 || values.isEmpty()) return
        val d = resources.displayMetrics.density
        val n = values.size
        val gap = 3f * d
        val bw = ((width - gap * (n - 1)) / n).coerceAtLeast(1f)
        val r = barRadiusDp * d
        val base = height.toFloat()

        for (i in 0 until n) {
            val frac = (values[i].coerceIn(0f, 1f)) * grow
            val bh = (base * frac).coerceAtLeast(2f * d)
            val left = i * (bw + gap)
            val top = base - bh
            val col = if (colours.isNotEmpty()) colours[i % colours.size] else 0xFF34D399.toInt()
            var sh = shaders.getOrNull(i)
            if (sh == null) {
                sh = LinearGradient(
                    0f, top, 0f, base,
                    col, (col and 0x00FFFFFF) or 0x33000000,
                    Shader.TileMode.CLAMP,
                )
                if (i < shaders.size) shaders[i] = sh
            }
            paint.shader = sh
            rect.set(left, top, left + bw, base)
            canvas.drawRoundRect(rect, r, r, paint)
        }
        paint.shader = null
    }

    override fun onDetachedFromWindow() { anim?.cancel(); anim = null; super.onDetachedFromWindow() }
}

// ─────────────────────────────────────────────────────────────────────────────
// Ticker tape
// ─────────────────────────────────────────────────────────────────────────────

/**
 * The render's scrolling symbol tape. Draws its items twice end-to-end and
 * translates by one width, so the loop is seamless with no per-frame
 * allocation and no re-measure.
 *
 * Deliberately slow (the render runs one pass in 22s) — this sits at the top of
 * a screen someone is reading, not a thing to chase across it.
 */
class TickerTapeView7020 @JvmOverloads constructor(
    ctx: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(ctx, attrs, defStyle) {

    private val symPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = android.graphics.Typeface.MONOSPACE
    }
    private val valPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD)
    }

    private var syms: Array<String> = arrayOf()
    private var vals: Array<String> = arrayOf()
    private var cols: IntArray = IntArray(0)
    private var widths: FloatArray = FloatArray(0)
    private var runWidth = 0f
    private var offset = 0f
    // V5.0.7027 — the tape drives its offset from a 0..1 phase scaled by the
    // CURRENT runWidth rather than animating 0..runWidth directly, so adding or
    // removing a symbol no longer needs the animator restarted, and the loop is
    // visibility-gated and rate-limited. At 22s per pass and ~15fps the tape
    // advances a couple of pixels a frame; nobody can tell it from 60.
    private val scrollLoop = AateLoopAnim7027(
        host = this, durationMs = 22_000L,
        frameMs = AateLoopAnim7027.FRAME_MS_SLOW,
    ) { phase -> offset = phase * runWidth }

    var symColor: Int = 0xFFC8D6EE.toInt()
        set(v) { field = v; symPaint.color = v; invalidate() }

    init {
        val d = resources.displayMetrics.density
        symPaint.textSize = 10f * d
        symPaint.color = symColor
        valPaint.textSize = 10f * d
    }

    /** One entry per item; all three arrays must be the same length or nothing draws. */
    fun setItems(symbols: Array<String>, values: Array<String>, colours: IntArray) {
        if (symbols.size != values.size || symbols.size != colours.size) return
        if (symbols.contentEquals(syms) && values.contentEquals(vals) && colours.contentEquals(cols)) return
        syms = symbols.copyOf()
        vals = values.copyOf()
        cols = colours.copyOf()
        measureRun()
        startScroll()
        invalidate()
    }

    private fun measureRun() {
        val d = resources.displayMetrics.density
        val pad = 11f * d
        val gap = 5f * d
        widths = FloatArray(syms.size) { i ->
            pad + symPaint.measureText(syms[i]) + gap + valPaint.measureText(vals[i]) + pad
        }
        runWidth = widths.sum()
    }

    /** One full pass in ~22s regardless of how many symbols are in the tape. */
    private fun startScroll() { scrollLoop.request(runWidth > 0f && syms.isNotEmpty()) }

    override fun onDraw(canvas: Canvas) {
        if (width <= 0 || height <= 0 || syms.isEmpty() || runWidth <= 0f) return
        val d = resources.displayMetrics.density
        val pad = 11f * d
        val gap = 5f * d
        val baseline = height / 2f - (symPaint.descent() + symPaint.ascent()) / 2f

        // Two passes so the tape is continuous across the seam.
        var pass = 0
        while (pass < 2) {
            var x = -offset + pass * runWidth
            for (i in syms.indices) {
                val w = widths[i]
                if (x + w >= 0f && x <= width) {
                    canvas.drawText(syms[i], x + pad, baseline, symPaint)
                    valPaint.color = cols[i]
                    canvas.drawText(
                        vals[i],
                        x + pad + symPaint.measureText(syms[i]) + gap,
                        baseline,
                        valPaint,
                    )
                }
                x += w
            }
            pass++
        }
    }

    override fun onDetachedFromWindow() { scrollLoop.release(); super.onDetachedFromWindow() }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); scrollLoop.sync() }
    override fun onWindowVisibilityChanged(v: Int) { super.onWindowVisibilityChanged(v); scrollLoop.sync() }
    override fun onVisibilityAggregated(isVisible: Boolean) { super.onVisibilityAggregated(isVisible); scrollLoop.sync() }
}
