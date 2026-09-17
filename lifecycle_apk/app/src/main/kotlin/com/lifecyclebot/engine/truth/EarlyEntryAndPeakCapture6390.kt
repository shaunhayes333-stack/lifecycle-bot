package com.lifecyclebot.engine.truth

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * V5.0.6390 — EARLY-ENTRY DETECTION + PEAK CAPTURE.
 *
 * Directive: bot correctly identified CHEEMS but entered AFTER the chart
 * turned green (crowd-obvious) and rode the position without cutting the
 * top. A 26× runner became a give-back.
 *
 * Two orthogonal problems, one bundle, no cherry-picking:
 *
 *   PART A — EARLY ENTRY (pre-parabola)
 *     EarlyEntryScout6390        — score BEFORE the crowd
 *     SmartMoneyClusterDetector  — 2+ whale/copy-trader buys in 60s
 *     BondingCurveAcceleration   — buy-tx rate > sell-tx rate rising
 *     MicroCapAccumulationBand   — MC 20k-100k with organic breadth
 *
 *   PART B — PEAK CAPTURE (dynamic trail + give-back cut + ladder)
 *     PeakAdaptiveTrail6390      — trail % ratchets tighter as gain grows
 *     PeakSlipExit6390           — force cut when give-back > threshold
 *     WinnerLadderExit6390       — 25/25/25/25 ladder at 3x / 5x / 10x
 *     VolumeExhaustionDetector   — buy-vol collapse warning
 *     WhaleDistributionAlarm     — top-holder net-sell detection
 */

/* ============================ PART A · EARLY ENTRY ========================= */

object EarlyEntryScout6390 {
    /** Inputs the scout evaluates for a fresh candidate BEFORE the parabola. */
    data class Signals(
        val mintAgeMinutes: Long,
        val liquidityUsd: Double,
        val marketCapUsd: Double,
        val distinctBuyerWalletsLast60s: Int,
        val netBuyVolumeUsdLast60s: Double,
        val netSellVolumeUsdLast60s: Double,
        val topHolderConcentrationPct: Double,
        val topHolderConcentrationDeltaPct: Double,   // negative = accumulating retail
        val smartMoneyBuysLast60s: Int,
        val bondingCurveBuyTxPerMinRising: Boolean,
        val hasRealPoolAddress: Boolean,
        val mintAuthorityRevoked: Boolean,
        val freezeAuthorityRevoked: Boolean,
    )
    data class Verdict(val score: Int, val tier: Tier, val reasons: List<String>)
    enum class Tier {
        NOT_QUALIFIED,        // regular scanner path
        EARLY_INTEREST,       // small size, faster route
        HIGH_CONVICTION_EARLY, // priority routing, decisive size
    }

    /** Directive: score higher when signals are visible BEFORE the chart turns green. */
    fun evaluate(s: Signals): Verdict {
        val reasons = mutableListOf<String>()
        var score = 0

        // Hard filters — safety rails first (never abandoned by tiering).
        if (!s.hasRealPoolAddress) return Verdict(0, Tier.NOT_QUALIFIED, listOf("NO_REAL_POOL"))
        if (!s.mintAuthorityRevoked) return Verdict(0, Tier.NOT_QUALIFIED, listOf("MINT_AUTHORITY_LIVE"))
        if (!s.freezeAuthorityRevoked) return Verdict(0, Tier.NOT_QUALIFIED, listOf("FREEZE_AUTHORITY_LIVE"))
        if (s.liquidityUsd < 3_000.0) return Verdict(0, Tier.NOT_QUALIFIED, listOf("LP_BELOW_3K"))

        // 1. Fresh mint: young age is the strongest edge over crowd-followers.
        if (s.mintAgeMinutes in 1..15) { score += 25; reasons += "FRESH_MINT_1_15M" }
        else if (s.mintAgeMinutes in 16..60) { score += 15; reasons += "FRESH_MINT_16_60M" }
        else if (s.mintAgeMinutes > 240) { score -= 5; reasons += "MATURE_MINT" }

        // 2. Micro-cap window (directive's SolSignal example: $46.5k MC).
        if (s.marketCapUsd in 20_000.0..80_000.0) { score += 20; reasons += "MICROCAP_20_80K" }
        else if (s.marketCapUsd in 80_000.1..250_000.0) { score += 10; reasons += "SMALLCAP_80_250K" }
        else if (s.marketCapUsd > 1_000_000.0) { score -= 10; reasons += "MC_ABOVE_1M_LATE" }

        // 3. Smart-money cluster: 2+ known whale/copy-trader buys in 60s.
        if (s.smartMoneyBuysLast60s >= 3) { score += 30; reasons += "SMART_MONEY_CLUSTER_3PLUS" }
        else if (s.smartMoneyBuysLast60s == 2) { score += 20; reasons += "SMART_MONEY_CLUSTER_2" }

        // 4. Broad retail accumulation (many distinct wallets, decreasing
        //    top-holder concentration → healthy distribution).
        if (s.distinctBuyerWalletsLast60s >= 20) { score += 15; reasons += "BROAD_BUYERS_20PLUS" }
        else if (s.distinctBuyerWalletsLast60s >= 10) { score += 8; reasons += "BROAD_BUYERS_10PLUS" }
        if (s.topHolderConcentrationDeltaPct <= -2.0) { score += 10; reasons += "TOP_HOLDER_DECREASING" }
        if (s.topHolderConcentrationPct > 70.0) { score -= 15; reasons += "TOP_HOLDER_ABOVE_70PCT" }

        // 5. Buy pressure > sell pressure by a meaningful margin.
        if (s.netBuyVolumeUsdLast60s > 0.0 && s.netSellVolumeUsdLast60s >= 0.0) {
            val ratio = s.netBuyVolumeUsdLast60s / (s.netSellVolumeUsdLast60s + 1.0)
            if (ratio >= 3.0) { score += 15; reasons += "BUY_SELL_RATIO_GE_3X" }
            else if (ratio >= 2.0) { score += 8; reasons += "BUY_SELL_RATIO_GE_2X" }
        }

        // 6. Bonding-curve acceleration — buy-tx-per-min trending UP.
        if (s.bondingCurveBuyTxPerMinRising) { score += 10; reasons += "BC_ACCELERATION_RISING" }

        val tier = when {
            score >= 70 -> Tier.HIGH_CONVICTION_EARLY
            score >= 40 -> Tier.EARLY_INTEREST
            else -> Tier.NOT_QUALIFIED
        }
        return Verdict(score, tier, reasons)
    }

