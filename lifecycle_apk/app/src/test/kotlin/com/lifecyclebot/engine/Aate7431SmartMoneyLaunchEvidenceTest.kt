package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.SmartMoneyFeed6394
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

class Aate7431SmartMoneyLaunchEvidenceTest {

    @Test fun smartMoneyClusterCountsDistinctWalletsNotTransactions() {
        SmartMoneyFeed6394.clearForTest()
        val now = 1_000_000L
        SmartMoneyFeed6394.onWhaleBuy("mintA", "wallet1", now)
        SmartMoneyFeed6394.onWhaleBuy("mintA", "wallet1", now - 1_000L)
        assertEquals(1, SmartMoneyFeed6394.smartMoneyBuysLast60s("mintA", now))

        SmartMoneyFeed6394.onWhaleBuy("mintA", "wallet2", now - 2_000L)
        assertEquals(2, SmartMoneyFeed6394.smartMoneyBuysLast60s("mintA", now))
    }

    @Test fun realCopyBuyFeedsLaunchCluster() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/CopyTradeEngine.kt").readText()
        val event = src.indexOf("val signal = CopySignal")
        val write = src.indexOf("SmartMoneyFeed6394.onWhaleBuy", event)
        val callback = src.indexOf("onCopySignal(mint, buyerWallet, solAmount)", event)
        assertTrue(event >= 0)
        assertTrue(write > event)
        assertTrue(callback > write)
        assertTrue(src.contains("SMART_MONEY_FEED_BUY_WRITTEN_7431"))
    }

    @Test fun fdgConsumesClusterThroughCanonicalDecisionPath() {
        val fdg = File("src/main/kotlin/com/lifecyclebot/engine/FinalDecisionGate.kt").readText()
        assertTrue(fdg.contains("EarlyLaunchBypass6396.evaluateForCanonicalEntry("))
        val bypass = File("src/main/kotlin/com/lifecyclebot/engine/truth/EarlyLaunchBypass6396.kt").readText()
        assertTrue(bypass.contains("SmartMoneyFeed6394.smartMoneyBuysLast60s(mint)"))
        assertTrue(bypass.contains("SMART_MONEY_EARLY_REDUCED_SIZE_7431"))
        assertTrue(bypass.contains("whaleBuys < 2"))
    }

    @Test fun smartMoneyDiscoveryAndCopyBridgeAreProductionWired() {
        val bot = File("src/main/kotlin/com/lifecyclebot/engine/BotService.kt").readText()
        assertTrue(bot.contains("SmartMoneyDiscovery7277.start("))
        assertTrue(bot.contains("InsiderCopyEngine.copyBuyFromSmartMoney7277("))
        assertTrue(bot.contains("copyTradeEngine.onSwapDetected("))
        assertTrue(bot.contains("HeliusPushSwapParser7277.detectBuys("))
    }
    @Test fun paperAndLiveShareCanonicalEdgeDecision() {
        val fdg = File("src/main/kotlin/com/lifecyclebot/engine/FinalDecisionGate.kt").readText()
        val start = fdg.indexOf("if (blockReason == null && edgeVerdict == EdgeVerdict.SKIP)")
        val end = fdg.indexOf("var narrativeAdjustment", start)
        assertTrue(start >= 0 && end > start)
        val edge = fdg.substring(start, end)
        assertTrue(edge.contains("canonical PAPER/LIVE edge parity"))
        assertTrue(edge.contains("SMART_MONEY_EARLY_ENTRY_REDUCED_SIZE_7431"))
        assertFalse(edge.contains("PAPER BOOTSTRAP PROBE"))
        assertFalse(edge.contains("edge_veto_softened_paper"))
        assertFalse(edge.contains("if (config.paperMode)"))
    }

}
