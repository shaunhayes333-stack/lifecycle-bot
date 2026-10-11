package com.lifecyclebot.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8032 — the sub-trader -15% floor measures from the position's fill, not the sub-trader's map price. */
class Aate8032SubTraderFloorTest {
    private val bot = File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()

    @Test fun subTraderFloorReadsTheFill() {
        assertTrue(bot.contains("val subPnlVerdict = subTraderPnl8032(ts, entryPrice, currentPrice)"))
        assertTrue(bot.contains("entryPrice = ts.position.entryPrice.takeIf { ts.position.isOpen && it.isFinite() && it > 0.0 } ?: mapEntryPrice"))
        assertTrue(bot.contains("BotService.rapidSubTraderFloor"))
        assertFalse(bot.contains("                                entryPrice = entryPrice,\n                                currentPrice = currentPrice,"))
    }
}
