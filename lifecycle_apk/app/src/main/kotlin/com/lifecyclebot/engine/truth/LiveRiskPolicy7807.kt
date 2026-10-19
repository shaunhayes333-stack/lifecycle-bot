package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.7807 §LIVE_RISK_POLICY — one LIVE size authority, applied once, after
 * the last-mile floor.
 *
 * Root cause this closes. Every learned live shrink in Executor.liveBuy
 * (governor, discipline 4460, LaneExpectancyDamper upstream, style, provider
 * quorum, lane capital, common sense) runs BEFORE
 * LiveSizingProfile.lastMileEntryFloor, which lifts any order below
 * max(0.06 SOL, 12% of wallet) back up to that floor (capped at 18%). So in
 * live every shrink was erased and every entry was 12-18% of the wallet
 * whatever the lane, stop, drawdown or record said.
 *
 * This object is the replacement for that effect, not another multiplier in
 * the pre-floor stack. It is consulted twice from Executor.liveBuy:
 *   1. pre-ticket (before the execution lease) — per-lane slot cap, net-of-cost
 *      move check and the executable-minimum risk rule, so a candidate that can
 *      only be refused never burns an attempt;
 *   2. final shape (after the wallet/realistic/pending-proof sizing, before the
 *      executable floors) — the order becomes min(upstream, policy size).
 *
 * Size is from invalidation (Field Manual L243, L247):
 *   riskBudget = equity x lane.baseRiskFrac x learned multipliers
 *   size       = riskBudget / (stop + round-trip cost at that size)
 * capped by the lane's max fraction of equity and by pool impact. Stop and
 * first target come from TradePlan7739's plan when one exists, else from the
 * lane's FieldManual7715 mandate.
 *
 * Nothing here pauses a lane or the book. Learned evidence only shrinks (to a
 * floor, never zero) and a candidate is passed only for a per-candidate fact:
 * its lane's slots are full, cost eats the move, or even the executable
 * minimum would lose more than the hard per-trade cap at its stop.
 */
object LiveRiskPolicy7807 {
    /** V5.0.8098 — live positions each lane may hold at once (owner: back to 10). */
    const val LANE_SLOTS_8098 = 10

    // ── B8: one lane risk table ─────────────────────────────────────────────
    data class LaneRiskBudget(
        val lane: String,
        /** Concurrent LIVE positions this lane may hold (total book stays at 20). */
        val maxConcurrentLive: Int,
        /** Fraction of equity risked at the stop before learned multipliers. */
        val baseRiskFrac: Double,
        /** Largest single position as a fraction of equity. */
        val maxPositionEquityFrac: Double,
        /** Reference hold ceiling from the lane's existing exit constants; exits own it. */
        val maxHoldMinutes: Int,
        /** Rolling-24h net live loss (fraction of equity) at which the lane SHRINKS (never pauses). */
        val dailyLossCapEquityFrac: Double,
    )

    // V5.0.7807 — Field Manual L250: max loss per trade, per day and per
    // strategy. Hold references: QualityTraderAI 60, BlueChipTraderAI 480,
    // CashGenerationAI 45, DipHunterAI 6h, CyclicTradeEngine 90,
    // FluidLearningAI (MOONSHOT 240 / V3 60 / TREASURY 12), TradePlan7739
    // setup horizons (EXPRESS/SNIPER 30, MANIPULATED kept short).
    // V5.0.8098 — owner: back to 10 live positions per lane (was 1-3). The total live book (LIVE_SLOT_LIMIT_7728 = 20),
    // per-position caps, daily-loss caps and the hard loss at the stop are unchanged.
    val LaneRiskBudget7807: Map<String, LaneRiskBudget> = listOf(
        LaneRiskBudget("QUALITY", LANE_SLOTS_8098, 0.020, 0.25, 60, 0.04),
        LaneRiskBudget("BLUECHIP", LANE_SLOTS_8098, 0.020, 0.30, 480, 0.04),
        LaneRiskBudget("SHITCOIN", LANE_SLOTS_8098, 0.015, 0.20, 60, 0.03),
        LaneRiskBudget("EXPRESS", LANE_SLOTS_8098, 0.015, 0.20, 30, 0.03),
        LaneRiskBudget("CORE", LANE_SLOTS_8098, 0.020, 0.25, 60, 0.04),
        LaneRiskBudget("MOONSHOT", LANE_SLOTS_8098, 0.015, 0.20, 240, 0.03),
        LaneRiskBudget("PROJECT_SNIPER", LANE_SLOTS_8098, 0.015, 0.15, 30, 0.03),
        LaneRiskBudget("DIP_HUNTER", LANE_SLOTS_8098, 0.020, 0.25, 360, 0.04),
        LaneRiskBudget("MANIPULATED", LANE_SLOTS_8098, 0.010, 0.10, 15, 0.02),
        LaneRiskBudget("TREASURY", LANE_SLOTS_8098, 0.020, 0.25, 12, 0.04),
        LaneRiskBudget("CASHGEN", LANE_SLOTS_8098, 0.020, 0.25, 45, 0.04),
        LaneRiskBudget("CYCLIC", LANE_SLOTS_8098, 0.020, 0.25, 90, 0.04),
    ).associateBy { it.lane }

    private val DEFAULT_BUDGET_7807 = LaneRiskBudget("DEFAULT", LANE_SLOTS_8098, 0.015, 0.15, 60, 0.03)

    /** Total live book; LiveConcentrationDoctrine7697 enforces it. Lane caps never exceed [MAX_LANE_SLOTS_7807]. */
    const val TOTAL_LIVE_SLOTS_7807 = LiveConcentrationDoctrine7697.LIVE_SLOT_LIMIT_7728
    private const val MAX_LANE_SLOTS_7807 = 10

    fun canonicalLane(raw: String?): String {
        val c = try { CanonicalLaneIdentity6506.canonical(raw) } catch (_: Throwable) { raw?.trim()?.uppercase().orEmpty() }
        return c  // CanonicalLaneIdentity6506 already folds BLUE_CHIP / SNIPER / PRESALE_SNIPE
    }

    fun budgetFor(lane: String?): LaneRiskBudget {
        val b = LaneRiskBudget7807[canonicalLane(lane)] ?: DEFAULT_BUDGET_7807
        return if (b.maxConcurrentLive > MAX_LANE_SLOTS_7807) b.copy(maxConcurrentLive = MAX_LANE_SLOTS_7807) else b
    }

    // ── constants ───────────────────────────────────────────────────────────
    /** Loss at the stop of an executable-minimum order may not exceed this share of equity. */
    private const val HARD_PER_TRADE_RISK_CAP_FRAC_7807 = 0.04
    private const val SMALL_ACCOUNT_RISK_CAP_FRAC_7807 = 0.08
    private const val SMALL_ACCOUNT_FULL_AT_SOL_7807 = 1.0
    private const val SMALL_ACCOUNT_MAX_AT_SOL_7807 = 0.25
    private const val DRAWDOWN_FLOOR_MULT_7807 = 0.35
    private const val DRAWDOWN_FLOOR_AT_FRAC_7807 = 0.30
    /** Live closes a lane needs before loss evidence may shrink it below SHALLOW_LOSS_FLOOR. */
    const val MIN_LIVE_SAMPLE_DEEP_SHRINK_7807 = 10
    const val SHALLOW_LOSS_FLOOR_7807 = 0.70
    /** Clean live closes with positive net expectancy before size may grow above 1.0x. */
    private const val GROWTH_MIN_CLEAN_CLOSES_7807 = 10
    private const val MAX_GROWTH_MULT_7807 = 1.50
    /** Deepest learned shrink; never zero. */
    const val LEARNED_FLOOR_7807 = 0.35
    private const val TOTAL_FLOOR_7807 = 0.15
    private const val LIQ_MAX_ONE_WAY_IMPACT_PCT_7807 = 3.0
    /** Only when a Moonshot candidate has no plan stop (a plan's wider stop shrinks size by itself). */
    private const val MOONSHOT_NO_PLAN_MULT_7807 = 0.60
    private const val COST_PASS_RATIO_7807 = 2.0
    private const val COST_REDUCE_RATIO_7807 = 3.0
    private const val COST_REDUCE_MULT_7807 = 0.50
    /** A runner plan paying at least this R to its target counts as a high-EV runner. */
    private const val HIGH_EV_RUNNER_MIN_R_7807 = 3.0
    private const val LIVE_WR_FLOOR_PCT_7807 = 30.0

    // ── pure maths ──────────────────────────────────────────────────────────

    /** Pure (V5.0.8094): a proven positive-edge candidate (Kelly share > 0) is not shrunk again for drawdown. */
    fun drawdownWaivedForProvenEdge8094(edgeKellyFrac: Double, drawdownFrac: Double): Boolean =
        edgeKellyFrac.isFinite() && edgeKellyFrac > 0.0 && drawdownFrac.isFinite() && drawdownFrac > 0.0

    /** B5/B9: 1.0 at no drawdown, linear to 0.35 at 30%, flat 0.35 beyond. Never zero. */
    fun drawdownMultiplier(drawdownFrac: Double): Double {
        if (!drawdownFrac.isFinite() || drawdownFrac <= 0.0) return 1.0
        val t = (drawdownFrac / DRAWDOWN_FLOOR_AT_FRAC_7807).coerceIn(0.0, 1.0)
        return 1.0 - t * (1.0 - DRAWDOWN_FLOOR_MULT_7807)
    }

