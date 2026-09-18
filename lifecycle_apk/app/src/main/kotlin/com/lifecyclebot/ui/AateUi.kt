package com.lifecyclebot.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

/**
 * V5.0.6977 — AATE vNext design system, for the UI that is painted in Kotlin.
 *
 * WHY THIS FILE EXISTS
 * ====================
 * V5.0.6931-6939 restyled the app and the operator reported that nothing
 * changed except the splash screen. That report was correct, and the reason is
 * structural rather than cosmetic: the restyle was applied to `res/layout/*.xml`
 * and `res/values/{colors,dimens}.xml`, but almost none of what is on screen
 * comes from those files.
 *
 * Counting the source:
 *
 *     MainActivity.kt        246 addView() calls, 107 views constructed in code
 *     CryptoAltActivity.kt   468 addView() calls
 *     MultiAssetActivity.kt   59 addView() calls
 *     ---------------------------------------------------------------
 *     704 hardcoded 0xAARRGGBB literals and ~300 hardcoded textSize values
 *     across ui/*.kt
 *
 * The XML files define the shell. Every card, row, stat, badge and number
 * inside the shell is built at runtime in Kotlin with literal colours, literal
 * sp sizes and `setBackgroundColor(parseColor("#0D192B"))` — a flat rectangle.
 * Editing colors.xml cannot reach any of it. The splash screen changed because
 * the splash is one of the few screens that really is its XML.
 *
 * So this is the missing half of the design system: the same vNext tokens,
 * expressed as Kotlin, plus the surface/typography builders the painted screens
 * need so a card can stop being a flat rectangle.
 *
 * DOCTRINE
 * ========
 * - Presentation only. Nothing here reads engine state, makes a decision, or
 *   can throw into a render path. Every builder is total.
 * - Tokens mirror res/values/colors.xml exactly. One palette, two languages.
 * - Sizes are dp/sp in, pixels out. Call sites stop doing
 *   `(14 * resources.displayMetrics.density).toInt()` by hand.
 */
object AateUi {

    // ── Palette — mirrors res/values/colors.xml ───────────────────────────
    const val BG = 0xFF030712.toInt()
    const val BG_DEEP = 0xFF01040B.toInt()
    const val SURFACE = 0xFF0A1424.toInt()
    const val SURFACE_2 = 0xFF0D192B.toInt()
    const val SURFACE_3 = 0xFF101E33.toInt()
    const val STROKE = 0xFF2B4B78.toInt()
    const val STROKE_SOFT = 0xFF193250.toInt()
    const val TEXT = 0xFFF5F7FF.toInt()
    const val TEXT_SECONDARY = 0xFFA7B7D8.toInt()
    const val TEXT_MUTED = 0xFF63759B.toInt()
    const val PURPLE = 0xFF9A4DFF.toInt()
    const val PURPLE_BRIGHT = 0xFFB36BFF.toInt()
    const val BLUE = 0xFF4C8DFF.toInt()
    const val CYAN = 0xFF31C7FF.toInt()
    const val GREEN = 0xFF16E6A1.toInt()
    const val AMBER = 0xFFFFB020.toInt()
    const val RED = 0xFFFF4D6D.toInt()

    // ── Geometry ──────────────────────────────────────────────────────────
    const val RADIUS_CARD_DP = 16f
    const val RADIUS_TILE_DP = 10f
    const val RADIUS_PILL_DP = 999f

    /** dp → px for this display. */
    fun dp(ctx: Context, value: Number): Int =
        (value.toFloat() * ctx.resources.displayMetrics.density).toInt()

    /**
     * Semantic colour for a signed number. One rule, so a positive figure is
     * never green on one card and cyan on the next.
     */
    fun signed(value: Double): Int = when {
        !value.isFinite() -> TEXT_MUTED
        value > 0.0 -> GREEN
        value < 0.0 -> RED
        else -> TEXT_SECONDARY
    }

