package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.HeliusCreditEconomy7881
import com.lifecyclebot.engine.truth.HeliusSwapCandles7819
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8453 — owner: "raise the daily limit but reduce the amount of wasted data calls dramatically". The day is 400k
 * credits; the 100-credit Enhanced candle history is for held positions only; the whale-only wallets leave the
 * smart-money stream (8450: 242 MB, detected=0).
 */
class Aate8453HeliusSpendTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()

    @Test fun theDayIsRaisedAndEnrichmentStaysAQuarterOfIt() {
        assertEquals(400_000.0, HeliusCreditEconomy7881.DAILY_BUDGET_CREDITS, 0.0)
        assertFalse(HeliusCreditEconomy7881.enrichmentCapped8452(99_000.0, 100.0, HeliusCreditEconomy7881.DAILY_BUDGET_CREDITS, 1.0))
        assertTrue(HeliusCreditEconomy7881.enrichmentCapped8452(100_000.0, 100.0, HeliusCreditEconomy7881.DAILY_BUDGET_CREDITS, 1.0))
    }

    @Test fun enhancedCandleHistoryIsForHeldPositionsOnly() {
        assertTrue(HeliusSwapCandles7819.heliusCandlesAllowed8453(HeliusSwapCandles7819.PRIORITY_HELD_8284))
        assertFalse(HeliusSwapCandles7819.heliusCandlesAllowed8453(HeliusSwapCandles7819.PRIORITY_NEAR_DECISION_8284))
        assertFalse(HeliusSwapCandles7819.heliusCandlesAllowed8453(HeliusSwapCandles7819.PRIORITY_HANDOFF_8284))
        assertFalse(HeliusSwapCandles7819.heliusCandlesAllowed8453(0))
        val c = src("engine/truth/HeliusSwapCandles7819.kt")
        val req = c.substring(c.indexOf("fun request7819("), c.indexOf("/** Pure (V5.0.8453)"))
        assertTrue("checked after the free sources ran, before any credit is reserved",
            req.indexOf("heliusCandlesAllowed8453(priority)") in req.indexOf("heliusAfterFree8418(ts, nowMs, priority)") until req.indexOf("admission8386.reserve(mint, nowMs, priority)"))
    }

    @Test fun whaleOnlyWalletsLeaveTheSmartMoneyStream() {
        val s = src("engine/HeliusCadence8361.kt")
        val wl = s.substring(s.indexOf("fun watchList8361("), s.indexOf("fun copyWatched8361("))
        assertTrue(wl.contains("watchListOf8361(emptyList(), alpha, copyActive, ExpertWallets7962.OWNER_WALLET_7962)"))
        // the pure builder still orders whales first when given them (unchanged contract)
        assertEquals(listOf("W1", "A1"), HeliusCadence8361.watchListOf8361(listOf("W1"), listOf("A1"), emptyList(), "OWN"))
        assertEquals(listOf("A1"), HeliusCadence8361.watchListOf8361(emptyList(), listOf("A1"), emptyList(), "OWN"))
    }
}
