package com.lifecyclebot.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View

/**
 * V5.0.7025 — the render's signal ticker, the one Main component that was
 * missing outright rather than merely plainer.
 *
 * project/Main.dc.html carries a card between the lane strip and the nav deck
 * that rotates through the engine's recent decisions:
 *
 *   ● MOONSHOT · BONKAI entry 4.21e-6              +312%
 *   ● BRIDGE · base→sol AERO settling                38s
 *   ● COUNCIL · quorum 4/6 conviction .74           HOLD
 *   ● REFUSED · CEX_REQUIRED not tradeable          SKIP
 *
 * with a pulsing dot and a shine sweeping across it every 3.2s. It is the only
 * place on the home screen that says what the bot just DID, as opposed to what
 * it currently holds, and the shipped Main had nothing equivalent — the nearest
 * thing was a single static status line above the button.
 *
 * WHY A CUSTOM VIEW RATHER THAN A TextView THAT SWAPS STRINGS. Two reasons, and
 * both are about not lying: the sweep and the rotation have to stop together
 * when there is nothing to show (a card that shines with no content reads as
 * live), and the crossfade has to be driven by the same clock as the rotation
 * or a message can be half-replaced at the moment it changes.
 *
 * ANR discipline (CORRECTED IN 7027): two loops, both owned by
 * AateLoopAnim7027, so they run only while this card is genuinely on screen
 * and repaint at ~20fps rather than 60. Nothing is allocated in onDraw, and
 * NOTHING IS DRAWN AT ALL when the caller has given it no items.
 *
 * The 7025 version of this comment claimed ANR discipline on the strength of
 * "cancelled on detach", which is not the same claim and was not enough: this
 * card sits on the home screen, so it stayed attached — and repainting — for
 * the whole life of the app. It cost the bot every execution in that build.
 */
