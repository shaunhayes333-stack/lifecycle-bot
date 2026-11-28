package com.lifecyclebot.engine

import com.lifecyclebot.v3.scoring.DipHunterAI as Dip
import com.lifecyclebot.v3.scoring.ShitCoinExpress as Express
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class Aate9066GhostIsolationIntegrationTest {
    @Test fun sharedSpecialistBooksRejectCleanupFromTheOtherAccount() {
        val prefix = "ghost9066_${System.nanoTime()}"
        try {
            for (paper in listOf(true, false)) {
                val mint = "$prefix-$paper"
                Dip.restoreDip(Dip.DipPosition(mint, "D", .001, .1, 1000, .002, 50.0, 20_000.0, 5_000.0, paper))
                Express.restoreRide(Express.ExpressRide(mint, "E", .001, .1, 1000, 20.0, 60.0, paper))
                assertFalse(Dip.evictGhost(mint, !paper))
                assertFalse(Express.evictGhost(mint, !paper))
                assertTrue(Dip.getActiveDips().any { it.mint == mint && it.isPaper == paper })
                assertTrue(Express.getActiveRides().any { it.mint == mint && it.isPaper == paper })
                assertTrue(Dip.evictGhost(mint, paper))
                assertTrue(Express.evictGhost(mint, paper))
                assertFalse(Dip.evictGhost(mint, paper))
                assertFalse(Express.evictGhost(mint, paper))
            }
        } finally {
            for (paper in listOf(true, false)) {
                Dip.evictGhost("$prefix-$paper")
                Express.evictGhost("$prefix-$paper")
            }
        }
    }

    @Test fun liveReapingCarriesItsAccountThroughEverySpecialistCleanup() {
        val source = File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        val helper = source.substringAfter("private fun evictSubTraderGhost(")
            .substringBefore("private fun isCapCountableLiveToken(")
        assertTrue(helper.contains("mint: String, isPaper: Boolean"))
        assertTrue(helper.contains("val paper7858 = isPaper"))
        assertFalse(helper.contains("RuntimeModeAuthority"))
        for (trader in listOf("CashGenerationAI", "MoonshotTraderAI", "ShitCoinTraderAI", "QualityTraderAI",
            "BlueChipTraderAI", "ManipulatedTraderAI", "DipHunterAI", "ShitCoinExpress")) {
            assertTrue(trader, helper.contains("$trader.evictGhost(mint, isPaper = paper7858)"))
        }
        val reaper = source.substringAfter("private fun reapGhostForcedOpen(")
            .substringBefore("private fun publishSlotHealth(")
        assertTrue(reaper.contains("evictSubTraderGhost(m, isPaper = false)"))
    }
}
