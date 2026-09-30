package com.lifecyclebot.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Aate7540PerpsReplayClosedLoopTest {
    @Test fun replay_patterns_have_bounded_non_veto_actuator() {
        val s = File("src/main/kotlin/com/lifecyclebot/perps/PerpsAutoReplayLearner.kt").readText()
        val fn = s.substringAfter("fun boundedSizeMultiplier7540")
            .substringBefore("// ═══════════════════════════════════════════════════════════════════════════\n    // STATS")
        assertTrue(fn.contains("matchesWinningPattern"))
        assertTrue(fn.contains("matchesLosingPattern"))
        assertTrue(fn.contains("pattern.avgPnl > 0.0"))
        assertTrue(fn.contains("pattern.avgPnl < 0.0"))
        assertTrue(fn.contains("coerceIn(0.75, 1.15)"))
        assertFalse(fn.contains("return 0.0"))
    }

    @Test fun perps_trader_consumes_replay_only_in_position_size_not_leverage() {
        val s = File("src/main/kotlin/com/lifecyclebot/perps/PerpsTraderAI.kt").readText()
        val block = s.substringAfter("V5.0.7540 — PerpsAutoReplayLearner")
            .substringBefore("// ═══════════════════════════════════════════════════════════════════\n        // RISK PARAMETERS")
        assertTrue(block.contains("boundedSizeMultiplier7540(market, direction)"))
        assertTrue(block.contains("PERPS_REPLAY_PATTERN_SIZE_SHAPED_7540"))
        assertTrue(block.contains("baseSize * sizeMultiplier * behaviorSizeMult * behaviorGradeMult * replayPatternSizeMult7540"))
        assertFalse(block.contains("recommendedLeverage ="))
    }
}
