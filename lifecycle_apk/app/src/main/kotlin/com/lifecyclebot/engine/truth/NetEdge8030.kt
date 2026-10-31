package com.lifecyclebot.engine.truth

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8030 — positive EV and net profit, always.
 *
 * Owner: "treasury scalps and all trades need to consider total cost — 3% is dumb, that just costs money ...
 * positive EV and net profit always." At a ~0.027 SOL ticket a round trip costs ~4-8% (venue fee both ways,
 * the priority/sender tip on each leg, slippage, impact in and out). Across the 152 live closes in this chat's
 * logs: 20 wins, average +13.1%, 8 of them under +5% — net losses booked as wins — and TREASURY / CASHGEN took
 * profit at 2.5-4%, profit locks armed at 3.5-4.9%, CYCLIC locked +1% / +3%, EXPRESS / SNIPER / MANIPULATED
 * sold at any green print. No exit read the measured cost.
 *
 * One cost figure ([costPct8030]): the lane's measured live round trip (CostLedger7962, once it has 8 trades)
 * or the model at the real ticket and pool depth (FieldManual7715.roundTripCostPct7766), whichever is higher.
 *
 *  ENTRY (live, every lane, hard — LiveEdgeGate7877): the lane's expected move must be at least [EDGE_MULT]x
 *        the round trip ([entryRefusal8030]). The expected move is the Field Manual lane mandate (runner 50%,
 *        momentum 30%, dip 25%, quality 20%, treasury 12%).
 *  EXIT  (live, the sell door — Executor): no profit-taking exit below net break-even + [NET_BUFFER_PCT]
 *        ([exitRefusal8030]). A green position under that line keeps its stop and time limit and holds; stops,
 *        rug / liquidity / dev exits, manual, emergency and operational exits are never held.
 */
object NetEdge8030 {
    // V5.0.8197 — owner: 1.5x (was 2.0x). At the live minimum ticket (~0.027 SOL, round trip ~7.8%) 2x asked every entry
    // for a 15.6% expected move, which refused 203 of 317 FDG decisions on 5.0.8196 and capped QUALITY/BLUECHIP (20%
    // mandate) to coins already moving 15.6%/h. 1.5x still requires the move to beat the round trip by half again.
    const val EDGE_MULT = 1.5
    const val NET_BUFFER_PCT = 1.0
    const val DEFAULT_TICKET_SOL = 0.0273
    private val entryRefused = AtomicLong(0)
    private val exitHeld = AtomicLong(0)
    private val partialHeld = AtomicLong(0)
    private val giveBackReleased = AtomicLong(0)
    @Volatile private var lastCostPct = Double.NaN

    // ── pure ──

    /** The expected move clears [EDGE_MULT]x the round-trip cost. */
    fun entryClears8030(expectedGrossPct: Double, costPct: Double, margin: Double = EDGE_MULT): Boolean =
        expectedGrossPct.isFinite() && costPct.isFinite() && costPct >= 0.0 && margin.isFinite() && margin >= 1.0 &&
            expectedGrossPct >= margin * costPct

    fun margin8391(lane: String, sizeSol: Double, costPct: Double): Double =
        try { CostLedger7962.margin8391(lane, sizeSol, costPct) } catch (_: Throwable) { EDGE_MULT }

    /** The lowest gross gain a profit-taking exit may sell at: the round trip plus the net buffer. */
    /**
     * Pure (V5.0.8169): the cost an exit still has to clear — the round trip [roundTripPct] less the buy leg's fixed cost
     * ([fixedLegSol] on [positionSol], already paid), never under the sell leg's own fixed share, never over the round trip.
     * Unknown fixed leg or size: the round trip as before.
     */
    fun exitCostPct8169(roundTripPct: Double, fixedLegSol: Double, positionSol: Double): Double {
        if (!roundTripPct.isFinite() || !fixedLegSol.isFinite() || fixedLegSol <= 0.0 || !positionSol.isFinite() || positionSol <= 0.0) return roundTripPct
        val legPct = fixedLegSol / positionSol * 100.0
        return (roundTripPct - legPct).coerceAtLeast(minOf(legPct, roundTripPct))
    }

    fun exitFloorPct8030(costPct: Double): Double = (if (costPct.isFinite() && costPct > 0.0) costPct else 0.0) + NET_BUFFER_PCT

    /** An exit that must never be held for profit: stops, safety, operational, manual. */
    fun structural8030(reason: String): Boolean {
        val r = reason.uppercase()
        // A trailing stop or a profit lock on a green position is profit-taking, so "STOP" alone is not structural.
        return listOf("STOP_LOSS", "STRICT_SL", "CATASTROPH", "HARD_FLOOR", "BACKSTOP", "RUG", "DEV_", "DEV SOLD", "LIQUIDITY",
            "HONEYPOT", "FREEZE", "FROZEN", "MANUAL", "EMERGENCY", "KILL", "SHUTDOWN", "QUARANTINE", "ORPHAN", "RESURRECT",
            "STARTUP", "STALE_FEED", "EVICT", "DUST", "ZOMBIE", "RECONCIL", "DEAD_TOKEN", "NO_PRICE", "WALLET", "MAYHEM").any { r.contains(it) }
    }

    /** Is a green exit at [pnlPct] below the net floor (and not structural)? */
    fun profitExitHeld8030(pnlPct: Double, floorPct: Double, structural: Boolean): Boolean =
        !structural && pnlPct.isFinite() && pnlPct > 0.0 && pnlPct < floorPct

    /**
     * V5.0.8048 — a give-back exit (trail, profit lock, peak capture, drawdown) on a position whose peak already cleared
     * the net floor is protecting what is left, not taking a scalp. Holding it only waits for a worse print: 5.0.8046
     * MINERS peaked +15.8%, the trail fired at ~0% and was held; the profit-lock floor (cost+0.5, model cost) always
     * breached under this door's floor (cost+1, measured-or-model), so every lock sold at <= 0% as
     * REALIZED_LOSS_AFTER_PROFIT_SIGNAL. Pure.
     */
    fun giveBackReleased8048(reason: String, peakPct: Double, floorPct: Double): Boolean {
        if (!peakPct.isFinite() || !floorPct.isFinite() || peakPct < floorPct) return false
        val r = reason.uppercase()
        return listOf("TRAIL", "PROFIT_LOCK", "PEAK", "GIVEBACK", "GIVE_BACK", "DRAWDOWN", "BREAKEVEN", "BREAK_EVEN", "LOCK").any { r.contains(it) }
    }

    // ── live ──

