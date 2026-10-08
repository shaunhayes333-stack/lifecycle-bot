package com.lifecyclebot.engine.truth

import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.7739 §ONE_PLAN_PER_POSITION.
 *
 * The Field Manual's unit is the plan card (§9): setup, trigger, invalidation,
 * target, horizon. "Every setup needs five things: context, trigger,
 * invalidation, exit plan, and cost-adjusted payoff. If one is missing, it is
 * an observation, not a trade plan." (§4). The 5.0.7738 audit of the stack
 * against that doctrine found no plan anywhere:
 *
 *  - 11 of 12 lanes enter on a weighted score over one snapshot (liquidity,
 *    mcap, buy pressure, age, percent change); only DipHunter reads price
 *    structure, and it discards the low it found. Sniper and Moonshot score
 *    PRE_IGNITION highest: the blind launch purchase setup F forbids.
 *  - The V3 trunk buys under MOONSHOT/SHITCOIN/SNIPER/CORE without that lane's
 *    own evaluator; ~14 learned refusals become size cuts in live.
 *  - FieldManual7715 names the setup from the lane string; its invalidationPct
 *    is never read by an exit. Tactic rotation changes scores and sizes, not
 *    the trigger. ModeSpecificExits' per-type exits only log.
 *  - Every stop is a fixed percentage judged at the first price seen; vetoes
 *    hold losers in the -5..-20% band until the 180-240 minute flushes
 *    (losers held 162 min); winners trail 3-5 points under the peak (+45% at
 *    22 min). 44 live closes: 18% wins, average win ~+45%, loss ~-27%, PF 0.36.
 *
 * ENTRY. A LIVE entry on any FDG lane must carry a plan read from one-minute
 * bars of the token's own tape. Three setups from §4, thresholds scaled to the
 * token's size tier (a $68M token does not make 30% impulses):
 *   A/F PULLBACK_RECLAIM  impulse, a pullback keeping 25-65% of it, a turn
 *                         up off the pullback low, room under the high;
 *                         invalidation under the pullback low.
 *   B   BASE_BREAKOUT     a tight base, two closes above it (acceptance), not
 *                         extended; in CHOP one of them must retest the edge;
 *                         invalidation back inside the base.
 *   D   SWEEP_RECLAIM     a prior low swept and reclaimed, turning up;
 *                         invalidation under the sweep low.
 * Each must pay at least [MIN_R_7739]R to its target over its stop. No plan,
 * no live entry: the verdict is WAIT and the next cycle re-reads the tape.
 * Paper is never refused, so the table keeps filling.
 *
 * EXIT (ticked at 1 Hz from the open-position loop, before the generic floors):
 *   structural stop  P&L at or under the plan's invalidation;
 *   first target     half the position at the first target (the prior high
 *                    or range high), once, when it clears cost;
 *   structure trail  after the first target, a close under the lowest low of
 *                    the three completed bars before it;
 *   target           the rest at the measured target;
 *   thesis time      no half-way progress to the first target within the
 *                    setup's horizon;
 *   underwater time  any position under water after [UNDERWATER_MS_7739].
 * While a plan owns a position the generic 3-5 point give-back lock stands
 * down; the structure trail replaces it.
 *
 * Levels are kept as percentages from the decision price, so a position marked
 * on another feed keeps its plan without a cross-basis comparison.
 */
object TradePlan7739 {
    private const val WINDOW_MS_7739 = 30L * 60_000L
    private const val MIN_BARS_7739 = 5
    private const val MIN_R_7739 = 2.0
    private const val UNDERWATER_MS_7739 = 45L * 60_000L
    private const val PLAN_ENTRY_WINDOW_MS_7739 = 10L * 60_000L
    private const val PLAN_TTL_MS_7739 = 8L * 60L * 60_000L

    enum class Setup(val horizonMs: Long) {
        PULLBACK_RECLAIM(20L * 60_000L),
        BASE_BREAKOUT(15L * 60_000L),
        SWEEP_RECLAIM(30L * 60_000L),
        /** V5.0.7742 — a fresh launch too young for bars, admitted on its setup's -15% ladder. */
        LAUNCH_EARLY(30L * 60_000L),
    }

