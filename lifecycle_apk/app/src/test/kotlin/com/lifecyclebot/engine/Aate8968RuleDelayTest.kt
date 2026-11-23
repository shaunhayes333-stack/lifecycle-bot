package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.LaunchSniper8748
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8968 — an armed launch / base rule's fire is evidence only for the entry delay its rule was graded at. */
class Aate8968RuleDelayTest {
    private val s = LaunchSniper8748
    private fun fire(delayMs: Long) = LaunchSniper8748.Fire("mint", 15_000L, 0L, 5_000.0, "cap\$5k buys>=3 tp5x stop35% DOCTRINE +20s",
        3104, 6.5, 2.2, 0.07, armed = true, triggerEventMs = 15_000L, gradedDelayMs8968 = delayMs)

    @Test fun aRuleGradedAtTwentySecondsIsEvidenceForTwentySeconds() {
        val f = fire(20_000L)
        assertTrue(s.liveFire8959(f, 15_000L))
        assertTrue(s.liveFire8959(f, 35_000L))
        assertFalse("30 s after the trigger is not the proven strategy (8749: -4..-6 %)", s.liveFire8959(f, 45_000L))
        assertFalse(s.liveFire8959(f, 135_000L))
    }

    @Test fun theWindowNeverExceedsTheOldTtlAndAnUngradedFireKeepsIt() {
        assertEquals(20_000L, s.window8968(20_000L))
        assertEquals(120_000L, s.window8968(600_000L))
        assertEquals(120_000L, s.window8968(0L))
        val signal = fire(0L)
        assertTrue(s.liveFire8959(signal, 135_000L))
        assertFalse(s.liveFire8959(signal, 135_001L))
        assertFalse("an unarmed fire is never evidence", s.liveFire8959(fire(20_000L).copy(armed = false), 16_000L))
    }

    @Test fun launchAndBaseFiresCarryTheirRulesDelay() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/truth/LaunchSniper8748.kt").readText()
        assertTrue(src.contains("armed = proven != null, triggerEventMs = eventMs, gradedDelayMs8968 = result.rule.delayMs))"))
        assertTrue(src.contains("armed = baseProven != null, triggerEventMs = eventMs, gradedDelayMs8968 = baseRule.rule.delayMs))"))
        assertTrue(src.contains("nowMs - minOf(f.triggerEventMs, f.atMs) in 0L..window8968(f.gradedDelayMs8968)"))
        assertTrue(File("../../docs/DATA_MAP.md").readText().contains("## 153. A rule is traded at the delay it was proven at (8968)"))
    }
}
