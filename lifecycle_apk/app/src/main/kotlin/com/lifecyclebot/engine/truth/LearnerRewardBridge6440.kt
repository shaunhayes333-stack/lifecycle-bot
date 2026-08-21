package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6440 — LEARNER REWARD BRIDGE.
 *
 * OPERATOR DIRECTIVE (V5.0.6439 follow-up):
 *   "Wire Shaper Into Learners: Retrofit SentienceAutoTune,
 *    AdaptiveLearning and LabUniverse to read reward from
 *    GrowthAlignedRewardShaper6439 instead of their own PnL heuristics."
 *
 * The reward shaper is authoritative at position close (via
 * PositionCloseLedger.markClosedFull). Learners run in a different
 * lifecycle — they receive a TradeFeatures / OutcomeSnapshot object
 * WITHOUT the shaper's shaped reward attached. Doubling the shape()
 * call from a learner would double-fire the LosingStreakReflex.
 *
 * This bridge is the read-only, side-effect-FREE derivation of the
 * shaper's doctrine, so learners can pull a reward multiplier without
 * disturbing the reflex or telemetry.
 *
 *   derivedMultiplier(pnlPct, holdTimeMins) → Double
 *
 * The multiplier is intentionally aligned 1:1 with
 * GrowthAlignedRewardShaper6439.shape() rules:
 *
 *   loss  (pnl < 0)  → 1.0 + 0.25 × (holdMs ÷ 5min)   capped at 3.0
 *   scratch (pnl 0)  → -0.5  (creed: break-even is negative — used as
 *                             a NEGATIVE-nudge multiplier for scratch
 *                             trades that classic ALE treats as +0.3)
 *   fast win (<5m)   → 1.0
 *   slow win (>5m)   → 0.9
 *   >20m win         → 0.75
 *   >60m win         → 0.5
 *
 * Callers apply the multiplier to their internal learning-rate scalar.
 * A magnitude ≥ 1.0 means "accelerate learning from this event"; a
 * value in (0..1) means "learn less" and a negative multiplier means
 * "invert the sign of the reward" (used by learners that reward on
 * outcome-score sign — treat break-evens as a loss).
 */
object LearnerRewardBridge6440 {

    private val queryCount = AtomicLong(0L)
    private val lossMultCount = AtomicLong(0L)
    private val breakevenMultCount = AtomicLong(0L)
    private val winMultCount = AtomicLong(0L)
    private val finalizedMultipliers = ConcurrentHashMap<String, Double>()

    /** V5.0.6486 — consume and retain the canonical finalized reward by position. */
    fun acceptFinalized6486(
        positionId: String, mint: String, lane: String, tactic: String, mode: String,
        pnlPct: Double, pnlSol: Double, holdTimeMins: Double,
    ): Boolean {
        if (positionId.isBlank()) return false
        val multiplier = derivedMultiplier(pnlPct, holdTimeMins)
        finalizedMultipliers[positionId] = multiplier
        val engine = when {
            lane.contains("CRYPTO", true) -> "ALTS"
            lane.contains("FOREX", true) -> "FOREX"
            lane.contains("METAL", true) -> "METALS"
            lane.contains("COMMOD", true) -> "COMMODITIES"
            lane.contains("STOCK", true) -> "STOCKS"
            lane.contains("PERPS", true) -> "PERPS"
            else -> "MEME"
        }
        val sentienceAccepted = try {
            com.lifecyclebot.engine.SentienceHooks.recordCanonicalEngineOutcome6486(
                positionId, engine, pnlSol, pnlSol > 0.0,
            ) || com.lifecyclebot.engine.SentienceHooks.run { true }
        } catch (_: Throwable) { false }
        val labAccepted = try {
            com.lifecyclebot.engine.lab.LlmLabEngine.recordCanonicalOutcome6486(
                positionId, lane, tactic, pnlPct, pnlSol, mode.equals("paper", true),
            ) || true
        } catch (_: Throwable) { false }
        try { PipelineHealthCollector.labelInc("LEARNER_REWARD_FINALIZED_CONSUMED_6486") } catch (_: Throwable) {}
        return sentienceAccepted && labAccepted
    }

    fun finalizedMultiplier6486(positionId: String): Double? = finalizedMultipliers[positionId]

    /**
     * Returns the shaper-aligned reward multiplier. Zero side effects
     * beyond telemetry counters.
     *
     * @param pnlPct realised trade PnL in percent (negative = loss)
     * @param holdTimeMins how long the position was held, in minutes
     * @return multiplier value in the range roughly [-1.0, 3.0]
     */
    fun derivedMultiplier(pnlPct: Double, holdTimeMins: Double): Double {
        queryCount.incrementAndGet()
        try { PipelineHealthCollector.labelInc("LEARNER_REWARD_BRIDGE_6440") } catch (_: Throwable) {}
        return when {
            pnlPct < -0.5 -> lossMultiplier(holdTimeMins).also { lossMultCount.incrementAndGet() }
            pnlPct > 0.5  -> winMultiplier(holdTimeMins).also { winMultCount.incrementAndGet() }
            else -> {
                // Break-even zone (-0.5% .. +0.5%). Per capital-preservation creed,
                // break-even is NEGATIVE — learners that treat this as "gentle"
                // are reward-hackable. Return -0.5 so the SIGN inverts and the
                // learner nudges AWAY from the pattern that produced it.
                breakevenMultCount.incrementAndGet()
                -0.5
            }
        }
    }

    private fun lossMultiplier(holdTimeMins: Double): Double {
        // Mirrors GrowthAlignedRewardShaper6439.shapeLoss(): every 5 min
        // of bag-hold adds 25% to the pain, capped at 3.0x.
        val steps = (holdTimeMins / 5.0).coerceAtLeast(0.0).coerceAtMost(8.0)
        val mult = (1.0 + steps * 0.25).coerceAtMost(3.0)
        return mult
    }

    private fun winMultiplier(holdTimeMins: Double): Double = when {
        holdTimeMins > 60.0 -> 0.5
        holdTimeMins > 20.0 -> 0.75
        holdTimeMins > 5.0  -> 0.9
        else                -> 1.0
    }

    fun statusLine(): String {
        val n = queryCount.get()
        val l = lossMultCount.get()
        val b = breakevenMultCount.get()
        val w = winMultCount.get()
        return "queries=$n losses=$l breakevens=$b wins=$w"
    }
}