    // V5.0.7742 §A_LAUNCH_IS_NOT_A_CHART_YET.
    //
    // 5.0.7741 at 631 s: Trade plans admitted[none] waited=56, TOO_FEW_BARS=55.
    // A candidate's one-minute bars come from the scan cycle appending one
    // point per visit (BotService processTokenCycle, pair.candle), about one
    // point per token every 40 s in that run (5,595 scans over ~380 tokens), so
    // five bars take about five minutes of watching. Fresh launches reach the
    // gate at age 0-2 minutes: the plan could never admit one. The launch
    // record that does exist is FreshLaunchSelector7737's first-touch ladder
    // for the token's setup; it now measures +50% before -15% (the lane floors'
    // reach) beside +50% before -30%. A fresh launch whose bars show no setup
    // and no danger shape is planned LAUNCH_EARLY: stop -15%, half at +50%,
    // rest at +100%, out after 30 minutes without progress. It trades while
    // its ladder is thin (learn before tighten) or positive after cost, and
    // waits once the ladder has [FreshLaunchSelector7737]'s 20 results and is
    // negative.
    private const val LAUNCH_STOP_PCT_7742 = 15.0
    private const val LAUNCH_FIRST_TARGET_PCT_7742 = 50.0
    private const val LAUNCH_TARGET_PCT_7742 = 100.0
    private const val LAUNCH_COST_PCT_7742 = 4.0
    /** Bar reads that only say no setup has formed yet; anything else is a danger shape. */
    private val NO_SETUP_YET_7742 = setOf("TOO_FEW_BARS", "NO_IMPULSE", "NO_PULLBACK_YET", "NO_BASE", "NO_SWEEP", "NO_ACCEPTANCE")
    private val launchAdmits7742 = ConcurrentHashMap<String, AtomicLong>()

    /** Pure: may a fresh launch with this bar read be planned LAUNCH_EARLY? */
    fun barsPermitLaunch7742(barWhy: String): Boolean = barWhy in NO_SETUP_YET_7742

    /** Size tier: thresholds a token of this size can plausibly print. */
    data class Tier(val minImpulsePct: Double, val maxStopPct: Double, val maxBaseWidthPct: Double)

    fun tierFor(mcapUsd: Double): Tier = when {
        !mcapUsd.isFinite() || mcapUsd <= 0.0 || mcapUsd < 1_000_000.0 -> Tier(30.0, 25.0, 25.0)
        mcapUsd < 20_000_000.0 -> Tier(12.0, 12.0, 10.0)
        else -> Tier(5.0, 6.0, 5.0)
    }

    data class Bar(val startMs: Long, val open: Double, val high: Double, val low: Double, val close: Double)

    /** A plan read at decision time; every level is a percent move from the decision price. */
    data class Read(
        val setup: Setup?,
        val why: String,
        val stopPct: Double = 0.0,
        val firstTargetPct: Double = 0.0,
        val targetPct: Double = 0.0,
    ) {
        val r: Double get() = if (stopPct > 0.0) targetPct / stopPct else 0.0
    }

    class Plan(
        val setup: Setup,
        val stopPnlPct: Double,
        val firstTargetPnlPct: Double,
        val targetPnlPct: Double,
        val atMs: Long,
    ) {
        @Volatile var firstTargetTaken = false
        @Volatile var trailArmed = false
    }

    private val plans = ConcurrentHashMap<String, Plan>()
    private val admitted = ConcurrentHashMap<Setup, AtomicLong>()
    private val waited = AtomicLong(0)
    private val waitReasons = ConcurrentHashMap<String, AtomicLong>()
    private val exits = ConcurrentHashMap<String, AtomicLong>()

    // ── bars ──

    /** One-minute bars rebuilt from a history that may mix ticks, synthetic and fetched candles. */
    private fun barsFrom(ts: TokenState, nowMs: Long): List<Bar> {
        if (ts.candleTimeframeMinutes > 1) return emptyList()
        val pts = try { synchronized(ts) { ts.history.toList() } } catch (_: Throwable) { emptyList() }
        val byMinute = java.util.TreeMap<Long, DoubleArray>()   // minute -> [open, high, low, close, lastTs]
        for (c in pts.sortedBy { it.ts }) {
            if (c.ts <= 0L || nowMs - c.ts > WINDOW_MS_7739 || c.ts > nowMs + 60_000L) continue
            val p = c.priceUsd
            if (!p.isFinite() || p <= 0.0) continue
            val hi = if (c.highUsd.isFinite() && c.highUsd >= p) c.highUsd else p
            val lo = if (c.lowUsd.isFinite() && c.lowUsd > 0.0 && c.lowUsd <= p) c.lowUsd else p
            val op = if (c.openUsd.isFinite() && c.openUsd > 0.0) c.openUsd else p
            val m = c.ts / 60_000L
            val b = byMinute[m]
            if (b == null) byMinute[m] = doubleArrayOf(op, hi, lo, p, c.ts.toDouble())
            else {
                if (hi > b[1]) b[1] = hi
                if (lo < b[2]) b[2] = lo
                if (c.ts.toDouble() >= b[4]) { b[3] = p; b[4] = c.ts.toDouble() }
            }
        }
        return byMinute.entries.map { (m, b) -> Bar(m * 60_000L, b[0], b[1], b[2], b[3]) }
    }

