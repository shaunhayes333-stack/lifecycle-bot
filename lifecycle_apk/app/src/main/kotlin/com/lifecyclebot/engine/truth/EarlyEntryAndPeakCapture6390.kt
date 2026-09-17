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

    /** Should we exit right now given the current gain vs the peak? */
    fun shouldExitOnTrail(peakGainPct: Double, currentGainPct: Double): Boolean {
        val trailPct = trailPctForPeakGain(peakGainPct)
        if (trailPct.isInfinite()) return false
        val giveBack = peakGainPct - currentGainPct
        return giveBack >= trailPct
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
    internal fun clearForTest() { peaks.clear() }
}

/**
 * V5.0.6390 — force-cut when give-back from peak exceeds a threshold. This
 * is a coarser safety net that acts even if trail evaluation is delayed.
 */
object PeakSlipExit6390 {
    enum class Action { HOLD, CUT_HALF, CUT_FULL }

    /** Directive: give-back 25% → cut half; give-back 40% → cut full. */
    fun evaluate(peakGainPct: Double, currentGainPct: Double): Action {
        if (peakGainPct < 30.0) return Action.HOLD    // avoid tripping on noise
        val giveBack = peakGainPct - currentGainPct
        return when {
            giveBack >= 40.0 -> Action.CUT_FULL
            giveBack >= 25.0 -> Action.CUT_HALF
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
        if (PeakAdaptiveTrail6390.shouldExitOnTrail(i.peakGainPct, i.currentGainPct))
            return Decision(Verdict.TRAIL_EXIT,
                "PEAK_ADAPTIVE_TRAIL_BROKEN peakGain=${i.peakGainPct} current=${i.currentGainPct}", 1.0)
        // 5. Scheduled winner ladder partial.
        val rung = WinnerLadderExit6390.nextRung(i.positionId, i.currentGainPct)
        if (rung != null)
            return Decision(Verdict.LADDER_PARTIAL, "WINNER_LADDER_${rung.name}", rung.sellFraction)
        return Decision(Verdict.HOLD, "OK", 0.0)
    }

    /** V5.0.6919 — a peak must exist before "distribution into it" is meaningful. */
    private const val MIN_PEAK_GAIN_FOR_VOL_EXHAUSTION_6919 = 25.0
}
