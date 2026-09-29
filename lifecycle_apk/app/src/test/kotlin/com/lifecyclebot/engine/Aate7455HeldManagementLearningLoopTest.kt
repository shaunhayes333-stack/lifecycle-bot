package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class Aate7455HeldManagementLearningLoopTest {
    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/$rel").readText()

    @Test fun canonical_hold_path_uses_public_mode_parameter_authority() {
        val s = src("engine/HoldingLogicLayer.kt")
        assertTrue(s.contains("val params = getHoldParams(mode)"))
        assertTrue(s.contains("HOLD_PARAMS_CANONICAL_READ_7455"))
    }

    @Test fun terminal_learning_shapes_tp_hold_and_partial_management() {
        val s = src("engine/HoldingLogicLayer.kt")
        assertTrue(s.contains("val exitTune7455 = try { LiveStrategyTuner.adjustment(mode) }"))
        assertTrue(s.contains("exitTune7455?.tpMult"))
        assertTrue(s.contains("exitTune7455?.holdMult"))
        assertTrue(s.contains("exitTune7455?.partialTriggerMult"))
        assertTrue(s.contains("targetProfit6091 = baseTarget6684 * ssiExitPatience6091 * tpMult7455"))
        assertTrue(s.contains("fluidMaxHold * holdMult7455"))
        assertTrue(s.contains("rawScaleOutLevel * ssiExitPatience6091 * partialMult7455"))
        assertTrue(s.contains("HOLD_EXIT_TUNER_CONSUMED_7455"))
    }

    @Test fun learned_hold_policy_cannot_bypass_hard_stop() {
        val s = src("engine/HoldingLogicLayer.kt")
        val stop = s.indexOf("if (currentPnlPct <= activeStopLoss6684)")
        val fluid = s.indexOf("if (holdTimeMinutes > tunedFluidMaxHold7455)")
        assertTrue(stop >= 0)
        assertTrue(fluid > stop)
        assertFalse(s.contains("activeStopLoss6684 * holdMult7455"))
        assertFalse(s.contains("activeStopLoss6684 * tpMult7455"))
    }

    @Test fun duplicate_symbolic_exit_pressure_is_not_added_to_meme_hot_path() {
        val s = src("engine/HoldingLogicLayer.kt")
        assertFalse(s.contains("SmartExitOptimizer.getExitPressure("))
        val audit = File("../../audits/aate_strategy_unwired_worklist_2026-09-29.md")
        if (audit.exists()) {
            assertTrue(audit.readText().contains("ALIAS_REDUNDANT_FOR_MEME_HOLD"))
        }
    }
}
