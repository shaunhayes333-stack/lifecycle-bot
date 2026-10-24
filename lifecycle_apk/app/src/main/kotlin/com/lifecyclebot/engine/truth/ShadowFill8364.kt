package com.lifecyclebot.engine.truth

/**
 * V5.0.8364 §SHADOW_FILLS_PRICED_LIKE_LIVE — audit 8360 item 3: the shadow books that "proved" lanes and scalps filled at the
 * signal mark (ExploreShadow8347 open / arm, EvidenceShadow8344 open). A live buy fills seconds later, higher on a moving coin
 * (EntryChase7961 measures that decision-to-fill chase per lane). Every shadow entry is now booked at
 *   mark x (1 + the lane's chase)   — the lane's measured live median once [EntryChase7961] has 8 live fills
 *                                     (its latency read is non-zero exactly then), else [MODEL_CHASE_PCT_8364];
 * and every shadow exit at mark x (1 - [EXIT_SLIP_EXTRA_PCT_8364]).
 * No double count: NetEdge8030.costPct8030 (the round trip both books already charge) is max(model, measured) where the model
 * carries FieldManual7715.SLIPPAGE_ALLOWANCE_PCT_7715 (both sides) plus the depth impact of both legs, and the measured
 * figure (CostLedger7962) is the fill-versus-market friction of both legs — so the SELL-side slippage is already in it and
 * the extra exit slippage charged here is 0. The decision-to-fill chase is NOT in it (CostLedger7962: "the entry mark is the
 * market at the FILL, not at the decision") — that is the one term added.
 */
object ShadowFill8364 {
    /** The model chase (%) before a lane has 8 measured live fills (5.0.8360 audit: live median chase 2.9 %). */
    const val MODEL_CHASE_PCT_8364 = 2.9
    /** Sell-side slippage NOT already inside NetEdge8030.costPct8030 (none: the round trip carries both legs' slippage). */
    const val EXIT_SLIP_EXTRA_PCT_8364 = 0.0
    private const val MAX_CHASE_PCT_8364 = 30.0

    /** Pure: the chase (%) a shadow entry pays — the measured live median when [liveMeasured], else the model; clipped to [0, 30]. */
    fun chasePct8364(liveMeasured: Boolean, measuredPct: Double, modelPct: Double = MODEL_CHASE_PCT_8364): Double {
        val c = if (liveMeasured && measuredPct.isFinite()) measuredPct else modelPct
        return c.coerceIn(0.0, MAX_CHASE_PCT_8364)
    }

    /** Pure: the booked entry price — the signal mark plus the chase. */
    fun entryPx8364(markPx: Double, chasePct: Double): Double =
        markPx * (1.0 + (if (chasePct.isFinite()) chasePct.coerceIn(0.0, MAX_CHASE_PCT_8364) else 0.0) / 100.0)

    /** Pure: the booked exit price — the mark less the uncharged exit slippage. */
    fun exitPx8364(markPx: Double, slipPct: Double = EXIT_SLIP_EXTRA_PCT_8364): Double =
        markPx * (1.0 - (if (slipPct.isFinite()) slipPct.coerceIn(0.0, 50.0) else 0.0) / 100.0)

    /** Pure: gross % of a sale at [grossPct] after the exit slippage (identity while [slipPct] is 0). */
    fun exitGrossPct8364(grossPct: Double, slipPct: Double = EXIT_SLIP_EXTRA_PCT_8364): Double =
        ((1.0 + grossPct / 100.0) * (1.0 - (if (slipPct.isFinite()) slipPct.coerceIn(0.0, 50.0) else 0.0) / 100.0) - 1.0) * 100.0

    /** The lane's shadow chase (%): EntryChase7961's live median once measured (8 live fills), else the model. */
    fun laneChasePct8364(lane: String): Double {
        val liveMeasured = (try { EntryChase7961.laneLatencyMs8094(lane) } catch (_: Throwable) { 0L }) > 0L
        val measured = if (liveMeasured) (try { EntryChase7961.lanePenaltyPct7961(lane) } catch (_: Throwable) { Double.NaN }) else Double.NaN
        return chasePct8364(liveMeasured, measured)
    }

    /** The booked entry for a shadow fill at [markPx] in [lane]. */
    fun laneEntryPx8364(markPx: Double, lane: String): Double = entryPx8364(markPx, laneChasePct8364(lane))
}
