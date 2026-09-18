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
 * ANR discipline as 7009/7020/7021: two animators, both cancelled on detach,
 * nothing allocated in onDraw, and NOTHING DRAWN AT ALL when the caller has
 * given it no items.
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

    private var rotateAnim: ValueAnimator? = null
    private var sweepAnim: ValueAnimator? = null
    private var sweepShader: Shader? = null

    /** How long each message holds before the next replaces it. */
    var holdMs: Long = 3_600L

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
        if (rotateAnim == null) {
            rotateAnim = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = holdMs
                repeatCount = ValueAnimator.INFINITE
                interpolator = android.view.animation.LinearInterpolator()
                addUpdateListener {
                    val f = it.animatedValue as Float
                    // Crossfade only in the last 12% of each hold, so the text
                    // is legible for the other 88% rather than perpetually
                    // half-faded.
                    fade = if (f > 0.88f) (1f - (f - 0.88f) / 0.12f) else 1f
                    pulse = kotlin.math.abs(kotlin.math.sin(f * Math.PI * 4).toFloat())
                    invalidate()
                }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationRepeat(a: android.animation.Animator) {
                        if (msgs.isNotEmpty()) index = (index + 1) % msgs.size
                    }
                })
                start()
            }
        }
        if (sweepAnim == null) {
            sweepAnim = ValueAnimator.ofFloat(-0.45f, 1.45f).apply {
                duration = 3_200L
                repeatCount = ValueAnimator.INFINITE
                interpolator = android.view.animation.LinearInterpolator()
                addUpdateListener { sweepX = it.animatedValue as Float; invalidate() }
                start()
            }
        }
    }

    private fun stopAll() {
        rotateAnim?.cancel(); rotateAnim = null
        sweepAnim?.cancel(); sweepAnim = null
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

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (msgs.isNotEmpty()) startAll()
    }

    override fun onDetachedFromWindow() {
        stopAll()
        super.onDetachedFromWindow()
    }
}
