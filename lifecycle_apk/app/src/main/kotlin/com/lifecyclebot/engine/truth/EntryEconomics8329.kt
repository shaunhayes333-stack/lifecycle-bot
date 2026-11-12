package com.lifecyclebot.engine.truth

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.ExecutableOpenGate.ExecutionIntent
import com.lifecyclebot.engine.WalletManager
import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8329 §ENTRY_ECONOMICS_BIND_INSIDE_8114_MODE — the owner keeps retired compatibility mode ON; the few candidate-specific,
 * blockchain-provable reads now bind inside it. 5.0.8326 live (8114 mode): 1W/7L, PF 0.08; decision-to-fill chase MOONSHOT
 * +13.5 % (3 s), EXPRESS +10.7 %, CASHGEN +6.1 %; measured round trip MOONSHOT 14.8 % / QUALITY 19.1 % / CASHGEN 8.4 %
 * against a 3.7 % modelled breakeven; ROUTE_MIN_PROMOTED_KELLY_BELOW_MIN = 34 on a Kelly of ~0; MOONSHOT four consecutive
 * losing live closes while "lane realised losing" was bypassed 20 times as if it were a label.
 *
 *  1. [laneRealisedRefusal8329] — the lane's OWN live closes (3+, mean < 0: LaneRealisedGovernor8295.tailRefuted8295) are
 *     realised money, not a label: they refuse in mode ([LANE_REALISED_BINDS_8329]_<lane>). A shut lane re-opens on
 *     provable evidence only: its next live close after the shut is a win, or the mean of its last [REOPEN_LAST_N_8329]
 *     closes is >= 0; after a re-open it re-arms only on a NEW losing close. The label-based refusals (lane labels not
 *     positive, proven-setup-only, cheat sheet) stay bypassed per the owner's choice.
 *  2. [preBroadcastRefusal8329] — immediately before the live buy is sent: the fresh executable quote (the Jupiter quote the
 *     buy is built from; the curve mark on the PumpPortal route, which has no quote) against the decision price and time
 *     sealed in the execution intent. move % net of the quote's own impact must sit under the tactic's cap
 *     ([chaseCapPct8329]: launch / breakout 8 %, pullback / reaccumulation 2.5 %, momentum 5 %, an exhausted rally none)
 *     and the move left (expected gross - move) must still clear the owner's 1.5x round trip at this size
 *     (NetEdge8030.entryClears8030 on [NetEdge8030.preTradeCostPct8329]). Otherwise the buy is not sent
 *     ([CHASE_CONSUMED_8329]_<lane>), a "missed" forward label is filed under CHASEMISS_<lane> so the refusal is graded
 *     against what happened next, and a later structural re-entry (higher-low reclaim) re-arms the coin. EntryChase7961's
 *     eight-fill learned penalty is untouched. Binds in 8114 mode: this is economics at fill time, not selection.
 *  3. [promotionReasonRefusal8329] — a sub-minimum Kelly stake is promoted to the route minimum only for one named
 *     candidate-level reason: the selected setup's own record positive on 12+ labels, an early runner (LAUNCH_EARLY plan
 *     inside the launch window; the 8320 curve exit-safety rule still binds in PreTradeHardGate), or Kelly >= 25 % of the
 *     minimum. Otherwise [PROMOTION_UNSUPPORTED_8329]_<lane>. The viable route minimum itself is unchanged.
 *  4. Cost truth lives in [NetEdge8030.preTradeCostPct8329] (max of the model and the lane's measured median once 2+ live
 *     round trips exist) and in CostLedger7962.measuredMedianPct8329. Wins are never capped.
 */
object EntryEconomics8329 {

    private fun labelInc(key: String) { try { PipelineHealthCollector.labelInc(key) } catch (_: Throwable) {} }

    // ── 1. the lane's realised closes bind in mode ──

    const val LANE_REALISED_BINDS_8329 = "ENTRY8114_LANE_REALISED_LOSING_BINDS_8329"
    const val REOPEN_LAST_N_8329 = 5

    private class Shut8329(val sinceMs: Long, val closes: Int, val meanPct: Double)
    private val shut8329 = ConcurrentHashMap<String, Shut8329>()
    private val reopenedAt8329 = ConcurrentHashMap<String, Long>()
    private val realisedBinds8329 = ConcurrentHashMap<String, AtomicLong>()

    /** Pure: the lane's own live closes shut it (3+, mean < 0 — the LaneRealisedGovernor8295 rule). */
    fun laneShut8329(closes: Double, meanPct: Double): Boolean =
        com.lifecyclebot.engine.cortex.LaneRealisedGovernor8295.tailRefuted8295(doubleArrayOf(closes, meanPct))

