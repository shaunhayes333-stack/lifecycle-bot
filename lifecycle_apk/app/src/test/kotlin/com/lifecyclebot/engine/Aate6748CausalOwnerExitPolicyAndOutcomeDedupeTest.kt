package com.lifecyclebot.engine

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.6748 — Trade-quality tuning batch part 2 (items #5, #6, #7
 * from the operator's 6746 diagnostic).
 *
 *   §5 CAUSAL_OWNER_ATTRIBUTION_REPAIR — when the AATE envelope is
 *      missing, fall back to LaneAttributionLedger6427 for the
 *      definitive owner-lane stamp and drive UnifiedPolicyHead
 *      bindDecisionFallback6713 so specialist learning still fires
 *      against the real owner.
 *   §6 EXIT_TUNER_RESOLVED_AUTHORITY — LaneExitTuner's closed-loop
 *      output and LaneStrategyReplay's replay-bias output no longer
 *      multiply. Closed-loop is authoritative once matured
 *      (n >= MIN_SAMPLE); replay is only consulted during bootstrap.
 *   §7 PER_CANDIDATE_LEARNING_COALESCE — a settled mint counts as
 *      exactly one hypothesis-engine sample; duplicate settlement
 *      events (recovery replay, terminal reconstruction) are deduped.
 */
class Aate6748CausalOwnerExitPolicyAndOutcomeDedupeTest {

    // ─── §5 CAUSAL_OWNER_ATTRIBUTION_REPAIR ─────────────────────────

    @Test
    fun `AateDecisionEnvelope onFinalized has ledger-fallback attribution`() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/truth/AateDecisionEnvelope6512.kt").readText()
        assertTrue(
            "onFinalized MUST consult LaneAttributionLedger6427 as fallback owner",
            src.contains("LaneAttributionLedger6427.getEntryLane(env.positionId)"),
        )
        assertTrue(
            "onFinalized MUST call bindDecisionFallback6713 on the ledger owner",
            src.contains("UnifiedPolicyHead.bindDecisionFallback6713(") &&
                src.contains("ownerLane = ledgerLane6747"),
        )
        assertTrue(
            "onFinalized MUST emit a dedicated telemetry label so the fallback rate is observable",
            src.contains("CAUSAL_OWNER_ATTRIBUTION_LEDGER_FALLBACK_6747"),
        )
        // Ledger fallback MUST be guarded by env.lane match to prevent
        // cross-lane misattribution (the exact defect §5 is repairing).
        assertTrue(
            "ledger-fallback MUST require ledger lane == env.lane before binding",
            src.contains("ledgerLane6747.equals(env.lane, true)"),
        )
    }

    // ─── §6 EXIT_TUNER_RESOLVED_AUTHORITY ───────────────────────────

    @Test
    fun `LaneExitTuner resolves closed-loop OR replay bias, never both simultaneously`() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/learning/LaneExitTuner.kt").readText()
        // Closed-loop maturity is the switch between the two authorities.
        assertTrue(
            "getTpMult MUST branch on closed-loop maturity",
            src.contains("closedLoopMature") &&
                src.contains("laneSt.window.size >= MIN_SAMPLE"),
        )
        // The regression: the previous impl multiplied both sources.
        // Assert the multiply chain no longer exists in the getters.
        val tpGetterIdx = src.indexOf("fun getTpMult(")
        val slGetterIdx = src.indexOf("fun getSlMult(")
        val tpGetterBody = src.substring(tpGetterIdx, kotlin.math.min(tpGetterIdx + 700, src.length))
        val slGetterBody = src.substring(slGetterIdx, kotlin.math.min(slGetterIdx + 700, src.length))
        val forbiddenPattern = "(lanes[key]?.tpMult ?: 1.0) * (replayBiasByLane[key]?.tpMult ?: 1.0)"
        assertTrue(
            "getTpMult MUST NOT multiply closed-loop × replay (they are two authorities, not two factors)",
            !tpGetterBody.contains(forbiddenPattern),
        )
        assertTrue(
            "getSlMult MUST NOT multiply closed-loop × replay",
            !slGetterBody.contains(forbiddenPattern.replace("tpMult", "slMult")),
        )
    }

    // ─── §7 PER_CANDIDATE_LEARNING_COALESCE ─────────────────────────

    @Test
    fun `hypothesis engine settles each mint exactly once`() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/StrategyHypothesisEngine.kt").readText()
        assertTrue(
            "engine MUST define a per-mint settle-once guard set",
            src.contains("settledOnceGuard6747"),
        )
        assertTrue(
            "recordOutcome MUST short-circuit when the guard rejects a duplicate mint settle",
            src.contains("if (!settledOnceGuard6747.add(mint))"),
        )
        assertTrue(
            "duplicate-settle path MUST emit a dedicated dedup label",
            src.contains("HYPOTHESIS_OUTCOME_DEDUPED_PER_CANDIDATE_6747"),
        )
        assertTrue(
            "reset() MUST clear the guard so long-running sessions don't leak",
            src.contains("settledOnceGuard6747.clear()"),
        )
    }
}
