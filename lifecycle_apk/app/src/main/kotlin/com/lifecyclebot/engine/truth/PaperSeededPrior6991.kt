package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector

/**
 * V5.0.6991 — the one place paper evidence is assessed before it reaches live.
 *
 * OPERATOR DOCTRINE, verbatim:
 *
 *   "paper is meant to seed the live trading engine only with things that can
 *    transfer and be used. pattern memory, where edge lies within the lanes and
 *    traders, the intelligent scanners and where their edge lies, basically
 *    anything that will give live trading a true edge without hurting the real
 *    cash balance. paper builds the brains, the edge, educates the system."
 *
 *   "live of course does its own learning as well and is weighted a lot higher
 *    and harder than paper because it deals with real money ... anything passed
 *    from paper must be intelligently assessed before directly passed into live
 *    trading."
 *
 * THE ASYMMETRY THIS ENCODES
 * ==========================
 * Paper and live differ in exactly one way that matters: a paper fill is free
 * and a live fill is not. Paper has no slippage, no partial fills, no MEV and
 * no failed route legs — every one of which makes a real trade WORSE than its
 * simulation, never better.
 *
 * So the two directions of paper evidence do not deserve the same trust:
 *
 *   CAUTION  — "this lane is bleeding", "this signature rugs", "this cohort is
 *              on a losing streak". Simulation UNDERSTATES how bad live will
 *              be, so this transfers at FULL strength. Being defensive on
 *              real money because paper bled is never the expensive mistake.
 *
 *   OPTIMISM — "this lane wins", "this signature has edge", "size up here".
 *              Simulation OVERSTATES this, because the costs that eat a real
 *              edge are the ones paper does not model. It transfers SHRUNK,
 *              and fades to nothing as live accumulates its own samples.
 *
 * That is what "intelligently assessed" means here, and it is what keeps the
 * seeding from hurting the real cash balance: paper can make live more careful
 * immediately and at full force, and can only make live bolder slowly, weakly,
 * and temporarily.
 *
 * LIVE OUTWEIGHS PAPER. [liveFade] reaches zero at [LIVE_AUTONOMY_N] live
 * samples — past that point live consults its own evidence only, exactly as
 * the operator asked. Below it, one live sample is worth roughly
 * 1/OPTIMISM_WEIGHT paper samples on the optimistic side.
 *
 * This object holds no state and makes no decision of its own. It is a pure
 * assessment applied by the learners that own the evidence.
 */
object PaperSeededPrior6991 {

    /**
     * Paper's optimistic evidence counts for this fraction of live's.
     * 0.35 means roughly three paper wins to trust as much as one live win.
     */
    const val OPTIMISM_WEIGHT = 0.35

    /** Paper's cautionary evidence transfers whole. Deliberately 1.0. */
    const val CAUTION_WEIGHT = 1.0

    /** Live samples after which paper is no longer consulted at all. */
    const val LIVE_AUTONOMY_N = 40

    /**
     * V5.0.6992 — MARKET IMPACT. The thing paper cannot simulate at all.
     *
     * Operator: "paper trading never moves the chart or affects the real life
     * token metrics like real buying and selling does vs live trading."
     *
     * That is a bigger gap than slippage, and it cuts the wrong way for exactly
     * the trades this bot exists to catch. A paper buy fills at the quoted
     * price and leaves the book untouched. A real buy walks the book, moves the
     * mark, and on a thin pool is itself a visible event other bots trade
     * against. A paper sell exits 100% at the mark; a real sell into the same
     * pool is the thing that breaks the price — which is precisely the runner
     * position the doctrine cares most about protecting.
     *
     * So paper's overstatement is not a constant. It scales with how large the
     * position is relative to the pool it has to move through. The lanes
     * showing the most spectacular paper edge are the thin micro-cap ones
     * (MOONSHOT and BLUECHIP at mean +3187% and +2169% in the operator's
     * 5.0.6972 snapshot) and those are exactly the pools where a real fill
     * least resembles its simulation.
     *
     * This returns the fraction of OPTIMISM_WEIGHT that survives, given the
     * position's footprint in the pool. Round-trip footprint is 2x the notional
     * because the exit has to come back out through the same book.
     *
     * A missing or nonsense liquidity reading returns the thin-pool answer, not
     * the generous one: unknown depth is not shallow depth, but it is certainly
     * not proven depth, and this is the direction where being wrong is cheap.
     */
    fun impactSurvival6992(positionSol: Double, liquidityUsd: Double, solPriceUsd: Double): Double {
        if (!positionSol.isFinite() || positionSol <= 0.0) return 1.0
        if (!liquidityUsd.isFinite() || liquidityUsd <= 0.0) return 0.25
        val solPx = if (solPriceUsd.isFinite() && solPriceUsd > 1.0) solPriceUsd else 150.0
        val roundTripUsd = positionSol * solPx * 2.0
        val footprint = roundTripUsd / liquidityUsd
        return when {
            footprint <= 0.002 -> 1.00  // under 0.2% of the pool — impact is noise
            footprint <= 0.01  -> 0.80
            footprint <= 0.03  -> 0.55
            footprint <= 0.08  -> 0.30
            else               -> 0.10  // we ARE the market here; paper proved nothing
        }
    }

