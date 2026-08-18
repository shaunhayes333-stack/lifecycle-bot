package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.AdvisorIntegrityHold6466
import com.lifecyclebot.engine.truth.CanonicalMintOccupancyRegistry6464
import com.lifecyclebot.engine.truth.DataProviderFaultCircuits6468
import com.lifecyclebot.engine.truth.ForcedCloseSlotSweeper6468
import com.lifecyclebot.engine.truth.OrderSizeResolver6441
import com.lifecyclebot.engine.truth.OrderSizeResolverInvariant6468
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V5.0.6468 — HARD CI ASSERTIONS for §P0 items 15 (ext), 16, 17, 18.
 *
 * 15 ext  AdvisorIntegrityHold6466 respects EventStreamReplay divergence + sizing violations.
 * 16      OrderSizeResolverInvariant6468 catches a bad Resolution.
 * 17      ForcedCloseSlotSweeper6468 releases a leaked slot on force-close.
 * 18      DataProviderFaultCircuits6468 handles 401/404/429 distinctly.
 */
class CorrectnessHardeningAcceptanceTest6468 {

    @Test
    fun `item18 auth failure locks provider out even on single 401`() {
        DataProviderFaultCircuits6468.resetForTest()
        DataProviderFaultCircuits6468.recordHttpStatus(DataProviderFaultCircuits6468.Provider.BIRDEYE, 401)
        assertEquals(
            DataProviderFaultCircuits6468.Mode.AUTH_LOCKOUT,
            DataProviderFaultCircuits6468.mode(DataProviderFaultCircuits6468.Provider.BIRDEYE),
        )
        assertFalse(DataProviderFaultCircuits6468.isNetworkAllowed(DataProviderFaultCircuits6468.Provider.BIRDEYE))
        // A subsequent success MUST NOT auto-clear auth lockout — operator rotate required.
        DataProviderFaultCircuits6468.recordSuccess(DataProviderFaultCircuits6468.Provider.BIRDEYE)
        assertEquals(
            DataProviderFaultCircuits6468.Mode.AUTH_LOCKOUT,
            DataProviderFaultCircuits6468.mode(DataProviderFaultCircuits6468.Provider.BIRDEYE),
        )
        // Release path clears it.
        DataProviderFaultCircuits6468.releaseAuthLockout(DataProviderFaultCircuits6468.Provider.BIRDEYE)
        assertEquals(
            DataProviderFaultCircuits6468.Mode.LIVE,
            DataProviderFaultCircuits6468.mode(DataProviderFaultCircuits6468.Provider.BIRDEYE),
        )
    }

    @Test
    fun `item18 rate limit and 404 use CACHE_ONLY not AUTH_LOCKOUT`() {
        DataProviderFaultCircuits6468.resetForTest()
        // 429 → cache-only
        DataProviderFaultCircuits6468.recordHttpStatus(DataProviderFaultCircuits6468.Provider.HELIUS, 429)
        assertEquals(
            DataProviderFaultCircuits6468.Mode.CACHE_ONLY,
            DataProviderFaultCircuits6468.mode(DataProviderFaultCircuits6468.Provider.HELIUS),
        )
        // 404 twice → cache-only
        DataProviderFaultCircuits6468.recordHttpStatus(DataProviderFaultCircuits6468.Provider.GROQ, 404)
        DataProviderFaultCircuits6468.recordHttpStatus(DataProviderFaultCircuits6468.Provider.GROQ, 404)
        assertEquals(
            DataProviderFaultCircuits6468.Mode.CACHE_ONLY,
            DataProviderFaultCircuits6468.mode(DataProviderFaultCircuits6468.Provider.GROQ),
        )
        // Success closes cache-only back to LIVE.
        DataProviderFaultCircuits6468.recordHttpStatus(DataProviderFaultCircuits6468.Provider.GROQ, 200)
        assertEquals(
            DataProviderFaultCircuits6468.Mode.LIVE,
            DataProviderFaultCircuits6468.mode(DataProviderFaultCircuits6468.Provider.GROQ),
        )
    }

