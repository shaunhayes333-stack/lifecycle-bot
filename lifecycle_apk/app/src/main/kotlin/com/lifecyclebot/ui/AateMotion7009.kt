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
        super.onSizeChanged(w, h, ow, oh)
        rebuildShader()
        // Width-dependent, so it must not survive a resize.
        strokeShader = null
    }

    // V5.0.7025 — the render's equity curve is not a single-colour line.
    // It carries three horizontal gridlines behind it and a LEFT-TO-RIGHT
    // gradient stroke (violet -> cyan -> green), so the curve reads as a
    // journey across the card rather than one flat trace. Both are opt-in so
    // every existing caller keeps the plain line.
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 1f
        color = 0x177CC4FF
    }
    private var strokeShader: android.graphics.Shader? = null

    /** Draw the render's 3 horizontal rules behind the curve. */
    var showGrid: Boolean = false
        set(v) { field = v; invalidate() }

    /**
     * Stroke the line with a left-to-right gradient through these colours.
     * Empty restores the flat [lineColor].
     */
    var strokeGradient: IntArray = IntArray(0)
        set(v) { field = v.copyOf(); strokeShader = null; invalidate() }

    override fun onDraw(canvas: Canvas) {
        val n = series.size
        if (n < 2 || width <= 0 || height <= 0) return

        if (showGrid) {
            // Thirds, matching the render's y = 18 / 38 / 58 over a 74 box.
            for (k in 1..3) {
                val y = height * (k / 4f)
                canvas.drawLine(0f, y, width.toFloat(), y, gridPaint)
            }
        }

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

        if (strokeGradient.size >= 2) {
            if (strokeShader == null) {
                strokeShader = android.graphics.LinearGradient(
                    0f, 0f, width.toFloat(), 0f,
                    strokeGradient, null,
                    android.graphics.Shader.TileMode.CLAMP,
                )
            }
            linePaint.shader = strokeShader
            canvas.drawPath(linePath, linePaint)
            linePaint.shader = null
        } else {
            linePaint.color = lineColor
            canvas.drawPath(linePath, linePaint)
        }

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
    private val tagPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = 0xFF6E82A8.toInt()
    }
    private val rect = RectF()

    private var values: FloatArray = FloatArray(0)
    private var colors: IntArray = IntArray(0)
    private var tags: Array<String> = arrayOf()
    private var shaders: Array<android.graphics.Shader?> = arrayOf()
    private var grow = 1f
    private var anim: ValueAnimator? = null

    /**
     * V5.0.7025 — the render labels every lane bar (QLTY / BLUE / MOON / SHIT
     * / SNPR …). Without a tag the strip shows that SOMETHING is busy and not
     * WHICH, which is the only actionable half. Empty leaves the bars bare, so
     * existing callers are unchanged.
     */
    fun setTags(t: Array<String>) {
        if (t.contentEquals(tags)) return
        tags = t.copyOf()
        invalidate()
    }

    /** values are 0..1; colors is parallel and may be shorter (it wraps). */
    fun setBars(v: FloatArray, c: IntArray, animate: Boolean = true) {
        if (v.size == values.size && v.contentEquals(values)) return
        values = v; colors = c
        shaders = arrayOfNulls(v.size)
        if (!animate) { grow = 1f; invalidate(); return }
        anim?.cancel()
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 750L
            interpolator = android.view.animation.OvershootInterpolator(0.7f)
            addUpdateListener { grow = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        shaders = arrayOfNulls(values.size)
    }

    override fun onDraw(canvas: Canvas) {
        val n = values.size
        if (n == 0 || width <= 0 || height <= 0) return
        val d = resources.displayMetrics.density
        val gap = 4f * d
        val bw = (width - gap * (n - 1)) / n
        val radius = 3f * d

        // V5.0.7025 — reserve the tag row, so bars never overlap their labels.
        val hasTags = tags.isNotEmpty()
        tagPaint.textSize = 7.5f * d
        val tagRow = if (hasTags) 11f * d else 0f
        val barArea = (height - tagRow).coerceAtLeast(4f * d)

        for (i in 0 until n) {
            val h = (values[i].coerceIn(0f, 1f) * barArea * grow).coerceAtLeast(2f * d)
            val left = i * (bw + gap)
            val top = barArea - h
            val col = if (colors.isEmpty()) 0xFF22D3EE.toInt() else colors[i % colors.size]

            // The render fills each bar with a gradient from its colour down to
            // a transparent tail rather than a flat block. Shader built here
            // and cached — never inside the draw of a later frame.
            var sh = shaders.getOrNull(i)
            if (sh == null) {
                sh = android.graphics.LinearGradient(
                    0f, top, 0f, barArea,
                    col, (col and 0x00FFFFFF) or 0x3A000000,
                    android.graphics.Shader.TileMode.CLAMP,
                )
                if (i < shaders.size) shaders[i] = sh
            }
            barPaint.shader = sh
            rect.set(left, top, left + bw, barArea)
            canvas.drawRoundRect(rect, radius, radius, barPaint)
            barPaint.shader = null

            if (hasTags && i < tags.size) {
                canvas.drawText(tags[i], left + bw / 2f, height - 1.5f * d, tagPaint)
            }
        }
    }

    override fun onDetachedFromWindow() { anim?.cancel(); anim = null; super.onDetachedFromWindow() }
}