    /**
     * Optimism weight adjusted for the pool this trade actually has to move
     * through. Caution is unaffected — market impact makes the downside worse,
     * never better, so a warning from paper stays at full volume regardless of
     * liquidity.
     */
    fun optimismWeightFor6992(positionSol: Double, liquidityUsd: Double, solPriceUsd: Double): Double =
        (OPTIMISM_WEIGHT * impactSurvival6992(positionSol, liquidityUsd, solPriceUsd)).coerceIn(0.0, 1.0)

    /**
     * How much paper is still allowed to speak, given how much live has seen.
     * 1.0 with no live evidence, 0.0 at [LIVE_AUTONOMY_N] and beyond.
     */
    fun liveFade(liveSamples: Long): Double {
        if (liveSamples <= 0L) return 1.0
        if (liveSamples >= LIVE_AUTONOMY_N) return 0.0
        return (1.0 - liveSamples.toDouble() / LIVE_AUTONOMY_N).coerceIn(0.0, 1.0)
    }

    /**
     * Seed a PROTECTIVE quantity — a loss streak, a rug rate, a bleed count.
     * Higher means more careful, so live takes the worse of the two views and
     * paper transfers at full strength. Never fades: a lane that bled in paper
     * is a lane to be careful with on the first real trade, and the point of
     * the first real trade is not to rediscover that with cash.
     */
    fun seedProtective(paperValue: Double, liveValue: Double): Double {
        val p = if (paperValue.isFinite()) paperValue else 0.0
        val l = if (liveValue.isFinite()) liveValue else 0.0
        return maxOf(p * CAUTION_WEIGHT, l)
    }

    /**
     * V5.0.7307 — the hand-over the protective seed always promised and never
     * made. "Once live has its own view maxOf selects it" is false for a
     * streak: a live WIN resets live's streak to 0, and max(paper, 0) keeps
     * the frozen paper streak forever. Paper does not trade while live, so on
     * 5.0.7305 QUALITY carried paper streak 6 (floor +15 to 95, size x0.35,
     * weak candidates shadow-only) for the whole session with no way out.
     *
     * Paper caution still transfers whole until live has booked a terminal
     * close in that lane (OracleTradeHistory7287, journal, all sessions).
     * From then on live's own streak is the streak.
     */
    fun seedProtectiveLiveAware(lane: String, paperValue: Long, liveValue: Long): Long {
        // V5.0.7308 — a lane whose whole recorded history (paper + live, net
        // of fees) is proven positive is not a lane "paper knows is bad": its
        // recent paper streak is already inside that net figure. QUALITY
        // (+25% mean over 44 closes) sat shadow-only on a paper streak of 6.
        val liveHasView = try {
            OracleTradeHistory7287.liveCloses(lane) > 0 ||
                OracleTradeHistory7287.lane(lane)?.let { it.n >= 20 && it.meanNetPct > 0.0 } == true
        } catch (_: Throwable) { false }
        if (liveHasView) {
            if (paperValue > liveValue) {
                try { PipelineHealthCollector.labelInc("PAPER_PRIOR_HANDED_TO_LIVE_7307") } catch (_: Throwable) {}
            }
            return liveValue
        }
        return seedProtective(paperValue, liveValue)
    }