    /**
     * Pure: a lane shut at [shutSinceMs] re-opens when a live close after the shut won (realised SOL > 0), or when the mean
     * realised return of its last [REOPEN_LAST_N_8329] closes is >= 0. [closes] = (atMs, realizedPnlSol, realizedReturnPct).
     */
    fun reopens8329(shutSinceMs: Long, closes: List<Triple<Long, Double, Double>>): Boolean {
        if (closes.any { it.first > shutSinceMs && it.second > 0.0 }) return true
        val last = closes.sortedByDescending { it.first }.take(REOPEN_LAST_N_8329).map { it.third }.filter { it.isFinite() }
        return last.isNotEmpty() && last.average() >= 0.0
    }

    /** Pure: after a re-open at [reopenedAtMs] the shut rule re-arms only on a NEW losing close. */
    fun rearms8329(reopenedAtMs: Long, latestCloseAtMs: Long, latestClosePnlSol: Double): Boolean =
        latestCloseAtMs > reopenedAtMs && latestClosePnlSol < 0.0

    private fun laneCloses8329(lane: String): List<Triple<Long, Double, Double>> = try {
        CanonicalFinalizedTradeBus6464.terminalEnvelopes7863("live")
            .filter { CanonicalLaneIdentity6506.desk8392(it.lane) == lane }
            .map { Triple(it.atMs, it.realizedPnlSol, it.realizedReturnPct) }
    } catch (_: Throwable) { emptyList() }

    /**
     * LiveEdgeGate7877.liveRefusal: the realised-lane refusal for [lane] now (binds in mode), else null.
     * V5.0.8424 — a shut lane is not halted: it PROBES (LaneRealisedProbe8424: one small-stake live position at a time; a
     * proven-losing lane once per two hours). [mint] names the candidate so its probe claim survives the FDG's re-asks;
     * blank = read-only (no claim).
     */
    fun laneRealisedRefusal8329(lane: String, nowMs: Long = System.currentTimeMillis(), mint: String = ""): String? {
        val l = CanonicalLaneIdentity6506.desk8392(lane)
        val r = try { com.lifecyclebot.engine.cortex.Cortex7885.laneRealised8295(l) } catch (_: Throwable) { null }
        if (r == null || r.size < 2 || !laneShut8329(r[0], r[1])) { shut8329.remove(l); return null }
        val closes = laneCloses8329(l)
        val reopened = reopenedAt8329[l]
        if (reopened != null) {
            val latest = closes.maxByOrNull { it.first }
            if (latest == null || !rearms8329(reopened, latest.first, latest.second)) return null
            reopenedAt8329.remove(l)
        }
        val s = shut8329[l] ?: Shut8329(nowMs, r[0].toInt(), r[1]).also { shut8329[l] = it }
        if (reopens8329(s.sinceMs, closes)) {
            shut8329.remove(l)
            reopenedAt8329[l] = nowMs
            labelInc("ENTRY8114_LANE_REOPENED_8329_$l")
            return null
        }
        // V5.0.8424 §A_LOSING_LANE_SIZES_DOWN_IT_DOES_NOT_HALT — the shut lane probes; only an occupied slot or a proven
        // lane's probe gap refuses. superseded: `return "${LANE_REALISED_BINDS_8329}_$l"` for every candidate while shut
        // (8422: MOONSHOT shut on n3/-21.3% with nothing able to enter and so nothing able to re-open it).
        val probe8424 = LaneRealisedProbe8424.probe8424(l, mint, closes, s.sinceMs, nowMs) ?: return null
        realisedBinds8329.computeIfAbsent(l) { AtomicLong(0) }.incrementAndGet()
        labelInc("${LANE_REALISED_BINDS_8329}_$l")
        return probe8424
    }

    // ── 2. pre-broadcast re-quote and chase check ──

    const val CHASE_CONSUMED_8329 = "ENTRY_CHASE_CONSUMED_8329"
    const val MISSED_LANE_PREFIX_8329 = "CHASEMISS_"

    /**
     * A nullable admission proof is not necessarily a usable gross forecast: an
     * earlier unpriced observation may have sealed NaN. Kotlin's `?:` does not
     * replace NaN. Re-evaluate the SAME selected setup at the fresh executable
     * quote/cost only when the bound forecast is non-finite. An unpriced fresh
     * result remains non-finite and the existing execution refusal still wins.
     */
    internal fun expectedGrossAtExecution8653(bound: Double?, fresh: () -> Double): Double =
        bound?.takeIf { it.isFinite() } ?: try { fresh() } catch (_: Exception) { Double.NaN }
    const val CAP_LAUNCH_PCT_8329 = 8.0
    const val CAP_MOMENTUM_PCT_8329 = 5.0
    const val CAP_DEFAULT_PCT_8329 = 4.0
    const val CAP_PULLBACK_PCT_8329 = 2.5
    private const val DECISION_MAX_AGE_MS_8329 = 10L * 60_000L
    private const val MOVES_RING_8329 = 60

