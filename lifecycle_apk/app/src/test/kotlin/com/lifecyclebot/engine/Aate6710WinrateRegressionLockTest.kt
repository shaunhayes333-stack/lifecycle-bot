package com.lifecyclebot.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.6710 — catastrophic WR collapse + reward-purity regression locks. */
class Aate6710WinrateRegressionLockTest {

    @Test
    fun `catastrophic rolling collapse requires high grade canonical entries`() {
        val fdg = File("src/main/kotlin/com/lifecyclebot/engine/FinalDecisionGate.kt").readText()
        assertTrue(fdg.contains("WR_ROLL50_COLLAPSE_A_GRADE_REQUIRED"))
        assertTrue(fdg.contains("WR_ROLL50_COLLAPSE_BLOCK"))
        assertFalse(fdg.contains("WR_ROLL50_COLLAPSE_PROBE"))

        val exec = File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        assertTrue(exec.contains("s.rollingCollapse -> 60"))
        assertTrue(exec.contains("val delta = if (s.rollingCollapse)"))
        assertFalse(exec.contains("s.rollingCollapse -> 35"))
    }

    @Test
    fun `economically untrusted closes cannot enter canonical learners`() {
        val eligibility = File("src/main/kotlin/com/lifecyclebot/engine/truth/PaperLearningEligibility6519.kt").readText()
        val reward = File("src/main/kotlin/com/lifecyclebot/engine/truth/RewardPurityGate6441.kt").readText()
        assertTrue(eligibility.contains("EconomicPurityGate6504.shouldExcludeFromAnalytics(mint)"))
        assertTrue(eligibility.contains("ECONOMIC_PURITY_EXCLUDED_6504"))
        assertTrue(reward.contains("EconomicPurityGate6504.shouldExcludeFromAnalytics(pos.mint)"))
        assertTrue(reward.contains("REWARD_PURITY_REJECT_ECONOMIC_6710"))
    }
}