    /**
     * V5.0.7819 — a candidate this plan reads with fewer bars than its
     * setups need (TOO_FEW_BARS: under MIN_BARS_7739 + 1, base/sweep need six)
     * asks HeliusSwapCandles7819 for its last 30 minutes of swaps, binned into
     * real one-minute bars. Asked before the paper return so a paper session
     * builds the same history live would. Asynchronous and budgeted there; the
     * bar requirement itself is unchanged (Field Manual L325: no fabricated
     * data, L404: route around an unhealthy provider).
     */
    private fun requestBarsIfShort7819(ts: TokenState, nowMs: Long) {
        try {
            if (barsFrom(ts, nowMs).size > MIN_BARS_7739) return
            HeliusSwapCandles7819.request7819(ts, nowMs)
        } catch (_: Throwable) {}
    }

    // ── setups (pure) ──

    private fun planned(setup: Setup, cur: Double, invalidation: Double, firstTarget: Double, target: Double, tier: Tier): Read {
        val stopPct = (1.0 - invalidation / cur) * 100.0
        if (stopPct <= 0.0 || stopPct > tier.maxStopPct) return Read(null, "${setup.name}_STOP_TOO_FAR")
        val targetPct = (target / cur - 1.0) * 100.0
        if (targetPct < MIN_R_7739 * stopPct) return Read(null, "${setup.name}_REWARD_UNDER_2R")
        val firstPct = ((firstTarget / cur - 1.0) * 100.0).coerceIn(0.0, targetPct)
        return Read(setup, setup.name, stopPct, firstPct, targetPct)
    }

    /** A/F: impulse, held pullback, reclaim, room under the high. */
    fun pullbackReclaim(bars: List<Bar>, tier: Tier): Read {
        val n = bars.size
        if (n < MIN_BARS_7739) return Read(null, "TOO_FEW_BARS")
        var iH = 0
        for (i in 0 until n - 1) if (bars[i].high > bars[iH].high) iH = i
        if (iH < 1 || iH > n - 3) return Read(null, "NO_PULLBACK_YET")
        val h = bars[iH].high
        val base = (0..iH).minOf { bars[it].low }
        if (base <= 0.0 || (h / base - 1.0) * 100.0 < tier.minImpulsePct) return Read(null, "NO_IMPULSE")
        var iPl = iH + 1
        for (i in iH + 1 until n) if (bars[i].low < bars[iPl].low) iPl = i
        val pl = bars[iPl].low
        val retrace = (h - pl) / (h - base)
        if (retrace < 0.25) return Read(null, "NO_PULLBACK_YET")
        if (retrace > 0.65) return Read(null, "PULLBACK_TOO_DEEP")
        if (iPl >= n - 1) return Read(null, "STILL_FALLING")
        val cur = bars[n - 1].close
        val minLift = (tier.minImpulsePct / 6.0).coerceAtLeast(1.0)
        if (cur < pl * (1.0 + minLift / 100.0)) return Read(null, "NO_RECLAIM")
        if (cur < bars[n - 2].close) return Read(null, "NOT_TURNING_UP")
        if (cur > h * (1.0 - minLift / 100.0)) return Read(null, "CHASING_THE_HIGH")
        return planned(Setup.PULLBACK_RECLAIM, cur, pl * 0.98, h, h + (h - base), tier)
    }

    /** B: a tight base, two closes above it, not extended; a retest is required in CHOP. */
    fun baseBreakout(bars: List<Bar>, tier: Tier, chop: Boolean): Read {
        val n = bars.size
        if (n < MIN_BARS_7739 + 1) return Read(null, "TOO_FEW_BARS")
        val baseBars = bars.subList(maxOf(0, n - 12), n - 2)
        if (baseBars.size < 4) return Read(null, "NO_BASE")
        val rh = baseBars.maxOf { it.high }
        val rl = baseBars.minOf { it.low }
        if (rl <= 0.0) return Read(null, "NO_BASE")
        if ((rh / rl - 1.0) * 100.0 > tier.maxBaseWidthPct) return Read(null, "BASE_TOO_WIDE")
        val a = bars[n - 2]; val b = bars[n - 1]
        if (a.close <= rh || b.close <= rh) return Read(null, "NO_ACCEPTANCE")
        val cur = b.close
        if (cur > rh * (1.0 + tier.maxBaseWidthPct / 200.0 + 0.04)) return Read(null, "EXTENDED_PAST_BASE")
        if (chop && minOf(a.low, b.low) > rh * 1.02) return Read(null, "NO_RETEST_IN_CHOP")
        // §4B invalidation: back inside the base and not reclaimed — a quarter of
        // the base height under its top. Target: three base heights over the top.
        val width = rh - rl
        return planned(Setup.BASE_BREAKOUT, cur, rh - 0.25 * width, rh + width, rh + 3.0 * width, tier)
    }