    /**
     * Pure: the most the executable quote may sit above the decision price for this plan setup / tactic.
     * NaN = an exhausted rally (chasing a spike at fill): no move up is bought.
     */
    fun chaseCapPct8329(setup: TradePlan7739.Setup?, tactic: com.lifecyclebot.engine.learning.TacticSwitcher.Tactic?, exhaustedRally: Boolean): Double {
        if (exhaustedRally) return Double.NaN
        return when (setup) {
            TradePlan7739.Setup.LAUNCH_EARLY, TradePlan7739.Setup.BASE_BREAKOUT -> CAP_LAUNCH_PCT_8329
            TradePlan7739.Setup.PULLBACK_RECLAIM, TradePlan7739.Setup.SWEEP_RECLAIM -> CAP_PULLBACK_PCT_8329
            else -> when (tactic) {
                com.lifecyclebot.engine.learning.TacticSwitcher.Tactic.BREAKOUT -> CAP_LAUNCH_PCT_8329
                com.lifecyclebot.engine.learning.TacticSwitcher.Tactic.PULLBACK -> CAP_PULLBACK_PCT_8329
                com.lifecyclebot.engine.learning.TacticSwitcher.Tactic.REACCUMULATION -> CAP_PULLBACK_PCT_8329
                com.lifecyclebot.engine.learning.TacticSwitcher.Tactic.MOMENTUM -> CAP_MOMENTUM_PCT_8329
                else -> CAP_DEFAULT_PCT_8329
            }
        }
    }

    /**
     * V5.0.8723 §THE_CHASE_CAP_IS_A_DOOR_LIKE_ANY_OTHER — owner: "there isnt meant to be anything fixed". The caps above
     * (8 / 5 / 4 / 2.5 % by setup) are hand numbers; 8720 live: all three EXPRESS tickets died on this check. The economic
     * test in [consumed8329] is not a hand number: the move that is left after paying up must still clear the round trip
     * at its margin, and an exhausted rally buys no move up. The fixed cap now binds a lane only once that lane's own
     * chase-miss record (the coins this check turned away, graded forward under [MISSED_LANE_PREFIX_8329]) proves they
     * lose, on the bar every learned door earns its right to bind (GateOutcome8418.protects8715: 40+ graded, mean + SE
     * below zero). Until then the economics and the exhaustion test decide.
     */
    fun chaseCapBinds8723(n: Int, meanPct: Double, sePct: Double): Boolean =
        n >= 40 && meanPct.isFinite() && sePct.isFinite() && meanPct + sePct < 0.0
    /** The cap handed to [consumed8329] when the fixed one has not earned its right to bind. */
    const val UNCAPPED_8723 = 1.0e9

    /**
     * Pure: why the edge is consumed at the quote, else null. [movePct] = quote vs decision price (positive = dearer),
     * [capPct] from [chaseCapPct8329], [expectedGrossPct] the move the entry was admitted on, [costPct] the round trip at
     * this size. A cheaper or unchanged quote never refuses.
     */
    fun consumed8329(movePct: Double, capPct: Double, expectedGrossPct: Double, costPct: Double, margin: Double = NetEdge8030.EDGE_MULT): String? {
        // RunnerGrab also calls this during scouting, before an admitted mark can exist.
        // preBroadcastRefusal8329 separately requires a comparable quote at the live send boundary.
        if (!movePct.isFinite()) return null
        if (movePct > 0.0 && !capPct.isFinite()) return "EXHAUSTED_RALLY"
        if (movePct > capPct) return "OVER_CAP"
        if (!expectedGrossPct.isFinite() || !costPct.isFinite()) return "ECONOMICS_UNAVAILABLE"
        val chase = movePct.coerceAtLeast(0.0)
        val remainingPct = (expectedGrossPct - chase) / (1.0 + chase / 100.0)
        return if (NetEdge8030.entryClears8030(remainingPct, costPct, margin)) null else "EDGE_CONSUMED"
    }

