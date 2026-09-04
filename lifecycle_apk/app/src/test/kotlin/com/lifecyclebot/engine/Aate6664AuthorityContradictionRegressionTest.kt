package com.lifecyclebot.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Aate6664AuthorityContradictionRegressionTest {
    private val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .first { File(it, "src/main/kotlin/com/lifecyclebot/engine/BotService.kt").exists() }

    @Test
    fun `express creation fdg authorization and execution retain one lane`() {
        val bot = File(root, "src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        val express = bot.substringAfter("V5.9.1570 — Express FDG verdict")
            .substringBefore("END ShitCoin Express evaluation")
        assertTrue(express.contains("lane = \"EXPRESS\""))
        assertTrue(express.contains("requestedBook = TradeAuthorizer.ExecutionBook.EXPRESS"))
        assertTrue(express.contains("executor.shitCoinBuy"))
        assertTrue(express.contains("executionLane = \"EXPRESS\""))
        assertTrue(express.contains("finalityPrechecked = true"))
        assertTrue(express.contains("TradeAuthorizer.ExecutionBook.EXPRESS)"))
        assertFalse(express.contains("lane = \"SHITCOIN\""))
        assertFalse(express.contains("TradeAuthorizer.ExecutionBook.SHITCOIN)"))
    }

    @Test
    fun `shared meme transport cannot overwrite specialist lane after open`() {
        val executor = File(root, "src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        val transport = executor.substringAfter("fun shitCoinBuy(")
            .substringBefore("fun moonshotBuy(")
        assertTrue(transport.contains("executionLane: String = \"SHITCOIN\""))
        assertTrue(transport.contains("preflightExecutableOpen(ts, isPaper, executionLane"))
        assertTrue(transport.contains("layerTag = executionLane"))
        assertTrue(transport.contains("quality = executionLane"))
        assertTrue(transport.contains("ts.position.tradingMode = executionLane"))
        assertTrue(transport.contains("mode = executionLane"))
        assertTrue(transport.contains("isShitCoinPosition = executionLane == \"SHITCOIN\""))
    }

    @Test
    fun `terminal learning uses pre-close position and clears every matching book`() {
        val executor = File(root, "src/main/kotlin/com/lifecyclebot/engine/Executor.kt").readText()
        val paperClose = executor.substringAfter("V5.9.1133 — close paper position state")
            .substringBefore("fun liveSell(")
        assertTrue(paperClose.contains("val entryTimeSafeEdu = if (pos.entryTime"))
        assertTrue(paperClose.contains("tradingMode = pos.tradingMode"))
        assertTrue(paperClose.contains("pos.tradingMode == \"EXPRESS\""))
        assertTrue(paperClose.contains("pos.tradingMode == \"PROJECT_SNIPER\""))
        assertTrue(paperClose.contains("entryCostSol = pos.costSol"))
        assertTrue(paperClose.contains("entryScore = pos.entryScore"))
        assertFalse(paperClose.contains("book = TradeAuthorizer.ExecutionBook.CORE"))
    }

    @Test
    fun `strict replay recovers only provable immutable full terminals`() {
        val replay = File(root,
            "src/main/kotlin/com/lifecyclebot/engine/truth/JournalEconomicReplay6619.kt").readText()
        val recovery = replay.substringAfter("V5.0.6664 — historical canonical terminals")
            .substringBefore("if (lot == null) { reject")
        assertTrue(recovery.contains("side == \"SELL\""))
        assertTrue(recovery.contains("t.economicEventId.startsWith(\"paper_full_\")"))
        assertTrue(recovery.contains("recoveredRaw > java.math.BigInteger.ZERO"))
        assertTrue(recovery.contains("t.entryPriceSnapshot.isFinite()"))
        assertTrue(recovery.contains("cash -= basis"))
        assertTrue(recovery.contains("openCost += basis"))
        assertTrue(recovery.contains("JOURNAL_EMBEDDED_ENTRY_RECOVERED_6664"))
        assertFalse(recovery.contains("PARTIAL_SELL\" &&"))
    }
}