    /** D: a prior low swept and reclaimed, turning up. */
    fun sweepReclaim(bars: List<Bar>, tier: Tier): Read {
        val n = bars.size
        if (n < MIN_BARS_7739 + 1) return Read(null, "TOO_FEW_BARS")
        val prior = bars.subList(0, n - 3)
        val support = prior.minOf { it.low }
        val rangeHigh = prior.maxOf { it.high }
        val recent = bars.subList(n - 3, n)
        val sweepLow = recent.minOf { it.low }
        if (support <= 0.0 || sweepLow >= support * 0.99) return Read(null, "NO_SWEEP")
        val cur = bars[n - 1].close
        if (cur <= support * 1.01) return Read(null, "NO_RECLAIM")
        if (cur < bars[n - 2].close) return Read(null, "NOT_TURNING_UP")
        val mid = (support + rangeHigh) / 2.0
        return planned(Setup.SWEEP_RECLAIM, cur, sweepLow * 0.98, mid, rangeHigh, tier)
    }

    /** Pure: the best-paying setup the bars show, or the reason none is present. */
    fun analyze(bars: List<Bar>, tier: Tier, chop: Boolean, buyTx: Int, sellTx: Int): Read {
        if (sellTx >= 3 && sellTx > buyTx) return Read(null, "SELL_DOMINANT_TAPE")
        val reads = listOf(pullbackReclaim(bars, tier), baseBreakout(bars, tier, chop), sweepReclaim(bars, tier))
        val best = reads.filter { it.setup != null }.maxByOrNull { it.r }
        if (best != null) return best
        // Report the most advanced miss, so the wait reason says how close the tape came.
        return reads.firstOrNull { !it.why.endsWith("TOO_FEW_BARS") && !it.why.startsWith("NO_") } ?: reads.first()
    }

    /** Shared current-data read for the field manual and entry planner. No
     * stored plan is reused, and a read alone never authorizes execution. */
    fun readForEntry7837(ts: TokenState, nowMs: Long = System.currentTimeMillis()): Read {
        val lp = try { LaunchPhaseAuthority7401.snapshot(ts, nowMs) } catch (_: Throwable) { null }
        val chop = try { com.lifecyclebot.engine.RegimeDetector.currentRegime().name == "CHOP" } catch (_: Throwable) { false }
        return analyze(barsFrom(ts, nowMs), tierFor(ts.lastMcap), chop, lp?.buyTx60s ?: 0, lp?.sellTx60s ?: 0)
    }

    // ── entry ──