/**
 * V5.0.7011 — the render's health ring.
 *
 * A single arc that sweeps to its value on change, with the number inside it.
 * The render puts this beside the equity figure because the two answer
 * different questions — "how much" and "is the machine well" — and the app
 * previously buried health in a text pill among five other pills, where it read
 * as one more label rather than as a gauge.
 *
 * Same discipline as the rest of this file: one animator, cancelled on detach,
 * no allocation in onDraw.
 */
class RingGaugeView7010 @JvmOverloads constructor(
    ctx: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(ctx, attrs, defStyle) {

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val capPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val oval = RectF()

    private var target = 0f
    private var shown = 0f
    private var anim: ValueAnimator? = null

    // V5.0.7025 — the render wraps the health ring in a dashed outer ring that
    // rotates (ringSpin, 6s linear, infinite). It is the one piece of motion on
    // the hero that runs whether or not the numbers move, which is what makes
    // the card feel live rather than merely rendered.
    private val orbitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0x668B5CF6
    }
    private var orbitDeg = 0f
    private var orbitAnim: ValueAnimator? = null

    /** Set false for a still ring (used where the gauge is decorative). */
    var orbiting: Boolean = true
        set(v) { field = v; if (v) startOrbit() else { orbitAnim?.cancel(); orbitAnim = null; invalidate() } }

    var caption: String = "HEALTH"
        set(v) { field = v; invalidate() }

    var ringColor: Int = 0xFF34D399.toInt()
        set(v) { field = v; arcPaint.color = v; invalidate() }

    var textColor: Int = 0xFFE8EEFB.toInt()
        set(v) { field = v; labelPaint.color = v; invalidate() }

    init {
        val d = resources.displayMetrics.density
        trackPaint.strokeWidth = 5f * d
        trackPaint.color = 0x1F7CC4FF
        arcPaint.strokeWidth = 5f * d
        arcPaint.color = ringColor
        labelPaint.color = textColor
        labelPaint.isFakeBoldText = true
        capPaint.color = 0xFF6E82A8.toInt()
        orbitPaint.strokeWidth = 1f * d
        orbitPaint.pathEffect = android.graphics.DashPathEffect(floatArrayOf(3f * d, 7f * d), 0f)
    }

    private fun startOrbit() {
        orbitAnim?.cancel()
        orbitAnim = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 6_000L
            repeatCount = ValueAnimator.INFINITE
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener { orbitDeg = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (orbiting && orbitAnim == null) startOrbit()
    }

    /** value is 0..1. */
    fun setValue(v: Float, animate: Boolean = true) {
        val t = v.coerceIn(0f, 1f)
        if (kotlin.math.abs(t - target) < 0.005f) return
        target = t
        if (!animate) { shown = t; invalidate(); return }
        anim?.cancel()
        val from = shown
        anim = ValueAnimator.ofFloat(from, t).apply {
            duration = 850L
            interpolator = android.view.animation.DecelerateInterpolator(1.8f)
            addUpdateListener { shown = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (width <= 0 || height <= 0) return
        val d = resources.displayMetrics.density
        val inset = arcPaint.strokeWidth / 2f + 1f
        val size = minOf(width, height).toFloat()
        val cx = width / 2f
        val cy = height / 2f
        oval.set(cx - size / 2f + inset, cy - size / 2f + inset, cx + size / 2f - inset, cy + size / 2f - inset)
        canvas.drawArc(oval, -90f, 360f, false, trackPaint)
        canvas.drawArc(oval, -90f, 360f * shown, false, arcPaint)

        // Dashed orbit, outside the arc, rotating. Drawn last of the rings so a
        // full gauge never hides it.
        if (orbiting) {
            val oInset = inset + arcPaint.strokeWidth * 0.9f
            oval.set(
                cx - size / 2f + oInset - 5f * d, cy - size / 2f + oInset - 5f * d,
                cx + size / 2f - oInset + 5f * d, cy + size / 2f - oInset + 5f * d,
            )
            canvas.save()
            canvas.rotate(orbitDeg, cx, cy)
            canvas.drawArc(oval, 0f, 360f, false, orbitPaint)
            canvas.restore()
            // Restore the value-arc oval for anything drawn after this point.
            oval.set(cx - size / 2f + inset, cy - size / 2f + inset, cx + size / 2f - inset, cy + size / 2f - inset)
        }

        labelPaint.textSize = size * 0.30f
        canvas.drawText("${(shown * 100f).toInt()}", cx, cy + labelPaint.textSize * 0.16f, labelPaint)
        capPaint.textSize = size * 0.13f
        canvas.drawText(caption, cx, cy + size * 0.30f, capPaint)
    }

    override fun onDetachedFromWindow() {
        anim?.cancel(); anim = null
        orbitAnim?.cancel(); orbitAnim = null
        super.onDetachedFromWindow()
    }
}
