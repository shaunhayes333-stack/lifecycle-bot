package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.BleederLaneProbation6747
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * V5.0.6747 — Operator trade-quality tuning batch.
 *
 * Four source-level landings from the 6746 diagnostic (items #1–#4
 * of the operator's ordered priority list):
 *
 *   §1 PROMOTION_QUALITY_GATES — StrategyHypothesisEngine now
 *      refuses a size-bump promotion when the arm's pWin < 25%
 *      or pRug > 50%. Kills the 17% pWin / 64% pRug → sizeBias 1.10
 *      smoking gun.
 *   §2 REGIME_FLOOR_AUTHORITATIVE — RegimeDetector.scoreFloorDelta()
 *      is now applied at the sealed admission check in
 *      ExecutableOpenGate. CHOP +10 survives to the veto.
 *   §3 EXPLORATION_DAMPER_ON_WR_COLLAPSE — DUST_PROBE and
 *      ZERO_SIGNAL_PROBE emissions sampled 1-in-N in CHOP/DUMP
 *      regimes so the learner stops drowning in WAIT candidates
 *      while WR is collapsed.
 *   §4 BLEEDER_LANE_PROBATION — new authority tracks per-lane
 *      sliding-window WR and refuses normal-size admissions when
 *      the window WR falls under 20% over ≥15 trades. Only tiny
 *      probes (≤ 0.02 SOL) bypass so recovery evidence can still
 *      accumulate. PROJECT_SNIPER exempt (only lane at acceptable
 *      hit-rate per operator).
 */
class Aate6747TradeQualityBatchTest {

    @Before
    fun reset() {
        BleederLaneProbation6747.resetForTest()
    }

    // ─── §1 PROMOTION_QUALITY_GATES ──────────────────────────────────

    @Test
    fun `hypothesis engine gates promotion on pWin and pRug`() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/StrategyHypothesisEngine.kt").readText()
        assertTrue(
            "engine MUST define MIN_PWIN_PROMOTE_6747",
            src.contains("MIN_PWIN_PROMOTE_6747") && src.contains("0.25"),
        )
        assertTrue(
            "engine MUST define MAX_PRUG_PROMOTE_6747",
            src.contains("MAX_PRUG_PROMOTE_6747") && src.contains("0.50"),
        )
        assertTrue(
            "engine MUST define RUG_PNL_THRESHOLD_6747 at -80%",
            src.contains("RUG_PNL_THRESHOLD_6747") && src.contains("-80.0"),
        )
        assertTrue(
            "Arm MUST track wins/losses/rugs so the promotion gate can read pWin/pRug",
            src.contains("var wins: Long") &&
                src.contains("var losses: Long") &&
                src.contains("var rugs: Long"),
        )
        assertTrue(
            "Arm MUST expose pWin6747 and pRug6747 accessors",
            src.contains("val pWin6747") && src.contains("val pRug6747"),
        )
        assertTrue(
            "proveCtrlEdge promotion MUST reject when quality floors fail",
            src.contains("proveCtrlQualityOk6747") &&
                src.contains("HYPOTHESIS_PROVEN_BASELINE_REJECTED_QUALITY_6747"),
        )
        assertTrue(
            "variant promotion MUST include quality gate in promoteOk",
            src.contains("variantQualityOk6747") &&
                src.contains("promoteOk = t >= PROMOTE_T") &&
                src.contains("variantQualityOk6747"),
        )
    }

    // ─── §2 REGIME_FLOOR_AUTHORITATIVE ───────────────────────────────

    @Test
    fun `executable open gate applies regime score floor at final admission`() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/ExecutableOpenGate.kt").readText()
        assertTrue(
            "gate MUST expose REGIME_BASE_MIN_SCORE_6747 constant",
            src.contains("REGIME_BASE_MIN_SCORE_6747 = 15"),
        )
        assertTrue(
            "gate MUST call RegimeDetector.scoreFloorDelta() at admission",
            src.contains("RegimeDetector.scoreFloorDelta()"),
        )
        assertTrue(
            "gate MUST emit an authoritative regime-floor veto label",
            src.contains("EXEC_OPEN_BLOCKED_REGIME_FLOOR_6747"),
        )
        // Order matters: the veto must appear BEFORE the internal open call
        // so a floor-violating score cannot silently reach the sizing path.
        val floorIdx = src.indexOf("EXEC_OPEN_BLOCKED_REGIME_FLOOR_6747")
        val internalIdx = src.indexOf("return canOpenExecutablePositionInternal(")
        assertTrue("floor veto MUST appear in source before the internal open call",
            floorIdx > 0 && internalIdx > 0 && floorIdx < internalIdx)
    }

    // ─── §3 EXPLORATION_DAMPER_ON_WR_COLLAPSE ────────────────────────

    @Test
    fun `probe emissions are sampled in CHOP and DUMP regimes`() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/ExecutableOpenGate.kt").readText()
        assertTrue(
            "gate MUST expose probeShouldEmit6747",
            src.contains("fun probeShouldEmit6747("),
        )
        assertTrue(
            "damper MUST sample in CHOP and DUMP (WR-collapsed regimes)",
            src.contains("Regime.CHOP") &&
                src.contains("Regime.DUMP"),
        )
        assertTrue(
            "damper MUST emit a dedicated skip label",
            src.contains("EXPLORATION_DAMPER_SKIPPED_6747"),
        )
        // Call sites in BotService.
        val botSrc = File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        assertTrue(
            "BotService DUST_PROBE emitter MUST consult the damper",
            botSrc.contains("EXPLORATION_DAMPED_DUST_PROBE_6747"),
        )
        assertTrue(
            "BotService ZERO_SIGNAL_PROBE emitter MUST consult the damper",
            botSrc.contains("EXPLORATION_DAMPED_ZERO_SIGNAL_6747"),
        )
    }

    // ─── §4 BLEEDER_LANE_PROBATION ───────────────────────────────────

    @Test
    fun `bleeder authority transitions in and out of probation on WR window`() {
        // Below-threshold window → probation.
        repeat(16) { BleederLaneProbation6747.onTradeClosed("EXPRESS", -5.0) }
        assertTrue("EXPRESS must be on probation after 16 losses",
            BleederLaneProbation6747.isOnProbation("EXPRESS"))
        // Regular size admission must refuse.
        val refuseReason = BleederLaneProbation6747.evaluate("EXPRESS", 0.05)
        assertNotNull("regular-size admission must be refused during probation", refuseReason)
        assertTrue(refuseReason!!.contains("BLEEDER_LANE_PROBATION_6747"))
        // Tiny probe admissions still allowed.
        assertNull("probe-size admission (≤ 0.02 SOL) must bypass probation",
            BleederLaneProbation6747.evaluate("EXPRESS", 0.015))
        // Push WR back over recovery floor.
        repeat(20) { BleederLaneProbation6747.onTradeClosed("EXPRESS", +25.0) }
        assertFalse("EXPRESS must exit probation once WR recovers past 30%",
            BleederLaneProbation6747.isOnProbation("EXPRESS"))
    }

    @Test
    fun `PROJECT_SNIPER is exempt from bleeder probation`() {
        repeat(30) { BleederLaneProbation6747.onTradeClosed("PROJECT_SNIPER", -25.0) }
        assertFalse("PROJECT_SNIPER must never enter probation (operator carve-out)",
            BleederLaneProbation6747.isOnProbation("PROJECT_SNIPER"))
        assertNull("PROJECT_SNIPER admissions must never be refused",
            BleederLaneProbation6747.evaluate("PROJECT_SNIPER", 0.10))
    }

    @Test
    fun `admission gate wires bleeder probation`() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/ExecutableOpenGate.kt").readText()
        assertTrue(
            "gate MUST invoke BleederLaneProbation6747.evaluate",
            src.contains("BleederLaneProbation6747.evaluate("),
        )
        assertTrue(
            "gate MUST emit a bleeder-refused label",
            src.contains("EXEC_OPEN_BLOCKED_BLEEDER_PROBATION_6747"),
        )
        // TacticSwitcher must feed outcomes into the probation window.
        val tacticSrc = File("src/main/kotlin/com/lifecyclebot/engine/learning/TacticSwitcher.kt").readText()
        assertTrue(
            "TacticSwitcher.onTradeClosed MUST feed BleederLaneProbation6747.onTradeClosed",
            tacticSrc.contains("BleederLaneProbation6747.onTradeClosed("),
        )
    }
}
