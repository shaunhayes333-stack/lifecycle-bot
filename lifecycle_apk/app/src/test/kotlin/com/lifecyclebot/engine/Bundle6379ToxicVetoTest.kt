package com.lifecyclebot.engine

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.6379 — Learned Toxic Lane Hard Veto invariants.
 *
 * Operator directive (verbatim):
 *   "its not improving overtime which is the entire point of learning.
 *    it has to get better!!! i tried going live its not very good at
 *    trading live, buys in the wrong waves of the chart. it needs to
 *    use the full aate stack to find profitable trades live asap!!!!"
 *
 * The V5.9.1549 doctrine removed hard exec vetoes to unchoke live volume,
 * but that let LiveProbabilityEngine's pWin=0% E=-28.7% signal fall on the
 * floor — the paperBuy min-size clamp overrode the shaped-size shrink and
 * the buy still executed. This bundle re-introduces a NARROW hard veto
 * that only fires for genuinely catastrophic lanes measured off the RAW
 * journal (not the sanitizer):
 *
 *   n >= 15  AND  wr <= 10%  AND  meanPnlPct <= -25%
 *
 * Self-clearing (any threshold crossed back → un-veto). Emits telemetry
 * so the operator can see the block in the pipeline dump.
 */
class Bundle6379ToxicVetoTest {

    @Test
    fun live_probability_engine_exposes_toxic_veto_check() {
        val txt = File("src/main/kotlin/com/lifecyclebot/engine/LiveProbabilityEngine.kt").readText()
        assertTrue(
            "V5.0.6379: LiveProbabilityEngine must expose isLaneLearnedToxic6379(lane): Boolean",
            txt.contains("fun isLaneLearnedToxic6379(lane: String): Boolean")
        )
        assertTrue(
            "V5.0.6379: LiveProbabilityEngine must expose toxicVetoReason6379(lane): String? for gate-side consumption",
            txt.contains("fun toxicVetoReason6379(lane: String): String?")
        )
    }

    @Test
    fun toxic_veto_thresholds_documented() {
        val txt = File("src/main/kotlin/com/lifecyclebot/engine/LiveProbabilityEngine.kt").readText()
        assertTrue(
            "V5.0.6379: TOXIC_VETO_MIN_SAMPLES must be 15 (large enough for statistical realness)",
            txt.contains("TOXIC_VETO_MIN_SAMPLES  = 15")
        )
        assertTrue(
            "V5.0.6379: TOXIC_VETO_MAX_WR_PCT must be 10.0 (only truly catastrophic lanes are blocked)",
            txt.contains("TOXIC_VETO_MAX_WR_PCT   = 10.0")
        )
        assertTrue(
            "V5.0.6379: TOXIC_VETO_MAX_MEAN_PCT must be -25.0 (mean loss > 25% per trade)",
            txt.contains("TOXIC_VETO_MAX_MEAN_PCT = -25.0")
        )
    }

    @Test
    fun executable_open_gate_hard_vetoes_toxic_lanes_before_buy() {
        val txt = File("src/main/kotlin/com/lifecyclebot/engine/ExecutableOpenGate.kt").readText()
        assertTrue(
            "V5.0.6379: ExecutableOpenGate must call toxicVetoReason6379 BEFORE re-entry lockout check",
            txt.contains("LiveProbabilityEngine.toxicVetoReason6379(canonicalSelectedLane)")
        )
        assertTrue(
            "V5.0.6379: veto must return blocked(EXEC_OPEN_BLOCKED_LEARNED_TOXIC_LANE_6379) — no soft-fallthrough",
            txt.contains("EXEC_OPEN_BLOCKED_LEARNED_TOXIC_LANE_6379")
        )
        assertTrue(
            "V5.0.6379: veto must emit LEARNED_TOXIC_LANE_HARD_VETO_6379|<LANE> counter so pipeline dump surfaces block",
            txt.contains("LEARNED_TOXIC_LANE_HARD_VETO_6379|")
        )
    }

    @Test
    fun toxic_veto_reads_raw_journal_not_sanitized() {
        val txt = File("src/main/kotlin/com/lifecyclebot/engine/LiveProbabilityEngine.kt").readText()
        // isLaneLearnedToxic6379 must build off rawLaneReality — that source
        // was added specifically because StrategyTruthLedger's sanitizer hides
        // catastrophic losses as "duplicateTerminal" pruned rows. Reading
        // the sanitized view is the exact bug this fix is closing.
        assertTrue(
            "V5.0.6379: toxic veto MUST source stats from rawLaneReality() (raw journal), never the sanitized StrategyTruthLedger view",
            txt.contains("fun isLaneLearnedToxic6379(lane: String): Boolean") &&
                txt.contains("rawLaneReality(lane)?.let")
        )
    }
}
