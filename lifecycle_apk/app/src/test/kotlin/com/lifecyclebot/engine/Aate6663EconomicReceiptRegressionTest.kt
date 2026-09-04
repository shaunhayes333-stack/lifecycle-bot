package com.lifecyclebot.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Aate6663EconomicReceiptRegressionTest {
    @Test
    fun `typed receipt derives cost gross gain fee and net exactly once`() {
        val r = JournalReceiptAccounting6663.sell(
            solAmount = 0.984,
            recordedPnl = -0.016,
            recordedFee = 0.016,
            entryCost = 1.0,
            soldCostBasis = 1.0,
            grossProceeds = 1.0,
        )
        assertEquals(1.0, r.costBasis, 0.0)
        assertEquals(1.0, r.grossProceeds, 0.0)
        assertEquals(0.0, r.grossGain, 0.0)
        assertEquals(0.016, r.fee, 0.0)
        assertEquals(-0.016, r.netGain, 0.0)
    }

    @Test
    fun `zero proceeds catastrophe remains a real total loss`() {
        val r = JournalReceiptAccounting6663.sell(
            solAmount = 0.0,
            recordedPnl = -0.5,
            recordedFee = 0.0,
            entryCost = 0.5,
            soldCostBasis = 0.5,
            grossProceeds = 0.0,
        )
        assertEquals(0.5, r.costBasis, 0.0)
        assertEquals(0.0, r.grossProceeds, 0.0)
        assertEquals(-0.5, r.netGain, 0.0)
        val history = File("src/main/kotlin/com/lifecyclebot/engine/TradeHistoryStore.kt").readText()
        assertTrue(history.contains("isCanonicalZeroProceedsLoss6663"))
        assertTrue(history.contains("t.grossProceedsSol == 0.0"))
    }

    @Test
    fun `paper terminal sends pre fee gross into reducer`() {
        val exec = File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        val close = exec.substringAfter("val close6474 = com.lifecyclebot.engine.truth.CanonicalPaperTerminalBridge6469.finalizeSell(")
            .substringBefore("canonicalPaperSellCommitted6474 = close6474.applied")
        assertTrue(close.contains("grossProceedsSol = (grossNoFrictionValue - treasuryShare).coerceAtLeast(0.0)"))
        assertTrue(close.contains("feesSol = simulatedFeeSol.coerceAtLeast(0.0)"))
        assertFalse(close.contains("grossProceedsSol = (value - treasuryShare)"))
    }

    @Test
    fun `paper entry validates the exact snapshot it persists`() {
        val exec = File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        assertTrue(exec.contains("val entryMarketSnapshot = mintEntryMarketSnapshot(ts)"))
        assertTrue(exec.contains("source = entryMarketSnapshot.priceSource"))
        assertTrue(exec.contains("poolAddress = entryMarketSnapshot.poolAddress"))
        assertTrue(exec.contains("Provenance.NON_AUTHORITATIVE_MISSING"))
        assertTrue(exec.contains("persistMintEntryMarketSnapshot(ts, entryMarketSnapshot, \"paperBuy.authoritative.6663\")"))
    }

    @Test
    fun `treasury role boundary and fdg failure are binding`() {
        val bot = File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        assertTrue(bot.contains("val treasuryRoleEligible6663"))
        assertTrue(bot.contains("TreasuryScannerFeed.MIN_TREASURY_MCAP"))
        assertTrue(bot.contains("TreasuryScannerFeed.MIN_TREASURY_LIQUIDITY"))
        assertTrue(bot.contains("val treasuryFdgCanExecute6663 = treasuryFdg?.canExecute() == true"))
        assertFalse(bot.contains("treasuryFdg?.canExecute() ?: true"))
    }

    @Test
    fun `canonical paper positions cannot execute while learning is suppressed`() {
        val exec = File("src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        val gate = exec.substringAfter("shouldSuppressPaperLearningEntry(ts, score, layerTag, identity)?.let")
            .substringBefore("// V5.9.1129")
        assertTrue(gate.contains("markPaperBuyNotOpened(\"LEARNING_QUALITY_REJECTED_6663\")"))
        assertTrue(gate.contains("return"))
        assertFalse(gate.contains("execution continues"))
    }

    @Test
    fun `learned hold observations change the next timing prediction`() {
        val hold = File("src/main/kotlin/com/lifecyclebot/v3/scoring/HoldTimeOptimizerAI.kt").readText()
        val prediction = hold.substringAfter("fun predict(").substringBefore("fun isHoldTimeOptimal")
        assertTrue(prediction.contains("avgHoldSeconds"))
        assertTrue(prediction.contains("bestHoldSeconds"))
        assertTrue(prediction.contains("learnedWeight"))
        assertTrue(prediction.contains("adaptedOptimalBase * combinedMultiplier"))
        assertTrue(hold.contains("put(\"winRateByHold\""))
    }

    @Test
    fun `cross asset positions are governed by adaptive hold and stale mark settlement`() {
        val crypto = File("src/main/kotlin/com/lifecyclebot/perps/CryptoAltTrader.kt").readText()
        val stocks = File("src/main/kotlin/com/lifecyclebot/perps/TokenizedStockTrader.kt").readText()
        assertTrue(crypto.contains("adaptiveMaxHoldSeconds = holdRecommendation6663?.maxSeconds"))
        assertTrue(crypto.contains("ADAPTIVE_HOLD_MAX_6663"))
        assertTrue(crypto.contains("settleUntrustedDynamicPaperPosition6663"))
        assertTrue(crypto.contains("UNTRUSTED_DYNAMIC_MARK_ADMIN_REFUND_6663"))
        assertTrue(stocks.contains("adaptiveMaxHoldSeconds = holdRecommendation6663?.maxSeconds"))
        assertTrue(stocks.contains("ADAPTIVE_HOLD_MAX_6663"))
        assertTrue(stocks.contains("HoldTimeOptimizerAI.recordOutcomeSeconds"))
    }

    @Test
    fun `meme exit coverage rotates instead of restarting at inventory head`() {
        val bot = File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        assertTrue(bot.contains("private fun <T> rotatingExitSlice6663"))
        assertTrue(bot.contains("cursor.getAndAdd(maxItems)"))
        assertTrue(bot.contains("cursor = hotExitCoverageCursor6663"))
        assertTrue(bot.contains("cursor = fullExitCoverageCursor6663"))
        assertTrue(bot.contains("cursor = manageExitCoverageCursor6663"))
    }
}