    /** Pure: the USD price one UI token costs at a quote of [outRaw] raw tokens for [inSol] SOL; NaN when unknown. */
    fun quotePxUsd8329(inSol: Double, outRaw: Long, decimals: Int?, solUsd: Double): Double {
        if (decimals == null || decimals < 0 || decimals > 18 || outRaw <= 0L || !(inSol > 0.0) || !(solUsd > 0.0)) return Double.NaN
        val ui = outRaw.toDouble() / Math.pow(10.0, decimals.toDouble())
        return if (ui > 0.0) inSol * solUsd / ui else Double.NaN
    }

    /** Pure: the market move since the decision, net of the quote's own [impactPct] (already charged by the cost model). */
    fun quoteMovePct8329(decisionPx: Double, quotePx: Double, impactPct: Double): Double? {
        val raw = EntryChase7961.chasePct7961(decisionPx, quotePx) ?: return null
        val impact = if (impactPct.isFinite() && impactPct > 0.0) impactPct else 0.0
        return raw - impact
    }

    private val lastChase8726 = ConcurrentHashMap<String, String>()
    /** The numbers the last chase refusal of [mint] was made on, or null. */
    fun lastChaseDetail8726(mint: String): String? = lastChase8726[mint]

    private class ChaseBook8329 { val checked = AtomicLong(0); val consumed = AtomicLong(0); val moves = ArrayDeque<Double>() }
    private val chase8329 = ConcurrentHashMap<String, ChaseBook8329>()
    private val consumedAt8329 = ConcurrentHashMap<String, Long>()

    /**
     * Executor, immediately before the live buy is sent: null = send; else the refusal (the buy is not sent). [quotePxUsd]
     * is the route quote's implied price (or the validated Pump mark), [impactPct] its own
     * impact, [source] the route for the log.
     */
    fun preBroadcastRefusal8329(
        ts: TokenState, intent: ExecutionIntent, sizeSol: Double, quotePxUsd: Double, impactPct: Double, source: String,
        nowMs: Long = System.currentTimeMillis(),
    ): String? {
        val lane = CanonicalLaneIdentity6506.desk8392(intent.canonicalLane)
        val book = chase8329.computeIfAbsent(lane) { ChaseBook8329() }
        val decisionPx = intent.executableMarkPriceUsd6613
        val observedAt = intent.executableMarkTimestampMs6613
        if (intent.mint != ts.mint || nowMs - observedAt !in 0L..DECISION_MAX_AGE_MS_8329 ||
            nowMs >= intent.expiresAtMs6613) return "ENTRY_CHASE_DECISION_STALE_$lane"
        val px = quotePxUsd
        val move = quoteMovePct8329(decisionPx, px, impactPct) ?: return "ENTRY_CHASE_PRICE_INCOMPARABLE_$lane"
        if (consumedAt8329.containsKey(ts.mint) &&
            (try { com.lifecyclebot.engine.chart.StructureTracker7962.hlReclaim7962(ts.mint, nowMs) } catch (_: Throwable) { false })) {
            consumedAt8329.remove(ts.mint)
            labelInc("ENTRY_CHASE_REARMED_HL_RECLAIM_8329_$lane")
        }
        val setup = try { TradePlan7739.freshPlan7783(ts.mint, nowMs)?.setup } catch (_: Throwable) { null }
        val sel = intent.admissionProof?.chosenSetup
            ?: try { com.lifecyclebot.engine.cortex.LanePlaybook7907.selectedSetup8094(ts, lane, nowMs) } catch (_: Throwable) { null }
        val tactic = try { com.lifecyclebot.engine.learning.TacticSwitcher.seedTactic8049(lane, sel) } catch (_: Throwable) { null }
        val snap = try { com.lifecyclebot.engine.TokenMetricStageRouter.snapshot(ts) } catch (_: Throwable) { null }
        val exhausted = snap != null &&
            (try { LiveEdgeGate7877.chasing8065(snap.runupFromLocalLowPct, snap.currentVsPeak, ts.lastPriceChange5m) } catch (_: Throwable) { false })
        val liq = try { LiquidityTruth8037.hydrate8037(ts) } catch (_: Throwable) { ts.lastLiquidityUsd }
        val cost = try { NetEdge8030.preTradeCostPct8329(lane, sizeSol, liq, ts.mint, ts.lastMcap) } catch (_: Throwable) { Double.NaN }
        val expected = expectedGrossAtExecution8653(intent.admissionProof?.expectedGrossPct) {
            NetEdge8030.expectedGrossFor8153(ts, lane, sel, cost)
        }
        if (!expected.isFinite() || !cost.isFinite()) return "ENTRY_CHASE_ECONOMICS_UNAVAILABLE_$lane"
        val setupCap = chaseCapPct8329(setup, tactic, exhausted)
        val missed8723 = try { ForwardReturnLabeler7731.laneStatFor7737("$MISSED_LANE_PREFIX_8329$lane") } catch (_: Throwable) { null }
        val cap = if (!setupCap.isFinite() || chaseCapBinds8723(missed8723?.n60 ?: 0, missed8723?.meanNet60Pct ?: Double.NaN, missed8723?.stderr60Pct ?: Double.NaN)) setupCap
            else UNCAPPED_8723.also { labelInc("ENTRY_CHASE_CAP_UNPROVEN_8723_$lane") }
        val why = consumed8329(move, cap, expected, cost, NetEdge8030.margin8391(lane, sizeSol, cost))
        synchronized(book) {
            book.checked.incrementAndGet()
            book.moves.addLast(move)
            while (book.moves.size > MOVES_RING_8329) book.moves.removeFirst()
        }
        if (why == null) return null
        book.consumed.incrementAndGet()
        consumedAt8329[ts.mint] = nowMs
        val label = "${CHASE_CONSUMED_8329}_$lane"
        // V5.0.8726 — the numbers this refusal was made on, kept for the failed-buy record (ExecutionAttemptLease).
        if (lastChase8726.size > 400) lastChase8726.clear()
        lastChase8726[ts.mint] = "why=$why move=${"%.2f".format(move)}% cap=${if (cap.isFinite() && cap < UNCAPPED_8723) "%.1f".format(cap) else "none"}% " +
            "expected=${"%.2f".format(expected)}% cost=${"%.2f".format(cost)}% setup=${setup?.name ?: sel ?: "-"} latencyMs=${nowMs - observedAt} source=$source"
        labelInc(label)
        labelInc("${label}_$why")
        try {
            ForensicLogger.lifecycle(CHASE_CONSUMED_8329,
                "mint=${ts.mint.take(10)} sym=${ts.symbol} lane=$lane why=$why move=${"%.2f".format(move)}% cap=${if (cap.isFinite() && cap < UNCAPPED_8723) "%.1f".format(cap) else "none"}% " +
                    "expected=${"%.2f".format(expected)}% cost=${"%.2f".format(cost)}% setup=${setup?.name ?: "-"} tactic=${tactic?.name ?: "-"} source=$source " +
                    "decisionPx=$decisionPx quotePx=$px latencyMs=${nowMs - observedAt} action=do_not_buy_wait_for_structural_reentry")
            // Graded against what happened next: a refused forward label in the lane's chase-miss pond.
            ForwardReturnLabeler7731.observe(ts, "$MISSED_LANE_PREFIX_8329$lane", false, label, nowMs)
        } catch (_: Throwable) {}
        return label
    }