    /** Directive: HIGH_CONVICTION_EARLY unlocks priority routing + decisive size. */
    fun sizeMultiplier(t: Tier): Double = when (t) {
        Tier.HIGH_CONVICTION_EARLY -> 1.50
        Tier.EARLY_INTEREST -> 1.10
        Tier.NOT_QUALIFIED -> 1.00
    }
}

/* ============================ PART B · PEAK CAPTURE ======================== */

/**
 * V5.0.6390 — dynamic trailing stop. As unrealized gain grows, the trail
 * ratchets TIGHTER so more of the run is captured. Directive: the CHEEMS
 * 26× would have banked at least 8-10× under this model.
 */
object PeakAdaptiveTrail6390 {
    /** Returns the trail % that must be applied given current peak-gain %. */
    fun trailPctForPeakGain(peakGainPct: Double): Double = when {
        peakGainPct >= 1_000.0 -> 8.0     // >10x  — hold 92% of peak
        peakGainPct >= 500.0 -> 10.0      // 5-10x — hold 90% of peak
        peakGainPct >= 200.0 -> 12.0      // 2-5x  — hold 88% of peak
        peakGainPct >= 100.0 -> 18.0      // 1-2x  — hold 82% of peak
        peakGainPct >= 50.0 -> 25.0
        peakGainPct >= 20.0 -> 30.0
        peakGainPct >= 10.0 -> 40.0
        else -> Double.POSITIVE_INFINITY   // no trail below +10% — allow room
    }