    /**
     * The round trip (%) for [lane] at [sizeSol] against [liquidityUsd]: measured or modelled, the higher.
     * V5.0.8151 — the model is built from the trip's measured components (CostMeter8151: network fee per confirmed leg,
     * the tips actually set; the venue's fee for [mint]; the route's fee; the app's own fee; rent the reclaimer could
     * not take back) instead of the 7766 constants (a 1% "platform" fee and 0.000205 SOL a leg).
     */
    fun costPct8030(lane: String, sizeSol: Double, liquidityUsd: Double, mint: String = "", mcapUsd: Double = 0.0): Double {
        val size = if (sizeSol.isFinite() && sizeSol > 0.0) sizeSol else DEFAULT_TICKET_SOL
        val solUsd = try { com.lifecyclebot.engine.WalletManager.lastKnownSolPrice } catch (_: Throwable) { 0.0 }
        val sizeUsd = if (solUsd.isFinite() && solUsd > 0.0) size * solUsd else 0.0
        // V5.0.8037 — unknown depth (0) is not 20% impact here: on 8036 that priced every curve coin at a 23.5% round
        // trip and refused 80 entries. Unobserved liquidity has its own gates (ZERO_LIQUIDITY, Field Manual "no priced
        // liquidity"); this rule charges fees, tips and slippage and the impact only of depth it can see.
        val depthKnown = liquidityUsd.isFinite() && liquidityUsd > 0.0
        if (!depthKnown) unpricedDepth8037.incrementAndGet()
        // V5.0.8337 — impact is priced on the depth a trade actually moves: an active curve's VIRTUAL reserve (30 + real
        // SOL), not its $3 of real SOL (which read as 50%/side). Fees/venue keep the real figure.
        val impactLiq8337 = FieldManual7715.depthUsdForImpact8337(mint, if (depthKnown) liquidityUsd else 0.0, solUsd)
        val components8151 = try { componentsPct8151(size, mint, if (depthKnown) liquidityUsd else 0.0, mcapUsd) } catch (_: Throwable) { Double.NaN }
        // V5.0.8418 — slippage is the REALISED fill vs quote per side once receipts measure it (the 1 % allowance until
        // then), and impact is raised to a fresh quote's own priceImpactPct (RoundTrip8418).
        val depthImpact8418 = FieldManual7715.impactRoundTripPct(if (impactLiq8337 > 0.0) sizeUsd else 0.0, impactLiq8337)
        val impact8418 = try { com.lifecyclebot.engine.RoundTrip8418.impactLive8418(mint, size, depthImpact8418) } catch (_: Throwable) { depthImpact8418 }
        val slip8418 = try { com.lifecyclebot.engine.RoundTrip8418.slipRoundTripPctLive8418() } catch (_: Throwable) { FieldManual7715.SLIPPAGE_ALLOWANCE_PCT_7715 }
        val model = if (components8151.isFinite()) components8151 + slip8418 + impact8418
        else try { FieldManual7715.roundTripCostPct7766(size, if (impactLiq8337 > 0.0) sizeUsd else 0.0, impactLiq8337) } catch (_: Throwable) { 5.0 }
        // V5.0.8418 — the mark-based trip (SOL paid vs the tape 3 s before each fill) lifts the cost only until realised
        // slippage is receipt-measured; after that its gap to the receipts is the market's move, reported, not charged.
        val receipts8418 = try { com.lifecyclebot.engine.RealisedSlip8418.receiptMeasured8418() } catch (_: Throwable) { false }
        val mark8418 = try { CostLedger7962.measuredCostPct7962(lane, size) } catch (_: Throwable) { null }
        val measured = com.lifecyclebot.engine.RoundTrip8418.markLift8418(mark8418, receipts8418)
        try { com.lifecyclebot.engine.Costs8369.noteModel8369(lane, model) } catch (_: Throwable) {}  // V5.0.8369 — model vs measured
        return maxOf(model, measured ?: 0.0).also {
            lastCostPct = it
            // V5.0.8169 — what the figure is made of (5.0.8168 printed lastCost=58.64% with no lane, size or depth).
            lastBreakdown8169 = breakdown8169(lane, size, impactLiq8337, components8151.isFinite(),
                impact8418, measured, it) + com.lifecyclebot.engine.RoundTrip8418.breakdownTail8418(slip8418, impact8418, mark8418, receipts8418)
            tlBreakdown8209.set(lastBreakdown8169)   // V5.0.8209 — the same thread reads its own quote's breakdown, not a racing one
        }
    }

    @Volatile private var lastBreakdown8169 = "-"
    @Volatile private var lastEntryBreakdown8169 = "-"
    /** V5.0.8204 — the breakdown of the last entry cost (ticket, liquidity, impact, fees) for the refusal that used it. */
    fun lastEntryBreakdown8204(): String = lastEntryBreakdown8169
    @Volatile private var lastComponents8169 = doubleArrayOf(Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN)
    /** V5.0.8252 — the components THIS thread's quote priced; the breakdown never mixes another candidate's venue/route. */
    private val tlComponents8252 = ThreadLocal<DoubleArray>()

    /**
     * V5.0.8271 — the SELL leg's VARIABLE cost only (venue + route + app fee per side, half the slippage allowance and
     * half the depth impact), percent of [sizeSol]. [costPct8030] is an all-in round trip that already carries the fixed
     * legs and rent as a share of size; Executor passed half of it PLUS the fixed sell fee to ProfitEscalation8189, so
     * every fixed lamport was charged twice (0.030 proceeds / 0.0289 basis / 1 % / 0.000205 fixed: 0.000390 instead of
     * 0.000595, keeping an economic full take). Each component is charged once: this here, the fixed fee by the caller.
     */
    fun exitVariablePct8271(lane: String, sizeSol: Double, liquidityUsd: Double, mint: String = "", mcapUsd: Double = 0.0): Double {
        val rt = costPct8030(lane, sizeSol, liquidityUsd, mint, mcapUsd)
        val size = if (sizeSol.isFinite() && sizeSol > 0.0) sizeSol else DEFAULT_TICKET_SOL
        val comps = tlComponents8252.get()
        val fixedShare = if (comps != null && comps.size >= 5) com.lifecyclebot.engine.CostTrim8151.fixedCostSharePct8151(comps[3], comps[4], size) else 0.0
        return exitVariableFromRoundTrip8271(rt, fixedShare)
    }

    /** Pure: one leg's variable share of an all-in round trip whose fixed share of size is [fixedSharePct]. */
    fun exitVariableFromRoundTrip8271(roundTripPct: Double, fixedSharePct: Double): Double {
        if (!roundTripPct.isFinite()) return Double.NaN
        val fixed = if (fixedSharePct.isFinite() && fixedSharePct > 0.0) fixedSharePct else 0.0
        return maxOf(0.0, (roundTripPct - fixed) / 2.0)
    }

