package com.lifecyclebot.engine.truth

import com.lifecyclebot.data.CandidateDecision
import com.lifecyclebot.data.TokenState
import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.7715 §THE_FIELD_MANUAL_IS_THE_BASELINE_BRAIN.
 *
 * Operator, 2 October 2026, handing over "The Crypto Trader's Field Manual"
 * (docs/FIELD_MANUAL.md, kept verbatim): "I want this used as a baseline
 * logic helper. so from trade one live or paper there is a real trading
 * brain to build on. this goes everywhere."
 *
 * This object is that baseline. It is deliberately not another scorer: it
 * is the manual's trading loop (§1), regime map (§3.1), setup playbook
 * (§4), all-in cost model (§7.1), risk-from-the-stop sizing (§8.1),
 * expectancy maths (§8.3), plan card (§9), pass conditions (§9) and exit
 * discipline (§10) expressed as code that every entry and exit passes
 * through, in both modes, from the first trade. The lane specialists keep
 * their own edge; the manual is the shared expert baseline they build on
 * (§12). "Everywhere" is four seams, each of which already sees every
 * trade of every lane:
 *
 *   FinalDecisionGate.evaluate      every candidate gets a PlanCard and a
 *                                   Decision before any lane-specific gate
 *                                   (ENTER / SMALL_PROBE / WAIT / PASS).
 *   OrderSizeResolver6441.resolve   the one sizing authority applies the
 *                                   risk-from-the-stop cap and the probe
 *                                   multiplier.
 *   Executor.requestSell            every exit is classified by the §10
 *                                   discipline so the operator can see what
 *                                   kind of exits the book is taking.
 *   GeminiCopilot                   every LLM call carries the doctrine as
 *                                   the first part of its system prompt.
 *
 * What it refuses, in both modes (the manual's "pass" conditions are
 * economics, not throttles): unresolved identity, no priced liquidity on
 * live, round-trip impact that eats a fifth of the position, all-in cost
 * that eats half the expected move, a reward-to-risk under one half.
 *
 * What it treats differently by mode, on purpose: a missing trigger, a
 * stale quote, an unknown regime or a late vertical entry is WAIT on live
 * and a half-size SMALL_PROBE on paper. The operator doctrine since 7697 is
 * fewer, larger, higher-conviction live entries and an unthrottled paper
 * book whose job is to explore; the manual's own §12 keeps the two
 * evidence pools separate. Paper still carries the card, the reasons and
 * the probe tag, so the learning layer sees the manual's opinion on every
 * paper trade too.
 */
object FieldManual7715 {

    // ──────────────────────────────────────────────────────────────────
    // Vocabulary (§1, §3.1, §4, §9, §10)
    // ──────────────────────────────────────────────────────────────────

    /** §9 plan-card decision. */
    enum class Decision { ENTER, SMALL_PROBE, WAIT, PASS }

    /** §3.1 regime map. */
    enum class Regime { UPTREND, DOWNTREND, RANGE, VOL_EXPANSION, LAUNCH, IMPAIRED, UNKNOWN }

    /** §4 setup playbook A–J. */
    enum class SetupFamily(val code: Char, val label: String) {
        TREND_PULLBACK('A', "trend pullback / continuation"),
        BASE_BREAKOUT('B', "base breakout and retest"),
        RANGE_REVERSION('C', "range mean reversion"),
        SWEEP_RECLAIM('D', "failed breakdown / sweep and reclaim"),
        MOMENTUM('E', "momentum continuation"),
        LAUNCH('F', "event, listing or token launch"),
        RELATIVE_STRENGTH('G', "relative-strength rotation"),
        BASIS_CARRY('H', "funding / basis / cash-and-carry"),
        VENUE_ARB('I', "cross-venue / pool arbitrage"),
        DERIVATIVES('J', "shorting and derivatives"),
    }

    /** §10 exit discipline. */
    enum class ExitClass { STRUCTURAL, INTEGRITY, TARGET, TIME, REGIME, OPERATIONAL, OTHER }

    /**
     * §12 specialist mandate: which setup a lane is designed to exploit, the
     * gross move it is paid for, and where its structural invalidation sits.
     * The gross/invalidation numbers are the lane's own tested behaviour
     * (runner lanes ride for multiples with a -15 floor, treasury lanes
     * harvest single digits with a tight stop); the manual turns them into
     * the reward-to-risk and cost tests, it does not invent them. The risk
     * fractions are set so that risk / (invalidation + cost) lands at the
     * 7697 doctrine share (about half of tradeable on a sub-1 SOL wallet):
     * the cap binds oversize requests, it does not shrink the doctrine.
     */
    data class LaneMandate(
        val lane: String,
        val setup: SetupFamily,
        val expectedGrossPct: Double,
        val invalidationPct: Double,
        val riskFraction: Double,
        val runner: Boolean,
    )

    /** §9 trading plan card, one per candidate, stamped before any gate. */
    data class PlanCard(
        val mint: String,
        val symbol: String,
        val lane: String,
        val paper: Boolean,
        val mandate: LaneMandate,
        val regime: Regime,
        val identityResolved: Boolean,
        val quoteAgeMs: Long,
        val liquidityUsd: Double,
        val sizeSol: Double,
        val sizeUsd: Double,
        val allInCostPct: Double,
        val impactRoundTripPct: Double,
        val triggerPresent: Boolean,
        val triggerNote: String,
        val runUpPct: Double,
        val expectedGrossPct: Double,
        val invalidationPct: Double,
        val rewardToRisk: Double,
    )

    /** The card's answer: decision, reasons, and the size multiplier a probe carries. */
    data class Verdict(
        val decision: Decision,
        val reasons: List<String>,
        val sizeMultiplier: Double,
        val card: PlanCard,
    ) {
        val blocks: Boolean get() = decision == Decision.WAIT || decision == Decision.PASS
        val blockReason: String get() = when (decision) {
            Decision.PASS -> "FIELD_MANUAL_DECLINE_7835:" + reasons.firstOrNull().orEmpty()
            Decision.WAIT -> "FIELD_MANUAL_WAIT_7715:" + reasons.firstOrNull().orEmpty()
            else -> ""
        }
    }

    /**
     * Small, explicit rule prior for the predictive brain. This encodes the
     * manual's setup/trigger discipline; it is not a learned outcome or an
     * EV claim. Missing market evidence stays neutral. The independently
     * evaluated manual verdict still owns its existing execution boundary.
     */
    data class FoundationPrior(val deltaPct: Double, val label: String)

