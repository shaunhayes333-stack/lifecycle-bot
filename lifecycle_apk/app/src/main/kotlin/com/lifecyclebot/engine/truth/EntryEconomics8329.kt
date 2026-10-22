package com.lifecyclebot.engine.truth

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.8329 §ENTRY_ECONOMICS_BIND_INSIDE_8114_MODE — the owner keeps Entry8114Mode8326 ON; the few candidate-specific,
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
 *     (ForwardReturnLabeler7731.decisionPx8094). move % net of the quote's own impact must sit under the tactic's cap
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
            .filter { Entry8114Mode8326.canon8326(it.lane) == lane }
            .map { Triple(it.atMs, it.realizedPnlSol, it.realizedReturnPct) }
    } catch (_: Throwable) { emptyList() }

    /** Entry8114Mode8326.decide8326: the realised-lane refusal for [lane] now (binds in mode), else null. */
    fun laneRealisedRefusal8329(lane: String, nowMs: Long = System.currentTimeMillis()): String? {
        val l = Entry8114Mode8326.canon8326(lane)
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
        realisedBinds8329.computeIfAbsent(l) { AtomicLong(0) }.incrementAndGet()
        labelInc("${LANE_REALISED_BINDS_8329}_$l")
        return "${LANE_REALISED_BINDS_8329}_$l"
    }

    // ── 2. pre-broadcast re-quote and chase check ──

    const val CHASE_CONSUMED_8329 = "ENTRY_CHASE_CONSUMED_8329"
    const val MISSED_LANE_PREFIX_8329 = "CHASEMISS_"
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
     * Pure: why the edge is consumed at the quote, else null. [movePct] = quote vs decision price (positive = dearer),
     * [capPct] from [chaseCapPct8329], [expectedGrossPct] the move the entry was admitted on, [costPct] the round trip at
     * this size. A cheaper or unchanged quote never refuses.
     */
    fun consumed8329(movePct: Double, capPct: Double, expectedGrossPct: Double, costPct: Double): String? {
        if (!movePct.isFinite() || movePct <= 0.0) return null
        if (!capPct.isFinite()) return "EXHAUSTED_RALLY"
        if (movePct > capPct) return "OVER_CAP"
        if (!expectedGrossPct.isFinite() || !costPct.isFinite()) return null
        return if (NetEdge8030.entryClears8030(expectedGrossPct - movePct, costPct)) null else "EDGE_CONSUMED"
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

    private class ChaseBook8329 { val checked = AtomicLong(0); val consumed = AtomicLong(0); val moves = ArrayDeque<Double>() }
    private val chase8329 = ConcurrentHashMap<String, ChaseBook8329>()
    private val consumedAt8329 = ConcurrentHashMap<String, Long>()

    /**
     * Executor, immediately before the live buy is sent: null = send; else the refusal (the buy is not sent). [quotePxUsd]
     * is the executable quote's implied price (NaN on a route without one: the current mark is used), [impactPct] its own
     * impact, [source] the route for the log.
     */
    fun preBroadcastRefusal8329(
        ts: TokenState, laneRaw: String, sizeSol: Double, quotePxUsd: Double, impactPct: Double, source: String,
        nowMs: Long = System.currentTimeMillis(),
    ): String? {
        val lane = Entry8114Mode8326.canon8326(laneRaw)
        val book = chase8329.computeIfAbsent(lane) { ChaseBook8329() }
        val d = try { ForwardReturnLabeler7731.decisionPx8094(ts.mint) } catch (_: Throwable) { null }
        if (d == null || nowMs - d.second !in 0L..DECISION_MAX_AGE_MS_8329) {
            labelInc("ENTRY_CHASE_NO_DECISION_8329_$lane")
            return null
        }
        val px = if (quotePxUsd.isFinite() && quotePxUsd > 0.0) quotePxUsd else ts.lastPrice
        val move = quoteMovePct8329(d.first, px, impactPct) ?: return null
        if (consumedAt8329.containsKey(ts.mint) &&
            (try { com.lifecyclebot.engine.chart.StructureTracker7962.hlReclaim7962(ts.mint, nowMs) } catch (_: Throwable) { false })) {
            consumedAt8329.remove(ts.mint)
            labelInc("ENTRY_CHASE_REARMED_HL_RECLAIM_8329_$lane")
        }
        val setup = try { TradePlan7739.freshPlan7783(ts.mint, nowMs)?.setup } catch (_: Throwable) { null }
        val sel = try { com.lifecyclebot.engine.cortex.LanePlaybook7907.selectedSetup8094(ts, lane, nowMs) } catch (_: Throwable) { null }
        val tactic = try { com.lifecyclebot.engine.learning.TacticSwitcher.seedTactic8049(lane, sel) } catch (_: Throwable) { null }
        val snap = try { com.lifecyclebot.engine.TokenMetricStageRouter.snapshot(ts) } catch (_: Throwable) { null }
        val exhausted = snap != null &&
            (try { LiveEdgeGate7877.chasing8065(snap.runupFromLocalLowPct, snap.currentVsPeak, ts.lastPriceChange5m) } catch (_: Throwable) { false })
        val liq = try { LiquidityTruth8037.hydrate8037(ts) } catch (_: Throwable) { ts.lastLiquidityUsd }
        val cost = try { NetEdge8030.preTradeCostPct8329(lane, sizeSol, liq, ts.mint, ts.lastMcap) } catch (_: Throwable) { Double.NaN }
        val gross = try { NetEdge8030.expectedGrossFor8153(ts, lane, sel, cost) } catch (_: Throwable) { Double.NaN }
        val expected = if (gross.isFinite()) gross else (try { FieldManual7715.mandateFor(lane).expectedGrossPct } catch (_: Throwable) { Double.NaN })
        val cap = chaseCapPct8329(setup, tactic, exhausted)
        val why = consumed8329(move, cap, expected, cost)
        synchronized(book) {
            book.checked.incrementAndGet()
            book.moves.addLast(move)
            while (book.moves.size > MOVES_RING_8329) book.moves.removeFirst()
        }
        if (why == null) return null
        book.consumed.incrementAndGet()
        consumedAt8329[ts.mint] = nowMs
        val label = "${CHASE_CONSUMED_8329}_$lane"
        labelInc(label)
        labelInc("${label}_$why")
        try {
            ForensicLogger.lifecycle(CHASE_CONSUMED_8329,
                "mint=${ts.mint.take(10)} sym=${ts.symbol} lane=$lane why=$why move=${"%.2f".format(move)}% cap=${if (cap.isFinite()) "%.1f".format(cap) else "none"}% " +
                    "expected=${"%.2f".format(expected)}% cost=${"%.2f".format(cost)}% setup=${setup?.name ?: "-"} tactic=${tactic?.name ?: "-"} source=$source " +
                    "decisionPx=${d.first} quotePx=$px latencyMs=${nowMs - d.second} action=do_not_buy_wait_for_structural_reentry")
            // Graded against what happened next: a refused forward label in the lane's chase-miss pond.
            ForwardReturnLabeler7731.observe(ts, "$MISSED_LANE_PREFIX_8329$lane", false, label, nowMs)
        } catch (_: Throwable) {}
        return label
    }

    /** Executor.prepareJupiterBuy8090: the Jupiter quote the buy is built from, against the decision price. */
    fun jupiterQuoteRefusal8329(ts: TokenState, lane: String?, sizeSol: Double, quote: com.lifecyclebot.network.SwapQuote?): String? {
        if (quote == null) return null
        return try {
            val solUsd = try { com.lifecyclebot.engine.WalletManager.lastKnownSolPrice } catch (_: Throwable) { 0.0 }
            val inSol = if (quote.inAmount > 0L) quote.inAmount / 1_000_000_000.0 else sizeSol
            val px = quotePxUsd8329(inSol, quote.outAmount, ts.tokenMap.decimals, solUsd)
            preBroadcastRefusal8329(ts, lane.orEmpty(), sizeSol, px, quote.priceImpactPct, "JUPITER")?.let { "BUY_TERMINAL_$it" }
        } catch (_: Throwable) { null }
    }

    /** Executor.liveBuy, before the PumpPortal send (no quote on that route): the current curve mark against the decision price. */
    fun markRefusal8329(ts: TokenState, lane: String?, sizeSol: Double): String? = try {
        preBroadcastRefusal8329(ts, lane.orEmpty(), sizeSol, Double.NaN, 0.0, "PUMPPORTAL")?.let { "BUY_TERMINAL_$it" }
    } catch (_: Throwable) { null }

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
        val lane = Entry8114Mode8326.canon8326(laneRaw)
        val ts = if (mint.isNotBlank()) try { com.lifecyclebot.engine.BotService.status.tokens[mint] } catch (_: Throwable) { null } else null
        val rec = if (ts != null) try { com.lifecyclebot.engine.cortex.LanePlaybook7907.selectedRecord8153(ts, lane, null, nowMs) } catch (_: Throwable) { null } else null
        val early = ts != null && earlyRunner8329(ts, nowMs)
        val reason = promotionReason8329(requestedSol, promotedSol, rec?.getOrNull(0) ?: 0.0, rec?.getOrNull(1) ?: Double.NaN, early)
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
        return "chase8329[$chase] promo8329[$promo] laneShut8329[$shut]"
    }

    internal fun resetForTests8329() { shut8329.clear(); reopenedAt8329.clear(); realisedBinds8329.clear(); chase8329.clear(); consumedAt8329.clear(); promoReasons8329.clear() }
}
