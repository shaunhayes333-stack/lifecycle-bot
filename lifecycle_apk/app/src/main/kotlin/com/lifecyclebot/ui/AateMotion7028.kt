package com.lifecyclebot.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View

/**
 * V5.0.7028 — the three render components the small screens were missing.
 *
 * Insiders, Watchlist and Unlock were the flattest screens left: a title, a
 * row of tabs and a vertical list of text, on three renders that carry a flow
 * graph, per-row sparklines and a breathing lock. They are also the three
 * screens with the LEAST Kotlin behind them, which is why they kept getting
 * skipped — nothing needed fixing there, so nothing got looked at.
 *
 * ANR discipline, from 7027 and non-negotiable after what 7025 cost: every
 * looping animator here is an AateLoopAnim7027, so it runs only while its view
 * is genuinely on screen and repaints at ~15-20fps. Nothing allocates in
 * onDraw; the one per-frame object (the dash phase) is built in the frame
 * callback instead, which runs at the throttled rate rather than at 60Hz.
 */

/**
 * project/Insiders.dc.html carries a NET FLOW · 1H card: six dashed lines,
 * one per tracked wallet cluster, each in its own colour and weighted by the
 * size of the flow, converging from source dots on the left into a POOL block
 * on the right, with the dashes marching along the lines.
 *
 * It is the only thing on that screen that says which way the smart money is
 * MOVING. The shipped screen had a tab bar and a list of addresses, so the
 * one question the screen exists to answer — in or out? — was not on it.
 */
class FlowGraphView7028 @JvmOverloads constructor(
    ctx: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(ctx, attrs, defStyle) {

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val poolFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val poolStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val poolLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val rect = RectF()

    private var colours: IntArray = IntArray(0)
    private var weights: FloatArray = FloatArray(0)
    private var phase = 0f
    private var dash: DashPathEffect? = null

    /** Colour of the pool block and its label. Green for net-in, red for net-out. */
    var poolColor: Int = 0xFF34D399.toInt()
        set(v) {
            field = v
            poolStroke.color = v
            poolFill.color = (v and 0x00FFFFFF) or 0x33000000
            poolLabel.color = v
            invalidate()
        }

    // The dash marches by advancing the phase; a NEGATIVE step makes it travel
    // left-to-right, i.e. toward the pool, which is the direction the numbers
    // above the graph claim. A positive step would animate money leaving while
    // the caption said it was arriving.
    private val marchLoop = AateLoopAnim7027(
        host = this, durationMs = 1_400L, to = 28f,
        frameMs = AateLoopAnim7027.FRAME_MS_SLOW,
    ) { p ->
        phase = p
        val d = resources.displayMetrics.density
        dash = DashPathEffect(floatArrayOf(6f * d, 8f * d), -p * d)
    }

    init {
        val d = resources.displayMetrics.density
        poolStroke.strokeWidth = 1.2f * d
        poolLabel.textSize = 9f * d
        // Property initialisers do not run setters, so the three paints that
        // poolColor's setter owns would otherwise keep their default colours.
        poolColor = 0xFF34D399.toInt()
    }

    /**
     * One entry per flow. Weight scales the line thickness and is expected to
     * be a share of the total, not an absolute — the graph shows proportion.
     * An empty set stops the march and draws nothing, because a flow graph
     * animating over no flows claims activity that is not there.
     */
    fun setFlows(lineColours: IntArray, lineWeights: FloatArray) {
        if (lineColours.size != lineWeights.size) return
        if (lineColours.contentEquals(colours) && lineWeights.contentEquals(weights)) return
        colours = lineColours.copyOf()
        weights = lineWeights.copyOf()
        marchLoop.request(colours.isNotEmpty())
        invalidate()
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); marchLoop.sync() }
    override fun onWindowVisibilityChanged(v: Int) { super.onWindowVisibilityChanged(v); marchLoop.sync() }
    override fun onVisibilityAggregated(isVisible: Boolean) { super.onVisibilityAggregated(isVisible); marchLoop.sync() }
    override fun onDetachedFromWindow() { marchLoop.release(); super.onDetachedFromWindow() }

    override fun onDraw(canvas: Canvas) {
        if (width <= 0 || height <= 0 || colours.isEmpty()) return
        val d = resources.displayMetrics.density
        val leftX = 8f * d
        val poolW = 20f * d
        val rightX = width - poolW - 3f * d

        // Pool block first, so the lines land ON it rather than under it.
        val poolTop = height * 0.18f
        val poolBottom = height * 0.82f
        rect.set(rightX, poolTop, rightX + poolW, poolBottom)
        canvas.drawRoundRect(rect, 6f * d, 6f * d, poolFill)
        canvas.drawRoundRect(rect, 6f * d, 6f * d, poolStroke)
        val poolMidY = (poolTop + poolBottom) / 2f
        canvas.save()
        canvas.rotate(-90f, rect.centerX(), poolMidY)
        canvas.drawText("POOL", rect.centerX(), poolMidY + poolLabel.textSize * 0.36f, poolLabel)
        canvas.restore()

        val n = colours.size
        val span = height - 16f * d
        linePaint.pathEffect = dash
        for (i in 0 until n) {
            val srcY = 8f * d + span * (if (n == 1) 0.5f else i / (n - 1f))
            // Each line's landing point on the pool drifts with the march, so
            // the bundle breathes rather than sitting as a static fan.
            val drift = kotlin.math.sin((phase / 28f * 2f * Math.PI + i).toFloat()) * (span * 0.12f)
            val dstY = poolMidY + drift
            linePaint.color = (colours[i] and 0x00FFFFFF) or 0x61000000
            linePaint.strokeWidth = (1f + (weights.getOrElse(i) { 0.5f }.coerceIn(0f, 1f)) * 2.4f) * d
            canvas.drawLine(leftX, srcY, rightX, dstY, linePaint)
            dotPaint.color = colours[i]
            canvas.drawCircle(leftX, srcY, 3.4f * d, dotPaint)
        }
        linePaint.pathEffect = null
    }
}

