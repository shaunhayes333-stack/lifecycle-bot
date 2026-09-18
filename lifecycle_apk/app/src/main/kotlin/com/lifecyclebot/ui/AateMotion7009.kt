package com.lifecyclebot.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator

/**
 * V5.0.7009 — the motion the render has and the app did not.
 *
 * WHY THIS FILE EXISTS
 * ====================
 * V5.0.7007 fixed the surfaces and V5.0.7008 fixed the type, and between them
 * the app finally has the render's colour, depth and voice. What it still had
 * nothing of was MOVEMENT. Every chart in the render draws itself, every live
 * indicator breathes, the lane bars rise. Those are not decoration: on a screen
 * whose whole job is to report a machine that never stops, stillness reads as
 * "frozen", and the operator has repeatedly had to ask the log whether the bot
 * was alive because the UI could not tell them.
 *
 * Three views, deliberately small, each doing one thing the render does:
 *
 *   SparklineView7009   a price series that draws itself, with an optional
 *                       gradient fill and a pulsing head — the render's
 *                       equity curve and per-row position sparklines
 *   PulseDotView7009    a live indicator that breathes instead of sitting
 *                       still — the render's LIVE / REC / scanning dots
 *   LaneBarsView7009    a row of proportional bars that rise on data change —
 *                       the render's lane-pressure strip
 *
 * ANR DISCIPLINE. This app has a documented history of main-thread stalls
 * driven by UI work (V5.9.1229 disabled position-row logos for exactly that
 * reason; 6997's snapshot still showed 21.7% stall). So:
 *   - every animator is a single ValueAnimator per view, cancelled in
 *     onDetachedFromWindow, so a scrolled-away view costs nothing;
 *   - no allocation inside onDraw — Paint, Path and RectF are fields;
 *   - the pulse runs at 60fps for 1.6s per cycle and animates ONE float;
 *   - setSeries() ignores an unchanged series, so the 1Hz price tick does not
 *     restart a draw animation 80 times a second across a long list.
 * The 5.0.7003 snapshot measured 1 ANR hint and 0.2% stall, so there is room
 * for this — but it is written to stay cheap regardless.
 */

