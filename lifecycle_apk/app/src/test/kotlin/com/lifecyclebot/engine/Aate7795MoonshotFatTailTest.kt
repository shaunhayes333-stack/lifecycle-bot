package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Aate7795MoonshotFatTailTest {
    private fun src() = File("src/main/kotlin/com/lifecyclebot/v3/scoring/MoonshotTraderAI.kt").readText()

    @Test fun moonshotRestoresGoldenEraRunway() {
        val s = src()
        assertTrue(s.contains("private const val HARD_FLOOR_STOP = -20.0"))
        assertTrue(s.contains("private const val EARLY_TIGHT_STOP_PCT_7696 = HARD_FLOOR_STOP"))
        assertTrue(s.contains("val timeoutMins = maxOf(180L"))
    }

    @Test fun moonshotDoesNotManufactureMicroWinners() {
        val s = src()
        assertTrue(s.contains("MOONSHOT HAS NO MICRO-WINNER FLAT EXIT"))
        assertFalse(s.contains("pnlPct > -2.0 && pnlPct < 5.0"))
        assertTrue(s.contains("pnlPct < 5.0 && pnlPct > HARD_FLOOR_STOP"))
    }

    @Test fun runnerGivebackAuthorityCoversNativeTrail() {
        val s = src()
        assertTrue(s.contains("if (!runnerGiveBackDeferred7335 && pnlPct > 30.0 && currentPrice <= pos.trailingStop)"))
    }

    @Test fun advisoryExitsCannotCutIncubationWindow() {
        val s = src()
        assertTrue(s.contains("if (holdMinutes >= 180L &&"))
        assertTrue(s.contains("if (holdMinutes >= timeoutMins && com.lifecyclebot.engine.SentienceHooks.shouldExit("))
        assertTrue(s.contains("if (holdMinutes >= timeoutMins && com.lifecyclebot.engine.lab.LabPromotedFeed.shouldExitByPromotedRule("))
    }
}