    fun foundationPrior(card: PlanCard): FoundationPrior? {
        if (!active()) return null
        val verdict = evaluate(card)
        val delta = when {
            verdict.decision == Decision.PASS -> -4.0
            !card.triggerPresent -> -2.0
            verdict.decision == Decision.ENTER -> 4.0
            verdict.decision == Decision.SMALL_PROBE -> 1.0
            else -> 0.0 // WAIT from stale/unknown inputs is uncertainty, not bearish EV.
        }
        return FoundationPrior(
            deltaPct = delta,
            label = "fieldManualFoundation(setup=${card.mandate.setup.code},regime=${card.regime.name}," +
                "trigger=${card.triggerPresent},R=${fmt(card.rewardToRisk)},decision=${verdict.decision.name})",
        )
    }

    // ──────────────────────────────────────────────────────────────────
    // Thresholds. Each one is a sentence from the manual.
    // ──────────────────────────────────────────────────────────────────

    /** §6.1 / §7.3 — a quote older than this is not fresh enough to price an entry. */
    const val QUOTE_MAX_AGE_MS_7715 = 180_000L
    /** §7.1 — round-trip impact above this and the position cannot exit at size. */
    const val MAX_IMPACT_ROUND_TRIP_PCT_7715 = 20.0
    /** §7.1 — all-in cost consuming this share of the expected move removes the edge. */
    const val MAX_COST_SHARE_OF_GROSS_7715 = 0.50
    /** §8.3 — below this reward-to-risk the trade is a pass; below one it is a probe. */
    const val MIN_R_PASS_7715 = 0.5
    const val MIN_R_FULL_7715 = 1.0
    /** §13 "chasing a vertical move" — run-up over the lookback that makes a non-launch entry late. */
    const val LATE_RUN_UP_PCT_7715 = 60.0
    /** SMALL_PROBE size, as a share of the proposal (§4 F: "deliberately small"). */
    const val PROBE_SIZE_MULTIPLIER_7715 = 0.5
    /** §7.1 conservative round-trip venue cost when no better number exists: fees + priority + failure allowance. */
    const val BASE_ROUND_TRIP_COST_PCT_7715 = 1.5
    /** §7.1 expected slippage against the bound, both ways. */
    const val SLIPPAGE_ALLOWANCE_PCT_7715 = 1.0
    /** §4 F — a pool younger than this is a launch market, whatever the candles say. */
    private const val LAUNCH_AGE_MS_7715 = 2L * 60L * 60_000L
    private const val PROBE_TTL_MS_7715 = 10L * 60_000L

    // ──────────────────────────────────────────────────────────────────
    // V5.0.7717 — activation. 5.0.7715 crashed the app the instant the
    // password was accepted and the trace was never captured. The manual is
    // an optional doctrine layer: it engages only once the process has been
    // up for a minute (login and service bootstrap are never in its path),
    // and it stays off for six hours after any startup crash
    // (StartupCrashGuard7717), saying so in the report. Every public entry
    // point below checks active() itself, so the seams need no flag of their
    // own and an inactive manual is a no-op everywhere.
    // ──────────────────────────────────────────────────────────────────
    const val ACTIVATION_DELAY_MS_7717 = 60_000L
    private val inactiveReads7717 = AtomicLong(0)

    fun active(): Boolean {
        val on = try {
            !StartupCrashGuard7717.manualSuppressed() && StartupCrashGuard7717.uptimeMs() >= ACTIVATION_DELAY_MS_7717
        } catch (_: Throwable) { false }
        if (!on) inactiveReads7717.incrementAndGet()
        return on
    }
    private const val REGIME_LOOKBACK_7715 = 60
    private const val RUN_UP_LOOKBACK_7715 = 20

    // ──────────────────────────────────────────────────────────────────
    // Lane mandates (§12)
    // ──────────────────────────────────────────────────────────────────

    private val RUNNER_LANE = LaneMandate("RUNNER", SetupFamily.LAUNCH, expectedGrossPct = 50.0, invalidationPct = 15.0, riskFraction = 0.09, runner = true)
    private val MOMENTUM_LANE = LaneMandate("MOMENTUM", SetupFamily.MOMENTUM, expectedGrossPct = 30.0, invalidationPct = 10.0, riskFraction = 0.065, runner = false)
    private val DIP_LANE = LaneMandate("DIP", SetupFamily.SWEEP_RECLAIM, expectedGrossPct = 25.0, invalidationPct = 10.0, riskFraction = 0.065, runner = false)
    private val QUALITY_LANE = LaneMandate("QUALITY", SetupFamily.TREND_PULLBACK, expectedGrossPct = 20.0, invalidationPct = 10.0, riskFraction = 0.065, runner = false)
    private val TREASURY_LANE = LaneMandate("TREASURY", SetupFamily.RANGE_REVERSION, expectedGrossPct = 12.0, invalidationPct = 5.0, riskFraction = 0.04, runner = false)
    private val CRYPTO_LANE = LaneMandate("CRYPTO", SetupFamily.RELATIVE_STRENGTH, expectedGrossPct = 12.0, invalidationPct = 6.0, riskFraction = 0.045, runner = false)
    private val PERPS_LANE = LaneMandate("PERPS", SetupFamily.DERIVATIVES, expectedGrossPct = 10.0, invalidationPct = 3.0, riskFraction = 0.02, runner = false)
    private val CARRY_LANE = LaneMandate("CARRY", SetupFamily.BASIS_CARRY, expectedGrossPct = 6.0, invalidationPct = 1.5, riskFraction = 0.02, runner = false)
    private val ARB_LANE = LaneMandate("ARB", SetupFamily.VENUE_ARB, expectedGrossPct = 4.0, invalidationPct = 0.8, riskFraction = 0.02, runner = false)
    private val GENERIC_LANE = LaneMandate("GENERIC", SetupFamily.BASE_BREAKOUT, expectedGrossPct = 20.0, invalidationPct = 10.0, riskFraction = 0.065, runner = false)

