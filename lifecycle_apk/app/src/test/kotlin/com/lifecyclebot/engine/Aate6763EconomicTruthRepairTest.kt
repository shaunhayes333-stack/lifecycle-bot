package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.CatastrophicLaneAutoVeto6763
import com.lifecyclebot.engine.truth.PostSealAuthorityInvariants6760
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.6763 §ECONOMIC_TRUTH_REPAIR — regression fence for the four
 * source-level repairs mandated by the operator's V5.0.6761 diagnostic:
 *
 *   §DNA_TAXONOMY_FIX — LIVE_WIN_DNA_CAPTURED_6238 must never fire on a
 *      negative-pnl close; class-split label emitted per pnl sign.
 *   §EXIT_REASON_ECONOMIC_TRUTH — STRICT_SL exit reason must annotate
 *      the mark staleness so the terminal record reflects the true
 *      execution conditions.
 *   §CATASTROPHIC_LANE_AUTO_VETO — a lane with proven catastrophic
 *      evidence (≥20 clean same-mode closes, WR ≤ 8%, meanPnl ≤ -20%)
 *      hard-vetoes further admissions. LaneExpectancyDamper stays size-
 *      only (operator doctrine #86); the new authority ADDS a veto path.
 *   §POST_SEAL_ALLOWLIST — the new SAFETY_HARD_VETO_LANE_CATASTROPHIC_6763
 *      reason must remain authoritative post-FDG-allow so a sealed
 *      admission cannot bypass the catastrophic veto.
 */
class Aate6763EconomicTruthRepairTest {

    // ---------- §DNA_TAXONOMY_FIX ----------

    @Test fun dna_capture_never_labels_a_loss_as_a_win() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/LiveWinDNAStore.kt").readText()
        assertTrue(
            "capture() must split the label by pnl sign",
            src.contains("classTag6763 = when {") &&
                src.contains("pnlPct > 0.0 -> \"WIN\"") &&
                src.contains("pnlPct < 0.0 -> \"LOSS\""),
        )
        assertTrue(
            "class-tagged forensic label must be emitted",
            src.contains("LIVE_TRADE_DNA_\${classTag6763}_CAPTURED_6763"),
        )
        assertTrue(
            "legacy LIVE_WIN_DNA_CAPTURED_6238 must be emitted ONLY on positive pnl",
            src.contains("if (pnlPct > 0.0) {") &&
                src.contains("LIVE_WIN_DNA_CAPTURED_6238"),
        )
    }

    // ---------- §EXIT_REASON_ECONOMIC_TRUTH ----------

    @Test fun strict_sl_reason_annotates_mark_staleness() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        assertTrue(
            "STRICT_SL must compute mark age before dispatching doSell",
            src.contains("markAgeMs6763 = try {") &&
                src.contains("System.currentTimeMillis() - ts.lastPriceUpdate"),
        )
        assertTrue(
            "STRICT_SL must annotate reason with MARK_STALE_<age>s when age > 15s",
            src.contains("STRICT_SL_\${hardFloor.toInt()}_MARK_STALE_\${markAgeMs6763 / 1000}s"),
        )
        assertTrue(
            "diagnostic label STRICT_SL_MARK_STALE_ECONOMIC_TRUTH_6763 must be emitted",
            src.contains("STRICT_SL_MARK_STALE_ECONOMIC_TRUTH_6763"),
        )
    }

    // ---------- §CATASTROPHIC_LANE_AUTO_VETO ----------

    @Test fun catastrophic_veto_returns_ok_on_blank_lane_and_unknown_lane() {
        val decisionBlank = CatastrophicLaneAutoVeto6763.evaluate("paper", null)
        assertFalse("blank lane must not be vetoed", decisionBlank.vetoed)
        val decisionUnknown = CatastrophicLaneAutoVeto6763.evaluate("paper", "MADE_UP_LANE_6763")
        assertFalse("unknown lane must not be vetoed", decisionUnknown.vetoed)
    }

    @Test fun catastrophic_veto_reason_prefix_matches_post_seal_authority() {
        // The authority emits SAFETY_HARD_VETO_LANE_CATASTROPHIC_6763. This
        // MUST be recognised by PostSealAuthorityInvariants6760 so a sealed
        // admission cannot bypass the veto.
        val safetyReason = "SAFETY_HARD_VETO_LANE_CATASTROPHIC_6763"
        assertTrue(
            "$safetyReason must remain authoritative post-FDG-allow",
            PostSealAuthorityInvariants6760.mayBlockAfterFdgAllow(safetyReason),
        )
    }

    @Test fun executable_open_gate_wires_catastrophic_veto_before_internal_evaluation() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/ExecutableOpenGate.kt").readText()
        assertTrue(
            "canOpenExecutablePosition(mint,...) must call CatastrophicLaneAutoVeto6763.evaluate before canOpenExecutablePositionInternal",
            src.contains("CatastrophicLaneAutoVeto6763.evaluate(mode, lane)") &&
                src.contains("EXEC_OPEN_BLOCKED_LANE_CATASTROPHIC_6763"),
        )
        assertTrue(
            "veto path must produce an OpenVerdict with allowed=false and the SAFETY_HARD_VETO_LANE_CATASTROPHIC_6763 reason",
            src.contains("reason = veto6763.reason,") &&
                src.contains("allowed = false,"),
        )
    }
}
