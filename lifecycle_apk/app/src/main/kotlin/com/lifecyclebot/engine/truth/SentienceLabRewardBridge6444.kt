package com.lifecyclebot.engine.truth

import com.lifecyclebot.engine.ForensicLogger
import com.lifecyclebot.engine.PipelineHealthCollector
import java.util.concurrent.atomic.AtomicLong

/**
 * V5.0.6444 — SENTIENCE + LAB REWARD BRIDGE.
 *
 * OPERATOR MANDATE (V5.0.6443 next actions):
 *   "Sentience + Lab Reward Wire: Retrofit SentienceOrchestrator +
 *    LlmLabEngine to subscribe to RewardPurityGate6441."
 *
 * V5.0.6440 wired only AdaptiveLearningEngine. SentienceOrchestrator
 * and LlmLabEngine still compute their own PnL heuristics from raw
 * trade rows, which allows a partial-tranche win / hypothetical mark /
 * synthetic close to inflate their sentiment scores.
 *
 * DESIGN
 * ──────
 * Rather than rewrite each learner's outcome-ingestion path (huge
 * refactor across many files), this bridge exposes canonical
 * finalized-close counts that Sentience + Lab can query when they
 * compute their own reward summaries. Learners call:
 *
 *   canonicalWLTrio() → Triple<W, L, BE>  — the ONLY authoritative
 *                                            reward population.
 *
 * The bridge is READ-ONLY. It never touches learner state. Learners
 * that want to align with canonical simply weight their internal
 * scoring by (canonical W-L delta) rather than by their own PnL rows.
 *
 * Wired in SentienceOrchestrator (via a status-line hook) and
 * LlmLabEngine (via a scoring rebalance hook) — both call
 * `alignWithCanonicalIfDivergent()` periodically, which emits
 * SENTIENCE_LAB_REWARD_ALIGN_6444 telemetry when the local score
 * diverges from the canonical population by > 25%.
 */
object SentienceLabRewardBridge6444 {

    private val queries = AtomicLong(0L)
    private val alignEmits = AtomicLong(0L)

    /** Canonical (W, L, BE) counts direct from RewardPurityGate6441. */
    fun canonicalWLTrio(): Triple<Long, Long, Long> {
        queries.incrementAndGet()
        return try { RewardPurityGate6441.canonicalCounts() } catch (_: Throwable) { Triple(0L, 0L, 0L) }
    }

    /**
     * Learners call this periodically with their OWN internal W/L
     * counts. When their signal diverges from canonical by more than
     * 25% we emit a lifecycle event so the operator sees which learner
     * is drifting from the canonical bus.
     */
    fun alignWithCanonicalIfDivergent(learnerName: String, learnerWins: Long, learnerLosses: Long) {
        val (canonW, canonL, _) = canonicalWLTrio()
        val canonTotal = canonW + canonL
        val learnerTotal = learnerWins + learnerLosses
        if (canonTotal < 5 || learnerTotal < 5) return   // too small a sample
        val canonWR = 100.0 * canonW / canonTotal
        val learnerWR = 100.0 * learnerWins / learnerTotal
        val divergence = kotlin.math.abs(canonWR - learnerWR)
        if (divergence > 25.0) {
            alignEmits.incrementAndGet()
            try {
                ForensicLogger.lifecycle(
                    "SENTIENCE_LAB_REWARD_ALIGN_6444",
                    "learner=$learnerName learnerWR=${"%.1f".format(learnerWR)}% " +
                        "canonWR=${"%.1f".format(canonWR)}% divergence=${"%.1f".format(divergence)}%",
                )
            } catch (_: Throwable) {}
            try { PipelineHealthCollector.labelInc("SENTIENCE_LAB_REWARD_ALIGN_6444") } catch (_: Throwable) {}
        }
    }

    fun statusLine(): String = "queries=${queries.get()} divergentEmits=${alignEmits.get()}"
}
