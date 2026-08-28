package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6576 §P0-3 — ONE CANONICAL ECONOMIC OUTCOME CLASSIFICATION.
 *
 * OPERATOR DIRECTIVE (verbatim):
 *   "Current learners disagree about the same closes.
 *    This corrupts adaptive learning.
 *    DO NOT allow each subsystem to independently decide WIN/LOSS/BREAKEVEN.
 *    Create/centralize one classification from canonical finalized economics."
 *
 * FORENSIC EVIDENCE (6573):
 *   RewardPurityGate     : 75 losses / 0 breakevens    (uses realizedPnlSol >0 WIN, <0 LOSS)
 *   PerformanceAnalytics : 0W / 7L / 67BE              (uses 0.5% WIN, -2.0% LOSS)
 *   GrowthRewardShaper   : 10 losses
 *   LearnerRewardBridge  : 10 breakevens               (uses +/-0.5%)
 *
 * Same closes → four contradictory classes. Adaptive learners polling any
 * one of these surfaces derive contradictory reward signals.
 *
 * ONE authoritative classifier for every terminal learner:
 *
 *   RewardPurityGate
 *   GrowthAlignedRewardShaper
 *   LearnerRewardBridge
 *   StrategyTruthLedger
 *   PerformanceAnalytics
 *   TacticSwitcher
 *   UnifiedPolicyHead
 *   StrategyHypothesisEngine
 *   CapitalPreservationCreed
 *   LosingStreakReflex
 *   ForwardOutcomeModel (EVEstimator)
 *   MemeCausalLearning
 *
 * BAND — symmetric ±0.5% per operator's own example. Matches LearnerRewardBridge's
 * existing ±0.5% doctrine (which the operator preserved). PerformanceAnalytics
 * (0.5%/-2.0% asymmetric) is realigned so all consumers agree.
 *
 * A BREAKEVEN may still receive a slightly negative reward for fees / capital
 * inefficiency / opportunity cost — but its OUTCOME CLASS remains BREAKEVEN
 * everywhere.
 */
object CanonicalOutcomeClassifier6576 {

    /** Symmetric breakeven band in percent (0.5 = 0.5%). */
    const val BREAKEVEN_BAND_PCT: Double = 0.5

    enum class Class { WIN, LOSS, BREAKEVEN }

    private val wins = AtomicLong(0L)
    private val losses = AtomicLong(0L)
    private val breakevens = AtomicLong(0L)
    private val divergences = AtomicLong(0L)

    /** Primary API — classify by realized return percent (canonical form). */
    fun classify(returnPct: Double): Class {
        val c = when {
            returnPct > BREAKEVEN_BAND_PCT -> Class.WIN
            returnPct < -BREAKEVEN_BAND_PCT -> Class.LOSS
            else -> Class.BREAKEVEN
        }
        when (c) {
            Class.WIN -> wins.incrementAndGet()
            Class.LOSS -> losses.incrementAndGet()
            Class.BREAKEVEN -> breakevens.incrementAndGet()
        }
        return c
    }

    /** Same as classify() but does not update tally — for divergence probes. */
    fun classifyReadonly(returnPct: Double): Class = when {
        returnPct > BREAKEVEN_BAND_PCT -> Class.WIN
        returnPct < -BREAKEVEN_BAND_PCT -> Class.LOSS
        else -> Class.BREAKEVEN
    }

    /** Convenience: classify from realized PnL SOL + cost basis SOL. */
    fun classifyPnl(realizedPnlSol: Double, costBasisSol: Double): Class {
        if (costBasisSol <= 0.0 || !costBasisSol.isFinite() || !realizedPnlSol.isFinite()) {
            return Class.BREAKEVEN
        }
        val pct = (realizedPnlSol / costBasisSol) * 100.0
        return classifyReadonly(pct)
    }

    /**
     * Divergence probe — any consumer that must classify internally should first
     * compare with this authority; a mismatch increments OUTCOME_CLASS_DIVERGENCE_6576.
     * Consumers that CANNOT be reformed to call classify() directly must at least
     * report their private classification here.
     */
    fun reportConsumerClass(positionId: String, consumer: String, callerClass: Class, returnPct: Double) {
        val canonical = classifyReadonly(returnPct)
        if (canonical != callerClass) {
            divergences.incrementAndGet()
            try {
                PipelineHealthCollector.labelInc("OUTCOME_CLASS_DIVERGENCE_6576")
                PipelineHealthCollector.labelInc("OUTCOME_CLASS_DIVERGENCE_${consumer}_6576")
                com.lifecyclebot.engine.ForensicLogger.lifecycle(
                    "OUTCOME_CLASS_DIVERGENCE_6576",
                    "positionId=$positionId consumer=$consumer callerClass=$callerClass canonical=$canonical returnPct=${"%.4f".format(returnPct)}"
                )
            } catch (_: Throwable) {}
        }
    }

    fun counts(): Triple<Long, Long, Long> = Triple(wins.get(), losses.get(), breakevens.get())
    fun divergenceCount(): Long = divergences.get()

    fun statusLine(): String {
        val (w, l, b) = counts()
        return "W=$w L=$l BE=$b div=${divergences.get()} band=±${BREAKEVEN_BAND_PCT}%"
    }

    internal fun resetForTest() {
        wins.set(0L); losses.set(0L); breakevens.set(0L); divergences.set(0L)
    }
}
