package com.lifecyclebot.ui

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

/**
 * V5.0.6994 — the AATE vNext component kit, built from the operator's renders.
 *
 * WHY A SECOND FILE
 * =================
 * AateUi (V5.0.6977) holds the TOKENS and the crudest primitives — colours,
 * dp(), a flat stroked card, a KPI tile. It was written before I had the
 * renders, so it guessed at the shapes. This file holds the COMPONENTS the
 * renders actually use, and it reads its colours from AateUi so there is still
 * exactly one palette.
 *
 * WHAT THE RENDERS ACTUALLY ESTABLISH, as distinct from what I had assumed:
 *
 *  1. BORDERS GLOW. Every card in every render has a luminous hairline edge,
 *     not a flat 1px stroke. That single property is most of why the mockups
 *     read as a product and the current app reads as a debug view. Done here
 *     with a LayerDrawable of concentric rounded rects at decreasing alpha,
 *     which needs no API level above 21 and no bitmap work on the render path.
 *
 *  2. THE TYPE IS BIMODAL. Enormous bold figures ($1,188.82, NOT READY, 23%)
 *     against tiny wide-tracked uppercase captions (WIN RATE, TRADES, MCAP).
 *     There is almost nothing in between. The current screens are all middle:
 *     everything 10-13sp, which is why nothing has hierarchy.
 *
 *  3. COMPONENTS REPEAT ACROSS SCREENS. Segmented controls (1m/5m/15m/1h,
 *     ALL/LIVE/PAPER, MEME/CRYPTO/MARKETS, 1D/1W/1M/ALL), stat strips with
 *     vertical rules (MCAP | LIQ | 5m VOL | BUY%), labelled bar rows
 *     (TREND ---- 82), glow-ringed token avatars, status pills, module tiles
 *     (SNIP + ON). Build each once.
 *
 *  4. COLOUR CARRIES MEANING, CONSISTENTLY. Purple is the app and its primary
 *     action. Green is healthy/profit/online. Red is loss and NOT READY. Amber
 *     is degraded — never failure. Cyan is information and selection.
 *
 * DOCTRINE
 * ========
 * Presentation only. Nothing here reads engine state, makes a decision, or can
 * throw on a render path. Every builder is total and returns a usable View even
 * when handed nonsense.
 */
object AateComponents6994 {

    // Ground and surfaces sampled from the renders. Slightly deeper than the
    // 6977 tokens because the mockups sit on near-black with an aurora wash.
    const val GROUND = 0xFF05070F.toInt()
    const val SURFACE_TOP = 0xFF0B1424.toInt()
    const val SURFACE_BOTTOM = 0xFF080F1C.toInt()

    private fun dp(ctx: Context, v: Number) = AateUi.dp(ctx, v)

    // ── surfaces ──────────────────────────────────────────────────────────

