package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Aate6696LearningHoldExitRepairTest {
    private fun src(path: String) = File("src/main/kotlin/com/lifecyclebot/$path").readText()

    @Test fun executed_soft_advisor_paper_trade_remains_trainable() {
        val s = src("engine/Executor.kt")
        assertTrue(s.contains("ELIGIBLE_SOFT_ADVISOR_OBSERVED_6696"))
        assertTrue(s.contains("execute_and_learn_soft_advisor_6696"))
        assertFalse(s.contains("action=execute_learning_ineligible"))
    }

    @Test fun legacy_classifier_no_longer_calls_all_paper_synthetic() {
        val s = src("engine/LearningEligibility.kt")
        assertTrue(s.contains("PAPER_SIMULATED"))
        assertFalse(s.contains("\"PAPER_OR_SYNTHETIC\""))
    }

    @Test fun tactic_and_exit_heads_share_post_commit_canonical_bus() {
        val bus = src("engine/truth/CanonicalFinalizedTradeBus6464.kt")
        val bridge = src("engine/truth/FinalizedBusConsumerBridge6465.kt")
        assertTrue(bus.contains("\"TacticSwitcher\""))
        assertTrue(bus.contains("\"ForwardOutcomeModel\""))
        assertTrue(bus.contains("\"UnifiedExitPolicyHead\""))
        assertTrue(bridge.contains("deliverToTacticSwitcher(env)"))
        assertTrue(bridge.contains("deliverToForwardOutcomeModel6696(env)"))
        assertTrue(bridge.contains("deliverToUnifiedExitPolicyHead6696(env)"))
        assertTrue(bridge.contains("committedTerminalEventForPosition"))
    }

    @Test fun exit_reason_is_preserved_into_post_commit_credit() {
        val rich = src("engine/truth/CanonicalTradeFinalizedBus6450.kt")
        val bus = src("engine/truth/CanonicalFinalizedTradeBus6464.kt")
        assertTrue(bus.contains("val exitReason: String = \"\""))
        assertTrue(rich.contains("exitReason = event.exitReason"))
    }

    @Test fun predictive_mid_hold_pivot_is_actuated_before_hold_logic() {
        val exec = src("engine/Executor.kt")
        val pivot = exec.indexOf("HeldPositionPivotArbiter.evaluate(")
        val hold = exec.indexOf("HoldingLogicLayer.evaluatePosition(")
        assertTrue(pivot >= 0)
        assertTrue(hold >= 0)
        assertTrue(pivot < hold)
        assertTrue(exec.contains("HELD_POSITION_FLUID_PIVOT_APPLIED_6696"))
    }

    @Test fun tactic_switcher_keeps_fluid_never_disable_doctrine() {
        val t = src("engine/learning/TacticSwitcher.kt")
        assertTrue(t.contains("TRADE_ONE_CATASTROPHIC_PNL"))
        assertTrue(t.contains("onCanonicalTradeClosed6486"))
        assertTrue(t.contains("NEVER DISABLE A BUCKET. ALWAYS ROTATE ITS TACTIC"))
    }
}