class SignalTickerView7025 @JvmOverloads constructor(
    ctx: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(ctx, attrs, defStyle) {

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val msgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = android.graphics.Typeface.MONOSPACE
    }
    private val valPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD)
        textAlign = Paint.Align.RIGHT
    }
    private val sweepPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    private var msgs: Array<String> = arrayOf()
    private var vals: Array<String> = arrayOf()
    private var cols: IntArray = IntArray(0)

    private var index = 0
    private var fade = 1f
    private var sweepX = -0.4f
    private var pulse = 0f

    private var sweepShader: Shader? = null

    /**
     * How long each message holds before the next replaces it.
     *
     * V5.0.7027 — read once, when the loop is built. It was never reassigned
     * by any caller and a setter that silently did nothing would be worse than
     * a constant.
     */
    val holdMs: Long = 3_600L

    // V5.0.7027 — BOTH of these were raw INFINITE ValueAnimators calling
    // invalidate() on every one of their 60 frames a second, on a card that is
    // always on the home screen. Together with the ring orbit and the Markets
    // tape they are the 7025 regression: maxFrameGap 725ms -> 32,636ms,
    // stall 0.1% -> 16.5%, executions 17 -> 0. See AateLoopAnim7027.
    private val rotateLoop = AateLoopAnim7027(
        host = this, durationMs = holdMs,
    ) { f ->
        // Crossfade only in the last 12% of each hold, so the text is legible
        // for the other 88% rather than perpetually half-faded.
        fade = if (f > 0.88f) (1f - (f - 0.88f) / 0.12f) else 1f
        pulse = kotlin.math.abs(kotlin.math.sin(f * Math.PI * 4).toFloat())
    }.onRepeat {
        if (msgs.isNotEmpty()) index = (index + 1) % msgs.size
    }

    private val sweepLoop = AateLoopAnim7027(
        host = this, durationMs = 3_200L, from = -0.45f, to = 1.45f,
        frameMs = AateLoopAnim7027.FRAME_MS_SLOW,
    ) { v -> sweepX = v }

    init {
        val d = resources.displayMetrics.density
        msgPaint.textSize = 10.5f * d
        msgPaint.color = 0xFFE8EEFB.toInt()
        valPaint.textSize = 11f * d
    }

    /**
     * Replace the rotation. All three arrays must be the same length; an empty
     * set stops both animators and blanks the card, because a ticker sweeping
     * over nothing claims the engine is doing something it is not.
     */
    fun setSignals(messages: Array<String>, values: Array<String>, colours: IntArray) {
        if (messages.size != values.size || messages.size != colours.size) return
        if (messages.contentEquals(msgs) && values.contentEquals(vals) && colours.contentEquals(cols)) return
        msgs = messages.copyOf()
        vals = values.copyOf()
        cols = colours.copyOf()
        if (index >= msgs.size) index = 0
        if (msgs.isEmpty()) { stopAll(); invalidate(); return }
        startAll()
        invalidate()
    }

    private fun startAll() {
        rotateLoop.request(true)
        sweepLoop.request(true)
    }

    private fun stopAll() {
        rotateLoop.request(false)
        sweepLoop.request(false)
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        sweepShader = null
    }

    override fun onDraw(canvas: Canvas) {
        if (width <= 0 || height <= 0 || msgs.isEmpty()) return
        val d = resources.displayMetrics.density
        val i = index.coerceIn(0, msgs.size - 1)
        val accent = cols[i % cols.size]
        val alpha = (255 * fade).toInt().coerceIn(0, 255)

        // sweeping shine, clipped to the card's own rounded shape
        if (sweepShader == null) {
            sweepShader = LinearGradient(
                0f, 0f, width * 0.42f, 0f,
                intArrayOf(0x00BEE1FF, 0x33BEE1FF, 0x00BEE1FF),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        val r = 15f * d
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.save()
        canvas.clipRect(rect)
        canvas.translate(sweepX * width, 0f)
        sweepPaint.shader = sweepShader
        canvas.drawRect(0f, 0f, width * 0.42f, height.toFloat(), sweepPaint)
        sweepPaint.shader = null
        canvas.restore()

        // pulsing dot
        val cy = height / 2f
        val dotX = 13f * d
        dotPaint.color = accent
        dotPaint.alpha = alpha
        canvas.drawCircle(dotX, cy, 3f * d, dotPaint)
        dotPaint.alpha = (70 * (1f - pulse) * fade).toInt().coerceIn(0, 255)
        canvas.drawCircle(dotX, cy, (3f + 4f * pulse) * d, dotPaint)
        dotPaint.alpha = 255

        // value first, so the message can be clipped against what is left
        valPaint.color = accent
        valPaint.alpha = alpha
        val valTxt = vals[i % vals.size]
        val valW = valPaint.measureText(valTxt)
        val baseline = cy - (msgPaint.descent() + msgPaint.ascent()) / 2f
        canvas.drawText(valTxt, width - 13f * d, baseline, valPaint)

        msgPaint.alpha = alpha
        val msgLeft = dotX + 9f * d
        val avail = (width - 13f * d - valW - 10f * d) - msgLeft
        if (avail > 8f * d) {
            val txt = msgs[i]
            val cut = msgPaint.breakText(txt, true, avail, null)
            canvas.drawText(
                if (cut < txt.length) txt.substring(0, cut.coerceAtLeast(1)).trimEnd() + "…" else txt,
                msgLeft, baseline, msgPaint,
            )
        }
        msgPaint.alpha = 255
        valPaint.alpha = 255
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); syncLoops() }
    override fun onWindowVisibilityChanged(v: Int) { super.onWindowVisibilityChanged(v); syncLoops() }
    override fun onVisibilityAggregated(isVisible: Boolean) { super.onVisibilityAggregated(isVisible); syncLoops() }

    private fun syncLoops() { rotateLoop.sync(); sweepLoop.sync() }

    override fun onDetachedFromWindow() {
        rotateLoop.release()
        sweepLoop.release()
        super.onDetachedFromWindow()
    }
}