    /** Live-entry verdict: null admits (and records the plan). Paper is never refused. */
    fun liveBlockReason(ts: TokenState, lane: String, paper: Boolean, nowMs: Long = System.currentTimeMillis()): String? {
        requestBarsIfShort7819(ts, nowMs)
        if (paper) return null
        // V5.0.7878 — a runner lane is judged per cohort by the edge gate below,
        // not by its lane-wide record: MOONSHOT's 10-close live record held every
        // MOONSHOT token to paper (5.0.7876 PIVOT_7876_NEGATIVE_MOONSHOT=1924),
        // including the cohort with a 15% runner rate.
        val runner7878 = try { com.lifecyclebot.engine.RunnerExitProfile7277.isRunnerLane(lane) } catch (_: Throwable) { false }
        // V5.0.7876 — under a breached loss limit only evidence-positive lanes stay live.
        if (!runner7878) LivePivotAuthority7876.liveRefusal(lane, paper, nowMs)?.let { return it }
        // V5.0.7877 — always on: live only where a measured, net-of-cost prediction says it pays.
        LiveEdgeGate7877.liveRefusal(ts, lane, paper, nowMs)?.let { return it }
        val read = readForEntry7837(ts, nowMs)
        val setup = read.setup
        if (setup == null && barsPermitLaunch7742(read.why)) {
            val lr = try { FreshLaunchSelector7737.launchRead7742(ts, LAUNCH_COST_PCT_7742, nowMs) } catch (_: Throwable) { null }
            when (lr?.verdict) {
                FreshLaunchSelector7737.LaunchVerdict.LEARNING, FreshLaunchSelector7737.LaunchVerdict.PROVEN -> {
                    plans[ts.mint] = Plan(Setup.LAUNCH_EARLY, -LAUNCH_STOP_PCT_7742, LAUNCH_FIRST_TARGET_PCT_7742, LAUNCH_TARGET_PCT_7742, nowMs)
                    admitted.computeIfAbsent(Setup.LAUNCH_EARLY) { AtomicLong(0) }.incrementAndGet()
                    launchAdmits7742.computeIfAbsent(lr.verdict.name) { AtomicLong(0) }.incrementAndGet()
                    try {
                        PipelineHealthCollector.labelInc("PLAN_ADMITTED_7739_LAUNCH_EARLY_${lr.verdict.name}")
                        ForensicLogger.lifecycle(
                            "PLAN_ADMITTED_7739",
                            "mint=${ts.mint.take(10)} symbol=${ts.symbol} lane=$lane setup=LAUNCH_EARLY ladder=${lr.verdict.name} cell=${lr.key} ${lr.why} bars=${read.why} stop=-$LAUNCH_STOP_PCT_7742%",
                        )
                    } catch (_: Throwable) {}
                    return null
                }
                FreshLaunchSelector7737.LaunchVerdict.NEGATIVE, FreshLaunchSelector7737.LaunchVerdict.REFUSED -> {
                    val why = "LAUNCH_${lr.verdict.name}"
                    waited.incrementAndGet()
                    waitReasons.computeIfAbsent(why) { AtomicLong(0) }.incrementAndGet()
                    try { PipelineHealthCollector.labelInc("PLAN_WAIT_7739_$why") } catch (_: Throwable) {}
                    return waitOrOverrule7757(ts, why, "NO_PLAN_WAIT_7739:$why:${lr.why}", nowMs, runner7878)
                }
                else -> {}
            }
        }
        // V5.0.7871 — TOO_FEW_BARS is uncertainty on a young tape, not a danger
        // shape (5.0.7868: PLANWAIT_TOO_FEW_BARS n=106 net +11.7%). With every
        // other quality read clean it is planned LAUNCH_EARLY at reduced size;
        // its forward labels keep measuring it under its own key.
        if (setup == null && read.why == "TOO_FEW_BARS" && LaunchEntryShaping7871.admitTooFewBars(ts, nowMs)) {
            plans[ts.mint] = Plan(Setup.LAUNCH_EARLY, -LAUNCH_STOP_PCT_7742, LAUNCH_FIRST_TARGET_PCT_7742, LAUNCH_TARGET_PCT_7742, nowMs)
            admitted.computeIfAbsent(Setup.LAUNCH_EARLY) { AtomicLong(0) }.incrementAndGet()
            launchAdmits7742.computeIfAbsent("TOO_FEW_BARS_REDUCED") { AtomicLong(0) }.incrementAndGet()
            try { ForwardReturnLabeler7731.observe(ts, "PLANADMIT_TOO_FEW_BARS_7871", true, null, nowMs) } catch (_: Throwable) {}
            return null
        }
        if (setup == null) {
            waited.incrementAndGet()
            waitReasons.computeIfAbsent(read.why) { AtomicLong(0) }.incrementAndGet()
            try { PipelineHealthCollector.labelInc("PLAN_WAIT_7739_${read.why}") } catch (_: Throwable) {}
            return waitOrOverrule7757(ts, read.why, "NO_PLAN_WAIT_7739:${read.why}", nowMs, runner7878)
        }
        plans[ts.mint] = Plan(setup, -read.stopPct, read.firstTargetPct, read.targetPct, nowMs)
        if (plans.size > 2_000) plans.entries.removeIf { nowMs - it.value.atMs > PLAN_TTL_MS_7739 }
        admitted.computeIfAbsent(setup) { AtomicLong(0) }.incrementAndGet()
        try {
            PipelineHealthCollector.labelInc("PLAN_ADMITTED_7739_${setup.name}")
            ForensicLogger.lifecycle(
                "PLAN_ADMITTED_7739",
                "mint=${ts.mint.take(10)} symbol=${ts.symbol} lane=$lane setup=${setup.name} stop=-${"%.1f".format(read.stopPct)}% " +
                    "first=+${"%.1f".format(read.firstTargetPct)}% target=+${"%.1f".format(read.targetPct)}% R=${"%.1f".format(read.r)}",
            )
        } catch (_: Throwable) {}
        return null
    }

    /**
     * V5.0.7878 — the forward-label cohorts this token's current tape puts it in
     * (the keys waitOrOverrule7757 labels under). Side-effect free; read by
     * LiveEdgeGate7877 for runner lanes and by sizing.
     */
    fun runnerCohortKeys7878(ts: TokenState, nowMs: Long = System.currentTimeMillis()): List<String> {
        val read = readForEntry7837(ts, nowMs)
        if (read.setup != null) return emptyList()
        val keys = ArrayList<String>(2)
        if (barsPermitLaunch7742(read.why)) {
            val lr = try { FreshLaunchSelector7737.launchRead7742(ts, LAUNCH_COST_PCT_7742, nowMs) } catch (_: Throwable) { null }
            if (lr != null && (lr.verdict == FreshLaunchSelector7737.LaunchVerdict.NEGATIVE || lr.verdict == FreshLaunchSelector7737.LaunchVerdict.REFUSED)) {
                keys += "PLANWAIT_${"LAUNCH_${lr.verdict.name}".take(20)}"
            }
        }
        keys += "PLANWAIT_${read.why.take(20)}"
        return keys
    }