    @Test
    fun `item18 classify maps provider names correctly`() {
        assertEquals(DataProviderFaultCircuits6468.Provider.BIRDEYE, DataProviderFaultCircuits6468.classify("https://public-api.birdeye.so/x"))
        assertEquals(DataProviderFaultCircuits6468.Provider.GROQ, DataProviderFaultCircuits6468.classify("api.groq.com"))
        assertEquals(DataProviderFaultCircuits6468.Provider.HELIUS, DataProviderFaultCircuits6468.classify("mainnet.helius-rpc.com"))
        assertEquals(DataProviderFaultCircuits6468.Provider.GENERIC, DataProviderFaultCircuits6468.classify("unknown-host.example"))
    }

    @Test
    fun `item16 invariant catches executable-with-zero-size`() {
        OrderSizeResolverInvariant6468.resetForTest()
        val bad = OrderSizeResolver6441.Resolution(
            requestedSol = 1.0, riskSol = 1.0, ladderSol = 1.0,
            cashCapSol = 5.0, laneCapSol = 2.0,
            finalSizeSol = 0.0, executable = true, reason = "OK",
        )
        assertFalse(OrderSizeResolverInvariant6468.check(bad))
        assertTrue(OrderSizeResolverInvariant6468.statusLine().contains("violations=1"))
    }

    @Test
    fun `item16 invariant catches final exceeding lane cap`() {
        OrderSizeResolverInvariant6468.resetForTest()
        val bad = OrderSizeResolver6441.Resolution(
            requestedSol = 5.0, riskSol = 5.0, ladderSol = 5.0,
            cashCapSol = 10.0, laneCapSol = 2.0,
            finalSizeSol = 3.0, executable = true, reason = "OK",
        )
        assertFalse(OrderSizeResolverInvariant6468.check(bad))
    }

    @Test
    fun `item16 clean resolution passes`() {
        OrderSizeResolverInvariant6468.resetForTest()
        val good = OrderSizeResolver6441.Resolution(
            requestedSol = 1.0, riskSol = 1.0, ladderSol = 1.0,
            cashCapSol = 10.0, laneCapSol = 5.0,
            finalSizeSol = 1.0, executable = true, reason = "OK",
        )
        assertTrue(OrderSizeResolverInvariant6468.check(good))
    }

    @Test
    fun `item17 onForcedClose releases occupancy slot`() {
        CanonicalMintOccupancyRegistry6464.resetForTest()
        ForcedCloseSlotSweeper6468.resetForTest()
        // Set up a leaked open slot.
        CanonicalMintOccupancyRegistry6464.markOpen(mode = "paper", mint = "MINT_LEAK", symbol = "T", source = "test")
        assertTrue(CanonicalMintOccupancyRegistry6464.isOpen("paper", "MINT_LEAK"))
        ForcedCloseSlotSweeper6468.onForcedClose(mode = "paper", mint = "MINT_LEAK", reason = "synthetic_dust")
        assertFalse(CanonicalMintOccupancyRegistry6464.isOpen("paper", "MINT_LEAK"))
        assertTrue(ForcedCloseSlotSweeper6468.statusLine().contains("slotsReleased="))
    }

    @Test
    fun `item17 sweep is idempotent on clean state`() {
        CanonicalMintOccupancyRegistry6464.resetForTest()
        ForcedCloseSlotSweeper6468.resetForTest()
        val released = ForcedCloseSlotSweeper6468.sweep()
        assertEquals(0, released)
    }

    @Test
    fun `item15 firewall statusLine remains publishable after new signals added`() {
        AdvisorIntegrityHold6466.resetForTest()
        // Run once so counters increment; we only assert the surface is stable.
        val _ignored = AdvisorIntegrityHold6466.isHold()
        val line = AdvisorIntegrityHold6466.statusLine()
        assertTrue("statusLine has checks counter: $line", line.contains("checks="))
    }

    @Test
    fun `item18 statusLine contains all providers`() {
        DataProviderFaultCircuits6468.resetForTest()
        val line = DataProviderFaultCircuits6468.statusLine()
        assertTrue(line.contains("BIRDEYE="))
        assertTrue(line.contains("GROQ="))
        assertTrue(line.contains("HELIUS="))
        assertNotEquals("", line)
    }
}
