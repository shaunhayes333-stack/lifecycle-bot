package com.lifecyclebot.engine

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Aate7536StandaloneLearnerResetTest {
    @Test fun meta_policy_reset_clears_disk_and_counters() {
        val s = File("src/main/kotlin/com/lifecyclebot/engine/AutonomousMetaPolicy.kt").readText()
        val fn = s.substringAfter("fun reset()").substringBefore("// V5.9.1290")
        assertTrue(fn.contains("totalUpdates = 0L"))
        assertTrue(fn.contains("vetoProbeCounter.set(0L)"))
        assertTrue(fn.contains("autonomous_meta_policy"))
        assertTrue(fn.contains("clear()?.commit()"))
    }

    @Test fun forward_model_reset_clears_disk_and_updates() {
        val s = File("src/main/kotlin/com/lifecyclebot/engine/ForwardOutcomeModel.kt").readText()
        val fn = s.substringAfter("fun reset()").substringBefore("fun stamp(")
        assertTrue(fn.contains("totalUpdates = 0L"))
        assertTrue(fn.contains("forward_outcome_model"))
        assertTrue(fn.contains("clear()?.commit()"))
    }

    @Test fun hypothesis_reset_clears_disk_and_experiment_counters() {
        val s = File("src/main/kotlin/com/lifecyclebot/engine/StrategyHypothesisEngine.kt").readText()
        val fn = s.substringAfter("fun reset()").substringBefore("private fun band(")
        assertTrue(fn.contains("promotions = 0L"))
        assertTrue(fn.contains("retirements = 0L"))
        assertTrue(fn.contains("outcomeUpdates6512 = 0L"))
        assertTrue(fn.contains("strategy_hypothesis_engine"))
        assertTrue(fn.contains("clear()?.commit()"))
    }
}