    /** V5.0.8169 — one priced round trip, per side: fees per side, the fixed leg, rent, slippage and impact halves. */
    private fun breakdown8169(lane: String, size: Double, liq: Double, componentModel: Boolean, impactRtPct: Double,
                              measured: Double?, usedPct: Double): String {
        val c = tlComponents8252.get() ?: lastComponents8169
        val perSideFees = if (componentModel) c[0] + c[1] + c[2] else Double.NaN
        val fixedLegPct = if (componentModel && size > 0.0) c[3] / size * 100.0 else Double.NaN
        val rentPct = if (componentModel && size > 0.0) c[4] / size * 100.0 else Double.NaN
        val slipHalf = FieldManual7715.SLIPPAGE_ALLOWANCE_PCT_7715 / 2.0
        val impactHalf = if (impactRtPct.isFinite()) impactRtPct / 2.0 else 0.0
        // V5.0.8418 — the app fee is asymmetric (buy 0.5 %, sell 0): c[2] is its per-side mean, so the buy leg carries the
        // difference and the sell leg does not.
        val appShift8418 = (com.lifecyclebot.engine.CostTrim8151.BUY_APP_FEE_PCT_8418 - com.lifecyclebot.engine.CostTrim8151.SELL_APP_FEE_PCT_8418) / 2.0
        val buySide = perSideFees + appShift8418 + fixedLegPct + slipHalf + impactHalf
        val sellSide = perSideFees - appShift8418 + fixedLegPct + slipHalf + impactHalf + (if (rentPct.isFinite()) rentPct else 0.0)
        fun p(v: Double) = if (v.isFinite()) "%.2f".format(v) else "-"
        return "lane=${lane.uppercase().take(14)} size=${"%.4f".format(size)} liq=${"%.0f".format(liq)} " +
            "venue=${p(c[0])}+route=${p(c[1])}%/side app=buy${p(com.lifecyclebot.engine.CostTrim8151.BUY_APP_FEE_PCT_8418)}/sell${p(com.lifecyclebot.engine.CostTrim8151.SELL_APP_FEE_PCT_8418)}% fixedLeg=${"%.6f".format(c[3])}SOL(${p(fixedLegPct)}%) rent=${p(rentPct)}% " +
            "slip=${p(slipHalf)}%/side impact=${p(impactHalf)}%/side buySide=${p(buySide)}% sellSide=${p(sellSide)}% " +
            "measured=${measured?.let { p(it) } ?: "-"} used=${p(usedPct)}%"
    }

    /**
     * V5.0.8151 — fees and fixed costs of one round trip (%), from the components each trade actually pays: the venue
     * fee (PaperVenueCost7287 for [mint]; an AMM's 0.25% when the mint is not known), the route fee (PumpPortal 0.5% on
     * a bonding curve, the aggregator otherwise), the app's fee (V5.0.8418: 0.5% on the buy, 0 on the sell, carried as its
     * per-side mean CostTrim8151.APP_FEE_PCT_PER_SIDE_8151), two measured fixed legs, and the rent share the
     * reclaimer could not take back. Slippage allowance and impact are added by [costPct8030].
     */
    private fun componentsPct8151(sizeSol: Double, mint: String, liquidityUsd: Double, mcapUsd: Double): Double {
        val venuePct = if (mint.isBlank()) 0.25 else PaperVenueCost7287.venueFeePct(mint, liquidityUsd, mcapUsd)
        // V5.0.8337 — the route fee follows the ROUTE a pump mint takes (PumpPortal), not the venue the depth classifies.
        // superseded by V5.0.8369 — "is a pump mint" is not the route: a curve buy goes Jupiter when Jupiter quotes it, and the
        // exit's first route follows the venue (Costs8369.modelRouteFeePctPerSide8369: buy route + sell route, per side).
        val routePct = com.lifecyclebot.engine.Costs8369.modelRouteFeePctPerSide8369(mint)
        val rentLoss = try { com.lifecyclebot.engine.RentReclaimer7927.rentLossRate8151() } catch (_: Throwable) { 0.0 }
        val fixedLeg = com.lifecyclebot.engine.CostMeter8151.roundTripFixedSol8151() / 2.0
        val rentSol = com.lifecyclebot.engine.CostTrim8151.ATA_RENT_SOL_8151 * rentLoss
        val rt = com.lifecyclebot.engine.CostTrim8151.roundTripPct8151(
            venuePct, routePct, com.lifecyclebot.engine.CostTrim8151.APP_FEE_PCT_PER_SIDE_8151, fixedLeg, rentSol, sizeSol,
        )
        val components = doubleArrayOf(venuePct, routePct, com.lifecyclebot.engine.CostTrim8151.APP_FEE_PCT_PER_SIDE_8151, fixedLeg, rentSol)
        lastComponents8169 = components  // V5.0.8169
        tlComponents8252.set(components)
        if (mint.isNotBlank() && sizeSol >= DEFAULT_TICKET_SOL * 0.5) com.lifecyclebot.engine.CostMeter8151.noteRoundTrip8151(rt)
        return rt
    }

    private val measuredLifts8329 = AtomicLong(0)

    /**
     * V5.0.8329 §COST_TRUTH — the PRE-TRADE round trip: [costPct8030] (model, or the 8-trade measured cost) lifted to the
     * lane's measured median once 2+ live round trips exist (CostLedger7962.measuredMedianPct8329). 8326 live: MOONSHOT
     * measured 14.8 % (n=2) while entries were judged on a 3.7 % model. Exits and labels keep [costPct8030]; wins are never
     * capped — a dearer round trip only raises the move an ENTRY must clear.
     */
    fun preTradeCostPct8329(lane: String, sizeSol: Double, liquidityUsd: Double, mint: String = "", mcapUsd: Double = 0.0): Double {
        val model = costPct8030(lane, sizeSol, liquidityUsd, mint, mcapUsd)
        // V5.0.8418 — the mark-based median lifts only until realised slippage is receipt-measured (RoundTrip8418.markLift8418).
        val receipts8418 = try { com.lifecyclebot.engine.RealisedSlip8418.receiptMeasured8418() } catch (_: Throwable) { false }
        val measured = com.lifecyclebot.engine.RoundTrip8418.markLift8418(
            try { CostLedger7962.measuredMedianPct8329(lane, 2) } catch (_: Throwable) { null }, receipts8418) ?: return model
        if (measured > model) {
            measuredLifts8329.incrementAndGet()
            try { PipelineHealthCollector.labelInc("COST_TRUTH_MEASURED_LIFTS_MODEL_8329_${lane.uppercase().take(16)}") } catch (_: Throwable) {}
        }
        return maxOf(model, measured)
    }

