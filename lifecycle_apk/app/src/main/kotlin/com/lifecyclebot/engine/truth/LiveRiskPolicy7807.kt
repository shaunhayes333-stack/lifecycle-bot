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
    val LaneRiskBudget7807: Map<String, LaneRiskBudget> = listOf(
        LaneRiskBudget("QUALITY", 3, 0.020, 0.25, 60, 0.04),
        LaneRiskBudget("BLUECHIP", 3, 0.020, 0.30, 480, 0.04),
        LaneRiskBudget("SHITCOIN", 3, 0.015, 0.20, 60, 0.03),
        LaneRiskBudget("EXPRESS", 2, 0.015, 0.20, 30, 0.03),
        LaneRiskBudget("CORE", 3, 0.020, 0.25, 60, 0.04),
        LaneRiskBudget("MOONSHOT", 3, 0.015, 0.20, 240, 0.03),
        LaneRiskBudget("PROJECT_SNIPER", 2, 0.015, 0.15, 30, 0.03),
        LaneRiskBudget("DIP_HUNTER", 2, 0.020, 0.25, 360, 0.04),
        LaneRiskBudget("MANIPULATED", 1, 0.010, 0.10, 15, 0.02),
        LaneRiskBudget("TREASURY", 2, 0.020, 0.25, 12, 0.04),
        LaneRiskBudget("CASHGEN", 2, 0.020, 0.25, 45, 0.04),
        LaneRiskBudget("CYCLIC", 2, 0.020, 0.25, 90, 0.04),
    ).associateBy { it.lane }

    private val DEFAULT_BUDGET_7807 = LaneRiskBudget("DEFAULT", 2, 0.015, 0.15, 60, 0.03)

    /** Total live book; LiveConcentrationDoctrine7697 enforces it. Lane caps never exceed 6. */
    const val TOTAL_LIVE_SLOTS_7807 = LiveConcentrationDoctrine7697.LIVE_SLOT_LIMIT_7728
    private const val MAX_LANE_SLOTS_7807 = 6

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
        val allowedWalletCap = maxOf(configuredWalletCap, if (routeFloorLifted) routableMin else 0.0)
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
    )

    data class Decision(
        val open: Boolean,
        val sizeSol: Double,
        val reason: String,
        val labels: List<String>,
        val stopPct: Double,
        val costPct: Double,
        val totalMult: Double,
    )

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
        val probeSize = if (i.upstreamSol > 0.0) i.upstreamSol else i.execMinSol
        val cost0 = roundTripCostPct(probeSize, i.solUsd, i.liquidityUsd)
        val costMult = when (costVerdict(firstTarget, cost0)) {
            CostVerdict.PASS -> return Decision(false, 0.0, "COST_CONSUMES_MOVE_7807", labels, stop, cost0, 0.0)
            CostVerdict.REDUCE -> { labels += "COST_NEAR_MOVE_SIZE_HALVED_7807"; COST_REDUCE_MULT_7807 }
            CostVerdict.OK -> 1.0
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
        val ddMult = drawdownMultiplier(i.drawdownFrac)
        if (ddMult < 1.0) labels += "WALLET_DRAWDOWN_SHRINK_7807"
        val moonMult = if (planless && canonicalLane(i.lane) == "MOONSHOT") MOONSHOT_NO_PLAN_MULT_7807 else 1.0
        if (moonMult < 1.0) labels += "MOONSHOT_NO_PLAN_SIZE_7807"
        val total = (shrink * growth * admMult * ddMult * moonMult * costMult).coerceIn(TOTAL_FLOOR_7807, MAX_GROWTH_MULT_7807)

        var size = sizeFromInvalidation(i.equitySol, b.baseRiskFrac, total, stop, cost0)
        val cost1 = if (size > 0.0) roundTripCostPct(size, i.solUsd, i.liquidityUsd) else cost0
        size = sizeFromInvalidation(i.equitySol, b.baseRiskFrac, total, stop, cost1)
        val laneCap = i.equitySol * b.maxPositionEquityFrac
        val liqCap = if (i.liquidityUsd.isFinite() && i.liquidityUsd > 0.0 && i.solUsd.isFinite() && i.solUsd > 0.0)
            (i.liquidityUsd * 0.5 * LIQ_MAX_ONE_WAY_IMPACT_PCT_7807 / 100.0) / i.solUsd else Double.POSITIVE_INFINITY
        if (size > laneCap) { size = laneCap; labels += "LANE_MAX_POSITION_CAP_7807" }
        if (size > liqCap) { size = liqCap; labels += "LIQUIDITY_IMPACT_CAP_7807" }
        if (i.upstreamSol > 0.0 && size > i.upstreamSol) size = i.upstreamSol

        if (i.execMinSol > 0.0 && size < i.execMinSol) {
            // V5.0.7840 — prove the venue minimum against the REAL hard-risk
            // envelope instead of rejecting merely because adaptive sizing
            // landed below it. This is the last-mile counterpart to the shared
            // resolver's capacity promotion: no safety/EV veto is bypassed.
            val minCost7840 = roundTripCostPct(i.execMinSol, i.solUsd, i.liquidityUsd)
            // V5.0.7987 — on a sub-1-SOL wallet the venue minimum is a large share of equity: a meme
            // lane's 20% position cap refused EVERY meme order below 0.137 SOL equity (5.0.7985: equity
            // 0.1215, cap 0.0243 < min 0.0273, 177 refusals, runner grabs included). The minimum may use
            // up to [smallAccountMinShare7987] of equity; the hard per-trade loss cap below still binds.
            val minCap7987 = maxOf(laneCap, i.equitySol * smallAccountMinShare7987(i.equitySol))
            val minFundable7840 = i.execMinSol <= minCap7987 + 1e-12 && i.execMinSol <= liqCap + 1e-12
            val minRiskSafe7840 = minFundable7840 &&
                executableMinRiskOk(i.execMinSol, stop, minCost7840, i.equitySol)
            if (minRiskSafe7840) {
                labels += "RISK_SAFE_EXECUTABLE_MIN_PROMOTED_7840"
                return Decision(true, i.execMinSol, "OPEN_RISK_SAFE_MIN_PROMOTED_7840", labels, stop, minCost7840, total)
            }
            return Decision(false, 0.0, "RISK_SIZE_BELOW_EXECUTABLE_MINIMUM_7835", labels, stop, minCost7840, total)
        }
        return Decision(true, size, "OPEN_7807", labels, stop, cost1, total)
    }

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
            it.mode.equals("live", ignoreCase = true) && canonicalLane(it.lane) == key
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
    ): Inputs {
        ensureSubscribed()
        // The caller carries the canonical decision lane. Never substitute a
        // different candidate's active intent while resolving a new proposal.
        val effectiveLane7811 = canonicalLane(lane)

        val equity = liveEquitySol(walletSol)
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
        )
    }

    /** Records the verdict (labels + one forensic line); returns the decision unchanged. */
    fun record(stage: String, mint: String, symbol: String, i: Inputs, d: Decision): Decision {
        try {
            if (d.open) {
                opens.incrementAndGet()
                PipelineHealthCollector.labelInc("LIVE_RISK_POLICY_${stage}_OPEN_7807")
            } else {
                passes.computeIfAbsent(d.reason) { AtomicLong(0) }.incrementAndGet()
                PipelineHealthCollector.labelInc("LIVE_RISK_POLICY_${stage}_PASS_7807")
                PipelineHealthCollector.labelInc(d.reason)
            }
            for (l in d.labels) PipelineHealthCollector.labelInc(l)
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

    fun statusLine(): String =
        "LiveRiskPolicy7807: opens=${opens.get()} passes=${passes.entries.joinToString(",") { "${it.key}=${it.value.get()}" }} " +
            "equityPeak=${"%.4f".format(CapitalDrawdown7948.currentPeak7948().peakSol)}"
}
