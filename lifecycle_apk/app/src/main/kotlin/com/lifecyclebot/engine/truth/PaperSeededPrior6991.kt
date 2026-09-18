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
    fun assess(paperValue: Double, neutral: Double, liveSamples: Long, label: String): Double {
        if (!paperValue.isFinite()) return neutral
        val out = if (paperValue <= neutral) {
            paperValue
        } else {
            shrinkOptimism(paperValue, neutral, liveSamples)
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