    /** LiveEdgeGate7877 (live): the refusal when [lane]'s expected move is under 2x the round trip, else null. */
    fun entryRefusal8030(ts: TokenState, lane: String): String? {
        val mandate = try { FieldManual7715.mandateFor(lane).expectedGrossPct } catch (_: Throwable) { return null }
        // V5.0.8035 — owner: "why is it buying bonk ... wouldn't this be common sense everywhere". The lane's textbook
        // move (20% for quality / bluechip) let BLUECHIP buy BONK ($300M cap) whose hour moves 1-3%. Every lane now
        // expects no more than the coin itself is actually moving: its last hour's range (or 1h change).
        // V5.0.8329 — at the lane's measured round trip once 2+ live trips exist (preTradeCostPct8329), never under the model.
        val cost = preTradeCostPct8329(lane, entryTicket8151(), liquidity8037(ts), ts.mint, ts.lastMcap)
        // V5.0.8153 — the move judged is the SELECTED strategy's own executable move when its record supports one, else the
        // mandate capped by a dense range / the 1h change; a fresh launch with only a sparse range defers for evidence
        // (bounded, coalesced per mint) instead of being refused as slow on two candles (StrategyMove8153).
        val move = executableMove8153(ts, lane, mandate, cost)
        if (StrategyMove8153.needsDefer8153(move, cost, margin8391(lane, entryTicket8151(), cost)) &&
            CandidateAdmission8136.deferBudgetFor8144(ts.mint, listOf(StrategyMove8153.DEFER_FEATURE_8153))) {
            historyDeferred8153.incrementAndGet()
            // The evidence the deferral waits for is asked for now: the candle seed and a forced poll (1h change, depth).
            try { com.lifecyclebot.engine.cortex.LanePlaybook7907.requestEvidence8153(ts, listOf("shape:HISTORY_SPARSE", "chg:UNOBSERVED", "liq:REFRESH")) } catch (_: Throwable) {}
            try { PipelineHealthCollector.labelInc("NET_EDGE_MOVE_HISTORY_DEFER_8153_${lane.uppercase().take(16)}") } catch (_: Throwable) {}
            return StrategyMove8153.DEFER_REASON_8153
        }
        val expected = move.expectedGrossPct
        if (entryClears8030(expected, cost, margin8391(lane, entryTicket8151(), cost))) return null
        entryRefused.incrementAndGet()
        // V5.0.8418 — a sparse range whose wait is spent is still not the coin's hour: the refusal says so, not "too slow".
        if (move.deferForHistory && move.basis == "RANGE_SPARSE") {
            try { PipelineHealthCollector.labelInc("${MOVE_UNOBSERVED_8418}_${lane.uppercase().take(16)}") } catch (_: Throwable) {}
            return MOVE_UNOBSERVED_8418
        }
        val slow = move.basis != "MANDATE" && move.basis != "STRATEGY" && expected < mandate
        if (slow) slowRefused8035.incrementAndGet()
        try { PipelineHealthCollector.labelInc("NET_EDGE_ENTRY_REFUSED_8030_${lane.uppercase().take(16)}") } catch (_: Throwable) {}
        return if (slow) "NET_EDGE_COIN_TOO_SLOW_8035" else "NET_EDGE_BELOW_2X_COST_8030"
    }

    /**
     * V5.0.8136 — CandidateAdmission8136: the same expected executable move [entryRefusal8030] judges, or NaN when the lane
     * has no mandate. V5.0.8153 — of [setup] (the strategy the admission selected), at [costPct]; NaN while a fresh launch's
     * sparse history defers the move (the admission then defers on economics, never refuses on two candles).
     */
    fun expectedGrossFor8153(ts: TokenState, lane: String, setup: String?, costPct: Double): Double {
        val m = expectedMove8418(ts, lane, setup, costPct) ?: return Double.NaN
        return if (StrategyMove8153.needsDefer8153(m, costPct, margin8391(lane, entryTicket8151(), costPct))) Double.NaN else m.expectedGrossPct
    }

    /**
     * V5.0.8418 — the whole move read [expectedGrossFor8153] reduces to a number (its basis says WHY a NaN is NaN: a sparse
     * history to wait for, or a falling hour that more history will not change); null when the lane has no mandate.
     */
    fun expectedMove8418(ts: TokenState, lane: String, setup: String?, costPct: Double): StrategyMove8153.Move8153? {
        val mandate = try { FieldManual7715.mandateFor(lane).expectedGrossPct } catch (_: Throwable) { return null }
        return executableMove8153(ts, lane, mandate, costPct, setup)
    }

    /** Pure (V5.0.8418): why [m] gives the admission no number at [costPct] — [MOVE_WAIT_HISTORY_8418], [MOVE_WAIT_FALLING_8418] or null. */
    fun moveWait8418(m: StrategyMove8153.Move8153?, costPct: Double, margin: Double = EDGE_MULT): String? = when {
        m == null || !StrategyMove8153.needsDefer8153(m, costPct, margin) -> null
        m.basis == "FALLING_HOUR_UNPROVEN" -> MOVE_WAIT_FALLING_8418
        else -> MOVE_WAIT_HISTORY_8418
    }
    private const val MOVE_UNOBSERVED_8418 = "NET_EDGE_MOVE_UNOBSERVED_8418"
    const val MOVE_WAIT_HISTORY_8418 = "HISTORY_SPARSE"
    const val MOVE_WAIT_FALLING_8418 = "FALLING_HOUR_UNPROVEN"

    /** V5.0.8418 — the move [executableMove8153] judges with [setup]'s own book left out (the wrong-clock fallback). */
    private fun moveWithoutBook8418(mandatePct: Double, range: Double?, dense: Boolean, h1: Double?, costPct: Double,
                                    age: Double?, hour: Double, lane: String): StrategyMove8153.Move8153 =
        StrategyMove8153.expected8153(
            mandatePct = mandatePct, rangePct = range, rangeDense = dense, h1Pct = h1,
            strategyN = 0, strategyMeanPct = Double.NaN, strategySePct = Double.NaN,
            costPct = costPct, freshLaunch = StrategyMove8153.fresh8153(age),
            signedHourPct = hour, momentumEntry = StrategyMove8153.momentumLane8153(lane),
            inLaunchWindow8418 = FallingHourCohort8418.inLaunchWindow8418(age),
        )

