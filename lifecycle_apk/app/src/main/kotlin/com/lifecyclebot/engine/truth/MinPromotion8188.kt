package com.lifecyclebot.engine.truth

/**
 * V5.0.8188 §PROMOTION_IS_RE_EVALUATED_AT_THE_PROMOTED_NOTIONAL — a below-minimum order is lifted to the venue minimum
 * only when the trade still makes sense AT that size.
 *
 * 5.0.8186: ROUTE_MIN_PROMOTED_KELLY_BELOW_MIN=7 — risk asked for 0.02424 SOL and 0.02862 SOL (+18%) was sent at a
 * 60.8% drawdown. Every promotion path (LiveRiskPolicy7807's 7840 risk-safe minimum, OrderSizeResolver6441's 6600/7840
 * capacity promotion, SmartSizerV3's 8180 admitted-edge promotion) proved only that the minimum was AFFORDABLE and
 * inside the hard per-trade cap. Affordable is not intended. A promotion now needs all of:
 *   (c) the account is not in a defensive drawdown — the tier where LiveRiskPolicy7807's drawdown multiplier reaches
 *       its floor (30%), read from CapitalDrawdown7948's verified (PeakLedger8188) drawdown;
 *   (a) the promoted size's loss at the planned stop, round trip at THAT size included, is within [LOSS_TOLERANCE_8188]
 *       of the loss the ORIGINAL request budgeted (requested x (stop + its own round trip));
 *   (b) the after-cost expected edge at the promoted size is measured and positive (the round trip is proportionally
 *       lower at the larger size, NetEdge8030 / CostTrim8151 model; an unmeasured edge is not a positive one).
 * Otherwise the order is REFUSED by name — never sent smaller, never sent larger.
 */
object MinPromotion8188 {

    const val REFUSED_LOSS_BUDGET_8188 = "ROUTE_MIN_PROMOTION_REFUSED_LOSS_BUDGET_8188"
    const val REFUSED_NEGATIVE_EDGE_8188 = "ROUTE_MIN_PROMOTION_REFUSED_NEGATIVE_EDGE_8188"
    const val REFUSED_UNMEASURED_EDGE_8188 = "ROUTE_MIN_PROMOTION_REFUSED_UNMEASURED_EDGE_8188"
    const val REFUSED_DRAWDOWN_8188 = "ROUTE_MIN_PROMOTION_REFUSED_DRAWDOWN_8188"
    const val REFUSED_UNEVALUATED_8188 = "ROUTE_MIN_PROMOTION_REFUSED_UNEVALUATED_8188"
    /** The promoted loss may exceed the original budget by at most this share. */
    const val LOSS_TOLERANCE_8188 = 0.10
    /** LiveRiskPolicy7807.drawdownMultiplier reaches its floor here (DRAWDOWN_FLOOR_AT_FRAC_7807): the defensive tier. */
    const val DEFENSIVE_DRAWDOWN_FRAC_8188 = 0.30

    /** Pure: SOL lost at the stop by [sizeSol] with a [costPct] round trip. */
    fun lossAtStopSol8188(sizeSol: Double, stopPct: Double, costPct: Double): Double =
        sizeSol * ((stopPct + (if (costPct.isFinite()) costPct.coerceAtLeast(0.0) else 0.0)) / 100.0)

    /** Pure: is the account in the defensive drawdown tier? */
    fun defensiveDrawdown8188(drawdownFrac: Double): Boolean =
        drawdownFrac.isFinite() && drawdownFrac >= DEFENSIVE_DRAWDOWN_FRAC_8188