    /** Executor.prepareJupiterBuy8090: the Jupiter quote the buy is built from, against the decision price. */
    fun jupiterQuoteRefusal8329(ts: TokenState, intent: ExecutionIntent, sizeSol: Double, quote: com.lifecyclebot.network.SwapQuote): String? {
        val px = quotePxUsd8329(quote.inAmount / 1_000_000_000.0, quote.outAmount, ts.tokenMap.decimals, WalletManager.freshSolPrice8163())
        return preBroadcastRefusal8329(ts, intent, sizeSol, px, quote.priceImpactPct, "JUPITER")?.let { "BUY_TERMINAL_$it" }
    }

    /** The unquoted Pump route needs a current mark for its comparison. */
    fun markRefusal8329(ts: TokenState, intent: ExecutionIntent, sizeSol: Double): String? {
        val now = System.currentTimeMillis()
        if (ts.lastPriceUpdate < intent.executableMarkTimestampMs6613 || ts.lastPriceUpdate > now)
            return "BUY_TERMINAL_ENTRY_CHASE_PUMP_MARK_STALE"
        return preBroadcastRefusal8329(ts, intent, sizeSol, ts.lastPrice, 0.0, "PUMPPORTAL", now)?.let { "BUY_TERMINAL_$it" }
    }

    /** V5.0.8521 — outAmount is an estimate; the transaction can legally land at its minimum output.
     * Keep that executable floor separate from the old chase calculation: buy impact/fees/slip are
     * already paid in this price, and must not be charged again as a complete modelled round trip.
     */
    fun jupiterFloorRefusal8521(ts: TokenState, intent: ExecutionIntent, sizeSol: Double,
                               quote: com.lifecyclebot.network.SwapQuote, builtSlippageBps: Int,
                               plannedBuyFixedSol: Double, nowMs: Long = System.currentTimeMillis(),
                               builtSimulatedOutRaw8521: Long? = null): String? {
        if (!com.lifecyclebot.engine.ExecutableEntryFloor8521.fresh8521(quote.receivedAtMs8521, nowMs))
            return "ENTRY_EXECUTABLE_QUOTE_STALE_8521"
        val minimum = if (quote.raw.has("otherAmountThreshold")) quote.raw.optString("otherAmountThreshold").toLongOrNull() else null
        if (quote.raw.has("otherAmountThreshold") && minimum == null && builtSlippageBps < 0)
            return "ENTRY_EXECUTABLE_MIN_OUT_INVALID_8521"
        val quoteBps = if (quote.raw.has("slippageBps")) quote.raw.optInt("slippageBps", -1) else null
        val floor = (if (builtSlippageBps >= 0) com.lifecyclebot.engine.ExecutableEntryFloor8521.dynamicBound8521(
            quote.outAmount, minimum, quoteBps, builtSlippageBps, builtSimulatedOutRaw8521)
            else com.lifecyclebot.engine.ExecutableEntryFloor8521.minimumOut8521(quote.outAmount, minimum, quoteBps, null))
            ?: return "ENTRY_EXECUTABLE_MIN_OUT_UNOBSERVED_8521"
        val px = quotePxUsd8329(quote.inAmount / 1_000_000_000.0, floor, ts.tokenMap.decimals, WalletManager.freshSolPrice8163())
        return executableNetRefusal8521(ts, intent, sizeSol, px, plannedBuyFixedSol,
            if (builtSlippageBps >= 0) "JUPITER_DYNAMIC_CONSERVATIVE_BOUND" else "JUPITER_QUOTE_MIN_OUT")
    }