/**
 * project/Security.dc.html shows the PIN as six dots, not as a text field.
 *
 * That is not only a look: a masked EditText tells the viewer how many digits
 * they have typed only if they trust the dot glyphs it chose, and on this
 * screen the answer matters — six is the whole PIN. The dots are explicit.
 */
class PinDotsView7028 @JvmOverloads constructor(
    ctx: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(ctx, attrs, defStyle) {

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    private var total = 6
    private var filled = 0

    var dotColor: Int = 0xFF22D3EE.toInt()
        set(v) { field = v; fillPaint.color = v; invalidate() }

    init {
        fillPaint.color = dotColor
        edgePaint.strokeWidth = 1f * resources.displayMetrics.density
        edgePaint.color = 0x5722D3EE
    }

    /** How many digits are entered, out of how many the PIN needs. */
    fun setProgress(entered: Int, of: Int = 6) {
        val e = entered.coerceIn(0, of)
        if (e == filled && of == total) return
        filled = e
        total = of.coerceAtLeast(1)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (width <= 0 || height <= 0) return
        val d = resources.displayMetrics.density
        val r = 6.5f * d
        val gap = 14f * d
        val runW = total * (r * 2f) + (total - 1) * gap
        var x = (width - runW) / 2f + r
        val cy = height / 2f
        for (i in 0 until total) {
            if (i < filled) {
                canvas.drawCircle(x, cy, r, fillPaint)
            } else {
                canvas.drawCircle(x, cy, r - edgePaint.strokeWidth / 2f, edgePaint)
            }
            x += r * 2f + gap
        }
    }
}

/**
 * The unlock screen's two concentric rings, each scaling out and fading, the
 * second offset by a fraction of the cycle so the pair reads as a pulse
 * travelling outward rather than as two rings breathing in step.
 *
 * Android cannot put a coloured outer glow on a shape, which is what the
 * render's `box-shadow: 0 0 32px` does. Drawing more static strokes to
 * approximate it is what 7013-7019 tried and it produced hard rings, not
 * bloom. Motion is the honest substitute: the eye reads an expanding fade as
 * light, and a still one as an outline.
 */
class PulseRingsView7028 @JvmOverloads constructor(
    ctx: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(ctx, attrs, defStyle) {

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private var phase = 0f

    var ringA: Int = 0xFF22D3EE.toInt()
        set(v) { field = v; invalidate() }

    var ringB: Int = 0xFF8B5CF6.toInt()
        set(v) { field = v; invalidate() }

    private val pulseLoop = AateLoopAnim7027(
        host = this, durationMs = 2_600L,
        frameMs = AateLoopAnim7027.FRAME_MS_SLOW,
    ) { p -> phase = p }

    init {
        ringPaint.strokeWidth = 1.5f * resources.displayMetrics.density
        pulseLoop.request(true)
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); pulseLoop.sync() }
    override fun onWindowVisibilityChanged(v: Int) { super.onWindowVisibilityChanged(v); pulseLoop.sync() }
    override fun onVisibilityAggregated(isVisible: Boolean) { super.onVisibilityAggregated(isVisible); pulseLoop.sync() }
    override fun onDetachedFromWindow() { pulseLoop.release(); super.onDetachedFromWindow() }

    override fun onDraw(canvas: Canvas) {
        if (width <= 0 || height <= 0) return
        val cx = width / 2f
        val cy = height / 2f
        val base = minOf(width, height) / 2f - ringPaint.strokeWidth
        drawRing(canvas, cx, cy, base, phase, ringA)
        // .23 of a cycle behind, matching the render's .6s offset on 2.6s.
        drawRing(canvas, cx, cy, base, (phase + 0.77f) % 1f, ringB)
    }

    private fun drawRing(canvas: Canvas, cx: Float, cy: Float, base: Float, t: Float, colour: Int) {
        // Scale 1.00 -> 1.10 and fade .50 -> .12 across the cycle, then back:
        // the render's ringPulse keyframes, which peak at the midpoint.
        val tri = 1f - kotlin.math.abs(t * 2f - 1f)
        val scale = 1f + 0.10f * tri
        val alpha = (0.50f - 0.38f * tri).coerceIn(0f, 1f)
        ringPaint.color = (colour and 0x00FFFFFF) or ((alpha * 255f).toInt() shl 24)
        canvas.drawCircle(cx, cy, base * scale, ringPaint)
    }
}

/**
 * The render's rounded gradient tile — the lock on Unlock, the avatar squares
 * on Watchlist and Insiders. A gradient-filled rounded square with a short
 * label centred on it.
 *
 * Android can express this as a shape drawable plus a TextView, and that is
 * what the shipped screens do, but a `<gradient>` in a shape drawable is a
 * FIXED pair of colours, so each lane needed its own drawable file and the
 * per-row colours the render varies by score could not be expressed at all.
 * One view that takes its two colours at bind time replaces all of them.
 */
class GradientTileView7028 @JvmOverloads constructor(
    ctx: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(ctx, attrs, defStyle) {

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val rect = RectF()
    private var shader: Shader? = null

    private var c0 = 0xFF8B5CF6.toInt()
    private var c1 = 0xFF22D3EE.toInt()
    private var label = ""

    /** Corner radius in dp. The render uses 10 on avatars, 22 on the lock. */
    var cornerDp: Float = 10f
        set(v) { field = v; invalidate() }

    var labelSizeSp: Float = 11f
        set(v) { field = v; textPaint.textSize = v * resources.displayMetrics.scaledDensity; invalidate() }

    init {
        textPaint.textSize = labelSizeSp * resources.displayMetrics.scaledDensity
        textPaint.color = 0xFF04060D.toInt()
    }

    fun setTile(text: String, from: Int, to: Int, textColor: Int) {
        if (text == label && from == c0 && to == c1 && textColor == textPaint.color) return
        label = text
        c0 = from
        c1 = to
        textPaint.color = textColor
        shader = null
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        shader = null
    }

    override fun onDraw(canvas: Canvas) {
        if (width <= 0 || height <= 0) return
        val d = resources.displayMetrics.density
        if (shader == null) {
            // 140deg in CSS is top-left to bottom-right on a square.
            shader = LinearGradient(
                0f, 0f, width.toFloat(), height.toFloat(),
                c0, c1, Shader.TileMode.CLAMP,
            )
        }
        bgPaint.shader = shader
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(rect, cornerDp * d, cornerDp * d, bgPaint)
        bgPaint.shader = null
        if (label.isNotEmpty()) {
            canvas.drawText(
                label, width / 2f,
                height / 2f - (textPaint.descent() + textPaint.ascent()) / 2f,
                textPaint,
            )
        }
    }
}