    /**
     * Pure: why [requestedSol] may NOT be promoted to [promotedSol] (null = it may). [costRequestedPct] / [costPromotedPct]
     * are the round trips at each size (an unknown cost at the request is charged the promoted size's, which is never
     * higher); [expectedGrossPct] the setup's expected gross per trade (NaN = unmeasured).
     */
    fun refusal8188(
        requestedSol: Double, promotedSol: Double, stopPct: Double,
        costRequestedPct: Double, costPromotedPct: Double, expectedGrossPct: Double, drawdownFrac: Double,
    ): String? {
        if (!promotedSol.isFinite() || promotedSol <= 0.0 || !(requestedSol < promotedSol)) return null
        if (defensiveDrawdown8188(drawdownFrac)) return REFUSED_DRAWDOWN_8188
        if (!requestedSol.isFinite() || requestedSol <= 0.0 || !stopPct.isFinite() || stopPct <= 0.0) return REFUSED_LOSS_BUDGET_8188
        val cReq = if (costRequestedPct.isFinite()) costRequestedPct else costPromotedPct
        val budget = lossAtStopSol8188(requestedSol, stopPct, cReq)
        val promotedLoss = lossAtStopSol8188(promotedSol, stopPct, costPromotedPct)
        if (!(promotedLoss <= budget * (1.0 + LOSS_TOLERANCE_8188) + 1e-12)) return REFUSED_LOSS_BUDGET_8188
        if (!expectedGrossPct.isFinite() || !costPromotedPct.isFinite()) return REFUSED_UNMEASURED_EDGE_8188
        if (expectedGrossPct - costPromotedPct <= 0.0) return REFUSED_NEGATIVE_EDGE_8188
        return null
    }

    /**
     * Runtime (the upstream sizers): the same test with the inputs LiveRiskPolicy7807.runtimeInputs would read — the
     * fresh plan stop (else the lane mandate), the realistic round trip at each size, the setup's expected gross and the
     * verified live drawdown. Any fault refuses ([REFUSED_UNEVALUATED_8188]): an unproven promotion is not made.
     */
    fun runtimeRefusal8188(mint: String, lane: String, requestedSol: Double, promotedSol: Double, liquidityUsd: Double): String? {
        if (!(requestedSol < promotedSol)) return null
        val r = try {
            val l = LiveRiskPolicy7807.canonicalLane(lane)
            val plan = if (mint.isNotBlank()) try { TradePlan7739.freshPlan7783(mint) } catch (_: Throwable) { null } else null
            val stop = plan?.stopPnlPct?.let { -it }?.takeIf { it > 0.0 }
                ?: (try { FieldManual7715.mandateFor(l).invalidationPct } catch (_: Throwable) { 10.0 })
            val ts = if (mint.isNotBlank()) try { com.lifecyclebot.engine.BotService.status.tokens[mint] } catch (_: Throwable) { null } else null
            val liq = if (liquidityUsd.isFinite() && liquidityUsd > 0.0) liquidityUsd else (ts?.lastLiquidityUsd ?: 0.0)
            val mcap = ts?.lastMcap ?: 0.0
            val solUsd = try { com.lifecyclebot.engine.WalletManager.lastKnownSolPrice } catch (_: Throwable) { 0.0 }
            fun cost(s: Double): Double = maxOf(LiveRiskPolicy7807.roundTripCostPct(s, solUsd, liq),
                (try { NetEdge8030.costPct8030(l, s, liq, mint, mcap) } catch (_: Throwable) { Double.NaN }).takeIf { it.isFinite() } ?: 0.0)
            val gross = ts?.let { LiveRiskPolicy7807.setupExpectedGrossForPromotion8188(it, l) } ?: Double.NaN
            refusal8188(requestedSol, promotedSol, stop, cost(requestedSol), cost(promotedSol), gross, liveDrawdownFrac8188())
        } catch (_: Throwable) { REFUSED_UNEVALUATED_8188 }
        if (r != null) note8188(r, lane, requestedSol, promotedSol)
        return r
    }

    /** The verified live drawdown (fraction), read without moving the peak. */
    fun liveDrawdownFrac8188(): Double = try {
        CapitalDrawdown7948.peekPct7948(CapitalDrawdown7948.liveMarkedEquitySol7948(com.lifecyclebot.engine.BotService.status.walletSol)) / 100.0
    } catch (_: Throwable) { 0.0 }

    /** Counts and logs one refused promotion (any path). */
    private fun note8188(reason: String, lane: String, requestedSol: Double, promotedSol: Double) {
        try {
            com.lifecyclebot.engine.PipelineHealthCollector.labelInc(reason)
            com.lifecyclebot.engine.ForensicLogger.lifecycle(reason,
                "lane=$lane requested=${"%.5f".format(requestedSol)} promoted=${"%.5f".format(promotedSol)} action=refuse_not_promote")
        } catch (_: Throwable) {}
    }
}
