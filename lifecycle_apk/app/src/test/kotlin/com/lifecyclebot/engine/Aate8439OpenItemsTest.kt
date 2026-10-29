package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.DiscoveryCapture8400
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8439 — the 8438 report's open items: birth reads no longer lock every RPC on one free-node 429; a shadow exit waits
 * for the next observed mark instead of becoming a missing Cortex outcome; market-cap misses name their cause; label
 * recovery yields GeckoTerminal to live reads; the brief separates runner-carried means from edge.
 */
class Aate8439OpenItemsTest {
    private fun src(p: String) = File("src/main/kotlin/com/lifecyclebot/$p").readText()

    @Test fun birthReadsUseEachRungsOwnHealthLabel() {
        val h = src("engine/truth/TokenBirthHydrator7441.kt")
        assertFalse("no shared label on any birth read", h.contains("HealthAwareHttp.execute(http, req, host = \"solana_rpc\")"))
        assertEquals(3, Regex("HealthAwareHttp\\.execute\\(http, req, host = rpcLabel8439\\(rpc\\)\\)").findAll(h).count())
        assertEquals("helius", com.lifecyclebot.network.KeylessRpc8359.label8359("https://mainnet.helius-rpc.com/?api-key=x"))
        assertEquals("rpc_api.mainnet-beta.solana.com", com.lifecyclebot.network.KeylessRpc8359.label8359("https://api.mainnet-beta.solana.com"))
    }

    @Test fun aShadowExitWithoutAFreshMarkWaitsForTheNextOne() {
        val s = src("engine/truth/ExploreShadow8347.kt")
        assertTrue(s.contains("if (why == \"SOLD_OUT\" || why == \"LOST_MARK\" || lost) {"))
        assertFalse("px == null no longer closes the leg unavailable on the spot", s.contains("if (why == \"SOLD_OUT\" || why == \"LOST_MARK\" || px == null) {"))
        assertTrue(s.contains("SHADOW_EXIT_WAITS_FOR_MARK_8439_\$why"))
    }

    @Test fun aMarketCapMissNamesItsCause() {
        assertEquals("NO_PRICE", DiscoveryCapture8400.missWhy8439(0.0, "DEXSCREENER", 1e9))
        assertEquals("PRICE_NOT_INDEPENDENT", DiscoveryCapture8400.missWhy8439(0.0001, "PUMP_FUN_BC_SYNTHETIC", 1e9))
        assertEquals("NO_VERIFIED_SUPPLY", DiscoveryCapture8400.missWhy8439(0.0001, "DEXSCREENER", 0.0))
        assertEquals("OTHER", DiscoveryCapture8400.missWhy8439(0.0001, "DEXSCREENER", 1e9))
        assertTrue(DiscoveryCapture8400.statusLine().contains("unresolvedWhy8439{"))
    }

    @Test fun labelRecoveryYieldsTheCandleBudgetToLiveReads() {
        val d = src("engine/truth/DowntimeRecovery8430.kt")
        assertTrue(d.contains("GeckoHostBudget8382.spare8439(nowMs)"))
        assertTrue(com.lifecyclebot.engine.truth.DowntimeRecovery8430.statusLine8430().contains("yieldedToLive8439="))
        assertEquals(6, com.lifecyclebot.engine.truth.DowntimeRecovery8430.counts8438().size)
    }

    @Test fun theBriefSeparatesRunnerCarriedMeansFromEdge() {
        // 8438's sniper / dip-hunter / treasury rows: median coin loses, runners must carry several times the mean
        assertTrue(DiagnosisBrief8438.tailOnly8439(43.8, 0.17, 0.09))
        assertTrue(DiagnosisBrief8438.tailOnly8439(38.7, 0.35, 0.03))
        assertFalse("shitcoin: the runners need not carry 3x", DiagnosisBrief8438.tailOnly8439(20.7, 0.44, 0.18))
        assertFalse("a winning median is never tail-only", DiagnosisBrief8438.tailOnly8439(10.0, 0.60, 0.05))
        assertEquals("LEAK_TAIL_ONLY", DiagnosisBrief8438.laneVerdict8438(1027, 43.8, 0L, 0, 0.0, 0.17, 0.09))
        assertEquals("LEAK_EDGE_NOT_BOUGHT", DiagnosisBrief8438.laneVerdict8438(1192, 20.7, 0L, 0, 0.0, 0.44, 0.18))
        val b = src("engine/DiagnosisBrief8438.kt")
        assertTrue(b.contains("PumpFunWS.keyedStreamDenied8439()"))
        assertTrue(b.contains("CONFLICT means separate but the REFUSED side holds more runners"))
    }
}
