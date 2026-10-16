package com.lifecyclebot.engine.truth

/**
 * V5.0.8136 §EDGE_MATURITY_AND_BINDING_CONSTRAINT — two sizing truths for LiveRiskPolicy7807.
 *
 * 1. The drawdown waiver needs a MATURE edge. 8094 waived the drawdown shrink for any positive quarter-Kelly share,
 *    and that share is read from forward LABELS (setup / cell / kind books). A label book can be positive while the
 *    lane's real whole-position closes lose (8127: Cortex STRONG on positive 5-minute labels, negative realised
 *    closes). The waiver now also needs the lane's realised record: [MIN_REALISED_N_8136]+ clean live closes,
 *    mean - SE > 0 and net SOL > 0. An immature edge keeps the drawdown shrink (uncertainty and realised drawdown
 *    reduce size); nothing else changes.
 *
 * 2. Which constraint set the size. 8127 sealed Kelly-proven trades near the route minimum and the forensic row
 *    named the Kelly-stage cap only, never the later clamps (the upstream proposal when no compounding ceiling was
 *    offered, the compounding ceiling, the route-minimum promotion). [bindingConstraintOf8136] names the constraint
 *    that actually bound the decision; LiveRiskPolicy7807.statusLine counts them.
 */
object LiveSizingTruth8136 {

    /** Clean live closes a lane needs before its realised record may waive the drawdown shrink. */
    const val MIN_REALISED_N_8136 = 10

    /** The lane's realised whole-position record (net of cost, clean, live, terminal closes only). */
    data class EdgeMaturity8136(
        val realisedN: Int,
        val realisedMeanPct: Double,
        val realisedSePct: Double,
        val realisedNetSol: Double,
    )

    /** Pure: is the realised record mature and positive (n, mean - SE > 0, net SOL > 0)? */
    fun edgeMature8136(m: EdgeMaturity8136): Boolean =
        m.realisedN >= MIN_REALISED_N_8136 && m.realisedMeanPct.isFinite() && m.realisedSePct.isFinite() &&
            m.realisedSePct >= 0.0 && m.realisedMeanPct - m.realisedSePct > 0.0 &&
            m.realisedNetSol.isFinite() && m.realisedNetSol > 0.0

    /**
     * Pure: does this candidate skip the drawdown shrink? The 8094 rule (label-proven Kelly share > 0 under a
     * drawdown) AND a mature realised record. [maturity] null = the caller did not measure it (direct pure callers
     * only; LiveRiskPolicy7807.runtimeInputs always supplies it), which keeps the 8094 rule.
     */
    fun drawdownWaiver8136(edgeKellyFrac: Double, drawdownFrac: Double, maturity: EdgeMaturity8136?): Boolean =
        LiveRiskPolicy7807.drawdownWaivedForProvenEdge8094(edgeKellyFrac, drawdownFrac) &&
            (maturity == null || edgeMature8136(maturity))

    /** Pure: the realised record from per-close net return percents and net SOL. */
    fun maturityOf8136(returnsPct: List<Double>, netSol: List<Double>): EdgeMaturity8136 {
        val r = returnsPct.filter { it.isFinite() }
        val n = r.size
        if (n == 0) return EdgeMaturity8136(0, 0.0, Double.POSITIVE_INFINITY, netSol.filter { it.isFinite() }.sum())
        val mean = r.average()
        val se = if (n > 1) kotlin.math.sqrt(r.sumOf { (it - mean) * (it - mean) } / (n - 1) / n) else Double.POSITIVE_INFINITY
        return EdgeMaturity8136(n, mean, se, netSol.filter { it.isFinite() }.sum())
    }

    /**
     * Pure: a conservative standard error for a record known only by its win rate and average win / loss (the
     * persistent leaderboard): the two-point variance around the mean, inflated by 25%. Infinite when unknowable.
     */
    fun twoPointSe8136(n: Int, winRatePct: Double, avgWinPct: Double, avgLossPct: Double, meanPct: Double): Double {
        if (n < 2 || !winRatePct.isFinite() || !avgWinPct.isFinite() || !avgLossPct.isFinite() || !meanPct.isFinite()) return Double.POSITIVE_INFINITY
        val p = (winRatePct / 100.0).coerceIn(0.0, 1.0)
        val v = p * (avgWinPct - meanPct) * (avgWinPct - meanPct) + (1.0 - p) * (avgLossPct - meanPct) * (avgLossPct - meanPct)
        if (!v.isFinite() || v <= 0.0) return Double.POSITIVE_INFINITY
        return 1.25 * kotlin.math.sqrt(v / n)
    }

    /**
     * Pure: the constraint that set [d]'s size for [i]. Names the late clamps the Kelly-stage cap cannot see:
     * the upstream proposal (with or without an offered compounding ceiling), the compounding ceiling and the
     * route-minimum promotion. A proven compounding size reports its Kelly-stage cap as KELLY:<cap>.
     */
    fun bindingConstraintOf8136(i: LiveRiskPolicy7807.Inputs, d: LiveRiskPolicy7807.Decision): String {
        if (!d.open) return "REFUSED:${d.reason}"
        val sz = d.sizing8094
        if (d.reason == "OPEN_RISK_SAFE_MIN_PROMOTED_7840") return if (sz != null) "ROUTE_MIN_PROMOTED_KELLY_BELOW_MIN" else "ROUTE_MIN_PROMOTED"
        val eps = 1e-9
        // V5.0.8153 — the envelope clamps run last in decide, so the last one that acted is the binding one.
        when {
            "ENVELOPE_CLAMP_8153" in d.labels -> return "ENVELOPE_CLAMP"
            "LANE_PORTFOLIO_ENVELOPE_CAP_8153" in d.labels -> return "LANE_PORTFOLIO_ENVELOPE"
            "TRADEABLE_CASH_CAP_8153" in d.labels -> return "TRADEABLE_CASH"
        }
        if (sz == null) {
            return when {
                i.upstreamSol > 0.0 && d.sizeSol >= i.upstreamSol - eps -> "UPSTREAM_PROPOSAL"
                "LIQUIDITY_IMPACT_CAP_7807" in d.labels -> "LIQUIDITY"
                "LANE_MAX_POSITION_CAP_7807" in d.labels -> "LANE_CAP"
                "WALLET_DRAWDOWN_SHRINK_7807" in d.labels -> "RISK_BUDGET_DRAWDOWN"
                else -> "RISK_BUDGET"
            }
        }
        val compounding = "PROVEN_EDGE_COMPOUNDING_SIZE_8057" in d.labels
        val ceiling = i.compoundingCeilingSol8094
        val upCeil = LiveSizeTarget8094.upstreamCeiling8094(i.upstreamSol, sz.kellySol, ceiling)
        if (upCeil > 0.0 && d.sizeSol >= upCeil - eps && upCeil + eps < maxOf(sz.kellySol, sz.riskBudgetSol)) {
            return when {
                ceiling <= 0.0 -> "UPSTREAM_PROPOSAL_NO_COMPOUNDING_CEILING"
                compounding && ceiling + eps < sz.kellySol && upCeil <= ceiling + eps -> "COMPOUNDING_CEILING"
                else -> "UPSTREAM_PROPOSAL"
            }
        }
        if (!compounding) return "RISK_BUDGET_ABOVE_KELLY"
        return "KELLY:${sz.bindingCap}"
    }
}
