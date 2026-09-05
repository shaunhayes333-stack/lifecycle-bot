package com.lifecyclebot.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.6679 — regression lock for canonical PAPER terminal ownership.
 *
 * PAPER close is committed/journaled once by CanonicalPaperTransaction6486.
 * Asset-specific traders may keep live presentation/outcome writers, but must
 * not stack a second PAPER journal/outcome event or duplicate FluidLearning.
 */
class Aate6679PaperOutcomeAuthorityTest {

    private val perps = File(
        "src/main/kotlin/com/lifecyclebot/perps/PerpsTraderAI.kt"
    ).readText()

    private val stocks = File(
        "src/main/kotlin/com/lifecyclebot/perps/TokenizedStockTrader.kt"
    ).readText()

    @Test
    fun `perps paper terminal is canonical and legacy publishers are live only`() {
        val close = perps.substringAfter(
            "fun closePosition(positionId: String, exitPrice: Double, exitReason: PerpsExitSignal)"
        )

        assertTrue(close.contains("CanonicalPaperTransaction6486.close("))
        assertTrue(close.contains("if (!position.isPaper) com.lifecyclebot.engine.CanonicalPublishHelper.publishExit("))
        assertFalse(close.contains("modeStr248"))

        val journal = close.substringAfter("// V5.0.6679 — PAPER is already journaled")
            .substringBefore("\n        save()")
        assertTrue(journal.contains("if (!position.isPaper) try {"))
        assertTrue(journal.contains("TradeHistoryStore.recordTrade(Trade("))
        assertTrue(journal.contains("mode             = \"live\","))
    }

    @Test
    fun `perps paper FluidLearning terminal fanout occurs once`() {
        val close = perps.substringAfter(
            "fun closePosition(positionId: String, exitPrice: Double, exitReason: PerpsExitSignal)"
        )
        assertEquals(1, Regex("FluidLearning\\.recordPaperSell\\(").findAll(close).count())
    }

    @Test
    fun `tokenized stock explicit outcome publisher cannot duplicate paper close`() {
        assertTrue(stocks.contains("CanonicalPaperTransaction6486.close("))
        assertTrue(stocks.contains("if (!position.isPaper) com.lifecyclebot.engine.CanonicalPublishHelper.publishExit("))
    }
}