    /** V5.0.8521 — recheck AFTER PumpPortal's build/simulation/envelope, immediately before signing.
     * A cached headline mark is not a sized curve fill. Missing/stale reserves abandon only this
     * unquoted route; the caller falls through to Jupiter's fresh quoted route for the same ticket.
     */
    fun pumpFinalRefusal8521(ts: TokenState, intent: ExecutionIntent, sizeSol: Double, slippagePct: Int,
                            plannedBuyFixedSol: Double, nowMs: Long = System.currentTimeMillis()): String? {
        val curve = CurveReserves7951.activeExitObservation(ts.mint, nowMs,
            com.lifecyclebot.engine.ExecutableEntryFloor8521.MAX_MARK_AGE_MS)
            ?: return "ENTRY_EXECUTABLE_CURVE_UNOBSERVED_8521"
        if (!com.lifecyclebot.engine.ExecutableEntryFloor8521.fresh8521(curve.observedAtMs, nowMs))
            return "ENTRY_EXECUTABLE_CURVE_STALE_8521"
        val solUsd = WalletManager.freshSolPrice8163()
        val spot = CurveReserves7951.priceSol7951(curve.virtualSol, curve.virtualTokens) * solUsd
        preBroadcastRefusal8329(ts, intent, sizeSol, spot, 0.0, "PUMP_FINAL_CURVE", nowMs)?.let { return it }
        val venue = PaperVenueCost7287.venueFeePct(ts.mint, curve.realSol * solUsd, ts.lastMcap)
        val floorPx = com.lifecyclebot.engine.ExecutableEntryFloor8521.curvePrice8521(
            curve.virtualSol, curve.virtualTokens, sizeSol, solUsd,
            venue + com.lifecyclebot.engine.CostTrim8151.PUMPPORTAL_FEE_PCT_PER_SIDE_8151, slippagePct.toDouble())
            ?: return "ENTRY_EXECUTABLE_CURVE_PRICE_UNAVAILABLE_8521"
        return executableNetRefusal8521(ts, intent, sizeSol, floorPx, plannedBuyFixedSol, "PUMP_CURVE_SIZED_MODEL")
    }

