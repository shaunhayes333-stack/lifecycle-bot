package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.HeliusCreditEconomy7881
import com.lifecyclebot.engine.truth.HeliusSwapCandles7819
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 8269 — no mint-as-market address, no old curve after migration, no silent
 * 10-credit estimate for a 100-credit transaction-history request, and no
 * renewed Helius logs subscription on every cold watchlist intake.
 */
class Aate8269HeliusFirstTest {
    private fun src(path: String): String =
        File("src/main/kotlin/com/lifecyclebot/$path").readText()

    private val mint = "11111111111111111111111111111111"
    private val curve = "6EF8rrecthR5Dkzon8Nwu78hRvfCKubJ14M5uBEwF6P"
    private val pool = "So11111111111111111111111111111111111111112"

    @Test fun useRealMarketAccountAndNeverFallbackToDeadGraduatedCurve() {
        val h = HeliusSwapCandles7819
        assertEquals(curve, h.historyAddress8269(mint, pool, curve, false))
        assertEquals(pool, h.historyAddress8269(mint, pool, curve, true))
        assertEquals(pool, h.historyAddress8269(mint, pool, "", false))
        assertNull(h.historyAddress8269(mint, "", curve, true))
        assertNull(h.historyAddress8269(mint, mint, "", false))
        assertNull(h.historyAddress8269(mint, "UNRESOLVED", "", false))
    }

    @Test fun documentedHeliusHistoryCreditsAreNotUnderstated() {
        val host = "mainnet.helius-rpc.com"
        assertEquals(100.0, HeliusCreditEconomy7881.creditsFor(host, "/", listOf("getTransactionsForAddress")), 0.0)
        assertEquals(10.0, HeliusCreditEconomy7881.creditsFor(host, "/", listOf("getTransfersByAddress")), 0.0)
        assertEquals(100.0, HeliusCreditEconomy7881.creditsFor("api.helius.xyz", "/v0/addresses/a/transactions", emptyList()), 0.0)
    }

    @Test fun onlyFreshNearDecisionMintsAreOfferedFocusSlots() {
        DataPipeline8191.resetForTests8191()
        val now = 1_800_000L
        DataPipeline8191.noteNearDecision8191("expired", now - DataPipeline8191.NEAR_DECISION_MS_8191 - 1L)
        DataPipeline8191.noteNearDecision8191("older", now - 2_000L)
        DataPipeline8191.noteNearDecision8191("newer", now - 500L)
        assertEquals(listOf("newer", "older"), DataPipeline8191.recentNearDecisionMints8269(6, now))
        assertEquals(listOf("newer"), DataPipeline8191.recentNearDecisionMints8269(1, now))
        assertTrue(DataPipeline8191.recentNearDecisionMints8269(0, now).isEmpty())
        DataPipeline8191.resetForTests8191()
    }

    @Test fun onChainBackfillIsPagedAndWakesMissingShapeOnlyAfterRealBars() {
        val h = src("engine/truth/HeliusSwapCandles7819.kt")
        assertTrue(h.contains("activeMarketAddress8269(ts)"))
        assertTrue(h.contains("$" + "BASE/$" + "marketAddress/transactions"))
        assertTrue(h.contains("&before-signature=$" + "before"))
        assertTrue(h.contains("&gte-time="))
        assertFalse(h.contains("$" + "BASE/" + "$" + "{ts.mint}/transactions"))
        assertTrue(h.contains("if (merged > 0) try {"))
        assertTrue(h.contains("evidenceArrived8252(ts.mint, setOf(\"shape\")"))
    }

    @Test fun persisted8192EventReplayDoesNotScanWholeJournalForEverySell() {
        val journal = src("engine/TradeHistoryStore.kt")
        assertFalse(journal.contains("trades.asReversed().firstOrNull"))
        assertTrue(journal.contains("lastBuyByModeAndMint8269[buyLookupKey8269(t)]"))
        assertTrue(journal.contains("lastNamedBuyByMint8269[trade.mint]?.tradingMode"))
        assertTrue(journal.contains("trades.addAll(enrichedLoaded)"))
        assertTrue(journal.contains("rebuildBuyIndex8269()"))
        assertTrue(journal.contains("rememberBuy8269(tradeToStore)"))
        assertTrue(journal.contains("discardTrimmedBuys8269(evicted8269)"))
        val smoke = File("../ci/runtime-test.sh").readText()
        assertTrue(smoke.contains("Android background-run permission prompt overlay"))
        assertTrue(smoke.contains("ui_find_target_7168"))
    }

    @Test fun websocketFocusReplacesFirehoseChurnAndRiskUsesSameExplorationSlot() {
        val orch = src("engine/DataOrchestrator.kt")
        assertTrue(orch.contains("if (DataPipeline8191.seedPriority8191(mint)) heliusWs?.subscribeToken(mint)"))
        assertTrue(orch.contains("rotateHeliusFocus8269(now)"))
        assertTrue(orch.contains("socket.releaseTokens7948 { it in held || it in focus }"))
        val promotion = src("engine/truth/MinPromotion8188.kt")
        assertTrue(promotion.contains("explorationSlotFree = explorationFree"))
        assertTrue(promotion.contains("LaneScoreAdmission7308.explorationSlotFreeNow({ false })"))
    }
}