    /** V5.0.8153 — the expected executable move of [setup] (default: the strategy the coin trades as) for this entry. */
    fun executableMove8153(ts: TokenState, lane: String, mandatePct: Double, costPct: Double, setup: String? = null): StrategyMove8153.Move8153 {
        val nowMs = System.currentTimeMillis()
        val bars = synchronized(ts.history) { ts.history.filter { !it.synthetic && nowMs - it.ts in 0L..3_600_000L }.toList() }
        val range = rangePct8035(bars.flatMap { listOf(it.priceUsd, it.highUsd, it.lowUsd) })
        val spanMs = if (bars.size >= 2) bars.maxOf { it.ts } - bars.minOf { it.ts } else 0L
        // V5.0.8399 — the same dated hour move the setup classifier resolved (LanePlaybook7907.features: the row's reading
        // only while OBSERVED, else the tape / DexScreener pair / sweep fallback). 5.0.8398 read the raw row scalar here:
        // a stale or defaulted negative hour became FALLING_HOUR_UNPROVEN on a coin whose fresh pair reading was positive.
        val hour8399 = com.lifecyclebot.engine.cortex.LanePlaybook7907.resolvedChg1h8399(ts, nowMs)
        val h1 = StrategyMove8153.positiveHour8153(hour8399)
        val rec = try { com.lifecyclebot.engine.cortex.LanePlaybook7907.selectedRecord8153(ts, lane, setup, nowMs) } catch (_: Throwable) { null }
        val age = try { CanonicalTokenBirthTime7440.resolvedAgeMinutes(ts, nowMs) } catch (_: Throwable) { null }
        val m = StrategyMove8153.expected8153(
            mandatePct = mandatePct, rangePct = range, rangeDense = StrategyMove8153.dense8153(bars.size, spanMs), h1Pct = h1,
            strategyN = rec?.get(0)?.toInt() ?: 0, strategyMeanPct = rec?.get(1) ?: Double.NaN, strategySePct = rec?.get(2) ?: Double.NaN,
            costPct = costPct, freshLaunch = StrategyMove8153.fresh8153(age),
            signedHourPct = hour8399, momentumEntry = StrategyMove8153.momentumLane8153(lane),
            inLaunchWindow8418 = FallingHourCohort8418.inLaunchWindow8418(age),
        ).let { strat ->
            // V5.0.8418 — a book on the wrong clock (hour-graded lane, hour book short) may raise the move, never lower it.
            if (strat.basis != "STRATEGY") strat else StrategyMove8153.wrongClockMove8418(strat,
                moveWithoutBook8418(mandatePct, range, StrategyMove8153.dense8153(bars.size, spanMs), h1, costPct, age, hour8399, lane)) {
                com.lifecyclebot.engine.cortex.LaneGradeHorizon8406.wrongClockSpares8408(lane, "NET_EDGE_MOVE_8418")
            }
        }
        if (ts.mint.isNotBlank()) {
            if (moves8153.size > 4_000) moves8153.clear()
            moves8153[ts.mint] = "${m.basis}:${"%.1f".format(m.expectedGrossPct)}/bars${bars.size}"
        }
        return m
    }

    private val moves8153 = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val historyDeferred8153 = AtomicLong(0)

    /** V5.0.8153 — AdmissionRunnerTrace8144: the basis of the last expected move judged for [mint] ("" = none). */
    fun moveBasis8153(mint: String): String = moves8153[mint].orEmpty()

    /** V5.0.8136 — the round trip [entryRefusal8030] charges this entry: the default ticket against the observed depth. */
    fun entryCostPct8136(ts: TokenState, lane: String): Double =
        costPct8030(lane, entryTicket8151(), liquidity8037(ts), ts.mint, ts.lastMcap).also { lastEntryBreakdown8169 = lastBreakdown8169 }

    /** V5.0.8209 — the cost record of ONE quote: cost %, the size it priced (SOL), and its breakdown read on this thread. */
    data class EntryCost8209(val costPct: Double, val sizeSol: Double, val breakdown: String)

    /**
     * V5.0.8209 — the candidate's pre-admission cost at ITS proposed executable size. Pre-8209 every candidate was costed at
     * CostMeter8151's last sealed ticket (whatever lane or mint sealed last) and the refusal breakdown was a global last-write,
     * so EXECUTION_ECONOMICS_8136 refused or admitted on another trade's numbers. With no proposed size yet (callers without
     * a sizing read), a deterministic, capacity-bounded preview is used; the final size still re-quotes and seals.
     */
    /**
     * V5.0.8212: admission is an estimate of the eventual routable ticket, not a
     * permanently unexecutable sub-$3 request. This DOES NOT authorize an increase:
     * the FDG sizer, route share, live loss budget and exact sealed re-quote all
     * remain downstream. If one routable ticket is unaffordable, keep the
     * original requested size so it still refuses.
     */
    fun admissionQuoteSize8212(proposed: Double, tradeableSol: Double, solUsd: Double): Double {
        if (!proposed.isFinite() || proposed <= 0.0 || !tradeableSol.isFinite() ||
            tradeableSol <= 0.0 || !solUsd.isFinite() || solUsd <= 0.0) return proposed
        val p = try {
            com.lifecyclebot.v3.sizing.SmartSizerV3.routableCapacityPreflight7224(tradeableSol, solUsd)
        } catch (_: Throwable) { return proposed }
        val min = p.routableMinSol
        return if (!p.wouldRefuse && min.isFinite() && min > 0.0 && proposed < min &&
            min <= p.safeShareCapSol && min <= p.tradeableSol) min else proposed
    }

    fun entryCost8209(ts: TokenState, lane: String, proposedSizeSol: Double = Double.NaN): EntryCost8209 {
        val size = if (proposedSizeSol.isFinite() && proposedSizeSol > 0.0) proposedSizeSol else entryTicket8151()
        val cost = costPct8030(lane, size, liquidity8037(ts), ts.mint, ts.lastMcap)
        val bd = (tlBreakdown8209.get() ?: "COST_BREAKDOWN_UNAVAILABLE") +
            if (proposedSizeSol.isFinite() && proposedSizeSol > 0.0) " sizeAuthority=CANDIDATE" else " sizeAuthority=PREVIEW"
        lastEntryBreakdown8169 = bd
        return EntryCost8209(cost, size, bd)
    }
    private val tlBreakdown8209 = ThreadLocal<String>()

    private val slowRefused8035 = AtomicLong(0)