    /**
     * V5.0.7757 §THE_PLAN'S_REFUSALS_ANSWER_TO_THE_TAPE_TOO.
     *
     * 5.0.7756 at 31 min: the executor refused 100 buys on the plan (STANDARD
     * TOO_FEW_BARS=20, BASE_TOO_WIDE=17, NO_PULLBACK_YET=14 ...), and nothing
     * measured whether those waits were right. Every live plan wait is now a
     * forward label under its own key (PLANWAIT_<read>), priced at 60 minutes
     * like any other verdict. Field Manual §12: authority is earned by
     * measured, net-of-cost outcomes, the baseline's included. A read whose
     * refused tokens prove positive on the cell ladder (100+ labels, mean
     * above +1% by a standard error) stops refusing and the owner lane's buy
     * goes ahead; until then, and whenever its record is negative or thin, the
     * read stands. Paper never reaches here.
     */
    private fun waitOrOverrule7757(ts: TokenState, why: String, reason: String, nowMs: Long, runner: Boolean = false): String? {
        val key = "PLANWAIT_${why.take(20)}"
        try { ForwardReturnLabeler7731.observe(ts, key, false, reason, nowMs) } catch (_: Throwable) {}
        val stat = try { ForwardReturnLabeler7731.laneStatFor7737(key) } catch (_: Throwable) { null }
        // V5.0.7878 — a runner lane overrules a wait whose own refused cohort pays
        // net of cost (LiveEdgeGate7877 runner bar), not only at the hundred-label
        // POSITIVE tier: PLANWAIT_LAUNCH_REFUSED n=1043 net +3.1% runner-rate 15%.
        val runnerOverrule7878 = runner && LiveEdgeGate7877.runnerCohortAllows(stat)
        if (!runnerOverrule7878 && CellProofLadder7731.tierFor(stat) != CellProofLadder7731.Tier.POSITIVE) return reason
        overruled7757.computeIfAbsent(why) { AtomicLong(0) }.incrementAndGet()
        try {
            PipelineHealthCollector.labelInc("PLAN_WAIT_OVERRULED_BY_LABELS_7757_$why")
            ForensicLogger.lifecycle(
                "PLAN_WAIT_OVERRULED_BY_LABELS_7757",
                "mint=${ts.mint.take(10)} symbol=${ts.symbol} read=$why n60=${stat?.n60} net60=${"%+.1f".format(stat?.meanNet60Pct ?: 0.0)}% action=owner_lane_buy_proceeds",
            )
        } catch (_: Throwable) {}
        return null
    }

    private val overruled7757 = ConcurrentHashMap<String, AtomicLong>()

    /**
     * V5.0.7742 — the executor's chokepoint. PROJECT_SNIPER and other native
     * paths open live positions without FinalDecisionGate.evaluate (BotService:
     * "the sniper path never passes FDG"), so the plan and the council never
     * saw them: 5.0.7741 bought 11 times while the plan admitted none. Every
     * live buy answers to the plan here (the cheat-sheet baseline); a candidate
     * the gate already planned inside the recent window is not re-judged.
     * V5.0.7749 — the council does not vote here: the owner lane already
     * decided to buy, and re-asking a second evaluator overrode it (Field
     * Manual §12: "One selected strategy owns the live trade").
     */
    fun chokepointRefusal7742(ts: TokenState, lane: String, score: Int, nowMs: Long = System.currentTimeMillis()): String? {
        // V5.0.7748 — no pause here. Operator: "it has to trade to learn." The
        // daily loss floor (7746) and the lane losing-streak cooldown (7747) are
        // removed; losses teach the lane, they do not stop it.
        val p = plans[ts.mint]
        val planned = p != null && nowMs - p.atMs in 0L..CHOKEPOINT_RECENT_MS_7742
        if (!planned) liveBlockReason(ts, lane, false, nowMs)?.let { return chokeRefused7751(lane, it, p != null) }
        // V5.0.7753 — the sniper and other native paths never meet FinalDecisionGate's
        // cell-proof read, so a setup proven negative on its forward labels still
        // took the live slots. With two affordable slots, a slot spent there is a
        // slot not spent on a cell that pays. Paper and the labels keep learning it.
        CellProofLadder7731.liveBlockReason(ts, lane, false, nowMs)?.let { return chokeRefused7751(lane, it, false) }
        // V5.0.7749 — no council here. A buy that reaches the executor was produced
        // by its owner lane's own decision (the sniper's assessTarget, a lane's
        // shouldEnter), so the owner has voted. Re-asking it through
        // SpecialistBrainBridge7542 — separate inputs, liquidity read as $0 where
        // the lanes saw ~$3k, PROJECT_SNIPER nativeAllow=0 of 5894 — overruled the
        // owner's real decision: 5.0.7745 CHOKEPOINT_7742=57, buys 0. The council
        // still votes in FinalDecisionGate, where V3-trunk buys are judged.
        return null
    }