    /**
     * Should we exit right now given the current gain vs the peak?
     *
     * V5.0.6921 §TRAIL_UNIT_CORRECTION — this was:
     *
     *     val giveBack = peakGainPct - currentGainPct   // percentage POINTS
     *     return giveBack >= trailPct                   // fraction OF PEAK
     *
     * The table above is specified, in its own row comments, as a share of
     * the peak: 8.0 means "hold 92% of peak". The comparison treated the same
     * number as an absolute give-back in percentage points. Those two units
     * coincide at exactly one row — peakGain = 100% — and diverge by the
     * peak/100 factor everywhere else. Concretely, at a +1000% peak the
     * trail of 8.0 fired on an 8-POINT give-back: a drop from +1000% to
     * +992%, which is 0.8% off the high, not the documented 8%.
     *
     * So the tighter the run got, the more impossible it became to hold. A
     * 10x could not survive one noisy tick. This object's own header says the
     * CHEEMS 26x "would have banked at least 8-10x under this model" — under
     * the shipped arithmetic it would have banked on the first wobble past
     * 10x instead, which is the runner-capture failure the doctrine exists to
     * prevent. In the other direction, a +20% peak needed a give-back of 30
     * POINTS (i.e. a fall to -10%) before the trail said anything at all, so
     * small winners had effectively no trail.
     *
     * Now measured as a share of peak gain, which is what every row comment
     * claims and what the ratchet-tighter-as-it-climbs design requires.
     *
     * V5.0.6921 §PERSONALITY_TRAIL_SLACK — PersonalityTraitMultipliers.
     * trailSlackMultiplier() is read here. Its module header states
     * "conviction (loyalty) up -> +5% trail slack on winners (let winners
     * run)", but the only thing that ever called it was summaryLine(), so
     * that sentence described a dashboard string. The exit half of the
     * personality — this and takeProfitBiasPct — was printed, never applied,
     * while the sizing half was properly wired. Bounded [0.95, 1.10] at the
     * source; widening the trail means more room, i.e. let it run.
     */
    fun shouldExitOnTrail(
        peakGainPct: Double,
        currentGainPct: Double,
        /**
         * V5.0.6926 — mean per-candle true range as a % of PRICE. 0.0 means
         * "no volatility evidence", which falls back to the legacy table.
         */
        atrPctPerCandle: Double = 0.0,
        /**
         * V5.0.6948 — the mint, so an ELITE patient-hold profile can widen the
         * trail. Empty means "no profile lookup", which is the prior behaviour
         * exactly, so every existing caller is unchanged until it passes one.
         */
        mint6948: String = "",
    ): Boolean {
        if (peakGainPct <= 0.0) return false
        // V5.0.6948 §THE_OTHER_HALF_OF_THE_PATIENT_HOLD.
        //
        // MoonshotHoldProfileRegistry6415.trailStopPctFromPeak had zero callers,
        // so the elite profile suppressed the stop-loss (wired) without ever
        // widening the trail that replaces it. Once TP suppression releases at
        // +400%, the trail is the ONLY thing standing between a 26x and a round
        // trip — and it was the ordinary table, which at a +2500% peak is 8%.
        //
        // The registry returns 30.0 for elite: "wider than the standard trail so
        // a 26x has room to consolidate mid-run." Applied as a FLOOR on the trail
        // width, never a ceiling, so it can only ever grant more room. It is
        // applied inside each branch in THAT BRANCH'S OWN BASIS — run-fraction
        // for the legacy table, price-drawdown for the ATR branch — because
        // blending the two bases is the exact defect this file was bitten by in
        // 6921 and again in 6926. The two differ by peak/(100+peak); at the gains
        // where an elite profile is still live (>+400%, post-suppression) that is
        // under 20% of the number and always in the direction of more room.
        val eliteTrailFloorPct6948 = if (mint6948.isNotBlank()) {
            try {
                com.lifecyclebot.engine.truth.MoonshotHoldProfileRegistry6415
                    .trailStopPctFromPeak(mint6948)
                    ?.takeIf { it.isFinite() && it > 0.0 }
                    ?: 0.0
            } catch (_: Throwable) { 0.0 }
        } else 0.0
        val slack = try {
            com.lifecyclebot.engine.PersonalityTraitMultipliers.trailSlackMultiplier()
                .let { if (it.isFinite() && it > 0.0) it else 1.0 }
                .coerceIn(0.95, 1.10)
        } catch (_: Throwable) { 1.0 }

        // V5.0.6926 §TRAIL_ON_VOLATILITY_NOT_ON_GAIN.
        //
        // The table below is indexed on peak gain. Peak gain does not decide
        // whether a dip is noise or a trend break — VOLATILITY does.
        //
        // A token up 10x in four hours is printing 10-15% candles. An 8%
        // trail on that is roughly 0.6 ATR: it is not a trail, it is a
        // donation. One market-buy imbalance wicks it out and the move
        // continues without us. The same 8% on a +30% grinder with 2% candles
        // is 4 ATR, which is so loose it is not a stop at all.
        //
        // And it is worse than arbitrary, because the parabolic leg is the
        // HIGHEST-volatility part of the whole move. A gain-indexed
        // tightening trail therefore gets tightest exactly when volatility
        // peaks — wrong in both dimensions at once.
        //
        // So when there is real volatility evidence, the trail is an ATR
        // multiple off the high and the tape decides how much room the
        // position gets. Quiet tape, tight trail. Wild tape, wide trail.
        //
        // ON THE DENOMINATOR, which is the trap here. ATR is a percentage of
        // PRICE. The legacy comparison below is a percentage of the RUN
        // ((peak-current)/peak, fixed in V5.0.6921). Those are different
        // bases and mixing them is the exact defect class this file has
        // already been bitten by twice. With entry P0, peak Pmax and current
        // P, the price drawdown off the high is
        //
        //     (Pmax - P) / Pmax  =  (peakGain - currentGain) / (100 + peakGain)
        //
        // so the ATR branch uses that form and the legacy branch keeps its
        // own. Two internally-consistent regimes, never blended.
        val atr = if (atrPctPerCandle.isFinite() && atrPctPerCandle > 0.0) atrPctPerCandle else 0.0
        if (atr > 0.0) {
            val atrTrailPct = (atr * ATR_TRAIL_MULT_6926 * slack * regimeTrailMult6929())
                .coerceIn(ATR_TRAIL_FLOOR_PCT_6926, ATR_TRAIL_CEIL_PCT_6926)
                // Elite floor applied AFTER the ceiling clamp, deliberately: the
                // 35% ceiling exists to stop a volatility spike inventing an
                // absurd trail, not to overrule an explicit patient-hold profile.
                .coerceAtLeast(eliteTrailFloorPct6948)
            val priceDrawdownPctFromHigh =
                (peakGainPct - currentGainPct) / (100.0 + peakGainPct) * 100.0
            return priceDrawdownPctFromHigh >= atrTrailPct
        }

        // No volatility evidence — fall back to the legacy gain-indexed table
        // in its own (run-fraction) basis, as corrected by V5.0.6921.
        val baseTrailPct = trailPctForPeakGain(peakGainPct)
        // An infinite base means "below +10%, no trail at all — allow room".
        // The elite floor must not convert that into a 30% trail where none
        // existed; more room is the whole point, so infinity stands.
        if (baseTrailPct.isInfinite()) return false
        val trailPct = (baseTrailPct * slack).coerceAtLeast(eliteTrailFloorPct6948)
        val giveBackPctOfPeak = (peakGainPct - currentGainPct) / peakGainPct * 100.0
        return giveBackPctOfPeak >= trailPct
    }

