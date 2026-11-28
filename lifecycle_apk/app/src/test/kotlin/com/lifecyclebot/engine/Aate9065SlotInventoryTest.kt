package com.lifecyclebot.engine

import com.lifecyclebot.v3.scoring.MoonshotTraderAI as Moon
import com.lifecyclebot.v3.scoring.ManipulatedTraderAI as Manip
import com.lifecyclebot.v3.scoring.DipHunterAI as Dip
import com.lifecyclebot.v3.scoring.ShitCoinExpress as Express
import org.junit.Test
import org.junit.Assert.*
import java.io.File

class Aate9065SlotInventoryTest {
    @Test fun paperMoonshotsCannotConsumeLivePriorityEvenWhenBothBooksHoldTheSameMint() {
        val prefix = "slot9065_${System.nanoTime()}"
        val paper = "$prefix-paper"; val live = "$prefix-live"; val both = "$prefix-both"
        fun position(mint: String, isPaper: Boolean) = Moon.MoonshotPosition(
            mint, mint, 0.001, 0.1, 1000, 100.0, -20.0, 20_000.0, 5_000.0,
            80.0, Moon.SpaceMode.ORBITAL, isPaper,
        )
        try {
            Moon.addPosition(position(paper, true)); Moon.addPosition(position(live, false))
            Moon.addPosition(position(both, true)); Moon.addPosition(position(both, false))
            assertTrue(Moon.getActivePositions().any { it.mint == paper }) // combined view reproduces the old input
            assertEquals(setOf(live, both), SlotInventory9065.subTraderMints(false).filter { it.startsWith(prefix) }.toSet())
            assertEquals(setOf(paper, both), SlotInventory9065.subTraderMints(true).filter { it.startsWith(prefix) }.toSet())
            assertTrue(Moon.getActivePositionsForMode(true).any { it.mint == paper })
        } finally {
            for (mint in listOf(paper, live, both)) for (mode in listOf(true, false)) Moon.evictGhost(mint, mode)
        }
    }

    @Test fun explicitModeWinsOverManipulatedTradersCurrentUiMode() {
        val prefix = "manip9065_${System.nanoTime()}"
        fun position(mint: String, paper: Boolean) = Manip.ManipulatedPosition(
            mint, mint, .001, .1, 1000, 25.0, -10.0, 80, 20.0, 60.0, paper,
        )
        val before = Manip.isPaperMode
        try {
            Manip.addPosition(position("$prefix-paper", true)); Manip.addPosition(position("$prefix-live", false))
            Manip.setTradingMode(true)
            assertEquals(setOf("$prefix-live"), SlotInventory9065.subTraderMints(false).filter { it.startsWith(prefix) }.toSet())
            Manip.setTradingMode(false)
            assertEquals(setOf("$prefix-paper"), SlotInventory9065.subTraderMints(true).filter { it.startsWith(prefix) }.toSet())
        } finally {
            for (mint in listOf("$prefix-paper", "$prefix-live")) for (mode in listOf(true, false)) Manip.evictGhost(mint, mode)
            Manip.setTradingMode(before)
        }
    }

    @Test fun sharedDipAndExpressStoresAreFilteredWithoutDeletingPaperHoldings() {
        val prefix = "shared9065_${System.nanoTime()}"
        try {
            for (paper in listOf(true, false)) {
                val suffix = if (paper) "paper" else "live"
                Dip.restoreDip(Dip.DipPosition("$prefix-dip-$suffix", "D", .001, .1, 1000, .002, 50.0, 20_000.0, 5_000.0, paper))
                Express.restoreRide(Express.ExpressRide("$prefix-express-$suffix", "E", .001, .1, 1000, 20.0, 60.0, paper))
            }
            for (paper in listOf(true, false)) {
                val suffix = if (paper) "paper" else "live"
                assertEquals(setOf("$prefix-dip-$suffix", "$prefix-express-$suffix"),
                    SlotInventory9065.subTraderMints(paper).filter { it.startsWith(prefix) }.toSet())
            }
            assertEquals(2, Dip.getActiveDips().count { it.mint.startsWith(prefix) })
            assertEquals(2, Express.getActiveRides().count { it.mint.startsWith(prefix) })
        } finally {
            for (suffix in listOf("paper", "live")) {
                Dip.evictGhost("$prefix-dip-$suffix"); Express.evictGhost("$prefix-express-$suffix")
            }
        }
    }

    @Test fun serviceUsesScopedOwnershipForBothPriorityAndGhostReaping() {
        val s = File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        val priority = s.substringAfter("val canonicalSlotMints9065 =").substringBefore("val otherMints =")
        assertTrue(priority.contains("SlotInventory9065.canonicalMints(paperRuntimeForSlotTruth)"))
        assertTrue(priority.contains("collectSubTraderOpenMints(paperRuntimeForSlotTruth)"))
        assertFalse(priority.contains("GlobalTradeRegistry.hasOpenPosition"))
        val reaper = s.substringAfter("private fun reapGhostForcedOpen(").substringBefore("private fun publishSlotHealth(")
        assertTrue(reaper.contains("SlotInventory9065.canonicalMints(isPaper = false)"))
        assertTrue(reaper.contains("collectSubTraderOpenMints(isPaper = false)"))
        assertFalse(reaper.contains("GlobalTradeRegistry.hasOpenPosition"))
    }
}