    private const val CHOKEPOINT_RECENT_MS_7742 = 2L * 60_000L

    // V5.0.7751 — 5.0.7749 showed CHOKEPOINT_7742=72 with no breakdown: the
    // plan's waitWhy merges gate and executor reads. Which lane, which read,
    // and whether the gate had planned the mint earlier, so the next snapshot
    // says what the executor refused instead of leaving it to inference.
    private val chokeWhy7751 = ConcurrentHashMap<String, AtomicLong>()

    private fun chokeRefused7751(lane: String, reason: String, hadEarlierPlan: Boolean): String {
        val why = reason.removePrefix("NO_PLAN_WAIT_7739:").substringBefore(':')
        val key = "${CanonicalLaneIdentity6506.canonical(lane)}|$why${if (hadEarlierPlan) "|planExpired" else ""}"
        chokeWhy7751.computeIfAbsent(key) { AtomicLong(0) }.incrementAndGet()
        try { PipelineHealthCollector.labelInc("CHOKEPOINT_7742_PLAN_$why") } catch (_: Throwable) {}
        return reason
    }

    /**
     * V5.0.7809 — the precise terminal reason for a chokepoint refusal. 5.0.7808
     * logged two live buy aborts as the bare LIVE_BUY_FAIL_CHOKEPOINT_7742, so
     * the failure tile could not say which read refused them. The refusal is a
     * real doctrine verdict (plan wait or a negative measured cell) and stays
     * binding; its own name is now the terminal reason, e.g.
     * CHOKEPOINT_7742_PLAN_TOO_FEW_BARS or CHOKEPOINT_7742_CELL_PROOF_NEGATIVE_7731.
     * Field Manual L123: every refusal names its cause.
     */
    fun chokepointTerminalReason7809(refusal: String): String {
        val r = refusal.trim()
        val why = if (r.startsWith("NO_PLAN_WAIT_7739:")) {
            "PLAN_" + r.removePrefix("NO_PLAN_WAIT_7739:").substringBefore(':')
        } else r.substringBefore(':').substringBefore(' ')
        val clean = why.uppercase().map { if (it.isLetterOrDigit() || it == '_') it else '_' }.joinToString("").take(48)
        return if (clean.isBlank() || clean == "PLAN_") "CHOKEPOINT_7742_UNSPECIFIED" else "CHOKEPOINT_7742_$clean"
    }

    /**
     * V5.0.7783 — the plan the gate admitted for this mint in the last two
     * minutes (the same window the executor chokepoint honours), or null.
     */
    fun freshPlan7783(mint: String, nowMs: Long = System.currentTimeMillis()): Plan? {
        val p = plans[mint] ?: return null
        return if (nowMs - p.atMs in 0L..CHOKEPOINT_RECENT_MS_7742) p else null
    }

    /** The plan recorded at the gate for this position, when it predates the entry by under ten minutes. */
    fun planFor(mint: String, entryTimeMs: Long): Plan? {
        val p = plans[mint] ?: return null
        if (entryTimeMs <= 0L) return null
        return if (entryTimeMs >= p.atMs && entryTimeMs - p.atMs <= PLAN_ENTRY_WINDOW_MS_7739) p else null
    }

    // ── exit (pure decision + the trail read) ──

    enum class ExitKind { FULL, HALF }
    data class Exit(val kind: ExitKind, val reason: String)