    /**
     * V5.0.6926 — trail width in ATR multiples off the high.
     *
     * 2.75 sits in the band discretionary traders actually use (2-3 ATR).
     * Below ~2 you are inside the noise and get wicked out of every runner;
     * above ~3.5 the trail stops protecting anything.
     */
    private const val ATR_TRAIL_MULT_6926 = 2.75

    /**
     * Floor: a dead-quiet token must still have SOME trail, or a slow bleed
     * never triggers one.
     */
    private const val ATR_TRAIL_FLOOR_PCT_6926 = 5.0

    /**
     * Ceiling: a lunatic ATR must not effectively disable the trail. 35%
     * matches the ceiling AdvancedExitManager.calculateProgressiveTrailingStop
     * independently arrived at for its own monster-runner trail — useful
     * corroboration from a different author solving the same problem.
     */
    private const val ATR_TRAIL_CEIL_PCT_6926 = 35.0

    /**
     * V5.0.6926 — mean per-candle true range as a % of price, from whatever
     * evidence exists. Returns 0.0 when there is none, which is the signal to
     * fall back to the legacy table rather than to guess.
     *
     * Prefers raw candles because ts.volatility is a 0..100 score that
     * SATURATES at 10% per candle (DataOrchestrator: `ranges.average() * 10.0`
     * coerced to 100), and a parabolic memecoin routinely exceeds that. Losing
     * resolution precisely on the biggest runners is the opposite of useful,
     * so the score is only the fallback.
     */
    /**
     * V5.0.6929 — MarketRegimeAI.getTrailMultiplier, which had zero callers.
     *
     * The regime layer publishes a per-regime trail looseness (STRONG_BULL
     * 1.5 "50% looser trails", STRONG_BEAR 0.6 "40% tighter trails") and
     * nothing read it, so the trail was regime-blind. Its sibling
     * getHoldTimeMultiplier has been wired into both max-hold gates for
     * builds; the trail half was not.
     *
     * It belongs on the ATR trail specifically, because the two express
     * different things: ATR says how noisy the tape is right now, the regime
     * says which way the whole asset class is leaning. In a strong bull a
     * runner deserves more rope than its own volatility implies; in a strong
     * bear you bank into strength because the next leg is probably down.
     *
     * ONE CORRECTION APPLIED: HIGH_VOLATILITY is neutralised to 1.0 here. Its
     * 1.3 exists for the stated reason "volatility = noise", i.e. it is
     * compensating for exactly the thing the ATR term already measures
     * directly and better. Passing it through would double-count volatility
     * and widen the trail twice for the same fact — the double-counting
     * defect class this file has already been bitten by. The trend regimes
     * pass through unchanged because they are independent information.
     *
     * Fails open to 1.0.
     */
    private fun regimeTrailMult6929(): Double = try {
        val regime = com.lifecyclebot.engine.MarketRegimeAI.getCurrentRegime()
        if (regime == com.lifecyclebot.engine.MarketRegimeAI.Regime.HIGH_VOLATILITY) 1.0
        else com.lifecyclebot.engine.MarketRegimeAI.getTrailMultiplier()
            .let { if (it.isFinite() && it > 0.0) it else 1.0 }
            .coerceIn(0.6, 1.5)
    } catch (_: Throwable) { 1.0 }