    /**
     * A card whose border GLOWS. Three concentric rounded rects: a wide faint
     * halo, a mid ring, and the crisp inner hairline. Insetting each layer
     * inward keeps the halo outside the content box so nothing shifts.
     */
    fun glowCard(ctx: Context, accent: Int = AateUi.BLUE, radiusDp: Float = 20f): Drawable {
        val r = radiusDp * ctx.resources.displayMetrics.density
        val halo = GradientDrawable().apply {
            cornerRadius = r
            setStroke(maxOf(1, dp(ctx, 3)), AateUi.withAlpha(accent, 0x1A))
        }
        val ring = GradientDrawable().apply {
            cornerRadius = r
            setStroke(maxOf(1, dp(ctx, 2)), AateUi.withAlpha(accent, 0x3D))
        }
        val core = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(SURFACE_TOP, SURFACE_BOTTOM),
        ).apply {
            cornerRadius = r
            setStroke(maxOf(1, dp(ctx, 1)), AateUi.withAlpha(accent, 0x8A))
        }
        return LayerDrawable(arrayOf(halo, ring, core)).apply {
            setLayerInset(1, dp(ctx, 1), dp(ctx, 1), dp(ctx, 1), dp(ctx, 1))
            setLayerInset(2, dp(ctx, 2), dp(ctx, 2), dp(ctx, 2), dp(ctx, 2))
        }
    }

    /** A card container with the glow border and the renders' padding. */
    fun card(ctx: Context, accent: Int = AateUi.BLUE): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = glowCard(ctx, accent)
            setPadding(dp(ctx, 16), dp(ctx, 16), dp(ctx, 16), dp(ctx, 16))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { setMargins(dp(ctx, 14), dp(ctx, 8), dp(ctx, 14), dp(ctx, 8)) }
        }

    // ── typography ────────────────────────────────────────────────────────

    /**
     * The hero figure — $1,188.82, NOT READY, 23%. The renders run these at
     * roughly 34-40sp bold. Nothing else on a card competes with it.
     */
    fun hero(ctx: Context, text: CharSequence, color: Int = AateUi.TEXT, sizeSp: Float = 34f): TextView =
        TextView(ctx).apply {
            this.text = text
            setTextColor(color)
            textSize = sizeSp
            typeface = Typeface.DEFAULT_BOLD
            includeFontPadding = false
            letterSpacing = -0.01f
        }

    /**
     * The tiny wide-tracked uppercase caption that sits under every figure in
     * the renders: WIN RATE, TRADES, MCAP, PHASE.
     */
    fun caption(ctx: Context, text: CharSequence, color: Int = AateUi.TEXT_MUTED): TextView =
        TextView(ctx).apply {
            this.text = text.toString().uppercase()
            setTextColor(color)
            textSize = 9f
            letterSpacing = 0.16f
        }

    // ── segmented control ─────────────────────────────────────────────────

    /**
     * 1m / 5m / 15m / 1h, ALL / LIVE / PAPER, MEME / CRYPTO / MARKETS.
     * A pill-shaped track with the active option filled. [onSelect] receives
     * the chosen index; passing null makes it purely decorative.
     */
    fun segmented(
        ctx: Context,
        options: List<String>,
        activeIndex: Int = 0,
        accent: Int = AateUi.PURPLE,
        onSelect: ((Int) -> Unit)? = null,
    ): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        background = GradientDrawable().apply {
            setColor(AateUi.withAlpha(AateUi.STROKE_SOFT, 0x59))
            cornerRadius = 999f
            setStroke(maxOf(1, dp(ctx, 1)), AateUi.withAlpha(AateUi.STROKE_SOFT, 0xAA))
        }
        setPadding(dp(ctx, 3), dp(ctx, 3), dp(ctx, 3), dp(ctx, 3))
        options.forEachIndexed { i, label ->
            val active = i == activeIndex
            addView(TextView(ctx).apply {
                text = label
                gravity = Gravity.CENTER
                textSize = 11.5f
                setTextColor(if (active) AateUi.TEXT else AateUi.TEXT_MUTED)
                typeface = if (active) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                setPadding(dp(ctx, 14), dp(ctx, 7), dp(ctx, 14), dp(ctx, 7))
                if (active) {
                    background = GradientDrawable().apply {
                        setColor(AateUi.withAlpha(accent, 0xE6))
                        cornerRadius = 999f
                    }
                }
                if (onSelect != null) setOnClickListener { onSelect(i) }
            })
        }
    }

    // ── stat strip ────────────────────────────────────────────────────────

    /**
     * The value-over-caption row with vertical rules between columns:
     * MCAP | LIQ | 5m VOL | BUY%, and the six-up KPI row on the home screen.
     * Each item is (caption, value, valueColor).
     */
    fun statStrip(ctx: Context, items: List<Triple<String, String, Int>>): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            items.forEachIndexed { i, (cap, value, color) ->
                if (i > 0) {
                    addView(View(ctx).apply {
                        setBackgroundColor(AateUi.withAlpha(AateUi.STROKE_SOFT, 0xAA))
                        layoutParams = LinearLayout.LayoutParams(
                            maxOf(1, dp(ctx, 1)), ViewGroup.LayoutParams.MATCH_PARENT,
                        ).apply { topMargin = dp(ctx, 4); bottomMargin = dp(ctx, 4) }
                    })
                }
                addView(LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    addView(TextView(ctx).apply {
                        text = value
                        setTextColor(color)
                        textSize = 17f
                        typeface = Typeface.DEFAULT_BOLD
                        gravity = Gravity.CENTER
                        includeFontPadding = false
                    })
                    addView(caption(ctx, cap).apply {
                        gravity = Gravity.CENTER
                        setPadding(0, dp(ctx, 4), 0, 0)
                    })
                })
            }
        }

    // ── labelled bar row ──────────────────────────────────────────────────

    /**
     * TREND ------ 82. Caption on the left, a rounded rail, the figure on the
     * right. Used by the Decision Log and the ENTRY/EXIT/VOLUME/BUY% block.
     * [value] is 0..100.
     */
    fun barRow(
        ctx: Context,
        label: String,
        value: Double,
        accent: Int,
        showValue: Boolean = true,
        labelWidthDp: Int = 78,
    ): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(ctx, 5); bottomMargin = dp(ctx, 5) }

        addView(caption(ctx, label, AateUi.TEXT_SECONDARY).apply {
            layoutParams = LinearLayout.LayoutParams(dp(ctx, labelWidthDp), ViewGroup.LayoutParams.WRAP_CONTENT)
        })
        addView(AateUi.progressRail(ctx, (value / 100.0), accent).apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(ctx, 7), 1f).apply {
                marginStart = dp(ctx, 8)
                marginEnd = if (showValue) dp(ctx, 10) else 0
            }
        })
        if (showValue) {
            addView(TextView(ctx).apply {
                text = if (value % 1.0 == 0.0) value.toInt().toString() else "%.1f".format(value)
                setTextColor(AateUi.TEXT)
                textSize = 12.5f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.END
                layoutParams = LinearLayout.LayoutParams(dp(ctx, 30), ViewGroup.LayoutParams.WRAP_CONTENT)
            })
        }
    }

    // ── segmented meter ───────────────────────────────────────────────────

    /**
     * The SAFETY meter: discrete segments, the filled ones lit. Reads as a
     * rating rather than a continuous quantity, which is what a safety tier is.
     */
    fun segmentMeter(ctx: Context, filled: Int, total: Int = 5, accent: Int = AateUi.GREEN): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 6),
            )
            val n = total.coerceAtLeast(1)
            for (i in 0 until n) {
                addView(View(ctx).apply {
                    background = GradientDrawable().apply {
                        setColor(
                            if (i < filled) accent
                            else AateUi.withAlpha(AateUi.STROKE_SOFT, 0xCC),
                        )
                        cornerRadius = 999f
                    }
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                        .apply { if (i > 0) marginStart = dp(ctx, 4) }
                })
            }
        }

    // ── token avatar ──────────────────────────────────────────────────────

    /**
     * The circular token badge with its glow ring. Falls back to the symbol's
     * first character when there is no logo, which is the common case — the
     * renders show a letterform for `tunie` and an image for the rest.
     */
    fun tokenAvatar(ctx: Context, symbol: String, accent: Int = AateUi.PURPLE, sizeDp: Int = 44): TextView =
        TextView(ctx).apply {
            text = symbol.trim().take(1).uppercase()
            gravity = Gravity.CENTER
            setTextColor(accent)
            textSize = (sizeDp * 0.42f)
            typeface = Typeface.DEFAULT_BOLD
            includeFontPadding = false
            background = LayerDrawable(
                arrayOf(
                    GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setStroke(maxOf(1, dp(ctx, 3)), AateUi.withAlpha(accent, 0x2E))
                    },
                    GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(AateUi.withAlpha(accent, 0x1F))
                        setStroke(maxOf(1, dp(ctx, 1)), AateUi.withAlpha(accent, 0xB3))
                    },
                ),
            ).apply { setLayerInset(1, dp(ctx, 2), dp(ctx, 2), dp(ctx, 2), dp(ctx, 2)) }
            layoutParams = LinearLayout.LayoutParams(dp(ctx, sizeDp), dp(ctx, sizeDp))
        }

    // ── module tile ───────────────────────────────────────────────────────

    /**
     * SNIP + ON, MANIP + STANDBY. The small square status tiles. [state] drives
     * the dot colour: green online, amber standby, muted off.
     */
    fun moduleTile(ctx: Context, label: String, state: String): LinearLayout {
        val accent = when (state.trim().uppercase()) {
            "ON", "ONLINE", "ACTIVE", "LIVE" -> AateUi.GREEN
            "STANDBY", "DEGRADED", "WARM" -> AateUi.AMBER
            else -> AateUi.TEXT_MUTED
        }
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = glowCard(ctx, AateUi.STROKE, radiusDp = 14f)
            setPadding(dp(ctx, 6), dp(ctx, 10), dp(ctx, 6), dp(ctx, 10))
            addView(TextView(ctx).apply {
                text = label.uppercase()
                setTextColor(AateUi.TEXT)
                textSize = 10.5f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                letterSpacing = 0.06f
            })
            addView(TextView(ctx).apply {
                text = "● " + state.uppercase()
                setTextColor(accent)
                textSize = 8.5f
                gravity = Gravity.CENTER
                setPadding(0, dp(ctx, 4), 0, 0)
            })
        }
    }

    // ── gradient action button ────────────────────────────────────────────

    /**
     * BUY, GENERATE MAIN WALLET, CONNECT. A filled gradient pill — the only
     * thing on a card allowed to be this loud.
     */
    fun actionButton(
        ctx: Context,
        text: String,
        from: Int = AateUi.PURPLE,
        to: Int = AateUi.PURPLE_BRIGHT,
        onTap: (() -> Unit)? = null,
    ): TextView = TextView(ctx).apply {
        this.text = text.uppercase()
        gravity = Gravity.CENTER
        setTextColor(AateUi.TEXT)
        textSize = 14f
        typeface = Typeface.DEFAULT_BOLD
        letterSpacing = 0.06f
        background = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(from, to),
        ).apply { cornerRadius = 999f }
        setPadding(dp(ctx, 22), dp(ctx, 14), dp(ctx, 22), dp(ctx, 14))
        if (onTap != null) setOnClickListener { onTap() }
    }

    // ── position row ──────────────────────────────────────────────────────

    /**
     * One row of OPEN POSITIONS: avatar, symbol with its mode tag, a quantity
     * and value sub-line, and the P&L with a tinted percentage pill.
     */
    fun positionRow(
        ctx: Context,
        symbol: String,
        modeTag: String,
        subLine: String,
        pnlText: String,
        pctText: String,
        positive: Boolean,
    ): LinearLayout {
        val accent = if (positive) AateUi.GREEN else AateUi.RED
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(ctx, 9), 0, dp(ctx, 9))
            addView(tokenAvatar(ctx, symbol, accent, sizeDp = 38))
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { marginStart = dp(ctx, 11) }
                addView(LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(TextView(ctx).apply {
                        text = symbol.uppercase()
                        setTextColor(AateUi.TEXT)
                        textSize = 15f
                        typeface = Typeface.DEFAULT_BOLD
                    })
                    if (modeTag.isNotBlank()) {
                        addView(caption(ctx, modeTag, AateUi.TEXT_MUTED).apply {
                            setPadding(dp(ctx, 7), 0, 0, 0)
                        })
                    }
                })
                addView(TextView(ctx).apply {
                    text = subLine
                    setTextColor(AateUi.TEXT_MUTED)
                    textSize = 11f
                    setPadding(0, dp(ctx, 2), 0, 0)
                })
            })
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.END
                addView(TextView(ctx).apply {
                    text = pnlText
                    setTextColor(accent)
                    textSize = 15f
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.END
                })
                addView(AateUi.pill(ctx, pctText, accent).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply { topMargin = dp(ctx, 4); gravity = Gravity.END }
                })
            })
        }
    }

    // ── top app bar ───────────────────────────────────────────────────────

    /**
     * The AATE wordmark, network sub-label, and the two status pills the
     * renders carry on every screen: the mode selector and the health badge.
     */
    fun topBar(
        ctx: Context,
        network: String,
        modeText: String,
        healthText: String,
        healthAccent: Int,
    ): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(ctx, 16), dp(ctx, 10), dp(ctx, 16), dp(ctx, 10))
        addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(ctx).apply {
                text = "AATE"
                setTextColor(AateUi.TEXT)
                textSize = 26f
                typeface = Typeface.DEFAULT_BOLD
                letterSpacing = 0.10f
                includeFontPadding = false
            })
            addView(TextView(ctx).apply {
                text = network
                setTextColor(AateUi.TEXT_SECONDARY)
                textSize = 11f
                setPadding(0, dp(ctx, 2), 0, 0)
            })
        })
        addView(AateUi.pill(ctx, modeText, AateUi.PURPLE_BRIGHT))
        addView(AateUi.pill(ctx, healthText, healthAccent).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { marginStart = dp(ctx, 8) }
        })
    }

    /** The letter-spaced ENGINE caption that sits under the top bar. */
    fun engineCaption(ctx: Context): TextView =
        TextView(ctx).apply {
            text = "AUTONOMOUS TRADING ENGINE"
            setTextColor(AateUi.withAlpha(AateUi.CYAN, 0xB3))
            textSize = 9f
            letterSpacing = 0.22f
            gravity = Gravity.END
            setPadding(dp(ctx, 16), 0, dp(ctx, 16), dp(ctx, 8))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }

    // ── reason row ────────────────────────────────────────────────────────

    /**
     * The "Why?" bullets under NOT READY: a warning glyph and a plain sentence.
     * The renders use these to explain a refusal, which is the single most
     * useful thing this app can show an operator.
     */
    fun reasonRow(ctx: Context, text: String, accent: Int = AateUi.AMBER): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(ctx, 6), 0, dp(ctx, 6))
            addView(TextView(ctx).apply {
                this.text = "!"
                gravity = Gravity.CENTER
                setTextColor(accent)
                textSize = 11f
                typeface = Typeface.DEFAULT_BOLD
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setStroke(maxOf(1, dp(ctx, 1)), AateUi.withAlpha(accent, 0xCC))
                }
                layoutParams = LinearLayout.LayoutParams(dp(ctx, 20), dp(ctx, 20))
            })
            addView(TextView(ctx).apply {
                this.text = text
                setTextColor(AateUi.TEXT_SECONDARY)
                textSize = 12.5f
                setPadding(dp(ctx, 10), 0, 0, 0)
            })
        }
}