    /**
     * Pure: what the position owes now. [trailBroken] is true when the last bar
     * closed under the lowest low of the three completed bars before it.
     */
    /**
     * V5.0.7755 — [runner]: a runner lane (MOONSHOT, sniper, shitcoin ...) has
     * printed 200-500% live on its own trailing logic. On a runner the plan
     * banks half at the first target and hands the rest to the lane: no fixed
     * full target (it would sell a 5x at +100%) and no one-minute three-bar
     * trail (it would shake a runner out on its first pullback). Field Manual
     * §8: scale out, let the runner run on a trail that fits it.
     */
    fun exitFor(plan: Plan?, pnlPct: Double, peakPct: Double, holdMs: Long, trailBroken: Boolean, costPct: Double, runner: Boolean = false, underwaterMs: Long = UNDERWATER_MS_7739): Exit? {
        if (plan != null) {
            if (pnlPct <= plan.stopPnlPct) return Exit(ExitKind.FULL, "STRUCTURE_STOP_7739_${plan.setup.name}_${pnlPct.toInt()}PCT")
            if (!runner && pnlPct >= plan.targetPnlPct) return Exit(ExitKind.FULL, "PLAN_TARGET_7739_${plan.setup.name}_${pnlPct.toInt()}PCT")
            if (!plan.firstTargetTaken && pnlPct >= plan.firstTargetPnlPct && plan.firstTargetPnlPct >= costPct + 5.0) {
                return Exit(ExitKind.HALF, "PLAN_FIRST_TARGET_7739_${plan.setup.name}_${pnlPct.toInt()}PCT")
            }
            val armed = plan.trailArmed || peakPct >= plan.firstTargetPnlPct
            if (!runner && armed && trailBroken && pnlPct > 0.0) return Exit(ExitKind.FULL, "STRUCTURE_TRAIL_STOP_7739_${plan.setup.name}_${pnlPct.toInt()}PCT")
            val halfWay = plan.firstTargetPnlPct / 2.0
            if (holdMs >= plan.setup.horizonMs && peakPct < halfWay && pnlPct < halfWay) {
                return Exit(ExitKind.FULL, "THESIS_TIME_STOP_7739_${plan.setup.name}_${pnlPct.toInt()}PCT")
            }
        }
        if (holdMs >= underwaterMs && pnlPct < 0.0) return Exit(ExitKind.FULL, "UNDERWATER_TIME_STOP_7739_${pnlPct.toInt()}PCT")
        return null
    }

    /**
     * V5.0.7877 — the underwater horizon for a position in [lane]: 45 minutes, or
     * the full hour-plus when ExitRegret7752 has measured that underwater exits
     * were followed by recovery (5.0.7876: 100 UNDERWATER_TIME_STOP_7739 exits).
     */
    fun underwaterMsFor7877(lane: String?): Long =
        try { ExitRegret7752.underwaterHoldMs(lane ?: "", UNDERWATER_MS_7739) } catch (_: Throwable) { UNDERWATER_MS_7739 }

    /** True when the current bar closed under the lowest low of the three completed bars before it. */
    fun trailBroken(ts: TokenState, nowMs: Long): Boolean {
        val bars = barsFrom(ts, nowMs)
        if (bars.size < 4) return false
        val last = bars[bars.size - 1]
        val swingLow = bars.subList(bars.size - 4, bars.size - 1).minOf { it.low }
        return last.close < swingLow * 0.99
    }

    fun onExit(plan: Plan?, exit: Exit) {
        if (plan != null) {
            if (exit.kind == ExitKind.HALF) { plan.firstTargetTaken = true; plan.trailArmed = true }
        }
        val key = exit.reason.substringBefore("_7739") + "_7739"
        exits.computeIfAbsent(key) { AtomicLong(0) }.incrementAndGet()
        try { PipelineHealthCollector.labelInc(key) } catch (_: Throwable) {}
    }

    private fun waitProofLine7757(): String = waitReasons.keys.mapNotNull { why ->
        val st = try { ForwardReturnLabeler7731.laneStatFor7737("PLANWAIT_${why.take(20)}") } catch (_: Throwable) { null } ?: return@mapNotNull null
        "$why[n=${st.n60} net=${"%+.1f".format(st.meanNet60Pct)}% ${CellProofLadder7731.tierFor(st).name} overruled=${overruled7757[why]?.get() ?: 0}]"
    }.joinToString(",").ifBlank { "-" }

    fun statusLine(): String =
        "admitted[${admitted.entries.joinToString(",") { "${it.key.name}=${it.value.get()}" }.ifBlank { "none" }}] " +
            "launchLadder7742[${launchAdmits7742.entries.joinToString(",") { "${it.key}=${it.value.get()}" }.ifBlank { "none" }}] waited=${waited.get()} plans=${plans.size} " +
            "exits[${exits.entries.joinToString(",") { "${it.key}=${it.value.get()}" }.ifBlank { "none" }}] " +
            "waitWhy=${waitReasons.entries.sortedByDescending { it.value.get() }.take(6).joinToString(",") { "${it.key}=${it.value.get()}" }.ifBlank { "-" }} " +
            "executorRefused7751=${chokeWhy7751.entries.sortedByDescending { it.value.get() }.take(8).joinToString(",") { "${it.key}=${it.value.get()}" }.ifBlank { "-" }} " +
            "waitProof7757=${waitProofLine7757()} shaping7871=${LaunchEntryShaping7871.statusLine()} pivot7876=${LivePivotAuthority7876.statusLine()} edge7877=${LiveEdgeGate7877.statusLine()}"
}