    fun atrPctFromRanges6926(ranges: List<Double>): Double {
        if (ranges.isEmpty()) return 0.0
        val clean = ranges.filter { it.isFinite() && it >= 0.0 }
        if (clean.size < 3) return 0.0
        val mean = clean.average()
        return if (mean.isFinite() && mean > 0.0) mean else 0.0
    }

    /** Track peak per position so trail is stateful across ticks. */
    private val peaks = ConcurrentHashMap<String, Double>()
    fun recordTick(positionId: String, currentGainPct: Double): Double {
        val newPeak = peaks.compute(positionId) { _, prior ->
            if (prior == null || currentGainPct > prior) currentGainPct else prior
        }!!
        return newPeak
    }
    fun peakGainPctFor(positionId: String): Double = peaks[positionId] ?: 0.0

    /**
     * V5.0.6948 §THE_PEAK_NOBODY_READ_AND_NOBODY_EVICTED.
     *
     * recordTick() is called once per hot-exit tick — and since V5.0.6945 raised
     * held-position pricing from 0.41Hz to a true 1Hz, that is now once per second
     * per open position. peakGainPctFor() had ZERO callers: PeakCaptureAuthority
     * .decide() is handed ts.position.peakGainPct instead. So this map was written
     * every tick, read never, and cleared never — one permanent entry per mint the
     * bot has ever held.
     *
     * The tempting fix is to merge the two peaks (max of tracker and position).
     * That would be a runner-killer. ts.position.peakGainPct is DELIBERATELY reset
     * (OpenPnlSanity:258 zeroes it when the basis is untrustworthy) and DELIBERATELY
     * rebased (BotService:10910 sets it to the current pnl after a basis change).
     * This map is keyed by MINT and has no notion of either event, so a max() would
     * resurrect a dead peak from a previous position in the same mint and trail-exit
     * the re-entry on its first tick. One authority stays one authority.
     *
     * So: evict on close/rebase, and expose the tracker as DIVERGENCE TELEMETRY
     * rather than as a second opinion the exit path has to arbitrate between.
     */
    fun onPositionClosed6948(positionId: String) {
        peaks.remove(positionId)
    }

    /**
     * Returns a log-ready string when the independently-tracked peak disagrees
     * with the position's own by more than [tolerancePct] points, or null when
     * they agree. Divergence means one of the two reset and the other did not —
     * which is exactly the condition that silently corrupts every trail decision.
     */
    fun peakDivergence6948(
        positionId: String,
        positionPeakGainPct: Double,
        tolerancePct: Double = 5.0,
    ): String? {
        val tracked = peaks[positionId] ?: return null
        if (!tracked.isFinite() || !positionPeakGainPct.isFinite()) return null
        val gap = tracked - positionPeakGainPct
        if (kotlin.math.abs(gap) < tolerancePct) return null
        return "tracked=${"%.1f".format(tracked)}% position=${"%.1f".format(positionPeakGainPct)}% " +
            "gap=${"%+.1f".format(gap)}pts likely=${if (gap > 0) "position_peak_was_rebased_or_zeroed" else "tracker_missed_ticks"}"
    }

    /** Number of tracked peaks — a leak canary for the snapshot. */
    fun trackedPeakCount6948(): Int = peaks.size

    internal fun clearForTest() { peaks.clear() }
}

/**
 * V5.0.6390 — force-cut when give-back from peak exceeds a threshold. This
 * is a coarser safety net that acts even if trail evaluation is delayed.
 */
object PeakSlipExit6390 {
    enum class Action { HOLD, CUT_HALF, CUT_FULL }

    /**
     * Directive: give-back 25% → cut half; give-back 40% → cut full.
     *
     * V5.0.6921 §GIVEBACK_UNIT_CORRECTION — same unit error as
     * PeakAdaptiveTrail6390, and this one matters more because it is branch 1
     * of PeakCaptureAuthority6390 and it returns a FULL CUT, so it pre-empts
     * every other branch.
     *
     * It was:
     *
     *     val giveBack = peakGainPct - currentGainPct   // percentage POINTS
     *     giveBack >= 40.0 -> CUT_FULL                  // "give-back 40%"
     *
     * A "give-back of 40%" means giving back 40% of the run. The comparison
     * read it as 40 percentage points. The two agree only when peakGainPct is
     * 100, and above that the guard tightens without limit:
     *
     *     peak  +100%  → CUT_FULL at +60%   (40% of the run — as intended)
     *     peak  +500%  → CUT_FULL at +460%  (8% of the run)
     *     peak +1000%  → CUT_FULL at +960%  (4% of the run)
     *
     * So the better a position did, the smaller the wobble needed to
     * liquidate all of it. A 10x got full-cut on a 4% dip from its high.
     * That is the exact opposite of a give-back rule, and it silently
     * capped every large runner this authority was consulted on.
     *
     * Below peak = 100% it erred the other way — a +30% peak needed a fall to
     * -10% before CUT_FULL — so small winners had no protection either.
     *
     * Now a true proportional give-back, which is what the directive says and
     * what keeps the rule scale-free. The 30% minimum peak still gates noise.
     */
    fun evaluate(peakGainPct: Double, currentGainPct: Double): Action {
        if (peakGainPct < 30.0) return Action.HOLD    // avoid tripping on noise
        val giveBackPctOfPeak = (peakGainPct - currentGainPct) / peakGainPct * 100.0
        return when {
            giveBackPctOfPeak >= 40.0 -> Action.CUT_FULL
            giveBackPctOfPeak >= 25.0 -> Action.CUT_HALF
            else -> Action.HOLD
        }
    }
}

