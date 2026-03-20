package com.lifecyclebot.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * A single labelled score bar: [LABEL ████░░░░ 72]
 */
class ScoreBarView @JvmOverloads constructor(
    ctx: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(ctx, attrs, defStyle) {

    var label: String = "SCORE"
        set(v) { field = v; invalidate() }

    var value: Int = 0
        set(v) { field = v.coerceIn(0, 100); invalidate() }

    var barColor: Int = 0xFF00E5A0.toInt()
        set(v) { field = v; barPaint.color = v; invalidate() }

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF111720.toInt() }
    private val barPaint   = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF00E5A0.toInt() }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color     = 0xFF4A5E70.toInt()
        textSize  = 28f
        typeface  = android.graphics.Typeface.MONOSPACE
    }
    private val valPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color     = 0xFFDCE8F0.toInt()
        textSize  = 28f
        typeface  = android.graphics.Typeface.MONOSPACE
        textAlign = Paint.Align.RIGHT
    }

    private val rect = RectF()

    override fun onDraw(canvas: Canvas) {
        val h     = height.toFloat()
        val w     = width.toFloat()
        val lblW  = 120f
        val valW  = 80f
        val pad   = 12f
        val barH  = h * 0.4f
        val barY  = (h - barH) / 2f

        // label
        canvas.drawText(label, 0f, h * 0.72f, labelPaint)

        // track
        val trackX = lblW + pad
        val trackW = w - trackX - valW - pad
        rect.set(trackX, barY, trackX + trackW, barY + barH)
        canvas.drawRoundRect(rect, barH / 2, barH / 2, trackPaint)

        // fill
        val fillW = trackW * (value / 100f)
        if (fillW > 0) {
            rect.set(trackX, barY, trackX + fillW, barY + barH)
            canvas.drawRoundRect(rect, barH / 2, barH / 2, barPaint)
        }

        // value
        canvas.drawText("$value", w, h * 0.72f, valPaint)
    }
}