    // ── V5.0.8054 — lane fit: scalp lanes need a coin that moves; swing lanes don't buy the top of a moonshot ──
    //
    // Owner (wallet screenshot, 5.0.8051): CASHGEN bought NEET ($53.6M cap), OTC ($4.6M) and SLOPCORE ($1.9M) — two
    // stopped out within minutes; QUALITY bought Normie at a $364K cap after a +4,576% day (now -17%). "yes!!" to:
    // stop CASHGEN buying big caps that barely move, stop QUALITY buying coins that just made a huge run.
    private val SCALP_LANES_8054 = setOf("CASHGEN", "TREASURY")
    private val SWING_LANES_8054 = setOf("QUALITY", "BLUECHIP")
    private const val SCALP_EDGE_MULT_8054 = 3.0
    private const val OVEREXTENDED_1H_PCT_8054 = 100.0
    private const val OVEREXTENDED_LAUNCH_MULT_8054 = 50.0
    private const val OVEREXTENDED_MAX_AGE_MIN_8054 = 48.0 * 60.0
    private const val LAUNCH_CAP_SOL_8054 = 28.0
    private val scalpRefused8054 = AtomicLong(0)
    private val overextendedRefused8054 = AtomicLong(0)

    /** Pure: a scalp lane needs the coin's own last-hour move (known) at 3x the round trip. */
    fun scalpMoveTooSmall8054(
        realizedPct: Double?, costPct: Double,
        admission: CandidateAdmission8136.Decision8136? = null,
    ): Boolean {
        // superseded by V5.0.8418 (buy-path review of 76565f9ca) — a supported setup's PREDICTED net (label books) replaced the
        // coin's MEASURED last-hour move here, and filled in for an unknown one. The 8054 rule is a measured fact: the coin must
        // have moved 3x the round trip. The prediction is no longer the move; the admission's quote at the candidate's own size
        // is still the cost it is judged at.
        val quoted = admission?.takeIf { it.admitted && it.costPct.isFinite() && it.costPct > 0.0 &&
            it.costSizeSol.isFinite() && it.costSizeSol > 0.0 }
        val move = realizedPct
        val cost = quoted?.costPct ?: costPct
        return move == null || !move.isFinite() || !(cost.isFinite() && cost > 0.0) ||
            move < SCALP_EDGE_MULT_8054 * cost
    }

    const val SCALP_MOVE_UNOBSERVED_8418 = "NET_EDGE_SCALP_MOVE_UNOBSERVED_8418"

    /**
     * Pure (V5.0.8418): the scalp-lane wait when the coin's own move is unobserved: [StrategyMove8153.DEFER_REASON_8153] while
     * [budgetOpen], else [SCALP_MOVE_UNOBSERVED_8418] (a named terminal). Null when the move is known ([scalpMoveTooSmall8054]
     * decides).
     * 8416: SCALP_COIN_TOO_FLAT refused 1,397 — every unknown move among them was called "flat".
     */
    fun scalpWait8418(realizedPct: Double?, admission: CandidateAdmission8136.Decision8136?, budgetOpen: () -> Boolean): String? {
        if (realizedPct != null && realizedPct.isFinite()) return null
        // superseded by V5.0.8418 (buy-path review) — the admission's own-setup prediction no longer stands in for an unobserved
        // move: the scalp test is the coin's MEASURED hour, so an unknown one waits / ends by name ([admission] is not read).
        return if (budgetOpen()) StrategyMove8153.DEFER_REASON_8153 else SCALP_MOVE_UNOBSERVED_8418
    }

    private fun scalpUnobserved8418(ts: TokenState, lane: String, realized: Double?, admission: CandidateAdmission8136.Decision8136?): String? {
        val why = scalpWait8418(realized, admission) {
            CandidateAdmission8136.deferBudgetFor8144(ts.mint, listOf(StrategyMove8153.DEFER_FEATURE_8153))
        } ?: return null
        if (why == StrategyMove8153.DEFER_REASON_8153) {
            try { com.lifecyclebot.engine.cortex.LanePlaybook7907.requestEvidence8153(ts, listOf("shape:HISTORY_SPARSE", "chg:UNOBSERVED")) } catch (_: Throwable) {}
        }
        try { PipelineHealthCollector.labelInc("${if (why == SCALP_MOVE_UNOBSERVED_8418) why else "NET_EDGE_SCALP_MOVE_WAIT_8418"}_$lane") } catch (_: Throwable) {}
        return why
    }


    /**
     * Pure: a coin that already made its run — up 100%+ in the last hour, or a launchpad coin under 48 h old already
     * at 50x+ its launch cap (pump.fun opens at ~28 SOL; Normie $364K = ~83x).
     */
    fun overextended8054(change1hPct: Double, launchpad: Boolean, ageMin: Double?, capSol: Double): Boolean =
        (change1hPct.isFinite() && change1hPct >= OVEREXTENDED_1H_PCT_8054) ||
            (launchpad && ageMin != null && ageMin.isFinite() && ageMin <= OVEREXTENDED_MAX_AGE_MIN_8054 &&
                capSol.isFinite() && capSol >= OVEREXTENDED_LAUNCH_MULT_8054 * LAUNCH_CAP_SOL_8054)

    /** LiveEdgeGate7877 (live): the scalp-lane and swing-lane fit refusals, or null. */
    fun laneFitRefusal8054(ts: TokenState, laneRaw: String, admission: CandidateAdmission8136.Decision8136? = null): String? {
        val lane = laneRaw.trim().uppercase()
        if (lane in SCALP_LANES_8054) {
            val realized = try { realizedMovePct8035(ts) } catch (_: Throwable) { null }
            val cost = costPct8030(lane, entryTicket8151(), liquidity8037(ts), ts.mint, ts.lastMcap)
            scalpUnobserved8418(ts, lane, realized, admission)?.let { return it }   // V5.0.8418 — unobserved is not flat
            if (scalpMoveTooSmall8054(realized, cost, admission)) {
                scalpRefused8054.incrementAndGet()
                try { PipelineHealthCollector.labelInc("NET_EDGE_SCALP_COIN_TOO_FLAT_8054_$lane") } catch (_: Throwable) {}
                return "NET_EDGE_SCALP_COIN_TOO_FLAT_8054"
            }
        }
        if (lane in SWING_LANES_8054) {
            val launchpad = ts.mint.endsWith("pump", true) || ts.mint.endsWith("bonk", true) ||
                (try { com.lifecyclebot.network.PumpCurveKeys7269.keyFor(ts.mint) } catch (_: Throwable) { null }) != null
            val age = try { CanonicalTokenBirthTime7440.resolvedAgeMinutes(ts) } catch (_: Throwable) { null }
            val solUsd = try { com.lifecyclebot.engine.WalletManager.lastKnownSolPrice } catch (_: Throwable) { 0.0 }
            val capSol = try { EconomicUnitInvariant7061.usdToSol(TrustedMcap8019.mcap8019(ts), solUsd) } catch (_: Throwable) { Double.NaN }
            val hour = com.lifecyclebot.engine.cortex.LanePlaybook7907.resolvedChg1h8399(ts, System.currentTimeMillis())
            if (overextended8054(hour, launchpad, age, capSol)) {
                overextendedRefused8054.incrementAndGet()
                try { PipelineHealthCollector.labelInc("SWING_LANE_OVEREXTENDED_8054_$lane") } catch (_: Throwable) {}
                return "SWING_LANE_ALREADY_RAN_8054"
            }
        }
        return null
    }
    private val cryptoRefused8042 = AtomicLong(0)

