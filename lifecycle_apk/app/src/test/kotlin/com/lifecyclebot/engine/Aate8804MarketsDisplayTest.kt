package com.lifecyclebot.engine

import com.lifecyclebot.desks.DeskJournal8802
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8804 — owner: "you turned off the whole market trader display, position display everything". 8797 removed the
 * Markets tile (the screen's only visible way in) and the new desks never showed on the Markets screen. The tile is back,
 * and the Hyperliquid and xStocks desks' positions show in its PERPS and STOCKS tabs beside anything the legacy traders hold.
 */
class Aate8804MarketsDisplayTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()

    @Test fun theMarketsTileIsBackOnTheDeck() {
        val layout = File("src/main/res/layout/activity_main.xml").readText()
        assertTrue(layout.contains("@+id/btnQuickMarkets"))
        assertEquals("the deck stays 3x5", 15, layout.split("@drawable/aate_nav_tile_bg").size - 1)
        val main = src("ui/MainActivity.kt")
        assertTrue(main.contains("findViewById<View>(R.id.btnQuickMarkets)?.setOnClickListener {\n            startActivity(Intent(this, MultiAssetActivity::class.java))"))
        assertTrue(main.contains("findViewById<View>(R.id.btnQuickPerps)?.setOnClickListener {\n            startActivity(Intent(this, com.lifecyclebot.ui.HyperliquidActivity::class.java))"))
    }

    @Test fun theDesksPositionsShowOnTheMarketsScreen() {
        val m = src("ui/MultiAssetActivity.kt")
        assertTrue(m.contains("perpsPositions + cryptoFromTrader + deskInfos8804(com.lifecyclebot.hyperliquid.HlDesk.positionRows8804(), solPrice)"))
        assertTrue(m.contains("deskInfos8804(com.lifecyclebot.xstocks.XsDesk.positionRows8804(), solPrice) + positions.map { pos ->"))
        assertTrue(m.contains("com.lifecyclebot.xstocks.XsDesk.closeLive8804(id.removePrefix(\"desk:XSTOCKS:\"))"))
        assertTrue(m.contains("XsDesk.isRunning8804()") && m.contains("HlDesk.isRunning8804()"))
    }

    @Test fun aDeskRowCarriesItsOwnProfit() {
        val long = DeskJournal8802.DeskRow("XSTOCKS", "PAPER", "AAPL", 2.0, 100.0, 110.0)
        assertEquals(20.0, long.pnlUsd, 1e-9); assertEquals(10.0, long.pnlPct, 1e-9); assertEquals(220.0, long.valueUsd, 1e-9)
        val short = DeskJournal8802.DeskRow("HYPERLIQUID", "LIVE", "BTC", -0.5, 100.0, 90.0)
        assertEquals(5.0, short.pnlUsd, 1e-9); assertEquals(10.0, short.pnlPct, 1e-9)
        val unmarked = DeskJournal8802.DeskRow("XSTOCKS", "LIVE", "TSLA", 1.0, 200.0, Double.NaN)
        assertEquals(0.0, unmarked.pnlUsd, 0.0); assertEquals(200.0, unmarked.valueUsd, 0.0)
    }
}
