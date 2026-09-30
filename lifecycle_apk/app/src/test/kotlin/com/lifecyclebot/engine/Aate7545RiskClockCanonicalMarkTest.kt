package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class Aate7545RiskClockCanonicalMarkTest {
    private fun bot() = File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()

    @Test fun risk_clock_reads_only_canonical_exit_mark() {
        val s = bot()
        val block = s.substringAfter("CanonicalRiskClock6454.start { positionId, mint ->")
            .substringBefore("// V5.0.7222")
        assertTrue(block.contains("CanonicalMarkPurpose6570.EXIT_ECONOMIC"))
        assertTrue(block.contains("RISK_CLOCK_CANONICAL_EXIT_MARK_READ_7545"))
        assertTrue(block.contains("preResolvedMark6891 = exitMarkPx7545"))
        assertTrue(block.contains("copy(markAgeMs = exitMarkAge7545)"))
    }

    @Test fun risk_clock_never_invokes_heavy_price_resolution_implicitly() {
        val s = bot()
        val block = s.substringAfter("CanonicalRiskClock6454.start { positionId, mint ->")
            .substringBefore("// V5.0.7222")
        assertFalse(block.contains("protectiveExitThresholds6882(ts6882)"))
        assertFalse(block.contains("getActualPrice"))
        assertFalse(block.contains("getActualPricePublic"))
    }

    @Test fun existing_sixty_second_protective_freshness_bar_is_preserved() {
        val s = bot()
        val block = s.substringAfter("CanonicalRiskClock6454.start { positionId, mint ->")
            .substringBefore("// V5.0.7222")
        assertTrue(block.contains("exitMarkAge7545 <= 60_000L"))
    }
}
