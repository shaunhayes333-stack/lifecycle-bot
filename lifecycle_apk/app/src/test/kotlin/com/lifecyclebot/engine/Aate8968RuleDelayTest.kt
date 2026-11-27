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
        assertTrue(s.liveFireAtDelay9047(f, 15_000L, 20_000L))
        assertTrue(s.liveFireAtDelay9047(f, 35_000L, 20_000L))
        assertFalse("30 s after the trigger is not the proven strategy (8749: -4..-6 %)", s.liveFireAtDelay9047(f, 45_000L, 20_000L))
        assertFalse(s.liveFireAtDelay9047(f, 135_000L, 20_000L))
    }

    @Test fun theLegacyClockFallbackDoesNotGrantAnUngradedFireAuthority() {
        assertEquals(20_000L, s.window8968(20_000L))
        assertEquals(120_000L, s.window8968(600_000L))
        assertEquals(120_000L, s.window8968(0L))
        val signal = fire(0L)
        assertFalse("the 120s clock fallback is not an actual latency grade", s.liveFireAtDelay9047(signal, 135_000L, 10_000L))
        assertFalse(s.liveFireAtDelay9047(signal, 135_001L, 10_000L))
        assertFalse("an unarmed fire is never evidence", s.liveFireAtDelay9047(fire(20_000L).copy(armed = false), 16_000L, 10_000L))
    }

    @Test fun launchAndBaseFiresCarryTheirRulesDelay() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/truth/LaunchSniper8748.kt").readText()
        val compact = src.replace(Regex("\\s+"), " ")
        assertTrue(compact.contains("armed = proven != null, triggerEventMs = eventMs, gradedDelayMs8968 = result.rule.delayMs, sourceEventId9043 = sig, receivedElapsedMs9043 = receivedElapsedMs9043, gradedStopPct9047 = result.rule.stopPct"))
        assertTrue(compact.contains("armed = baseProven != null, triggerEventMs = eventMs, gradedDelayMs8968 = baseRule.rule.delayMs, sourceEventId9043 = sig, receivedElapsedMs9043 = receivedElapsedMs9043"))
        assertTrue(src.contains("fireElapsed9043(f, nowMs) in 0L..window8968(f.gradedDelayMs8968)"))
        assertTrue(File("../../docs/DATA_MAP.md").readText().contains("## 153. A rule is traded at the delay it was proven at (8968)"))
    }
}
