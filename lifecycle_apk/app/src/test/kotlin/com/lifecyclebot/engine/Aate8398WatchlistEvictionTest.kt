package com.lifecyclebot.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V5.0.8398 — the hot bench keeps undecided coins long enough to observe their core inputs, and evicts the oldest
 * decided resident instead of the newest unevaluated one (runtime 5.0.8397: every victim was 60-131 s old, pc 0-1).
 */
class Aate8398WatchlistEvictionTest {
    private val now = 1_000_000_000L
    private fun row(mint: String, ageMs: Long, pc: Int, lastMs: Long = now - 45_000L, src: String = "DEX_TRENDING", open: Boolean = false) =
        WatchlistEviction8398.Row(mint, src, now - ageMs, if (pc == 0) 0L else lastMs, pc, open)

    @Test
    fun `undecided coin at 61s with one cycle is protected and the old decided veteran goes first`() {
        val rows = listOf(
            row("fresh1", 61_000L, 1),
            row("fresh0", 131_000L, 0),
            row("veteran", 1_800_000L, 48),
            row("decidedYoung", 200_000L, 3),
        )
        val pool = WatchlistEviction8398.pool8398(rows, now, incomingMint = "incoming")
        assertEquals(2, pool.protectedUndecided)
        assertEquals(listOf("veteran", "decidedYoung"), pool.evictable.map { it.mint })
        assertEquals("veteran", WatchlistEviction8398.victim8398(pool, false) { false }?.mint)
    }

    @Test
    fun `undecided protection is bounded by the five minute ceiling and never admits the incoming or an open coin`() {
        val rows = listOf(
            row("stuck", 301_000L, 0),           // never completed a cycle, past the ceiling: evictable
            row("open", 900_000L, 9, open = true),
            row("incoming", 900_000L, 9),
            row("young", 20_000L, 0),            // under the 60 s admission floor
            row("working", 400_000L, 7, lastMs = now - 5_000L),  // touched 5 s ago
        )
        val pool = WatchlistEviction8398.pool8398(rows, now, incomingMint = "incoming")
        assertEquals(listOf("stuck"), pool.evictable.map { it.mint })
        assertEquals(0, pool.protectedUndecided)   // "young" is under the admission floor, counted by 6598 not 8398
        assertTrue(WatchlistEviction8398.undecided8398(row("x", 299_000L, 2), now))
        assertFalse(WatchlistEviction8398.undecided8398(row("x", 299_000L, 3), now))
    }

    @Test
    fun `all residents protected yields no victim so the 7735 probation route takes the incoming coin`() {
        val rows = (1..5).map { row("m$it", 60_000L + it * 1_000L, 1) }
        val pool = WatchlistEviction8398.pool8398(rows, now, incomingMint = "incoming")
        assertNull(WatchlistEviction8398.victim8398(pool, true) { true })
        assertEquals(5, pool.protectedUndecided)
    }

    @Test
    fun `pump share rule still picks a pump coin first among the evictable`() {
        val rows = listOf(row("dex", 500_000L, 5, src = "DEX_TRENDING"), row("pump", 400_000L, 5, src = "PUMP_FUN"))
        val pool = WatchlistEviction8398.pool8398(rows, now, incomingMint = "incoming")
        assertEquals("pump", WatchlistEviction8398.victim8398(pool, true) { it == "PUMP_FUN" }?.mint)
        assertEquals("dex", WatchlistEviction8398.victim8398(pool, false) { it == "PUMP_FUN" }?.mint)
    }

    @Test
    fun `registry eviction reads the 8398 pool instead of the lowest process count order`() {
        val src = File("src/main/kotlin/com/lifecyclebot/engine/GlobalTradeRegistry.kt").readText()
        assertTrue(src.contains("WatchlistEviction8398.pool8398("))
        assertTrue(src.contains("WatchlistEviction8398.victim8398(pool8398, pumpOver8029)"))
        assertTrue(src.contains("WATCHLIST_EVICT_UNDECIDED_PROTECTED_8398"))
        assertFalse(src.contains("compareBy<WatchlistEntry> { it.processCount }"))
    }
}