/**
 * V5.0.6390 — 25/25/25/25 winner ladder. Take partials as the runner scales;
 * the last 25% keeps trailing under PeakAdaptiveTrail6390.
 */
object WinnerLadderExit6390 {
    enum class Rung(val triggerPct: Double, val sellFraction: Double) {
        RUNG_3X(200.0, 0.25),   // at 3x, sell 25% of INITIAL position
        RUNG_5X(400.0, 0.25),
        RUNG_10X(900.0, 0.25),
        RUNG_25X_HOLD(2_400.0, 0.0),   // above 25x let the trailing 25% run
    }
    private val fired = ConcurrentHashMap<String, MutableSet<Rung>>()

    /** Returns the next rung to fire, or null if none. Idempotent per position. */
    @Synchronized
    fun nextRung(positionId: String, currentGainPct: Double): Rung? {
        val already = fired.getOrPut(positionId) { mutableSetOf() }
        for (r in Rung.values()) {
            if (r in already) continue
            if (r.sellFraction <= 0.0) continue
            if (currentGainPct >= r.triggerPct) {
                already.add(r)
                return r
            }
        }
        return null
    }
    fun firedRungs(positionId: String): Set<Rung> = fired[positionId].orEmpty().toSet()
    internal fun clearForTest() { fired.clear() }
}

/**
 * V5.0.6390 — buy-volume exhaustion detector. When volume drops sharply
 * while price still holds, distribution is starting.
 */
object VolumeExhaustionDetector6390 {
    /** Directive: buy-volume collapse ≥ 60% from rolling peak while price
     *  within 5% of peak = distribution risk. */
    fun isDistributionRisk(peakBuyVolumeUsd: Double, currentBuyVolumeUsd: Double,
                           peakPriceUsd: Double, currentPriceUsd: Double): Boolean {
        if (peakBuyVolumeUsd <= 0.0 || peakPriceUsd <= 0.0) return false
        val volCollapsePct = (peakBuyVolumeUsd - currentBuyVolumeUsd) / peakBuyVolumeUsd * 100.0
        val priceHeldWithin = kotlin.math.abs(peakPriceUsd - currentPriceUsd) / peakPriceUsd * 100.0
        return volCollapsePct >= 60.0 && priceHeldWithin <= 5.0
    }
}

/**
 * V5.0.6390 — whale-distribution alarm. When top-10 holders START selling
 * after a runner is up, exit.
 */
object WhaleDistributionAlarm6390 {
    /** Directive: any single top-10 holder net-sells > 20% of their bag → alarm. */
    fun isAlarm(topHolderNetSellPctOfBag: Double): Boolean =
        topHolderNetSellPctOfBag >= 20.0

    /** Aggregate check across whole top-10 cohort. */
    fun aggregateAlarm(topHolderNetSellsPctOfBag: List<Double>): Boolean =
        topHolderNetSellsPctOfBag.any { it >= 20.0 } ||
        topHolderNetSellsPctOfBag.count { it >= 10.0 } >= 3

    /**
     * V5.0.6919 — the alarm that can actually fire, on the evidence that
     * actually exists.
     *
     * `aggregateAlarm` above needs each top-10 holder's net sells as a share
     * of their own bag. No component in this app tracks per-holder bag size,
     * so its only call site has always passed emptyList() and this detector
     * has never fired once — one of the five branches of
     * PeakCaptureAuthority6390 permanently inert.
     *
     * WhaleWalletTracker holds real per-movement evidence (tokenMint, action,
     * solAmount, whaleScore) and its readers getRecentMovements /
     * getWatchedWhaleMovements were themselves zero-caller. This predicate
     * uses that, in its own units, and requires ALL of:
     *
     *   1. WE ARE UP. Whales selling into a dip is not distribution, it is
     *      capitulation, and the existing stop/backstop layer owns that. This
     *      detector is only about a runner being sold into.
     *   2. A HIGH-CONVICTION SELLER. whaleScore is the tracker's own
     *      reliability measure; a low-score wallet is noise.
     *   3. MATERIAL SIZE. The whale's sold SOL must be meaningful against our
     *      own cost, or against a repeated-seller count. A single small sale
     *      from one wallet is not the top.
     *
     * Deliberately conservative: three independent conditions, and the
     * authority acts on it with a 0.75 partial rather than a full cut,
     * because a whale exiting is strong evidence to BANK most of a runner and
     * weak evidence that the move is over. Capture, not abandon.
     */
    fun trackedWhaleDistribution6919(
        currentGainPct: Double,
        sellEvents: Int,
        topSellerScore: Int,
        whaleSellSol: Double,
        positionCostSol: Double,
    ): Boolean {
        if (currentGainPct < MIN_GAIN_FOR_DISTRIBUTION_6919) return false
        if (sellEvents <= 0) return false
        if (topSellerScore < MIN_WHALE_SCORE_6919) return false
        val materialBySize = positionCostSol > 0.0 &&
            whaleSellSol >= positionCostSol * SELL_SIZE_VS_POSITION_6919
        val materialByRepetition = sellEvents >= MIN_REPEAT_SELLERS_6919
        return materialBySize || materialByRepetition
    }