    /**
     * B5: expectancy-scaled lane multiplier from clean, position-bound, LIVE,
     * net-of-cost terminal closes (partials excluded upstream). Evidence weight
     * n/(n+10) (EvidenceMaturity7277's curve). Growth above 1.0 only with
     * >= 10 closes AND positive net SOL expectancy; under 10 closes a loss
     * record can shrink to 0.7 at most (B9).
     */
    fun expectancyMultiplier(cleanLiveCloses: Int, meanNetPct: Double, totalNetSol: Double): Double {
        if (cleanLiveCloses <= 0) return 1.0
        val mean = if (meanNetPct.isFinite()) meanNetPct else 0.0
        var target = (1.0 + mean / 50.0).coerceIn(LEARNED_FLOOR_7807, MAX_GROWTH_MULT_7807)
        if (totalNetSol <= 0.0 && target > 1.0) target = 1.0
        if (totalNetSol < 0.0 && target >= 1.0) target = 0.85
        val w = EvidenceMaturity7277.weight(cleanLiveCloses, MIN_LIVE_SAMPLE_DEEP_SHRINK_7807.toDouble())
        var m = 1.0 + (target - 1.0) * w
        val growthAllowed = cleanLiveCloses >= GROWTH_MIN_CLEAN_CLOSES_7807 && totalNetSol > 0.0
        if (!growthAllowed) m = m.coerceAtMost(1.0)
        if (cleanLiveCloses < MIN_LIVE_SAMPLE_DEEP_SHRINK_7807) m = m.coerceAtLeast(SHALLOW_LOSS_FLOOR_7807)
        return m.coerceIn(LEARNED_FLOOR_7807, MAX_GROWTH_MULT_7807)
    }

    // ── V5.0.8057 — compounding sizer ──
    //
    // Owner: "growing the cash balance exponentially!! its meant to be trying first 2x to 5x cash balance growth daily.
    // or the classic $50 to $1,000,000 thru the classic doubling". Every live order sat at the 0.0273 SOL route
    // minimum whatever the wallet did, so a winning streak added linearly. A coin whose classified setup is PROVEN
    // (LanePlaybook7907: 30+ graded labels, mean - SE > 0 net of cost) is sized at a quarter of the Kelly fraction of
    // its record — f = 0.25 x (mean - SE) / variance — times current equity. The daily 2x-5x target is a scoreboard
    // (dailyScoreLine8057), never a size input: chasing a target with size is how a small wallet is ruined.
    private const val KELLY_FRACTION_8057 = 0.25
    private const val KELLY_MAX_EQUITY_FRAC_8057 = 0.30

    /** Pure: the quarter-Kelly notional share of equity for a setup record ([meanPct], [variancePct2], [n]). */
    fun kellyFrac8057(meanPct: Double, variancePct2: Double, n: Double): Double {
        if (!meanPct.isFinite() || !variancePct2.isFinite() || variancePct2 <= 0.0 || !n.isFinite() || n < 2.0) return 0.0
        val se = kotlin.math.sqrt(variancePct2 / n)
        val edge = (meanPct - se) / 100.0
        if (edge <= 0.0) return 0.0
        return (KELLY_FRACTION_8057 * edge / (variancePct2 / 10_000.0)).coerceIn(0.0, KELLY_MAX_EQUITY_FRAC_8057)
    }

    /** Pure: the compounding size (SOL) for [frac] of [equitySol], inside the lane cap, depth cap and hard loss at the stop. */
    fun edgeKellySizeSol8057(equitySol: Double, frac: Double, laneCapSol: Double, liqCapSol: Double, stopPct: Double, costPct: Double): Double {
        if (!equitySol.isFinite() || equitySol <= 0.0 || !frac.isFinite() || frac <= 0.0) return 0.0
        val lossFrac = ((stopPct + costPct) / 100.0).coerceAtLeast(0.005)
        val hardLossCap = equitySol * hardPerTradeRiskCapFrac(equitySol) / lossFrac
        return minOf(equitySol * frac, laneCapSol, liqCapSol, hardLossCap).coerceAtLeast(0.0)
    }

    /** Pure (V5.0.8094): [frac] capped by the timing band's own quarter-Kelly share once that band has 12+ labels. */
    fun timingCappedFrac8094(frac: Double, timing: DoubleArray?): Double =
        if (timing == null || timing.size < 3 || timing[2] < com.lifecyclebot.engine.cortex.LanePlaybook7907.NEGATIVE_N_8094) frac
        else minOf(frac, kellyFrac8057(timing[0], timing[1], timing[2]))

    /** V5.0.8057 — the proven-edge quarter-Kelly share for [ts] in [lane], or 0. */
    fun edgeKellyFracFor8057(ts: com.lifecyclebot.data.TokenState, lane: String): Double = try {
        val l = canonicalLane(lane)
        val st = com.lifecyclebot.engine.cortex.LanePlaybook7907.provenSetupStat8057(ts, l)
        val setupFrac0 = if (st == null) 0.0 else kellyFrac8057(st[0], st[1], st[2])
        // V5.0.8094 — confidence at THIS point of the move: once the setup's record in the coin's current timing band has
        // 12+ labels, the size is the smaller of the two quarter-Kelly shares (timing can only lower it).
        val tim8094 = try { com.lifecyclebot.engine.cortex.LanePlaybook7907.timingStat8094(ts, l) } catch (_: Throwable) { null }
        val setupFrac = timingCappedFrac8094(setupFrac0, tim8094)
        // V5.0.8062 — or the coin's own proven cell (5.0.8059: PUMP_PORTAL_WS|SHITCOIN|MC_10K_100K|AGE_LT15M live book
        // n19 +124.7%, label book n33 +474%): the best measured edge the bot has gets the compounding size too.
        val c = try { ForwardReturnLabeler7731.cellStatFor(ts, l) } catch (_: Throwable) { null }
        val cellFrac = if (c != null && LiveEdgeGate7877.cellProven8060(c.n60, c.meanNet60Pct, c.stderr60Pct))
            kellyFrac8057(c.meanNet60Pct, c.stderr60Pct * c.stderr60Pct * c.n60, c.n60.toDouble()) else 0.0
        // V5.0.8063 — or the coin's proven kind book (venue x cap band, narrative).
        val kindFrac = try {
            ForwardReturnLabeler7731.kindStatsFor8063(ts, l)
                .filter { LiveEdgeGate7877.cellProven8060(it.n60, it.meanNet60Pct, it.stderr60Pct) }
                .maxOfOrNull { kellyFrac8057(it.meanNet60Pct, it.stderr60Pct * it.stderr60Pct * it.n60, it.n60.toDouble()) } ?: 0.0
        } catch (_: Throwable) { 0.0 }
        // V5.0.8188 — forward setup / cell / kind labels alone never raise size: the share is capped by an independent proof's
        // own Kelly stake (outlier-capped realised whole-position closes, or walk-forward out-of-sample); labels may only lower it.
        validatedKellyFrac8188(maxOf(setupFrac, cellFrac, kindFrac), laneRealisedProofFrac8188(l, System.currentTimeMillis()), laneWalkForwardKellyFrac8188(l))
    } catch (_: Throwable) { 0.0 }

    // ── V5.0.8188 — an edge-based size-up needs validated after-cost evidence (the Cortex's 8187 rule) ──
    //
    // 5.0.8186: SHITCOIN|LAUNCH_LADDER_PROVEN forward record n578 +103.1% sized up while the same setup's live realised
    // closes were n2 -47.9% and the lane's walk-forward read IN_SAMPLE_ONLY. A forward label is not an executable outcome
    // (entry chase, costs, exit policy), so it may shrink a size but never grow one by itself.

    /**
     * Pure: the quarter-Kelly stake of a VALIDATED walk-forward out-of-sample record (CortexWalkForward8144), its mean read
     * at most at RobustLabelRead8185's plausibility cap; 0 for any other verdict (IN_SAMPLE_ONLY, NO_EDGE, ...).
     */
    fun walkForwardKellyFrac8188(r: com.lifecyclebot.engine.cortex.CortexWalkForward8144.Result8144?): Double {
        if (r == null || !r.validated || r.oos.n < 2 || !r.oos.se.isFinite() || !r.oos.mean.isFinite()) return 0.0
        return kellyFrac8057(minOf(r.oos.mean, RobustLabelRead8185.CAP_LABEL_PCT_8185), r.oos.se * r.oos.se * r.oos.n, r.oos.n.toDouble())
    }

    /** The lane's walk-forward proof stake from the Cortex (0 on a fault: no proof). */
    private fun laneWalkForwardKellyFrac8188(lane: String): Double =
        try { walkForwardKellyFrac8188(com.lifecyclebot.engine.cortex.Cortex7885.walkForwardResult8188(lane)) } catch (_: Throwable) { 0.0 }

    /** Pure: the forward-label share [labelFrac] capped by the larger independent proof's own Kelly stake (0 without one). */
    fun validatedKellyFrac8188(labelFrac: Double, realisedProofFrac: Double, walkForwardProofFrac: Double): Double {
        if (!labelFrac.isFinite() || labelFrac <= 0.0) return 0.0
        val proof = maxOf(if (realisedProofFrac.isFinite()) realisedProofFrac else 0.0, if (walkForwardProofFrac.isFinite()) walkForwardProofFrac else 0.0)
        return minOf(labelFrac, proof).coerceAtLeast(0.0)
    }

    /**
     * Pure: the quarter-Kelly stake of realised whole-position closes ([returnsPct] net %, [netSol]), every close read
     * through RobustLabelRead8185's plausibility cap; 0 unless the record is mature (LiveSizingTruth8136.edgeMature8136).
     */
    fun realisedProofKellyFrac8188(returnsPct: List<Double>, netSol: List<Double>): Double {
        val capped = returnsPct.filter { it.isFinite() }.map { it.coerceIn(-100.0, RobustLabelRead8185.CAP_LABEL_PCT_8185) }
        val m = LiveSizingTruth8136.maturityOf8136(capped, netSol)
        if (!LiveSizingTruth8136.edgeMature8136(m)) return 0.0
        return kellyFrac8057(m.realisedMeanPct, m.realisedSePct * m.realisedSePct * m.realisedN, m.realisedN.toDouble())
    }

    private val realisedProof8188 = ConcurrentHashMap<String, Pair<Long, Double>>()

    /** The lane's clean live whole-position closes on the canonical bus as a Kelly stake (cached 20 s; 0 on a fault). */
    private fun laneRealisedProofFrac8188(lane: String, nowMs: Long): Double {
        val key = canonicalLane(lane)
        realisedProof8188[key]?.let { (at, v) -> if (nowMs - at in 0L..20_000L) return v }
        val v = try {
            val envs = CanonicalFinalizedTradeBus6464.terminalEnvelopes7863("live").filter { it.learningEligible && canonicalLane(it.lane) == key }
            realisedProofKellyFrac8188(envs.map { it.realizedReturnPct }, envs.map { it.realizedPnlSol })
        } catch (_: Throwable) { 0.0 }
        if (realisedProof8188.size > 64) realisedProof8188.clear()
        realisedProof8188[key] = nowMs to v
        return v
    }