    /**
     * V5.0.8042 — the crypto lane through the same rule. 5.0.8041: CRYPTO_ALT bought SHIB live (a large-cap wrapped
     * token whose day moves a few percent) on a lane whose 120 live closes read WR 19%, -6.7% a trade, -0.69 SOL. The
     * expected move is no more than the coin's own 24 h move (the crypto mandate caps it); it must clear 2x the round
     * trip at this size and depth. Returns the refusal, or null.
     */
    fun cryptoEntryRefusal8042(change24hPct: Double, sizeSol: Double, liquidityUsd: Double): String? {
        val mandate = try { FieldManual7715.mandateFor("CRYPTO_ALT").expectedGrossPct } catch (_: Throwable) { 12.0 }
        val realized = kotlin.math.abs(change24hPct).takeIf { it.isFinite() }
        val cost = costPct8030("CRYPTO_ALT", sizeSol, liquidityUsd)
        if (entryClears8030(expectedMove8035(mandate, realized), cost, margin8391("CRYPTO_ALT", sizeSol, cost))) return null
        cryptoRefused8042.incrementAndGet()
        try { PipelineHealthCollector.labelInc("NET_EDGE_CRYPTO_REFUSED_8042") } catch (_: Throwable) {}
        return "NET_EDGE_CRYPTO_TOO_SLOW_8042"
    }
    private val unpricedDepth8037 = AtomicLong(0)

    /** Price the candidate's expected impact from the same dated, positive
     * pair/sweep observation that CandidateAdmission8136 reads when its own
     * token scalar has not hydrated. It is COST ESTIMATION ONLY — never a
     * liquidity safety permit, an on-chain depth proof, or an entry quote.
     * A real canonical liquidity reading always takes priority.
     */
    fun pricingDepth8171(canonicalUsd: Double, datedObservedUsd: Double?): Double =
        if (canonicalUsd.isFinite() && canonicalUsd > 0.0) canonicalUsd
        else datedObservedUsd?.takeIf { it.isFinite() && it > 0.0 } ?: 0.0

    private fun liquidity8037(ts: TokenState): Double {
        val canonical = try { LiquidityTruth8037.hydrate8037(ts) } catch (_: Throwable) { ts.lastLiquidityUsd }
        if (canonical.isFinite() && canonical > 0.0) return canonical
        val now = System.currentTimeMillis()
        val age = try { CanonicalTokenBirthTime7440.resolvedAgeMinutes(ts, now) ?: Double.NaN } catch (_: Throwable) { Double.NaN }
        val dated = try { SetupFeatureResolver8169.observedLiquidity8170(ts.mint, age, now) } catch (_: Throwable) { null }
        if (dated != null && dated > 0.0) try { PipelineHealthCollector.labelInc("NET_EDGE_OBSERVED_DEPTH_COST_ONLY_8171") } catch (_: Throwable) {}
        return pricingDepth8171(canonical, dated)
    }

    /** Pure: the move to expect — the lane's mandate, capped by what the coin is actually moving when that is known. */
    fun expectedMove8035(mandatePct: Double, realizedPct: Double?): Double =
        if (realizedPct != null && realizedPct.isFinite() && realizedPct >= 0.0) minOf(mandatePct, realizedPct) else mandatePct

    /** Pure: the range (%) of [prices] (the last hour's prints), or null with fewer than [minPrints]. */
    fun rangePct8035(prices: List<Double>, minPrints: Int = 6): Double? {
        val p = prices.filter { it.isFinite() && it > 0.0 }
        if (p.size < minPrints) return null
        return (p.max() / p.min() - 1.0) * 100.0
    }

    /** The coin's own recent move: the last hour's high-low range from its real candles, or its 1h change; null if unknown. */
    private fun realizedMovePct8035(ts: TokenState, nowMs: Long = System.currentTimeMillis()): Double? {
        val bars = synchronized(ts.history) { ts.history.filter { !it.synthetic && nowMs - it.ts in 0L..3_600_000L }.toList() }
        val range = rangePct8035(bars.flatMap { listOf(it.priceUsd, it.highUsd, it.lowUsd) })
        val spanMs = if (bars.size >= 2) bars.maxOf { it.ts } - bars.minOf { it.ts } else 0L
        val h1 = StrategyMove8153.positiveHour8153(com.lifecyclebot.engine.cortex.LanePlaybook7907.resolvedChg1h8399(ts, nowMs))   // V5.0.8399 — same dated reading
        return realizedMove8418(range, StrategyMove8153.dense8153(bars.size, spanMs), h1)
    }

    /**
     * Pure (V5.0.8418 §SPARSE_RANGE_IS_PRE_HISTORY): the coin's own last-hour move for the scalp test — the range only when it
     * is DENSE (StrategyMove8153.dense8153); a sparse range (two candles at intake) is not the hour and only counts beside a
     * measured hour change, never alone. Null = unobserved (the caller waits for history, it does not call the coin flat).
     */
    fun realizedMove8418(rangePct: Double?, rangeDense: Boolean, h1Pct: Double?): Double? {
        val range = rangePct?.takeIf { it.isFinite() && it >= 0.0 }
        val h1 = h1Pct?.takeIf { it.isFinite() && it > 0.0 }
        return when {
            range != null && rangeDense -> if (h1 != null) maxOf(range, h1) else range
            h1 != null -> if (range != null) maxOf(range, h1) else h1
            else -> null
        }
    }

