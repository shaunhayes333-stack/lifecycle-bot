package com.lifecyclebot.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** V5.0.8044 — a profit-lock slice's % is its realised SOL over its own cost, never proceeds over the whole cost. */
class Aate8044PartialPctTest {
    @Test fun theWjjkgmQuickTake() {
        // entryCost 0.03045, slice cost (trade.sol) 0.01827, realised +0.00825 SOL.
        val wrong = (0.01827 - 0.03045) / 0.03045 * 100.0
        assertEquals(-40.0, wrong, 0.1)
        val wholeCostPct = 0.00825 / 0.03045 * 100.0
        val slicePct = 0.00825 / 0.01827 * 100.0
        assertTrue(wholeCostPct > 0.0 && slicePct > wholeCostPct)
        assertEquals(slicePct, RealizedReturn8163.percent(0.00825, 0.01827, 0.03045, -40.0)!!, 1e-9)
        assertEquals(slicePct, RealizedReturn8163.percent(0.00825, 0.0, 0.03045, slicePct)!!, 1e-9)
    }
}

class Aate8044FullWinTest {
    @Test fun aBigFirstSpikeBanksAllButTheMoonbag() {
        val sel = com.lifecyclebot.engine.truth.FreshLaunchSelector7737
        assertEquals(0.75, sel.quickTakeFraction(156.0, 0), 1e-9)
        assertEquals(0.75, sel.quickTakeFraction(sel.QUICK_TAKE_FULL_WIN_PCT_8044, 0), 1e-9)
        // Below +100% the first take is unchanged; the second step is unchanged.
        assertEquals(0.60, sel.quickTakeFraction(55.0, 0), 1e-9)
        assertEquals(0.50, sel.quickTakeFraction(120.0, 1), 1e-9)
    }
}