    /** Only a position in real profit can be "distributed into". */
    private const val MIN_GAIN_FOR_DISTRIBUTION_6919 = 25.0
    /** WhaleWalletTracker's own reliability scale; below this is noise. */
    private const val MIN_WHALE_SCORE_6919 = 60
    /** Whale sold at least this multiple of our own position cost. */
    private const val SELL_SIZE_VS_POSITION_6919 = 2.0
    /** Or this many separate whale sells on the same mint. */
    private const val MIN_REPEAT_SELLERS_6919 = 3
}

/* ============================ AGGREGATE EXIT AUTHORITY ===================== */

/**
 * V5.0.6390 — one place the exit-loop consults every tick. Priority order:
 *   1. PeakSlipExit6390.CUT_FULL      (safety net)
 *   2. WhaleDistributionAlarm         (smart-money exit)
 *   3. VolumeExhaustionDetector       (distribution starting)
 *   4. PeakAdaptiveTrail6390          (trail broken)
 *   5. WinnerLadderExit6390           (scheduled partial)
 */
object PeakCaptureAuthority6390 {
    enum class Verdict { HOLD, LADDER_PARTIAL, TRAIL_EXIT, DISTRIBUTION_EXIT, FULL_CUT }
    data class Decision(val verdict: Verdict, val reason: String, val sellFraction: Double)

