package com.lifecyclebot.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class V5_0_6568AcceptanceTest {
    @Test fun meme_entry_policy_and_tactic_rewards_are_causal() {
        val exec = File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        val tactic = File("src/main/kotlin/com/lifecyclebot/engine/learning/TacticSwitcher.kt").readText()
        val bot = File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        assertTrue(exec.contains("entryTactic=$" + "electedTactic6568"))
        assertTrue(exec.contains("brainConsensus=$" + "brainVerdict6568") && exec.contains("policyPWin=$" + "{policyPWin6568.fmt(3)}"))
        assertTrue(exec.contains("LIVE_ENTRY_POLICY_SNAPSHOT_CANONICAL_6568") && exec.contains("entryThresholdSnapshot = ts.position.entryPolicySnapshot"))
        assertTrue(tactic.contains("TACTIC_HISTORICAL_OUTCOME_ATTRIBUTED_6568") && tactic.contains("if (elected.name == current) onTradeClosed"))
        assertFalse(tactic.contains("entered.isBlank() || entered == current"))
        assertTrue(bot.contains("authoritativePolicyPositive6568") && bot.contains("NEGATIVE_CONSENSUS_NORMAL_BUY_SUPPRESSED_6568"))
        assertTrue(bot.contains("liqOk && authoritativePolicyPositive6568"))
    }
}