    internal fun executableNetRefusal8521(ts: TokenState, intent: ExecutionIntent, sizeSol: Double,
                                         floorPx: Double, plannedBuyFixedSol: Double, source: String): String? {
        val lane = CanonicalLaneIdentity6506.desk8392(intent.canonicalLane)
        val liquidity = LiquidityTruth8037.hydrate8037(ts)
        val cost = NetEdge8030.preTradeCostPct8329(lane, sizeSol, liquidity, ts.mint, ts.lastMcap)
        val modelFixed = NetEdge8030.fixedRoundTripForCurrentCost8521()
        val expected = expectedGrossAtExecution8653(intent.admissionProof?.expectedGrossPct) {
            NetEdge8030.expectedGrossFor8153(ts, lane, intent.admissionProof?.chosenSetup, cost)
        }
        val fixed = com.lifecyclebot.engine.CostMeter8151.fixedLegs8521()
        val roundTripFixed = fixed.roundTripFixedSol
        val sellFixed = fixed.sellFixedSol
        // The round-trip percentage contains the average fixed leg; remove that average from
        // the same model observation, then charge one coherent set of asymmetric fixed estimates.
        val sellVariable = (com.lifecyclebot.engine.CostTrim8151.sellLegPct8418(cost) -
            (modelFixed ?: roundTripFixed) / 2.0 / sizeSol * 100.0).coerceAtLeast(0.0)
        val net = com.lifecyclebot.engine.ExecutableEntryFloor8521.netWithFixedLegs8521(
            intent.executableMarkPriceUsd6613, floorPx, expected, sizeSol, sellVariable, roundTripFixed,
            sellFixed, com.lifecyclebot.engine.CostTrim8151.BUY_APP_FEE_PCT_8418, plannedBuyFixedSol)
        val refusal = if (net == null) "ENTRY_EXECUTABLE_NET_UNAVAILABLE_8521"
            else if (net <= 0.0) "ENTRY_EXECUTABLE_NET_NONPOSITIVE_8521" else null
        try {
            PipelineHealthCollector.labelInc(refusal ?: "ENTRY_EXECUTABLE_NET_OK_8521")
            ForensicLogger.lifecycle("ENTRY_EXECUTABLE_NET_8521", "mint=${ts.mint.take(10)} lane=$lane source=$source " +
                "decisionPx=${intent.executableMarkPriceUsd6613} floorPx=$floorPx expectedGross=$expected netAtFloor=$net " +
                "sizeSol=$sizeSol sellVariable=$sellVariable sellFixed=$sellFixed buyFixed=$plannedBuyFixedSol verdict=${refusal ?: "ALLOW"}")
        } catch (_: Throwable) {}
        return refusal
    }

    // ── 3. route-minimum promotion needs a reason ──

    const val PROMOTION_UNSUPPORTED_8329 = "ROUTE_MIN_PROMOTION_UNSUPPORTED_8329"
    const val SETUP_RECORD_MIN_N_8329 = 12
    const val KELLY_SHARE_OF_MIN_8329 = 0.25
    private val promoReasons8329 = ConcurrentHashMap<String, AtomicLong>()

    /**
     * Pure: the one candidate-level reason that supports promoting [requestedSol] (the Kelly stake) to [promotedSol] (the
     * route minimum), else null: the selected setup's own record positive on [SETUP_RECORD_MIN_N_8329]+ labels, an early
     * runner entry, or a stake of at least [KELLY_SHARE_OF_MIN_8329] of the minimum.
     */
    fun promotionReason8329(requestedSol: Double, promotedSol: Double, setupN: Double, setupMeanPct: Double, earlyRunner: Boolean): String? = when {
        setupN >= SETUP_RECORD_MIN_N_8329 && setupMeanPct.isFinite() && setupMeanPct > 0.0 -> "SETUP_RECORD_POSITIVE"
        earlyRunner -> "EARLY_RUNNER"
        requestedSol.isFinite() && promotedSol.isFinite() && promotedSol > 0.0 && requestedSol >= KELLY_SHARE_OF_MIN_8329 * promotedSol -> "KELLY_GE_25PCT_OF_MIN"
        else -> null
    }

    /** A LAUNCH_EARLY plan inside the launch window (the 8320 curve exit-safety rule still binds in PreTradeHardGate). */
    fun earlyRunner8329(ts: TokenState, nowMs: Long = System.currentTimeMillis()): Boolean {
        val plan = try { TradePlan7739.freshPlan7783(ts.mint, nowMs) } catch (_: Throwable) { null } ?: return false
        if (plan.setup != TradePlan7739.Setup.LAUNCH_EARLY) return false
        val age = try { CanonicalTokenBirthTime7440.resolvedAgeMinutes(ts, nowMs) } catch (_: Throwable) { null }
        return StrategyMove8153.fresh8153(age)
    }

