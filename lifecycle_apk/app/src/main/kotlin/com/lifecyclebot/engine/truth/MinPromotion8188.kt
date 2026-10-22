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
        budgetSol: Double = requestedSol,
        explorationSlotFree: Boolean = false,
    ): String? {
        if (!promotedSol.isFinite() || promotedSol <= 0.0 || !(requestedSol < promotedSol)) return null
        // V5.0.8259 — the defensive tier (>=30 % drawdown) closed EVERY promotion while 10/12 lane allocations sat
        // below the routable minimum on a 0.25 SOL wallet (8257: ROUTE_MIN_PROMOTION_REFUSED_DRAWDOWN_8188 = 32): a
        // drawdown could never be traded out of. Operator's decision: ONE promoted exploration order at a time while
        // defensive (LaneScoreAdmission7308's slot — no open unproven live position, 5 min apart). The loss budget,
        // the measured edge at the promoted size and every later gate still bind below.
        if (defensiveDrawdown8188(drawdownFrac)) {
            if (!explorationSlotFree) return REFUSED_DRAWDOWN_8188
            try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("ROUTE_MIN_PROMOTION_DEFENSIVE_EXPLORATION_SLOT_8259") } catch (_: Throwable) {}
        }
        if (!requestedSol.isFinite() || requestedSol <= 0.0 || !stopPct.isFinite() || stopPct <= 0.0) return REFUSED_LOSS_BUDGET_8188
        val cReq = if (costRequestedPct.isFinite()) costRequestedPct else costPromotedPct
        val budget = lossAtStopSol8188(if (budgetSol.isFinite() && budgetSol > requestedSol) budgetSol else requestedSol, stopPct, cReq)  // V5.0.8234
        val promotedLoss = lossAtStopSol8188(promotedSol, stopPct, costPromotedPct)
        if (!(promotedLoss <= budget * (1.0 + LOSS_TOLERANCE_8188) + 1e-12)) return REFUSED_LOSS_BUDGET_8188
        if (!costPromotedPct.isFinite()) return REFUSED_UNMEASURED_EDGE_8188
        // V5.0.8316 §AN_UNMEASURED_EDGE_IS_THE_EXPLORATION_ORDER — the expected gross is NaN whenever the setup reads
        // DATA_INCOMPLETE / NO_TRIGGER (LanePlaybook7907.classifiedExpected7962): on a cold lane nothing is ever measured,
        // so the lane could never place the order that measures it (8314: REFUSED_UNMEASURED_EDGE 784, QUALITY / BLUECHIP /
        // CORE / CYCLIC / TREASURY ownerSelected 0). The lane's ONE exploration slot (LaneScoreAdmission7308: no open
        // unproven live position in the lane, 5 min apart) takes the promoted order; a busy slot still refuses by name. The
        // loss budget above and a MEASURED negative edge below still bind.
        if (!expectedGrossPct.isFinite()) {
            if (!explorationSlotFree) return REFUSED_UNMEASURED_EDGE_8188
            try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc(UNMEASURED_EXPLORATION_SLOT_8316) } catch (_: Throwable) {}
            return null
        }
        if (expectedGrossPct - costPromotedPct <= 0.0) return REFUSED_NEGATIVE_EDGE_8188
        return null
    }

    /** V5.0.8316 — counted when an unmeasured edge was promoted on the lane's free exploration slot. */
    const val UNMEASURED_EXPLORATION_SLOT_8316 = "ROUTE_MIN_PROMOTION_UNMEASURED_EXPLORATION_SLOT_8316"

    /**
     * V5.0.8316 — the lane's exploration slot is free (LaneScoreAdmission7308.runnerSlotFreeNow: one unproven promoted live
     * order per lane at a time). The upstream sizers (OrderSizeResolver6441 / SmartSizerV3) ask it beside their proven-edge
     * tests; [runtimeRefusal8188] then re-asks it inside the promotion. Any fault reads as busy.
     */
    fun explorationSlotFree8316(lane: String): Boolean = try {
        LaneScoreAdmission7308.runnerSlotFreeNow(LiveRiskPolicy7807.canonicalLane(lane))
    } catch (_: Throwable) { false }

    /**
     * Runtime (the upstream sizers): the same test with the inputs LiveRiskPolicy7807.runtimeInputs would read — the
     * fresh plan stop (else the lane mandate), the realistic round trip at each size, the setup's expected gross and the
     * verified live drawdown. Any fault refuses ([REFUSED_UNEVALUATED_8188]): an unproven promotion is not made.
     *
     * V5.0.8224 §ONE_ECONOMIC_BASIS — the round trip at each size is NetEdge8030.costPct8030, the SAME model (measured
     * ledger over components over the venue base) CandidateAdmission8136 costed the candidate with; the legacy
     * roundTripCostPct7766 max was a second estimator that refused promotions admission had never priced. The expected
     * gross is the admission's OWN (AdmissionEdge8180.Proof8198: its setup's net plus the round trip it was charged) when
     * this generation holds a proof, else the sizer's record as before.
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
            fun cost(s: Double): Double = NetEdge8030.costPct8030(l, s, liq, mint, mcap)
            val proof8224 = if (mint.isNotBlank()) try {
                val version = com.lifecyclebot.engine.LaneExecutionCoordinator.candidateVersionFor(mint)
                AdmissionEdge8180.proof8198("LIVE", mint, l, version)
            } catch (_: Throwable) { null } else null
            val admissionGross8224 = proof8224?.expectedGrossPct?.takeIf { it.isFinite() }
            if (admissionGross8224 != null) try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("ROUTE_MIN_PROMOTION_ADMISSION_BASIS_8224") } catch (_: Throwable) {}
            val gross = admissionGross8224 ?: ts?.let { LiveRiskPolicy7807.setupExpectedGrossForPromotion8188(it, l) } ?: Double.NaN
            // V5.0.8234 §LOSS_BUDGET_IS_THE_DOCTRINE — a lane's allocation on a small wallet (QUALITY 0.0199, CASHGEN 0.0193
            // on 0.32 SOL) sits under the routable minimum (0.0285); the "requested" ticket was never executable, so a loss
            // budget read from it refused EVERY promotion to the only size that routes (8233 forensic: 25 of 34 sized
            // candidates, every lane). The budget is the account's per-position doctrine — the safe share cap the
            // routable-capacity preflight already enforces — when it is larger than the request; the promoted ticket
            // still has to clear it, and a wallet whose doctrine is below the routable minimum still refuses.
            val budgetSol8234 = doctrineBudgetSol8236(requestedSol)
            // 8269: match LiveRiskPolicy7807's already-enforced 8259 exploration slot.
            // Without forwarding this, the upstream sizer always supplied the
            // default false and refused even the first permitted defensive order.
            // The 8259 permit is per specialist, not a global lock: a qualifying
            // MOONSHOT probe must not consume BLUECHIP/CASHGEN's own slot.
            val explorationFree = try { LaneScoreAdmission7308.runnerSlotFreeNow(l) } catch (_: Throwable) { false }
            refusal8188(requestedSol, promotedSol, stop, cost(requestedSol), cost(promotedSol), gross,
                liveDrawdownFrac8188(), budgetSol8234, explorationSlotFree = explorationFree)
        } catch (_: Throwable) { REFUSED_UNEVALUATED_8188 }
        if (r != null) note8188(r, lane, requestedSol, promotedSol)
        // V5.0.8316 — a promotion refusal is a refusal AT SIZE: the funnel shows "owner selected, refused at size" (SpecialistOwnership7951).
        if (r != null) try { com.lifecyclebot.engine.SpecialistOwnership7951.onCapitalRefusal7951(lane, mint, r, postIntent = false) } catch (_: Throwable) {}
        return r
    }

    /**
     * V5.0.8236 §ONE_RISK_BUDGET — the account's per-position doctrine (the safe share cap the routable-capacity
     * preflight enforces) as the route-minimum loss budget, read by BOTH promotion paths: the upstream sizers
     * ([runtimeRefusal8188]) and LiveRiskPolicy7807.decide's last-mile promotion. 8234 gave it to the first only; the
     * second still judged the same promotion against the sub-minimum lane ticket (8235 forensic: 100
     * ROUTE_MIN_PROMOTION_REFUSED_LOSS_BUDGET_8188, all from the risk policy). NaN when the wallet cannot be read.
     */
    fun doctrineBudgetSol8236(
        requestedSol: Double,
        tradeableSol8269: Double = Double.NaN,
        solUsd8269: Double = Double.NaN,
    ): Double {
        val budget = try {
            // The final risk decision already has reservation-adjusted live cash
            // and the current SOL quote. Never discard that evidence to read a
            // different global wallet snapshot during cost-floor promotion.
            val tradeable = if (tradeableSol8269.isFinite() && tradeableSol8269 >= 0.0) tradeableSol8269
                else (com.lifecyclebot.engine.BotService.status.walletSol - LiveSpendReserveAuthority7255.RESERVE_SOL).coerceAtLeast(0.0)
            val solUsd = if (solUsd8269.isFinite() && solUsd8269 > 0.0) solUsd8269
                else try { com.lifecyclebot.engine.WalletManager.lastKnownSolPrice } catch (_: Throwable) { 0.0 }
            com.lifecyclebot.v3.sizing.SmartSizerV3.routableCapacityPreflight7224(tradeable, solUsd).safeShareCapSol
        } catch (_: Throwable) { Double.NaN }
        if (budget.isFinite() && budget > requestedSol)
            try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("ROUTE_MIN_PROMOTION_BUDGET_FROM_DOCTRINE_8234") } catch (_: Throwable) {}
        return budget
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