    // ── V5.0.8057 — the daily doubling scoreboard (report only) ──
    @Volatile private var dayKey8057 = ""
    @Volatile private var dayStartEquity8057 = 0.0
    @Volatile private var dayLastEquity8057 = 0.0

    private fun noteEquity8057(equitySol: Double, nowMs: Long) {
        if (!equitySol.isFinite() || equitySol <= 0.0) return
        val day = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date(nowMs))
        if (dayKey8057.isEmpty()) try {
            com.lifecyclebot.engine.LearningPersistence.load("day_start_equity_8057")?.split(',')?.let { f ->
                if (f.size == 2 && f[0] == day) { dayKey8057 = day; dayStartEquity8057 = f[1].toDoubleOrNull() ?: 0.0 }
            }
        } catch (_: Throwable) {}
        if (day != dayKey8057 || dayStartEquity8057 <= 0.0) {
            dayKey8057 = day; dayStartEquity8057 = equitySol
            try { com.lifecyclebot.engine.LearningPersistence.save("day_start_equity_8057", "$day,$equitySol") } catch (_: Throwable) {}
        }
        dayLastEquity8057 = equitySol
    }

    /** V5.0.8057 — today's equity multiple against the 2x floor / 5x stretch (CapitalPreservationCreed6439). */
    fun dailyScoreLine8057(): String {
        val start = dayStartEquity8057; val now = dayLastEquity8057
        if (start <= 0.0 || now <= 0.0) return "day=- (no live equity read yet)"
        val x = now / start
        return "day=$dayKey8057 start=${"%.4f".format(start)} now=${"%.4f".format(now)} multiple=${"%.2f".format(x)}x " +
            "target=${CapitalPreservationCreed6439.DAILY_TARGET_MULTIPLE_7223}x stretch=${CapitalPreservationCreed6439.DAILY_STRETCH_MULTIPLE_7223}x " +
            "doublingsToday=${"%.2f".format(kotlin.math.ln(x) / kotlin.math.ln(2.0))} sizing=proven-edge quarter-Kelly of equity (target never sizes)"
    }

    /** B8: rolling-24h net loss against the lane cap: 1.0, then 0.5 at the cap, 0.3 at twice it. */
    fun dailyLossMultiplier(laneLossSol: Double, capSol: Double): Double {
        if (!laneLossSol.isFinite() || laneLossSol <= 0.0 || !capSol.isFinite() || capSol <= 0.0) return 1.0
        return when {
            laneLossSol >= capSol * 2.0 -> 0.30
            laneLossSol >= capSol -> 0.50
            else -> 1.0
        }
    }

    /** B9: loss-based shrink floor — 0.7 until the lane has 10 live closes, then the learned floor. */
    private fun lossShrinkFloor(liveCloses: Int): Double =
        if (liveCloses < MIN_LIVE_SAMPLE_DEEP_SHRINK_7807) SHALLOW_LOSS_FLOOR_7807 else LEARNED_FLOOR_7807

    data class Admission(
        val liveCloses: Int,
        val liveWrPct: Double,
        val partialProviderEvidence: Boolean,
        val oracleUnproven: Boolean,
        val highEvRunner: Boolean,
    )

    /**
     * B6: missing NON-essential evidence and an unproven / low-WR lane reduce
     * size and name the reason. (Missing essential evidence — identity,
     * exitability, trigger, invalidation, cost — is a WAIT/PASS decided by the
     * plan chokepoint, token-map and route gates, never a smaller buy:
     * Field Manual L123, L357.)
     */
    fun admissionMultiplier(a: Admission): Pair<Double, List<String>> {
        val reasons = ArrayList<String>(4)
        var m = 1.0
        when {
            a.liveCloses <= 0 -> { m *= 0.85; reasons += "NEW_SPECIALIST_NO_LIVE_SAMPLE_7807" }
            a.liveCloses < MIN_LIVE_SAMPLE_DEEP_SHRINK_7807 -> { m *= 0.90; reasons += "UNPROVEN_LANE_7807" }
        }
        if (a.liveCloses >= 5 && a.liveWrPct.isFinite() && a.liveWrPct < LIVE_WR_FLOOR_PCT_7807) {
            m *= 0.80; reasons += "LIVE_WR_BELOW_FLOOR_7807"
        }
        if (a.oracleUnproven) { m *= 0.90; reasons += "ORACLE_ADVISORY_UNPROVEN_7807" }
        if (a.partialProviderEvidence) { m *= 0.85; reasons += "PARTIAL_PROVIDER_EVIDENCE_7807" }
        val floor = if (a.highEvRunner) 0.70 else 0.50
        if (a.highEvRunner && m < floor) reasons += "HIGH_EV_RUNNER_ADMIT_REDUCED_7807"
        return m.coerceIn(floor, 1.0) to reasons
    }

    enum class CostVerdict { OK, REDUCE, PASS }

    /** B5/3: net-of-cost move check (Field Manual L215). */
    fun costVerdict(firstTargetPct: Double, roundTripCostPct: Double): CostVerdict {
        if (!firstTargetPct.isFinite() || !roundTripCostPct.isFinite() || roundTripCostPct <= 0.0) return CostVerdict.OK
        return when {
            firstTargetPct <= roundTripCostPct * COST_PASS_RATIO_7807 -> CostVerdict.PASS
            firstTargetPct <= roundTripCostPct * COST_REDUCE_RATIO_7807 -> CostVerdict.REDUCE
            else -> CostVerdict.OK
        }
    }

    /** Field Manual L243: notional = riskBudget / loss fraction (stop + cost). */
    fun sizeFromInvalidation(equitySol: Double, riskFrac: Double, mult: Double, stopPct: Double, costPct: Double): Double {
        if (!equitySol.isFinite() || equitySol <= 0.0) return 0.0
        val lossFrac = ((stopPct + costPct) / 100.0).coerceAtLeast(0.005)
        val budget = equitySol * riskFrac.coerceIn(0.0, 1.0) * mult.coerceAtLeast(0.0)
        return (budget / lossFrac).coerceIn(0.0, equitySol)
    }

    /** Executable-minimum rule: open at the minimum only if its loss at the stop fits the hard cap. */
    /** Pure. V5.0.7987 — share of equity one executable-minimum order may take: 30% at <= 0.25 SOL, 15% at >= 1 SOL. */
    fun smallAccountMinShare7987(equitySol: Double): Double {
        if (!equitySol.isFinite() || equitySol <= 0.0) return 0.0
        val t = ((equitySol - 0.25) / 0.75).coerceIn(0.0, 1.0)
        return 0.30 - 0.15 * t
    }

    fun executableMinRiskOk(execMinSol: Double, stopPct: Double, costPct: Double, equitySol: Double): Boolean {
        if (!execMinSol.isFinite() || execMinSol <= 0.0) return true
        if (!equitySol.isFinite() || equitySol <= 0.0) return false
        val lossAtStop = execMinSol * ((stopPct + costPct) / 100.0)
        return lossAtStop <= equitySol * hardPerTradeRiskCapFrac(equitySol) + 1e-12
    }

    /**
     * V5.0.7807 small-account rule: on a sub-1-SOL wallet the routable minimum
     * is already a large share of equity, so a flat 4% cap would refuse every
     * normal-stop setup and stop the bot learning (operator doctrine: it must
     * trade to learn). Cap is 4% at >= 1 SOL, rising linearly to 8% at
     * <= 0.25 SOL. Size still comes from the invalidation (Field Manual L243).
     */
    internal fun hardPerTradeRiskCapFracForTest7807(equitySol: Double): Double = hardPerTradeRiskCapFrac(equitySol)

    private fun hardPerTradeRiskCapFrac(equitySol: Double): Double {
        if (!equitySol.isFinite() || equitySol >= SMALL_ACCOUNT_FULL_AT_SOL_7807) return HARD_PER_TRADE_RISK_CAP_FRAC_7807
        val t = ((SMALL_ACCOUNT_FULL_AT_SOL_7807 - equitySol) /
            (SMALL_ACCOUNT_FULL_AT_SOL_7807 - SMALL_ACCOUNT_MAX_AT_SOL_7807)).coerceIn(0.0, 1.0)
        return HARD_PER_TRADE_RISK_CAP_FRAC_7807 + t * (SMALL_ACCOUNT_RISK_CAP_FRAC_7807 - HARD_PER_TRADE_RISK_CAP_FRAC_7807)
    }

    data class RouteAwareSpendCap7842(
        val maxSpendableSol: Double,
        val configuredWalletCapSol: Double,
        val routableMinSol: Double,
        val tradeableSol: Double,
        val routeFloorLifted: Boolean,
    )

    /**
     * V5.0.7842 — reconcile the normal wallet-share cap with the venue route
     * floor without globally increasing risk. On small wallets 18% can sit just
     * below the ~$5 routable minimum (0.0380 vs 0.0414 SOL in the 7840 runtime),
     * making "wallet can route" and "executor may spend" contradict each other.
     *
     * This only lifts the SPEND CEILING to the exact current route minimum when
     * SmartSizer's own capacity guard says the wallet can carry that minimum.
     * It grants no admission authority: LiveRiskPolicy.decide still has to prove
     * stop+cost loss at that minimum is inside the hard per-trade risk budget.
     */
    private const val ROUTE_FLOOR_HEADROOM_8079 = 1.03

    fun routeAwareSpendCap7842(
        walletSol: Double,
        solUsd: Double,
        maxLiveBuySol: Double,
        walletSharePct: Double,
    ): RouteAwareSpendCap7842 {
        val wallet = walletSol.takeIf { it.isFinite() && it > 0.0 } ?: 0.0
        val tradeable = (wallet - LiveSpendReserveAuthority7255.RESERVE_SOL).coerceAtLeast(0.0)
        val configuredWalletCap = wallet * walletSharePct.coerceIn(0.0, 1.0)
        val preflight = if (tradeable > 0.0 && solUsd.isFinite() && solUsd > 0.0) try {
            com.lifecyclebot.v3.sizing.SmartSizerV3.routableCapacityPreflight7224(tradeable, solUsd)
        } catch (_: Throwable) { null } else null
        val routableMin = preflight?.routableMinSol?.takeIf { it.isFinite() && it > 0.0 } ?: 0.0
        val routeFloorLifted = preflight != null && !preflight.wouldRefuse &&
            routableMin > configuredWalletCap + 1e-12 && routableMin <= tradeable + 1e-12
        // V5.0.8079 — the bridged floor carries the FDG seal headroom (FinalDecisionGate SEAL_FLOOR_HEADROOM_8079) so a
        // ticket sealed just above the SOL/USD-priced floor is not then refused as over the cap; never above tradeable.
        val allowedWalletCap = maxOf(configuredWalletCap, if (routeFloorLifted) minOf(routableMin * ROUTE_FLOOR_HEADROOM_8079, tradeable) else 0.0)
        val absoluteMax = maxLiveBuySol.takeIf { it.isFinite() && it > 0.0 } ?: Double.POSITIVE_INFINITY
        return RouteAwareSpendCap7842(
            maxSpendableSol = minOf(tradeable, absoluteMax, allowedWalletCap).coerceAtLeast(0.0),
            configuredWalletCapSol = configuredWalletCap,
            routableMinSol = routableMin,
            tradeableSol = tradeable,
            routeFloorLifted = routeFloorLifted,
        )
    }

    fun roundTripCostPct(sizeSol: Double, solUsd: Double, liquidityUsd: Double): Double = try {
        val usd = if (solUsd.isFinite() && solUsd > 0.0) sizeSol * solUsd else 0.0
        if (usd > 0.0) FieldManual7715.roundTripCostPct7766(sizeSol, usd, liquidityUsd)
        else FieldManual7715.BASE_ROUND_TRIP_COST_PCT_7715 + FieldManual7715.SLIPPAGE_ALLOWANCE_PCT_7715 +
            FieldManual7715.MAX_IMPACT_ROUND_TRIP_PCT_7715 * 0.25
    } catch (_: Throwable) { 6.0 }

    data class Inputs(
        val lane: String,
        val equitySol: Double,
        /** The size upstream sizing arrived at (wallet, realistic, pending-proof). 0 = unknown. */
        val upstreamSol: Double,
        val execMinSol: Double,
        /** Distance to invalidation, percent, positive. Null = no plan; the lane mandate is used. */
        val planStopPct: Double?,
        val planFirstTargetPct: Double?,
        val planTargetPct: Double?,
        val solUsd: Double,
        val liquidityUsd: Double,
        val liveCloses: Int,
        val liveMeanNetPct: Double,
        val liveTotalNetSol: Double,
        val liveWrPct: Double,
        val drawdownFrac: Double,
        val laneDailyLossSol: Double,
        /** Loss-based multipliers already decided by existing governors (discipline 4460, bucket EV). */
        val governorLossMult: Double,
        val partialProviderEvidence: Boolean,
        val oracleUnproven: Boolean,
        val laneOpenLive: Int,
        /** V5.0.8057 — quarter-Kelly share of equity the coin's PROVEN setup earns (0 = no proven edge). */
        val edgeKellyFrac8057: Double = 0.0,
        /**
         * V5.0.8094 — the external ceiling (pool depth, curve, candidate wallet share) of an APPROVED proven
         * compounding target. 0 = an ordinary request: the decision never exceeds [upstreamSol].
         */
        val compoundingCeilingSol8094: Double = 0.0,
        /**
         * V5.0.8136 — the lane's realised whole-position record; the drawdown waiver needs it mature
         * (LiveSizingTruth8136.drawdownWaiver8136). Null = not measured by the caller (runtimeInputs always measures).
         */
        val edgeMaturity8136: LiveSizingTruth8136.EdgeMaturity8136? = null,
        /**
         * V5.0.8153 — the selected setup's expected GROSS return per trade, % (its net record + the round trip its
         * labels were charged, CostLedger7962.labelChargedCostPct8153). NaN = unmeasured (the target test alone runs).
         */
        val setupExpectedGrossPct8153: Double = Double.NaN,
        /** V5.0.8153 — the record the proven Kelly share came from; null = only the share is known (it is not re-netted). */
        val edgeRecord8153: SizingNetEdge8153.EdgeRecord8153? = null,
        /**
         * V5.0.8153 — the realistic round trip (%) at a SOL size (NetEdge8030.costPct8030: the lane's measured cost or
         * the fee / fixed-leg / slippage / impact model, the higher). Null = the 7766 model only.
         */
        val costAtSizePct8153: ((Double) -> Double)? = null,
        /** V5.0.8153 — SOL this order can actually spend (wallet - reserve - other sealed reservations). NaN = unknown. */
        val tradeableSol8153: Double = Double.NaN,
        /** V5.0.8153 — principal already deployed in this lane's open live positions. NaN = unknown. */
        val laneDeployedSol8153: Double = Double.NaN,
        /** V5.0.8153 — the token's mark (USD) at this decision, for the pre-broadcast drift check. 0 = unknown. */
        val markPriceUsd8153: Double = 0.0,
        /** V5.0.8169 — one round trip's fixed legs (network fee + priority + tips, SOL; CostMeter8151). NaN = unknown. */
        val fixedRoundTripSol8169: Double = Double.NaN,
    )

    /** V5.0.8094 — how a proven candidate's size was reached (diagnostics; null for an unproven candidate). */
    data class Sizing8094(
        val rawKellySol: Double, val kellySol: Double, val riskBudgetSol: Double, val laneCapSol: Double,
        val liquidityCapSol: Double, val hardLossCapSol: Double, val drawdownMult: Double, val dampMult: Double,
        val bindingCap: String,
    )

    data class Decision(
        val open: Boolean,
        val sizeSol: Double,
        val reason: String,
        val labels: List<String>,
        val stopPct: Double,
        val costPct: Double,
        val totalMult: Double,
        val sizing8094: Sizing8094? = null,
        /** V5.0.8153 — the first target the cost test used (plan, else lane mandate); NaN on a refusal. */
        val firstTargetPct8153: Double = Double.NaN,
    )

    /**
     * V5.0.8153 — the round trip (%) of [sizeSol] for [i]: the realistic cost the caller supplied (measured lane cost /
     * component model with impact at THIS size — NetEdge8030.costPct8030, the model admission priced the candidate with),
     * else the 7766 model. V5.0.8232: the `max(7766, real)` of 8153 was a second estimator — it refused at size what the
     * same component model had admitted (NET_EV_NONPOSITIVE_AT_SIZE on 15 of 18 sized candidates, 8231 forensic).
     */
    fun costAtSizePct8153(i: Inputs, sizeSol: Double): Double {
        val model = roundTripCostPct(sizeSol, i.solUsd, i.liquidityUsd)
        val real = i.costAtSizePct8153?.let { f -> try { f(sizeSol) } catch (_: Throwable) { Double.NaN } } ?: Double.NaN
        return if (real.isFinite() && real > 0.0) real else model
    }

    fun isRunnerLane(lane: String?): Boolean = canonicalLane(lane) in setOf("MOONSHOT", "PROJECT_SNIPER")

    private fun planR(stopPct: Double?, targetPct: Double?): Double =
        if (stopPct != null && targetPct != null && stopPct > 0.0 && targetPct.isFinite()) targetPct / stopPct else 0.0

    /** Pure composite decision. */
    fun decide(i: Inputs): Decision {
        val b = budgetFor(i.lane)
        val labels = ArrayList<String>(8)
        val mandate = try { FieldManual7715.mandateFor(canonicalLane(i.lane)) } catch (_: Throwable) { null }
        val planless = i.planStopPct == null || i.planStopPct <= 0.0
        val stop = if (!planless) i.planStopPct!! else (mandate?.invalidationPct ?: 10.0)
        val firstTarget = i.planFirstTargetPct?.takeIf { it.isFinite() && it > 0.0 } ?: (mandate?.expectedGrossPct ?: 20.0)
        if (planless) labels += "NO_PLAN_STOP_LANE_MANDATE_USED_7807"

        if (i.laneOpenLive >= b.maxConcurrentLive) {
            return Decision(false, 0.0, "LANE_SLOT_CAP_7807", labels, stop, 0.0, 0.0)
        }
        // V5.0.8094 — the order is never below the executable minimum, so its cost is judged at least at that size.
        val probeSize = maxOf(if (i.upstreamSol > 0.0) i.upstreamSol else 0.0, i.execMinSol)
        // V5.0.8153 — the round trip at the size is the realistic one when the caller supplies it (never below 7766's).
        val cost0 = costAtSizePct8153(i, probeSize)
        val costMult = when (costVerdict(firstTarget, cost0)) {
            CostVerdict.PASS -> return Decision(false, 0.0, "COST_CONSUMES_MOVE_7807", labels, stop, cost0, 0.0)
            CostVerdict.REDUCE -> { labels += "COST_NEAR_MOVE_SIZE_HALVED_7807"; COST_REDUCE_MULT_7807 }
            CostVerdict.OK -> 1.0
        }
        // V5.0.8153 — a plan target clearing 2x the cost is not an expectation: the selected setup's expected gross
        // must clear the round trip at this size (+5% gross on a 7.5% trip is a loss whatever the target says).
        if (SizingNetEdge8153.netNonPositive8153(i.setupExpectedGrossPct8153, cost0)) {
            return Decision(false, 0.0, SizingNetEdge8153.NET_EV_REFUSAL_8153, labels, stop, cost0, 0.0)
        }

        val expMult = expectancyMultiplier(i.liveCloses, i.liveMeanNetPct, i.liveTotalNetSol)
        val dailyMult = dailyLossMultiplier(i.laneDailyLossSol, i.equitySol * b.dailyLossCapEquityFrac)
        if (dailyMult < 1.0) labels += "LANE_DAILY_LOSS_CAP_SHRINK_7807"
        val gov = if (i.governorLossMult.isFinite()) i.governorLossMult.coerceIn(0.0, 1.0) else 1.0
        val shrinkRaw = minOf(1.0, expMult) * dailyMult * gov
        val shrinkFloor = lossShrinkFloor(i.liveCloses)
        val shrink = shrinkRaw.coerceIn(shrinkFloor, 1.0)
        if (shrinkRaw < shrinkFloor) labels += "LOSS_SHRINK_FLOORED_SAMPLE_${i.liveCloses}_7807"
        if (expMult < 1.0) labels += "LIVE_EXPECTANCY_SHRINK_7807"
        val growth = maxOf(1.0, expMult)
        if (growth > 1.0) labels += "LIVE_EXPECTANCY_GROWTH_PROVEN_7807"
        val highEvRunner = isRunnerLane(i.lane) && planR(i.planStopPct, i.planTargetPct) >= HIGH_EV_RUNNER_MIN_R_7807
        val (admMult, admReasons) = admissionMultiplier(
            Admission(i.liveCloses, i.liveWrPct, i.partialProviderEvidence, i.oracleUnproven, highEvRunner),
        )
        labels += admReasons
        // V5.0.8094 — a PROVEN positive-edge candidate is sized as a quarter-Kelly share of CURRENT equity, which already
        // falls with the wallet and grows back with it; cutting it again for drawdown (to 0.35x) clipped 58 proven orders
        // to the route minimum at a 59% drawdown (4abd6b13a) and the balance could never compound back. Unproven candidates
        // keep the drawdown shrink; daily-loss, governor, lane, depth and the hard loss at the stop bind everyone.
        // V5.0.8136 — a label-proven Kelly share waives the drawdown shrink only on a MATURE realised record.
        val ddWaived8094 = LiveSizingTruth8136.drawdownWaiver8136(i.edgeKellyFrac8057, i.drawdownFrac, i.edgeMaturity8136)
        val ddMult = if (ddWaived8094) 1.0 else drawdownMultiplier(i.drawdownFrac)
        if (ddMult < 1.0) labels += "WALLET_DRAWDOWN_SHRINK_7807"
        if (ddWaived8094) labels += "DRAWDOWN_SHRINK_WAIVED_PROVEN_EDGE_8094"
        if (!ddWaived8094 && drawdownWaivedForProvenEdge8094(i.edgeKellyFrac8057, i.drawdownFrac)) labels += "DRAWDOWN_WAIVER_DENIED_IMMATURE_EDGE_8136"
        val moonMult = if (planless && canonicalLane(i.lane) == "MOONSHOT") MOONSHOT_NO_PLAN_MULT_7807 else 1.0
        if (moonMult < 1.0) labels += "MOONSHOT_NO_PLAN_SIZE_7807"
        val total = (shrink * growth * admMult * ddMult * moonMult * costMult).coerceIn(TOTAL_FLOOR_7807, MAX_GROWTH_MULT_7807)

        var size = sizeFromInvalidation(i.equitySol, b.baseRiskFrac, total, stop, cost0)
        val cost1 = if (size > 0.0) costAtSizePct8153(i, size) else cost0
        size = sizeFromInvalidation(i.equitySol, b.baseRiskFrac, total, stop, cost1)
        val laneCap = i.equitySol * b.maxPositionEquityFrac
        val liqCap = if (i.liquidityUsd.isFinite() && i.liquidityUsd > 0.0 && i.solUsd.isFinite() && i.solUsd > 0.0)
            (i.liquidityUsd * 0.5 * LIQ_MAX_ONE_WAY_IMPACT_PCT_7807 / 100.0) / i.solUsd else Double.POSITIVE_INFINITY
        if (size > laneCap) { size = laneCap; labels += "LANE_MAX_POSITION_CAP_7807" }
        if (size > liqCap) { size = liqCap; labels += "LIQUIDITY_IMPACT_CAP_7807" }
        // V5.0.8057 — compounding on proven edge: the bet is a quarter-Kelly share of CURRENT equity, so it grows as
        // the wallet grows and shrinks as it falls, still bounded by drawdown / daily loss / governor shrink, the lane's
        // position cap, the pool depth and the hard per-trade loss at the stop.
        val dampMult8094 = (ddMult * dailyMult * gov).coerceIn(0.0, 1.0)
        // V5.0.8153 — the Kelly share is re-netted at the round trip of the size it buys (labels are net of the cost
        // they were charged, not of the impact at a compounding size); a second pass uses the cost at the Kelly size.
        val netFrac0 = SizingNetEdge8153.netKellyFrac8153(i.edgeKellyFrac8057, i.edgeRecord8153, cost1)
        val kellySol0 = edgeKellySizeSol8057(i.equitySol, netFrac0 * dampMult8094, laneCap, liqCap, stop, cost1)
        val kellySol = if (i.edgeRecord8153 == null || kellySol0 <= 0.0) kellySol0 else {
            val costK = maxOf(cost1, costAtSizePct8153(i, kellySol0))
            edgeKellySizeSol8057(i.equitySol, minOf(netFrac0, SizingNetEdge8153.netKellyFrac8153(i.edgeKellyFrac8057, i.edgeRecord8153, costK)) * dampMult8094,
                laneCap, liqCap, stop, costK)
        }
        if (i.edgeKellyFrac8057 > 0.0 && netFrac0 < i.edgeKellyFrac8057 - 1e-12)
            labels += if (netFrac0 <= 0.0) "KELLY_EDGE_CONSUMED_BY_COST_AT_SIZE_8153" else "KELLY_NET_OF_COST_AT_SIZE_8153"
        val riskBudget8094 = size
        if (kellySol > size) { size = kellySol; labels += "PROVEN_EDGE_COMPOUNDING_SIZE_8057" }
        // V5.0.8094 — an ordinary request is still clamped to the upstream proposal; an APPROVED proven target may
        // exceed the proposal up to its own Kelly size, never above its external ceiling (depth, curve, wallet share).
        val upstreamCeil8094 = LiveSizeTarget8094.upstreamCeiling8094(i.upstreamSol, kellySol, i.compoundingCeilingSol8094)
        if (upstreamCeil8094 > 0.0 && size > upstreamCeil8094) size = upstreamCeil8094
        val sizing8094 = if (i.edgeKellyFrac8057 > 0.0) {
            val hardLoss8094 = i.equitySol * hardPerTradeRiskCapFrac(i.equitySol) / ((stop + cost1) / 100.0).coerceAtLeast(0.005)
            val raw8094 = i.equitySol * i.edgeKellyFrac8057
            Sizing8094(raw8094, kellySol, riskBudget8094, laneCap, liqCap, hardLoss8094, ddMult, dampMult8094,
                LiveSizeTarget8094.firstBindingCap8094(raw8094, dampMult8094, ddMult, laneCap, liqCap, hardLoss8094, i.compoundingCeilingSol8094))
        } else null

        // V5.0.8153 — the binding envelope. Tradeable SOL (wallet - reserve - other sealed reservations) and the lane's
        // slot share of the live book: idle cash in other lanes never lets one lane's principal grow past it.
        val tradeable8153 = if (i.tradeableSol8153.isFinite()) i.tradeableSol8153.coerceAtLeast(0.0) else Double.POSITIVE_INFINITY
        val laneRoom8153 = SizingNetEdge8153.laneEnvelopeRoomSol8153(i.equitySol, b.maxConcurrentLive, TOTAL_LIVE_SLOTS_7807, i.laneDeployedSol8153)
        if (size > tradeable8153) { size = tradeable8153; labels += "TRADEABLE_CASH_CAP_8153" }
        if (size > laneRoom8153) { size = laneRoom8153; labels += "LANE_PORTFOLIO_ENVELOPE_CAP_8153" }
        // V5.0.8153 — and the sealed amount is re-proven inside every hard cap at once (equity share, depth, hard loss
        // at the stop at the cost of THIS size). Never binds on a correctly shaped size; named if it ever does.
        val hardLossFinal8153 = if (size > 0.0) {
            val c = costAtSizePct8153(i, size)
            i.equitySol * hardPerTradeRiskCapFrac(i.equitySol) / ((stop + c) / 100.0).coerceAtLeast(0.005)
        } else Double.POSITIVE_INFINITY
        val envelope8153 = SizingNetEdge8153.envelopeCapSol8153(laneCap, liqCap, hardLossFinal8153, tradeable8153, laneRoom8153)
        if (size > envelope8153 + 1e-12) { size = envelope8153; labels += "ENVELOPE_CLAMP_8153" }

        if (i.execMinSol > 0.0 && size < i.execMinSol) {
            // V5.0.7840 — prove the venue minimum against the REAL hard-risk
            // envelope instead of rejecting merely because adaptive sizing
            // landed below it. This is the last-mile counterpart to the shared
            // resolver's capacity promotion: no safety/EV veto is bypassed.
            val minCost7840 = costAtSizePct8153(i, i.execMinSol)
            // V5.0.7987 — on a sub-1-SOL wallet the venue minimum is a large share of equity: a meme
            // lane's 20% position cap refused EVERY meme order below 0.137 SOL equity (5.0.7985: equity
            // 0.1215, cap 0.0243 < min 0.0273, 177 refusals, runner grabs included). The minimum may use
            // up to [smallAccountMinShare7987] of equity; the hard per-trade loss cap below still binds.
            val minCap7987 = maxOf(laneCap, i.equitySol * smallAccountMinShare7987(i.equitySol))
            val minFundable7840 = i.execMinSol <= minCap7987 + 1e-12 && i.execMinSol <= liqCap + 1e-12
            val minRiskSafe7840 = minFundable7840 &&
                executableMinRiskOk(i.execMinSol, stop, minCost7840, i.equitySol)
            if (minRiskSafe7840) {
                // V5.0.8153 — risk-safe is not enough: the minimum must be payable from tradeable SOL, fit the lane's
                // book envelope and still clear the cost (and the setup's expected return) at THAT size, or it is refused.
                val envelopeRefusal8153 = SizingNetEdge8153.promotionRefusal8153(
                    i.execMinSol, tradeable8153, laneRoom8153, firstTarget, minCost7840, i.setupExpectedGrossPct8153)
                if (envelopeRefusal8153 != null) {
                    return Decision(false, 0.0, envelopeRefusal8153, labels, stop, minCost7840, total, sizing8094)
                }
                // V5.0.8188 — re-evaluated AT the promoted notional: the ORIGINAL loss budget (risk size x stop + its round trip,
                // +10% at most), a measured positive edge after the round trip at the minimum, and no defensive drawdown.
                // V5.0.8236 — the 8234 upstream resolver used the routable wallet safe-share budget,
                // but this downstream policy silently defaulted to the sub-minimum risk size.
                // Reuse the SAME pure capacity budget; do not lift any other envelope or stop check.
                val doctrineBudget8236 = MinPromotion8188.doctrineBudgetSol8236(tradeable8153, i.solUsd)
                if (doctrineBudget8236.isFinite() && doctrineBudget8236 > size)
                    labels += "ROUTE_MIN_BUDGET_SHARED_WITH_UPSTREAM_8236"
                MinPromotion8188.refusal8188(size, i.execMinSol, stop, if (size > 0.0) costAtSizePct8153(i, size) else Double.NaN,
                    minCost7840, i.setupExpectedGrossPct8153, i.drawdownFrac, budgetSol = doctrineBudget8236)
                    ?.let { return Decision(false, 0.0, it, labels, stop, minCost7840, total, sizing8094) }
                labels += "RISK_SAFE_EXECUTABLE_MIN_PROMOTED_7840"
                fixedCostRefusal8169(i, i.execMinSol)?.let { return Decision(false, 0.0, it, labels, stop, minCost7840, total, sizing8094) }
                return Decision(true, i.execMinSol, "OPEN_RISK_SAFE_MIN_PROMOTED_7840", labels, stop, minCost7840, total, sizing8094, firstTarget)
            }
            return Decision(false, 0.0, "RISK_SIZE_BELOW_EXECUTABLE_MINIMUM_7835", labels, stop, minCost7840, total, sizing8094)
        }
        // V5.0.8153 — the final size's own round trip (a compounding size pays more impact than the probe).
        val costFinal8153 = costAtSizePct8153(i, size)
        if (SizingNetEdge8153.netNonPositive8153(i.setupExpectedGrossPct8153, costFinal8153)) {
            return Decision(false, 0.0, SizingNetEdge8153.NET_EV_REFUSAL_8153, labels, stop, costFinal8153, total, sizing8094)
        }
        fixedCostRefusal8169(i, size)?.let { return Decision(false, 0.0, it, labels, stop, costFinal8153, total, sizing8094) }
        return Decision(true, size, "OPEN_7807", labels, stop, cost1, total, sizing8094, firstTarget)
    }

    /**
     * V5.0.8169 — the explicit floor under every size-down (drawdown, daily loss, governor, expectancy): a size whose
     * fixed legs alone take more than CostLanding8169.MAX_FIXED_SHARE_PCT_8169 of the ticket cannot be positive-EV in
     * any lane, so it is REFUSED (FIXED_COST_DOMINATES_SIZE_8169), never traded thinner. Today's sizes sit at the route
     * minimum (~0.029 SOL, fixed ~1.8%), so this names the choice rather than binding; unknown fixed cost never refuses.
     */
    private fun fixedCostRefusal8169(i: Inputs, sizeSol: Double): String? =
        if (com.lifecyclebot.engine.CostLanding8169.fixedCostDominates8169(sizeSol, i.fixedRoundTripSol8169))
            com.lifecyclebot.engine.CostLanding8169.FIXED_COST_REFUSAL_8169 else null

    // ── runtime evidence (live only) ────────────────────────────────────────

    private const val DAY_MS_7807 = 24L * 60L * 60_000L
    private val laneLosses = ConcurrentHashMap<String, ConcurrentLinkedDeque<Pair<Long, Double>>>()
    private val subscribed = AtomicBoolean(false)
    private val bucketShrinkByMint = ConcurrentHashMap<String, Pair<Double, Long>>()
    private val opens = AtomicLong(0)
    private val passes = ConcurrentHashMap<String, AtomicLong>()

    /**
     * Subscribes to the one canonical terminal event (one per positionId, net of
     * fees, partials never published). In-memory: the 24h lane loss window
     * restarts empty after an app restart.
     */
    fun ensureSubscribed() {
        if (!subscribed.compareAndSet(false, true)) return
        try {
            CanonicalTradeFinalizedBus6450.subscribe { e ->
                if (e.mode.equals("live", ignoreCase = true)) onLiveFinalized(e.entryLane, e.netRealizedPnlSol, e.settledAtMs)
            }
        } catch (_: Throwable) { subscribed.set(false) }
    }

    private fun onLiveFinalized(lane: String, netPnlSol: Double, atMs: Long) {
        if (!netPnlSol.isFinite()) return
        val q = laneLosses.computeIfAbsent(canonicalLane(lane)) { ConcurrentLinkedDeque() }
        q.addLast(atMs to netPnlSol)
        while (q.size > 500) q.pollFirst()
    }

    /** Net live loss over the last 24h for [lane], SOL, >= 0. */
    fun laneDailyLossSol(lane: String, nowMs: Long = System.currentTimeMillis()): Double {
        val q = laneLosses[canonicalLane(lane)] ?: return 0.0
        while (true) { val h = q.peekFirst() ?: break; if (nowMs - h.first > DAY_MS_7807) q.pollFirst() else break }
        return (-q.sumOf { it.second }).coerceAtLeast(0.0)
    }


    /** B9: a refusal converted to a size note (e.g. PaperEvBucketGate6405), consumed at the final shape. */
    fun noteGovernorShrink(mint: String, mult: Double, label: String) {
        if (mint.isBlank() || !mult.isFinite()) return
        bucketShrinkByMint[mint] = mult.coerceIn(0.0, 1.0) to System.currentTimeMillis()
        if (bucketShrinkByMint.size > 500) bucketShrinkByMint.entries.removeIf { System.currentTimeMillis() - it.value.second > 10 * 60_000L }
        try { PipelineHealthCollector.labelInc(label) } catch (_: Throwable) {}
    }

    private fun governorShrinkFor(mint: String, nowMs: Long = System.currentTimeMillis()): Double {
        val e = bucketShrinkByMint[mint] ?: return 1.0
        return if (nowMs - e.second <= 2 * 60_000L) e.first else 1.0
    }

    /** True when a runner lane candidate carries a fresh plan paying >= 3R to target. */
    fun isHighEvRunner(mint: String, lane: String?): Boolean {
        if (!isRunnerLane(lane)) return false
        val p = try { TradePlan7739.freshPlan7783(mint) } catch (_: Throwable) { null } ?: return false
        return planR(-p.stopPnlPct, p.targetPnlPct) >= HIGH_EV_RUNNER_MIN_R_7807
    }

    data class LaneLive(val closes: Int, val meanNetPct: Double, val totalNetSol: Double, val wrPct: Double)

    private fun laneLive(lane: String): LaneLive = try {
        val key = canonicalLane(lane)
        val m = com.lifecyclebot.engine.StrategyTelemetry.computeCleanLiveTerminalLeaderboard()
            .firstOrNull { canonicalLane(it.strategy) == key }
        if (m == null) LaneLive(0, 0.0, 0.0, 0.0) else LaneLive(m.trades, m.meanPnlPct, m.totalSolPnl, m.winRatePct)
    } catch (_: Throwable) { LaneLive(0, 0.0, 0.0, 0.0) }

    fun laneOpenLive(lane: String): Int = try {
        val key = canonicalLane(lane)
        CanonicalPositionAuthority6441.openPositions().count {
            // V5.0.8034 — a moonbag tail is a lottery ticket, not a working position: it takes no lane slot.
            it.mode.equals("live", ignoreCase = true) && canonicalLane(it.lane) == key && !com.lifecyclebot.engine.TailBag8034.isTailMint8034(it.mint) &&
                // V5.0.8065 — a lifecycle hold past its 2x take is a free ride: no lane slot.
                !com.lifecyclebot.engine.LifecycleHold8065.freeRide8065(it.mint)
        }
    } catch (_: Throwable) { 0 }

    /** Cash plus cost basis still deployed in open live positions (realised equity; marks excluded). */
    /**
     * V5.0.7992 — is a protective live row real capital? The tracker's balance decides when it knows
     * the mint; a row the tracker has never seen counts only with a valid entry basis (the phantom
     * XOM / AC2x rows carried ENTRY_PRICE_INVALID and no wallet balance).
     */
    private fun walletHolds7992(mint: String, entryPriceUsd: Double): Boolean = try {
        val p = com.lifecyclebot.engine.HostWalletTokenTracker.getEntry(mint)
        if (p != null) p.uiAmount.isFinite() && p.uiAmount > 0.0 else entryPriceUsd.isFinite() && entryPriceUsd > 0.0
    } catch (_: Throwable) { true }

    internal fun liveEquitySol(walletSol: Double): Double {
        // V5.0.7992 — only rows the wallet actually holds count as deployed capital. 5.0.7991 after a
        // reset: XOM (STOCK_SPOT, 0.2385) and AC2x (DIP_HUNTER, 0.4293) — rows with no valid entry that
        // never existed in the wallet — inflated live equity, set a 0.56 SOL "peak" and a 68% drawdown.
        val deployed = try {
            CanonicalPositionAuthority6441.protectiveInventory7807("live")
                .filter { walletHolds7992(it.mint, it.entryPriceUsd) }
                .sumOf { (it.entryCostSol - it.soldCostBasisSol).coerceAtLeast(0.0) }
        } catch (_: Throwable) { 0.0 }
        val w = if (walletSol.isFinite()) walletSol.coerceAtLeast(0.0) else 0.0
        return w + deployed
    }

    /** Builds runtime inputs for [mint]/[lane]; plan read from TradePlan7739 when fresh. */
    fun runtimeInputs(
        mint: String,
        lane: String,
        walletSol: Double,
        upstreamSol: Double,
        execMinSol: Double,
        liquidityUsd: Double,
        governorLossMult: Double,
        partialProviderEvidence: Boolean,
        nowMs: Long = System.currentTimeMillis(),
        compoundingCeilingSol8094: Double = 0.0,
        /** V5.0.8094 — the proof the FDG computed from ITS token snapshot; null = read it here (non-FDG callers). */
        edgeKellyFracFromFdg8094: Double? = null,
    ): Inputs {
        ensureSubscribed()
        // The caller carries the canonical decision lane. Never substitute a
        // different candidate's active intent while resolving a new proposal.
        val effectiveLane7811 = canonicalLane(lane)

        val equity = liveEquitySol(walletSol)
        noteEquity8057(equity, nowMs)
        // V5.0.7948 — one drawdown authority (CapitalDrawdown7948): marked live equity of
        // the whole wallet vs its rolling-24h peak, the same number KillSwitch reports.
        // The free cash passed here is sizing input; a sealed in-flight buy is not a loss.
        val dd = CapitalDrawdown7948.observePct7948(
            CapitalDrawdown7948.liveMarkedEquitySol7948(
                try { com.lifecyclebot.engine.BotService.status.walletSol } catch (_: Throwable) { walletSol }),
            nowMs,
        ) / 100.0
        val ll = laneLive(effectiveLane7811)
        val plan = try { TradePlan7739.freshPlan7783(mint) } catch (_: Throwable) { null }
        val solUsd = try { com.lifecyclebot.engine.WalletManager.lastKnownSolPrice } catch (_: Throwable) { 0.0 }
        // V5.0.8153 — the economics at the size: the selected edge's record, its gross, the realistic round trip.
        val ts8153 = try { com.lifecyclebot.engine.BotService.status.tokens[mint] } catch (_: Throwable) { null }
        val rec8153 = ts8153?.let { edgeRecordFor8153(it, effectiveLane7811) }
        // V5.0.8232 §ONE_ECONOMIC_BASIS — the gross this candidate was ADMITTED on (AdmissionEdge8180.Proof8198: the
        // chosen setup's expected net plus the round trip it was costed at) is the gross the sizer nets at the size.
        // 8231 forensic: admission8136 ADMIT=174 and NET_EV_NONPOSITIVE_AT_SIZE_8153=15 of 18 sized — the sizer re-read
        // the lane's proven-record / classified expectation, a second estimator that contradicted the admission on the
        // same coin, same size. Without a proof for this generation the sizer's own read stands as before.
        val proofGross8232 = try {
            val version = com.lifecyclebot.engine.LaneExecutionCoordinator.candidateVersionFor(mint)
            AdmissionEdge8180.proof8198("LIVE", mint, effectiveLane7811, version)?.expectedGrossPct?.takeIf { it.isFinite() }
        } catch (_: Throwable) { null }
        if (proofGross8232 != null) try { com.lifecyclebot.engine.PipelineHealthCollector.labelInc("SIZING_GROSS_FROM_ADMISSION_PROOF_8232") } catch (_: Throwable) {}
        val gross8153 = proofGross8232 ?: ts8153?.let { setupExpectedGrossPct8153(it, effectiveLane7811, rec8153) } ?: Double.NaN
        val mcap8153 = ts8153?.lastMcap ?: 0.0
        val cost8153: (Double) -> Double = { s -> NetEdge8030.costPct8030(effectiveLane7811, s, liquidityUsd, mint, mcap8153) }
        return Inputs(
            lane = effectiveLane7811,
            equitySol = equity,
            upstreamSol = upstreamSol,
            execMinSol = execMinSol,
            planStopPct = plan?.stopPnlPct?.let { -it }?.takeIf { it > 0.0 },
            planFirstTargetPct = plan?.firstTargetPnlPct,
            planTargetPct = plan?.targetPnlPct,
            solUsd = solUsd,
            liquidityUsd = liquidityUsd,
            liveCloses = ll.closes,
            liveMeanNetPct = ll.meanNetPct,
            liveTotalNetSol = ll.totalNetSol,
            liveWrPct = ll.wrPct,
            drawdownFrac = dd,
            laneDailyLossSol = laneDailyLossSol(effectiveLane7811, nowMs),
            governorLossMult = (governorLossMult * governorShrinkFor(mint, nowMs)).coerceIn(0.0, 1.0),
            partialProviderEvidence = partialProviderEvidence,
            oracleUnproven = ll.closes < MIN_LIVE_SAMPLE_DEEP_SHRINK_7807 && plan == null,
            laneOpenLive = laneOpenLive(effectiveLane7811),
            // V5.0.8094 — one proof per decision: the FDG's own Kelly read is used as-is (no second token snapshot).
            edgeKellyFrac8057 = edgeKellyFracFromFdg8094?.takeIf { it.isFinite() && it >= 0.0 }
                ?: try { com.lifecyclebot.engine.BotService.status.tokens[mint]?.let { edgeKellyFracFor8057(it, effectiveLane7811) } ?: 0.0 } catch (_: Throwable) { 0.0 },
            compoundingCeilingSol8094 = compoundingCeilingSol8094,
            edgeMaturity8136 = laneMaturity8136(effectiveLane7811, nowMs),
            setupExpectedGrossPct8153 = gross8153,
            edgeRecord8153 = rec8153,
            costAtSizePct8153 = cost8153,
            // FDG passes wallet cash already net of other candidates' sealed reservations (WalletCapacitySeal7868.freeCashFor).
            tradeableSol8153 = if (walletSol.isFinite() && walletSol > 0.0)
                (walletSol - LiveSpendReserveAuthority7255.RESERVE_SOL).coerceAtLeast(0.0) else Double.NaN,
            laneDeployedSol8153 = laneDeployedSol8153(effectiveLane7811),
            markPriceUsd8153 = ts8153?.lastPrice?.takeIf { it.isFinite() && it > 0.0 } ?: 0.0,
            fixedRoundTripSol8169 = try { com.lifecyclebot.engine.CostMeter8151.roundTripFixedSol8151() } catch (_: Throwable) { Double.NaN },
        )
    }

    /**
     * V5.0.8153 — the record behind [edgeKellyFracFor8057]'s share (the same candidates: the selected setup — or its
     * timing band when that band caps it — the coin's proven cell and proven kinds), the one with the largest share,
     * carrying the round trip its labels were charged. Null when nothing is proven.
     */
    private fun edgeRecordFor8153(ts: com.lifecyclebot.data.TokenState, lane: String): SizingNetEdge8153.EdgeRecord8153? = try {
        val l = canonicalLane(lane)
        val labelCost = try { CostLedger7962.labelChargedCostPct8153(l) } catch (_: Throwable) { FieldManual7715.BASE_ROUND_TRIP_COST_PCT_7715 }
        val cands = ArrayList<SizingNetEdge8153.EdgeRecord8153>(4)
        com.lifecyclebot.engine.cortex.LanePlaybook7907.provenSetupStat8057(ts, l)?.let { st ->
            val tim = try { com.lifecyclebot.engine.cortex.LanePlaybook7907.timingStat8094(ts, l) } catch (_: Throwable) { null }
            val f0 = kellyFrac8057(st[0], st[1], st[2])
            val timingBinds = tim != null && tim.size >= 3 && timingCappedFrac8094(f0, tim) < f0 - 1e-12
            cands += if (timingBinds && tim != null) SizingNetEdge8153.EdgeRecord8153(tim[0], tim[1], tim[2], labelCost)
                else SizingNetEdge8153.EdgeRecord8153(st[0], st[1], st[2], labelCost)
        }
        val cell = try { ForwardReturnLabeler7731.cellStatFor(ts, l) } catch (_: Throwable) { null }
        if (cell != null && LiveEdgeGate7877.cellProven8060(cell.n60, cell.meanNet60Pct, cell.stderr60Pct))
            cands += SizingNetEdge8153.EdgeRecord8153(cell.meanNet60Pct, cell.stderr60Pct * cell.stderr60Pct * cell.n60, cell.n60.toDouble(), labelCost)
        val kinds = try { ForwardReturnLabeler7731.kindStatsFor8063(ts, l) } catch (_: Throwable) { emptyList<ForwardReturnLabeler7731.CellStat>() }
        for (k in kinds) {
            if (LiveEdgeGate7877.cellProven8060(k.n60, k.meanNet60Pct, k.stderr60Pct))
                cands += SizingNetEdge8153.EdgeRecord8153(k.meanNet60Pct, k.stderr60Pct * k.stderr60Pct * k.n60, k.n60.toDouble(), labelCost)
        }
        cands.maxByOrNull { kellyFrac8057(it.meanNetPct, it.variancePct2, it.n) }
            ?.takeIf { kellyFrac8057(it.meanNetPct, it.variancePct2, it.n) > 0.0 }
    } catch (_: Throwable) { null }

    /**
     * V5.0.8153 — the selected setup's expected GROSS return per trade (%): the proven record's net mean when one sized
     * the trade, else the classified setup's shrunk expectation, plus the round trip those labels were charged.
     * NaN when the setup has no record (the target test alone then judges cost).
     */
    fun setupExpectedGrossPct8153(ts: com.lifecyclebot.data.TokenState, lane: String, rec: SizingNetEdge8153.EdgeRecord8153?): Double = try {
        val l = canonicalLane(lane)
        val net = rec?.meanNetPct ?: com.lifecyclebot.engine.cortex.LanePlaybook7907.classifiedExpected7962(ts, l)
        if (net == null || !net.isFinite()) Double.NaN
        else net + (rec?.labelCostPct ?: CostLedger7962.labelChargedCostPct8153(l))
    } catch (_: Throwable) { Double.NaN }

    /** V5.0.8188 — the selected setup's expected gross (%) for a route-minimum promotion test outside decide (MinPromotion8188). */
    fun setupExpectedGrossForPromotion8188(ts: com.lifecyclebot.data.TokenState, lane: String): Double =
        try { setupExpectedGrossPct8153(ts, lane, edgeRecordFor8153(ts, lane)) } catch (_: Throwable) { Double.NaN }

    /** V5.0.8153 — principal deployed in [lane]'s open live positions (the rows that take a lane slot). NaN on a fault. */
    fun laneDeployedSol8153(lane: String): Double = try {
        val key = canonicalLane(lane)
        CanonicalPositionAuthority6441.openPositions().filter {
            it.mode.equals("live", ignoreCase = true) && canonicalLane(it.lane) == key && !com.lifecyclebot.engine.TailBag8034.isTailMint8034(it.mint) &&
                !com.lifecyclebot.engine.LifecycleHold8065.freeRide8065(it.mint)
        }.sumOf { (it.entryCostSol - it.soldCostBasisSol).coerceAtLeast(0.0) }
    } catch (_: Throwable) { Double.NaN }

    // ── V5.0.8136 — the lane's realised record (drawdown-waiver maturity) ──
    private val maturityCache8136 = ConcurrentHashMap<String, Pair<Long, LiveSizingTruth8136.EdgeMaturity8136>>()

    /**
     * The lane's clean, learning-eligible LIVE whole-position closes on the canonical bus (exact per-close spread);
     * when the bus holds fewer than the maturity bar (e.g. after a restart) the persistent leaderboard's record is
     * used with a conservative two-point standard error. Cached 20 s.
     */
    private fun laneMaturity8136(lane: String, nowMs: Long): LiveSizingTruth8136.EdgeMaturity8136 {
        val key = canonicalLane(lane)
        maturityCache8136[key]?.let { (cachedAt, cached) -> if (nowMs - cachedAt in 0L..20_000L) return cached }
        val bus = try {
            val envs = CanonicalFinalizedTradeBus6464.terminalEnvelopes7863("live")
                .filter { it.learningEligible && canonicalLane(it.lane) == key }
            LiveSizingTruth8136.maturityOf8136(envs.map { it.realizedReturnPct }, envs.map { it.realizedPnlSol })
        } catch (_: Throwable) { LiveSizingTruth8136.EdgeMaturity8136(0, 0.0, Double.POSITIVE_INFINITY, 0.0) }
        val m = if (bus.realisedN >= LiveSizingTruth8136.MIN_REALISED_N_8136) bus else try {
            val row = com.lifecyclebot.engine.StrategyTelemetry.computeCleanLiveTerminalLeaderboard()
                .firstOrNull { canonicalLane(it.strategy) == key }
            if (row == null || row.trades <= bus.realisedN) bus else LiveSizingTruth8136.EdgeMaturity8136(
                row.trades, row.meanPnlPct,
                LiveSizingTruth8136.twoPointSe8136(row.trades, row.winRatePct, row.avgWinPct, row.avgLossPct, row.meanPnlPct),
                row.totalSolPnl,
            )
        } catch (_: Throwable) { bus }
        if (maturityCache8136.size > 64) maturityCache8136.clear()
        maturityCache8136[key] = nowMs to m
        return m
    }

    /** Records the verdict (labels + one forensic line); returns the decision unchanged. */
    fun record(stage: String, mint: String, symbol: String, i: Inputs, d: Decision): Decision {
        try {
            if (d.open) {
                opens.incrementAndGet()
                // V5.0.8103 — its lane has room but the whole live book is full: ask for the poorest slot of any lane.
                if (i.edgeKellyFrac8057 > 0.0 && LiveConcentrationDoctrine7697.liveOpenCount() >= TOTAL_LIVE_SLOTS_7807)
                    OpportunityRotation8098.noteBookDemand8103(mint, i.edgeKellyFrac8057)
                if (stage == "FDG") noteSealedEconomics8153(mint, i, d)
                PipelineHealthCollector.labelInc("LIVE_RISK_POLICY_${stage}_OPEN_7807")
            } else {
                passes.computeIfAbsent(d.reason) { AtomicLong(0) }.incrementAndGet()
                // V5.0.8098 — a proven candidate refused only for its lane's slots asks for the lane's worst slot.
                if (d.reason == "LANE_SLOT_CAP_7807") OpportunityRotation8098.noteDemand8098(i.lane, mint, i.edgeKellyFrac8057)
                PipelineHealthCollector.labelInc("LIVE_RISK_POLICY_${stage}_PASS_7807")
                PipelineHealthCollector.labelInc(d.reason)
            }
            for (l in d.labels) PipelineHealthCollector.labelInc(l)
            val binding8136 = noteBinding8136(i, d)
            ForensicLogger.lifecycle("LIVE_RISK_BINDING_8136", "stage=$stage mint=${mint.take(10)} binding=$binding8136 size=${"%.4f".format(d.sizeSol)} kellyFrac=${"%.4f".format(i.edgeKellyFrac8057)} " +
                "matureEdge=${i.edgeMaturity8136?.let { LiveSizingTruth8136.edgeMature8136(it) } ?: "unmeasured"} realisedN=${i.edgeMaturity8136?.realisedN ?: -1}")
            ForensicLogger.lifecycle(
                "LIVE_RISK_POLICY_7807",
                "stage=$stage mint=${mint.take(10)} symbol=$symbol lane=${canonicalLane(i.lane)} open=${d.open} reason=${d.reason} " +
                    "size=${"%.4f".format(d.sizeSol)} upstream=${"%.4f".format(i.upstreamSol)} execMin=${"%.4f".format(i.execMinSol)} " +
                    "equity=${"%.4f".format(i.equitySol)} stop=${"%.1f".format(d.stopPct)}% cost=${"%.1f".format(d.costPct)}% " +
                    "mult=${"%.2f".format(d.totalMult)} dd=${"%.2f".format(i.drawdownFrac)} liveN=${i.liveCloses} " +
                    "laneOpen=${i.laneOpenLive}/${budgetFor(i.lane).maxConcurrentLive} labels=${d.labels.joinToString("|")}",
            )
        } catch (_: Throwable) {}
        return d
    }

    // ── V5.0.8153 — what an FDG-approved size was decided on (pre-broadcast recheck, resolver de-duplication) ──
    private val finalised8153 = ConcurrentHashMap<String, Pair<Long, Long>>()   // mint -> (decision lamports, atMs)
    const val FINALISED_TTL_MS_8153 = 5_000L

    private fun noteSealedEconomics8153(mint: String, i: Inputs, d: Decision) {
        if (!d.open || d.sizeSol <= 0.0) return
        val nowMs = System.currentTimeMillis()
        val lam = kotlin.math.round(d.sizeSol * 1_000_000_000.0).toLong()
        finalised8153[mint] = lam to nowMs
        if (finalised8153.size > 500) finalised8153.entries.removeIf { nowMs - it.value.second > 60_000L }
        val sizeUsd = if (i.solUsd.isFinite() && i.solUsd > 0.0) d.sizeSol * i.solUsd else 0.0
        val impactOneWay = try { FieldManual7715.impactRoundTripPct(sizeUsd, i.liquidityUsd) / 2.0 } catch (_: Throwable) { 0.0 }
        com.lifecyclebot.engine.SizingQuoteRecheck8153.noteDecision8153(mint, com.lifecyclebot.engine.SizingQuoteRecheck8153.SealedEconomics8153(
            sizeSol = d.sizeSol, expectedGrossPct = i.setupExpectedGrossPct8153, firstTargetPct = d.firstTargetPct8153,
            costPct = costAtSizePct8153(i, d.sizeSol), impactOneWayPct = if (sizeUsd > 0.0 && i.liquidityUsd > 0.0) impactOneWay else Double.NaN,
            priceUsd = i.markPriceUsd8153, atMs = nowMs, lane8198 = i.lane,
        ))
    }

    /**
     * Pure (V5.0.8153): is [requestedLamports] a size this policy just finalised for the mint? FDG hands the resolver
     * min(proposal, decision) (or the decision itself), so any request at or below the decision, within [ttlMs], is it.
     */
    fun finalisedBy8153(decisionLamports: Long, decidedAtMs: Long, requestedLamports: Long, nowMs: Long, ttlMs: Long = FINALISED_TTL_MS_8153): Boolean =
        decisionLamports > 0L && requestedLamports in 1L..(decisionLamports + 1L) && nowMs - decidedAtMs in 0L..ttlMs

    /**
     * V5.0.8153 — OrderSizeResolver6441: true when the live request on [mint] is this policy's FDG decision of the last
     * few seconds, so a second size-from-the-stop ceiling would act on an already-finalised risk decision.
     */
    fun policyFinalised8153(mint: String, requestedSol: Double, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (mint.isBlank() || !requestedSol.isFinite() || requestedSol <= 0.0) return false
        val e = finalised8153[mint] ?: return false
        return finalisedBy8153(e.first, e.second, kotlin.math.round(requestedSol * 1_000_000_000.0).toLong(), nowMs)
    }

    // V5.0.8136 — which constraint set each OPEN decision's size (LiveSizingTruth8136.bindingConstraintOf8136).
    private val bindings8136 = ConcurrentHashMap<String, AtomicLong>()

    private fun noteBinding8136(i: Inputs, d: Decision): String {
        val b = try { LiveSizingTruth8136.bindingConstraintOf8136(i, d) } catch (_: Throwable) { "UNKNOWN" }
        if (d.open) {
            bindings8136.computeIfAbsent(b) { AtomicLong(0) }.incrementAndGet()
            try { PipelineHealthCollector.labelInc("LIVE_SIZE_BINDING_8136_${b.replace(':', '_')}".take(70)) } catch (_: Throwable) {}
        }
        return b
    }

    fun statusLine(): String =
        "LiveRiskPolicy7807: opens=${opens.get()} passes=${passes.entries.joinToString(",") { "${it.key}=${it.value.get()}" }} " +
            "equityPeak=${"%.4f".format(CapitalDrawdown7948.currentPeak7948().peakSol)} " +
            "binding8136=[${bindings8136.entries.sortedByDescending { it.value.get() }.joinToString(",") { "${it.key}=${it.value.get()}" }.ifBlank { "none yet" }}] " +
            "rotation8098[${OpportunityRotation8098.statusLine8098()}] " +
            "economics8153[${com.lifecyclebot.engine.SizingQuoteRecheck8153.statusLine8153()}]"
}
