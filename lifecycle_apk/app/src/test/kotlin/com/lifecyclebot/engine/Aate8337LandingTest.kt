package com.lifecyclebot.engine

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8337 — FIX 5: buys that land. 8151 trimmed fee / tip / slippage on "ordinary" entries and PumpPortal buys
 * landed 3/18 (Jupiter 25/25). Every entry pays to land again (entryOrdinary8151 = false), the PumpPortal landing
 * window judges the route on 3 resolved buys over 2 h, the fresh blockhash is read in parallel with the PumpPortal
 * build through a 2 s cache, and the 900 ms preflight simulation is skipped for a non-ordinary entry.
 */
class Aate8337LandingTest {
    private fun src(rel: String) = File("src/main/kotlin/com/lifecyclebot/$rel").readText()

    @After fun back() { PumpLanding8288.resetBlockhashCacheForTest8337() }

    @Test fun every_entry_pays_to_land() {
        val exec = src("engine/Executor.kt")
        assertTrue(exec.contains("private fun entryOrdinary8151(mint: String): Boolean = false"))
        assertFalse("no mint is ordinary any more", exec.contains("private fun entryOrdinary8151(mint: String): Boolean = try {"))
        // the 8151 consumers stay wired to it, so URGENT tip / 10 % PumpPortal slip / high priority follow from the one switch
        assertTrue(exec.contains("if (!entryOrdinary8151(ts.mint)) return 10"))
        assertTrue(exec.contains("ordinary8151    = entryOrdinary8151(ts.mint)"))
        assertTrue(exec.contains("ordinary = entryOrdinary8151(ts.mint), entry = true"))
    }

    @Test fun landing_window_is_two_hours_and_three_resolved_buys_judge_the_route() {
        assertEquals(120L * 60_000L, PumpLanding8288.WINDOW_MS_8288)
        assertEquals(3, PumpLanding8288.MIN_RESOLVED_8288)
        // three resolved, one landed -> routed away; two resolved -> too few to judge
        assertTrue(PumpLanding8288.routeAway8288(PumpLanding8288.Rate8288(3, 1)))
        assertFalse(PumpLanding8288.routeAway8288(PumpLanding8288.Rate8288(2, 0)))
        val now = 10L * 3_600_000L
        val rows = listOf(
            PumpLanding8288.Row8288("BUY", "PUMPPORTAL", "EXPIRED_PROVEN", now - 100L * 60_000L),   // inside 2 h, outside the old 20 min
            PumpLanding8288.Row8288("BUY", "PUMPPORTAL", "EXPIRED_PROVEN", now - 50L * 60_000L),
            PumpLanding8288.Row8288("BUY", "PUMPPORTAL", "CONFIRMED", now - 5L * 60_000L),
            PumpLanding8288.Row8288("BUY", "PUMPPORTAL", "FAILED_ON_CHAIN", now - 130L * 60_000L),  // aged out
        )
        val r = PumpLanding8288.pumpBuyRate8288(rows, now)
        assertEquals(3, r.resolved)
        assertEquals(1, r.landed)
        assertTrue(PumpLanding8288.routeAway8288(r))
    }

    @Test fun blockhash_cache_serves_two_seconds_then_refreshes() {
        PumpLanding8288.resetBlockhashCacheForTest8337()
        assertEquals(2_000L, PumpLanding8288.BLOCKHASH_CACHE_MS_8337)
        assertEquals("", PumpLanding8288.cachedBlockhash8337(1_000L))
        PumpLanding8288.noteBlockhash8337("", 1_000L)                       // a blank read notes nothing
        assertEquals("", PumpLanding8288.cachedBlockhash8337(1_000L))
        PumpLanding8288.noteBlockhash8337("HASH_A", 1_000L)
        assertEquals("HASH_A", PumpLanding8288.cachedBlockhash8337(1_000L))
        assertEquals("HASH_A", PumpLanding8288.cachedBlockhash8337(3_000L))  // exactly 2 s: still inside the window
        assertEquals("", PumpLanding8288.cachedBlockhash8337(3_001L))        // after the window: a fresh read is due
        assertEquals("", PumpLanding8288.cachedBlockhash8337(500L))          // a clock that went backwards never serves the cache
        PumpLanding8288.noteBlockhash8337("HASH_B", 3_001L)                  // the refresh replaces it
        assertEquals("HASH_B", PumpLanding8288.cachedBlockhash8337(4_000L))
    }

    @Test fun pumpportal_buy_reads_the_blockhash_in_parallel_and_skips_the_sim_when_not_ordinary() {
        val exec = src("engine/Executor.kt")
        val build = exec.indexOf("val built = com.lifecyclebot.network.PumpFunDirectApi.buildBuyTx(")
        val prefetch = exec.indexOf("PumpLanding8288.prefetchBlockhash8337(wallet)")
        val freshen = exec.indexOf("val pumpTx8288 = PumpLanding8288.freshen8288(wallet, built.txBase64)")
        assertTrue(build > 0 && prefetch > 0 && freshen > 0)
        assertTrue("the fresh blockhash read starts before the PumpPortal build", prefetch < build)
        assertTrue("and is consumed after it", build < freshen)
        assertTrue(exec.contains("if (entryOrdinary8151(ts.mint) && costPumpBuySimRefused8151(ts, built.txBase64, wallet, tradeKey)) return null"))
        assertTrue(exec.contains("if (!entryOrdinary8151(ts.mint)) return false"))   // the helper's own guard stays
        val pl = src("engine/PumpLanding8288.kt")
        assertTrue(pl.contains("val hash = freshHash8337(wallet)"))
        assertTrue(pl.contains("val cached = cachedBlockhash8337(System.currentTimeMillis())"))
        assertTrue(pl.contains("inflight8337 = submitRead8337(wallet)"))
    }
}
