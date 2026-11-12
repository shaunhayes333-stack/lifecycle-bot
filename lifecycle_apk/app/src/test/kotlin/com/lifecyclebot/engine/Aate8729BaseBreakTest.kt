package com.lifecyclebot.engine

import com.lifecyclebot.engine.truth.TradeTape8100.Print8128
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** V5.0.8729 — a base breaking out on real buying is detected at the break, not after the run. */
class Aate8729BaseBreakTest {
    private val now = 10_000_000L
    private fun p(agoMs: Long, px: Double, sol: Double = 0.2, buy: Boolean = true, priceOnly: Boolean = false) =
        Print8128(now - agoMs, px, if (priceOnly) 0.0 else sol, buy, "test", "pool", priceOnly)

    /** Four minutes holding 1.00-1.15, then [leg] in the last 45 s. */
    private fun tape(leg: List<Print8128>): List<Print8128> =
        (0 until 12).map { i -> p(240_000L - i * 15_000L, if (i % 2 == 0) 1.00 else 1.15, buy = i % 3 != 0) } + leg

    @Test fun theFirstLegOutOfTheBaseIsTheEntry() {
        val r = RunTape8293.detectBreak8729(tape(listOf(p(30_000, 1.17), p(20_000, 1.20), p(10_000, 1.21, sol = 0.4), p(3_000, 1.22))), now)
        assertNotNull(r)
        assertEquals(RunTape8293.KIND_BREAK_8729, r!!.kind)
        assertTrue("entered within the first leg: ${r.risePct}", r.risePct in 6.0..6.2)
        assertTrue(r.evidence().startsWith("BASE_BREAK_8729 "))
        // the run detector does not fire on the same tape: the coin has not run 15 % yet
        assertNull(RunTape8293.detect8293(tape(listOf(p(30_000, 1.17), p(20_000, 1.20), p(10_000, 1.21, sol = 0.4), p(3_000, 1.22))), now))
    }

    @Test fun whatIsNotABreak() {
        // already 20 % above the base: that is the run, not the break
        assertNull(RunTape8293.detectBreak8729(tape(listOf(p(30_000, 1.25), p(20_000, 1.32), p(10_000, 1.36, sol = 0.4), p(3_000, 1.38))), now))
        // sellers carry the leg
        assertNull(RunTape8293.detectBreak8729(tape(listOf(p(30_000, 1.17, sol = 2.0, buy = false), p(20_000, 1.20), p(10_000, 1.21), p(3_000, 1.22))), now))
        // a price poke with no executed buying (curve reads only)
        assertNull(RunTape8293.detectBreak8729(tape(listOf(p(20_000, 1.20, priceOnly = true), p(3_000, 1.22, priceOnly = true))), now))
        // no base: the range before the leg is 60 % wide
        val wild = (0 until 12).map { i -> p(240_000L - i * 15_000L, if (i % 2 == 0) 1.00 else 1.60) } +
            listOf(p(30_000, 1.62), p(20_000, 1.64), p(10_000, 1.65, sol = 0.4), p(3_000, 1.66))
        assertNull(RunTape8293.detectBreak8729(wild, now))
        // stale: the newest print is older than the freshness window
        assertNull(RunTape8293.detectBreak8729(tape(listOf(p(44_000, 1.17), p(43_000, 1.20), p(42_000, 1.21, sol = 0.4), p(41_000, 1.22))), now))
    }
}