    /** Semantic colour for a 0-100 win-rate style percentage. */
    fun rateColor(pct: Number): Int {
        val v = pct.toDouble()
        return when {
            !v.isFinite() -> TEXT_MUTED
            v >= 50.0 -> GREEN
            v >= 35.0 -> AMBER
            else -> RED
        }
    }

    // ── Surfaces ──────────────────────────────────────────────────────────

    /**
     * The card surface: rounded, stroked, with a vertical surface gradient.
     *
     * This replaces `setBackgroundColor(parseColor("#0D192B"))` — a flat,
     * square-cornered block, which is the single biggest reason the restyled
     * screens still read as the old screens.
     */
    fun cardBackground(
        ctx: Context,
        top: Int = SURFACE_2,
        bottom: Int = SURFACE,
        stroke: Int = STROKE_SOFT,
        radiusDp: Float = RADIUS_CARD_DP,
    ): GradientDrawable = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(top, bottom),
    ).apply {
        cornerRadius = radiusDp * ctx.resources.displayMetrics.density
        setStroke(maxOf(1, dp(ctx, 1)), stroke)
    }

    /** A filled inset tile — used for KPI cells and nested state blocks. */
    fun tileBackground(
        ctx: Context,
        fill: Int = SURFACE_3,
        stroke: Int = STROKE_SOFT,
        radiusDp: Float = RADIUS_TILE_DP,
    ): GradientDrawable = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = radiusDp * ctx.resources.displayMetrics.density
        setStroke(maxOf(1, dp(ctx, 1)), stroke)
    }

    /**
     * A status pill. [accent] tints both the fill and the border, so one colour
     * argument produces a coherent badge instead of a bare coloured word.
     */
    fun pillBackground(ctx: Context, accent: Int): GradientDrawable = GradientDrawable().apply {
        setColor(withAlpha(accent, 0x26))
        cornerRadius = RADIUS_PILL_DP * ctx.resources.displayMetrics.density
        setStroke(maxOf(1, dp(ctx, 1)), withAlpha(accent, 0x66))
    }

    /** Replace the alpha channel of [color] with [alpha] (0-255). */
    fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))

    // ── Typography ────────────────────────────────────────────────────────

    /**
     * Section heading: small, bold, letter-spaced, uppercase-toned. Gives the
     * cards a hierarchy they did not have when every line was 11-13sp.
     */
    fun sectionTitle(ctx: Context, text: CharSequence, accent: Int = TEXT): TextView =
        TextView(ctx).apply {
            this.text = text
            setTextColor(accent)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.08f
        }

    /** The headline figure on a card — large, bold, tabular-feeling. */
    fun valueText(ctx: Context, text: CharSequence, color: Int = TEXT, sizeSp: Float = 22f): TextView =
        TextView(ctx).apply {
            this.text = text
            setTextColor(color)
            textSize = sizeSp
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            includeFontPadding = false
        }

    /** The caption under a figure — muted, small, wide-tracked. */
    fun labelText(ctx: Context, text: CharSequence, color: Int = TEXT_MUTED): TextView =
        TextView(ctx).apply {
            this.text = text
            setTextColor(color)
            textSize = 9.5f
            letterSpacing = 0.12f
        }

    /** Ordinary body copy inside a card. */
    fun bodyText(ctx: Context, text: CharSequence, color: Int = TEXT_SECONDARY, sizeSp: Float = 11.5f): TextView =
        TextView(ctx).apply {
            this.text = text
            setTextColor(color)
            textSize = sizeSp
        }

    /** A status badge: short text on a tinted, rounded, stroked pill. */
    fun pill(ctx: Context, text: CharSequence, accent: Int): TextView =
        TextView(ctx).apply {
            this.text = text
            setTextColor(accent)
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.06f
            background = pillBackground(ctx, accent)
            setPadding(dp(ctx, 9), dp(ctx, 3), dp(ctx, 9), dp(ctx, 3))
        }

    // ── Composites ────────────────────────────────────────────────────────

    /**
     * A card container: rounded surface, standard padding and margins, and a
     * coloured accent rule down its left edge so lanes are distinguishable at a
     * glance rather than only by their text.
     */
    fun card(ctx: Context, accent: Int = PURPLE): LinearLayout {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            background = cardBackground(ctx)
            clipToOutline = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { setMargins(dp(ctx, 12), dp(ctx, 8), dp(ctx, 12), dp(ctx, 10)) }
        }
        row.addView(View(ctx).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(accent, withAlpha(accent, 0x33)),
            )
            layoutParams = LinearLayout.LayoutParams(dp(ctx, 3), ViewGroup.LayoutParams.MATCH_PARENT)
        })
        row.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 14), dp(ctx, 13), dp(ctx, 14), dp(ctx, 13))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        return row
    }

    /**
     * The content column of a [card] — where callers add their rows. Returns the
     * card itself if the structure is ever unexpected, so a call site can never
     * crash on a layout assumption.
     */
    fun cardBody(card: LinearLayout): LinearLayout =
        (card.getChildAt(1) as? LinearLayout) ?: card

    /**
     * A KPI tile: figure above, caption below, on its own inset surface. Four of
     * these in a row replaces the old `"Label: value"` text run, which is the
     * other half of why the screens read as unchanged.
     */
    fun kpiTile(ctx: Context, label: CharSequence, value: CharSequence, valueColor: Int = TEXT): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = tileBackground(ctx)
            setPadding(dp(ctx, 6), dp(ctx, 8), dp(ctx, 6), dp(ctx, 8))
            addView(valueText(ctx, value, valueColor, sizeSp = 15f))
            addView(labelText(ctx, label).apply {
                setPadding(0, dp(ctx, 3), 0, 0)
            })
        }

    /** A horizontal strip of evenly weighted KPI tiles with gaps between them. */
    fun kpiRow(ctx: Context, tiles: List<LinearLayout>): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            tiles.forEachIndexed { i, tile ->
                tile.layoutParams = LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f,
                ).apply {
                    if (i > 0) marginStart = dp(ctx, 6)
                }
                addView(tile)
            }
        }

    /**
     * A thin progress rail — a real bar with a rounded track and a tinted fill,
     * for the places that currently print a percentage and nothing else.
     * [fraction] is clamped to 0..1.
     */
    fun progressRail(ctx: Context, fraction: Double, accent: Int = CYAN): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            background = GradientDrawable().apply {
                setColor(withAlpha(STROKE_SOFT, 0xAA))
                cornerRadius = 999f
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 5),
            )
            val f = if (fraction.isFinite()) fraction.coerceIn(0.0, 1.0) else 0.0
            addView(View(ctx).apply {
                background = GradientDrawable(
                    GradientDrawable.Orientation.LEFT_RIGHT,
                    intArrayOf(withAlpha(accent, 0xBB), accent),
                ).apply { cornerRadius = 999f }
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, f.toFloat())
            })
            // Remainder keeps the fill honest at any width.
            addView(View(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, (1.0 - f).toFloat())
            })
        }

    /** Vertical spacing between rows inside a card. */
    fun gap(ctx: Context, heightDp: Number = 8): View =
        View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, heightDp),
            )
        }

    /** A hairline divider that matches the card stroke. */
    fun divider(ctx: Context): View =
        View(ctx).apply {
            setBackgroundColor(withAlpha(STROKE_SOFT, 0x99))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, maxOf(1, dp(ctx, 1)),
            ).apply { topMargin = dp(ctx, 9); bottomMargin = dp(ctx, 9) }
        }

    /**
     * A header row: title on the left, optional pill on the right. Every card in
     * the app wants this shape, and every card currently builds it by hand.
     */
    fun headerRow(
        ctx: Context,
        title: CharSequence,
        pillText: CharSequence? = null,
        pillAccent: Int = CYAN,
        titleAccent: Int = TEXT,
    ): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        addView(sectionTitle(ctx, title, titleAccent).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        if (!pillText.isNullOrBlank()) addView(pill(ctx, pillText, pillAccent))
    }
}
