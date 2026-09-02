package com.lifecyclebot.engine

import com.lifecyclebot.data.Trade
import com.lifecyclebot.engine.truth.DeskPerformanceAuthority6648
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DeskPerformanceAuthority6648Test {
    private fun terminal(
        id: String,
        tradingMode: String,
        pnl: Double,
        mode: String = "paper",
    ) = Trade(
        side = "SELL",
        mode = mode,
        sol = 1.0,
        price = 1.0,
        ts = id.hashCode().toLong(),
        pnlSol = pnl,
        pnlPct = pnl * 100.0,
        netPnlSol = pnl,
        tradingMode = tradingMode,
        positionId = id,
        economicEventId = "EVENT:$id",
    )

    @Test fun booksAndAccountModesCannotBleed() {
        val rows = listOf(
            terminal("MEME:1", "STANDARD", 1.0),
            terminal("ALT:2", "STANDARD", -1.0),
            terminal("PERPS:3", "STANDARD", 2.0),
            terminal("STOCK:4", "STANDARD", -2.0),
            terminal("FOREX:5", "STANDARD", 0.0),
            terminal("METAL:6", "STANDARD", 1.0),
            terminal("COMMODITY:7", "STANDARD", 1.0),
            terminal("LEGACY_WITHOUT_CLASS", "NOVEL_LANE", 99.0),
            terminal("ALT:LIVE", "STANDARD", 5.0, mode = "live"),
        )

        val paper = DeskPerformanceAuthority6648.reduce(rows, "paper", accountingAvailable = true)
        assertEquals(1, paper.getValue(DeskPerformanceAuthority6648.Book.MEME).trades)
        assertEquals(1, paper.getValue(DeskPerformanceAuthority6648.Book.CRYPTO).trades)
        assertEquals(1, paper.getValue(DeskPerformanceAuthority6648.Book.PERPS).trades)
        assertEquals(1, paper.getValue(DeskPerformanceAuthority6648.Book.UNCLASSIFIED).trades)
        assertEquals(7, paper.getValue(DeskPerformanceAuthority6648.Book.PORTFOLIO).trades)
        assertEquals(0, paper.getValue(DeskPerformanceAuthority6648.Book.MEME).losses)

        val live = DeskPerformanceAuthority6648.reduce(rows, "live", accountingAvailable = true)
        assertEquals(1, live.getValue(DeskPerformanceAuthority6648.Book.CRYPTO).trades)
        assertEquals(0, live.getValue(DeskPerformanceAuthority6648.Book.MEME).trades)
    }

    @Test fun failedAccountingWithholdsEveryBookPnl() {
        val result = DeskPerformanceAuthority6648.reduce(
            listOf(terminal("ALT:1", "CRYPTO_ALT", 3.0)),
            "paper",
            accountingAvailable = false,
        )
        assertNull(result.getValue(DeskPerformanceAuthority6648.Book.CRYPTO).realizedPnlSol)
        assertNull(result.getValue(DeskPerformanceAuthority6648.Book.PORTFOLIO).realizedPnlSol)
    }

    @Test fun cryptoUiCannotReadMarketsLearningOrGlobalRunTotals() {
        val ui = File("src/main/kotlin/com/lifecyclebot/ui/CryptoAltActivity.kt").readText()
        val tracker = File("src/main/kotlin/com/lifecyclebot/engine/RunTracker30D.kt").readText()
        val main = File("src/main/kotlin/com/lifecyclebot/ui/MainActivity.kt").readText()
        assertTrue(!ui.contains("FluidLearningAI.getMarkets"))
        assertTrue(!ui.contains("RunTracker30D.totalRealizedPnlSol"))
        assertTrue(tracker.contains("else                                       -> unclassifiedBucket"))
        assertTrue(main.contains("Book.MEME"))
        assertTrue(main.contains("Book.PORTFOLIO"))
        val layout = File("src/main/res/layout/activity_main.xml").readText()
        assertTrue(layout.contains("PORTFOLIO WR"))
    }
}
