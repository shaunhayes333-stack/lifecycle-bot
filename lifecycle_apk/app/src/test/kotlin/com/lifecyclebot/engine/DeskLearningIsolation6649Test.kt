package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeskLearningIsolation6649Test {
    private fun source(path: String) = File("src/main/kotlin/com/lifecyclebot/$path").readText()

    @Test fun maturity_and_quality_ladders_use_the_meme_book_not_portfolio_totals() {
        val freeRange = source("engine/FreeRangeMode.kt")
        val quality = source("engine/QualityLadder.kt")
        assertTrue(freeRange.contains("DeskPerformanceAuthority6648.Book.MEME"))
        assertTrue(freeRange.contains("deskSnapshot6648(book)"))
        assertFalse(freeRange.contains("TradeHistoryStore.getLifetimeStats()"))
        assertTrue(quality.contains("memeSnapshot6648()"))
        assertFalse(quality.contains("TradeHistoryStore.getLifetimeStats()"))
    }

    @Test fun performance_regime_and_throughput_are_meme_keyed() {
        val regime = source("engine/RegimeDetector.kt")
        val edge = source("v3/scoring/MemeEdgeAI.kt")
        assertTrue(regime.contains("DeskPerformanceAuthority6648.classify(it) == DeskPerformanceAuthority6648.Book.MEME"))
        assertTrue(edge.contains("DeskPerformanceAuthority6648.Book.MEME"))
        assertFalse(edge.contains("TradeHistoryStore.getTradeCount24h()"))
    }

    @Test fun stock_exploration_never_reads_meme_maturity() {
        val stock = source("perps/TokenizedStockTrader.kt")
        assertTrue(stock.contains("FreeRangeMode.isWideOpen("))
        assertTrue(stock.contains("DeskPerformanceAuthority6648.Book.STOCKS"))
    }

    @Test fun dashboard_learning_and_markets_readiness_do_not_consume_global_journal_winrate() {
        val main = source("ui/MainActivity.kt")
        assertEquals(0, Regex("journalParityStatsSnapshot6085\\(\\)").findAll(main).count())
        assertTrue(main.contains("val memeLearning6649 =") && main.contains("DeskPerformanceAuthority6648.Book.MEME"))
        assertTrue(main.contains("Markets readiness explicitly combines only its child desks"))
    }
}