    fun seedProtectiveLiveAware(lane: String, paperValue: Int, liveValue: Int): Int =
        seedProtectiveLiveAware(lane, paperValue.toLong(), liveValue.toLong()).toInt()

    /** Integer form, for streak counters. */
    fun seedProtective(paperValue: Int, liveValue: Int): Int =
        maxOf((paperValue * CAUTION_WEIGHT).toInt(), liveValue)

    fun seedProtective(paperValue: Long, liveValue: Long): Long =
        maxOf((paperValue * CAUTION_WEIGHT).toLong(), liveValue)

    /**
     * Shrink an OPTIMISTIC quantity toward its neutral value before live acts
     * on it. [neutral] is the value that expresses "no opinion" — 0.5 for a
     * win probability, 0.0 for an expected return, 1.0 for a size multiplier.
     *
     * Full strength would let a paper-only edge size up real money; neutral
     * would waste what paper learned. This lands in between and decays.
     */
    fun shrinkOptimism(paperValue: Double, neutral: Double, liveSamples: Long): Double {
        if (!paperValue.isFinite()) return neutral
        val weight = (OPTIMISM_WEIGHT * liveFade(liveSamples)).coerceIn(0.0, 1.0)
        return neutral + (paperValue - neutral) * weight
    }

    /**
     * Assess a paper-sourced value that could be either direction. Anything at
     * or worse than [neutral] is caution and passes whole; anything better is
     * optimism and is shrunk. One call for learners that publish a single
     * signed number, such as a size multiplier around 1.0.
     */
    fun assess(
        paperValue: Double,
        neutral: Double,
        liveSamples: Long,
        label: String,
        // V5.0.6992 — supply these and the optimism shrink also accounts for
        // the position's footprint in the pool. Omitted, behaviour is the
        // pre-6992 liquidity-blind shrink.
        positionSol: Double = Double.NaN,
        liquidityUsd: Double = Double.NaN,
        solPriceUsd: Double = Double.NaN,
    ): Double {
        if (!paperValue.isFinite()) return neutral
        val out = if (paperValue <= neutral) {
            // Caution. Passes whole, and market impact only makes the downside
            // worse, so liquidity never softens a warning.
            paperValue
        } else {
            val base = shrinkOptimism(paperValue, neutral, liveSamples)
            if (positionSol.isFinite() && liquidityUsd.isFinite()) {
                val survival = impactSurvival6992(positionSol, liquidityUsd, solPriceUsd)
                neutral + (base - neutral) * survival
            } else base
        }
        try {
            if (out != paperValue) {
                PipelineHealthCollector.labelInc("PAPER_PRIOR_OPTIMISM_SHRUNK_6991")
                ForensicLogger.lifecycle(
                    "PAPER_PRIOR_OPTIMISM_SHRUNK_6991",
                    "label=$label paper=${"%.4f".format(paperValue)} " +
                        "applied=${"%.4f".format(out)} neutral=${"%.4f".format(neutral)} " +
                        "liveSamples=$liveSamples fade=${"%.3f".format(liveFade(liveSamples))} " +
                        "rule=caution_transfers_whole_optimism_transfers_shrunk_and_fades",
                )
            }
        } catch (_: Throwable) {}
        return out
    }

    /** Emitted when a protective seed actually raised live's guard. */
    fun noteProtectiveSeed(label: String, paperValue: Number, liveValue: Number) {
        try {
            PipelineHealthCollector.labelInc("PAPER_PRIOR_PROTECTIVE_SEED_6991")
            ForensicLogger.lifecycle(
                "PAPER_PRIOR_PROTECTIVE_SEED_6991",
                "label=$label paperValue=$paperValue liveValue=$liveValue " +
                    "rule=paper_caution_transfers_at_full_strength_live_starts_guarded",
            )
        } catch (_: Throwable) {}
    }
}
