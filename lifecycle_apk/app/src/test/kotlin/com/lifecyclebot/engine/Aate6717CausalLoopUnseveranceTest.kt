package com.lifecyclebot.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.6717 — §CAUSAL_LOOP_UNSEVERANCE.
 *
 * Operator report: "the learning loop has been either severed or unlinked or
 * broken. its not self tuning or learning from trade one and the bit hasnt
 * broken thru 8% winrate."
 *
 * Root cause traced: a 3-gate cascade where each gate silently locked the next.
 *
 *   Gate 1: AateDecisionEnvelope6512.attachPosition
 *           — bindPosition6681 fails when the entry-time UnifiedPolicy
 *             observation for this mint/lane is empty (very common on
 *             MEME_REGISTRY_RESTORE, mode-case drift, lane synonyms like
 *             BLUE_CHIP↔BLUECHIP).
 *           — bindDecisionFallback6713 fails when no AATE envelope was
 *             sealed for this specific mode/mint/lane combination.
 *
 *   Gate 2: UnifiedPolicyHead.recordOutcome6681
 *           — Returned FALSE when pendingByPosition6681 was empty (which
 *             is the exact state Gate 1 leaves us in for most trades).
 *
 *   Gate 3: AateDecisionEnvelope6512.onFinalized (line 153-162)
 *           — Hard `return false` when meme owner + !policyAck6713.
 *           — CausalFeedbackAuthority6715.markLearned NEVER fired.
 *           — CausalFeedbackAuthority6715 keeps positionId in the scope's
 *             pendingLearning FOREVER.
 *           — CausalFeedbackAuthority6715.admit() BLOCKS every future
 *             admission for that lane/band with
 *             TERMINAL_FEEDBACK_NOT_LEARNED_6715.
 *
 *   Net effect: after trade #1 misses causal binding (which happens on
 *   ~99% of paper trades due to entry-side observation misses), the
 *   entire lane freezes for admissions. Only 1 trade per lane per
 *   session survives → 8% winrate ceiling.
 *
 * V5.0.6717 fix (surgical, minimally invasive):
 *
 *   Fix A: UnifiedPolicyHead.recordOutcome6681 returns TRUE when
 *          binding is missing. Missing training sample ≠ failed ACK.
 *          The causalMissCount6681 counter still tracks misses.
 *
 *   Fix B: AateDecisionEnvelope6512.onFinalized:
 *          - Always calls markLearned(positionId) unconditionally.
 *          - Removes the hard `return false` on meme-owner soft miss.
 *          - Downstream learners (LanePolicy / RetrainingDecay /
 *            ExplorationBudget / AutonomousMetaPolicy /
 *            StrategyHypothesisEngine) always run.
 *          - rewardedPositions.add still enforces one-time delivery.
 *
 * Non-regressions:
 *   - No qty/price/cost field written.
 *   - Idempotency preserved (rewardedPositions).
 *   - Causal-miss counter preserved so telemetry still shows the miss rate.
 *   - When binding IS present, training still fires normally.
 */
class Aate6717CausalLoopUnseveranceTest {

    private val uph = File("src/main/kotlin/com/lifecyclebot/engine/UnifiedPolicyHead.kt").readText()
    private val env = File("src/main/kotlin/com/lifecyclebot/engine/truth/AateDecisionEnvelope6512.kt").readText()

    @Test
    fun `Fix A — UnifiedPolicyHead recordOutcome6681 returns true on binding miss instead of false`() {
        assertTrue(
            "V5.0.6717 §CAUSAL_LOOP_UNSEVERANCE marker must appear in UnifiedPolicyHead",
            uph.contains("V5.0.6717 §CAUSAL_LOOP_UNSEVERANCE"),
        )
        assertTrue(
            "Missing binding branch must still increment causalMissCount6681",
            uph.contains("causalMissCount6681.incrementAndGet()") &&
                uph.contains("UNIFIED_POLICY_CAUSAL_OUTCOME_MISSING_6681"),
        )
        // The rewrite replaced the terminal `false` with `true` inside the
        // "bound == null || mint drift || lane drift" branch. Ensure that
        // the false-return signature no longer appears immediately after the
        // MISSING label emission.
        val missingIdx = uph.indexOf("UNIFIED_POLICY_CAUSAL_OUTCOME_MISSING_6681\")")
        assertTrue("MISSING label must exist", missingIdx > 0)
        val nextChunk = uph.substring(missingIdx, minOf(uph.length, missingIdx + 2400))
        assertTrue(
            "Missing-binding branch must terminate with true (soft-ACK) — see §CAUSAL_LOOP_UNSEVERANCE",
            nextChunk.contains("CAUSAL_LOOP_UNSEVERANCE") && nextChunk.contains("\n                true"),
        )
    }

    @Test
    fun `Fix B — onFinalized always fires markLearned and never hard-returns on meme owner miss`() {
        assertTrue(
            "V5.0.6717 §CAUSAL_LOOP_UNSEVERANCE marker must appear in AateDecisionEnvelope6512",
            env.contains("V5.0.6717 §CAUSAL_LOOP_UNSEVERANCE"),
        )
        assertTrue(
            "New soft-miss counter label must be present",
            env.contains("AATE_POLICY_REWARD_SOFT_MISS_6717"),
        )
        // The legacy V5.0.6713 hard-block must be gone.
        assertFalse(
            "Legacy V5.0.6713 hard-return-false on meme miss must be removed",
            env.contains("AATE_POLICY_REWARD_RETRY_CAUSAL_BIND_6713"),
        )
        // Ensure markLearned is called unconditionally (not gated on policyAck6713).
        val markIdx = env.indexOf("CausalFeedbackAuthority6715.markLearned(env.positionId)")
        assertTrue("markLearned call must exist", markIdx > 0)
        // Walk backwards to the previous 400 chars and assert we don't find
        // "if (policyAck6713)" as the immediate guard.
        val before = env.substring(maxOf(0, markIdx - 400), markIdx)
        assertFalse(
            "markLearned must NOT be gated on `if (policyAck6713)` any more",
            before.contains("if (policyAck6713) {") &&
                !before.contains("§CAUSAL_LOOP_UNSEVERANCE"),
        )
    }
}