    /** Which part of the playbook owns a lane. Unknown lanes get the generic breakout mandate. */
    fun mandateFor(lane: String?): LaneMandate {
        val l = lane?.trim()?.uppercase().orEmpty()
        return when {
            l.isEmpty() -> GENERIC_LANE
            l.contains("MOONSHOT") || l.contains("SHITCOIN") || l.contains("SNIPER") ||
                l.contains("SNIPE") || l.contains("MICRO_CAP") || l.contains("MANIPULATED") ||
                l.contains("PUMP") || l.contains("LAUNCH") -> RUNNER_LANE.copy(lane = l)
            l.contains("EXPRESS") || l.contains("NARRATIVE") || l.contains("CULT") ||
                l.contains("MOMENTUM") || l.contains("AGGRESSIVE") || l.contains("WHALE") ||
                l.contains("COPY") -> MOMENTUM_LANE.copy(lane = l)
            l.contains("DIP") || l.contains("RANGE") -> DIP_LANE.copy(lane = l)
            l.contains("QUALITY") || l.contains("BLUE") || l.contains("CORE") ||
                l.contains("LONG_HOLD") || l.contains("DEFENSIVE") -> QUALITY_LANE.copy(lane = l)
            l.contains("TREASURY") || l.contains("CASHGEN") || l.contains("CASH") -> TREASURY_LANE.copy(lane = l)
            l.contains("PERP") || l.contains("LEV") -> PERPS_LANE.copy(lane = l)
            l.contains("FUNDING") || l.contains("BASIS") || l.contains("CARRY") -> CARRY_LANE.copy(lane = l)
            l.contains("ARB") -> ARB_LANE.copy(lane = l)
            l.contains("CRYPTO") || l.contains("MARKET") || l.contains("FOREX") ||
                l.contains("METAL") || l.contains("COMMOD") || l.contains("STOCK") ||
                l.contains("UNIVERSE") -> CRYPTO_LANE.copy(lane = l)
            else -> GENERIC_LANE.copy(lane = l)
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // Maths (§7.1, §8.1, §8.3)
    // ──────────────────────────────────────────────────────────────────

    /**
     * §7.1 all-in cost as a percent of notional, for the size actually
     * intended: venue fees + priority + failure allowance, impact both
     * ways against the quote side of the pool, slippage both ways.
     * Unknown liquidity is a conservative range, never zero.
     */
    fun allInCostPct(sizeUsd: Double, liquidityUsd: Double): Double {
        val solUsd = try { com.lifecyclebot.engine.WalletManager.lastKnownSolPrice } catch (_: Throwable) { 0.0 }
        if (!solUsd.isFinite() || solUsd <= 0.0 || !sizeUsd.isFinite() || sizeUsd <= 0.0) {
            return BASE_ROUND_TRIP_COST_PCT_7715 + SLIPPAGE_ALLOWANCE_PCT_7715 + impactRoundTripPct(sizeUsd, liquidityUsd)
        }
        return roundTripCostPct7766(EconomicUnitInvariant7061.usdToSol(sizeUsd, solUsd), sizeUsd, liquidityUsd)
    }

    /**
     * V5.0.7935 — [costArmedStop7928] for every lane, plus the runner rule: a runner
     * lane under its give-back arm peak gets no sliding lock (room to run), but once
     * its peak cleared net break-even by [RUNNER_BREAKEVEN_MARGIN_PCT_7935] points it
     * is floored at net break-even (FloopyTrador 5.0.7930: peak +21%, sold -5.9%).
     */
    fun runnerAwareStop7935(rawStop: Double, peakPnlPct: Double, costPct: Double, runnerDeferred: Boolean, preTrail: () -> Double): Double {
        if (!runnerDeferred) return costArmedStop7928(rawStop, peakPnlPct, costPct, preTrail)
        val breakEven = (if (costPct.isFinite() && costPct > 0.0) costPct else 0.0) + 0.5
        if (peakPnlPct.isFinite() && peakPnlPct >= breakEven + RUNNER_BREAKEVEN_MARGIN_PCT_7935) return breakEven
        return preTrail().coerceAtMost(0.0)
    }

    private const val RUNNER_BREAKEVEN_MARGIN_PCT_7935 = 6.0

    /**
     * V5.0.7928 — a positive (profit-lock) stop under the round trip is a scratch
     * that books as a loss. Lock at net break-even once the peak cleared it by 1.5
     * pts; before that the lock is not armed and [preTrail] (the pre-trail stop) applies;
     * a [preTrail] failure propagates to the caller's own fallback.
     */
    fun costArmedStop7928(rawStop: Double, peakPnlPct: Double, costPct: Double, preTrail: () -> Double): Double {
        if (!rawStop.isFinite() || rawStop <= 0.0) return rawStop
        val breakEven = (if (costPct.isFinite() && costPct > 0.0) costPct else 0.0) + 0.5
        if (rawStop >= breakEven) return rawStop
        if (peakPnlPct >= breakEven + 1.5) return breakEven
        return preTrail().coerceAtMost(0.0)
    }

    /**
     * V5.0.7766 §ONE_ROUND_TRIP_COST. Four formulas priced the same trip: this one
     * (~2.5% + impact), LiveBreakEvenGuard's entry gate (buy slippage TOLERANCE +
     * 2x learned slip + priority + platform + spread + MEV, ~20-30% at $4k
     * liquidity), LaneShadowProof7307's variant and a fixed 4% for plan exits. A
     * round trip costs the venue fee both ways, the priority fee at this size, a
     * slippage allowance and the price impact in and out. Field Manual §6: cost
     * is what the trip actually takes, not the tolerance set on the order.
     */
    fun roundTripCostPct7766(sizeSol: Double, sizeUsd: Double, liquidityUsd: Double): Double {
        val priority = if (sizeSol.isFinite() && sizeSol > 0.0) (PRIORITY_FEE_SOL_7766 * 2.0 / sizeSol * 100.0).coerceIn(0.0, 6.0) else 6.0
        return PLATFORM_FEE_ROUND_TRIP_PCT_7766 + priority + SLIPPAGE_ALLOWANCE_PCT_7715 + impactRoundTripPct(sizeUsd, liquidityUsd)
    }

    private const val PLATFORM_FEE_ROUND_TRIP_PCT_7766 = 1.0
    // V5.0.7768 — per leg: the Executor's sender tip floor (effectiveSenderTipLamports,
    // 200,000 lamports) plus the CU price (~0.000005 SOL). 0.0004 double-counted it;
    // at a 0.024 SOL entry that added 3.3 points and every CORE/TREASURY card failed
    // "all-in cost eats half" (FIELD_MANUAL_PASS_7715:all-in = 27 on 5.0.7767).
    private const val PRIORITY_FEE_SOL_7766 = 0.000205

    /** Entry impact plus exit impact, percent, constant-product approximation against half the pool. */
    fun impactRoundTripPct(sizeUsd: Double, liquidityUsd: Double): Double {
        if (!sizeUsd.isFinite() || sizeUsd <= 0.0) return 0.0
        if (!liquidityUsd.isFinite() || liquidityUsd <= 0.0) return MAX_IMPACT_ROUND_TRIP_PCT_7715
        val quoteSide = liquidityUsd * 0.5
        val oneWay = (sizeUsd / quoteSide) * 100.0
        return (oneWay * 2.0).coerceIn(0.0, 100.0)
    }

    /**
     * §8.1 position size from the stop:
     *   risk_budget = equity × risk_fraction
     *   position_notional ≤ risk_budget / loss_fraction
     * where loss_fraction is the move to invalidation plus costs.
     */
    fun sizeFromStop(equitySol: Double, riskFraction: Double, lossFractionPct: Double): Double {
        if (!equitySol.isFinite() || equitySol <= 0.0) return 0.0
        val loss = (lossFractionPct / 100.0).coerceAtLeast(0.005)
        val budget = equitySol * riskFraction.coerceIn(0.0, 1.0)
        return (budget / loss).coerceIn(0.0, equitySol)
    }

    /** §8.3 expectancy per trade from a cohort's rates and average net outcomes. */
    fun expectancy(winRate: Double, avgNetWin: Double, avgNetLoss: Double): Double {
        val w = winRate.coerceIn(0.0, 1.0)
        return w * avgNetWin - (1.0 - w) * kotlin.math.abs(avgNetLoss)
    }

    /** §8.3 profit factor; infinite when there are wins and no losses, zero when there are no wins. */
    fun profitFactor(sumNetWins: Double, sumNetLosses: Double): Double {
        val l = kotlin.math.abs(sumNetLosses)
        return when {
            l > 0.0 -> sumNetWins / l
            sumNetWins > 0.0 -> Double.POSITIVE_INFINITY
            else -> 0.0
        }
    }

    /** §8.3 R multiple: realised net result over the planned risk. */
    fun rMultiple(realisedNetPnl: Double, plannedRisk: Double): Double =
        if (plannedRisk > 0.0 && plannedRisk.isFinite()) realisedNetPnl / plannedRisk else 0.0

    /** Reward-to-risk of a plan: (gross − cost) / (invalidation + cost). */
    fun rewardToRisk(expectedGrossPct: Double, invalidationPct: Double, allInCostPct: Double): Double {
        val reward = expectedGrossPct - allInCostPct
        val risk = invalidationPct + allInCostPct
        return if (risk > 0.0) reward / risk else 0.0
    }

    // ──────────────────────────────────────────────────────────────────
    // Reading the market (§3)
    // ──────────────────────────────────────────────────────────────────

    /** §3.1 regime from the asset's own candles; a working hypothesis, reclassified every call. */
    fun regimeOf(ts: TokenState, nowMs: Long = System.currentTimeMillis()): Regime {
        val price = ts.lastPrice
        if (!price.isFinite() || price <= 0.0) return Regime.IMPAIRED
        // V5.0.7809 — an old stamp whose price a fresh re-quote has just confirmed is not impaired.
        if (quoteAgeMs7837(ts, nowMs) > QUOTE_MAX_AGE_MS_7715) return Regime.IMPAIRED
        val candles = realCandles(ts, REGIME_LOOKBACK_7715)
        val ageMs = nowMs - ts.addedToWatchlistAt
        if (candles.size < 6) {
            return if (ageMs in 0..LAUNCH_AGE_MS_7715 || isLaunchSource(ts)) Regime.LAUNCH else Regime.UNKNOWN
        }
        val third = candles.size / 3
        val first = candles.subList(0, third)
        val middle = candles.subList(third, 2 * third)
        val last = candles.subList(2 * third, candles.size)
        val hi1 = first.maxOf { it.priceUsd }; val lo1 = first.minOf { it.priceUsd }
        val hi2 = middle.maxOf { it.priceUsd }; val lo2 = middle.minOf { it.priceUsd }
        val hi3 = last.maxOf { it.priceUsd }; val lo3 = last.minOf { it.priceUsd }
        val rangeMid = if (lo2 > 0.0) (hi2 - lo2) / lo2 else 0.0
        val rangeLast = if (lo3 > 0.0) (hi3 - lo3) / lo3 else 0.0
        return when {
            ageMs in 0..LAUNCH_AGE_MS_7715 && isLaunchSource(ts) -> Regime.LAUNCH
            hi3 > hi2 && lo3 > lo2 && hi2 >= hi1 * 0.98 -> Regime.UPTREND
            hi3 < hi2 && lo3 < lo2 && lo2 <= lo1 * 1.02 -> Regime.DOWNTREND
            rangeMid > 0.0 && rangeLast > rangeMid * 1.8 -> Regime.VOL_EXPANSION
            else -> Regime.RANGE
        }
    }

    /** Percent move from the lookback's first real candle to the current price (§13 "chasing a vertical move"). */
    fun runUpPct(ts: TokenState): Double {
        val candles = realCandles(ts, RUN_UP_LOOKBACK_7715)
        val base = candles.firstOrNull()?.priceUsd ?: return 0.0
        if (base <= 0.0 || ts.lastPrice <= 0.0) return 0.0
        return (ts.lastPrice / base - 1.0) * 100.0
    }

    private fun isAmmPriced(setup: SetupFamily): Boolean =
        setup != SetupFamily.BASIS_CARRY && setup != SetupFamily.VENUE_ARB && setup != SetupFamily.DERIVATIVES

    private fun isLaunchSource(ts: TokenState): Boolean {
        val s = ts.source.uppercase()
        return s.contains("PUMP") || s.contains("LAUNCH") || s.contains("NEW") || s.contains("BONK") || s.contains("MOONSHOT")
    }

    private fun realCandles(ts: TokenState, max: Int): List<com.lifecyclebot.data.Candle> {
        val all = try { ts.history.toList() } catch (_: Throwable) { emptyList() }
        val real = all.filter { !it.synthetic && it.priceUsd > 0.0 }
        return if (real.size > max) real.subList(real.size - max, real.size) else real
    }

    // ──────────────────────────────────────────────────────────────────
    // Setup trigger (§4): context, trigger, invalidation, exit plan, payoff.
    // ──────────────────────────────────────────────────────────────────

    /**
     * Does the setup the lane is mandated for have its trigger present?
     * Returns the trigger note as evidence. The candidate's own BUY signal
     * and the strategy's spike→pullback→reclaim flag are inputs, not the
     * answer: the manual wants a named trigger for the named setup.
     */
    fun triggerFor(setup: SetupFamily, ts: TokenState, candidate: CandidateDecision, regime: Regime): Pair<Boolean, String> {
        val candles = realCandles(ts, RUN_UP_LOOKBACK_7715)
        val last = candles.lastOrNull()
        val price = ts.lastPrice
        val recentHi = candles.maxOfOrNull { it.priceUsd } ?: price
        val recentLo = candles.minOfOrNull { it.priceUsd } ?: price
        val avgVol = if (candles.size > 1) candles.dropLast(1).map { it.vol }.average() else 0.0
        val lastVol = last?.vol ?: 0.0
        val buyRatio = last?.buyRatio ?: 0.5
        val reclaim = candidate.isOptimalEntry
        val expanding = candidate.edgePhase.equals("EXPANSION", ignoreCase = true)
        val reaccum = candidate.edgePhase.equals("REACCUMULATION", ignoreCase = true)
        val distributing = candidate.edgePhase.equals("DISTRIBUTION", ignoreCase = true) ||
            candidate.edgePhase.equals("DEAD", ignoreCase = true)
        if (distributing) return false to "edge phase ${candidate.edgePhase}: sellers own the tape"
        return when (setup) {
            SetupFamily.TREND_PULLBACK -> {
                val pulledBack = recentHi > 0.0 && price < recentHi * 0.97 && price > recentLo
                val ok = (regime == Regime.UPTREND || reaccum) && (reclaim || pulledBack) && buyRatio >= 0.45
                ok to "pullback in trend hi=$recentHi lo=$recentLo reclaim=$reclaim buyRatio=${fmt(buyRatio)}"
            }
            SetupFamily.BASE_BREAKOUT -> {
                val nearHigh = recentHi > 0.0 && price >= recentHi * 0.97
                val volumeConfirms = avgVol <= 0.0 || lastVol >= avgVol * 1.2
                val ok = (nearHigh && volumeConfirms) || reclaim || expanding
                ok to "break nearHigh=$nearHigh vol=${fmt(lastVol)} avg=${fmt(avgVol)} reclaim=$reclaim"
            }
            SetupFamily.RANGE_REVERSION -> {
                val span = recentHi - recentLo
                val nearEdge = span > 0.0 && (price <= recentLo + span * 0.25)
                val ok = (regime == Regime.RANGE || reaccum) && (nearEdge || reclaim)
                ok to "range edge lo=$recentLo hi=$recentHi nearLowerEdge=$nearEdge reclaim=$reclaim"
            }
            SetupFamily.SWEEP_RECLAIM -> {
                val ok = reclaim || (regime != Regime.DOWNTREND && recentLo > 0.0 && price > recentLo * 1.03 && buyRatio >= 0.5)
                ok to "sweep/reclaim reclaim=$reclaim aboveLow=${fmt(if (recentLo > 0.0) price / recentLo else 0.0)} buyRatio=${fmt(buyRatio)}"
            }
            SetupFamily.MOMENTUM -> {
                val ok = (expanding || regime == Regime.UPTREND || regime == Regime.VOL_EXPANSION) && buyRatio >= 0.5
                ok to "momentum phase=${candidate.edgePhase} regime=$regime buyRatio=${fmt(buyRatio)}"
            }
            SetupFamily.LAUNCH -> {
                val buyersPresent = buyRatio >= 0.5 || expanding || reaccum
                val ok = buyersPresent && regime != Regime.DOWNTREND
                ok to "launch buyers=${fmt(buyRatio)} phase=${candidate.edgePhase} regime=$regime"
            }
            SetupFamily.RELATIVE_STRENGTH -> {
                val ok = regime != Regime.DOWNTREND && (regime == Regime.UPTREND || expanding || reaccum || reclaim)
                ok to "relative strength regime=$regime phase=${candidate.edgePhase}"
            }
            SetupFamily.BASIS_CARRY, SetupFamily.VENUE_ARB, SetupFamily.DERIVATIVES -> {
                // The spot book does not price these; the owning trader carries its own trigger.
                true to "owner-defined trigger for ${setup.label}"
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // The plan card (§9) and the decision (§9, §16)
    // ──────────────────────────────────────────────────────────────────

    /**
     * V5.0.7730 — quote freshness from the canonical mark when the token state
     * carries none. 5.0.7729: FIELD_MANUAL_WAIT_7715:quote=186 (the manual's
     * top block, ahead of every other reason it has) and PASS:quote=23, on
     * BLUECHIP / TREASURY candidates that had a live canonical mark and a
     * price, but `lastPriceUpdate=0`: the mark reaches TokenState through the
     * canonical registry, which stamps its own timestamp, not the legacy
     * field. "Freshness unknown" was a plumbing gap, not a stale quote. The
     * registry's timestamp is the provider's; a mint with no mark at all is
     * still unknown and still waits.
     */
    /** Only evidence for this price can refresh this card; never splice a new
     * timestamp onto a different price. Both regime and card use this resolver. */
    internal fun quoteAgeMs7837(ts: TokenState, nowMs: Long): Long {
        val ages = ArrayList<Long>(3)
        if (ts.lastPriceUpdate > 0L && ts.lastPriceUpdate <= nowMs)
            ages += nowMs - ts.lastPriceUpdate
        try {
            val mark = CanonicalPriceMarkRegistry6522.get(ts.mint)
            if (mark != null && mark.baseMint == ts.mint && mark.timestampMs > 0L && mark.timestampMs <= nowMs &&
                pricesAgree7837(ts.lastPrice, mark.priceUsd.value.toDouble())) {
                ages += nowMs - mark.timestampMs
            }
        } catch (_: Throwable) {}
        try { QuoteRevalidation7809.confirmedAgeMs(ts.mint, ts.lastPrice, nowMs)?.let { ages += it } } catch (_: Throwable) {}
        return ages.minOrNull() ?: -1L
    }

    internal fun pricesAgree7837(cardPrice: Double, observedPrice: Double): Boolean =
        cardPrice.isFinite() && cardPrice > 0.0 && observedPrice.isFinite() && observedPrice > 0.0 &&
            kotlin.math.abs(observedPrice - cardPrice) / cardPrice <= 0.03

    /** The same candle-derived setup as the entry planner, not a second
     * mandatory pattern inferred solely from the lane's name. */
    internal fun mandateFromRead7837(base: LaneMandate, read: TradePlan7739.Read): LaneMandate {
        if (!isAmmPriced(base.setup) || read.setup == null || !read.stopPct.isFinite() || read.stopPct <= 0.0 ||
            !read.firstTargetPct.isFinite() || read.firstTargetPct <= 0.0) return base
        val setup = when (read.setup) {
            TradePlan7739.Setup.PULLBACK_RECLAIM -> SetupFamily.TREND_PULLBACK
            TradePlan7739.Setup.BASE_BREAKOUT -> SetupFamily.BASE_BREAKOUT
            TradePlan7739.Setup.SWEEP_RECLAIM -> SetupFamily.SWEEP_RECLAIM
            TradePlan7739.Setup.LAUNCH_EARLY -> SetupFamily.LAUNCH
        }
        return base.copy(setup = setup, expectedGrossPct = read.firstTargetPct, invalidationPct = read.stopPct)
    }

    fun cardFor(
        ts: TokenState,
        candidate: CandidateDecision,
        lane: String?,
        paper: Boolean,
        proposedSizeSol: Double,
        nowMs: Long = System.currentTimeMillis(),
    ): PlanCard {
        val baseMandate = mandateFor(lane)
        val planRead = TradePlan7739.readForEntry7837(ts, nowMs)
        val mandate = mandateFromRead7837(baseMandate, planRead)
        val observedSetup = mandate !== baseMandate
        val regime = regimeOf(ts, nowMs)
        val identity = ts.mint.isNotBlank() && ts.lastPrice > 0.0 &&
            (ts.pairAddress.isNotBlank() || ts.lastPriceSource.isNotBlank() || ts.lastPricePoolAddr.isNotBlank())
        val quoteAge7809 = quoteAgeMs7837(ts, nowMs)
        val solUsd = try { com.lifecyclebot.engine.WalletManager.lastKnownSolPrice } catch (_: Throwable) { 0.0 }
        val size = if (proposedSizeSol.isFinite()) proposedSizeSol.coerceAtLeast(0.0) else 0.0
        val sizeUsd = if (solUsd > 0.0) size * solUsd else 0.0
        val liq = if (ts.lastLiquidityUsd.isFinite()) ts.lastLiquidityUsd else 0.0
        // H/I/J are priced by their owning venue trader (fees, funding, margin),
        // not by an AMM curve; the pool-impact model does not apply to them.
        val ammPriced = isAmmPriced(mandate.setup)
        val cost = if (ammPriced) allInCostPct(sizeUsd, liq) else BASE_ROUND_TRIP_COST_PCT_7715
        val impact = if (ammPriced) impactRoundTripPct(sizeUsd, liq) else 0.0
        val (trig, note) = if (observedSetup) true to "candle plan ${planRead.setup}: ${planRead.why}"
            else triggerFor(mandate.setup, ts, candidate, regime)
        val gross = mandate.expectedGrossPct
        val inval = mandate.invalidationPct
        return PlanCard(
            mint = ts.mint,
            symbol = ts.symbol,
            lane = mandate.lane,
            paper = paper,
            mandate = mandate,
            regime = regime,
            identityResolved = identity,
            quoteAgeMs = quoteAge7809,
            liquidityUsd = liq,
            sizeSol = size,
            sizeUsd = sizeUsd,
            allInCostPct = cost,
            impactRoundTripPct = impact,
            triggerPresent = trig,
            triggerNote = note,
            runUpPct = runUpPct(ts),
            expectedGrossPct = gross,
            invalidationPct = inval,
            rewardToRisk = rewardToRisk(gross, inval, cost),
        )
    }

    /**
     * §9 "pass" conditions and §16 decision card. Hard refusals are the same
     * in both modes; evidence gaps are WAIT on live and SMALL_PROBE on paper.
     */
    fun evaluate(card: PlanCard): Verdict {
        val hard = ArrayList<String>()
        val soft = ArrayList<String>()

        if (!card.identityResolved) hard += "identity unresolved (no pair/pool/source behind the quote)"
        val ammPriced = isAmmPriced(card.mandate.setup)
        if (card.liquidityUsd <= 0.0 && !card.paper && ammPriced) hard += "no priced liquidity: exit cannot be costed"
        if (card.impactRoundTripPct > MAX_IMPACT_ROUND_TRIP_PCT_7715 && card.liquidityUsd > 0.0)
            hard += "round-trip impact ${fmt(card.impactRoundTripPct)}% at size $${fmt(card.sizeUsd)} vs liq $${fmt(card.liquidityUsd)}"
        if (card.expectedGrossPct > 0.0 && card.allInCostPct >= card.expectedGrossPct * MAX_COST_SHARE_OF_GROSS_7715)
            hard += "all-in cost ${fmt(card.allInCostPct)}% eats half of expected ${fmt(card.expectedGrossPct)}%"
        if (card.rewardToRisk < MIN_R_PASS_7715) hard += "reward-to-risk ${fmt(card.rewardToRisk)} under ${MIN_R_PASS_7715}"
        if (card.regime == Regime.IMPAIRED) hard += "quote impaired or older than ${QUOTE_MAX_AGE_MS_7715 / 1000}s"

        if (card.quoteAgeMs > QUOTE_MAX_AGE_MS_7715) soft += "quote age ${card.quoteAgeMs / 1000}s"
        if (card.quoteAgeMs < 0L) soft += "quote freshness unknown"
        if (!card.triggerPresent) soft += "no ${card.mandate.setup.code} trigger: ${card.triggerNote}"
        if (card.regime == Regime.DOWNTREND && !card.mandate.runner) soft += "asset in downtrend: wait for a base"
        if (card.regime == Regime.UNKNOWN && !card.mandate.runner) soft += "regime unknown: too few real candles"
        if (card.runUpPct > LATE_RUN_UP_PCT_7715 && card.mandate.setup != SetupFamily.LAUNCH)
            soft += "late: +${fmt(card.runUpPct)}% over lookback"
        if (card.rewardToRisk >= MIN_R_PASS_7715 && card.rewardToRisk < MIN_R_FULL_7715) soft += "reward-to-risk ${fmt(card.rewardToRisk)} under one: probe size"
        if (card.liquidityUsd <= 0.0 && card.paper && ammPriced) soft += "liquidity unknown on paper: conservative cost range"

        val decision = when {
            hard.isNotEmpty() -> Decision.PASS
            soft.isEmpty() -> Decision.ENTER
            card.paper -> Decision.SMALL_PROBE
            // Live: a sub-one R with every other field answered is still a probe, the rest is wait.
            soft.size == 1 && card.rewardToRisk >= MIN_R_PASS_7715 && card.rewardToRisk < MIN_R_FULL_7715 -> Decision.SMALL_PROBE
            else -> Decision.WAIT
        }
        // V5.0.7774 — operator: "I dont want probe trades." The verdict still records
        // SMALL_PROBE (the card's open questions stay visible and counted), but it no
        // longer halves the order: an admitted trade goes at the size the sizer chose.
        // Nothing that used to trade is refused by this change.
        val mult = 1.0
        return Verdict(decision, hard + soft, mult, card)
    }

    private val enters = AtomicLong(0)
    private val probes = AtomicLong(0)
    private val waits = AtomicLong(0)
    private val passes = AtomicLong(0)
    @Volatile private var lastVerdictLine: String = ""
    private val probeByMint = ConcurrentHashMap<String, Pair<Double, Long>>()

    /**
     * One call from the gate: build the card, decide, record, and hand the
     * probe multiplier to the sizer. The gate wraps this call and treats a
     * fault as "no opinion", so the manual can never become the thing that
     * stops the book by crashing.
     */
    fun decide(
        ts: TokenState,
        candidate: CandidateDecision,
        lane: String?,
        paper: Boolean,
        proposedSizeSol: Double,
    ): Verdict {
        val card = cardFor(ts, candidate, lane, paper, proposedSizeSol)
        val verdict = evaluate(card)
        val modeTag = if (paper) "PAPER" else "LIVE"
        requoteIfOnlyQuoteIsOpen7809(ts, verdict)
        when (verdict.decision) {
            Decision.ENTER -> enters.incrementAndGet()
            Decision.SMALL_PROBE -> { probes.incrementAndGet(); probeByMint[ts.mint] = verdict.sizeMultiplier to System.currentTimeMillis() }
            Decision.WAIT -> waits.incrementAndGet()
            Decision.PASS -> passes.incrementAndGet()
        }
        if (verdict.decision != Decision.SMALL_PROBE) probeByMint.remove(ts.mint)
        if (probeByMint.size > 512) {
            val cutoff = System.currentTimeMillis() - PROBE_TTL_MS_7715
            probeByMint.entries.removeIf { it.value.second < cutoff }
        }
        try {
            PipelineHealthCollector.labelInc("FIELD_MANUAL_${verdict.decision.name}_7715")
            PipelineHealthCollector.labelInc("FIELD_MANUAL_${verdict.decision.name}_${modeTag}_7715")
            PipelineHealthCollector.labelInc("FIELD_MANUAL_SETUP_${card.mandate.setup.code}_7715")
        } catch (_: Throwable) {}
        lastVerdictLine = "${verdict.decision} ${card.symbol} lane=${card.lane} setup=${card.mandate.setup.code} " +
            "regime=${card.regime} R=${fmt(card.rewardToRisk)} cost=${fmt(card.allInCostPct)}% " +
            (verdict.reasons.firstOrNull() ?: "all fields answered")
        if (verdict.blocks || verdict.decision == Decision.SMALL_PROBE) try {
            ForensicLogger.lifecycle(
                "FIELD_MANUAL_${verdict.decision.name}_7715",
                "mode=$modeTag mint=${ts.mint.take(10)} sym=${card.symbol} lane=${card.lane} " +
                    "setup=${card.mandate.setup.code} regime=${card.regime} identity=${card.identityResolved} " +
                    "quoteAgeS=${card.quoteAgeMs / 1000} liqUsd=${fmt(card.liquidityUsd)} sizeSol=${fmt(card.sizeSol)} " +
                    "sizeUsd=${fmt(card.sizeUsd)} cost=${fmt(card.allInCostPct)}% impact=${fmt(card.impactRoundTripPct)}% " +
                    "trigger=${card.triggerPresent} runUp=${fmt(card.runUpPct)}% R=${fmt(card.rewardToRisk)} " +
                    "reasons=${verdict.reasons.joinToString(" | ")}",
            )
        } catch (_: Throwable) {}
        return verdict
    }

    /**
     * V5.0.7809 — FIELD_MANUAL_WAIT_7715:quote on 5.0.7808 live. When a LIVE
     * card blocks and every open question is the quote (age / freshness /
     * stale-impaired), the setup is otherwise valid: ask for one bounded
     * re-quote instead of letting the candidate lapse. This tick still blocks
     * (never execute on stale price evidence); a confirmed quote makes the next
     * card recompute cost / impact / R:R and decide again. Field Manual L187.
     */
    private fun requoteIfOnlyQuoteIsOpen7809(ts: TokenState, verdict: Verdict) {
        if (verdict.card.paper || !verdict.blocks || verdict.reasons.isEmpty()) return
        if (!verdict.reasons.all { it.startsWith("quote ") }) return
        try {
            if (QuoteRevalidation7809.request(ts.mint, ts.lastPrice)) {
                PipelineHealthCollector.labelInc("FIELD_MANUAL_QUOTE_REVALIDATION_ASKED_7809")
            }
        } catch (_: Throwable) {}
    }

    /** The multiplier a SMALL_PROBE verdict left for the sizer; 1.0 when none is current. */
    fun probeSizeMultiplier(mint: String): Double {
        if (mint.isBlank() || !active()) return 1.0
        val e = probeByMint[mint] ?: return 1.0
        if (System.currentTimeMillis() - e.second > PROBE_TTL_MS_7715) {
            probeByMint.remove(mint)
            return 1.0
        }
        return e.first.coerceIn(0.1, 1.0)
    }

    // ──────────────────────────────────────────────────────────────────
    // Sizing (§8.1, §8.2) — consumed by OrderSizeResolver6441
    // ──────────────────────────────────────────────────────────────────

    private val riskCapApplied = AtomicLong(0)
    private val riskCapFloorWins = AtomicLong(0)

    /**
     * §8.1 risk-from-the-stop ceiling for one position in a lane, in SOL.
     * The loss fraction is the lane's invalidation plus the conservative
     * round-trip cost. On live the ceiling never undercuts the routable
     * minimum: a position that cannot route is not a smaller risk, it is a
     * failed order (operator doctrine 7399/7706), and the floor win is
     * labelled so the report shows when the wallet is below its own manual.
     */
    fun riskCapSol(lane: String?, paper: Boolean, equitySol: Double): Double {
        if (!active()) return Double.POSITIVE_INFINITY
        val m = mandateFor(lane)
        val lossPct = m.invalidationPct + BASE_ROUND_TRIP_COST_PCT_7715 + SLIPPAGE_ALLOWANCE_PCT_7715
        val cap = sizeFromStop(equitySol, m.riskFraction, lossPct)
        if (cap <= 0.0) return Double.POSITIVE_INFINITY
        if (paper) return cap
        val routableMin = try {
            val solUsd = com.lifecyclebot.engine.WalletManager.lastKnownSolPrice
            if (solUsd > 0.0) com.lifecyclebot.v3.sizing.SmartSizerV3.routableCapacityPreflight7224(equitySol, solUsd).routableMinSol else 0.0
        } catch (_: Throwable) { 0.0 }
        if (routableMin > cap) {
            riskCapFloorWins.incrementAndGet()
            try { PipelineHealthCollector.labelInc("FIELD_MANUAL_RISK_CAP_UNDER_ROUTABLE_FLOOR_7715") } catch (_: Throwable) {}
            return routableMin
        }
        return cap
    }

    /** Called by the sizer when the cap actually trimmed a request. */
    fun noteRiskCapApplied(lane: String?, requestedSol: Double, cappedSol: Double) {
        riskCapApplied.incrementAndGet()
        try {
            PipelineHealthCollector.labelInc("FIELD_MANUAL_RISK_CAP_APPLIED_7715")
            ForensicLogger.lifecycle(
                "FIELD_MANUAL_RISK_CAP_APPLIED_7715",
                "lane=${lane.orEmpty()} requested=${fmt(requestedSol)} capped=${fmt(cappedSol)}",
            )
        } catch (_: Throwable) {}
    }

    // ──────────────────────────────────────────────────────────────────
    // Exit discipline (§10) — consumed by Executor.requestSell
    // ──────────────────────────────────────────────────────────────────

    private val exitCounts = ConcurrentHashMap<String, AtomicLong>()

    /** Map any exit reason string onto the manual's exit classes. */
    fun classifyExit(reason: String): ExitClass {
        val r = reason.uppercase()
        return when {
            r.contains("QUARANTINE") || r.contains("INTEGRITY") || r.contains("RUG") || r.contains("FREEZE") ||
                r.contains("HONEYPOT") || r.contains("MARK_INVALID") || r.contains("IDENTITY") ||
                r.contains("DUST_UNROUTABLE") || r.contains("LIQUIDITY_COLLAPSE") || r.contains("LIQ_") ||
                r.contains("CATASTROPHIC") || r.contains("EMERGENCY") -> ExitClass.INTEGRITY
            r.contains("TAKE_PROFIT") || r.contains("TP_") || r.contains("_TP") || r.contains("TARGET") ||
                r.contains("PARTIAL") || r.contains("LADDER") || r.contains("RUNG") || r.contains("PEAK") ||
                r.contains("TRAIL") || r.contains("COMPOUND") -> ExitClass.TARGET
            r.contains("STOP") || r.contains("SL_") || r.contains("_SL") || r.contains("FLOOR") ||
                r.contains("DRAWDOWN") || r.contains("BREAKDOWN") || r.contains("STRUCTURE") ||
                r.contains("INVALIDAT") || r.contains("HARD_") -> ExitClass.STRUCTURAL
            r.contains("CULL") || r.contains("STALE") || r.contains("TIME") || r.contains("TTL") ||
                r.contains("AGE") || r.contains("DEAD_MONEY") || r.contains("FLAT") || r.contains("HORIZON") ->
                ExitClass.TIME
            r.contains("REGIME") || r.contains("MACRO") || r.contains("MARKET_") || r.contains("BENCHMARK") -> ExitClass.REGIME
            r.contains("RECONCIL") || r.contains("ORPHAN") || r.contains("MANUAL") || r.contains("BOOTUP") ||
                r.contains("RESURRECT") || r.contains("SWEEP") || r.contains("DISPOS") || r.contains("LIQUIDATION") ||
                r.contains("RECOVER") -> ExitClass.OPERATIONAL
            else -> ExitClass.OTHER
        }
    }

    /** Record one exit request against its class, per mode. */
    fun noteExit(reason: String, paper: Boolean): ExitClass {
        val cls = classifyExit(reason)
        if (!active()) return cls
        val key = (if (paper) "PAPER_" else "LIVE_") + cls.name
        exitCounts.getOrPut(key) { AtomicLong(0) }.incrementAndGet()
        try { PipelineHealthCollector.labelInc("FIELD_MANUAL_EXIT_${cls.name}_7715") } catch (_: Throwable) {}
        return cls
    }

    // ──────────────────────────────────────────────────────────────────
    // The doctrine as the LLM's first instruction (§12) — GeminiCopilot
    // ──────────────────────────────────────────────────────────────────

    private const val DOCTRINE_7715: String =
        "TRADING DOCTRINE (The Crypto Trader's Field Manual, baseline for every lane, live and paper):\n" +
            "1. Trading loop: protect the account; verify exact asset identity and venue; classify regime " +
            "(uptrend/downtrend/range/volatility expansion/launch/impaired); name the setup with context, trigger, " +
            "invalidation, exit plan and holding period; price the trade all-in (fees, spread, impact both ways, slippage, " +
            "failure allowance); size from the distance to invalidation, then cap for liquidity and concentration; " +
            "execute, reconcile fills, record one outcome per position.\n" +
            "2. Default is PASS when identity, exitability, trigger, invalidation or cost-adjusted payoff cannot be " +
            "established. Cash is a position. A missed move creates no obligation to chase.\n" +
            "3. Setups: A trend pullback, B base breakout/retest, C range reversion at an edge, D failed breakdown/sweep " +
            "and reclaim, E momentum continuation with participation still present, F launch with verified identity and " +
            "exit route, G relative strength, H basis/carry, I venue arbitrage, J derivatives only with known liquidation rules.\n" +
            "4. Volume shows activity, not demand. Indicators are correlated descriptions, not independent votes. " +
            "Market cap is not liquidity. A mark is not an executable price. A submitted order is not a fill.\n" +
            "5. Risk: position_notional <= equity x risk_fraction / loss_fraction_to_invalidation_including_costs. " +
            "No martingale, no widening stops, no revenge entries, reserve cash for exits. " +
            "Nothing the bot buys is ever unmanaged: every bought holding is a position with an exit from the moment it is held.\n" +
            "6. Exits: structural (thesis failed), integrity (identity/route/liquidity/data untrustworthy, act at once), " +
            "target, time, regime, operational (reconcile before retrying). Never turn an invalidated trade into an investment.\n" +
            "7. Evidence: keep LIVE, PAPER and SHADOW separate; count unique finalized outcomes, not scans; small samples " +
            "stay uncertain. Missing evidence is uncertainty, never a positive signal.\n" +
            "Answer as the specialist you are asked to be, within this doctrine. Decisions are ENTER / SMALL PROBE / WAIT / PASS.\n"

    /** The doctrine text, for prompts and the report. */
    fun doctrineForLlm(): String = DOCTRINE_7715

    /** Prepend the doctrine to any system prompt that does not already carry it. */
    fun withDoctrine7715(systemPrompt: String): String {
        if (!active()) return systemPrompt
        if (systemPrompt.contains("TRADING DOCTRINE (The Crypto Trader's Field Manual")) return systemPrompt
        return DOCTRINE_7715 + "\n" + systemPrompt
    }

    // ──────────────────────────────────────────────────────────────────
    // Report
    // ──────────────────────────────────────────────────────────────────

    fun statusLine(): String {
        val exits = exitCounts.entries.sortedBy { it.key }.joinToString(" ") { "${it.key}=${it.value.get()}" }
        return "active=${active()} inactiveReads=${inactiveReads7717.get()} guard=[${try { StartupCrashGuard7717.statusLine() } catch (_: Throwable) { "?" }}] " +
            "enter=${enters.get()} probe=${probes.get()} wait=${waits.get()} pass=${passes.get()} " +
            "riskCapApplied=${riskCapApplied.get()} floorWins=${riskCapFloorWins.get()} " +
            "exits[$exits] last=[$lastVerdictLine] " +
            "requote=[${try { QuoteRevalidation7809.statusLine() } catch (_: Throwable) { "?" }}]"
    }

    private fun fmt(v: Double): String =
        if (!v.isFinite()) v.toString() else String.format(java.util.Locale.US, "%.3f", v)
}