/** A self-drawing price line. Set [series]; it animates from empty to full. */
class SparklineView7009 @JvmOverloads constructor(
    ctx: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(ctx, attrs, defStyle) {

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val headPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    private val linePath = Path()
    private val fillPath = Path()

    private var series: FloatArray = FloatArray(0)
    private var progress = 1f
    private var pulse = 0f
    private var animator: ValueAnimator? = null
    private var pulseAnim: ValueAnimator? = null

    /** Stroke colour. Callers usually pass green for gain, red for loss. */
    var lineColor: Int = 0xFF34D399.toInt()
        set(v) { field = v; rebuildShader(); invalidate() }

    /** Fill under the line. 0 disables the fill (per-row sparklines). */
    var fillColor: Int = 0
        set(v) { field = v; rebuildShader(); invalidate() }

    var strokeWidthDp: Float = 2f
        set(v) { field = v; linePaint.strokeWidth = v * resources.displayMetrics.density; invalidate() }

    /** The pulsing head dot. Off for dense lists, on for the hero curve. */
    var showHead: Boolean = false
        set(v) { field = v; if (v) startPulse() else stopPulse(); invalidate() }

    init {
        linePaint.strokeWidth = strokeWidthDp * resources.displayMetrics.density
        haloPaint.strokeWidth = 1f * resources.displayMetrics.density
    }

    /**
     * Feed the view. An identical series is ignored so a 1Hz tick that did not
     * actually move the line cannot restart the draw animation.
     */
    fun setSeries(values: FloatArray, animate: Boolean = true) {
        if (values.size == series.size && values.contentEquals(series)) return
        series = values
        rebuildShader()
        if (!animate || values.size < 2) { progress = 1f; invalidate(); return }
        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 900L
            interpolator = android.view.animation.DecelerateInterpolator(1.6f)
            addUpdateListener { progress = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    private fun startPulse() {
        if (pulseAnim != null) return
        pulseAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1600L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = LinearInterpolator()
            addUpdateListener { pulse = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    private fun stopPulse() { pulseAnim?.cancel(); pulseAnim = null }

    private fun rebuildShader() {
        if (fillColor == 0 || height <= 0) { fillPaint.shader = null; return }
        fillPaint.shader = LinearGradient(
            0f, 0f, 0f, height.toFloat(),
            (fillColor and 0x00FFFFFF) or 0x66000000,
            fillColor and 0x00FFFFFF,
            Shader.TileMode.CLAMP,
        )
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh); rebuildShader()
    }

    override fun onDraw(canvas: Canvas) {
        val n = series.size
        if (n < 2 || width <= 0 || height <= 0) return

        var lo = series[0]; var hi = series[0]
        for (v in series) { if (v < lo) lo = v; if (v > hi) hi = v }
        val span = (hi - lo).takeIf { it > 1e-9f } ?: 1f

        val pad = linePaint.strokeWidth
        val usableH = height - pad * 2f
        val shown = (n * progress).toInt().coerceIn(2, n)

        linePath.reset()
        var lastX = 0f; var lastY = 0f
        for (i in 0 until shown) {
            val x = (i.toFloat() / (n - 1)) * width
            val y = pad + (1f - (series[i] - lo) / span) * usableH
            if (i == 0) linePath.moveTo(x, y) else linePath.lineTo(x, y)
            lastX = x; lastY = y
        }

        if (fillColor != 0 && fillPaint.shader != null) {
            fillPath.reset()
            fillPath.addPath(linePath)
            fillPath.lineTo(lastX, height.toFloat())
            fillPath.lineTo(0f, height.toFloat())
            fillPath.close()
            canvas.drawPath(fillPath, fillPaint)
        }

        linePaint.color = lineColor
        canvas.drawPath(linePath, linePaint)

        if (showHead && progress >= 1f) {
            val d = resources.displayMetrics.density
            headPaint.color = lineColor
            canvas.drawCircle(lastX, lastY, 3f * d, headPaint)
            haloPaint.color = (lineColor and 0x00FFFFFF) or ((0x99 * (1f - pulse)).toInt() shl 24)
            canvas.drawCircle(lastX, lastY, (4f + 5f * pulse) * d, haloPaint)
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel(); animator = null
        stopPulse()
        super.onDetachedFromWindow()
    }
}

/**
 * A breathing status dot. The render uses one everywhere the app is claiming
 * something is live; a static dot claims the same thing and proves nothing.
 */
class PulseDotView7009 @JvmOverloads constructor(
    ctx: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(ctx, attrs, defStyle) {

    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private var phase = 0f
    private var anim: ValueAnimator? = null

    var dotColor: Int = 0xFF34D399.toInt()
        set(v) { field = v; invalidate() }

    /** Stop the animation without removing the dot (paused / stopped states). */
    var beating: Boolean = true
        set(v) { field = v; if (v) start() else { anim?.cancel(); anim = null; phase = 0f; invalidate() } }

    init { ringPaint.strokeWidth = 1f * resources.displayMetrics.density }

    private fun start() {
        if (anim != null || !beating) return
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1500L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = LinearInterpolator()
            addUpdateListener { phase = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); start() }
    override fun onDetachedFromWindow() { anim?.cancel(); anim = null; super.onDetachedFromWindow() }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f; val cy = height / 2f
        val r = minOf(width, height) / 2f
        val core = r * 0.38f
        corePaint.color = (dotColor and 0x00FFFFFF) or ((0xFF - (0x9E * phase).toInt()) shl 24)
        canvas.drawCircle(cx, cy, core * (1f - 0.16f * phase), corePaint)
        ringPaint.color = (dotColor and 0x00FFFFFF) or ((0x77 * (1f - phase)).toInt() shl 24)
        canvas.drawCircle(cx, cy, core + (r - core) * phase, ringPaint)
    }
}

/**
 * The render's lane-pressure strip: proportional bars that rise into place.
 * One view instead of N child views, so a 13-lane strip is one draw pass.
 */
class LaneBarsView7009 @JvmOverloads constructor(
    ctx: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(ctx, attrs, defStyle) {

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val rect = RectF()

    private var values: FloatArray = FloatArray(0)
    private var colors: IntArray = IntArray(0)
    private var grow = 1f
    private var anim: ValueAnimator? = null

    /** values are 0..1; colors is parallel and may be shorter (it wraps). */
    fun setBars(v: FloatArray, c: IntArray, animate: Boolean = true) {
        if (v.size == values.size && v.contentEquals(values)) return
        values = v; colors = c
        if (!animate) { grow = 1f; invalidate(); return }
        anim?.cancel()
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 750L
            interpolator = android.view.animation.OvershootInterpolator(0.7f)
            addUpdateListener { grow = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val n = values.size
        if (n == 0 || width <= 0 || height <= 0) return
        val d = resources.displayMetrics.density
        val gap = 4f * d
        val bw = (width - gap * (n - 1)) / n
        val radius = 3f * d
        for (i in 0 until n) {
            val h = (values[i].coerceIn(0f, 1f) * height * grow).coerceAtLeast(2f * d)
            val left = i * (bw + gap)
            rect.set(left, height - h, left + bw, height.toFloat())
            barPaint.color = if (colors.isEmpty()) 0xFF22D3EE.toInt() else colors[i % colors.size]
            canvas.drawRoundRect(rect, radius, radius, barPaint)
        }
    }

    override fun onDetachedFromWindow() { anim?.cancel(); anim = null; super.onDetachedFromWindow() }
}
