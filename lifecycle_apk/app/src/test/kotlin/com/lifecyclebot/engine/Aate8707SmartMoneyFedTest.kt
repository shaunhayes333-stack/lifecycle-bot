package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.ObservedRunners8707
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8707 — 8704: Smart Money detected=0, alpha list 0/25, buysSeen=0; ownerTrades=137, learned=0. The miner read its
 * runners from the bot's own winning trades (there are none) and the owner's trades were queued, never booked.
 */
class Aate8707SmartMoneyFedTest {
    private val a = "3nN4GVY7eS9JwWiiTFwzJBMTCv6rD8a2gofADeq6Qm63"
    private val b = "HpJsv8sjq5rqJ8yVbEfoJUNj2DMrasP36D3Kj2DDJUXm"
    private val c = "2uqXwpNMXpiDzrJMGu2NVyszzteAbUHVx1GtFpBupump"

    @Test fun theMinerIsFedTheRunnersTheLabelerWatched() {
        ObservedRunners8707.resetForTest8707()
        assertFalse(ObservedRunners8707.qualifies8707(99.9))
        assertTrue(ObservedRunners8707.qualifies8707(100.0))
        ObservedRunners8707.note8707(a, 60.0, true, 1L)        // not a runner
        ObservedRunners8707.note8707(b, 900.0, false, 2L)
        ObservedRunners8707.note8707(c, 150.0, true, 3L)
        ObservedRunners8707.note8707("short", 500.0, true, 4L)  // not a mint
        assertEquals("chain-witnessed first, then the largest", listOf(c, b), ObservedRunners8707.mints8707())
        ObservedRunners8707.note8707(b, 300.0, true, 5L)
        assertEquals("a later witness upgrades the row and keeps its best gross", listOf(b, c), ObservedRunners8707.mints8707())
        val miner = src("engine/SmartMoneyDiscovery7277.kt")
        assertTrue(miner.contains("out.addAll(com.lifecyclebot.engine.truth.ObservedRunners8707.mints8707())"))
        assertFalse("mining is no longer once a day", miner.contains("if (!HeliusCadence8361.alphaRebuildDue8361(lastMineMs8361, now8361)) return"))
        assertTrue(src("engine/truth/ForwardReturnLabeler7731.kt").contains("ObservedRunners8707.note8707(o.mint, gross0, w8324, nowMs)"))
    }

    @Test fun anExpertTripIsReceiptedOnlyAfterItIsBooked() {
        assertTrue(ExpertWallets7962.receiptsStale8707(0L, 0))
        assertFalse(ExpertWallets7962.receiptsStale8707(12L, 0))
        assertFalse(ExpertWallets7962.receiptsStale8707(0L, 3))
        val x = src("engine/ExpertWallets7962.kt")
        val fn = x.substringAfter("private fun learnPending() {").substringBefore("/** Pure: are per-wallet receipts stale")
        assertTrue(fn.contains("val booked = try { learnTrip(tier, trip, useBars, created, solUsd); true } catch (_: Throwable) { false }"))
        assertTrue(fn.indexOf("if (!booked)") < fn.indexOf("h.learned += "))
        assertFalse("the second ledger no longer spends the id before the trip is booked", fn.contains("LearnerAttribution8418.admit8418("))
        assertTrue(fn.contains("while (System.currentTimeMillis() < deadline) {"))
        assertFalse(x.contains("MINTS_PER_CYCLE = 4"))
    }

    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()
}
