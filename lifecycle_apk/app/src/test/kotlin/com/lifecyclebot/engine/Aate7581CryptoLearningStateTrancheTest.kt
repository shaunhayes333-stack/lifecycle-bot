package com.lifecyclebot.engine

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class Aate7581CryptoLearningStateTrancheTest {
    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/" + rel).readText()
    @Test fun `crypto learning tranche is state readback or local statistic`() {
        val f=src("perps/crypto/brain/CryptoFluidLearning.kt")
        assertTrue(f.contains("fun paperTradeCount(): Int = tradesPaper.get()"))
        assertTrue(f.contains("fun liveTradeCount(): Int = tradesLive.get()"))
        assertTrue(f.contains("fun winCount(): Int = wins.get()"))
        assertTrue(f.contains("fun lossCount(): Int = losses.get()"))
        assertTrue(f.contains("fun paperPnlEma(): Double = paperPnlEma"))
        assertTrue(src("perps/CryptoAltTrader.kt").contains("fun getLossCount(): Int"))
        assertTrue(src("perps/PerpsTraderAI.kt").contains("fun getMaxWinStreak(): Int = maxWinStreak.get()"))
        assertTrue(src("perps/PerpsTraderAI.kt").contains("fun getMaxLossStreak(): Int = maxLossStreak.get()"))
        val m=src("perps/crypto/brain/CryptoLosingPatternMemory.kt")
        assertTrue(m.contains("fun lossRate(): Double"))
        assertTrue(m.contains("fun meanPnl(): Double"))
    }
    @Test fun `audit count advances`() {
        val a=File("../../audits/aate_strategy_unwired_worklist_2026-09-29.md").readText()
        assertTrue(a.contains("117 / 1,458"))
        assertTrue(a.contains("1,341 remain"))
    }
}