    /**
     * MinPromotion8188.runtimeRefusal8188 / LiveRiskPolicy7807.decide: the refusal when no candidate-level reason supports
     * promoting [requestedSol] to [promotedSol] in [laneRaw], else null (the reason is counted).
     */
    fun promotionReasonRefusal8329(mint: String, laneRaw: String, requestedSol: Double, promotedSol: Double, nowMs: Long = System.currentTimeMillis()): String? {
        if (!(promotedSol > 0.0) || !(requestedSol < promotedSol)) return null
        val lane = CanonicalLaneIdentity6506.desk8392(laneRaw)
        val ts = if (mint.isNotBlank()) try { com.lifecyclebot.engine.BotService.status.tokens[mint] } catch (_: Throwable) { null } else null
        val rec = if (ts != null) try { com.lifecyclebot.engine.cortex.LanePlaybook7907.selectedRecord8153(ts, lane, null, nowMs) } catch (_: Throwable) { null } else null
        // V5.0.8343 — a grab-lane admit (RunnerGrabLane8343: forming runner on its own evidence) is the early runner at the route minimum.
        val early = ts != null && (earlyRunner8329(ts, nowMs) || com.lifecyclebot.engine.RunnerGrabLane8343.admitted8343(mint, lane, nowMs))
        // The 8316 exploration slot (owner directive) is itself an explicit reason: a lane with no record yet earns its first
        // labels through that one bounded order, so the promotion is not refused while the slot is free.
        val reason = promotionReason8329(requestedSol, promotedSol, rec?.getOrNull(0) ?: 0.0, rec?.getOrNull(1) ?: Double.NaN, early)
            ?: (if (MinPromotion8188.explorationSlotFree8316(lane)) "EXPLORATION_SLOT_8316" else null)
        promoReasons8329.computeIfAbsent(reason ?: "UNSUPPORTED") { AtomicLong(0) }.incrementAndGet()
        if (reason != null) {
            labelInc("ROUTE_MIN_PROMOTION_REASON_8329_$reason")
            return null
        }
        val r = "${PROMOTION_UNSUPPORTED_8329}_$lane"
        labelInc(r)
        try {
            ForensicLogger.lifecycle(PROMOTION_UNSUPPORTED_8329,
                "mint=${mint.take(10)} lane=$lane requested=${"%.5f".format(requestedSol)} promoted=${"%.5f".format(promotedSol)} " +
                    "setupN=${rec?.getOrNull(0)?.toInt() ?: 0} setupMean=${rec?.getOrNull(1)?.let { "%.1f".format(it) } ?: "-"}% earlyRunner=$early action=refuse_not_promote")
        } catch (_: Throwable) {}
        return r
    }

    /**
     * V5.0.8365 — LiveRiskPolicy7807.decide's promotion verdict on the Kelly stake. [refusal8329] is
     * [promotionReasonRefusal8329] for the same stake: a stake below [Kelly8365.ROUTE_SHARE_8365] of the route minimum with
     * no named candidate-level reason (setup record positive, early runner, exploration slot) is refused as
     * [Kelly8365.KELLY_TOO_SMALL_8365] — the minimum no longer replaces a Kelly stake far below it. A stake at or above that
     * share is promoted (the route minimum still binds); a stake above the minimum is never promoted (decide uses it).
     */
    fun kellyRefusal8365(stakeSol: Double, routeMinSol: Double, refusal8329: String?): String? {
        if (refusal8329 == null) return null
        if (Kelly8365.stakeNearRoute8365(stakeSol, routeMinSol)) return null
        labelInc(Kelly8365.KELLY_TOO_SMALL_8365)
        return Kelly8365.KELLY_TOO_SMALL_8365
    }

    // ── report ──

    fun statusLine8329(nowMs: Long = System.currentTimeMillis()): String {
        val chase = chase8329.entries.sortedBy { it.key }.joinToString(",") { (l, b) ->
            val med = synchronized(b) { CostLedger7962.median7962(b.moves.toList()) }
            "$l: ${b.checked.get()}/${b.consumed.get()}/${if (med.isFinite()) "%+.1f".format(med) else "-"}%"
        }.ifBlank { "-" }
        val promo = promoReasons8329.entries.sortedByDescending { it.value.get() }.joinToString(",") { "${it.key}=${it.value.get()}" }.ifBlank { "-" }
        val shut = shut8329.entries.sortedBy { it.key }.joinToString(",") { (l, s) ->
            "$l: since=${(nowMs - s.sinceMs) / 60_000L}m closes=${s.closes} mean=${"%+.1f".format(s.meanPct)}% binds=${realisedBinds8329[l]?.get() ?: 0L}"
        }.ifBlank { "-" }
        return "chase8329[$chase] promo8329[$promo] laneShut8329[$shut] " + LaneRealisedProbe8424.statusLine8424(nowMs)
    }

    internal fun resetForTests8329() { shut8329.clear(); reopenedAt8329.clear(); realisedBinds8329.clear(); chase8329.clear(); consumedAt8329.clear(); promoReasons8329.clear(); LaneRealisedProbe8424.resetForTests8424() }
}
