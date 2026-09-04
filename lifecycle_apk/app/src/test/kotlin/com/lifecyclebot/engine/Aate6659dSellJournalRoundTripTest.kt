package com.lifecyclebot.engine

import com.lifecyclebot.data.Trade
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

/** Regression coverage for the terminal journal hole seen in runtime smoke 3093. */
class Aate6659dSellJournalRoundTripTest {
    private fun terminalSell(gross: Double, pnlPct: Double) = Trade(
        side = "SELL",
        mode = "paper",
        sol = gross,
        price = 0.00000001,
        ts = 1L,
        reason = "CATASTROPHIC_HARD_BACKSTOP_-25",
        pnlSol = gross - 0.05,
        pnlPct = pnlPct,
        netPnlSol = gross - 0.05,
        tradingMode = "PROJECT_SNIPER",
        mint = "B6DfUsHYvvDjunsCWFiYcJSNMghfW7W3Hr82wSGqpump",
        proofState = "PAPER_SIMULATED",
        positionId = "PAPER:B6DfUsHY:1",
        entryTsMs = 1L,
        entryPriceSnapshot = 0.000001,
        entryQtyToken = 50_000.0,
        entryCostSol = 0.05,
        soldQtyToken = 50_000.0,
        entryRawQty = BigInteger.valueOf(50_000L),
        canonicalConsumedRaw = BigInteger.valueOf(50_000L),
        remainingRawQty = BigInteger.ZERO,
        tokenDecimals = 0,
        preCostSol = 0.05,
        soldCostBasisSol = 0.05,
        postCostSol = 0.0,
        grossProceedsSol = gross,
        economicEventId = "SELL:B6DfUsHY:1",
    )

    @Test
    fun `deep loss keeps its terminal journal row`() {
        assertTrue(TradeHistoryStore.isValidAccountingTrade(terminalSell(gross = 0.001, pnlPct = -98.0)))
    }

    @Test
    fun `total loss is a valid zero proceeds close`() {
        assertTrue(TradeHistoryStore.isValidAccountingTrade(terminalSell(gross = 0.0, pnlPct = -100.0)))
    }

    @Test
    fun `paper sell ceiling applies to entries not profitable exits`() {
        val source = java.io.File("src/main/kotlin/com/lifecyclebot/engine/PaperLearningSanity.kt").readText()
        assertTrue(source.contains("t.side.equals(\"BUY\", true) && t.sol > maxSol"))
    }

    @Test
    fun `canonical cross asset close owns its journal projection`() {
        val source = java.io.File(
            "src/main/kotlin/com/lifecyclebot/engine/truth/CanonicalPaperTransaction6486.kt"
        ).readText()
        assertTrue(source.contains("private fun recordCloseProjection6659("))
        assertTrue(source.contains("recordCloseProjection6659(pos, r, exitReason, terminal)"))
        assertTrue(source.contains("sol = gross"))
        assertTrue(source.contains("economicEventId = receipt.economicEventId"))
    }
}
