package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class Aate7545RiskClockCanonicalMarkTest {
    private fun bot() = File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()

    // V5.0.7960 moved the canonical EXIT_ECONOMIC read into riskClockMark7960().
    private fun markHelper(s: String) = s.substringAfter("private fun riskClockMark7960(")
        .substringBefore("fun startBot()")

    private fun code(text: String) = text.lines().filterNot { it.trim().startsWith("//") }.joinToString("\n")

    @Test fun risk_clock_reads_canonical_held_observations_without_requiring_profit_authority() {
        val s = bot()
        val block = s.substringAfter("CanonicalRiskClock6454.start { positionId, mint ->")
            .substringBefore("// V5.0.7222")
        assertTrue(block.contains("riskClockMark7960(mint, ts6882, now7545)"))
        assertTrue(markHelper(s).contains("CanonicalPriceMarkRegistry6522.heldRiskMark(mint)"))
        assertTrue(block.contains("RISK_CLOCK_CANONICAL_EXIT_MARK_READ_7545"))
        assertTrue(block.contains("preResolvedMark6891 = exitMarkPx7545"))
        assertTrue(block.contains("copy(markAgeMs = exitMarkAge7545)"))
    }

    @Test fun risk_clock_never_invokes_heavy_price_resolution_implicitly() {
        val s = bot()
        val block = code(s.substringAfter("CanonicalRiskClock6454.start { positionId, mint ->")
            .substringBefore("// V5.0.7222"))
        val helper = code(markHelper(s))
        assertFalse(block.contains("protectiveExitThresholds6882(ts6882)"))
        assertFalse(block.contains("getActualPrice"))
        assertFalse(block.contains("getActualPricePublic"))
        assertFalse(helper.contains("getActualPrice"))
    }

    @Test fun existing_sixty_second_protective_freshness_bar_is_preserved() {
        val s = bot()
        assertTrue(markHelper(s).contains("if (px != null && age <= 60_000L) return RiskMark7960(true, px, age, true)"))
    }
}