    data class Inputs(
        val positionId: String,
        val peakGainPct: Double, val currentGainPct: Double,
        val peakBuyVolumeUsd: Double, val currentBuyVolumeUsd: Double,
        val peakPriceUsd: Double, val currentPriceUsd: Double,
        val topHolderNetSellsPctOfBag: List<Double>,
        // V5.0.6919 §HONEST_WHALE_INPUT.
        //
        // topHolderNetSellsPctOfBag needs each top-10 holder's net sells as a
        // percentage of THEIR OWN bag. Nothing in this app tracks per-holder
        // bag size, so that list has only ever been passed as emptyList() —
        // which means WhaleDistributionAlarm6390, one of the five detectors
        // here, has never been able to fire. See the call site in BotService.
        //
        // WhaleWalletTracker DOES hold real evidence: per-movement tokenMint,
        // action, solAmount and whaleScore, with getRecentMovements /
        // getWatchedWhaleMovements — both zero-caller before V5.0.6919. That
        // is genuine smart-money-exit evidence in a different unit, so it gets
        // its own honestly-named fields rather than being cast into a
        // percentage it is not. Fabricating a "% of bag" from sol amounts
        // would be inventing evidence, which is the exact failure class this
        // codebase keeps rediscovering.
        //
        // Defaults keep every existing construction valid.
        val whaleSellEventsOnMint: Int = 0,
        val whaleTopSellerScore: Int = 0,
        val whaleSellSolOnMint: Double = 0.0,
        /** Position cost, so whale sell size can be judged against our own. */
        val positionCostSol: Double = 0.0,
        /**
         * V5.0.6926 — mean per-candle true range as a % of price. Lets the
         * adaptive trail size itself against the tape instead of against the
         * gain. 0.0 = no evidence, trail falls back to the legacy table.
         */
        val atrPctPerCandle: Double = 0.0,
    )
    fun decide(i: Inputs): Decision {
        // 1. Safety net first — hard give-back → full cut.
        when (PeakSlipExit6390.evaluate(i.peakGainPct, i.currentGainPct)) {
            PeakSlipExit6390.Action.CUT_FULL -> return Decision(Verdict.FULL_CUT,
                "PEAK_SLIP_CUT_FULL peakGain=${i.peakGainPct} current=${i.currentGainPct}", 1.0)
            PeakSlipExit6390.Action.CUT_HALF -> return Decision(Verdict.FULL_CUT,
                "PEAK_SLIP_CUT_HALF peakGain=${i.peakGainPct} current=${i.currentGainPct}", 0.5)
            PeakSlipExit6390.Action.HOLD -> {}
        }
        // 2. Whale distribution.
        if (WhaleDistributionAlarm6390.aggregateAlarm(i.topHolderNetSellsPctOfBag))
            return Decision(Verdict.DISTRIBUTION_EXIT, "WHALE_DISTRIBUTION_ALARM", 1.0)
        // 2b. V5.0.6919 — tracked-whale selling on THIS mint while we are up.
        // The %-of-bag alarm above cannot fire because nothing supplies that
        // unit; this uses the evidence that does exist. Requires all three:
        // we are in profit (so this is distribution, not a dip), a
        // high-conviction whale is the seller, and the sold size is material
        // against our own position. Partial rather than full: a whale exiting
        // is a strong reason to bank most of a runner, not proof the move is
        // finished, and the doctrine is capture not abandon.
        if (WhaleDistributionAlarm6390.trackedWhaleDistribution6919(
                currentGainPct = i.currentGainPct,
                sellEvents = i.whaleSellEventsOnMint,
                topSellerScore = i.whaleTopSellerScore,
                whaleSellSol = i.whaleSellSolOnMint,
                positionCostSol = i.positionCostSol,
            )
        ) return Decision(
            Verdict.DISTRIBUTION_EXIT,
            "TRACKED_WHALE_DISTRIBUTION_6919 events=${i.whaleSellEventsOnMint} " +
                "topScore=${i.whaleTopSellerScore} whaleSol=${"%.2f".format(i.whaleSellSolOnMint)} " +
                "ourCost=${"%.2f".format(i.positionCostSol)} gain=${"%.0f".format(i.currentGainPct)}%",
            0.75,
        )
        // 3. Volume exhaustion.
        //
        // V5.0.6919 §PEAK_PRECONDITION. This branch was written against
        // volumes that the only call site hardcoded to 0.0, so it could never
        // fire and its missing precondition never mattered. Now that it is
        // fed real data the precondition matters a lot: isDistributionRisk
        // asks only "did buy volume collapse while price held near its peak",
        // with no reference to whether we are UP. Without a floor it would
        // sell 75% of a flat or losing position purely because the tape went
        // quiet — and a quiet flat token is exactly the state a memecoin sits
        // in for hours before it runs. "Distribution" means being sold into
        // near a peak, so a peak has to exist. Same floor as branch 2b.
        if (i.peakGainPct >= MIN_PEAK_GAIN_FOR_VOL_EXHAUSTION_6919 &&
            VolumeExhaustionDetector6390.isDistributionRisk(
                i.peakBuyVolumeUsd, i.currentBuyVolumeUsd, i.peakPriceUsd, i.currentPriceUsd))
            return Decision(Verdict.DISTRIBUTION_EXIT,
                "VOLUME_EXHAUSTION_DISTRIBUTION peakGain=${"%.0f".format(i.peakGainPct)}% " +
                    "buyVol=${"%.0f".format(i.currentBuyVolumeUsd)}/${"%.0f".format(i.peakBuyVolumeUsd)}",
                0.75)
        // 4. Adaptive trail broken.
        // V5.0.6948 — positionId is the mint at the only live call site
        // (BotService sets positionIdForPeak = ts.mint), so the elite patient-hold
        // trail floor threads through with no change to Inputs. A caller that
        // passes a non-mint id simply finds no profile and gets a 0.0 floor,
        // i.e. exactly the prior behaviour.
        if (PeakAdaptiveTrail6390.shouldExitOnTrail(
                i.peakGainPct, i.currentGainPct, i.atrPctPerCandle, i.positionId))
            return Decision(Verdict.TRAIL_EXIT,
                "PEAK_ADAPTIVE_TRAIL_BROKEN peakGain=${"%.0f".format(i.peakGainPct)} " +
                    "current=${"%.0f".format(i.currentGainPct)} " +
                    "atrPct=${"%.2f".format(i.atrPctPerCandle)} " +
                    "basis=${if (i.atrPctPerCandle > 0.0) "ATR_6926" else "LEGACY_TABLE"}", 1.0)
        // 5. Scheduled winner ladder partial.
        val rung = WinnerLadderExit6390.nextRung(i.positionId, i.currentGainPct)
        if (rung != null)
            return Decision(Verdict.LADDER_PARTIAL, "WINNER_LADDER_${rung.name}", rung.sellFraction)
        return Decision(Verdict.HOLD, "OK", 0.0)
    }

    /** V5.0.6919 — a peak must exist before "distribution into it" is meaningful. */
    private const val MIN_PEAK_GAIN_FOR_VOL_EXHAUSTION_6919 = 25.0
}