    /** Executor sell door (live): true holds a green profit-taking exit that would not clear the net floor. */
    fun exitRefusal8030(ts: TokenState, reason: String, markPrice: Double, partial: Boolean): Boolean {
        val pos = ts.position
        if (!pos.isOpen || pos.isPaperPosition || !(pos.entryPrice > 0.0) || !(markPrice > 0.0)) return false
        val pnl = (markPrice / pos.entryPrice - 1.0) * 100.0
        if (!(pnl > 0.0)) return false
        // V5.0.8337 — a protective exit (ranks 1-3) is never held by the cost model.
        if (try { com.lifecyclebot.engine.ExitTruthArbiter8144.protective8144(reason) } catch (_: Throwable) { false }) return false
        // V5.0.8363 — a profit lock / give-back / trail is protective (ProfitLock8363, the one classification): never held.
        if (try { com.lifecyclebot.engine.ProfitLock8363.profitLock8363(reason) } catch (_: Throwable) { false }) return false
        // V5.0.8468 — a fast lane's own designed exit (momentum death, time exit, take-profit, ...) before promotion acts:
        // holding a fading +3 % for cost + 1 % rode it down to the stop (LaneExitContract8468).
        if (try { com.lifecyclebot.engine.LaneExitContract8468.acts8468(ts, reason) } catch (_: Throwable) { false }) return false
        val structural = structural8030(reason)
        if (structural) return false
        try { OnChainDepth8038.request8038(ts) } catch (_: Throwable) {}
        // V5.0.8169 — the buy leg's fixed cost is already paid (sunk): an exit is charged the round trip less that leg. On a
        //    ~0.001 SOL remainder both legs read ~59%, so every profit exit of a small remainder was held.
        val roundTrip = costPct8030(pos.tradingMode.ifBlank { "UNKNOWN" }, pos.costSol, liquidity8037(ts), ts.mint, ts.lastMcap)
        val cost = exitCostPct8169(roundTrip, try { com.lifecyclebot.engine.CostMeter8151.roundTripFixedSol8151() / 2.0 } catch (_: Throwable) { Double.NaN }, pos.costSol)
        val floor = exitFloorPct8030(cost)
        if (!profitExitHeld8030(pnl, floor, structural)) return false
        val peak = if (pos.highestPrice > 0.0) (pos.highestPrice / pos.entryPrice - 1.0) * 100.0 else Double.NaN
        if (giveBackReleased8048(reason, peak, floor)) {
            giveBackReleased.incrementAndGet()
            try { PipelineHealthCollector.labelInc("NET_PROFIT_GIVEBACK_RELEASED_8048") } catch (_: Throwable) {}
            return false
        }
        if (partial) partialHeld.incrementAndGet() else exitHeld.incrementAndGet()
        try {
            PipelineHealthCollector.labelInc(if (partial) "NET_PROFIT_PARTIAL_HELD_8030" else "NET_PROFIT_EXIT_HELD_8030")
            if (com.lifecyclebot.engine.ForensicEmitRateLimiter6356.shouldEmit("NET_PROFIT_8030", ts.mint)) {
                com.lifecyclebot.engine.ForensicLogger.lifecycle(
                    "NET_PROFIT_EXIT_HELD_8030",
                    "mint=${ts.mint.take(10)} sym=${ts.symbol} lane=${pos.tradingMode} pnl=${"%.2f".format(pnl)} cost=${"%.2f".format(cost)} floor=${"%.2f".format(floor)} partial=$partial reason=${reason.take(50)}",
                )
            }
        } catch (_: Throwable) {}
        return true
    }

    fun statusLine(): String =
        "entryRefused=${entryRefused.get()} coinTooSlow=${slowRefused8035.get()} moveHistoryDeferred8153=${historyDeferred8153.get()} scalpTooFlat8054=${scalpRefused8054.get()} swingAlreadyRan8054=${overextendedRefused8054.get()} cryptoRefused=${cryptoRefused8042.get()} depthUnknown=${unpricedDepth8037.get()} profitExitsHeld=${exitHeld.get()} giveBackReleased8048=${giveBackReleased.get()} partialsHeld=${partialHeld.get()} lastCost=${if (lastCostPct.isFinite()) "%.2f".format(lastCostPct) + "%" else "-"} " +
            "rule=entry move>=${EDGE_MULT}x cost · exit only at >= cost+${NET_BUFFER_PCT}% (stops never held)" +
            " · lastCost8169=[$lastBreakdown8169] lastEntryCost8169=[$lastEntryBreakdown8169]" +
            " · cost8151 " + com.lifecyclebot.engine.CostMeter8151.statusLine8151(DEFAULT_TICKET_SOL)

    /** V5.0.8151 — the entry is costed at the last live ticket that actually sealed (TradeVerifier), else the default. */
    // Pre-size evaluations use the configured preview notional promoted only within
    // current wallet capacity. Another mint's last sealed ticket is never input.
    private fun entryTicket8151(): Double = try {
        if (com.lifecyclebot.engine.RuntimeModeAuthority.isPaper()) paperTicket8399()
        else admissionQuoteSize8212(DEFAULT_TICKET_SOL,
            (com.lifecyclebot.engine.BotService.status.walletSol - LiveSpendReserveAuthority7255.RESERVE_SOL).coerceAtLeast(0.0),
            com.lifecyclebot.engine.WalletManager.lastKnownSolPrice)
    } catch (_: Throwable) { DEFAULT_TICKET_SOL }

    /** Paper previews cost at a meaningful share of the PAPER ledger, never at the live wallet's dust ticket. */
    const val PAPER_TICKET_WALLET_PCT_8399 = 5.0

    /**
     * V5.0.8399 §PAPER_COST_AT_LIVE_DUST — the admission cost preview priced every paper candidate at the LIVE wallet's
     * routable minimum (0.31 SOL wallet -> ~0.027 SOL tickets, 15–25% round trips from fixed fees + ATA rent), so paper
     * refused EXECUTION_ECONOMICS_8136 on exactly the setups it exists to learn (374 in ten minutes on 5.0.8398) and never
     * produced the closes the evidence hold needs. Paper is a live-economics mirror at wallet-percent size: 5% of the
     * paper ledger, floored at the default ticket, capped by the routable preflight's safe share when it is known.
     */
    fun paperTicket8399(
        paperWalletSol: Double = try { com.lifecyclebot.engine.BotService.status.paperWalletSol } catch (_: Throwable) { Double.NaN },
        solUsd: Double = try { com.lifecyclebot.engine.WalletManager.lastKnownSolPrice } catch (_: Throwable) { Double.NaN },
    ): Double {
        if (!paperWalletSol.isFinite() || paperWalletSol <= 0.0) return DEFAULT_TICKET_SOL
        val share = (paperWalletSol * PAPER_TICKET_WALLET_PCT_8399 / 100.0).coerceAtLeast(DEFAULT_TICKET_SOL)
        val cap = try {
            if (solUsd.isFinite() && solUsd > 0.0) com.lifecyclebot.v3.sizing.SmartSizerV3.routableCapacityPreflight7224(paperWalletSol, solUsd).safeShareCapSol else Double.NaN
        } catch (_: Throwable) { Double.NaN }
        val t = if (cap.isFinite() && cap > 0.0) minOf(share, cap).coerceAtLeast(DEFAULT_TICKET_SOL) else share
        try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("PAPER_TICKET_WALLET_PCT_8399") } catch (_: Throwable) {}
        return t
    }
}
