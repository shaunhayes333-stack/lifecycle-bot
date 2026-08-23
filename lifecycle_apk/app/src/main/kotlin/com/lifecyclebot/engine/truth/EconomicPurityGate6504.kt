package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6504 §10 — ECONOMIC PURITY GATE.
 *
 * OPERATOR MANDATE (verbatim):
 *
 *   "Any position with RUNNER_EXIT_BASIS_UNTRUSTED or quantity repair
 *    pending is excluded from:
 *      realized PnL
 *      WR/PF/EV
 *      tactic training
 *      reward shaper
 *      governor
 *      hypothesis engine
 *    until reconciled.
 *    Rebuild contaminated PAPER performance from immutable fills after
 *    repair.
 *    Do not treat TRUMP +7133.8% or derived BLUECHIP +1300% EV as
 *    canonical while basis is untrusted."
 *
 * DESIGN
 * ──────
 * Single read surface consulted by every analytics / learner ingress:
 *   `shouldExcludeFromAnalytics(mint, reason)` — boolean gate
 *
 * A mint enters the untrusted set on any of the following signals:
 *   • RUNNER_EXIT_BASIS_UNTRUSTED tag (marked by exit path)
 *   • QuantityInvariantAuthority6500.isQuarantined(mint) == true
 *   • LearningQuarantineGate6470.isQuarantined(mint) == true
 *   • FillLotLedger6504 assertMatches() fails (repair pending)
 *
 * Consumers should call `shouldExcludeFromAnalytics(mint)` BEFORE
 * feeding a terminal-close row into:
 *   • RewardPurityGate6441 (already gates its own; this is defence-in-depth)
 *   • StrategyTelemetry.recordTerminal
 *   • MathematicalEdgeEngine.captureTerminal
 *   • GrowthAlignedRewardShaper6439.shape
 *   • GovernorRecovery6388 signals
 *   • HypothesisEngine ingress
 */
object EconomicPurityGate6504 {

    private val untrusted = ConcurrentHashMap<String, UntrustedRecord>()
    private val queries = AtomicLong(0L)
    private val exclusions = AtomicLong(0L)

    data class UntrustedRecord(
        val mint: String,
        val reason: String,
        val markedAtMs: Long,
    )

    /**
     * Mark a mint economically untrusted. Idempotent — first reason
     * wins. Called from the sell/exit path when RUNNER_EXIT_BASIS_UNTRUSTED
     * fires or the FillLot invariant fails post-mutation.
     */
    fun markUntrusted(mint: String, reason: String) {
        if (mint.isBlank()) return
        val trimmed = reason.take(64)
        val prev = untrusted.putIfAbsent(
            mint, UntrustedRecord(mint, trimmed, System.currentTimeMillis()),
        )
        if (prev == null) {
            try {
                ForensicLogger.lifecycle(
                    "ECONOMIC_PURITY_MARK_UNTRUSTED_6504",
                    "mint=${mint.take(10)} reason=$trimmed",
                )
                PipelineHealthCollector.labelInc("ECONOMIC_PURITY_MARK_UNTRUSTED_6504")
            } catch (_: Throwable) {}
        }
    }

    /** Clear the untrusted mark ONCE the mint is fully reconciled. */
    fun clearUntrusted(mint: String) {
        if (mint.isBlank()) return
        if (untrusted.remove(mint) != null) {
            try {
                ForensicLogger.lifecycle(
                    "ECONOMIC_PURITY_CLEAR_UNTRUSTED_6504",
                    "mint=${mint.take(10)}",
                )
                PipelineHealthCollector.labelInc("ECONOMIC_PURITY_CLEAR_UNTRUSTED_6504")
            } catch (_: Throwable) {}
        }
    }

    /**
     * Fast-path read consulted by learners / analytics before ingesting
     * a terminal-close row. Combines the local untrusted set with the
     * upstream quarantines (Quantity + Historical).
     *
     * `emit=false` for hot paths to avoid log spam; the aggregate
     * `exclusions` counter still ticks.
     */
    fun shouldExcludeFromAnalytics(mint: String, emit: Boolean = false): Boolean {
        queries.incrementAndGet()
        if (mint.isBlank()) return false
        val local = untrusted.containsKey(mint)
        val invariantBroken = try {
            QuantityInvariantAuthority6500.isQuarantined(mint)
        } catch (_: Throwable) { false }
        val historical = try {
            LearningQuarantineGate6470.isQuarantined(positionId = null, mint = mint)
        } catch (_: Throwable) { false }
        val excluded = local || invariantBroken || historical
        if (excluded) {
            exclusions.incrementAndGet()
            if (emit) {
                try {
                    ForensicLogger.lifecycle(
                        "ECONOMIC_PURITY_EXCLUSION_6504",
                        "mint=${mint.take(10)} local=$local invariant=$invariantBroken historical=$historical",
                    )
                    PipelineHealthCollector.labelInc("ECONOMIC_PURITY_EXCLUSION_6504")
                } catch (_: Throwable) {}
            }
        }
        return excluded
    }

    fun size(): Int = untrusted.size

    fun statusLine(): String =
        "untrustedMints=${untrusted.size} queries=${queries.get()} exclusions=${exclusions.get()}"

    internal fun clearForTest() {
        untrusted.clear()
        queries.set(0L)
        exclusions.set(0L)
    }
}
